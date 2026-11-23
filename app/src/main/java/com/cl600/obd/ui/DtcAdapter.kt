package com.cl600.obd.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cl600.obd.R
import com.cl600.obd.model.DtcCategory
import com.cl600.obd.model.DtcEntry

class DtcAdapter : RecyclerView.Adapter<DtcAdapter.DtcViewHolder>() {

    private val items = ArrayList<DtcEntry>()

    fun submitList(newItems: List<DtcEntry>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DtcViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dtc, parent, false)
        return DtcViewHolder(view)
    }

    override fun onBindViewHolder(holder: DtcViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class DtcViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCode: TextView = itemView.findViewById(R.id.tvDtcCode)
        private val tvCategory: TextView = itemView.findViewById(R.id.tvDtcCategory)
        private val tvDesc: TextView = itemView.findViewById(R.id.tvDtcDescription)
        private val tvNote: TextView = itemView.findViewById(R.id.tvDtcNote)

        fun bind(entry: DtcEntry) {
            tvCode.text = entry.code
            tvCategory.text = entry.category.label
            val catColor = when (entry.category) {
                DtcCategory.STORED -> ContextCompat.getColor(itemView.context, R.color.status_error)
                DtcCategory.PENDING -> ContextCompat.getColor(itemView.context, R.color.accent_amber)
                DtcCategory.PERMANENT -> ContextCompat.getColor(itemView.context, R.color.accent_cyan)
            }
            tvCategory.setTextColor(catColor)
            tvDesc.text = entry.description

            if (!entry.m275Note.isNullOrBlank()) {
                tvNote.visibility = View.VISIBLE
                tvNote.text = entry.m275Note
            } else {
                tvNote.visibility = View.GONE
            }
        }
    }
}
