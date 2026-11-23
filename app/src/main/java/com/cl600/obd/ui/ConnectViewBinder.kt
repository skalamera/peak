package com.cl600.obd.ui

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cl600.obd.R
import com.cl600.obd.model.ConnectionConfig
import com.cl600.obd.model.ConnectionState
import com.cl600.obd.model.ObdProtocol
import com.cl600.obd.model.ObdTelemetry
import com.cl600.obd.model.TransportType
import com.cl600.obd.obd.ObdManager
import com.cl600.obd.obd.UsbDeviceUiItem

class ConnectViewBinder(
    root: View,
    private val context: Context,
    private val onRequestUsbScan: () -> Unit,
    private val onRequestConnect: (TransportType) -> Unit
) {
    private val btnMainConnect: Button = root.findViewById(R.id.btnMainConnect)
    private val btnTryDemo: Button = root.findViewById(R.id.btnTryDemo)
    private val tvConnectDetail: TextView = root.findViewById(R.id.tvConnectStateDetail)
    private val rgTransport: RadioGroup = root.findViewById(R.id.rgTransport)
    private val rbUsbKline: RadioButton = root.findViewById(R.id.rbUsbKline)
    private val rbWifi: RadioButton = root.findViewById(R.id.rbWifi)
    private val layoutUsbConfig: View = root.findViewById(R.id.layoutUsbConfig)
    private val btnScanUsb: Button = root.findViewById(R.id.btnScanUsb)
    private val rvUsbDevices: RecyclerView = root.findViewById(R.id.rvUsbDevices)
    private val tvNoUsbDevices: TextView = root.findViewById(R.id.tvNoUsbDevices)
    private val layoutWifiConfig: View = root.findViewById(R.id.layoutWifiConfig)
    private val etWifiHost: EditText = root.findViewById(R.id.etWifiHost)
    private val etWifiPort: EditText = root.findViewById(R.id.etWifiPort)
    private val spinnerProtocol: Spinner = root.findViewById(R.id.spinnerProtocol)
    private val btnZeroBoost: Button = root.findViewById(R.id.btnZeroBoost)
    private val btnResetBaro: Button = root.findViewById(R.id.btnResetBaro)
    private val tvBaroInfo: TextView = root.findViewById(R.id.tvBaroInfo)
    private val switchAutoConnect: SwitchCompat = root.findViewById(R.id.switchAutoConnect)

    private val usbAdapter: UsbDeviceAdapter
    private var suppressCallbacks = false
    private var currentState: ConnectionState = ConnectionState.Disconnected

    init {
        usbAdapter = UsbDeviceAdapter { item ->
            ObdManager.updateConfig {
                it.copy(
                    usbDeviceName = item.deviceName,
                    transportType = TransportType.USB_KLINE
                )
            }
            Toast.makeText(
                context,
                "Selected ${item.displayTitle} (${item.vidPidLabel})",
                Toast.LENGTH_SHORT
            ).show()
        }
        rvUsbDevices.layoutManager = LinearLayoutManager(context)
        rvUsbDevices.adapter = usbAdapter

        val protoAdapter = ArrayAdapter(
            context,
            R.layout.item_spinner_text,
            ObdProtocol.values().map { it.displayName }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerProtocol.adapter = protoAdapter

        btnMainConnect.setOnClickListener {
            if (currentState.isActiveOrConnecting()) {
                ObdManager.disconnect()
            } else {
                saveWifiInputsIfNeeded()
                val transport = if (rbWifi.isChecked) TransportType.WIFI else TransportType.USB_KLINE
                ObdManager.updateConfig { it.copy(transportType = transport) }
                onRequestConnect(transport)
            }
        }

        btnTryDemo.setOnClickListener {
            ObdManager.startDemo()
        }

        rgTransport.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val transport = if (checkedId == R.id.rbWifi) TransportType.WIFI else TransportType.USB_KLINE
            ObdManager.updateConfig { it.copy(transportType = transport) }
            updateTransportVisibility(transport)
        }

        btnScanUsb.setOnClickListener {
            onRequestUsbScan()
        }

        spinnerProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCallbacks) return
                val selected = ObdProtocol.values()[position]
                if (ObdManager.config.value.obdProtocol != selected) {
                    ObdManager.updateConfig { it.copy(obdProtocol = selected) }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnZeroBoost.setOnClickListener {
            val ok = ObdManager.calibrateBaroFromCurrentMap()
            val msg = if (ok) {
                "Boost zeroed to current ambient MAP pressure"
            } else {
                "Connect with key ON (engine OFF) so MAP can be read first"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        btnResetBaro.setOnClickListener {
            ObdManager.resetBaroToStandard()
            Toast.makeText(context, "Baro reset to ECU/Standard (101.3 kPa)", Toast.LENGTH_SHORT).show()
        }

        switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            ObdManager.updateConfig { it.copy(autoConnect = isChecked) }
        }
    }

    fun updateUsbDevices(devices: List<UsbDeviceUiItem>, selectedDeviceName: String) {
        usbAdapter.submitList(devices, selectedDeviceName)
        if (devices.isEmpty()) {
            rvUsbDevices.visibility = View.GONE
            tvNoUsbDevices.visibility = View.VISIBLE
        } else {
            rvUsbDevices.visibility = View.VISIBLE
            tvNoUsbDevices.visibility = View.GONE
        }
    }

    fun render(config: ConnectionConfig, state: ConnectionState, telemetry: ObdTelemetry) {
        currentState = state
        suppressCallbacks = true

        if (config.transportType == TransportType.WIFI) {
            rbWifi.isChecked = true
        } else {
            rbUsbKline.isChecked = true
        }
        updateTransportVisibility(config.transportType)

        if (!etWifiHost.hasFocus() && etWifiHost.text.toString() != config.wifiHost) {
            etWifiHost.setText(config.wifiHost)
        }
        val portStr = config.wifiPort.toString()
        if (!etWifiPort.hasFocus() && etWifiPort.text.toString() != portStr) {
            etWifiPort.setText(portStr)
        }

        usbAdapter.setSelectedDeviceName(config.usbDeviceName)
        spinnerProtocol.setSelection(config.obdProtocol.ordinal)
        switchAutoConnect.isChecked = config.autoConnect

        suppressCallbacks = false

        tvConnectDetail.text = when (state) {
            is ConnectionState.Disconnected -> {
                val target = if (config.transportType == TransportType.WIFI) {
                    "Wi-Fi ${config.wifiHost}:${config.wifiPort}"
                } else {
                    config.usbDeviceName.ifBlank { "Galletto 1260 USB K-Line" }
                }
                "Disconnected  ($target)"
            }
            is ConnectionState.Connecting -> "Connecting: ${state.detail}"
            is ConnectionState.Initializing -> "Initializing: ${state.detail}"
            is ConnectionState.Connected -> "Connected: ${state.deviceName} • ${state.protocol}"
            is ConnectionState.Demo -> "Demo Mode Active (Simulated M275 V12)"
            is ConnectionState.Error -> "Error: ${state.message}"
        }

        btnMainConnect.text = if (state.isActiveOrConnecting()) {
            context.getString(R.string.disconnect_btn)
        } else {
            context.getString(R.string.connect_btn)
        }
        btnTryDemo.isEnabled = state !is ConnectionState.Demo

        tvBaroInfo.text = "Baro: ${telemetry.baroSource}"
    }

    private fun updateTransportVisibility(transport: TransportType) {
        if (transport == TransportType.WIFI) {
            layoutUsbConfig.visibility = View.GONE
            layoutWifiConfig.visibility = View.VISIBLE
        } else {
            layoutUsbConfig.visibility = View.VISIBLE
            layoutWifiConfig.visibility = View.GONE
        }
    }

    private fun saveWifiInputsIfNeeded() {
        val host = etWifiHost.text.toString().trim().ifEmpty { "192.168.0.10" }
        val port = etWifiPort.text.toString().trim().toIntOrNull()?.coerceIn(1, 65535) ?: 35000
        ObdManager.updateConfig {
            it.copy(wifiHost = host, wifiPort = port)
        }
    }
}
