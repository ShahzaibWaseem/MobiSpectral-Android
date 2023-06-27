package com.shahzaib.mobispectral

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Color.rgb
import android.util.Log
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import kotlin.math.roundToInt

class WhiteBalance(context: Context, modelPath: String) {
    private var model: Module? = null
    private var mean = floatArrayOf(0.0f, 0.0f, 0.0f)
    private var std = floatArrayOf(1.0f, 1.0f, 1.0f)

    init {
        Log.i("Model Load", Utils.assetFilePath(context, modelPath).toString())
        model = Module.load(Utils.assetFilePath(context, modelPath))
    }

    private fun preprocess(bitmap: Bitmap?): Tensor {
        return TensorImageUtils.bitmapToFloat32Tensor(bitmap, mean, std)
    }

    private fun tensor2Bitmap(input: DoubleArray, width: Int, height: Int): Bitmap {
        val pixelsCount = height * width
        val pixels = IntArray(pixelsCount)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // mapping smallest value to 0 and largest value to 255
        val maxValue = input.max()
        val minValue = input.min()
        val delta = maxValue-minValue

        // Define if float min..max will be mapped to 0..255 or 255..0
        val conversion = { v: Double -> ((v-minValue)/delta*255.0).roundToInt()}

        for (i in 0 until pixelsCount) {
            val r = conversion(input[3 * i])
            val g = conversion(input[3 * i + 1])
            val b = conversion(input[3 * i + 2])
            pixels[i] = 255 shl 24 or (r and 0xff shl 16) or (g and 0xff shl 8) or (b and 0xff)
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun floatArrayToBitmap(floatArray: FloatArray, width: Int, height: Int) : Bitmap {

        // Create empty bitmap in ARGB format
        val bmp: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height * 4)

        // mapping smallest value to 0 and largest value to 255
        val maxValue = floatArray.max()
        val minValue = floatArray.min()
        val delta = maxValue-minValue

        // Define if float min..max will be mapped to 0..255 or 255..0
        val conversion = { v: Float -> ((v-minValue)/delta*255.0f).roundToInt()}

        // copy each value from float array to RGB channels
        for (i in 0 until width * height) {
            val r = conversion(floatArray[i])
            val g = conversion(floatArray[i+width*height])
            val b = conversion(floatArray[i+2*width*height])
            pixels[i] = rgb(r, g, b) // you might need to import for rgb()
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)

        return bmp
    }

    private fun bitmapToFloatArrayFirst(bitmap: Bitmap): Tensor {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height * 3)
        val pixelCount = width * height
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val floatArray = FloatArray(width * height * 3)
        for (i in 0 until width * height) {
            val pixel = pixels[i]
            val red = Color.red(pixel) / 255.0f
            val green = Color.green(pixel) / 255.0f
            val blue = Color.blue(pixel) / 255.0f
            floatArray[i] = red
            floatArray[pixelCount + i] = green
            floatArray[pixelCount * 2 + i] = blue
        }
        val bitmapTensor = Tensor.allocateFloatBuffer(width * height * 3)
        bitmapTensor.put(floatArray)

        return Tensor.fromBlob(bitmapTensor, longArrayOf(1, 3, height.toLong(), width.toLong()))
    }

    private fun bitmapToFloatArraySecond(bitmap: Bitmap): Tensor {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height * 3)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val floatArray = FloatArray(width * height * 3)
        for (i in 0 until width * height) {
            val pixel = pixels[i]
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            floatArray[3 * i] = red.toFloat()
            floatArray[3 * i + 1] = green.toFloat()
            floatArray[3 * i + 2] = blue.toFloat()
        }
        val bitmapTensor = Tensor.allocateFloatBuffer(width * height * 3)
        bitmapTensor.put(floatArray)

        return Tensor.fromBlob(bitmapTensor, longArrayOf(height.toLong(), width.toLong(), 3))
    }

    fun whiteBalance(rgbBitmap: Bitmap): Bitmap {
        val rgbTensor: Tensor = bitmapToFloatArraySecond(rgbBitmap)
        val startTime = System.currentTimeMillis()

        Log.i("imageShape", "${rgbTensor.shape().toList()}")

        val inputs: IValue = IValue.from(rgbTensor)
        val outputs: Tensor = model?.forward(inputs)?.toTensor()!!
        Log.i("White Balancing", "Output Shape: ${outputs.shape().toList()}")

        val whiteBalancedBitmap = tensor2Bitmap(
            outputs.dataAsDoubleArray, outputs.shape()[1].toInt(), outputs.shape()[0].toInt()
        )
        // val whiteBalancedBitmap3 = floatArrayToBitmap(rgbResizedTensor.dataAsFloatArray, rgbResizedTensor.shape()[1].toInt(), rgbResizedTensor.shape()[0].toInt())

        val duration = System.currentTimeMillis() - startTime
        Log.i("White Balancing" , "White balancing Duration: $duration ms")

        return whiteBalancedBitmap
    }
}