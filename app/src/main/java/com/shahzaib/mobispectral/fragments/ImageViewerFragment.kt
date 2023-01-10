package com.shahzaib.mobispectral.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.android.camera.utils.GenericListAdapter
import com.example.android.camera.utils.decodeExifOrientation
import com.google.android.material.tabs.TabLayoutMediator
import com.shahzaib.mobispectral.R
import com.shahzaib.mobispectral.databinding.FragmentImageviewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

class ImageViewerFragment : Fragment() {

    /** AndroidX navigation arguments */
    private val args: ImageViewerFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }
    private var _fragmentImageviewerBinding: FragmentImageviewerBinding? = null
    private val fragmentImageviewerBinding get() = _fragmentImageviewerBinding!!
    /** Default Bitmap decoding options */
    private val bitmapOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = false
        // Keep Bitmaps at less than 1 MP
        if (max(outHeight, outWidth) > DOWNSAMPLE_SIZE) {
            val scaleFactorX = outWidth / DOWNSAMPLE_SIZE + 1
            val scaleFactorY = outHeight / DOWNSAMPLE_SIZE + 1
            inSampleSize = max(scaleFactorX, scaleFactorY)
        }
    }

    /** Bitmap transformation derived from passed arguments */
    private val bitmapTransformation: Matrix by lazy { decodeExifOrientation(args.orientation) }

    /** Flag indicating that there is depth data available for this image */
    private val isDepth: Boolean by lazy { args.depth }

    /** Data backing our Bitmap viewpager */
    private val bitmapList: MutableList<Bitmap> = mutableListOf()

    private fun imageViewFactory() = ImageView(requireContext()).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentImageviewerBinding = FragmentImageviewerBinding.inflate(inflater, container, false)
        fragmentImageviewerBinding.viewpager.apply {
            offscreenPageLimit=2
            adapter = GenericListAdapter(bitmapList,
                itemViewFactory = { imageViewFactory() }) { view, item, _ ->
                view as ImageView
                Glide.with(view).load(item).into(view)
            }
        }
        TabLayoutMediator(
            fragmentImageviewerBinding.tabLayout,
            fragmentImageviewerBinding.viewpager
        ) { tab, position ->

        }.attach()
        return fragmentImageviewerBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch(Dispatchers.IO) {

            // Load input image file
            val (rgbbuffer, nirbuffer) = loadInputBuffer()

            // Load the main JPEG image
            val rgbImageBitmap = decodeBitmap(rgbbuffer, 0, rgbbuffer.size, "RGB")
            val nirImageBitmap = decodeBitmap(nirbuffer, 0, nirbuffer.size, "NIR")

            val rgbByteArray = BitmaptoByteArray(rgbImageBitmap)
            var nirByteArray = BitmaptoByteArray(nirImageBitmap)
            Log.i("RGB NIR ByteArray Sizes", "${rgbByteArray.size}, ${nirByteArray.size}")
            nirByteArray = getBand(nirByteArray, 1)
            Log.i("RGB NIR ByteArray Sizes", "${rgbByteArray.size}, ${nirByteArray.size}")
            addItemToViewPager(fragmentImageviewerBinding.viewpager, rgbImageBitmap)
            addItemToViewPager(fragmentImageviewerBinding.viewpager, nirImageBitmap)

            fragmentImageviewerBinding.button.setOnClickListener {
                val reconstructionDialogFragment = ReconstructionDialogFragment()
                reconstructionDialogFragment.show(childFragmentManager, ReconstructionDialogFragment.TAG)

                lifecycleScope.launch(Dispatchers.Main) {
                    navController.navigate(
                        ImageViewerFragmentDirections
                            .actionImageViewerFragmentToReconstructionFragment2(
                                serializeByteArraytoString(rgbImageBitmap),
                                serializeByteArraytoString(
                                    nirImageBitmap
                                )
                            )
                    )
                }
            }
            fragmentImageviewerBinding.reloadButton.setOnClickListener {
                lifecycleScope.launch(Dispatchers.Main) {
                    navController.navigate(
                        ImageViewerFragmentDirections
                            .actionImageViewerFragmentToCameraFragment("1", ImageFormat.JPEG)
                    )
                }
            }
        }
    }

    fun BitmaptoByteArray(bitmap: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos)
        return bos.toByteArray()
    }

    fun getBand(imageBuff: ByteArray, bandNumber: Int): ByteArray {
        val width = 640
        val height = 480

        val byteBuffer = ByteBuffer.allocate((width + 1) * (height + 1) * bandNumber)

        val startOffset = 0
        val endOffset = (bandNumber) * width * height - 1

        var tempValue :Byte

        var buff_idx = 0
        Log.i("Size", "${imageBuff.size}")
        for (i in 0 .. 56983) {
//            Log.i("Indices", "$i, $buff_idx")
            tempValue = imageBuff[i]
            byteBuffer.put(bandNumber * buff_idx, tempValue)
            buff_idx += 1
        }

        val byteArray = ByteArray(byteBuffer.capacity())
        byteBuffer.get(byteArray)
        return byteArray
    }

    /** Utility function used to read input file into a byte array */
    private fun loadInputBuffer(): Pair<ByteArray, ByteArray> {
        val rgbFile = File(args.filePath)
        val nirFile = File(args.filePath2)
        val rgbImage = BufferedInputStream(rgbFile.inputStream()).let { stream ->
            ByteArray(stream.available()).also {
                stream.read(it)
                stream.close()
            }
        }
        val nirImage = BufferedInputStream(nirFile.inputStream()).let { stream ->
            ByteArray(stream.available()).also {
                stream.read(it)
                stream.close()
            }
        }
        return Pair(rgbImage, nirImage)
    }

    /** Utility function used to add an item to the viewpager and notify it, in the main thread */
    private fun addItemToViewPager(view: ViewPager2, item: Bitmap) = view.post {
        bitmapList.add(item)
        view.adapter!!.notifyDataSetChanged()
    }

    fun serializeByteArraytoString(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    /** Utility function used to decode a [Bitmap] from a byte array */
    private fun decodeBitmap(buffer: ByteArray, start: Int, length: Int, RGB: String): Bitmap {
        var bitmap: Bitmap

        // Load bitmap from given buffer
        val decodedbitmap = BitmapFactory.decodeByteArray(buffer, start, length, bitmapOptions)
        bitmap = if (RGB == "RGB")
            Bitmap.createBitmap(decodedbitmap, 83, 100,
                640, 480, Matrix().apply { postScale(-1.0F, 1.0F); postRotate(90F) }, true)
        else
            Bitmap.createBitmap(decodedbitmap, 0, 0, decodedbitmap.width, decodedbitmap.height,
                Matrix().apply { postScale(-1.0F, 1.0F); postRotate(90F) }, false)

        // Transform bitmap orientation using provided metadata
        return bitmap
    }

    companion object {
        private val TAG = ImageViewerFragment::class.java.simpleName

        /** Maximum size of [Bitmap] decoded */
        private const val DOWNSAMPLE_SIZE: Int = 1024  // 1MP

        /** These are the magic numbers used to separate the different JPG data chunks */
        private val JPEG_DELIMITER_BYTES = arrayOf(-1, -39)

        /**
         * Utility function used to find the markers indicating separation between JPEG data chunks
         */
        private fun findNextJpegEndMarker(jpegBuffer: ByteArray, start: Int): Int {

            // Sanitize input arguments
            assert(start >= 0) { "Invalid start marker: $start" }
            assert(jpegBuffer.size > start) {
                "Buffer size (${jpegBuffer.size}) smaller than start marker ($start)" }

            // Perform a linear search until the delimiter is found
            for (i in start until jpegBuffer.size - 1) {
                if (jpegBuffer[i].toInt() == JPEG_DELIMITER_BYTES[0] &&
                        jpegBuffer[i + 1].toInt() == JPEG_DELIMITER_BYTES[1]) {
                    return i + 2
                }
            }

            // If we reach this, it means that no marker was found
            throw RuntimeException("Separator marker not found in buffer (${jpegBuffer.size})")
        }
    }
}
