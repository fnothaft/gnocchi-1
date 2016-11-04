/**
 * Copyright 2015 Taner Dagdelen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.fnothaft.gnocchi.association

import breeze.numerics.log10
import breeze.linalg._
import breeze.numerics._
import net.fnothaft.gnocchi.models.Association
import org.apache.commons.math3.distribution.ChiSquaredDistribution
import org.apache.spark.SparkContext
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.mllib.regression.LabeledPoint
import org.bdgenomics.adam.models.ReferenceRegion
import org.apache.spark.ml.classification.LogisticRegressionModel
import org.apache.spark.sql.SQLContext
import org.apache.commons.math3.special.Gamma
import org.bdgenomics.formats.avro.{ Contig, Variant }

trait LogisticValidationRegression extends ValidationRegression with LogisticSiteRegression {

  /**
   * This method will predict the phenotype given a certain site, given the association results
   *
   * @param sampleObservations An array containing tuples in which the first element is the sampleid. The second is the coded genotype.
   *                          The third is an Array[Double] representing the phenotypes, where the first element in the array is the phenotype to regress and the rest are to be treated as covariates. .
   * @param association  An Association object that specifies the model trained for this locus
   * @return An array of results with the model applied to the observations
   */

  def predictSite(sampleObservations: Array[(Double, Array[Double], String)],
                  association: Association): Array[(String, Double)] = {
    // transform the data in to design matrix and y matrix compatible with mllib's logistic regresion
    val observationLength = sampleObservations(0)._2.length
    val numObservations = sampleObservations.length
    val lp = new Array[LabeledPoint](numObservations)

    // iterate over observations, copying correct elements into sample array and filling the x matrix.
    // the first element of each sample in x is the coded genotype and the rest are the covariates.
    var features = new Array[Double](observationLength)
    val samples = new Array[String](sampleObservations.length)
    for (i <- sampleObservations.indices) {
      // rearrange variables into label and features
      features = new Array[Double](observationLength)
      features(0) = sampleObservations(i)._1.toDouble
      sampleObservations(i)._2.slice(1, observationLength).copyToArray(features, 1)
      val label = sampleObservations(i)._2(0)

      // pack up info into LabeledPoint object
      lp(i) = new LabeledPoint(label, new org.apache.spark.mllib.linalg.DenseVector(features))

      samples(i) = sampleObservations(i)._3
    }

    val statistics = association.statistics
    val weights = statistics("weights").asInstanceOf[Array[Double]]
    val intercept = statistics("intercept").asInstanceOf[Double]
    val b: Array[Double] = intercept +: weights

    // receive 0/1 results from datapoints and model
    val results = predict(lp, b)
    samples zip results
  }

  def expit(lpArray: Array[LabeledPoint], b: Array[Double]): Array[Double] = {
    val expitResults = new Array[Double](lpArray.length)
    val bDense = DenseVector(b)
    for (j <- expitResults.indices) {
      val lp = lpArray(j)
      expitResults(j) = 1 / (1 + Math.exp(-DenseVector(1.0 +: lp.features.toArray) dot bDense))
    }
    expitResults
  }

  def predict(lpArray: Array[LabeledPoint], b: Array[Double]): Array[Double] = {
    val expitResults = expit(lpArray, b)
    val predictions = new Array[Double](expitResults.length)
    for (j <- predictions.indices) {
      predictions(j) = Math.round(expitResults(j))
    }
    predictions
  }
}

object AdditiveLogisticEvaluation extends LogisticValidationRegression with Additive {
  val regressionName = "additiveLogisticEvaluation"
}

object DominantLogisticEvaluation extends LogisticValidationRegression with Dominant {
  val regressionName = "dominantLogisticEvaluation"
}
