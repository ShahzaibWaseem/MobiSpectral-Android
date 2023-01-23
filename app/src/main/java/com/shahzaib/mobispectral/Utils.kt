package com.shahzaib.mobispectral

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object Utils {
    const val previewHeight = 800
    const val previewWidth = 600
    const val torchHeight = 640
    const val torchWidth = 480
    const val aligningFactorX = 83
    const val aligningFactorY = 100

    fun assetFilePath(context: Context, assetName: String): String? {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        try {
            context.assets.open(assetName).use { `is` ->
                FileOutputStream(file).use { os ->
                    val buffer = ByteArray(4 * 1024)
                    var read: Int
                    while (`is`.read(buffer).also { read = it } != -1) {
                        os.write(buffer, 0, read)
                    }
                    os.flush()
                }
                return file.absolutePath
            }
        } catch (e: IOException) {
            Log.e("PyTorch Android", "Error process asset $assetName to file path")
        }
        return null
    }

    fun getCameraIDs(context: Context): Pair<String, String> {
        var cameraIdRGB = ""
        var cameraIdNIR = ""
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraList = enumerateCameras(cameraManager)

        for (camera in cameraList) {
            Log.i("Available Cameras", camera.title)
            if (camera.sensorArrangement == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR)
                cameraIdNIR = camera.cameraId
            if (camera.sensorArrangement == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB)
                cameraIdRGB = camera.cameraId
        }
        if (cameraIdNIR == "")
            cameraIdNIR = "No NIR Camera"
        return Pair(cameraIdRGB, cameraIdNIR)
    }
}

/** Helper class used as a data holder for each selectable camera format item */
data class FormatItem(val title: String, val cameraId: String, val format: Int, val orientation: String, val sensorArrangement: Int)

/** Helper function used to convert a lens orientation enum into a human-readable string */
private fun lensOrientationString(value: Int) = when(value) {
    CameraCharacteristics.LENS_FACING_BACK -> "Back"
    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
    else -> "Unknown"
}

/** Helper function used to list all compatible cameras and supported pixel formats */
@SuppressLint("InlinedApi")
fun enumerateCameras(cameraManager: CameraManager): List<FormatItem> {
    val availableCameras: MutableList<FormatItem> = mutableListOf()
    val usableCameraList: MutableList<FormatItem> = mutableListOf()

    // Get list of all compatible cameras
    val cameraIds = cameraManager.cameraIdList.filter {
        val characteristics = cameraManager.getCameraCharacteristics(it)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
    }

    // Iterate over the list of cameras and return all the compatible ones
    cameraIds.forEach { id ->
        val characteristics = cameraManager.getCameraCharacteristics(id)
        val orientation = lensOrientationString(characteristics.get(CameraCharacteristics.LENS_FACING)!!)

        // All cameras *must* support JPEG output so we don't need to check characteristics
        // Return cameras that support NIR Filter Arrangement
        if (characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR)
            availableCameras.add(
                FormatItem("$orientation JPEG ($id) NIR", id, ImageFormat.JPEG, orientation,
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR))

        else if (characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB)
            availableCameras.add(
                FormatItem("$orientation JPEG ($id)", id, ImageFormat.JPEG, orientation,
                    CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB))

        for (camera in availableCameras) {
            if (camera.sensorArrangement == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR) {
                val nirLensOrientation = camera.orientation
                usableCameraList.add(camera)
                for (other_camera in availableCameras) {
                    if ((other_camera.orientation) == nirLensOrientation && other_camera.cameraId != camera.cameraId) {
                        usableCameraList.add(other_camera)
                    }
                }
            }
        }
    }

    return usableCameraList
}