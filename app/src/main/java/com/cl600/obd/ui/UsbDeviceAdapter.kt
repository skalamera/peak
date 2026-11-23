package com.cl600.obd.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.cl600.obd.R
import com.cl600.obd.obd.UsbDeviceUiItem

class UsbDeviceAdapter(
    private val onSelect: (UsbDeviceUiItem) -> Unit
) : RecyclerView.Adapter<UsbDeviceAdapter.Holder>() {

    private val items = ArrayList<UsbDeviceUiItem>()
    private var selectedDeviceName: String = ""

    fun submitList(list: List<UsbDeviceUiItem>, selectedName: String) {
        items.clear()
        items.addAll(list)
        selectedDeviceName = selectedName
        notifyDataSetChanged()
    }

    fun setSelectedDeviceName(deviceName: String) {
        selectedDeviceName = deviceName
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_usb_device, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val isSelected = item.deviceName == selectedDeviceName
        holder.tvName.text = item.displayTitle
        holder.tvDetail.text = "${item.vidPidLabel} • ${item.deviceName}"

        val ctx = holder.itemView.context
        if (item.isFtdi) {
            holder.tvBadge.text = if (item.hasPermission) "FTDI READY" else "GRANT USB"
            holder.tvBadge.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (item.hasPermission) R.color.accent_cyan else R.color.accent_amber
                )
            )
        } else {
            holder.tvBadge.text = if (item.hasPermission) item.driverName.uppercase() else "GRANT USB"
            holder.tvBadge.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
        }

        holder.itemView.setBackgroundResource(
            if (isSelected) R.drawable.bg_tab_pill else R.drawable.bg_input_box
        )
        holder.itemView.isSelected = isSelected
        holder.itemView.setOnClickListener { onSelect(item) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvUsbName)
        val tvDetail: TextView = view.findViewById(R.id.tvUsbDetail)
        val tvBadge: TextView = view.findViewById(R.id.tvUsbBadge)
    }
}
