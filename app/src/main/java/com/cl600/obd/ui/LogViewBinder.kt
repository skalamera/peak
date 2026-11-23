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

        root.findViewById<Button>(R.id.btnCmdAtz).setOnClickListener { ObdManager.sendRawCommand("ATZ") }
        root.findViewById<Button>(R.id.btnCmdAtrv).setOnClickListener { ObdManager.sendRawCommand("ATRV") }
        root.findViewById<Button>(R.id.btnCmdAtdp).setOnClickListener { ObdManager.sendRawCommand("ATDP") }
        root.findViewById<Button>(R.id.btnCmd0100).setOnClickListener { ObdManager.sendRawCommand("0100") }
        root.findViewById<Button>(R.id.btnCmd010b).setOnClickListener { ObdManager.sendRawCommand("010B") }
        root.findViewById<Button>(R.id.btnCmd03).setOnClickListener { ObdManager.sendRawCommand("03") }
    }

    fun render(entries: List<LogEntry>, verbosePolls: Boolean) {
        if (cbVerbosePoll.isChecked != verbosePolls) {
            cbVerbosePoll.isChecked = verbosePolls
        }

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
}
