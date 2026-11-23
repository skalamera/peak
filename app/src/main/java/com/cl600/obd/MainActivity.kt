package com.cl600.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cl600.obd.model.ConnectionState
import com.cl600.obd.model.LogLevel
import com.cl600.obd.model.TransportType
import com.cl600.obd.model.UnitSystem
import com.cl600.obd.obd.ObdManager
import com.cl600.obd.ui.BtDeviceUiItem
import com.cl600.obd.ui.CodesViewBinder
import com.cl600.obd.ui.ConnectViewBinder
import com.cl600.obd.ui.GaugesViewBinder
import com.cl600.obd.ui.LogViewBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private enum class Tab(val index: Int) {
        GAUGES(0),
        CODES(1),
        CONNECT(2),
        LOG(3)
    }

    private companion object {
        const val KEY_ACTIVE_TAB = "active_tab"
        const val REQ_BT_CONNECT = 1001
        const val REQ_BT_SCAN = 1002
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var viewStatusDot: View
    private lateinit var tvStatusBadge: TextView
    private lateinit var btnTabGauges: TextView
    private lateinit var btnTabCodes: TextView
    private lateinit var btnTabConnect: TextView
    private lateinit var btnTabLog: TextView
    private lateinit var btnToggleUnits: TextView
    private lateinit var btnResetPeaks: TextView
    private lateinit var contentContainer: FrameLayout

    private lateinit var viewGauges: View
    private lateinit var viewCodes: View
    private lateinit var viewConnect: View
    private lateinit var viewLog: View

    private lateinit var gaugesBinder: GaugesViewBinder
    private lateinit var codesBinder: CodesViewBinder
    private lateinit var connectBinder: ConnectViewBinder
    private lateinit var logBinder: LogViewBinder

    private var activeTab = Tab.GAUGES
    private var pendingActionAfterPermission: (() -> Unit)? = null
    private val discoveredDevices = LinkedHashMap<String, BtDeviceUiItem>()
    private var btReceiverRegistered = false

    private val btDiscoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        val addr = device.address ?: return
                        val name = safeDeviceName(device)
                        discoveredDevices[addr.uppercase()] = BtDeviceUiItem(
                            name = name,
                            address = addr,
                            badge = "FOUND",
                            isObdCandidate = isObdName(name)
                        )
                        refreshBluetoothDeviceList()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    ObdManager.appendLog(LogLevel.INFO, "Bluetooth nearby scan finished")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        ObdManager.init(applicationContext)

        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        btnTabGauges = findViewById(R.id.btnTabGauges)
        btnTabCodes = findViewById(R.id.btnTabCodes)
        btnTabConnect = findViewById(R.id.btnTabConnect)
        btnTabLog = findViewById(R.id.btnTabLog)
        btnToggleUnits = findViewById(R.id.btnToggleUnits)
        btnResetPeaks = findViewById(R.id.btnResetPeaks)
        contentContainer = findViewById(R.id.contentContainer)

        val inflater = LayoutInflater.from(this)
        viewGauges = inflater.inflate(R.layout.view_gauges, contentContainer, false)
        viewCodes = inflater.inflate(R.layout.view_codes, contentContainer, false)
        viewConnect = inflater.inflate(R.layout.view_connect, contentContainer, false)
        viewLog = inflater.inflate(R.layout.view_log, contentContainer, false)

        contentContainer.addView(viewGauges)
        contentContainer.addView(viewCodes)
        contentContainer.addView(viewConnect)
        contentContainer.addView(viewLog)

        gaugesBinder = GaugesViewBinder(viewGauges)
        codesBinder = CodesViewBinder(viewCodes, this)
        connectBinder = ConnectViewBinder(
            root = viewConnect,
            context = this,
            onRequestBtRefresh = { ensureBtConnectPermission { refreshBluetoothDeviceList(autoSelectObd = false) } },
            onRequestBtScan = { startBluetoothDiscovery() },
            onRequestConnect = { transport -> handleConnectRequest(transport) }
        )
        logBinder = LogViewBinder(viewLog, this)

        btnTabGauges.setOnClickListener { selectTab(Tab.GAUGES) }
        btnTabCodes.setOnClickListener { selectTab(Tab.CODES) }
        btnTabConnect.setOnClickListener { selectTab(Tab.CONNECT) }
        btnTabLog.setOnClickListener { selectTab(Tab.LOG) }

        btnToggleUnits.setOnClickListener {
            val next = ObdManager.toggleUnitSystem()
            Toast.makeText(this, "Units: ${next.shortLabel}", Toast.LENGTH_SHORT).show()
        }

        btnResetPeaks.setOnClickListener {
            ObdManager.resetPeaks()
            Toast.makeText(this, "Peaks reset", Toast.LENGTH_SHORT).show()
        }

        val savedIdx = savedInstanceState?.getInt(KEY_ACTIVE_TAB, Tab.GAUGES.index) ?: Tab.GAUGES.index
        selectTab(Tab.values().getOrElse(savedIdx) { Tab.GAUGES })

        registerDiscoveryReceiver()
        if (hasBtConnectPermission()) {
            refreshBluetoothDeviceList(autoSelectObd = true)
        }
        observeManagerFlows()

        if (savedInstanceState == null && ObdManager.config.value.autoConnect && !ObdManager.connectionState.value.isActiveOrConnecting()) {
            handleConnectRequest(ObdManager.config.value.transportType)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_TAB, activeTab.index)
    }

    override fun onDestroy() {
        uiScope.cancel()
        if (btReceiverRegistered) {
            try {
                unregisterReceiver(btDiscoveryReceiver)
            } catch (_: Exception) {
            }
            btReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun selectTab(tab: Tab) {
        activeTab = tab
        viewGauges.visibility = if (tab == Tab.GAUGES) View.VISIBLE else View.GONE
        viewCodes.visibility = if (tab == Tab.CODES) View.VISIBLE else View.GONE
        viewConnect.visibility = if (tab == Tab.CONNECT) View.VISIBLE else View.GONE
        viewLog.visibility = if (tab == Tab.LOG) View.VISIBLE else View.GONE

        btnTabGauges.isSelected = tab == Tab.GAUGES
        btnTabCodes.isSelected = tab == Tab.CODES
        btnTabConnect.isSelected = tab == Tab.CONNECT
        btnTabLog.isSelected = tab == Tab.LOG
    }

    private fun observeManagerFlows() {
        uiScope.launch {
            combine(
                ObdManager.telemetry,
                ObdManager.config,
                ObdManager.connectionState
            ) { telemetry, config, state ->
                Triple(telemetry, config, state)
            }.collect { (telemetry, config, state) ->
                gaugesBinder.render(telemetry, config.unitSystem, state)
                connectBinder.render(config, state, telemetry)
                btnToggleUnits.text = if (config.unitSystem == UnitSystem.IMPERIAL) "PSI/°F" else "BAR/°C"
                updateTopStatus(state)
            }
        }

        uiScope.launch {
            combine(
                ObdManager.dtcList,
                ObdManager.milOn,
                ObdManager.dtcStatusText,
                ObdManager.isDtcBusy,
                ObdManager.connectionState
            ) { dtcs, milOn, statusText, isBusy, state ->
                arrayOf(dtcs, milOn, statusText, isBusy, state)
            }.collect { values ->
                @Suppress("UNCHECKED_CAST")
                val dtcs = values[0] as List<com.cl600.obd.model.DtcEntry>
                val milOn = values[1] as Boolean
                val statusText = values[2] as String
                val isBusy = values[3] as Boolean
                val state = values[4] as ConnectionState

                codesBinder.render(dtcs, milOn, statusText, isBusy, state)
                btnTabCodes.text = if (dtcs.isNotEmpty()) "CODES (${dtcs.size})" else getString(R.string.tab_codes)
            }
        }

        uiScope.launch {
            combine(ObdManager.logs, ObdManager.verbosePollLogs) { logs, verbose ->
                Pair(logs, verbose)
            }.collect { (logs, verbose) ->
                logBinder.render(logs, verbose)
            }
        }
    }

    private fun updateTopStatus(state: ConnectionState) {
        val (text, colorRes) = when (state) {
            is ConnectionState.Connected -> Pair("CONNECTED", R.color.status_connected)
            is ConnectionState.Demo -> Pair(getString(R.string.demo_mode), R.color.status_demo)
            is ConnectionState.Connecting -> Pair("CONNECTING...", R.color.status_connecting)
            is ConnectionState.Initializing -> Pair("INIT ECU...", R.color.status_connecting)
            is ConnectionState.Error -> Pair("ERROR", R.color.status_error)
            is ConnectionState.Disconnected -> Pair(getString(R.string.disconnected), R.color.status_disconnected)
        }
        tvStatusBadge.text = text
        val color = ContextCompat.getColor(this, colorRes)
        (viewStatusDot.background as? GradientDrawable)?.setColor(color)
        tvStatusBadge.setTextColor(color)
    }

    private fun handleConnectRequest(transport: TransportType) {
        if (transport == TransportType.BLUETOOTH) {
            ensureBtConnectPermission {
                refreshBluetoothDeviceList(autoSelectObd = true)
                ObdManager.connect(TransportType.BLUETOOTH)
            }
        } else {
            ObdManager.connect(transport)
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBluetoothDeviceList(autoSelectObd: Boolean = false) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            connectBinder.updateBluetoothDevices(emptyList(), ObdManager.config.value.bluetoothAddress)
            return
        }

        if (!hasBtConnectPermission()) {
            return
        }

        val combined = LinkedHashMap<String, BtDeviceUiItem>()
        try {
            val bonded = adapter.bondedDevices ?: emptySet()
            for (dev in bonded) {
                val addr = dev.address ?: continue
                val name = safeDeviceName(dev)
                combined[addr.uppercase()] = BtDeviceUiItem(
                    name = name,
                    address = addr,
                    badge = "PAIRED",
                    isObdCandidate = isObdName(name)
                )
            }
        } catch (se: SecurityException) {
            ObdManager.appendLog(LogLevel.WARN, "Cannot read paired devices: ${se.message}")
        }

        for ((mac, item) in discoveredDevices) {
            if (!combined.containsKey(mac)) {
                combined[mac] = item
            }
        }

        val sorted = combined.values.sortedWith(
            compareByDescending<BtDeviceUiItem> { it.isObdCandidate }
                .thenBy { it.name.lowercase() }
        )

        if (autoSelectObd && ObdManager.config.value.bluetoothAddress.isBlank()) {
            val bestObd = sorted.firstOrNull { it.isObdCandidate }
            if (bestObd != null) {
                ObdManager.updateConfig {
                    it.copy(bluetoothAddress = bestObd.address, bluetoothName = bestObd.name)
                }
                ObdManager.appendLog(LogLevel.INFO, "Auto-selected paired adapter: ${bestObd.name} (${bestObd.address})")
            }
        }

        connectBinder.updateBluetoothDevices(sorted, ObdManager.config.value.bluetoothAddress)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothDiscovery() {
        ensureBtScanPermission {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                Toast.makeText(this, "No Bluetooth adapter found", Toast.LENGTH_SHORT).show()
                return@ensureBtScanPermission
            }
            if (!adapter.isEnabled) {
                Toast.makeText(this, "Turn Bluetooth ON in head unit settings first", Toast.LENGTH_SHORT).show()
                return@ensureBtScanPermission
            }
            try {
                if (adapter.isDiscovering) adapter.cancelDiscovery()
                val started = adapter.startDiscovery()
                ObdManager.appendLog(
                    if (started) LogLevel.INFO else LogLevel.WARN,
                    if (started) "Scanning for nearby Bluetooth devices..." else "Bluetooth scan failed to start"
                )
                Toast.makeText(
                    this,
                    if (started) "Scanning nearby Bluetooth devices..." else "Scan could not start",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (se: SecurityException) {
                ObdManager.appendLog(LogLevel.ERROR, "Bluetooth scan permission denied: ${se.message}")
            }
        }
    }

    private fun registerDiscoveryReceiver() {
        if (btReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btDiscoveryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(btDiscoveryReceiver, filter)
        }
        btReceiverRegistered = true
    }

    private fun hasBtConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun ensureBtConnectPermission(onGranted: () -> Unit) {
        if (hasBtConnectPermission()) {
            onGranted()
            return
        }
        pendingActionAfterPermission = onGranted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                REQ_BT_CONNECT
            )
        }
    }

    private fun ensureBtScanPermission(onGranted: () -> Unit) {
        val missing = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (missing.isEmpty()) {
            onGranted()
        } else {
            pendingActionAfterPermission = onGranted
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_BT_SCAN)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_CONNECT || requestCode == REQ_BT_SCAN) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            val action = pendingActionAfterPermission
            pendingActionAfterPermission = null
            if (allGranted) {
                action?.invoke()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth permission required to access paired/nearby OBDII adapters",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        return try {
            device.name ?: "Unnamed Device"
        } catch (_: SecurityException) {
            "Bluetooth Device"
        }
    }

    private fun isObdName(name: String): Boolean {
        val u = name.uppercase()
        return u.contains("OBD") || u.contains("ELM") || u.contains("VLINK") ||
            u.contains("VGATE") || u.contains("CAR") || u.contains("SCAN") || u.contains("KONNWEI")
    }
}
