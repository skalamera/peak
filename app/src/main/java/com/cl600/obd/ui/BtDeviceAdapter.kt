package com.cl600.obd.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cl600.obd.R

data class BtDeviceUiItem(
    val name: String,
    val address: String,
    val badge: String,
    val isObdCandidate: Boolean
)

class BtDeviceAdapter(
    private val onSelected: (BtDeviceUiItem) -> Unit
) : RecyclerView.Adapter<BtDeviceAdapter.BtViewHolder>() {

    private val items = ArrayList<BtDeviceUiItem>()
    private var selectedAddress: String = ""

    fun submitList(newItems: List<BtDeviceUiItem>, selectedMac: String) {
        items.clear()
        items.addAll(newItems)
        selectedAddress = selectedMac.uppercase()
        notifyDataSetChanged()
    }

    fun setSelectedMac(mac: String) {
        selectedAddress = mac.uppercase()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BtViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bt_device, parent, false)
        return BtViewHolder(view, onSelected)
    }

    override fun onBindViewHolder(holder: BtViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, item.address.equals(selectedAddress, ignoreCase = true))
    }

    override fun getItemCount(): Int = items.size

    class BtViewHolder(
        itemView: View,
        private val onSelected: (BtDeviceUiItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvBtName)
        private val tvAddress: TextView = itemView.findViewById(R.id.tvBtAddress)
        private val tvBadge: TextView = itemView.findViewById(R.id.tvBtBadge)

        fun bind(item: BtDeviceUiItem, isSelected: Boolean) {
            itemView.isSelected = isSelected
            tvName.text = item.name.ifBlank { "Unnamed Bluetooth Device" }
            tvAddress.text = item.address
            tvBadge.text = if (isSelected) "SELECTED" else item.badge

            val badgeColor = when {
                isSelected -> ContextCompat.getColor(itemView.context, R.color.status_connected)
                item.isObdCandidate -> ContextCompat.getColor(itemView.context, R.color.accent_amber)
                else -> ContextCompat.getColor(itemView.context, R.color.text_secondary)
            }
            tvBadge.setTextColor(badgeColor)

            itemView.setOnClickListener {
                onSelected(item)
            }
        }
    }
}
