package com.shahzaib.mobispectral.fragments

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.*
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.android.camera.utils.GenericListAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.shahzaib.mobispectral.R
import com.shahzaib.mobispectral.Reconstruction
import com.shahzaib.mobispectral.databinding.FragmentReconstructionBinding
import org.opencv.android.OpenCVLoader
import java.nio.ByteBuffer
import java.nio.FloatBuffer

class ReconstructionFragment : Fragment() {
    private lateinit var pred_HS: FloatArray
    private val HS_bands: MutableList<Bitmap> = mutableListOf()
    private val args: ReconstructionFragmentArgs by navArgs()
    var reconstructionDuration = 0L
    var classificationDuration = 0L
    val height = 640
    val width = 480
    val numberOfBands = 60
    var phoneHeight = 0
    var phoneWidth = 0

    private var _fragmentReconstructionBinding: FragmentReconstructionBinding? = null
    private val fragmentReconstructionBinding get() = _fragmentReconstructionBinding!!

    private fun imageViewFactory() = ImageView(requireContext()).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    private fun createORTSession(
        context: Context,
        assetName: String,
        ortEnvironment: OrtEnvironment
    ) : OrtSession {
        val modelBytes = resources.openRawResource(R.raw.classifier).readBytes()
        return ortEnvironment.createSession(modelBytes)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        OpenCVLoader.initDebug()
        super.onCreateView(inflater, container, savedInstanceState)
        _fragmentReconstructionBinding = FragmentReconstructionBinding.inflate(
            inflater,
            container,
            false
        )
        val wm: WindowManager= context?.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        Log.i("INIT Phone Size", "height: ${display.height}, width: ${display.width}")
        return fragmentReconstructionBinding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        generateHypercube()
        fragmentReconstructionBinding.viewpager.apply {
            offscreenPageLimit=2
            adapter = GenericListAdapter(HS_bands,
                itemViewFactory = { imageViewFactory() }) { view, item, idx ->
                view as ImageView
                Log.i("Image View Factory", "ImageinViewPager$idx")
                view.setTag("ImageinViewPager$idx")
                Glide.with(view).load(item).into(view)
            }
        }
        TabLayoutMediator(
            fragmentReconstructionBinding.tabLayout,
            fragmentReconstructionBinding.viewpager
        ) { tab, position ->

        }.attach()

        val viewpagerThread = Thread {
            for (i in 0 until numberOfBands) {
                addItemToViewPager(fragmentReconstructionBinding.viewpager, getBand(pred_HS, i))
            }
        }

        viewpagerThread.start()

        try {
            viewpagerThread.join()
        }
        catch (exception: InterruptedException) {
            exception.printStackTrace()
        }

        fragmentReconstructionBinding.textViewReconTime.text = "Reconstruction Time: $reconstructionDuration s\n"

        fragmentReconstructionBinding.analysisButton.setOnClickListener {
            fragmentReconstructionBinding.textConstraintView.visibility = View.INVISIBLE
            fragmentReconstructionBinding.graphView.visibility = View.VISIBLE
        }

        val overlay = fragmentReconstructionBinding.overlay
        phoneHeight = overlay.measuredHeight
        phoneWidth = overlay.measuredWidth
        Log.i("Phones Size", "width: $phoneWidth, height: $phoneHeight")

        val ortEnvironment = OrtEnvironment.getEnvironment()
        val ortSession = context?.let { createORTSession(it, "classifier.onnx", ortEnvironment) }

        overlay.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                val coordinates = IntArray(2)
                overlay.getLocationOnScreen(coordinates)
                val overlayWidth = (overlay.right - overlay.left).toFloat()
                val overlayHeight = (overlay.bottom - overlay.top).toFloat()
                Log.i("Phones Size", "width: $phoneWidth, height: $phoneHeight")
                Log.i(
                    "Overlay sizes",
                    "left ${overlay.left} event x: ${event?.x!!} = ${overlay.left + event.x}"
                )
                Log.i(
                    "Overlay sizes",
                    "top ${overlay.top} Bottom ${overlay.bottom} event y: ${event.y} = ${overlay.top + event.y}"
                )
                Log.i("Overlay sizes", "width: $overlayWidth, height: $overlayHeight")

                val xCoord = (event.x / overlayWidth) * width.toFloat()
                val yCoord = (event.y / overlayHeight) * height.toFloat()

                Log.i(
                    "Overlay size",
                    "percentage x: ${event.x / overlayWidth}, percentage y : ${event.y / overlayHeight}"
                )

                val inputSignature = getSignature(pred_HS, xCoord.toInt(), yCoord.toInt())
                val output = ortSession?.let { runPrediction(inputSignature, it, ortEnvironment) }
                val outputString: String =
                    if (output == 1L) "Organic Apple" else "Non-Organic Apple"
                fragmentReconstructionBinding.textViewClass.text = outputString
                return false
            }
        })
    }

    fun getSignature(pred_HS: FloatArray, x: Int, y: Int): FloatArray {
        val signature = FloatArray(numberOfBands)
        Log.i("Touch Coords", "$x, $y")
        val leftX = width - x
        val leftY = height - y

        var idx = width*y + x

        print("Signature is:")
        val series = LineGraphSeries<DataPoint>()

        for (i in 0 until numberOfBands) {
            signature[i] = pred_HS[idx]
            print("${pred_HS[idx]}, ")
            series.appendData(DataPoint(i.toDouble(), pred_HS[idx].toDouble()), true, 60)
            idx += leftX + width*leftY + (width*y + x)
        }
        val graphView = fragmentReconstructionBinding.graphView
        graphView.removeAllSeries()         // remove all previous series
        graphView.title = "Signature at (x: $x, y: $y)"
        graphView.gridLabelRenderer.textSize = 60f
        graphView.gridLabelRenderer.setHumanRounding(true)
        series.dataPointsRadius = 10F
        series.thickness = 10
        graphView.addSeries(series)
        graphView.viewport.setMaxX(60.0)
        graphView.gridLabelRenderer.reloadStyles()

        return signature
    }

    fun generateHypercube() {
        val reconstructorModel = context?.let { Reconstruction(it, "mobile_mst_apple_wb.pt") }!!

        val decoded_rgb = Base64.decode(args.rgbImage, Base64.DEFAULT)
        var rgbBitmap = BitmapFactory.decodeByteArray(decoded_rgb, 0, decoded_rgb.size)
        rgbBitmap = Bitmap.createBitmap(
            rgbBitmap, 0, 0, rgbBitmap.width, rgbBitmap.height,
            null, true
        )

        val decoded_nir = Base64.decode(args.nirImage, Base64.DEFAULT)
        var nirBitmap = BitmapFactory.decodeByteArray(decoded_nir, 0, decoded_nir.size)
        nirBitmap = Bitmap.createBitmap(
            nirBitmap, 0, 0, nirBitmap.width, nirBitmap.height,
            null, true
        )
        val startTime = System.currentTimeMillis()

        val modelRunningThread = Thread { pred_HS = reconstructorModel.predict(rgbBitmap, nirBitmap) }

        modelRunningThread.start()

//        pred_HS = reconstructorModel.predict(rgbBitmap, nirBitmap)

        try {
            modelRunningThread.join()
        }
        catch (exception: InterruptedException) {
            exception.printStackTrace()
        }


        Log.i("Pred_HS Size", pred_HS.size.toString())

        val endTime = System.currentTimeMillis()
        reconstructionDuration = (endTime - startTime)/1000
        println("Reconstruction Time: $reconstructionDuration s")
    }

    private fun runPrediction(
        input: FloatArray,
        ortSession: OrtSession,
        ortEnvironment: OrtEnvironment
    ): Long {
        val inputName = ortSession.inputNames?.iterator()?.next()
        val floatBufferInputs = FloatBuffer.wrap(input)
        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment, floatBufferInputs, longArrayOf(
                1,
                60
            )
        )

        val startTime = System.currentTimeMillis()
        val results = ortSession.run(mapOf(inputName to inputTensor))
        val endTime = System.currentTimeMillis()

        classificationDuration = (endTime - startTime)
        println("Classification Time: $classificationDuration ms")
        fragmentReconstructionBinding.textViewClassTime.text = "Classification Duration: $classificationDuration ms\n"

        var output = results[0].value
        output = output as LongArray
        println("Classifier OUTPUT Label" + output[0].toString())

        return output[0]
    }

    fun getBand(pred_HS: FloatArray, bandNumber: Int, reverseScale: Boolean = false): Bitmap {
        val alpha :Byte = (255).toByte()

        val byteBuffer = ByteBuffer.allocate((width + 1) * (height + 1) * 4)
        var bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val startOffset = bandNumber * width * height
        val endOffset = (bandNumber+1) * width * height - 1

        // mapping smallest value to 0 and largest value to 255
        val maxValue = pred_HS.maxOrNull() ?: 1.0f
        val minValue = pred_HS.minOrNull() ?: 0.0f
        val delta = maxValue-minValue
        var tempValue :Byte

        // Define if float min..max will be mapped to 0..255 or 255..0
        val conversion = when(reverseScale) {
            false -> { v: Float -> (((v - minValue) / delta * 255)).toInt().toByte() }
            true -> { v: Float -> (255 - (v - minValue) / delta * 255).toInt().toByte() }
        }
        var buff_idx = 0
        for (i in startOffset .. endOffset) {
            tempValue = conversion(pred_HS[i])
            byteBuffer.put(4 * buff_idx, tempValue)
            byteBuffer.put(4 * buff_idx + 1, tempValue)
            byteBuffer.put(4 * buff_idx + 2, tempValue)
            byteBuffer.put(4 * buff_idx + 3, alpha)
            buff_idx += 1
        }

        bmp.copyPixelsFromBuffer(byteBuffer)
        bmp = Bitmap.createBitmap(
            bmp, 0, 0, width, height,
            null, true
        )
        return bmp
    }

    private fun addItemToViewPager(view: ViewPager2, item: Bitmap) = view.post {
        HS_bands.add(item)
        view.adapter!!.notifyDataSetChanged()
    }
}