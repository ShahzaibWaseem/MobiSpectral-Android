package com.shahzaib.mobispectral.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.shahzaib.mobispectral.R
import com.shahzaib.mobispectral.Utils
import com.shahzaib.mobispectral.databinding.FragmentApplicationselectorBinding

class ApplicationSelectorFragment: Fragment() {
    private lateinit var fragmentApplicationselectorBinding: FragmentApplicationselectorBinding
    private val applicationArray: Array<String> = arrayOf("Organic Non-Organic Apple Classification", "Olive Oil Adulteration", "Organic Non-Organic Kiwi Classification")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        fragmentApplicationselectorBinding = FragmentApplicationselectorBinding.inflate(inflater, container, false)
        val applicationPicker = fragmentApplicationselectorBinding.applicationPicker
        applicationPicker.minValue = 0
        applicationPicker.maxValue = applicationArray.size-1
        applicationPicker.displayedValues = applicationArray

        return fragmentApplicationselectorBinding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStart() {
        super.onStart()
        val cameraIdNIR = Utils.getCameraIDs(requireContext()).second
        if (cameraIdNIR == "No NIR Camera") {
            fragmentApplicationselectorBinding.runApplicationButton.isEnabled = false
            fragmentApplicationselectorBinding.runApplicationButton.text = resources.getString(R.string.no_nir_warning)
            fragmentApplicationselectorBinding.runApplicationButton.transformationMethod = null
            fragmentApplicationselectorBinding.runApplicationButton.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        }
        fragmentApplicationselectorBinding.runApplicationButton.setOnTouchListener { _, _ ->
            val selectedApplication = applicationArray[fragmentApplicationselectorBinding.applicationPicker.value]
            val selectedRadio = fragmentApplicationselectorBinding.radioGroup.checkedRadioButtonId
            val selectedOption = requireView().findViewById<RadioButton>(selectedRadio).text.toString()
            val sharedPreferences = requireActivity().getSharedPreferences("mobispectral_preferences", Context.MODE_PRIVATE)
            val editor = sharedPreferences?.edit()
            editor!!.putString("application", selectedApplication)
            editor.putString("option", selectedOption)
            Log.i("Radio Button", "$selectedApplication, $selectedOption")
            editor.apply()

            lifecycleScope.launchWhenStarted {
                Navigation.findNavController(requireActivity(), R.id.fragment_container)
                    .navigate(ApplicationSelectorFragmentDirections.actionAppselectorToPermissionFragment())
            }
            true
        }
    }
}