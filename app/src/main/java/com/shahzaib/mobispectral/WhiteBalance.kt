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

class WhiteBalance(context: Context) {
    private var model_awb: Module? = null
    private var model_s: Module? = null
    private var model_t: Module? = null
    private var output_awb: Tensor? = null
    private var output_t: Tensor? = null
    private var output_s: Tensor? = null

    private var mean = floatArrayOf(0.0f, 0.0f, 0.0f)
    private var std = floatArrayOf(1.0f, 1.0f, 1.0f)

    init {
        model_awb = Module.load(Utils.assetFilePath(context, "mobile_awb.pt"))
        model_t = Module.load(Utils.assetFilePath(context, "mobile_t.pt"))
        model_s = Module.load(Utils.assetFilePath(context, "mobile_s.pt"))
    }

    fun deepWB(image: Tensor): Tensor {
        val inputs: IValue = IValue.from(image)
        output_awb = model_awb?.forward(inputs)?.toTensor()!!
        output_t = model_t?.forward(inputs)?.toTensor()!!
        output_s = model_s?.forward(inputs)?.toTensor()!!

        val output_d = colorTempInterpolate(output_t!!, output_s!!)
        return output_d
    }

    /*
    * This function does the following python equivalent operation:
    * I_D = I_T * g_D + I_S * (1 - g_D)
    * */
    private fun multiplyAndAddTensors(tensor1: Tensor, tensor2: Tensor, scalar: Float): Tensor {
        val float1 = tensor1.dataAsFloatArray
        val float2 = tensor2.dataAsFloatArray
        val resulting = FloatArray(float1.size)

        for (i in resulting.indices) {
            resulting[i] = (scalar * float1[i]) + (float2[i] * (1-scalar))
        }
        return Tensor.fromBlob(resulting, longArrayOf(1, 3, tensor1.shape()[2], tensor1.shape()[3]))
    }

    private fun colorTempInterpolate(I_T: Tensor, I_S: Tensor): Tensor {
        val colorTemperatures = mapOf('T' to 2850, 'F' to 3800, 'D' to 5500, 'C' to 6500, 'S' to 7500)
        val cct1 = colorTemperatures['T']!!.toDouble()
        val cct2 = colorTemperatures['S']!!.toDouble()

        // Interpolation weight
        val cct1inv = 1.0 / cct1
        val cct2inv = 1.0 / cct2
        val tempinv_D = 1.0 / colorTemperatures['D']!!.toDouble()

        val g_D = (tempinv_D - cct2inv) / (cct1inv - cct2inv)

        val I_D = multiplyAndAddTensors(I_T, I_S, g_D.toFloat())
        Log.i("ID IT IS", "${I_T.shape().toList()} ${I_S.shape().toList()} ${I_D.shape().toList()}")
        return I_D
    }

    private fun tensor2Bitmap(input: FloatArray, width: Int, height: Int): Bitmap {
        val pixelsCount = height * width
        val pixels = IntArray(pixelsCount)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // mapping smallest value to 0 and largest value to 255
        val maxValue = input.max()
        val minValue = input.min()
        val delta = maxValue-minValue

        // Define if float min..max will be mapped to 0..255 or 255..0
        val conversion = { v: Float -> ((v-minValue)/delta*255.0).roundToInt()}

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
        val pixels = IntArray(width * height * 3)

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
        val Array = FloatArray(width * height * 3)
        for (i in 0 until width * height) {
            val pixel = pixels[i]
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            Array[3 * i] = red.toFloat()
            Array[3 * i + 1] = green.toFloat()
            Array[3 * i + 2] = blue.toFloat()
        }
        val bitmapTensor = Tensor.allocateFloatBuffer(width * height * 3)
        bitmapTensor.put(Array)

        return Tensor.fromBlob(bitmapTensor, longArrayOf(1, 3, height.toLong(), width.toLong()))
    }

    fun whiteBalance(rgbBitmap: Bitmap): Bitmap {
        val rgbTensor = TensorImageUtils.bitmapToFloat32Tensor(rgbBitmap, mean, std)
        val startTime = System.currentTimeMillis()

        Log.i("imageShape", "${rgbTensor.shape().toList()}")

        val outputs = deepWB(rgbTensor)
        Log.i("White Balancing", "Output Shape: ${outputs.shape().toList()}")

         val whiteBalancedBitmap = floatArrayToBitmap(outputs.dataAsFloatArray, outputs.shape()[3].toInt(), outputs.shape()[2].toInt())

        val duration = System.currentTimeMillis() - startTime
        Log.i("White Balancing" , "White balancing Duration: $duration ms")

        return whiteBalancedBitmap
    }
}