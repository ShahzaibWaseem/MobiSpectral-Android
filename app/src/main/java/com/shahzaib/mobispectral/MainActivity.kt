package com.shahzaib.mobispectral

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shahzaib.mobispectral.databinding.ActivityMainBinding
import com.shahzaib.mobispectral.fragments.CameraFragment
import com.shahzaib.mobispectral.fragments.CameraFragmentDirections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)
    }
    companion object {
        val MOBISPECTRAL_APPLICATION = 0
        val SHELF_LIFE_APPLICATION = 1
        lateinit var fruitID: String
        lateinit var originalImageRGB: String
        lateinit var originalImageNIR: String
        lateinit var processedImageRGB: String
        lateinit var processedImageNIR: String
        lateinit var actualLabel: String
        lateinit var predictedLabel: String
        lateinit var reconstrutionTime: String
    }
}