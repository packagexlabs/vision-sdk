package com.example.vision_sdk_android

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MultiBarcodeFragment : DialogFragment() {
    var shown = false
    lateinit var btnBack: FloatingActionButton
    var barcodesList = mutableListOf<BarcodeModel>()
    lateinit var adapter: BarcodeAdapter
    lateinit var recyclerView: RecyclerView
    lateinit var textCopyAll: TextView
    lateinit var inputBarcode: EditText
    lateinit var addIcon: FloatingActionButton
    override fun onStart() {
        super.onStart()
        val width = (requireActivity().resources.displayMetrics.widthPixels * 0.90).toInt()
        val height = (requireActivity().resources.displayMetrics.heightPixels * 0.90).toInt()
//        dialog?.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setLayout(width, height)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        dialog?.setCancelable(false)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.multi_codes_view, container, false)
        dialog?.setCancelable(false)
        dialog?.setCanceledOnTouchOutside(true)
        btnBack = view.findViewById(R.id.btnBack)
        adapter = BarcodeAdapter()
        recyclerView = view.findViewById(R.id.barcodesRecyclerView)
        recyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        textCopyAll = view.findViewById(R.id.textCopyAll)
        inputBarcode = view.findViewById(R.id.inputValue)
        addIcon = view.findViewById(R.id.addIcon)
        return view
    }

    companion object {
        fun newInstance(title: String?): MultiBarcodeFragment {
            val frag = MultiBarcodeFragment()
            return frag
        }
    }

    override fun show(manager: FragmentManager, tag: String?) {
        if (shown) return
        super.show(manager, tag)
        shown = true
    }

    override fun onDismiss(dialog: DialogInterface) {
        shown = false
        super.onDismiss(dialog)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnBack.setOnClickListener {
            dialog?.dismiss()
        }
        adapter.setBarcodesList(barcodesList)
        recyclerView.adapter = adapter
        textCopyAll.setOnClickListener {
            val list = adapter.barcodes.distinct()
            if (list.isNotEmpty()) {
                var message = StringBuilder()
                for (i in 0..list.size) list.forEach {
                    message.append("${it.value}\n")
                }
                requireActivity().copyToClipboard(message.toString())
                Toast.makeText(requireContext(), "Copied!", Toast.LENGTH_SHORT).show()
            }


        }
        addIcon.setOnClickListener {
            val string = inputBarcode.text.toString()
            if (string.isNotEmpty()) {
                adapter.barcodes.add(BarcodeModel(string))
                adapter.notifyDataSetChanged()
                inputBarcode.setText("")
            }
        }
    }

}