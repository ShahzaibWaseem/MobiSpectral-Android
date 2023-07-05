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
import com.shahzaib.mobispectral.*
import com.shahzaib.mobispectral.databinding.FragmentReconstructionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.*
import kotlin.collections.ArrayList
import kotlin.concurrent.schedule
import kotlin.math.roundToInt
import kotlin.properties.Delegates

class ReconstructionFragment: Fragment() {
    private lateinit var predictedHS: FloatArray
    private val bandsHS: MutableList<Bitmap> = mutableListOf()
    private val args: ReconstructionFragmentArgs by navArgs()
    private var reconstructionDuration = 0L
    private var classificationDuration = 0L
    private val numberOfBands = 68
    private val bandSpacing = 204 / numberOfBands
    private var outputLabelString: String = ""
    private var clickedX = 0.0F
    private var clickedY = 0.0F
    private val bandsChosen = mutableListOf<Int>()
    private val loadingDialogFragment = LoadingDialogFragment()
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
    private var results = ArrayList<Long> ()
    private var alreadyMultiLabelInferred = false

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
        LoadingDialogFragment.text = "Reconstructing Hypercube"
        loadingDialogFragment.isCancelable = false

        sharedPreferences = requireActivity().getSharedPreferences("mobispectral_preferences", Context.MODE_PRIVATE)
        mobiSpectralApplication = sharedPreferences.getString("application", "Organic Non-Organic Apple Classification")!!
        reconstructionFile = when (mobiSpectralApplication) {
//            "Organic Non-Organic Apple Classification" -> "mobile_mst_apple_wb.pt"
            "Organic Non-Organic Apple Classification" -> "mobile_mst_apple_kiwi_blueberry.pt"
            "Olive Oil Adulteration" -> "mobile_mst_oil.pt"
            "Organic Non-Organic Kiwi Classification" -> "mobile_mst_kiwi_dayl.pt"
            else -> "mobile_mst_apple_wb.pt"
        }

        classificationFile = when (mobiSpectralApplication) {
            "Organic Non-Organic Apple Classification" -> R.raw.mobile_classifier_apple_kiwi_blueberry
//            "Organic Non-Organic Apple Classification" -> R.raw.mobile_classifier_apple
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
//            fragmentReconstructionBinding.textViewClassTime.text = ""
            fragmentReconstructionBinding.simpleModeSignaturePositionTextView.text = getString(R.string.simple_mode_signature_string, (Utils.croppedWidth/2F).toInt(), (Utils.croppedHeight/2F).toInt())
//            fragmentReconstructionBinding.textViewReconTime.visibility = View.INVISIBLE
//            fragmentReconstructionBinding.textViewClassTime.visibility = View.INVISIBLE
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
            { view, item, position ->
                view as ImageView
                view.scaleType = ImageView.ScaleType.FIT_XY
                var bitmapOverlay = Bitmap.createBitmap(item.width, item.height, item.config)
                var canvas = Canvas(bitmapOverlay)
                canvas.drawBitmap(item, Matrix(), null)
                var itemTouched = false
                var savedClickedX = 0.0F
                var savedClickedY = 0.0F

                if (advancedControlOption) {
                    view.setOnTouchListener { v, event ->
                        clickedX = (event!!.x / v!!.width) * bitmapsWidth
                        clickedY = (event.y / v.height) * bitmapsHeight
                        if (!itemTouched) {
                            savedClickedX = clickedX
                            savedClickedY = clickedY
                            itemTouched = true
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
                            paint.strokeWidth = 2.5F
                            val tempRect = MainActivity.tempRectangle

                            if (position == 9 && (clickedX >= tempRect.left || clickedX <= tempRect.right)
                                && (clickedY >= tempRect.top || clickedY <= tempRect.bottom))
                            {

                            }
                            else {
                                canvas.drawCircle(clickedX, clickedY, 5F, paint)
                                view.setImageBitmap(bitmapOverlay)
                                try {
                                    inference()
                                    MainActivity.actualLabel = ""
                                    addCSVLog(requireContext())
                                } catch (e: NullPointerException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                        if (itemTouched && savedClickedX != clickedX && savedClickedY != clickedY)
                            itemTouched = false
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
                    addCSVLog(requireContext())
                }
                Glide.with(view).load(item).into(view)
            }
        }
        val editor = sharedPreferences.edit()
        MainActivity.fruitID = sharedPreferences.getString("fruitID", "0").toString()
        MainActivity.fruitID = (MainActivity.fruitID.toInt() + 1).toString()
        editor.putString("fruitID", MainActivity.fruitID)
        editor.apply()

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
        loadingDialogFragment.show(childFragmentManager, LoadingDialogFragment.TAG)
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
            graphView.gridLabelRenderer.padding = 50
            graphView.gridLabelRenderer.textSize = 50F
            graphView.gridLabelRenderer.horizontalAxisTitleTextSize = 50F
            graphView.gridLabelRenderer.verticalAxisTitleTextSize = 50F
            graphView.title = "Click on the image to show the signature"
            graphView.titleTextSize = 50F
            graphView.viewport.isXAxisBoundsManual = true
            graphView.viewport.setMaxX(1000.0)
            graphView.viewport.setMinX(400.0)
            graphView.viewport.isYAxisBoundsManual = true
            graphView.viewport.setMaxY(1.0)
            graphView.gridLabelRenderer.setHumanRounding(true)

            graphView.setOnLongClickListener{
                fragmentReconstructionBinding.textConstraintView.visibility = View.VISIBLE
                fragmentReconstructionBinding.graphView.visibility = View.INVISIBLE
                false
            }

            val viewpagerThread = Thread {
                for (i in 0 until numberOfBands) {
                    if (i % 16 > 0) continue
                    Log.i("Bands Chosen", "$i")
                    bandsChosen.add(i)
                    addItemToViewPager(fragmentReconstructionBinding.viewpager, getBand(predictedHS, i), i)
                }
            }
            TabLayoutMediator(fragmentReconstructionBinding.tabLayout,
                fragmentReconstructionBinding.viewpager) { tab, position ->
                if (position == 5)
                    tab.text = "RGB"
                else
                    tab.text = ACTUAL_BAND_WAVELENGTHS[bandsChosen[position] * bandSpacing].roundToInt().toString() + " nm"
            }.attach()

            viewpagerThread.start()
            try { viewpagerThread.join() }
            catch (exception: InterruptedException) { exception.printStackTrace() }
            addItemToViewPager(fragmentReconstructionBinding.viewpager, MainActivity.tempRGBBitmap, 5)
//            fragmentReconstructionBinding.viewpager.currentItem = fragmentReconstructionBinding.viewpager.adapter!!.itemCount - 1
            loadingDialogFragment.dismissDialog()
        }
    }

    private fun inference() {
        val ortEnvironment = OrtEnvironment.getEnvironment()
        val ortSession = context?.let { createORTSession(ortEnvironment) }

        if (!advancedControlOption) {
            clickedX = bitmapsWidth/2F
            clickedY = bitmapsHeight/2F
        }
        val finalResults = ArrayList<Long> ()

        if (bitmapsWidth == 64 && bitmapsHeight == 64 && !advancedControlOption && !alreadyMultiLabelInferred) {
            val multiClassificationThread = Thread {
//                for (i in 1..16)
//                    for (j in 1..16)
//                        classifyOneSignature(8*i-4, 8*j-4)
                val zoneHeight = 16
                val zoneWidth = 16
                val numberOfZones = bitmapsWidth/zoneWidth

                for (z1 in 0 until numberOfZones) {
                    for (z2 in 0 until numberOfZones) {
                        results = ArrayList()

                        for (i in 1 .. zoneWidth) {
                            for (j in 1 .. zoneHeight) {
                                classifyOneSignature(z1*zoneWidth+i, z2*zoneHeight+j)
                            }
                        }
                        val frequencies = results.groupingBy { it }.eachCount()
                        finalResults.add(frequencies.maxBy { it.value }?.key!!)
                        Log.i("Signatures OneClassify", "Final Results: $finalResults")
                    }

                }
            }
            multiClassificationThread.start()
            try { multiClassificationThread.join() }
            catch (exception: InterruptedException) { exception.printStackTrace() }

            val finalFrequencies = finalResults.groupingBy { it }.eachCount()
            Log.i("Signatures OneClassify", "$finalResults $finalFrequencies")
            var frequenciesString = ""
            for (item in finalFrequencies) {
                val substring = if (Pair(mobiSpectralApplication, item.key) !in classificationLabels)
                    "Something went wrong = ${item.value}\n"
                else
                    "${classificationLabels[Pair(mobiSpectralApplication, item.key)]!!} = ${item.value}\n"

                frequenciesString += substring
            }
            Log.i("Frequency String", frequenciesString)
            fragmentReconstructionBinding.textViewClassTime.text = frequenciesString
            fragmentReconstructionBinding.textViewClassTime.visibility = View.VISIBLE
//            Toast.makeText(requireContext(), frequenciesString, Toast.LENGTH_LONG).show()
//            results = ArrayList()
        }

        val inputSignature = getSignature(predictedHS, clickedX.toInt(), clickedY.toInt())
        val outputLabel = ortSession?.let { classifyHypercube(inputSignature, it, ortEnvironment) }
        println("Output Label: $outputLabel")
        outputLabelString = if (Pair(mobiSpectralApplication, outputLabel) !in classificationLabels)
            "Something went wrong"
        else
            classificationLabels[Pair(mobiSpectralApplication, outputLabel)]!!
        MainActivity.predictedLabel = outputLabelString
//        addCSVLog(requireContext())
        fragmentReconstructionBinding.textViewClass.text = outputLabelString
        fragmentReconstructionBinding.graphView.title = "$outputLabelString Signature at (x: ${clickedX.toInt()}, y: ${clickedY.toInt()})"
        alreadyMultiLabelInferred = true
    }
    private fun classifyOneSignature(x: Int, y: Int) {
        val ortEnvironment = OrtEnvironment.getEnvironment()
        val ortSession = context?.let { createORTSession(ortEnvironment) }

        val signature = getSignature(predictedHS, x, y)
        val outputLabel = ortSession?.let { classifyHypercube(signature, it, ortEnvironment) }
        Log.i("Signatures OneClassify", "$x, $y, $outputLabel")

        outputLabelString = if (Pair(mobiSpectralApplication, outputLabel) !in classificationLabels)
            "Something went wrong"
        else
            classificationLabels[Pair(mobiSpectralApplication, outputLabel)]!!
        if (outputLabel != null) {
            results.add(outputLabel)
        }
//        MainActivity.predictedLabel = outputLabelString
    }

    private fun getSignature(predictedHS: FloatArray, SignatureX: Int, SignatureY: Int): FloatArray {
        val signature = FloatArray(numberOfBands)
//        Log.i("Touch Coordinates", "$SignatureX, $SignatureY")
        val leftX = bitmapsWidth - SignatureX - 1       // -1 is the pixel itself
        val leftY = bitmapsHeight - SignatureY - 1      // -1 is the pixel itself

        var idx = bitmapsWidth*SignatureY
        idx += SignatureX
//        print("Signature is:")
        val series = LineGraphSeries<DataPoint>()

        for (i in 0 until numberOfBands) {
            signature[i] = predictedHS[idx]
            print(" ${predictedHS[idx]},")
            if (advancedControlOption)
                series.appendData(DataPoint(ACTUAL_BAND_WAVELENGTHS[i*bandSpacing], predictedHS[idx].toDouble()), true, numberOfBands)
            idx += leftX + bitmapsWidth*leftY + (bitmapsWidth*SignatureY + SignatureX)
        }
        val graphView = fragmentReconstructionBinding.graphView
//        graphView.removeAllSeries()         // remove all previous series
        graphView.title = "$outputLabelString Signature at (x: $SignatureX, y: $SignatureY)"
        graphView.gridLabelRenderer.padding = 50
        graphView.gridLabelRenderer.textSize = 50F
        series.dataPointsRadius = 20F
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
        reconstructionDuration = (endTime - startTime)
        println(getString(R.string.reconstruction_time_string, reconstructionDuration))
        MainActivity.reconstructionTime = "$reconstructionDuration ms"
        fragmentReconstructionBinding.textViewReconTime.text = getString(R.string.reconstruction_time_string, reconstructionDuration)
    }

    private fun classifyHypercube(input: FloatArray, ortSession: OrtSession,
                                  ortEnvironment: OrtEnvironment): Long {
        val inputName = ortSession.inputNames?.iterator()?.next()
        val floatBufferInputs = FloatBuffer.wrap(input)
        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment, floatBufferInputs, longArrayOf(1, numberOfBands.toLong()))

        val startTime = System.currentTimeMillis()
        val results = ortSession.run(mapOf(inputName to inputTensor))
        val endTime = System.currentTimeMillis()

        classificationDuration = (endTime - startTime)
        MainActivity.classificationTime = "$classificationDuration ms"
        println(getString(R.string.classification_time_string, classificationDuration))
//        fragmentReconstructionBinding.textViewClassTime.text = getString(R.string.classification_time_string, classificationDuration)
//        for (item in 0 .. results.size()){
//            Log.i("Results", "${results[item].value}")
//        }
        var output = results[0].value
        println("Classifier OUTPUT Label" + output.toString() + " result: ${results[0].value}")
        output = output as LongArray
        println("Classifier OUTPUT Label: " + output[0].toString())

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
        Log.i("BandHS", "${bandsHS.size} ${bandsChosen.size}")
        Timer().schedule(1000) {
            if (bandsHS.size == bandsChosen.size && !advancedControlOption) {
                inference()
                addCSVLog(requireContext())
            }
        }
    }

    companion object {
        private val ACTUAL_BAND_WAVELENGTHS = listOf(397.32, 400.20, 403.09, 405.97, 408.85, 411.74, 414.63, 417.52, 420.40, 423.29, 426.19, 429.08, 431.97, 434.87, 437.76, 440.66, 443.56, 446.45, 449.35, 452.25, 455.16, 458.06, 460.96, 463.87, 466.77, 469.68, 472.59, 475.50, 478.41, 481.32, 484.23, 487.14, 490.06, 492.97, 495.89, 498.80, 501.72, 504.64, 507.56, 510.48, 513.40, 516.33, 519.25, 522.18, 525.10, 528.03, 530.96, 533.89, 536.82, 539.75, 542.68, 545.62, 548.55, 551.49, 554.43, 557.36, 560.30, 563.24, 566.18, 569.12, 572.07, 575.01, 577.96, 580.90, 583.85, 586.80, 589.75, 592.70, 595.65, 598.60, 601.55, 604.51, 607.46, 610.42, 613.38, 616.34, 619.30, 622.26, 625.22, 628.18, 631.15, 634.11, 637.08, 640.04, 643.01, 645.98, 648.95, 651.92, 654.89, 657.87, 660.84, 663.81, 666.79, 669.77, 672.75, 675.73, 678.71, 681.69, 684.67, 687.65, 690.64, 693.62, 696.61, 699.60, 702.58, 705.57, 708.57, 711.56, 714.55, 717.54, 720.54, 723.53, 726.53, 729.53, 732.53, 735.53, 738.53, 741.53, 744.53, 747.54, 750.54, 753.55, 756.56, 759.56, 762.57, 765.58, 768.60, 771.61, 774.62, 777.64, 780.65, 783.67, 786.68, 789.70, 792.72, 795.74, 798.77, 801.79, 804.81, 807.84, 810.86, 813.89, 816.92, 819.95, 822.98, 826.01, 829.04, 832.07, 835.11, 838.14, 841.18, 844.22, 847.25, 850.29, 853.33, 856.37, 859.42, 862.46, 865.50, 868.55, 871.60, 874.64, 877.69, 880.74, 883.79, 886.84, 889.90, 892.95, 896.01, 899.06, 902.12, 905.18, 908.24, 911.30, 914.36, 917.42, 920.48, 923.55, 926.61, 929.68, 932.74, 935.81, 938.88, 941.95, 945.02, 948.10, 951.17, 954.24, 957.32, 960.40, 963.47, 966.55, 969.63, 972.71, 975.79, 978.88, 981.96, 985.05, 988.13, 991.22, 994.31, 997.40, 1000.49, 1003.58)
    }
}