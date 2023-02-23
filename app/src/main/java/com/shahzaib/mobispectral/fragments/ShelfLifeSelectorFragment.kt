package com.shahzaib.mobispectral.fragments

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDirections
import androidx.navigation.Navigation
import com.shahzaib.mobispectral.R
import com.shahzaib.mobispectral.databinding.FragmentShelflifeselectorBinding

class ShelfLifeSelectorFragment: Fragment() {
    private lateinit var fragmentShelflifeselectorBinding: FragmentShelflifeselectorBinding

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    private fun NavController.safeNavigate(direction: NavDirections) {
        currentDestination?.getAction(direction.actionId)?.run {
            navigate(direction)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        fragmentShelflifeselectorBinding = FragmentShelflifeselectorBinding.inflate(inflater, container, false)

        return fragmentShelflifeselectorBinding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentShelflifeselectorBinding.launchApplicationButton.setOnTouchListener { _, _ ->
            lifecycleScope.launchWhenStarted {
                navController.safeNavigate(
                    ShelfLifeSelectorFragmentDirections.actionShelflifeFragmentToCameraFragment("0", ImageFormat.JPEG)
                )
            }
            true
        }
    }
}