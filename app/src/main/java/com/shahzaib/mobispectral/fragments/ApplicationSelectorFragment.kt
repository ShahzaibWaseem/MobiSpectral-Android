package com.shahzaib.mobispectral.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDirections
import androidx.navigation.Navigation
import com.shahzaib.mobispectral.MainActivity
import com.shahzaib.mobispectral.R
import com.shahzaib.mobispectral.Utils
import com.shahzaib.mobispectral.databinding.FragmentApplicationselectorBinding

class ApplicationSelectorFragment: Fragment() {
    private lateinit var fragmentApplicationselectorBinding: FragmentApplicationselectorBinding
    private lateinit var applicationArray: Array<String>

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    private fun NavController.safeNavigate(direction: NavDirections) {
        currentDestination?.getAction(direction.actionId)?.run {
            navigate(direction)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        fragmentApplicationselectorBinding = FragmentApplicationselectorBinding.inflate(inflater, container, false)
        val applicationPicker = fragmentApplicationselectorBinding.applicationPicker
        applicationArray = arrayOf(getString(R.string.organic_identification_string),
            getString(R.string.olive_oil_string), getString(R.string.kiwi_string))
        applicationPicker.minValue = 0
        applicationPicker.maxValue = applicationArray.size-1
        applicationPicker.displayedValues = applicationArray

        return fragmentApplicationselectorBinding.root
    }

    private fun disableButton(cameraIdNIR: String) {
        if (cameraIdNIR == "No NIR Camera") {
            fragmentApplicationselectorBinding.runApplicationButton.isEnabled = false
            fragmentApplicationselectorBinding.runApplicationButton.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.sfu_dark_gray))
            fragmentApplicationselectorBinding.runApplicationButton.text = resources.getString(R.string.no_nir_warning)
            fragmentApplicationselectorBinding.runApplicationButton.transformationMethod = null
        }
    }

    private fun enableButton() {
        fragmentApplicationselectorBinding.runApplicationButton.isEnabled = true
        fragmentApplicationselectorBinding.runApplicationButton.text = resources.getString(R.string.launch_application_button).uppercase()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStart() {
        super.onStart()
        var cameraIdNIR = Utils.getCameraIDs(requireContext(), MainActivity.MOBISPECTRAL_APPLICATION).second
        disableButton(cameraIdNIR)

        fragmentApplicationselectorBinding.applicationPicker.setOnValueChangedListener{_, _, newVal ->
            if (newVal != 3) {
                disableButton(cameraIdNIR)
            }
            else {
                enableButton()
                cameraIdNIR = Utils.getCameraIDs(requireContext(), MainActivity.SHELF_LIFE_APPLICATION).second
            }
        }

        fragmentApplicationselectorBinding.runApplicationButton.setOnTouchListener { _, _ ->
            val selectedApplication = applicationArray[fragmentApplicationselectorBinding.applicationPicker.value]
            val selectedRadio = fragmentApplicationselectorBinding.radioGroup.checkedRadioButtonId
            val selectedOption = requireView().findViewById<RadioButton>(selectedRadio).text.toString()
            val offlineMode = fragmentApplicationselectorBinding.liveModeCheckMark.isChecked
            val sharedPreferences = requireActivity().getSharedPreferences("mobispectral_preferences", Context.MODE_PRIVATE)
            val editor = sharedPreferences?.edit()
            editor!!.putString("application", selectedApplication)
            editor.putString("option", selectedOption)
            editor.putBoolean("offline_mode", offlineMode)
            Log.i("Radio Button", "$selectedApplication, $selectedOption")
            editor.apply()

            if (selectedApplication != "Shelf Life Prediction") {
                lifecycleScope.launchWhenStarted {
                    navController.safeNavigate(ApplicationSelectorFragmentDirections.actionAppselectorToCameraFragment(
                        Utils.getCameraIDs(requireContext(), MainActivity.MOBISPECTRAL_APPLICATION).first, ImageFormat.JPEG)
                    )
                }
            }
            else {
                lifecycleScope.launchWhenStarted {
                    navController.safeNavigate(
                        ApplicationSelectorFragmentDirections.actionAppselectorToShelflifeFragment()
                    )
                }
            }
            true
        }
    }
}