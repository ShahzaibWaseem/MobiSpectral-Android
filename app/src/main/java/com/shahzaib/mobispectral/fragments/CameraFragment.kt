package com.shahzaib.mobispectral.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.getPreviewOutputSize
import com.shahzaib.mobispectral.MainActivity
import com.shahzaib.mobispectral.R
import com.shahzaib.mobispectral.Utils
import com.shahzaib.mobispectral.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraFragment: Fragment() {
    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    /** AndroidX navigation arguments */
    private val args: CameraFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = requireContext().applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(args.cameraId)
    }

    /** Readers used as buffers for camera still shots */
    private lateinit var imageReader: ImageReader

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** The [CameraDevice] that will be opened in this fragment */
    private lateinit var camera: CameraDevice

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private lateinit var session: CameraCaptureSession

    private lateinit var cameraIdRGB: String
    private lateinit var cameraIdNIR: String

    private lateinit var sharedPreferences: SharedPreferences
    private var mobiSpectralApplicationID = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        sharedPreferences = requireActivity().getSharedPreferences("mobispectral_preferences", Context.MODE_PRIVATE)
        mobiSpectralApplicationID =
            when(sharedPreferences.getString("application", "Organic Non-Organic Apple Classification")!!) {
            "Shelf Life Prediction" -> MainActivity.SHELF_LIFE_APPLICATION
            else -> MainActivity.MOBISPECTRAL_APPLICATION
        }
        cameraIdRGB = Utils.getCameraIDs(requireContext(), mobiSpectralApplicationID).first
        cameraIdNIR = Utils.getCameraIDs(requireContext(), mobiSpectralApplicationID).second
        Log.i("CameraIDs Fragment", "RGB $cameraIdRGB, NIR $cameraIdNIR")
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fragmentCameraBinding.information.setOnClickListener {
            val builder: AlertDialog.Builder = AlertDialog.Builder(context)
            builder.setMessage(R.string.capture_information_string)
            builder.setTitle("Information")
            builder.setPositiveButton("Okay") {
                dialog: DialogInterface?, _: Int -> dialog?.cancel()
            }
            val alertDialog = builder.create()
            alertDialog.setOnShowListener {
                alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(requireContext(), R.color.sfu_primary))
            }
            alertDialog.show()
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int,
                                        height: Int) = Unit

            override fun surfaceCreated(holder: SurfaceHolder) {
                // Selects appropriate preview size and configures view finder
                val previewSize = getPreviewOutputSize(fragmentCameraBinding.viewFinder.display,
                    characteristics, SurfaceHolder::class.java)
//                fragmentCameraBinding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)
                holder.setFixedSize(previewSize.width, previewSize.height)

                Log.i("Preview Size", "AutoFitSurface Holder: Width${fragmentCameraBinding.viewFinder.width}, Height ${fragmentCameraBinding.viewFinder.height}")

                // To ensure that size is set, initialize camera in the view's thread
                view.post { initializeCamera() }
            }
        })

        fragmentCameraBinding.Title.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                navController.navigate(
                    CameraFragmentDirections
                        .actionCameraToApplicationsTitle()
                )
            }
        }

        fragmentCameraBinding.reloadButton.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                navController.navigate(
                    CameraFragmentDirections
                        .actionCameraToApplications()
                )
            }
        }
    }

    /**
     * Begin all camera operations in a coroutine in the main thread. This function:
     * - Opens the camera
     * - Configures the camera session
     * - Starts the preview by dispatching a repeating capture request
     * - Sets up the still image capture listeners
     */
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        val size = Size(Utils.previewWidth, Utils.previewHeight)
        Log.i("Size", "$size")

        imageReader = ImageReader.newInstance(
            size.width, size.height, args.pixelFormat, IMAGE_BUFFER_SIZE
        )

        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        ).apply { addTarget(fragmentCameraBinding.viewFinder.holder.surface)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO) }
//        Log.i("Surface Holder Size", "${fragmentCameraBinding.viewFinder.holder.surface}")

        // This will keep sending the capture request as frequently as possible until the
        // session is torn down or session.stopRepeating() is called
        session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

        if (args.cameraId == cameraIdNIR) {
            fragmentCameraBinding.captureButton.performClick()
            fragmentCameraBinding.captureButton.isPressed = true
            fragmentCameraBinding.captureButton.invalidate()
        }

        // Listen to the capture button
        if (args.cameraId == cameraIdRGB) {
            fragmentCameraBinding.captureButton.setOnClickListener {
                if (args.cameraId == cameraIdNIR) {
                    fragmentCameraBinding.captureButton.isPressed = false
                    fragmentCameraBinding.captureButton.invalidate()
                }
                // Disable click listener to prevent multiple requests simultaneously in flight
                it.isEnabled = false

                savePhoto(args.cameraId)
                // Re-enable click listener after photo is taken
                it.post { it.isEnabled = true }
            }
        }
        else {
            savePhoto(args.cameraId)
        }
    }

    private fun savePhoto(cameraId: String) {
        // Perform I/O heavy operations in a different scope
        lifecycleScope.launch(Dispatchers.IO) {
            takePhoto().use { result ->
                Log.d(TAG, "Result received: $result")

                // Save the result to disk
                val output = saveResult(result)
                Log.d(TAG, "Image saved: ${output.absolutePath}")

                if (cameraId == cameraIdRGB){
                    if (cameraIdNIR == "Shelf Life Prediction") {
                        lifecycleScope.launch(Dispatchers.Main) {
                            navController.navigate(
                                CameraFragmentDirections.actionCameraFragmentToAudioFragment(rgbAbsolutePath, fileFormat)
                            )
                        }
                    }
                    else {
                        // Display the photo taken to user
                        lifecycleScope.launch(Dispatchers.Main) {
                            navController.navigate(
                                CameraFragmentDirections.actionCameraFragmentSelf(cameraIdNIR, ImageFormat.JPEG)
                            )
                        }
                    }
                }
                else {
                    // Display the photo taken to user
                    lifecycleScope.launch(Dispatchers.Main) {
                        navController.navigate(
                            CameraFragmentDirections.actionCameraToJpegViewer(rgbAbsolutePath, output.absolutePath)
                                .setDepth(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                            result.format == ImageFormat.DEPTH_JPEG)
                        )
                    }
                }
            }
        }
    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        Log.i("CameraID", cameraId)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    @Suppress("DEPRECATION")
    private suspend fun createCaptureSession(device: CameraDevice, targets: List<Surface>, handler: Handler? = null)
            : CameraCaptureSession = suspendCoroutine { cont ->
        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
     * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
     * from the single capture, and outputs a [CombinedCaptureResult] object.
     */
    private suspend fun takePhoto(): CombinedCaptureResult
            = suspendCoroutine { cont ->

        // Flush any images left in the image reader
        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) {
        }

        // Start a new image queue
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")

            imageQueue.add(image)
        }, imageReaderHandler)

        val captureRequest = session.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE
        ).apply { addTarget(imageReader.surface) }
        session.capture(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)

                val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                Log.d(TAG, "Capture result received: $resultTimestamp")

                // Set a timeout in case image captured is dropped from the pipeline
                val exc = TimeoutException("Image dequeuing took too long")
                val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
                imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

                // Loop in the coroutine's context until an image with matching timestamp comes
                // We need to launch the coroutine context again because the callback is done in
                //  the handler provided to the `capture` method, not in our coroutine context
                @Suppress("BlockingMethodInNonBlockingContext")
                lifecycleScope.launch(cont.context) {
                    while (true) {
                        // Dequeue images while timestamps don't match
                        val image = imageQueue.take()

                        // if (image.timestamp != resultTimestamp) continue
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            image.format != ImageFormat.DEPTH_JPEG &&
                            image.timestamp != resultTimestamp
                        ) continue
                        Log.d(TAG, "Matching image dequeued: ${image.timestamp}")

                        // Unset the image reader listener
                        imageReaderHandler.removeCallbacks(timeoutRunnable)
                        imageReader.setOnImageAvailableListener(null, null)

                        // Clear the queue of images, if there are left
                        while (imageQueue.size > 0) {
                            imageQueue.take().close()
                        }
                        Log.d(TAG, "Capture Request DIMENSIONS: width ${image.width} Height: ${image.height}")

                        // Build the result and resume progress
                        cont.resume(CombinedCaptureResult(image, result, imageReader.imageFormat))
                        // There is no need to break out of the loop, this coroutine will suspend
                    }
                }
            }
        }, cameraHandler)
    }

    private fun isDark(bitmap: Bitmap): Boolean {
        val histogram = IntArray(256)

        for (i in 0..255) {
            histogram[i] = 0
        }

        var dark = false
        val darkThreshold = 0.25F
        var darkPixels = 0
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (color in pixels) {
            val r: Int = Color.red(color)
            val g: Int = Color.green(color)
            val b: Int = Color.blue(color)
            val brightness = (0.2126*r + 0.7152*g + 0.0722*b).toInt()
            histogram[brightness]++
        }

        for (i in 0..9)
            darkPixels += histogram[i]
        if (darkPixels > (bitmap.height * bitmap.width) * darkThreshold) {
            dark = true
        }
        Log.i("Dark", "DarkPixels: $darkPixels")
        return dark
    }
    /** Helper function used to save a [CombinedCaptureResult] into a [File] */
    private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
        when (result.format) {
            // When the format is JPEG or DEPTH JPEG we can simply save the bytes as-is
            ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
                val buffer = result.image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }

                var rotatedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null)
                val correctionMatrix = Matrix().apply { postRotate(-90F); postScale(-1F, 1F); }
                rotatedBitmap = Bitmap.createBitmap(rotatedBitmap, 0, 0, rotatedBitmap.width, rotatedBitmap.height, correctionMatrix, true)
                val stream = ByteArrayOutputStream()
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                val rotatedBytes = stream.toByteArray()

                try {
                    val nir = if (args.cameraId == cameraIdNIR) "NIR" else "RGB"

                    if (isDark(rotatedBitmap)) {
                        Log.i("Dark", "The bitmap is too dark")
                        fragmentCameraBinding.illumination.text = resources.getString(R.string.formatted_illumination_string, "Inadequate")
                        fragmentCameraBinding.illumination.setTextColor(ContextCompat.getColor(requireContext(), R.color.design_default_color_error))
                        Toast.makeText(context, "The bitmap is too dark", Toast.LENGTH_SHORT).show()
                    }
                    else {
                        fragmentCameraBinding.illumination.text = resources.getString(R.string.formatted_illumination_string, "Adequate")
                        fragmentCameraBinding.illumination.setTextColor(ContextCompat.getColor(requireContext(), R.color.design_default_color_secondary))
                    }
                    val output = createFile(requireContext(), nir)
                    FileOutputStream(output).use { it.write(rotatedBytes) }
                    cont.resume(output)
                    Log.i("Filename", output.toString())
                } catch (exc: IOException) {
                    Log.e(TAG, "Unable to write JPEG image to file", exc)
                    cont.resumeWithException(exc)
                }
            }

            // No other formats are supported by this sample
            else -> {
                val exc = RuntimeException("Unknown image format: ${result.image.format}")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName
        private lateinit var rgbAbsolutePath: String
        private lateinit var fileFormat: String

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 3

        /** Maximum time allowed to wait for the result of an image capture */
        private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

        /** Helper data class used to hold capture metadata with their associated image */
        data class CombinedCaptureResult(
            val image: Image,
            val metadata: CaptureResult,
            val format: Int
        ) : Closeable {
            override fun close() = image.close()
        }

        /**
         * Create a [File] named a using formatted timestamp with the current date and time.
         *
         * @return [File] created.
         */
        private fun createFile(context: Context, nir: String): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            Log.i("Filename", sdf.toString())
            fileFormat = sdf.format(Date())
            val file = File(context.filesDir, "IMG_${fileFormat}_$nir.jpg")
            if (nir == "RGB")
                rgbAbsolutePath = file.absolutePath
            return file
        }
    }
}