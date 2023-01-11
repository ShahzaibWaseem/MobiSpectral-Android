package com.shahzaib.mobispectral.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.os.Bundle
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
        TabLayoutMediator(fragmentImageviewerBinding.tabLayout,
            fragmentImageviewerBinding.viewpager) { _, _ -> }.attach()
        return fragmentImageviewerBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch(Dispatchers.IO) {
            // Load input image file
            val (bufferRGB, bufferNIR) = loadInputBuffer()

            // Load the main JPEG image
            val rgbImageBitmap = decodeBitmap(bufferRGB, 0, bufferRGB.size, "RGB")
            val nirImageBitmap = decodeBitmap(bufferNIR, 0, bufferNIR.size, "NIR")

            val rgbByteArray = bitmapToByteArray(rgbImageBitmap)
            var nirByteArray = bitmapToByteArray(nirImageBitmap)
            Log.i("RGB NIR ByteArray Sizes", "${rgbByteArray.size}, ${nirByteArray.size}")
            nirByteArray = getBand(nirByteArray, 1)
            Log.i("RGB NIR ByteArray Sizes", "${rgbByteArray.size}, ${nirByteArray.size}")
            addItemToViewPager(fragmentImageviewerBinding.viewpager, rgbImageBitmap)
            addItemToViewPager(fragmentImageviewerBinding.viewpager, nirImageBitmap)

            fragmentImageviewerBinding.button.setOnClickListener {
                lifecycleScope.launch(Dispatchers.Main) {
                    navController.navigate(
                        ImageViewerFragmentDirections
                            .actionImageViewerFragmentToReconstructionFragment2(
                                serializeByteArraytoString(rgbImageBitmap),
                                serializeByteArraytoString(nirImageBitmap))
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

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos)
        return bos.toByteArray()
    }

    private fun getBand(imageBuff: ByteArray, bandNumber: Int): ByteArray {
        val width = 640
        val height = 480

        val byteBuffer = ByteBuffer.allocate((width + 1) * (height + 1) * bandNumber)

        val startOffset = 0
        val endOffset = (bandNumber) * width * height - 1

        var tempValue :Byte

        var buffIdx = 0
        Log.i("Size", "${imageBuff.size}")
        for (i in startOffset .. 56983) {
            tempValue = imageBuff[i]
            byteBuffer.put(bandNumber * buffIdx, tempValue)
            buffIdx += 1
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

    private fun serializeByteArraytoString(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    /** Utility function used to decode a [Bitmap] from a byte array */
    private fun decodeBitmap(buffer: ByteArray, start: Int, length: Int, RGB: String): Bitmap {
        val bitmap: Bitmap

        // Load bitmap from given buffer
        val decodedBitmap = BitmapFactory.decodeByteArray(buffer, start, length, bitmapOptions)
        bitmap = if (RGB == "RGB")
            Bitmap.createBitmap(decodedBitmap, 83, 100,
                640, 480, Matrix().apply { postScale(-1.0F, 1.0F); postRotate(90F) }, true)
        else
            Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height,
                Matrix().apply { postScale(-1.0F, 1.0F); postRotate(90F) }, false)

        // Transform bitmap orientation using provided metadata
        return bitmap
    }

    companion object {
        /** Maximum size of [Bitmap] decoded */
        private const val DOWNSAMPLE_SIZE: Int = 1024  // 1MP
    }
}
