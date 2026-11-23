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
import com.cl600.obd.model.BtStrategy
import com.cl600.obd.model.ConnectionConfig
import com.cl600.obd.model.ConnectionState
import com.cl600.obd.model.ObdProtocol
import com.cl600.obd.model.ObdTelemetry
import com.cl600.obd.model.TransportType
import com.cl600.obd.obd.ObdManager
import java.util.Locale

class ConnectViewBinder(
    root: View,
    private val context: Context,
    private val onRequestBtRefresh: () -> Unit,
    private val onRequestBtScan: () -> Unit,
    private val onRequestConnect: (TransportType) -> Unit
) {
    private val btnMainConnect: Button = root.findViewById(R.id.btnMainConnect)
    private val btnTryDemo: Button = root.findViewById(R.id.btnTryDemo)
    private val tvConnectDetail: TextView = root.findViewById(R.id.tvConnectStateDetail)
    private val rgTransport: RadioGroup = root.findViewById(R.id.rgTransport)
    private val rbBluetooth: RadioButton = root.findViewById(R.id.rbBluetooth)
    private val rbWifi: RadioButton = root.findViewById(R.id.rbWifi)
    private val layoutBtConfig: View = root.findViewById(R.id.layoutBtConfig)
    private val btnRefreshPaired: Button = root.findViewById(R.id.btnRefreshPaired)
    private val btnScanNearby: Button = root.findViewById(R.id.btnScanNearby)
    private val rvBtDevices: RecyclerView = root.findViewById(R.id.rvBtDevices)
    private val tvNoBtDevices: TextView = root.findViewById(R.id.tvNoBtDevices)
    private val etManualMac: EditText = root.findViewById(R.id.etManualMac)
    private val btnUseManualMac: Button = root.findViewById(R.id.btnUseManualMac)
    private val spinnerBtStrategy: Spinner = root.findViewById(R.id.spinnerBtStrategy)
    private val layoutWifiConfig: View = root.findViewById(R.id.layoutWifiConfig)
    private val etWifiHost: EditText = root.findViewById(R.id.etWifiHost)
    private val etWifiPort: EditText = root.findViewById(R.id.etWifiPort)
    private val spinnerProtocol: Spinner = root.findViewById(R.id.spinnerProtocol)
    private val btnZeroBoost: Button = root.findViewById(R.id.btnZeroBoost)
    private val btnResetBaro: Button = root.findViewById(R.id.btnResetBaro)
    private val tvBaroInfo: TextView = root.findViewById(R.id.tvBaroInfo)
    private val switchAutoConnect: SwitchCompat = root.findViewById(R.id.switchAutoConnect)

    private val btAdapter: BtDeviceAdapter
    private var suppressCallbacks = false
    private var currentState: ConnectionState = ConnectionState.Disconnected

    init {
        btAdapter = BtDeviceAdapter { item ->
            etManualMac.setText(item.address)
            ObdManager.updateConfig {
                it.copy(
                    bluetoothAddress = item.address,
                    bluetoothName = item.name,
                    transportType = TransportType.BLUETOOTH
                )
            }
            Toast.makeText(context, "Selected ${item.name} (${item.address})", Toast.LENGTH_SHORT).show()
        }
        rvBtDevices.layoutManager = LinearLayoutManager(context)
        rvBtDevices.adapter = btAdapter

        val strategyAdapter = ArrayAdapter(
            context,
            R.layout.item_spinner_text,
            BtStrategy.values().map { it.displayName }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerBtStrategy.adapter = strategyAdapter

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
                val transport = if (rbWifi.isChecked) TransportType.WIFI else TransportType.BLUETOOTH
                ObdManager.updateConfig { it.copy(transportType = transport) }
                onRequestConnect(transport)
            }
        }

        btnTryDemo.setOnClickListener {
            ObdManager.startDemo()
        }

        rgTransport.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val transport = if (checkedId == R.id.rbWifi) TransportType.WIFI else TransportType.BLUETOOTH
            ObdManager.updateConfig { it.copy(transportType = transport) }
            updateTransportVisibility(transport)
        }

        btnRefreshPaired.setOnClickListener {
            onRequestBtRefresh()
        }

        btnScanNearby.setOnClickListener {
            onRequestBtScan()
        }

        btnUseManualMac.setOnClickListener {
            val rawMac = etManualMac.text.toString().trim().uppercase(Locale.US)
            if (!isValidMac(rawMac)) {
                Toast.makeText(
                    context,
                    "Enter MAC in XX:XX:XX:XX:XX:XX format",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            ObdManager.updateConfig {
                it.copy(
                    bluetoothAddress = rawMac,
                    bluetoothName = it.bluetoothName.ifBlank { "Manual OBDII" },
                    transportType = TransportType.BLUETOOTH
                )
            }
            Toast.makeText(context, "Saved MAC $rawMac", Toast.LENGTH_SHORT).show()
        }

        spinnerBtStrategy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCallbacks) return
                val selected = BtStrategy.values()[position]
                if (ObdManager.config.value.btStrategy != selected) {
                    ObdManager.updateConfig { it.copy(btStrategy = selected) }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
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

    fun updateBluetoothDevices(devices: List<BtDeviceUiItem>, selectedMac: String) {
        btAdapter.submitList(devices, selectedMac)
        if (devices.isEmpty()) {
            rvBtDevices.visibility = View.GONE
            tvNoBtDevices.visibility = View.VISIBLE
        } else {
            rvBtDevices.visibility = View.VISIBLE
            tvNoBtDevices.visibility = View.GONE
        }
    }

    fun render(config: ConnectionConfig, state: ConnectionState, telemetry: ObdTelemetry) {
        currentState = state
        suppressCallbacks = true

        if (config.transportType == TransportType.WIFI) {
            rbWifi.isChecked = true
        } else {
            rbBluetooth.isChecked = true
        }
        updateTransportVisibility(config.transportType)

        if (!etManualMac.hasFocus() && etManualMac.text.toString() != config.bluetoothAddress) {
            etManualMac.setText(config.bluetoothAddress)
        }
        if (!etWifiHost.hasFocus() && etWifiHost.text.toString() != config.wifiHost) {
            etWifiHost.setText(config.wifiHost)
        }
        val portStr = config.wifiPort.toString()
        if (!etWifiPort.hasFocus() && etWifiPort.text.toString() != portStr) {
            etWifiPort.setText(portStr)
        }

        btAdapter.setSelectedMac(config.bluetoothAddress)
        spinnerBtStrategy.setSelection(config.btStrategy.ordinal)
        spinnerProtocol.setSelection(config.obdProtocol.ordinal)
        switchAutoConnect.isChecked = config.autoConnect

        suppressCallbacks = false

        tvConnectDetail.text = when (state) {
            is ConnectionState.Disconnected -> {
                val target = if (config.transportType == TransportType.WIFI) {
                    "Wi-Fi ${config.wifiHost}:${config.wifiPort}"
                } else {
                    config.bluetoothAddress.ifBlank { "No BT device selected" }
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
            layoutBtConfig.visibility = View.GONE
            layoutWifiConfig.visibility = View.VISIBLE
        } else {
            layoutBtConfig.visibility = View.VISIBLE
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

    private fun isValidMac(mac: String): Boolean {
        return Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(mac)
    }
}
