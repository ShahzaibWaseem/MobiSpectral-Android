package com.shahzaib.mobispectral

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

import org.pytorch.Tensor
import org.pytorch.Module
import org.pytorch.IValue
import org.pytorch.torchvision.TensorImageUtils

class Reconstruction(context: Context, modelPath: String) {
    private var model: Module? = null
    private val height: Long = 640
    private val width: Long = 480

    private var mean = floatArrayOf(0.0f, 0.0f, 0.0f)
    private var std = floatArrayOf(1.0f, 1.0f, 1.0f)

    init {
        Log.i("Model Load", Utils.assetFilePath(context, modelPath).toString())
        model = Module.load(Utils.assetFilePath(context, modelPath))
    }

    private fun preprocess(bitmap: Bitmap?): Tensor {
        return TensorImageUtils.bitmapToFloat32Tensor(bitmap, mean, std)
    }

    private fun getOneBand(tensor: Tensor): Tensor {
        val tensorDoubleArray = tensor.dataAsFloatArray
        val floatArray = FloatArray((width*height).toInt())
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

    private fun concatenate(rgbTensor: Tensor, nirTensor: Tensor): Tensor {
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
        val rgbTensor: Tensor = preprocess(rgbBitmap)
        val nirTensor: Tensor = getOneBand(preprocess(nirBitmap))
        Log.i("TensorShape", "${rgbTensor.shape().toList()}, ${nirTensor.shape().toList()}")

        val imageTensor: Tensor = concatenate(rgbTensor, nirTensor)

        Log.i("Concatenated", imageTensor.shape().toList().toString())

//        val data: FloatArray = image_tensor.getDataAsFloatArray()
        val inputs: IValue = IValue.from(imageTensor)

        val outputs: Tensor = model?.forward(inputs)?.toTensor()!!
        val reconstructedHS: FloatArray = outputs.dataAsFloatArray
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        println(duration)

        return reconstructedHS
    }
}