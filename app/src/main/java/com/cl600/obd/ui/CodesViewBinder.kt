package com.cl600.obd.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cl600.obd.R
import com.cl600.obd.model.ConnectionState
import com.cl600.obd.model.DtcEntry
import com.cl600.obd.obd.ObdManager

class CodesViewBinder(
    root: View,
    private val context: Context
) {
    private val tvMilStatus: TextView = root.findViewById(R.id.tvMilStatus)
    private val tvDtcSummary: TextView = root.findViewById(R.id.tvDtcSummary)
    private val btnScan: Button = root.findViewById(R.id.btnScanCodes)
    private val btnClear: Button = root.findViewById(R.id.btnClearCodes)
    private val rvDtcs: RecyclerView = root.findViewById(R.id.rvDtcs)
    private val tvEmpty: TextView = root.findViewById(R.id.tvEmptyDtcs)

    private val adapter = DtcAdapter()

    init {
        rvDtcs.layoutManager = LinearLayoutManager(context)
        rvDtcs.adapter = adapter

        btnScan.setOnClickListener {
            ObdManager.scanCodes()
        }

        btnClear.setOnClickListener {
            confirmClearCodes()
        }
    }

    fun render(
        dtcs: List<DtcEntry>,
        milOn: Boolean,
        statusText: String,
        isBusy: Boolean,
        state: ConnectionState
    ) {
        val streaming = state.isStreaming()
        btnScan.isEnabled = streaming && !isBusy
        btnClear.isEnabled = streaming && !isBusy

        tvMilStatus.text = when {
            !streaming -> "CHECK ENGINE (MIL): NOT CONNECTED"
            milOn -> "CHECK ENGINE (MIL): ON"
            else -> "CHECK ENGINE (MIL): OFF"
        }

        val milColor = when {
            !streaming -> ContextCompat.getColor(context, R.color.text_secondary)
            milOn -> ContextCompat.getColor(context, R.color.status_error)
            else -> ContextCompat.getColor(context, R.color.status_connected)
        }
        tvMilStatus.setTextColor(milColor)
        tvDtcSummary.text = if (isBusy) "Working..." else statusText

        adapter.submitList(dtcs)
        if (dtcs.isEmpty()) {
            rvDtcs.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = if (streaming) {
                statusText
            } else {
                "No fault codes loaded.\nConnect to your CL600 (or try Demo mode), then tap SCAN CODES."
            }
        } else {
            rvDtcs.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
        }
    }

    private fun confirmClearCodes() {
        AlertDialog.Builder(context)
            .setTitle("Clear Engine Fault Codes?")
            .setMessage(
                "This sends OBD-II Mode 04 to erase stored & pending fault codes, turn off the Check Engine light, and reset emissions readiness monitors.\n\nOnly clear codes with the engine stopped (ignition ON) and after noting the codes."
            )
            .setPositiveButton("CLEAR CODES") { _, _ ->
                ObdManager.clearCodes()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }
}
