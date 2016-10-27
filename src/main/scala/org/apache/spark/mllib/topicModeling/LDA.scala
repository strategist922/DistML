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

package org.apache.spark.mllib.topicModeling

import com.intel.distml.api.Model
import com.intel.distml.platform.DistML
import com.intel.distml.util.DataStore
import org.apache.spark.annotation.Experimental
import org.apache.spark.api.java.JavaPairRDD
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.rdd.RDD
import org.apache.spark.{HashPartitioner, Logging, SparkContext}


/**
 * :: Experimental ::
 *
 * Latent Dirichlet Allocation (LDA), a topic model designed for text documents.
 *
 * Terminology:
 *  - "word" = "term": an element of the vocabulary
 *  - "token": instance of a term appearing in a document
 *  - "topic": multinomial distribution over words representing some concept
 *
 * Adapted from MLlib LDA implementation, it supports both online LDA and Gibbbs sampling LDA
 */
@Experimental
class LDA private (
    private var k: Int,
    private var maxIterations: Int,
    private var docConcentration: Double,
    private var topicConcentration: Double,
    private var seed: Long,
    private var checkpointInterval: Int,
    private var ldaOptimizer: LDAOptimizer) extends Logging {

  private var vocabSize: Int = 0
  private var windowSize: Int = 0
  private var corpusSize: Long = 0
  private var partition: Int = 0
  private var psCount: Int = 0

  def this() = this(k = 10, maxIterations = 50, docConcentration = -1, topicConcentration = -1,
    seed = new scala.util.Random().nextLong(), checkpointInterval = 10, ldaOptimizer = new OnlineLDAOptimizer)

  def getVocabSize = vocabSize
  def setVocabSeze(vocabSize: Int): this.type ={
    this.vocabSize = vocabSize
    this
  }

  def getWindowSize = windowSize
  def setWindowSize(windowSize: Int): this.type ={
    this.windowSize = windowSize
    this
  }

  def getCorpusSize = corpusSize
  def setCorpusSize(corpusSize: Long): this.type ={
    this.corpusSize = corpusSize
    this
  }

  def getPartition = partition
  def setPartition(partition: Int): this.type ={
    this.partition = partition
    this
  }

  def getPsCount = psCount
  def setPsCount(psCount: Int): this.type ={
    this.psCount = psCount
    this
  }

  /**
   * Number of topics to infer.  I.e., the number of soft cluster centers.
   */
  def getK: Int = k

  /**
   * Number of topics to infer.  I.e., the number of soft cluster centers.
   * (default = 10)
   */
  def setK(k: Int): this.type = {
    require(k > 0, s"LDA k (number of clusters) must be > 0, but was set to $k")
    this.k = k
    this
  }

  /**
   * Concentration parameter (commonly named "alpha") for the prior placed on documents'
   * distributions over topics ("theta").
   *
   * This is the parameter to a symmetric Dirichlet distribution.
   */
  def getDocConcentration: Double = this.docConcentration

  /**
   * Concentration parameter (commonly named "alpha") for the prior placed on documents'
   * distributions over topics ("theta").
   *
   * This is the parameter to a symmetric Dirichlet distribution, where larger values
   * mean more smoothing (more regularization).
   *
   * If set to -1, then docConcentration is set automatically.
   *  (default = -1 = automatic)
   *
   * Optimizer-specific parameter settings:
   *  - Online
   *     - Value should be >= 0
   *     - default = (1.0 / k), following the implementation from
   *       [[https://github.com/Blei-Lab/onlineldavb]].
   */
  def setDocConcentration(docConcentration: Double): this.type = {
    this.docConcentration = docConcentration
    this
  }

  /** Alias for [[getDocConcentration]] */
  def getAlpha: Double = getDocConcentration

  /** Alias for [[setDocConcentration()]] */
  def setAlpha(alpha: Double): this.type = setDocConcentration(alpha)

  /**
   * Concentration parameter (commonly named "beta" or "eta") for the prior placed on topics'
   * distributions over terms.
   *
   * This is the parameter to a symmetric Dirichlet distribution.
   *
   * Note: The topics' distributions over terms are called "beta" in the original LDA paper
   * by Blei et al., but are called "phi" in many later papers such as Asuncion et al., 2009.
   */
  def getTopicConcentration: Double = this.topicConcentration

  /**
   * Concentration parameter (commonly named "beta" or "eta") for the prior placed on topics'
   * distributions over terms.
   *
   * This is the parameter to a symmetric Dirichlet distribution.
   *
   * Note: The topics' distributions over terms are called "beta" in the original LDA paper
   * by Blei et al., but are called "phi" in many later papers such as Asuncion et al., 2009.
   *
   * If set to -1, then topicConcentration is set automatically.
   *  (default = -1 = automatic)
   *
   * Optimizer-specific parameter settings:
   *  - Online
   *     - Value should be >= 0
   *     - default = (1.0 / k), following the implementation from
   *       [[https://github.com/Blei-Lab/onlineldavb]].
   */
  def setTopicConcentration(topicConcentration: Double): this.type = {
    this.topicConcentration = topicConcentration
    this
  }

  /** Alias for [[getTopicConcentration]] */
  def getBeta: Double = getTopicConcentration

  /** Alias for [[setTopicConcentration()]] */
  def setBeta(beta: Double): this.type = setTopicConcentration(beta)

  /**
   * Maximum number of iterations for learning.
   */
  def getMaxIterations: Int = maxIterations

  /**
   * Maximum number of iterations for learning.
   * (default = 20)
   */
  def setMaxIterations(maxIterations: Int): this.type = {
    this.maxIterations = maxIterations
    this
  }

  /** Random seed */
  def getSeed: Long = seed

  /** Random seed */
  def setSeed(seed: Long): this.type = {
    this.seed = seed
    this
  }

  /**
   * Period (in iterations) between checkpoints.
   */
  def getCheckpointInterval: Int = checkpointInterval

  /**
   * Period (in iterations) between checkpoints (default = 10). Checkpointing helps with recovery
   * (when nodes fail). It also helps with eliminating temporary shuffle files on disk, which can be
   * important when LDA is run for many iterations. If the checkpoint directory is not set in
   * [[org.apache.spark.SparkContext]], this setting is ignored.
   *
   * @see [[org.apache.spark.SparkContext#setCheckpointDir]]
   */
  def setCheckpointInterval(checkpointInterval: Int): this.type = {
    this.checkpointInterval = checkpointInterval
    this
  }


  /** LDAOptimizer used to perform the actual calculation */
  def getOptimizer: LDAOptimizer = ldaOptimizer

  /**
   * LDAOptimizer used to perform the actual calculation (default = EMLDAOptimizer)
   */
  def setOptimizer(optimizer: LDAOptimizer): this.type = {
    this.ldaOptimizer = optimizer
    this
  }

  /**
   * Set the LDAOptimizer used to perform the actual calculation by algorithm name.
   * Only "online", "gibbs" are supported.
   */
  def setOptimizer(optimizerName: String): this.type = {
    this.ldaOptimizer =
      optimizerName.toLowerCase match {
        case "online" => new OnlineLDAOptimizer
        case other =>
          throw new IllegalArgumentException(s"Only online, gibbs are supported but got $other.")
      }
    this
  }

  /**
   * Learn an LDA model using the given dataset.
   *
   * @param documents  RDD of documents, which are term (word) count vectors paired with IDs.
   *                   The term count vectors are "bags of words" with a fixed-size vocabulary
   *                   (where the vocabulary size is the length of the vector).
   *                   Document IDs must be unique and >= 0.
   * @return  Inferred LDA model
   */
  def run(m : Model, dm: DistML[scala.Iterator[(Int, String, DataStore)]], monitorPath : String)
         (sc: SparkContext, documents: RDD[(Long, Vector)]): LDAModel = {
    val state = ldaOptimizer.asInstanceOf[OnlineLDAOptimizer].initialize(m, monitorPath)(sc, this)
    var windowCount = 0
    while ((windowCount +1)*windowSize < corpusSize){
      val begin = windowCount*windowSize
      val end = (windowCount +1)*windowSize
      var batch = documents.filter(x => x._1>= begin && x._1 < end)
      batch  = batch.partitionBy(new HashPartitioner(partition))
      batch.cache()
      batch.count()

      var iter = 0
      val iterationTimes = Array.fill[Double](maxIterations)(0)
      val iterationPer = Array.fill[(Double, Double)](maxIterations)((0D, 0D))
      while (iter < maxIterations) {
        logInfo(s"[windowcount+iter+maxiter: $windowCount $iter $maxIterations]")
        val start = System.nanoTime()
        val gammaArray = state.next(m, monitorPath)(batch)
        gammaArray.cache()
        gammaArray.count()
        val elapsedSeconds = (System.nanoTime() - start) / 1e9
        iterationTimes(iter) = elapsedSeconds

        val docTopicPerplexity = state.perplexity(m, monitorPath)(batch, gammaArray)
        iterationPer(iter) = docTopicPerplexity
        iter += 1

        gammaArray.unpersist()
        dm.iterationDone()
      }
      logInfo(s"[windowcount+itertime: $windowCount ${iterationTimes.mkString(" ")}]")
      logInfo(s"[windowcount+iterper: $windowCount ${iterationPer.mkString(" ")}]")

      batch.unpersist()
      windowCount += 1
    }

    null
  }
}
