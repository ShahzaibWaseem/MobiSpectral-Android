package com.shahzaib.mobispectral.fragments

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.*
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
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
import com.shahzaib.mobispectral.Utils
import com.shahzaib.mobispectral.databinding.FragmentReconstructionBinding
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.roundToInt

class ReconstructionFragment: Fragment() {
    private lateinit var predictedHS: FloatArray
    private val bandsHS: MutableList<Bitmap> = mutableListOf()
    private val args: ReconstructionFragmentArgs by navArgs()
    private var reconstructionDuration = 0L
    private var classificationDuration = 0L
    private val numberOfBands = 60
    private var outputLabelString: String = ""
    private var clickedX = 0.0F
    private var clickedY = 0.0F
    private val bandsChosen = mutableListOf<Int>()
    private val reconstructionDialogFragment = ReconstructionDialogFragment()
    private val randomColor = Random()
    private var color = Color.argb(255, randomColor.nextInt(256), randomColor.nextInt(256), randomColor.nextInt(256))

    private var _fragmentReconstructionBinding: FragmentReconstructionBinding? = null
    private val fragmentReconstructionBinding get() = _fragmentReconstructionBinding!!

    private fun imageViewFactory() = ImageView(requireContext()).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun createORTSession(ortEnvironment: OrtEnvironment) : OrtSession {
        val modelBytes = resources.openRawResource(R.raw.classifier).readBytes()
        return ortEnvironment.createSession(modelBytes)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        super.onCreateView(inflater, container, savedInstanceState)
        _fragmentReconstructionBinding = FragmentReconstructionBinding.inflate(
            inflater, container, false)
        reconstructionDialogFragment.isCancelable = false
        return fragmentReconstructionBinding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentReconstructionBinding.information.setOnClickListener {
            val builder: AlertDialog.Builder = AlertDialog.Builder(context)
            builder.setMessage(R.string.reconstruction_analysis_information_string)
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
        fragmentReconstructionBinding.viewpager.apply {
            offscreenPageLimit=2
            adapter = GenericListAdapter(bandsHS, itemViewFactory = { imageViewFactory() })
            { view, item, _ ->
                view as ImageView
                view.scaleType = ImageView.ScaleType.FIT_XY
                var bitmapOverlay = Bitmap.createBitmap(item.width, item.height, item.config)
                var canvas = Canvas(bitmapOverlay)
                canvas.drawBitmap(item, Matrix(), null)

                view.setOnTouchListener { v, event ->
                    clickedX = (event!!.x / v!!.width) * Utils.torchWidth
                    clickedY = (event.y / v.height) * Utils.torchHeight
                    Log.i("View Dimensions", "$clickedX, $clickedY, ${v.width}, ${v.height}")
                    color = Color.argb(255, randomColor.nextInt(256), randomColor.nextInt(256), randomColor.nextInt(256))
                    val paint = Paint()
                    paint.color = color
                    paint.style = Paint.Style.STROKE
                    canvas.drawCircle(clickedX, clickedY, 10F, paint)
                    view.setImageBitmap(bitmapOverlay)
                    inference()
                    false
                }
                view.setOnLongClickListener {
                    bitmapOverlay = Bitmap.createBitmap(item.width, item.height, item.config)
                    canvas = Canvas(bitmapOverlay)
                    canvas.drawBitmap(item, Matrix(), null)
                    view.setImageBitmap(bitmapOverlay)
                    fragmentReconstructionBinding.graphView.removeAllSeries()         // remove all previous series

                    false
                }
                Glide.with(view).load(item).into(view)
            }
        }

        fragmentReconstructionBinding.analysisButton.setOnClickListener {
            fragmentReconstructionBinding.textConstraintView.visibility = View.INVISIBLE
            fragmentReconstructionBinding.graphView.visibility = View.VISIBLE
        }
        reconstructionDialogFragment.show(childFragmentManager, ReconstructionDialogFragment.TAG)
    }

    override fun onStart() {
        super.onStart()

        Timer().schedule(1000) {
            val reconstructionThread = Thread {
                if (!::predictedHS.isInitialized)
                    generateHypercube()
            }
            reconstructionThread.start()
            try { reconstructionThread.join() }
            catch (exception: InterruptedException) { exception.printStackTrace() }

            val graphView = _fragmentReconstructionBinding!!.graphView
            graphView.gridLabelRenderer.horizontalAxisTitle = "Wavelength λ (nm)"
            graphView.gridLabelRenderer.verticalAxisTitle = "Reflectance"
            graphView.title = "Click on the image to show the signature"
            graphView.viewport.isXAxisBoundsManual = true
            graphView.viewport.setMaxX(1000.0)
            graphView.viewport.setMinX(400.0)
            graphView.viewport.isYAxisBoundsManual = true
            graphView.viewport.setMaxY(0.4)
            graphView.gridLabelRenderer.setHumanRounding(true)

            graphView.setOnLongClickListener{
                fragmentReconstructionBinding.textConstraintView.visibility = View.VISIBLE
                fragmentReconstructionBinding.graphView.visibility = View.INVISIBLE
                false
            }

            val viewpagerThread = Thread {
                for (i in 0 until numberOfBands) {
                    if (i % 8 > 0) continue
                    Log.i("Bands Chosen", "$i")
                    bandsChosen.add(i)
                    addItemToViewPager(fragmentReconstructionBinding.viewpager, getBand(predictedHS, i), i)
                }
            }

            TabLayoutMediator(fragmentReconstructionBinding.tabLayout,
                fragmentReconstructionBinding.viewpager) { tab, position ->
                tab.text = ACTUAL_BAND_WAVELENGTHS[bandsChosen[position]].roundToInt().toString() + " nm"
            }.attach()

            viewpagerThread.start()
            try { viewpagerThread.join() }
            catch (exception: InterruptedException) { exception.printStackTrace() }

            reconstructionDialogFragment.dismissDialog()
        }
    }

    private fun inference() {
        val ortEnvironment = OrtEnvironment.getEnvironment()
        val ortSession = context?.let { createORTSession(ortEnvironment) }

        val inputSignature = getSignature(predictedHS, clickedX.toInt(), clickedY.toInt())
        val outputLabel = ortSession?.let { classifyHypercube(inputSignature, it, ortEnvironment) }
        outputLabelString = if (outputLabel == 1L) "Organic Apple" else "Non-Organic Apple"
        fragmentReconstructionBinding.textViewClass.text = outputLabelString
        fragmentReconstructionBinding.graphView.title = "$outputLabelString Signature at (x: ${clickedX.toInt()}, y: ${clickedY.toInt()})"
    }

    private fun getSignature(predictedHS: FloatArray, SignatureX: Int, SignatureY: Int): FloatArray {
        val signature = FloatArray(numberOfBands)
        Log.i("Touch Coordinates", "$SignatureX, $SignatureY")
        val leftX = Utils.torchWidth - SignatureX
        val leftY = Utils.torchHeight - SignatureY

        var idx = if (Utils.torchWidth*SignatureY <= Utils.torchWidth) Utils.torchWidth*SignatureY else Utils.torchWidth
        idx += SignatureX

        print("Signature is:")
        val series = LineGraphSeries<DataPoint>()

        for (i in 0 until numberOfBands) {
            signature[i] = predictedHS[idx]
            print("${predictedHS[idx]}, ")
            series.appendData(DataPoint(ACTUAL_BAND_WAVELENGTHS[i], predictedHS[idx].toDouble()), true, 60)
            idx += leftX + Utils.torchWidth*leftY + (Utils.torchWidth*SignatureY + SignatureX)
        }
        val graphView = fragmentReconstructionBinding.graphView
//        graphView.removeAllSeries()         // remove all previous series
        graphView.title = "$outputLabelString Signature at (x: $SignatureX, y: $SignatureY)"
        series.dataPointsRadius = 10F
        series.thickness = 10
        series.color = color
        graphView.addSeries(series)

        return signature
    }

    private fun generateHypercube() {
        val reconstructionModel = context?.let { Reconstruction(it, "mobile_mst_apple_wb.pt") }!!
        val decodedRGB = Base64.decode(args.rgbImage, Base64.DEFAULT)
        var rgbBitmap = BitmapFactory.decodeByteArray(decodedRGB, 0, decodedRGB.size)
        rgbBitmap = Bitmap.createBitmap(rgbBitmap, 0, 0, rgbBitmap.width, rgbBitmap.height,
            null, true)

        val decodedNIR = Base64.decode(args.nirImage, Base64.DEFAULT)
        var nirBitmap = BitmapFactory.decodeByteArray(decodedNIR, 0, decodedNIR.size)
        nirBitmap = Bitmap.createBitmap(nirBitmap, 0, 0, nirBitmap.width, nirBitmap.height,
            null, true)

        val startTime = System.currentTimeMillis()
        predictedHS = reconstructionModel.predict(rgbBitmap, nirBitmap)

        Log.i("predictedHS Size", predictedHS.size.toString())

        val endTime = System.currentTimeMillis()
        reconstructionDuration = (endTime - startTime)/1000
        println(getString(R.string.reconstruction_time_string, reconstructionDuration))
        fragmentReconstructionBinding.textViewReconTime.text = getString(R.string.reconstruction_time_string, reconstructionDuration)
    }

    private fun classifyHypercube(input: FloatArray, ortSession: OrtSession,
                                  ortEnvironment: OrtEnvironment): Long {
        val inputName = ortSession.inputNames?.iterator()?.next()
        val floatBufferInputs = FloatBuffer.wrap(input)
        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment, floatBufferInputs, longArrayOf(1, 60))

        val startTime = System.currentTimeMillis()
        val results = ortSession.run(mapOf(inputName to inputTensor))
        val endTime = System.currentTimeMillis()

        classificationDuration = (endTime - startTime)
        println(getString(R.string.classification_time_string, classificationDuration))
        fragmentReconstructionBinding.textViewClassTime.text = getString(R.string.classification_time_string, classificationDuration)

        var output = results[0].value
        output = output as LongArray
        println("Classifier OUTPUT Label" + output[0].toString())

        return output[0]
    }

    private fun getBand(predictedHS: FloatArray, bandNumber: Int, reverseScale: Boolean = false): Bitmap {
        val alpha :Byte = (255).toByte()

        val byteBuffer = ByteBuffer.allocate((Utils.torchWidth + 1) * (Utils.torchHeight + 1) * 4)
        var bmp = Bitmap.createBitmap(Utils.torchWidth, Utils.torchHeight, Bitmap.Config.ARGB_8888)

        val startOffset = bandNumber * Utils.torchWidth * Utils.torchHeight
        val endOffset = (bandNumber+1) * Utils.torchWidth * Utils.torchHeight - 1

        // mapping smallest value to 0 and largest value to 255
        val maxValue = predictedHS.maxOrNull() ?: 1.0f
        val minValue = predictedHS.minOrNull() ?: 0.0f
        val delta = maxValue-minValue
        var tempValue :Byte

        // Define if float min..max will be mapped to 0..255 or 255..0
        val conversion = when(reverseScale) {
            false -> { v: Float -> (((v - minValue) / delta * 255)).toInt().toByte() }
            true -> { v: Float -> (255 - (v - minValue) / delta * 255).toInt().toByte() }
        }
        var buffIdx = 0
        for (i in startOffset .. endOffset) {
            tempValue = conversion(predictedHS[i])
            byteBuffer.put(4 * buffIdx, tempValue)
            byteBuffer.put(4 * buffIdx + 1, tempValue)
            byteBuffer.put(4 * buffIdx + 2, tempValue)
            byteBuffer.put(4 * buffIdx + 3, alpha)
            buffIdx += 1
        }

        bmp.copyPixelsFromBuffer(byteBuffer)
        bmp = Bitmap.createBitmap(bmp, 0, 0, Utils.torchWidth, Utils.torchHeight, null, true)
        return bmp
    }

    /** Utility function used to add an item to the viewpager and notify it, in the main thread */
    private fun addItemToViewPager(view: ViewPager2, item: Bitmap, position: Int) = view.post {
        bandsHS.add(item)
        view.adapter!!.notifyItemChanged(position)
    }

    companion object {
        private val ACTUAL_BAND_WAVELENGTHS = listOf(426.19, 434.87, 443.56, 452.25, 460.96, 469.68, 478.41, 487.14, 495.89, 504.64, 513.4, 522.18, 530.96, 539.75, 548.55, 557.36, 566.18, 575.01, 583.85, 592.7, 601.55, 610.42, 619.3, 628.18, 637.08, 645.98, 654.89, 663.81, 672.75, 681.69, 690.64, 699.6, 708.57, 717.54, 726.53, 735.53, 744.53, 753.55, 762.57, 771.61, 780.65, 789.7, 798.77, 807.84, 816.92, 826.01, 835.11, 844.22, 853.33, 862.46, 871.6, 880.74, 889.9, 899.06, 908.24, 917.42, 926.61, 935.81, 945.02, 954.24)
    }
}