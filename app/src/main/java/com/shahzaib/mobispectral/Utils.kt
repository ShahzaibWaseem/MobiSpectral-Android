package com.shahzaib.mobispectral

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object Utils {
    const val previewHeight = 800
    const val previewWidth = 600
    const val torchHeight = 640
    const val torchWidth = 480
    const val aligningFactorX = 37  //This is 37 if picture captured in portrait 83 if landscape
    const val aligningFactorY = 100 //This stays 100
    const val croppedHeight = 400
    const val croppedWidth = 300

    // Intrinsic Camera Parameters for Google Pixel 4 IR camera
    val K_Pixel = doubleArrayOf(542.6309295733048, 0.0, 232.78423096084575, 0.0, 542.8318136934467, 337.77984524454547, 0.0, 0.0, 1.0)
    val D_Pixel = doubleArrayOf(-0.04591876756027188, 0.4810113550965089, -1.1137694861149858, 0.4336328878297329)

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

    fun getCameraIDs(context: Context, application: Int): Pair<String, String> {
        var cameraIdRGB = ""
        var cameraIdNIR = ""
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        var cameraList = enumerateCameras(cameraManager)
        if (application == MainActivity.MOBISPECTRAL_APPLICATION) {
            cameraList = getMobiSpectralConfigCameras(cameraList)

            for (camera in cameraList) {
                Log.i("Available Cameras", camera.title)
                if (camera.sensorArrangement == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR)
                    cameraIdNIR = camera.cameraId
                else
                    cameraIdRGB = camera.cameraId
            }
            // OnePlus has hidden their Photochrom camera, so accessing it via Intent.
            if (cameraIdNIR == "") {
                cameraIdNIR = if (Build.PRODUCT == "OnePlus8Pro") "OnePlus" else "No NIR Camera"
                cameraIdRGB = if (Build.PRODUCT == "OnePlus8Pro") "0" else cameraIdRGB
            }
        }
        else {
            cameraIdRGB = cameraList[0].cameraId
            cameraIdNIR = "Shelf Life Prediction"
        }
        Log.i("Camera", "$cameraIdRGB $cameraIdNIR")
        return Pair(cameraIdRGB, cameraIdNIR)
    }

    fun initializeMats(parameter: DoubleArray, size: Size): Mat {
        val mat: Mat = Mat(size, 0)
        var iter: Int = 0
        for (row in 0..2) {
            for (col in 0..2) {
                mat.put(row, col, parameter[iter])
                iter+=1
            }
        }
        return mat
    }

    fun unwarp(imageNIR: Bitmap) {
        val K: Mat = initializeMats(K_Pixel, Size(3.0, 3.0))
        val D: Mat = initializeMats(D_Pixel, Size(4.0, 1.0))
//        Imgproc.initUndistortRectifyMap()
    }

    fun fixedAlignment(imageRGB: Bitmap): Bitmap{
        Log.i("Aligned RGB", "$aligningFactorX + $torchWidth = ${torchWidth + aligningFactorX} (${imageRGB.width})")
        Log.i("Aligned RGB", "$aligningFactorY + $torchHeight = ${torchHeight + aligningFactorY} (${imageRGB.height})")
        val alignedImageRGB = Bitmap.createBitmap(imageRGB, aligningFactorX, aligningFactorY, torchWidth, torchHeight, null, true)
        Log.i("Aligned RGB", "Resulting Bitmap: W ${alignedImageRGB.width} H ${alignedImageRGB.height}")
        return alignedImageRGB
    }

    fun alignImages(image1: Bitmap, image2: Bitmap): Bitmap {
        val image1Mat = Mat(image1.height, image1.width, CvType.CV_8UC3)
        val image2Mat = Mat(image2.height, image2.width, CvType.CV_8UC3)
        Utils.bitmapToMat(image1, image1Mat)
        Utils.bitmapToMat(image2, image2Mat)

        val image1Gray = Mat(image1.height, image1.width, CvType.CV_8UC1)
        val image2Gray = Mat(image2.height, image2.width, CvType.CV_8UC1)

        Imgproc.cvtColor(image1Mat, image1Gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.cvtColor(image2Mat, image2Gray, Imgproc.COLOR_RGB2GRAY)

        val warpMode = Video.MOTION_TRANSLATION

        val warpMatrix = if (warpMode == Video.MOTION_HOMOGRAPHY) Mat.eye(3, 3, CvType.CV_32F) else Mat.eye(2, 3, CvType.CV_32F)
        val iterations = 10000
        val terminationEps = 1e-10
        val terminationCriteria = TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, iterations, terminationEps)
        val image2Aligned = Mat(image1.height, image1.width, CvType.CV_8UC3)

        Video.findTransformECC(image1Gray, image2Gray, warpMatrix, warpMode, terminationCriteria)
        if (warpMode == Video.MOTION_HOMOGRAPHY)
            Imgproc.warpPerspective(image2Mat, image2Aligned, warpMatrix, image1Mat.size(), Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP)
        else
            Imgproc.warpAffine(image2Mat, image2Aligned, warpMatrix, image1Mat.size(), Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP)

        val img2Threshold = Mat(image2Gray.height(), image2Gray.width(), CvType.CV_8UC1)
        val contours: List<MatOfPoint> = ArrayList<MatOfPoint>()
        val hierarchy = Mat()

        Imgproc.cvtColor(image2Aligned, image2Gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.threshold(image2Gray, img2Threshold, 1.0, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.findContours(img2Threshold, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val cropCoordinates = Imgproc.boundingRect(contours[0])
        val image2AlignedCropped = image2Aligned.submat(cropCoordinates)
        val image2AlignedCroppedBitmap = Bitmap.createBitmap(image2AlignedCropped.width(), image2AlignedCropped.height(), Bitmap.Config.RGB_565)
        Utils.matToBitmap(image2AlignedCropped, image2AlignedCroppedBitmap)

        return image2AlignedCroppedBitmap
    }
}

/** Helper class used as a data holder for each selectable camera format item */
data class FormatItem(val title: String, val cameraId: String, val format: Int, val orientation: String, val sensorArrangement: Int)

data class MobiSpectralCSVFormat(val originalImageRGB: String, val originalImageNIR: String,
                                 val processedImageRGB: String, val processedImageNIR: String,
                                 val actualLabel: String, val predictedLabel: String,
                                 val memoryConsumption: Long)
data class ShelfLifeCSV(val image_path: String, val audio_path: String)

/** Helper function used to convert a lens orientation enum into a human-readable string */
private fun lensOrientationString(value: Int) = when(value) {
    CameraCharacteristics.LENS_FACING_BACK -> "Back"
    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
    else -> "Unknown"
}

fun getMobiSpectralConfigCameras(availableCameras: MutableList<FormatItem>): MutableList<FormatItem> {
    val usableCameraList: MutableList<FormatItem> = mutableListOf()

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
    return availableCameras
}

/** Helper function used to list all compatible cameras and supported pixel formats */
@SuppressLint("InlinedApi")
fun enumerateCameras(cameraManager: CameraManager): MutableList<FormatItem> {
    val availableCameras: MutableList<FormatItem> = mutableListOf()

    // Get list of all compatible cameras
    val cameraIds = cameraManager.cameraIdList.filter {
        val characteristics = cameraManager.getCameraCharacteristics(it)
        val orientation = lensOrientationString(characteristics.get(CameraCharacteristics.LENS_FACING)!!)
        val isNIR = if(characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR) "NIR" else "RGB"

        Log.i("ALl Cameras", "${characteristics.physicalCameraIds}")
        Log.i("All Cameras", "$it, $isNIR, $orientation")

        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
    }

    // Iterate over the list of cameras and return all the compatible ones
    cameraIds.forEach { id ->
        val characteristics = cameraManager.getCameraCharacteristics(id)
        val orientation = lensOrientationString(characteristics.get(CameraCharacteristics.LENS_FACING)!!)
        val isNIR = if(characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR) "NIR" else "RGB"

        // All cameras *must* support JPEG output so we don't need to check characteristics
        // Return cameras that support NIR Filter Arrangement
        if (isNIR == "NIR")
            availableCameras.add(FormatItem("$orientation JPEG ($id) $isNIR", id, ImageFormat.JPEG,
                orientation, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR))
        else
            availableCameras.add(FormatItem("$orientation JPEG ($id)", id, ImageFormat.JPEG,
                orientation, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB))
    }

    return availableCameras
}