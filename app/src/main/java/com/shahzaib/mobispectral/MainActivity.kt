package com.shahzaib.mobispectral

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.shahzaib.mobispectral.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)
        makeDirectory(Utils.rawImageDirectory)
        makeDirectory(Utils.croppedImageDirectory)
        makeDirectory(Utils.processedImageDirectory)
    }

    companion object {
        const val MOBISPECTRAL_APPLICATION = 0
        const val SHELF_LIFE_APPLICATION = 1
        lateinit var fruitID: String
        lateinit var originalImageRGB: String
        lateinit var originalImageNIR: String
        lateinit var processedImageRGB: String
        lateinit var processedImageNIR: String
        lateinit var croppedImageRGB: String
        lateinit var croppedImageNIR: String
        var actualLabel: String = ""
        lateinit var predictedLabel: String
        lateinit var normalizationTime: String
        lateinit var reconstructionTime: String
        lateinit var classificationTime: String
        lateinit var tempRGBBitmap: Bitmap
        lateinit var tempRectangle: Rect
    }
}