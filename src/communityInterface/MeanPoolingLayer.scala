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

package org.apache.spark.ml.ann

import breeze.linalg.{DenseMatrix => BDM, DenseVector => BDV, Vector => BV, axpy => Baxpy, sum => Bsum, _}
import org.apache.spark.mllib.linalg.{Vectors, Vector}

/**
 * Layer properties of affine transformations, that is y=A*x+b
 * @param poolingSize number of inputs
 */
private[ann] class MeanPoolingLayer(val poolingSize: Scale, val inputSize: Scale) extends Layer {

  override def getInstance(weights: Vector, position: Int): LayerModel = getInstance(0L)

  override def getInstance(seed: Long = 11L): LayerModel = MeanPoolingLayerModel(this, inputSize)
}


/**
 * @param poolingSize kernels (matrix A)
 * @param inputSize bias (vector b)
 */
private[ann] class MeanPoolingLayerModel private(
    poolingSize: Scale,
    inputSize: Scale) extends LayerModel {

  val outputSize = inputSize.divide(poolingSize)

  override val size = 0

  override def eval(data: BDM[Double]): BDM[Double] = {
    val inputMaps = ConvolutionLayerModel.line2Tensor(data, inputSize)
    val inputMapNum: Int = inputMaps.length
    val output = new Array[BDM[Double]](inputMapNum)
    var i: Int = 0
    while (i < inputMapNum) {
      val inputMap: BDM[Double] = inputMaps(i)
      val scaleSize: Scale = this.poolingSize
      output(i) = MeanPoolingLayerModel.avgPooling(inputMap, scaleSize)
      i += 1
    }
    ConvolutionLayerModel.tensor2Line(output)
  }

  override def prevDelta(nextDelta: BDM[Double], input: BDM[Double]): BDM[Double] = {
    val inputMaps = ConvolutionLayerModel.line2Tensor(input, inputSize)
    val nextDeltaMaps = ConvolutionLayerModel.line2Tensor(nextDelta, outputSize)

    val mapNum: Int = inputMaps.length
    val errors = new Array[BDM[Double]](mapNum)
    var m: Int = 0
    val scale: Scale = this.poolingSize
    while (m < mapNum) {
      val nextError: BDM[Double] = nextDeltaMaps(m)
      val map: BDM[Double] = inputMaps(m)
      var outMatrix: BDM[Double] = (1.0 - map)
      outMatrix = map :* outMatrix
      outMatrix = outMatrix :* MeanPoolingLayerModel.kronecker(nextError, scale)
      errors(m) = outMatrix
      m += 1
    }

    ConvolutionLayerModel.tensor2Line(errors)
  }

  override def grad(delta: BDM[Double], input: BDM[Double]): Array[Double] = {
    new Array[Double](0)
  }

  override def weights(): Vector = Vectors.dense(new Array[Double](0))

}

/**
 * Fabric for Affine layer models
 */
private[ann] object MeanPoolingLayerModel {

  /**
   * Creates a model of Affine layer
   * @param layer layer properties
   * @return model of Affine layer
   */
  def apply(layer: MeanPoolingLayer, inputSize: Scale): MeanPoolingLayerModel = {
    new MeanPoolingLayerModel(layer.poolingSize, inputSize: Scale)
  }

  /**
   * return a new matrix that has been scaled down
   *
   * @param matrix
   */
  private[ann] def avgPooling(matrix: BDM[Double], scale: Scale): BDM[Double] = {
    val m: Int = matrix.rows
    val n: Int = matrix.cols
    val scaleX = scale.x
    val scaleY = scale.y
    val sm: Int = m / scaleX
    val sn: Int = n / scaleY
    val outMatrix = new BDM[Double](sm, sn)
    val size = scaleX * scaleY

    var i = 0  // iterate through blocks
    while (i < sm) {
      var j = 0
      while (j < sn) {
        var sum = 0.0 // initial to left up corner of the block
        var bi = i * scaleX // block i
        val biMax = (i + 1) * scaleX
        val bjMax = (j + 1) * scaleY
        while (bi < biMax) {
          var bj = j * scaleY // block j
          while (bj < bjMax) {
            sum += matrix(bi, bj)
            bj += 1
          }
          bi += 1
        }
        outMatrix(i, j) = sum / size
        j += 1
      }
      i += 1
    }
    outMatrix
  }

  private[ann] def kronecker(matrix: BDM[Double], scale: Scale): BDM[Double] = {
    val ones = BDM.ones[Double](scale.x, scale.y)
    kron(matrix, ones)
  }
}