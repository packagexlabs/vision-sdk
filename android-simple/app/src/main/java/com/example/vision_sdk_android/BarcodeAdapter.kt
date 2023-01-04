package com.example.vision_sdk_android

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView

class BarcodeAdapter : RecyclerView.Adapter<BarcodeAdapter.BarcodeViewHolder>() {
    var barcodes = mutableListOf<BarcodeModel>()
    fun setBarcodesList(barcodes: MutableList<BarcodeModel>) {
        barcodes.forEach {
            if (!this.barcodes.contains(it)) {
                this.barcodes.add(it)
            }
        }
//        this.barcodes=barcodes
//        this.barcodes=barcodes
        notifyDataSetChanged()
    }

    inner class BarcodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textCopy = itemView.findViewById<TextView>(R.id.textCopy)
        val barcodeValue = itemView.findViewById<TextView>(R.id.barcodeValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BarcodeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.barcode_item, parent, false)
        return BarcodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: BarcodeViewHolder, position: Int) {
        val barcodeValue = barcodes[position]
        holder.barcodeValue.text = barcodeValue.value
        holder.textCopy.setOnClickListener {
            Toast.makeText(holder.itemView.context, "You Clicked $barcodeValue", Toast.LENGTH_SHORT)
                .show()
        }
        holder.textCopy.setOnClickListener {
            holder.itemView.context.copyToClipboard(barcodeValue.value)
            Toast.makeText(holder.itemView.context, "Copied!", Toast.LENGTH_SHORT).show()
        }
        holder.barcodeValue.setOnLongClickListener {
            AlertDialog.Builder(holder.itemView.context).setCancelable(false)
                .setTitle("Deleting Barcode").setMessage("Do you want to delete the barcode value?")
                .setPositiveButton(
                    "Yes"
                ) { dialog, which ->
                    barcodes.remove(barcodeValue)
                    notifyDataSetChanged()
                }.setNegativeButton(
                    "No"
                ) { dialog, which -> dialog?.dismiss() }.show()
            return@setOnLongClickListener true
        }
    }

    override fun getItemCount(): Int {
        return barcodes.size
    }
}