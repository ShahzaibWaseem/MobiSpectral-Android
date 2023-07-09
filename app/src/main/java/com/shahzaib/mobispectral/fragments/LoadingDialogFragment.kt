package com.shahzaib.mobispectral.fragments

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.fragment.app.DialogFragment
import com.shahzaib.mobispectral.databinding.FragmentReconstructionDialogBinding

class LoadingDialogFragment: DialogFragment() {
    private lateinit var alertDialog: AlertDialog
    private var _fragmentReconstructionDialogBinding: FragmentReconstructionDialogBinding? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var alertDialogBuilder: AlertDialog.Builder

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        super.onCreateDialog(savedInstanceState)
        _fragmentReconstructionDialogBinding = FragmentReconstructionDialogBinding.inflate(layoutInflater)
        progressBar = _fragmentReconstructionDialogBinding!!.progressBar
        alertDialogBuilder = AlertDialog.Builder(requireContext()).setCancelable(false)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 30
        _fragmentReconstructionDialogBinding!!.textView.text = text
        alertDialog = alertDialogBuilder.create()

        alertDialog.setView(_fragmentReconstructionDialogBinding!!.root)
        return alertDialog
    }

    fun setText(text: String) { _fragmentReconstructionDialogBinding?.textView?.text = text }

    fun hideProgressBar() { progressBar.visibility = View.INVISIBLE }

    fun setTitle(title: String) { alertDialogBuilder.setTitle(title) }

    fun dismissDialog() {
        alertDialog.dismiss()
    }

    companion object {
        const val TAG = "LoadingDialog"
        var text = ""
    }
}