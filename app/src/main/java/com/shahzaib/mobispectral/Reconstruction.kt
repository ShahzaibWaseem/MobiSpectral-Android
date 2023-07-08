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
    private var mean = floatArrayOf(0.0f, 0.0f, 0.0f)
    private var std = floatArrayOf(1.0f, 1.0f, 1.0f)
    private var bitmapsWidth = Utils.torchWidth
    private var bitmapsHeight = Utils.torchHeight

    init {
        Log.i("Model Load", Utils.assetFilePath(context, modelPath).toString())
        model = Module.load(Utils.assetFilePath(context, modelPath))
    }

    private fun preprocess(bitmap: Bitmap?): Tensor {
        return TensorImageUtils.bitmapToFloat32Tensor(bitmap, mean, std)
    }

    private fun getOneBand(tensor: Tensor): Tensor {
        val tensorDoubleArray = tensor.dataAsFloatArray
        val floatArray = FloatArray((bitmapsHeight*bitmapsWidth))
        Log.i("Tensor size", "${tensorDoubleArray.size}")
        for (i in 0 until (bitmapsHeight*bitmapsWidth)){
            floatArray[i] = tensorDoubleArray[i]
        }
        val size = longArrayOf(1, 1, bitmapsHeight.toLong(), bitmapsWidth.toLong())
        return Tensor.fromBlob(floatArray, size)
    }

    private fun concatenate(rgbTensor: Tensor, nirTensor: Tensor): Tensor {
        val rgbArray = rgbTensor.dataAsFloatArray
        val nirArray = nirTensor.dataAsFloatArray
        val concatenated = FloatArray(rgbArray.size + nirArray.size)
        System.arraycopy(rgbArray, 0, concatenated, 0, rgbArray.size)
        System.arraycopy(nirArray, 0, concatenated, rgbArray.size, nirArray.size)
        Log.i("Concatenated Array Size", "${concatenated.size}")
        val size = longArrayOf(1, 4, bitmapsHeight.toLong(), bitmapsWidth.toLong())
        return Tensor.fromBlob(concatenated, size)
    }

    fun predict(rgbBitmap: Bitmap, nirBitmap: Bitmap): FloatArray {
        bitmapsWidth = rgbBitmap.width
        bitmapsHeight = rgbBitmap.height

        val rgbTensor: Tensor = preprocess(rgbBitmap)
        val nirTensor: Tensor = getOneBand(preprocess(nirBitmap))
        Log.i("TensorShape", "${rgbTensor.shape().toList()}, ${nirTensor.shape().toList()}")

        val imageTensor: Tensor = concatenate(rgbTensor, nirTensor)

        Log.i("Concatenated", imageTensor.shape().toList().toString())

        val inputs: IValue = IValue.from(imageTensor)

        val outputs: Tensor = model?.forward(inputs)?.toTensor()!!
        Log.i("Output Tensor", "${outputs.shape().toList()}")

        return outputs.dataAsFloatArray
    }
}