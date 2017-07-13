/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.mllib.recommendation

import java.io.IOException
import java.lang.{Integer => JavaInteger}

import scala.collection.mutable

import com.clearspring.analytics.stream.cardinality.HyperLogLogPlus
import com.github.fommil.netlib.BLAS.{getInstance => blas}
import org.apache.hadoop.fs.Path
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

import org.apache.spark.SparkContext
import org.apache.spark.annotation.Since
import org.apache.spark.api.java.{JavaPairRDD, JavaRDD}
import org.apache.spark.internal.Logging
import org.apache.spark.mllib.linalg._
import org.apache.spark.mllib.rdd.MLPairRDDFunctions._
import org.apache.spark.mllib.util.{Loader, Saveable}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.BoundedPriorityQueue

/**
 * Model representing the result of matrix factorization.
 *
 * @param rank Rank for the features in this model.
 * @param userFeatures RDD of tuples where each tuple represents the userId and
 *                     the features computed for this user.
 * @param productFeatures RDD of tuples where each tuple represents the productId
 *                        and the features computed for this product.
 *
 * @note If you create the model directly using constructor, please be aware that fast prediction
 * requires cached user/product features and their associated partitioners.
 */
@Since("0.8.0")
class MatrixFactorizationModel @Since("0.8.0") (
    @Since("0.8.0") val rank: Int,
    @Since("0.8.0") val userFeatures: RDD[(Int, Array[Double])],
    @Since("0.8.0") val productFeatures: RDD[(Int, Array[Double])])
  extends Saveable with Serializable with Logging {

  require(rank > 0)
  validateFeatures("User", userFeatures)
  validateFeatures("Product", productFeatures)

  /** Validates factors and warns users if there are performance concerns. */
  private def validateFeatures(name: String, features: RDD[(Int, Array[Double])]): Unit = {
    require(features.first()._2.length == rank,
      s"$name feature dimension does not match the rank $rank.")
    if (features.partitioner.isEmpty) {
      logWarning(s"$name factor does not have a partitioner. "
        + "Prediction on individual records could be slow.")
    }
    if (features.getStorageLevel == StorageLevel.NONE) {
      logWarning(s"$name factor is not cached. Prediction could be slow.")
    }
  }

  /** Predict the rating of one user for one product. */
  @Since("0.8.0")
  def predict(user: Int, product: Int): Double = {
    val userVector = userFeatures.lookup(user).head
    val productVector = productFeatures.lookup(product).head
    blas.ddot(rank, userVector, 1, productVector, 1)
  }

  /**
   * Return approximate numbers of users and products in the given usersProducts tuples.
   * This method is based on `countApproxDistinct` in class `RDD`.
   *
   * @param usersProducts  RDD of (user, product) pairs.
   * @return approximate numbers of users and products.
   */
  private[this] def countApproxDistinctUserProduct(usersProducts: RDD[(Int, Int)]): (Long, Long) = {
    val zeroCounterUser = new HyperLogLogPlus(4, 0)
    val zeroCounterProduct = new HyperLogLogPlus(4, 0)
    val aggregated = usersProducts.aggregate((zeroCounterUser, zeroCounterProduct))(
      (hllTuple: (HyperLogLogPlus, HyperLogLogPlus), v: (Int, Int)) => {
        hllTuple._1.offer(v._1)
        hllTuple._2.offer(v._2)
        hllTuple
      },
      (h1: (HyperLogLogPlus, HyperLogLogPlus), h2: (HyperLogLogPlus, HyperLogLogPlus)) => {
        h1._1.addAll(h2._1)
        h1._2.addAll(h2._2)
        h1
      })
    (aggregated._1.cardinality(), aggregated._2.cardinality())
  }

  /**
   * Predict the rating of many users for many products.
   * The output RDD has an element per each element in the input RDD (including all duplicates)
   * unless a user or product is missing in the training set.
   *
   * @param usersProducts  RDD of (user, product) pairs.
   * @return RDD of Ratings.
   */
  @Since("0.9.0")
  def predict(usersProducts: RDD[(Int, Int)]): RDD[Rating] = {
    // Previously the partitions of ratings are only based on the given products.
    // So if the usersProducts given for prediction contains only few products or
    // even one product, the generated ratings will be pushed into few or single partition
    // and can't use high parallelism.
    // Here we calculate approximate numbers of users and products. Then we decide the
    // partitions should be based on users or products.
    val (usersCount, productsCount) = countApproxDistinctUserProduct(usersProducts)

    if (usersCount < productsCount) {
      val users = userFeatures.join(usersProducts).map {
        case (user, (uFeatures, product)) => (product, (user, uFeatures))
      }
      users.join(productFeatures).map {
        case (product, ((user, uFeatures), pFeatures)) =>
          Rating(user, product, blas.ddot(uFeatures.length, uFeatures, 1, pFeatures, 1))
      }
    } else {
      val products = productFeatures.join(usersProducts.map(_.swap)).map {
        case (product, (pFeatures, user)) => (user, (product, pFeatures))
      }
      products.join(userFeatures).map {
        case (user, ((product, pFeatures), uFeatures)) =>
          Rating(user, product, blas.ddot(uFeatures.length, uFeatures, 1, pFeatures, 1))
      }
    }
  }

  /**
   * Java-friendly version of `MatrixFactorizationModel.predict`.
   */
  @Since("1.2.0")
  def predict(usersProducts: JavaPairRDD[JavaInteger, JavaInteger]): JavaRDD[Rating] = {
    predict(usersProducts.rdd.asInstanceOf[RDD[(Int, Int)]]).toJavaRDD()
  }

  /**
   * Recommends products to a user.
   *
   * @param user the user to recommend products to
   * @param num how many products to return. The number returned may be less than this.
   * @return [[Rating]] objects, each of which contains the given user ID, a product ID, and a
   *  "score" in the rating field. Each represents one recommended product, and they are sorted
   *  by score, decreasing. The first returned is the one predicted to be most strongly
   *  recommended to the user. The score is an opaque value that indicates how strongly
   *  recommended the product is.
   */
  @Since("1.1.0")
  def recommendProducts(user: Int, num: Int): Array[Rating] =
    MatrixFactorizationModel.recommend(userFeatures.lookup(user).head, productFeatures, num)
      .map(t => Rating(user, t._1, t._2))

  /**
   * Recommends users to a product. That is, this returns users who are most likely to be
   * interested in a product.
   *
   * @param product the product to recommend users to
   * @param num how many users to return. The number returned may be less than this.
   * @return [[Rating]] objects, each of which contains a user ID, the given product ID, and a
   *  "score" in the rating field. Each represents one recommended user, and they are sorted
   *  by score, decreasing. The first returned is the one predicted to be most strongly
   *  recommended to the product. The score is an opaque value that indicates how strongly
   *  recommended the user is.
   */
  @Since("1.1.0")
  def recommendUsers(product: Int, num: Int): Array[Rating] =
    MatrixFactorizationModel.recommend(productFeatures.lookup(product).head, userFeatures, num)
      .map(t => Rating(t._1, product, t._2))

  protected override val formatVersion: String = "1.0"

  /**
   * Save this model to the given path.
   *
   * This saves:
   *  - human-readable (JSON) model metadata to path/metadata/
   *  - Parquet formatted data to path/data/
   *
   * The model may be loaded using `Loader.load`.
   *
   * @param sc  Spark context used to save model data.
   * @param path  Path specifying the directory in which to save this model.
   *              If the directory already exists, this method throws an exception.
   */
  @Since("1.3.0")
  override def save(sc: SparkContext, path: String): Unit = {
    MatrixFactorizationModel.SaveLoadV1_0.save(this, path)
  }

  /**
   * Recommends top products for all users.
   *
   * @param num how many products to return for every user.
   * @return [(Int, Array[Rating])] objects, where every tuple contains a userID and an array of
   * rating objects which contains the same userId, recommended productID and a "score" in the
   * rating field. Semantics of score is same as recommendProducts API
   */
  @Since("1.4.0")
  def recommendProductsForUsers(num: Int): RDD[(Int, Array[Rating])] = {
    MatrixFactorizationModel.recommendForAll(rank, userFeatures, productFeatures, num).map {
      case (user, top) =>
        val ratings = top.map { case (product, rating) => Rating(user, product, rating) }
        (user, ratings)
    }
  }


  /**
   * Recommends top users for all products.
   *
   * @param num how many users to return for every product.
   * @return [(Int, Array[Rating])] objects, where every tuple contains a productID and an array
   * of rating objects which contains the recommended userId, same productID and a "score" in the
   * rating field. Semantics of score is same as recommendUsers API
   */
  @Since("1.4.0")
  def recommendUsersForProducts(num: Int): RDD[(Int, Array[Rating])] = {
    MatrixFactorizationModel.recommendForAll(rank, productFeatures, userFeatures, num).map {
      case (product, top) =>
        val ratings = top.map { case (user, rating) => Rating(user, product, rating) }
        (product, ratings)
    }
  }
}

@Since("1.3.0")
object MatrixFactorizationModel extends Loader[MatrixFactorizationModel] {

  import org.apache.spark.mllib.util.Loader._

  /**
   * Makes recommendations for a single user (or product).
   */
  private def recommend(
      recommendToFeatures: Array[Double],
      recommendableFeatures: RDD[(Int, Array[Double])],
      num: Int): Array[(Int, Double)] = {
    val scored = recommendableFeatures.map { case (id, features) =>
      (id, blas.ddot(features.length, recommendToFeatures, 1, features, 1))
    }
    scored.top(num)(Ordering.by(_._2))
  }

  /**
   * Makes recommendations for all users (or products).
   *
   * Note: the previous approach used for computing top-k recommendations aimed to group
   * individual factor vectors into blocks, so that Level 3 BLAS operations (gemm) could
   * be used for efficiency. However, this causes excessive GC pressure due to the large
   * arrays required for intermediate result storage, as well as a high sensitivity to the
   * block size used.
   *
   * The following approach still groups factors into blocks, but instead computes the
   * top-k elements per block, using dot product and an efficient [[BoundedPriorityQueue]]
   * (instead of gemm). This avoids any large intermediate data structures and results
   * in significantly reduced GC pressure as well as shuffle data, which far outweighs
   * any cost incurred from not using Level 3 BLAS operations.
   *
   * @param rank rank
   * @param srcFeatures src features to receive recommendations
   * @param dstFeatures dst features used to make recommendations
   * @param num number of recommendations for each record
   * @return an RDD of (srcId: Int, recommendations), where recommendations are stored as an array
   *         of (dstId, rating) pairs.
   */
  private def recommendForAll(
      rank: Int,
      srcFeatures: RDD[(Int, Array[Double])],
      dstFeatures: RDD[(Int, Array[Double])],
      num: Int): RDD[(Int, Array[(Int, Double)])] = {
    val srcBlocks = blockify(rank, srcFeatures).zipWithIndex()
    val dstBlocks = blockify(rank, dstFeatures)
    val ratings = srcBlocks.cartesian(dstBlocks).map {
      case (((srcIds, srcFactors), index), (dstIds, dstFactors)) =>
        val m = srcIds.length
        val n = dstIds.length
        val dstIdMatrix = new Array[Int](m * num)
        val scoreMatrix = Array.fill[Double](m * num)(Double.MinValue)
        val pq = new BoundedPriorityQueue[(Int, Double)](num)(Ordering.by(_._2))

        val ratings = srcFactors.transpose.multiply(dstFactors)
        var i = 0
        var j = 0
        while (i < m) {
          var k = 0
          while (k < n) {
            pq += dstIds(k) -> ratings(i, k)
            k += 1
          }
          var size = pq.size
          while(size > 0) {
            size -= 1
            val factor = pq.poll
            dstIdMatrix(j + size) = factor._1
            scoreMatrix(j + size) = factor._2
          }
          i += 1
          // pq.size maybe less than num, corner case
          j += num
          pq.clear
        }
        (index -> (srcIds, dstIdMatrix, new DenseMatrix(m, num, scoreMatrix)))
    }
    ratings.aggregateByKey(null: Array[Int], null: Array[Int], null: DenseMatrix)(
      (rateSum, rate) => {
        mergeFunc(rateSum, rate, num)
      },
      (rateSum1, rateSum2) => {
        mergeFunc(rateSum1, rateSum2, num)
      }
    ).flatMap(value => {
      // to avoid corner case that the number of items is less than recommendation num
      var col: Int = 0
      while (col < num && value._2._3(0, col) > Double.MinValue) {
        col += 1
      }
      val row = value._2._3.numRows
      val output = new Array[(Int, Array[(Int, Double)])](row)
      var i = 0
      while (i < row) {
        val factors = new Array[(Int, Double)](col)
        var j = 0
        while (j < col) {
          factors(j) = (value._2._2(i * num + j), value._2._3(i, j))
          j += 1
        }
        output(i) = (value._2._1(i), factors)
        i += 1
      }
      output.toSeq})
  }

  private def mergeFunc(rateSum: (Array[Int], Array[Int], DenseMatrix),
                        rate: (Array[Int], Array[Int], DenseMatrix),
                        num: Int): (Array[Int], Array[Int], DenseMatrix) = {
    if (rateSum._1 == null) {
      rate
    } else {
      val row = rateSum._3.numRows
      var i = 0
      val tempIdMatrix = new Array[Int](row * num)
      val tempScoreMatrix = new Array[Double](row * num)
      while (i < row) {
        var j = 0
        var sum_index = 0
        var rate_index = 0
        while (j < num) {
          if (rate._3(i, rate_index) > rateSum._3(i, sum_index)) {
            tempIdMatrix(i * num + j) = rate._2(i * num + rate_index)
            tempScoreMatrix(i * num + j) = rate._3(i, rate_index)
            rate_index += 1
          } else if (rate._3(i, rate_index) < rateSum._3(i, sum_index)) {
            tempIdMatrix(i * num + j) = rateSum._2(i * num + sum_index)
            tempScoreMatrix(i * num + j) = rateSum._3(i, sum_index)
            sum_index += 1
          }
          j += 1
        }
        i += 1
      }
      (rateSum._1, tempIdMatrix, new DenseMatrix(row, num, tempScoreMatrix))
    }
  }

  /**
   * Blockifies features to improve the efficiency of cartesian product
   * TODO: SPARK-20443 - expose blockSize as a param?
   */
  def blockify(
      rank: Int,
      features: RDD[(Int, Array[Double])]): RDD[(Array[Int], DenseMatrix)] = {
    val blockSize = 2000 // TODO: tune the block size
    val blockStorage = rank * blockSize
    features.mapPartitions { iter =>
      iter.grouped(blockSize).map { grouped =>
        val ids = mutable.ArrayBuilder.make[Int]
        ids.sizeHint(blockSize)
        val factors = mutable.ArrayBuilder.make[Double]
        factors.sizeHint(blockStorage)
        var i = 0
        grouped.foreach { case (id, factor) =>
          ids += id
          factors ++= factor
          i += 1
        }
        (ids.result(), new DenseMatrix(rank, i, factors.result()))
      }
    }
  }

  /**
   * Load a model from the given path.
   *
   * The model should have been saved by `Saveable.save`.
   *
   * @param sc  Spark context used for loading model files.
   * @param path  Path specifying the directory to which the model was saved.
   * @return  Model instance
   */
  @Since("1.3.0")
  override def load(sc: SparkContext, path: String): MatrixFactorizationModel = {
    val (loadedClassName, formatVersion, _) = loadMetadata(sc, path)
    val classNameV1_0 = SaveLoadV1_0.thisClassName
    (loadedClassName, formatVersion) match {
      case (className, "1.0") if className == classNameV1_0 =>
        SaveLoadV1_0.load(sc, path)
      case _ =>
        throw new IOException("MatrixFactorizationModel.load did not recognize model with" +
          s"(class: $loadedClassName, version: $formatVersion). Supported:\n" +
          s"  ($classNameV1_0, 1.0)")
    }
  }

  private[recommendation]
  object SaveLoadV1_0 {

    private val thisFormatVersion = "1.0"

    private[recommendation]
    val thisClassName = "org.apache.spark.mllib.recommendation.MatrixFactorizationModel"

    /**
     * Saves a [[MatrixFactorizationModel]], where user features are saved under `data/users` and
     * product features are saved under `data/products`.
     */
    def save(model: MatrixFactorizationModel, path: String): Unit = {
      val sc = model.userFeatures.sparkContext
      val spark = SparkSession.builder().sparkContext(sc).getOrCreate()
      import spark.implicits._
      val metadata = compact(render(
        ("class" -> thisClassName) ~ ("version" -> thisFormatVersion) ~ ("rank" -> model.rank)))
      sc.parallelize(Seq(metadata), 1).saveAsTextFile(metadataPath(path))
      model.userFeatures.toDF("id", "features").write.parquet(userPath(path))
      model.productFeatures.toDF("id", "features").write.parquet(productPath(path))
    }

    def load(sc: SparkContext, path: String): MatrixFactorizationModel = {
      implicit val formats = DefaultFormats
      val spark = SparkSession.builder().sparkContext(sc).getOrCreate()
      val (className, formatVersion, metadata) = loadMetadata(sc, path)
      assert(className == thisClassName)
      assert(formatVersion == thisFormatVersion)
      val rank = (metadata \ "rank").extract[Int]
      val userFeatures = spark.read.parquet(userPath(path)).rdd.map {
        case Row(id: Int, features: Seq[_]) =>
          (id, features.asInstanceOf[Seq[Double]].toArray)
      }
      val productFeatures = spark.read.parquet(productPath(path)).rdd.map {
        case Row(id: Int, features: Seq[_]) =>
          (id, features.asInstanceOf[Seq[Double]].toArray)
      }
      new MatrixFactorizationModel(rank, userFeatures, productFeatures)
    }

    private def userPath(path: String): String = {
      new Path(dataPath(path), "user").toUri.toString
    }

    private def productPath(path: String): String = {
      new Path(dataPath(path), "product").toUri.toString
    }
  }

}
