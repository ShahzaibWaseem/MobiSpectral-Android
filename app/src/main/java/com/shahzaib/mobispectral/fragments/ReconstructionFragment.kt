package com.shahzaib.mobispectral.fragments

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.roundToInt
import kotlin.properties.Delegates

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

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    private var _fragmentReconstructionBinding: FragmentReconstructionBinding? = null
    private val fragmentReconstructionBinding get() = _fragmentReconstructionBinding!!

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var mobiSpectralApplication: String
    private lateinit var reconstructionFile: String
    private var classificationFile by Delegates.notNull<Int>()
    private lateinit var mobiSpectralControlOption: String
    private var advancedControlOption by Delegates.notNull<Boolean>()

    private val classificationLabels = mapOf(
        Pair("Organic Non-Organic Apple Classification", 0L) to "Non-Organic Apple",
        Pair("Organic Non-Organic Apple Classification", 1L) to "Organic Apple",
        Pair("Organic Non-Organic Kiwi Classification", 0L) to "Non-Organic Kiwi",
        Pair("Organic Non-Organic Kiwi Classification", 1L) to "Organic Kiwi",
        Pair("Olive Oil Adulteration", 0L) to "50% EVOO, 50% SFO",
        Pair("Olive Oil Adulteration", 1L) to "75% EVOO, 25% SFO",
        Pair("Olive Oil Adulteration", 2L) to "100% EVOO, 0% SFO",
    )

    private var bitmapsWidth = Utils.torchWidth
    private var bitmapsHeight = Utils.torchHeight

    private fun imageViewFactory() = ImageView(requireContext()).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun createORTSession(ortEnvironment: OrtEnvironment) : OrtSession {
        val modelBytes = resources.openRawResource(classificationFile).readBytes()
        return ortEnvironment.createSession(modelBytes)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        super.onCreateView(inflater, container, savedInstanceState)
        _fragmentReconstructionBinding = FragmentReconstructionBinding.inflate(
            inflater, container, false)
        reconstructionDialogFragment.isCancelable = false

        sharedPreferences = requireActivity().getSharedPreferences("mobispectral_preferences", Context.MODE_PRIVATE)
        mobiSpectralApplication = sharedPreferences.getString("application", "Organic Non-Organic Apple Classification")!!
        reconstructionFile = when (mobiSpectralApplication) {
            "Organic Non-Organic Apple Classification" -> "mobile_mst_apple_wb.pt"
            "Olive Oil Adulteration" -> "mobile_mst_oil.pt"
            "Organic Non-Organic Kiwi Classification" -> "mobile_mst_kiwi_dayl.pt"
            else -> "mobile_mst_apple_wb.pt"
        }

        classificationFile = when (mobiSpectralApplication) {
            "Organic Non-Organic Apple Classification" -> R.raw.mobile_classifier_apple
            "Olive Oil Adulteration" -> R.raw.mobile_classifier_oil
            "Organic Non-Organic Kiwi Classification" -> R.raw.mobile_classifier_kiwi
            else -> R.raw.mobile_classifier_apple
        }
        mobiSpectralControlOption = sharedPreferences.getString("option", "Advanced Option (with Signature Analysis)")!!

        advancedControlOption = when (mobiSpectralControlOption) {
            "Advanced Option (with Signature Analysis)" -> true
            "Simple Option (no Signature Analysis)" -> false
            else -> true
        }

        fragmentReconstructionBinding.textViewClass.text = mobiSpectralApplication

        if (!advancedControlOption) {
            fragmentReconstructionBinding.analysisButton.visibility = View.INVISIBLE
            fragmentReconstructionBinding.simpleModeSignaturePositionTextView.visibility = View.VISIBLE
            fragmentReconstructionBinding.graphView.visibility = View.INVISIBLE
            fragmentReconstructionBinding.textViewClassTime.text = ""
            fragmentReconstructionBinding.simpleModeSignaturePositionTextView.text = getString(R.string.simple_mode_signature_string, (Utils.croppedWidth/2F).toInt(), (Utils.croppedHeight/2F).toInt())
            fragmentReconstructionBinding.textViewReconTime.visibility = View.INVISIBLE
            fragmentReconstructionBinding.textViewClassTime.visibility = View.INVISIBLE
        }
        return fragmentReconstructionBinding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentReconstructionBinding.information.setOnClickListener {
            val builder: AlertDialog.Builder = AlertDialog.Builder(context)
            if (!advancedControlOption)
                builder.setMessage(R.string.reconstruction_analysis_information_simple_string)
            else
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

                if (advancedControlOption) {
                    view.setOnTouchListener { v, event ->
                        clickedX = (event!!.x / v!!.width) * bitmapsWidth
                        clickedY = (event.y / v.height) * bitmapsHeight
                        Log.i("View Dimensions", "$clickedX, $clickedY, ${v.width}, ${v.height}")
                        color = Color.argb(
                            255,
                            randomColor.nextInt(256),
                            randomColor.nextInt(256),
                            randomColor.nextInt(256)
                        )
                        val paint = Paint()
                        paint.color = color
                        paint.style = Paint.Style.STROKE
                        canvas.drawCircle(clickedX, clickedY, 10F, paint)
                        view.setImageBitmap(bitmapOverlay)
                        try {
                            inference()
                        }
                        catch (e: NullPointerException) {
                            e.printStackTrace()
                        }
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
                }
                else {
                    Log.i("Simple Mode", "${bitmapsWidth/2F}, ${bitmapsHeight/2F}")
                    color = Color.argb(
                        255,
                        randomColor.nextInt(256),
                        randomColor.nextInt(256),
                        randomColor.nextInt(256)
                    )
                    val paint = Paint()
                    paint.color = color
                    paint.style = Paint.Style.STROKE
                    canvas.drawCircle(Utils.torchWidth/2F, Utils.torchHeight/2F, 10F, paint)
                    view.setImageBitmap(bitmapOverlay)
                    inference()
                }
                Glide.with(view).load(item).into(view)
            }
        }

        fragmentReconstructionBinding.Title.setOnClickListener {
            lifecycleScope.launch(Dispatchers.Main) {
                navController.navigate(
                    ReconstructionFragmentDirections
                        .actionReconstructionToApplicationTitle()
                )
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

        if (!advancedControlOption) {
            clickedX = bitmapsWidth/2F
            clickedY = bitmapsHeight/2F
        }

        val inputSignature = getSignature(predictedHS, clickedX.toInt(), clickedY.toInt())
        val outputLabel = ortSession?.let { classifyHypercube(inputSignature, it, ortEnvironment) }
        println("Output Label: $outputLabel")
        outputLabelString = if (Pair(mobiSpectralApplication, outputLabel) !in classificationLabels)
            "Something went wrong"
        else
            classificationLabels[Pair(mobiSpectralApplication, outputLabel)]!!
        fragmentReconstructionBinding.textViewClass.text = outputLabelString
        fragmentReconstructionBinding.graphView.title = "$outputLabelString Signature at (x: ${clickedX.toInt()}, y: ${clickedY.toInt()})"
    }

    private fun getSignature(predictedHS: FloatArray, SignatureX: Int, SignatureY: Int): FloatArray {
        val signature = FloatArray(numberOfBands)
        Log.i("Touch Coordinates", "$SignatureX, $SignatureY")
        val leftX = bitmapsWidth - SignatureX
        val leftY = bitmapsHeight - SignatureY

        var idx = if (bitmapsWidth*SignatureY <= bitmapsWidth) bitmapsWidth*SignatureY else bitmapsWidth
        idx += SignatureX

        print("Signature is:")
        val series = LineGraphSeries<DataPoint>()

        for (i in 0 until numberOfBands) {
            signature[i] = predictedHS[idx]
            print("${predictedHS[idx]}, ")
            series.appendData(DataPoint(ACTUAL_BAND_WAVELENGTHS[i], predictedHS[idx].toDouble()), true, 60)
            idx += leftX + bitmapsWidth*leftY + (bitmapsWidth*SignatureY + SignatureX)
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
        val reconstructionModel = context?.let { Reconstruction(it, reconstructionFile) }!!
        val decodedRGB = Base64.decode(args.rgbImage, Base64.DEFAULT)
        var rgbBitmap = BitmapFactory.decodeByteArray(decodedRGB, 0, decodedRGB.size)
        rgbBitmap = Bitmap.createBitmap(rgbBitmap, 0, 0, rgbBitmap.width, rgbBitmap.height,
            null, true)

        val decodedNIR = Base64.decode(args.nirImage, Base64.DEFAULT)
        var nirBitmap = BitmapFactory.decodeByteArray(decodedNIR, 0, decodedNIR.size)
        nirBitmap = Bitmap.createBitmap(nirBitmap, 0, 0, nirBitmap.width, nirBitmap.height,
            null, true)

        bitmapsWidth = rgbBitmap.width
        bitmapsHeight = rgbBitmap.height

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

        val byteBuffer = ByteBuffer.allocate((bitmapsWidth + 1) * (bitmapsHeight + 1) * 4)
        var bmp = Bitmap.createBitmap(bitmapsWidth, bitmapsHeight, Bitmap.Config.ARGB_8888)

        val startOffset = bandNumber * bitmapsWidth * bitmapsHeight
        val endOffset = (bandNumber+1) * bitmapsWidth * bitmapsHeight - 1

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
        bmp = Bitmap.createBitmap(bmp, 0, 0, bitmapsWidth, bitmapsHeight, null, true)
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