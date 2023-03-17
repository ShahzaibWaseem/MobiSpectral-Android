package com.shahzaib.mobispectral

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.net.toUri
import com.opencsv.CSVWriter
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException

object Utils {
    const val previewHeight = 800
    const val previewWidth = 600
    const val torchHeight = 640
    const val torchWidth = 480
    const val aligningFactorX = 37  //This is 37 if picture captured in portrait [35-41 if un-warped] 83 if landscape
    const val aligningFactorY = 87  //This is 83 if picture captured in portrait [74 if un-warped] 100 if landscape
    const val croppedHeight = 400
    const val croppedWidth = 300
    const val MobiSpectralPath = "MobiSpectral/processedImages"

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

    private fun initializeMats(parameter: DoubleArray, width: Int, height: Int): Mat {
        val mat: Mat = Mat(height, width, CvType.CV_64FC1)
        var iter: Int = 0
        for (row in 0 until height) {
            print("$row ")
            for (col in 0 until width) {
                print("$col ")

                mat.put(row, col, parameter[iter])
                iter+=1
            }
        }
        return mat
    }

    fun unwarp(imageNIR: Bitmap): Bitmap {
        val K: Mat = initializeMats(K_Pixel, 3, 3)
        val D: Mat = initializeMats(D_Pixel, 4, 1)
//        val K: Mat = Mat(3, 3, CvType.CV_32F)
//        K.put(0, 0, K_Pixel)
//
//        val nK: Mat = K.clone()
//        val D: Mat = Mat(4, 1,  CvType.CV_32F)
//        D.put(0, 0, D_Pixel)
//        nK.put(0, 0, nK.get(0, 0)[0]/2.0)
//        nK.put(1, 1, nK.get(1, 1)[0]/2.0)
        Log.i("Parameters", "${K.dump()} ${D.dump()} ${imageNIR.config}")

        val map1 = Mat()
        val map2 = Mat()

        val nirMat = Mat()
        Utils.bitmapToMat(imageNIR, nirMat)

        val width = imageNIR.width.toDouble()
        val height = imageNIR.height.toDouble()
        val fixedNirMat = Mat()

        Imgproc.initUndistortRectifyMap(K, D, Mat.eye(3, 3, 0), K, Size(width, height), CvType.CV_16SC2, map1, map2)
        Imgproc.remap(nirMat, fixedNirMat, map1, map2, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT)
        val fixedImageBitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(fixedNirMat, fixedImageBitmap)
        return fixedImageBitmap
    }

    fun cropImage(bitmap: Bitmap, left: Float, top: Float): Bitmap {
        return Bitmap.createBitmap(bitmap, left.toInt(), top.toInt(), 128, 128, null, true)
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
lateinit var csvFile: File

/** Helper class used as a data holder for each selectable camera format item */
data class FormatItem(val title: String, val cameraId: String, val format: Int, val orientation: String, val sensorArrangement: Int)

data class MobiSpectralCSVFormat(val fruitID: String, val originalImageRGB: String,
                                 val originalImageNIR: String, val processedImageRGB: String,
                                 val processedImageNIR: String, val actualLabel: String,
                                 val predictedLabel: String, val reconstructionTime: String,
                                 val classificationTime: String) {
    fun csvFormat(): Array<String> {
        return arrayOf(fruitID, originalImageRGB, originalImageNIR, processedImageRGB, processedImageNIR,
            actualLabel, predictedLabel, reconstructionTime)
    }
}
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

fun makeFolderInRoot (directoryPath: String, context: Context) {
    val externalStorageDirectory = Environment.getExternalStorageDirectory().toString()
    val directory = File(externalStorageDirectory, "/$directoryPath")
    if (!directory.exists()) {
        directory.mkdirs()
    }
    csvFile = File(directory.parent, "MobiSpectralLogs.csv")
    if (! csvFile.exists()) {
        val header = MobiSpectralCSVFormat("Fruit ID", "Original RGB Path", "Original NIR Path",
            "Processed RGB Path", "Processed NIR Path",
            "Actual Label", "Predicted Label", "Reconstruction Time", "Classification Time")

        val writer = CSVWriter(
            FileWriter(csvFile, false),
            ',',
            CSVWriter.NO_QUOTE_CHARACTER,
            CSVWriter.DEFAULT_ESCAPE_CHARACTER,
            "\r\n"
        )
        writer.writeNext(header.csvFormat())
        writer.close()
        MediaScannerConnection.scanFile(context, arrayOf(csvFile.absolutePath), null, null)
    }
}

fun saveProcessedImages (rgbBitmap: Bitmap, nirBitmap: Bitmap, rgbFileName: String, nirFileName: String) {
    val externalStorageDirectory = Environment.getExternalStorageDirectory().toString()
    val rootDirectory = File(externalStorageDirectory, "/MobiSpectral")
    val imageDirectory = File(rootDirectory, "/processedImages")
    if (!imageDirectory.exists()) {
        imageDirectory.mkdirs()
    }
    val rgbImage = File(imageDirectory, rgbFileName)
    val nirImage = File(imageDirectory, nirFileName)

    MainActivity.processedImageRGB = rgbImage.absolutePath.toUri().toString()
    MainActivity.processedImageNIR = nirImage.absolutePath.toUri().toString()

    Log.i("FilePath", "${MainActivity.originalImageRGB} ${MainActivity.originalImageNIR} ${MainActivity.processedImageRGB}, ${MainActivity.processedImageNIR}")

    Thread {
        try {
            var fos = FileOutputStream(rgbImage)
            rgbBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            fos.flush()
            fos.close()

            fos = FileOutputStream(nirImage)
            nirBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            fos.flush()
            fos.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }.start()
}

fun addCSVLog (context: Context) {
    if (csvFile.exists()) {
        val entry = MobiSpectralCSVFormat(MainActivity.fruitID, MainActivity.originalImageRGB, MainActivity.originalImageNIR,
            MainActivity.processedImageRGB, MainActivity.processedImageNIR, MainActivity.actualLabel,
            MainActivity.predictedLabel, MainActivity.reconstructionTime, MainActivity.classificationTime)

        val writer = CSVWriter(
            FileWriter(csvFile, true),
            ',',
            CSVWriter.NO_QUOTE_CHARACTER,
            CSVWriter.DEFAULT_ESCAPE_CHARACTER,
            "\r\n"
        )
        writer.writeNext(entry.csvFormat())
        writer.close()
        MediaScannerConnection.scanFile(context, arrayOf(csvFile.absolutePath), null, null)
    }
    else
        makeFolderInRoot(com.shahzaib.mobispectral.Utils.MobiSpectralPath, context)
}