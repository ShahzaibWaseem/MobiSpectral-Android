package com.shahzaib.mobispectral

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

import org.pytorch.Tensor
import org.pytorch.Module
import org.pytorch.IValue
import org.pytorch.torchvision.TensorImageUtils

class Reconstruction(context: Context, modelPath: String) {
    var model: Module? = null
    val height: Long = 640
    val width: Long = 480

    var mean = floatArrayOf(0.0f, 0.0f, 0.0f)
    var std = floatArrayOf(1.0f, 1.0f, 1.0f)

    init {
        Log.i("Model Load", Utils.assetFilePath(context, modelPath).toString())
        model = Module.load(Utils.assetFilePath(context, modelPath))
    }

    fun preprocess(bitmap: Bitmap?, size: Int): Tensor {
        var bitmap = bitmap
        return TensorImageUtils.bitmapToFloat32Tensor(bitmap, mean, std)
    }

    fun getOneBand(tensor: Tensor): Tensor {
        val tensorDoubleArray = tensor.dataAsFloatArray
        var floatArray = FloatArray((width*height).toInt())
        Log.i("Tensor size", "${tensorDoubleArray.size}")
        for (i in 0 until (height*width).toInt()){
            floatArray[i] = tensorDoubleArray[i]
        }
        val size = LongArray(4)
        size[0] = 1
        size[1] = 1
        size[2] = height
        size[3] = width
        return Tensor.fromBlob(floatArray, size)
    }

    fun argMax(inputs: FloatArray): Int {
        var maxIndex = -1
        var maxvalue = 0.0f
        for (i in inputs.indices) {
            if (inputs[i] > maxvalue) {
                maxIndex = i
                maxvalue = inputs[i]
            }
        }
        return maxIndex
    }

    fun concatenate(rgbTensor: Tensor, nirTensor: Tensor): Tensor {
        val rgbArray = rgbTensor.dataAsFloatArray
        val nirArray = nirTensor.dataAsFloatArray
        val concatenated = FloatArray(rgbArray.size + nirArray.size)
        System.arraycopy(rgbArray, 0, concatenated, 0, rgbArray.size)
        System.arraycopy(nirArray, 0, concatenated, rgbArray.size, nirArray.size)
        Log.i("Concatenated Array Size", "${concatenated.size}")
        val size = LongArray(4)
        size[0] = 1
        size[1] = 4
        size[2] = height
        size[3] = width
        return Tensor.fromBlob(concatenated, size)
    }

    fun predict(rgbBitmap: Bitmap, nirBitmap: Bitmap): FloatArray {
        val startTime = System.currentTimeMillis()
        val rgb_tensor: Tensor = preprocess(rgbBitmap, 512)
        val nir_tensor: Tensor = getOneBand(preprocess(nirBitmap, 512))
        Log.i("TensorShape", "${rgb_tensor.shape().toList().toString()}, ${nir_tensor.shape().toList().toString()}")

        val image_tensor: Tensor = concatenate(rgb_tensor, nir_tensor)

        Log.i("Concatenated", image_tensor.shape().toList().toString())

        val data: FloatArray = image_tensor.getDataAsFloatArray()
        val inputs: IValue = IValue.from(image_tensor)

        val outputs: Tensor = model?.forward(inputs)?.toTensor()!!
        val reconstructed_HS: FloatArray = outputs.dataAsFloatArray
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        println(duration)

        return reconstructed_HS
    }
}