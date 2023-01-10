package com.shahzaib.mobispectral.fragments

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import com.shahzaib.mobispectral.databinding.FragmentReconstructionDialogBinding

class ReconstructionDialogFragment: DialogFragment() {
    private lateinit var alertDialog: AlertDialog
    private var _fragmentReconstructionDialogBinding: FragmentReconstructionDialogBinding? = null
    private val fragmentReconstructionDialogBinding get() = _fragmentReconstructionDialogBinding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        super.onCreateDialog(savedInstanceState)
        _fragmentReconstructionDialogBinding = FragmentReconstructionDialogBinding.inflate(layoutInflater)
        _fragmentReconstructionDialogBinding!!.progressBar.visibility = View.VISIBLE
        _fragmentReconstructionDialogBinding!!.progressBar.setProgress(30)
        alertDialog = AlertDialog.Builder(requireContext())
            .setCancelable(false)
            .create()

        alertDialog.setView(_fragmentReconstructionDialogBinding!!.root)
        return alertDialog
    }

    fun startDialog() {
        alertDialog.show()
    }

    fun dismissDialog() {
        alertDialog.dismiss()
    }

    companion object {
        const val TAG = "ReconstructionDialog"
    }
}