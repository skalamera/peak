package com.cl600.obd.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cl600.obd.R
import com.cl600.obd.model.LogEntry
import com.cl600.obd.model.LogLevel
import com.cl600.obd.model.TransportType
import com.cl600.obd.obd.ObdManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogViewBinder(
    root: View,
    private val context: Context
) {
    private val btnClearLog: Button = root.findViewById(R.id.btnClearLog)
    private val btnCopyLog: Button = root.findViewById(R.id.btnCopyLog)
    private val cbVerbosePoll: CheckBox = root.findViewById(R.id.cbVerbosePoll)
    private val cbAutoScroll: CheckBox = root.findViewById(R.id.cbAutoScroll)
    private val svLog: ScrollView = root.findViewById(R.id.svLog)
    private val tvLogOutput: TextView = root.findViewById(R.id.tvLogOutput)
    private val etRawCmd: EditText = root.findViewById(R.id.etRawCmd)
    private val btnSendCmd: Button = root.findViewById(R.id.btnSendCmd)

    private val btnPreset1: Button = root.findViewById(R.id.btnCmdAtz)
    private val btnPreset2: Button = root.findViewById(R.id.btnCmdAtrv)
    private val btnPreset3: Button = root.findViewById(R.id.btnCmdAtdp)

    private var preset1Cmd = "INIT"
    private var preset2Cmd = "0105"
    private var preset3Cmd = "010C"

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        btnClearLog.setOnClickListener { ObdManager.clearLogs() }
        btnCopyLog.setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("CL600 OBD Logs", tvLogOutput.text.toString()))
            Toast.makeText(context, "Diagnostic log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        cbVerbosePoll.setOnCheckedChangeListener { _, isChecked ->
            ObdManager.setVerbosePollLogs(isChecked)
        }

        val sendAction = {
            val cmd = etRawCmd.text.toString().trim()
            if (cmd.isNotEmpty()) {
                ObdManager.sendRawCommand(cmd)
                etRawCmd.setText("")
            }
        }

        btnSendCmd.setOnClickListener { sendAction() }
        etRawCmd.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendAction()
                true
            } else {
                false
            }
        }

        btnPreset1.setOnClickListener { ObdManager.sendRawCommand(preset1Cmd) }
        btnPreset2.setOnClickListener { ObdManager.sendRawCommand(preset2Cmd) }
        btnPreset3.setOnClickListener { ObdManager.sendRawCommand(preset3Cmd) }
        root.findViewById<Button>(R.id.btnCmd0100).setOnClickListener { ObdManager.sendRawCommand("0100") }
        root.findViewById<Button>(R.id.btnCmd010b).setOnClickListener { ObdManager.sendRawCommand("010B") }
        root.findViewById<Button>(R.id.btnCmd03).setOnClickListener { ObdManager.sendRawCommand("03") }

        applyTransportPresets(TransportType.USB_KLINE)
    }

    fun render(
        entries: List<LogEntry>,
        verbosePolls: Boolean,
        transport: TransportType = TransportType.USB_KLINE
    ) {
        if (cbVerbosePoll.isChecked != verbosePolls) {
            cbVerbosePoll.isChecked = verbosePolls
        }
        applyTransportPresets(transport)

        val builder = SpannableStringBuilder()
        for (entry in entries) {
            val ts = timeFormat.format(Date(entry.timestampMs))
            val line = "$ts [${entry.level.tag}] ${entry.message}\n"
            val start = builder.length
            builder.append(line)
            val color = when (entry.level) {
                LogLevel.INFO -> Color.parseColor("#A1A1AA")
                LogLevel.TX -> Color.parseColor("#FFFFFF")
                LogLevel.RX -> Color.parseColor("#D4D4D8")
                LogLevel.WARN -> Color.parseColor("#F59E0B")
                LogLevel.ERROR -> Color.parseColor("#EF4444")
            }
            builder.setSpan(
                ForegroundColorSpan(color),
                start,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tvLogOutput.text = builder

        if (cbAutoScroll.isChecked) {
            svLog.post {
                svLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun applyTransportPresets(transport: TransportType) {
        if (transport == TransportType.USB_KLINE) {
            preset1Cmd = "INIT"
            preset2Cmd = "0105"
            preset3Cmd = "010C"
            etRawCmd.hint = "Enter OBD hex (e.g. 0105, 010B, 010C, 03, INIT)"
        } else {
            preset1Cmd = "ATZ"
            preset2Cmd = "ATRV"
            preset3Cmd = "ATDP"
            etRawCmd.hint = "Enter AT or OBD hex command (e.g. ATDP, 010C)"
        }
        if (btnPreset1.text.toString() != preset1Cmd) btnPreset1.text = preset1Cmd
        if (btnPreset2.text.toString() != preset2Cmd) btnPreset2.text = preset2Cmd
        if (btnPreset3.text.toString() != preset3Cmd) btnPreset3.text = preset3Cmd
    }
}
