package com.shahzaib.mobispectral.fragments

import android.content.Context
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
import com.shahzaib.mobispectral.MainActivity
import com.shahzaib.mobispectral.R
import com.shahzaib.mobispectral.Utils
import com.shahzaib.mobispectral.databinding.FragmentImageviewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

class ImageViewerFragment: Fragment() {
    private val correctionMatrix = Matrix().apply {  }

    /** AndroidX navigation arguments */
    private val args: ImageViewerFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }
    private var _fragmentImageViewerBinding: FragmentImageviewerBinding? = null
    private val fragmentImageViewerBinding get() = _fragmentImageViewerBinding!!
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
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _fragmentImageViewerBinding = FragmentImageviewerBinding.inflate(inflater, container, false)
        fragmentImageViewerBinding.viewpager.apply {
            offscreenPageLimit=2
            adapter = GenericListAdapter(bitmapList,
                itemViewFactory = { imageViewFactory() }) { view, item, _ ->
                view as ImageView
//                view.scaleType = ImageView.ScaleType.FIT_XY
                Glide.with(view).load(item).into(view)
            }
        }
        TabLayoutMediator(fragmentImageViewerBinding.tabLayout,
            fragmentImageViewerBinding.viewpager) { tab, position ->
            tab.text = if (position==0) "RGB" else "NIR"
        }.attach()
        return fragmentImageViewerBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fragmentImageViewerBinding.Title.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                navController.navigate(
                    ImageViewerFragmentDirections
                        .actionImageViewerFragmentToApplicationTitle()
                )
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // Load input image file
            val (bufferRGB, bufferNIR) = loadInputBuffer()

            // Load the main JPEG image
            var rgbImageBitmap = decodeBitmap(bufferRGB, bufferRGB.size, "RGB")
            var nirImageBitmap = decodeBitmap(bufferNIR, bufferNIR.size, "NIR")
            Log.i("Bitmap Size", "Decoded RGB: ${rgbImageBitmap.width} x ${rgbImageBitmap.height}")
            Log.i("Bitmap Size", "Decoded NIR: ${nirImageBitmap.width} x ${nirImageBitmap.height}")



            val sharedPreferences = requireActivity().getSharedPreferences("mobispectral_preferences", Context.MODE_PRIVATE)
            val advancedControlOption = when (sharedPreferences.getString("option", "Advanced Option (with Signature Analysis)")!!) {
                "Advanced Option (with Signature Analysis)" -> true
                "Simple Option (no Signature Analysis)" -> false
                else -> true
            }
            // Crop images to 300x150 if its the simple mode, to speed up the process.
            if (!advancedControlOption) {
                val newLeft = (rgbImageBitmap.width - Utils.croppedWidth)/2
                val newTop = (rgbImageBitmap.height - Utils.croppedHeight)/2
                Log.i("Crop Dimension", "Width: ${rgbImageBitmap.width/2} - ${Utils.croppedWidth/2} = $newLeft")
                Log.i("Crop Dimension", "Height: ${rgbImageBitmap.height/2} - ${Utils.croppedHeight/2} = $newTop")
                Log.i("Crop Dimension", "X + width: $newLeft + ${Utils.croppedWidth} = ${newLeft + Utils.croppedWidth}, rgbBitmap Width: ${rgbImageBitmap.width}")
                Log.i("Crop Dimension", "Y + height: $newTop + ${Utils.croppedHeight} = ${newTop + Utils.croppedHeight}, rgbBitmap Height: ${rgbImageBitmap.height}")

                rgbImageBitmap = Bitmap.createBitmap(rgbImageBitmap, newLeft, newTop, Utils.croppedWidth, Utils.croppedHeight)
                nirImageBitmap = Bitmap.createBitmap(nirImageBitmap, newLeft, newTop, Utils.croppedWidth, Utils.croppedHeight)
            }

            val rgbByteArray = bitmapToByteArray(rgbImageBitmap)
            var nirByteArray = bitmapToByteArray(nirImageBitmap)
            Log.i("RGB NIR ByteArray Sizes", "${rgbByteArray.size}, ${nirByteArray.size}")
            nirByteArray = getNIRBand(nirByteArray)
            Log.i("RGB NIR ByteArray Sizes", "${rgbByteArray.size}, ${nirByteArray.size}")
            addItemToViewPager(fragmentImageViewerBinding.viewpager, rgbImageBitmap, 0)
            addItemToViewPager(fragmentImageViewerBinding.viewpager, nirImageBitmap, 1)

            fragmentImageViewerBinding.button.setOnClickListener {
                lifecycleScope.launch(Dispatchers.Main) {
                    navController.navigate(
                        ImageViewerFragmentDirections
                            .actionImageViewerFragmentToReconstructionFragment2(
                                serializeByteArrayToString(rgbImageBitmap),
                                serializeByteArrayToString(nirImageBitmap))
                    )
                }
            }

            fragmentImageViewerBinding.reloadButton.setOnClickListener {
                lifecycleScope.launch(Dispatchers.Main) {
                    navController.navigate(
                        ImageViewerFragmentDirections
                            .actionImageViewerFragmentToCameraFragment(
                                Utils.getCameraIDs(requireContext(), MainActivity.MOBISPECTRAL_APPLICATION).first, ImageFormat.JPEG)
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

    private fun getNIRBand(imageBuff: ByteArray): ByteArray {
        val byteBuffer = ByteBuffer.allocate(imageBuff.size)
        var buffIdx = 0
        var pixelValueBuff: Byte

        val startOffset = 0
        val endOffset = imageBuff.size - 1

        Log.i("Image Buff Size", "${imageBuff.size}, EndOffset $endOffset, byteBuffer ${byteBuffer.capacity()}")
        for (i in startOffset .. endOffset) {
            pixelValueBuff = imageBuff[i]
            byteBuffer.put(1 * buffIdx, pixelValueBuff)
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
    private fun addItemToViewPager(view: ViewPager2, item: Bitmap, position: Int) = view.post {
        bitmapList.add(item)
        view.adapter!!.notifyItemChanged(position)
    }

    /** Utility function to convert images into String to be decoded in ReconstructionFragment **/
    private fun serializeByteArrayToString(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    /** Utility function used to decode a [Bitmap] from a byte array */
    private fun decodeBitmap(buffer: ByteArray, length: Int, RGB: String): Bitmap {
        val bitmap: Bitmap

        // Load bitmap from given buffer
        val decodedBitmap = BitmapFactory.decodeByteArray(buffer, 0, length, bitmapOptions)
        Log.i("Bitmap Size", "${decodedBitmap.width} x ${decodedBitmap.height}")
        Log.i("Decode Bitmap", "${Utils.aligningFactorX} + ${Utils.torchWidth} = ${Utils.torchWidth + Utils.aligningFactorX} (${decodedBitmap.width})")
        Log.i("Decode Bitmap", "${Utils.aligningFactorY} + ${Utils.torchHeight} = ${Utils.torchHeight + Utils.aligningFactorY} (${decodedBitmap.height})")
        bitmap = if (RGB == "RGB")
            Bitmap.createBitmap(decodedBitmap, Utils.aligningFactorX, Utils.aligningFactorY, Utils.torchWidth, Utils.torchHeight, correctionMatrix, true)
        else
            Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, correctionMatrix, false)

        // Transform bitmap orientation using provided metadata
        return bitmap
    }

    companion object {
        /** Maximum size of [Bitmap] decoded */
        private const val DOWNSAMPLE_SIZE: Int = 1024  // 1MP
    }
}