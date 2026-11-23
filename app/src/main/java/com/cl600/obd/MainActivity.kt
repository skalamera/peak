package com.cl600.obd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.cl600.obd.model.ConnectionState
import com.cl600.obd.model.LogLevel
import com.cl600.obd.model.TransportType
import com.cl600.obd.model.UnitSystem
import com.cl600.obd.music.MusicSidebarController
import com.cl600.obd.obd.ObdManager
import com.cl600.obd.obd.UsbSerialHelper
import com.cl600.obd.trips.TripRecorder
import com.cl600.obd.ui.CodesViewBinder
import com.cl600.obd.ui.ConnectViewBinder
import com.cl600.obd.ui.GaugesViewBinder
import com.cl600.obd.ui.LogViewBinder
import com.cl600.obd.ui.TripsViewBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Tab(val index: Int) {
        GAUGES(0),
        TRIPS(1),
        CODES(2),
        CONNECT(3),
        LOG(4)
    }

    private companion object {
        const val KEY_ACTIVE_TAB = "active_tab"
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var viewStatusDot: View
    private lateinit var tvStatusBadge: TextView
    private lateinit var btnTabGauges: TextView
    private lateinit var btnTabTrips: TextView
    private lateinit var btnTabCodes: TextView
    private lateinit var btnTabConnect: TextView
    private lateinit var btnTabLog: TextView
    private lateinit var btnToggleUnits: TextView
    private lateinit var btnResetPeaks: TextView
    private lateinit var contentContainer: FrameLayout

    private lateinit var viewGauges: View
    private lateinit var viewTrips: View
    private lateinit var viewCodes: View
    private lateinit var viewConnect: View
    private lateinit var viewLog: View

    private lateinit var gaugesBinder: GaugesViewBinder
    private lateinit var tripsBinder: TripsViewBinder
    private lateinit var codesBinder: CodesViewBinder
    private lateinit var connectBinder: ConnectViewBinder
    private lateinit var logBinder: LogViewBinder
    private lateinit var musicController: MusicSidebarController

    private var activeTab = Tab.GAUGES
    private var usbReceiversRegistered = false

    private val tripBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (::musicController.isInitialized && musicController.isExpanded()) {
                musicController.collapseDrawer()
                return
            }
            if (!tripsBinder.handleBackPressed()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbSerialHelper.ACTION_USB_PERMISSION) return
            val device = extractUsbDevice(intent)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            refreshUsbDeviceList(logDevices = false)
            ObdManager.onUsbPermissionResult(granted, device?.deviceName)
        }
    }

    private val usbAttachDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleUsbAttachedIntent(intent)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = extractUsbDevice(intent)
                    refreshUsbDeviceList(logDevices = false)
                    ObdManager.handleUsbDetached(device?.deviceName)
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
        btnTabTrips = findViewById(R.id.btnTabTrips)
        btnTabCodes = findViewById(R.id.btnTabCodes)
        btnTabConnect = findViewById(R.id.btnTabConnect)
        btnTabLog = findViewById(R.id.btnTabLog)
        btnToggleUnits = findViewById(R.id.btnToggleUnits)
        btnResetPeaks = findViewById(R.id.btnResetPeaks)
        contentContainer = findViewById(R.id.contentContainer)

        val inflater = LayoutInflater.from(this)
        viewGauges = inflater.inflate(R.layout.view_gauges, contentContainer, false)
        viewTrips = inflater.inflate(R.layout.view_trips, contentContainer, false)
        viewCodes = inflater.inflate(R.layout.view_codes, contentContainer, false)
        viewConnect = inflater.inflate(R.layout.view_connect, contentContainer, false)
        viewLog = inflater.inflate(R.layout.view_log, contentContainer, false)

        contentContainer.addView(viewGauges)
        contentContainer.addView(viewTrips)
        contentContainer.addView(viewCodes)
        contentContainer.addView(viewConnect)
        contentContainer.addView(viewLog)

        gaugesBinder = GaugesViewBinder(viewGauges)
        tripsBinder = TripsViewBinder(
            root = viewTrips,
            context = this,
            uiScope = uiScope,
            onDetailVisibilityChanged = { updateTripBackCallbackState() }
        )
        codesBinder = CodesViewBinder(viewCodes, this)
        connectBinder = ConnectViewBinder(
            root = viewConnect,
            context = this,
            onRequestUsbScan = { refreshUsbDeviceList(logDevices = true) },
            onRequestConnect = { transport -> ObdManager.connect(transport) }
        )
        logBinder = LogViewBinder(viewLog, this)
        musicController = MusicSidebarController(
            activity = this,
            rootView = findViewById(R.id.mainWorkspaceFrame),
            onDrawerStateChanged = { updateTripBackCallbackState() }
        )

        onBackPressedDispatcher.addCallback(this, tripBackCallback)

        btnTabGauges.setOnClickListener { selectTab(Tab.GAUGES) }
        btnTabTrips.setOnClickListener { selectTab(Tab.TRIPS) }
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

        registerUsbReceivers()
        refreshUsbDeviceList(logDevices = true)
        observeManagerFlows()

        handleUsbAttachedIntent(intent)
        if (savedInstanceState == null && ObdManager.config.value.autoConnect && !ObdManager.connectionState.value.isActiveOrConnecting()) {
            ObdManager.connect(ObdManager.config.value.transportType)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUsbDeviceList(logDevices = false)
        if (::musicController.isInitialized) {
            musicController.onResume()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttachedIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_TAB, activeTab.index)
    }

    override fun onDestroy() {
        if (::musicController.isInitialized) {
            musicController.destroy()
        }
        uiScope.cancel()
        if (usbReceiversRegistered) {
            try {
                unregisterReceiver(usbPermissionReceiver)
            } catch (_: Exception) {
            }
            try {
                unregisterReceiver(usbAttachDetachReceiver)
            } catch (_: Exception) {
            }
            usbReceiversRegistered = false
        }
        super.onDestroy()
    }

    private fun selectTab(tab: Tab) {
        activeTab = tab
        viewGauges.visibility = if (tab == Tab.GAUGES) View.VISIBLE else View.GONE
        viewTrips.visibility = if (tab == Tab.TRIPS) View.VISIBLE else View.GONE
        viewCodes.visibility = if (tab == Tab.CODES) View.VISIBLE else View.GONE
        viewConnect.visibility = if (tab == Tab.CONNECT) View.VISIBLE else View.GONE
        viewLog.visibility = if (tab == Tab.LOG) View.VISIBLE else View.GONE

        btnTabGauges.isSelected = tab == Tab.GAUGES
        btnTabTrips.isSelected = tab == Tab.TRIPS
        btnTabCodes.isSelected = tab == Tab.CODES
        btnTabConnect.isSelected = tab == Tab.CONNECT
        btnTabLog.isSelected = tab == Tab.LOG
        updateTripBackCallbackState()
    }

    private fun updateTripBackCallbackState() {
        val drawerOpen = ::musicController.isInitialized && musicController.isExpanded()
        tripBackCallback.isEnabled =
            drawerOpen || (activeTab == Tab.TRIPS && tripsBinder.isInDetailView())
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
                TripRecorder.isRecording,
                TripRecorder.liveSamples,
                TripRecorder.liveSummary,
                TripRecorder.savedTrips,
                ObdManager.config,
                ObdManager.connectionState
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val isRec = values[0] as Boolean
                @Suppress("UNCHECKED_CAST")
                val samples = values[1] as List<com.cl600.obd.model.TripSample>
                val liveSummary = values[2] as com.cl600.obd.model.TripSummary?
                @Suppress("UNCHECKED_CAST")
                val saved = values[3] as List<com.cl600.obd.model.TripSummary>
                val config = values[4] as com.cl600.obd.model.ConnectionConfig
                val connState = values[5] as ConnectionState

                tripsBinder.render(isRec, samples, liveSummary, saved, config.unitSystem, connState)
                if (isRec) {
                    btnTabTrips.text = "● TRIPS"
                    btnTabTrips.setTextColor(Color.parseColor("#EF4444"))
                } else {
                    btnTabTrips.text = getString(R.string.tab_trips)
                    btnTabTrips.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                }
            }.collect {}
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
            combine(
                ObdManager.logs,
                ObdManager.verbosePollLogs,
                ObdManager.config,
                ObdManager.connectionState
            ) { logs, verbose, config, state ->
                val effectiveTransport = if (state is ConnectionState.Demo) {
                    TransportType.DEMO
                } else {
                    config.transportType
                }
                Triple(logs, verbose, effectiveTransport)
            }.collect { (logs, verbose, transport) ->
                logBinder.render(logs, verbose, transport)
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

    private fun refreshUsbDeviceList(logDevices: Boolean) {
        val (serialItems, rawDevices) = UsbSerialHelper.listUsbDevices(this)
        val cfg = ObdManager.config.value

        if (serialItems.isNotEmpty() &&
            (cfg.usbDeviceName.isBlank() || serialItems.none { it.deviceName == cfg.usbDeviceName })
        ) {
            val preferred = serialItems.firstOrNull { it.isFtdi } ?: serialItems.first()
            ObdManager.updateConfig { it.copy(usbDeviceName = preferred.deviceName) }
        }

        connectBinder.updateUsbDevices(serialItems, ObdManager.config.value.usbDeviceName)

        if (logDevices) {
            if (serialItems.isNotEmpty()) {
                serialItems.forEach { item ->
                    ObdManager.appendLog(
                        LogLevel.INFO,
                        "USB K-Line candidate: ${item.displayTitle} (${item.vidPidLabel}, ${item.deviceName}, perm=${item.hasPermission})"
                    )
                }
            } else if (rawDevices.isNotEmpty()) {
                rawDevices.forEach { dev ->
                    ObdManager.appendLog(
                        LogLevel.WARN,
                        String.format(
                            Locale.US,
                            "USB device (no serial driver): %s VID:%04X PID:%04X",
                            dev.deviceName,
                            dev.vendorId,
                            dev.productId
                        )
                    )
                }
            } else {
                ObdManager.appendLog(LogLevel.INFO, "USB scan: no USB OTG serial cable currently attached")
            }
        }
    }

    private fun handleUsbAttachedIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = extractUsbDevice(intent)
        refreshUsbDeviceList(logDevices = false)
        if (device != null) {
            ObdManager.appendLog(
                LogLevel.INFO,
                String.format(
                    Locale.US,
                    "USB cable attached: %s (VID:%04X PID:%04X)",
                    device.deviceName,
                    device.vendorId,
                    device.productId
                )
            )
        }
        val cfg = ObdManager.config.value
        if (cfg.autoConnect &&
            cfg.transportType == TransportType.USB_KLINE &&
            !ObdManager.connectionState.value.isActiveOrConnecting()
        ) {
            ObdManager.connect(TransportType.USB_KLINE)
        }
    }

    private fun registerUsbReceivers() {
        if (usbReceiversRegistered) return
        val permFilter = IntentFilter(UsbSerialHelper.ACTION_USB_PERMISSION)
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachDetachReceiver, usbFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, permFilter)
            registerReceiver(usbAttachDetachReceiver, usbFilter)
        }
        usbReceiversRegistered = true
    }

    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
