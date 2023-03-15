package com.shahzaib.mobispectral.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.jjoe64.graphview.GraphView
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.opencsv.CSVWriter
import com.shahzaib.mobispectral.R
import com.shahzaib.mobispectral.databinding.FragmentAudioBinding
import java.io.*
import java.util.*
import kotlin.math.abs
import kotlin.math.log10


class AudioFragment: Fragment() {
    /** Android ViewBinding */
    private var _fragmentAudioBinding: FragmentAudioBinding? = null

    private val fragmentAudioBinding get() = _fragmentAudioBinding!!

    /** AndroidX navigation arguments */
    private val args: AudioFragmentArgs by navArgs()

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }
    private lateinit var audioDirectory: File
    private lateinit var newImagePath: File
    private lateinit var externalStorageDirectory: String

    private var recording = false

    private val objectArray: Array<String> = arrayOf("Grape Fruit",
        "Orange", "Tomatoes", "Apple", "Avocado")

    private val objectSideArray: Array<String> = arrayOf("Top",
        "Bottom", "Sides")

    private val organicityArray: Array<String> = arrayOf("Organic",
        "Non-Organic", "Not Applicable")

    private lateinit var sharedPreferences: SharedPreferences
    private var objectID = 0
    private lateinit var graphView: GraphView

    private fun initializingNumberPickers(numberPicker: NumberPicker, array: Array<String>) {
        numberPicker.minValue = 0
        numberPicker.maxValue = array.size - 1
        numberPicker.displayedValues = array
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalStorageDirectory = Environment.getExternalStorageDirectory().toString()
        audioDirectory = File(externalStorageDirectory, "/ShelfLife/Audio")
        if (!audioDirectory.exists()) {
            audioDirectory.mkdirs()
        }
        var rgbBitmap = BitmapFactory.decodeFile(args.filePath)
        val correctionMatrix = Matrix().apply { postScale(1.0F, 1.0F); postRotate(90F) }

        rgbBitmap = Bitmap.createBitmap(rgbBitmap, 0, 0, rgbBitmap.width, rgbBitmap.height, correctionMatrix, true)

        val imageDirectory = File(audioDirectory.parent, "/Images/")
        if (!imageDirectory.exists()) {
            imageDirectory.mkdirs()
        }
        val rgbImage = File(args.filePath)
        val directoryPath = rgbImage.absolutePath.split(System.getProperty("file.separator")!!)
        val rgbImageFileName = directoryPath[directoryPath.size-1]
        newImagePath = File(imageDirectory, rgbImageFileName)

//        if (ContextCompat.checkSelfPermission(
//                requireContext(),
//                Manifest.permission.MANAGE_EXTERNAL_STORAGE
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
////            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
////            } else {
////                TODO("VERSION.SDK_INT < R")
////            }
//            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
//
//            val uri = Uri.fromParts("package", requireContext().packageName, null)
//            intent.data = uri
//            startActivity(intent)
//        }
        Thread{
            try {
                val fos = FileOutputStream(newImagePath)
                rgbBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                fos.flush()
                fos.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()

        MediaScannerConnection.scanFile(context, arrayOf(newImagePath.absolutePath), null, null)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _fragmentAudioBinding = FragmentAudioBinding.inflate(inflater, container, false)

        initializingNumberPickers(fragmentAudioBinding.objectPicker, objectArray)
        initializingNumberPickers(fragmentAudioBinding.objectSidePicker, objectSideArray)
        initializingNumberPickers(fragmentAudioBinding.organicPicker, organicityArray)
        sharedPreferences = requireActivity().getSharedPreferences("mobispectral_preferences", Context.MODE_PRIVATE)
        objectID = sharedPreferences.getInt("objectID", 0)
        fragmentAudioBinding.objectIDTextView.text = getString(R.string.object_id_string, objectID)
        graphView = fragmentAudioBinding.graphView

        return fragmentAudioBinding.root
    }

    override fun onStart() {
        super.onStart()

        graphView.gridLabelRenderer.horizontalAxisTitle = "Frequency (Hz)"
        graphView.gridLabelRenderer.verticalAxisTitle = "Level (dBSPL)"
        graphView.viewport.isXAxisBoundsManual = true
        graphView.viewport.setMaxX(SAMPLE_RATE.toDouble())
        graphView.viewport.setMinX(0.0)
        graphView.viewport.isYAxisBoundsManual = true
//        graphView.viewport.setMaxY(90.0)
//        graphView.viewport.setMinY(0.0)
        graphView.gridLabelRenderer.setHumanRounding(true)

        fragmentAudioBinding.overlayImageView.setImageBitmap(BitmapFactory.decodeFile(args.filePath))
        fragmentAudioBinding.overlayImageView.visibility = View.VISIBLE

        val editor = sharedPreferences.edit()

        fragmentAudioBinding.plusButton.setOnClickListener {
            objectID += 1
            fragmentAudioBinding.objectIDTextView.text = getString(R.string.object_id_string, objectID)
            editor!!.putInt("objectID", objectID)
            editor.apply()
        }
        fragmentAudioBinding.minusButton.setOnClickListener {
            objectID -= if (objectID == 0) 0 else 1                     // No Negative IDs
            fragmentAudioBinding.objectIDTextView.text = getString(R.string.object_id_string, objectID)
            editor!!.putInt("objectID", objectID)
            editor.apply()
        }

        fragmentAudioBinding.overlayImageView.setOnLongClickListener {
            fragmentAudioBinding.overlayImageView.visibility = View.INVISIBLE
            fragmentAudioBinding.graphView.visibility = View.VISIBLE
            fragmentAudioBinding.dataConstraints.visibility = View.VISIBLE
            true
        }
        fragmentAudioBinding.recordAudioButton.setOnClickListener {
            if (!recording) {
                startRecording()
                recording = true
            }
            else {
                Toast.makeText(requireContext(), "Already Recording", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val graphviewVisibility = fragmentAudioBinding.graphView.visibility
        if (graphviewVisibility == View.VISIBLE) {
            fragmentAudioBinding.overlayImageView.visibility = View.INVISIBLE
            fragmentAudioBinding.graphView.visibility = View.VISIBLE
            fragmentAudioBinding.dataConstraints.visibility = View.VISIBLE
        }
        else {
            fragmentAudioBinding.overlayImageView.visibility = View.VISIBLE
            fragmentAudioBinding.graphView.visibility = View.INVISIBLE
            fragmentAudioBinding.dataConstraints.visibility = View.INVISIBLE
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val audioRecord =
            AudioRecord(AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE)
        val buffer = ByteArray(BUFFER_SIZE / 2)
        //First check whether the above object actually initialized
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecord", "error initializing AudioRecord")
        }
        audioRecord.startRecording()
        val recordingDuration = 5 * 1000 // 5 seconds in milliseconds
        val startTime = System.currentTimeMillis()
        var currentTime = startTime

        while (currentTime - startTime < recordingDuration) {
            audioRecord.read(buffer, 0, buffer.size)
            plotData(buffer)
            currentTime = System.currentTimeMillis()
        }
        audioRecord.stop()
        audioRecord.release()
        val file = File(audioDirectory, FILE_NAME.format(args.fileFormat))

        try {
            file.writeBytes(buffer)
        } catch (e: IOException) {
            Log.i("Audio File Saved", "File Not saved")
            e.printStackTrace()
        }
        // Notify the media scanner about the new file

        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        updateCSV(file.absolutePath)

        recording = false
    }

    private fun highPassFilter(audioData: ShortArray): ShortArray {
        val filteredData = ShortArray(audioData.size)
        val timeConstant = 1.0f / (CUTOFF_FREQUENCY * 2 * Math.PI).toFloat()
        val alpha = 1.0f / (timeConstant + 1.0f / SAMPLE_RATE.toFloat())
        var previousInput = 0.0f
        var previousOutput = 0.0f

        for (i in audioData.indices) {
            val x = audioData[i].toFloat() / Short.MAX_VALUE.toFloat()
            val filteredSample = alpha * (previousOutput + x - previousInput)
            filteredData[i] = (filteredSample * Short.MAX_VALUE.toFloat()).toInt().toShort()
            previousInput = x
            previousOutput = filteredSample
            Log.i("Audio", "${filteredData[i]} $x")
        }
        return filteredData
    }

    private fun updateCSV(absolutePath: String) {
        val csvFile = File(audioDirectory.parent, CSV_FILE)
        val entries = arrayOf(File(newImagePath.toURI()).absoluteFile.toString(), absolutePath)
        val writer = CSVWriter(
            FileWriter(csvFile, true),
            ',',
            CSVWriter.NO_QUOTE_CHARACTER,
            CSVWriter.DEFAULT_ESCAPE_CHARACTER,
            "\r\n"
        )
        writer.writeNext(entries)
        writer.close()
        MediaScannerConnection.scanFile(context, arrayOf(csvFile.absolutePath), null, null)
    }

    private fun plotData(data: ByteArray) {
        // dbSPL: decibels relative to 20 micropascals
        val size = data.size
        val dBSPL = DoubleArray(size)
        val frequency = DoubleArray(size)

        for (i in 0 until size) {
            val pressure = abs(data[i].toDouble() / Short.MAX_VALUE)
            // 32768f is the maximum amplitude value of a 16-bit audio sample
            // 20e-6f is the pressure at 20 micropascals
            dBSPL[i] = 20.0 * log10((pressure.toFloat() / 20e-6f) + 0.001f).toDouble()
            frequency[i] = (i * SAMPLE_RATE / size).toDouble()
//            Log.i("Frequency", "${frequency[i]}, ${dBSPL[i]}")
        }

        val series = LineGraphSeries<DataPoint>()
        val dataPoints = frequency.zip(dBSPL).mapIndexed { _, pair -> DataPoint(pair.first, pair.second)}
        series.resetData(dataPoints.toTypedArray())
//        graphView.removeAllSeries()
//        graphView.addSeries(series)
    }

    companion object {
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        private const val FILE_NAME = "recording_%s.pcm"
        private const val CSV_FILE = "FileLinker.csv"
        private const val CUTOFF_FREQUENCY = 17.0
    }
}