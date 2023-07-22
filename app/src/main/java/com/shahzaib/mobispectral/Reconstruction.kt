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

    private fun processTensor(floatArray: FloatArray, numberOfElements: Int, min: Float, max: Float, channels: Long): Tensor {
        val diff = max - min
        for (i in 0 until numberOfElements) {
            floatArray[i] = (floatArray[i] - min)/diff
        }
        val size = longArrayOf(1, channels, bitmapsHeight.toLong(), bitmapsWidth.toLong())
        return Tensor.fromBlob(floatArray, size)
    }
    private fun getOneBand(tensor: Tensor, offset: Int): Tensor {
        val tensorDoubleArray = tensor.dataAsFloatArray
        val floatArray = FloatArray((bitmapsHeight*bitmapsWidth))
        val bandOffset = bitmapsHeight*bitmapsWidth*offset
        for (i in 0 until (bitmapsHeight*bitmapsWidth)){
            floatArray[i] = tensorDoubleArray[bandOffset+i]
        }
        val size = longArrayOf(1, 1, bitmapsHeight.toLong(), bitmapsWidth.toLong())
        return Tensor.fromBlob(floatArray, size)
    }

    private fun concatenate(tensor1: Tensor, tensor2: Tensor, channels: Long): Tensor {
        val rgbArray = tensor1.dataAsFloatArray
        val nirArray = tensor2.dataAsFloatArray
        val concatenated = FloatArray(rgbArray.size + nirArray.size)
        System.arraycopy(rgbArray, 0, concatenated, 0, rgbArray.size)
        System.arraycopy(nirArray, 0, concatenated, rgbArray.size, nirArray.size)
        val size = longArrayOf(1, channels, bitmapsHeight.toLong(), bitmapsWidth.toLong())
        return Tensor.fromBlob(concatenated, size)
    }

    fun predict(rgbBitmap: Bitmap, nirBitmap: Bitmap): FloatArray {
        bitmapsWidth = rgbBitmap.width
        bitmapsHeight = rgbBitmap.height

        var rgbBitmapTensor = preprocess(rgbBitmap)
        rgbBitmapTensor = processTensor(rgbBitmapTensor.dataAsFloatArray, bitmapsHeight*bitmapsWidth*3, rgbBitmapTensor.dataAsFloatArray.min(), rgbBitmapTensor.dataAsFloatArray.max(), 3)

        var nirTensor: Tensor = getOneBand(preprocess(nirBitmap), 0)
        nirTensor = processTensor(nirTensor.dataAsFloatArray, bitmapsHeight*bitmapsWidth, nirTensor.dataAsFloatArray.min(), nirTensor.dataAsFloatArray.max(), 1)

        val imageTensor: Tensor = concatenate(rgbBitmapTensor, nirTensor, 4)
        val inputs: IValue = IValue.from(imageTensor)

        val outputs: Tensor = model?.forward(inputs)?.toTensor()!!
        Log.i("Reconstruction Tensors", "RGB ${rgbBitmapTensor.shape().toList()} + NIR ${nirTensor.shape().toList()} = Concat ${imageTensor.shape().toList()} -> [Reconstruction] -> ${outputs.shape().toList()}")

        saveHypercube("Output.txt", outputs.dataAsFloatArray, Utils.hypercubeDirectory)
        return outputs.dataAsFloatArray
    }
}