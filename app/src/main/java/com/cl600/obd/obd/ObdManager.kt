package com.cl600.obd.obd

import android.content.Context
import android.hardware.usb.UsbManager
import com.cl600.obd.model.ConnectionConfig
import com.cl600.obd.model.ConnectionState
import com.cl600.obd.model.DtcEntry
import com.cl600.obd.model.LogEntry
import com.cl600.obd.model.LogLevel
import com.cl600.obd.model.ObdTelemetry
import com.cl600.obd.model.TransportType
import com.cl600.obd.model.UnitSystem
import com.cl600.obd.prefs.AppPreferences
import com.cl600.obd.trips.TripRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Locale

object ObdManager {

    private const val MAX_LOG_ENTRIES = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()

    private var appContext: Context? = null
    private var prefs: AppPreferences? = null
    private var activeSession: ObdSession? = null
    private var connectionJob: Job? = null
    private var keepAliveJob: Job? = null

    @Volatile
    private var pendingUsbConnect: Boolean = false

    private val _config = MutableStateFlow(ConnectionConfig())
    val config: StateFlow<ConnectionConfig> = _config.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(ObdTelemetry())
    val telemetry: StateFlow<ObdTelemetry> = _telemetry.asStateFlow()

    private val _dtcList = MutableStateFlow<List<DtcEntry>>(emptyList())
    val dtcList: StateFlow<List<DtcEntry>> = _dtcList.asStateFlow()

    private val _milOn = MutableStateFlow(false)
    val milOn: StateFlow<Boolean> = _milOn.asStateFlow()

    private val _dtcStatusText = MutableStateFlow("Tap SCAN CODES while connected (or in Demo mode) to read engine fault codes.")
    val dtcStatusText: StateFlow<String> = _dtcStatusText.asStateFlow()

    private val _isDtcBusy = MutableStateFlow(false)
    val isDtcBusy: StateFlow<Boolean> = _isDtcBusy.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _verbosePollLogs = MutableStateFlow(false)
    val verbosePollLogs: StateFlow<Boolean> = _verbosePollLogs.asStateFlow()

    private var ecuBaroKpa: Float? = null

    fun init(context: Context) {
        if (prefs == null) {
            val appCtx = context.applicationContext
            appContext = appCtx
            val appPrefs = AppPreferences(appCtx)
            prefs = appPrefs
            TripRecorder.init(appCtx)
            val loaded = appPrefs.loadConfig()
            _config.value = loaded
            recomputeEffectiveBaro()
            appendLog(LogLevel.INFO, "CL600 OBD initialized (USB K-Line Galletto + Wi-Fi ELM327)")
        }
    }

    fun updateConfig(transform: (ConnectionConfig) -> ConnectionConfig) {
        val updated = transform(_config.value)
        _config.value = updated
        prefs?.saveConfig(updated)
        recomputeEffectiveBaro()
    }

    fun toggleUnitSystem(): UnitSystem {
        val next = if (_config.value.unitSystem == UnitSystem.IMPERIAL) {
            UnitSystem.METRIC
        } else {
            UnitSystem.IMPERIAL
        }
        updateConfig { it.copy(unitSystem = next) }
        return next
    }

    fun setVerbosePollLogs(enabled: Boolean) {
        _verbosePollLogs.value = enabled
        activeSession?.verbosePollLogging = enabled
    }

    fun connect(overrideTransport: TransportType? = null) {
        val cfg = _config.value.let {
            if (overrideTransport != null) it.copy(transportType = overrideTransport) else it
        }

        if (cfg.transportType == TransportType.WIFI && cfg.wifiHost.isBlank()) {
            val msg = "Wi-Fi host IP cannot be blank (ELM327 Wi-Fi adapters typically use 192.168.0.10)."
            appendLog(LogLevel.ERROR, msg)
            _connectionState.value = ConnectionState.Error(msg)
            return
        }

        pendingUsbConnect = false
        connectionJob?.cancel()
        connectionJob = scope.launch {
            cleanupSession()
            ecuBaroKpa = null
            _telemetry.value = ObdTelemetry(
                effectiveBaroKpa = _config.value.manualBaroKpa ?: ObdTelemetry.STANDARD_BARO_KPA,
                baroSource = if (_config.value.manualBaroKpa != null) "KOEO Calibrated" else "Default (101.3 kPa)"
            )

            val session: ObdSession
            val transportLabel: String

            when (cfg.transportType) {
                TransportType.USB_KLINE -> {
                    val ctx = appContext
                    val usbManager = ctx?.getSystemService(Context.USB_SERVICE) as? UsbManager
                    if (ctx == null || usbManager == null) {
                        val err = "Android UsbManager service unavailable on this device."
                        appendLog(LogLevel.ERROR, err)
                        _connectionState.value = ConnectionState.Error(err)
                        return@launch
                    }

                    val drivers = UsbSerialHelper.findSupportedDrivers(ctx)
                    val selectedDriver = drivers.firstOrNull {
                        cfg.usbDeviceName.isNotBlank() && it.device.deviceName == cfg.usbDeviceName
                    } ?: drivers.firstOrNull()

                    if (selectedDriver == null) {
                        val rawCount = usbManager.deviceList.size
                        if (rawCount > 0) {
                            usbManager.deviceList.values.forEach { dev ->
                                appendLog(
                                    LogLevel.WARN,
                                    String.format(
                                        Locale.US,
                                        "Attached USB device unsupported: %s VID:%04X PID:%04X",
                                        dev.deviceName,
                                        dev.vendorId,
                                        dev.productId
                                    )
                                )
                            }
                        }
                        val err = "No Galletto / FTDI USB cable detected. Plug into a rear 4-pin USB data pigtail (not charge-only) and turn ignition ON (Pos 2)."
                        appendLog(LogLevel.ERROR, err)
                        _connectionState.value = ConnectionState.Error(err)
                        return@launch
                    }

                    val usbDevice = selectedDriver.device
                    if (!usbManager.hasPermission(usbDevice)) {
                        pendingUsbConnect = true
                        _connectionState.value = ConnectionState.Connecting("Waiting for USB permission...")
                        appendLog(
                            LogLevel.INFO,
                            String.format(
                                Locale.US,
                                "Requesting Android USB permission for %s (VID:%04X PID:%04X)",
                                usbDevice.deviceName,
                                usbDevice.vendorId,
                                usbDevice.productId
                            )
                        )
                        UsbSerialHelper.requestPermission(ctx, usbDevice)
                        return@launch
                    }

                    val port = AndroidUsbKlinePort(usbManager, selectedDriver)
                    transportLabel = port.displayName
                    session = KlineSession(
                        port = port,
                        protocolPref = cfg.obdProtocol,
                        logger = { level, msg -> appendLog(level, msg) }
                    ).also {
                        it.verbosePollLogging = _verbosePollLogs.value
                    }
                }

                TransportType.WIFI -> {
                    val transport = WifiObdTransport(cfg.wifiHost.trim(), cfg.wifiPort, appContext)
                    transportLabel = transport.displayName
                    _connectionState.value = ConnectionState.Connecting(transportLabel)
                    try {
                        transport.connect { level, msg -> appendLog(level, msg) }
                    } catch (t: Throwable) {
                        val msg = t.message ?: t.javaClass.simpleName
                        appendLog(LogLevel.ERROR, "Connection error: $msg")
                        _connectionState.value = ConnectionState.Error(msg)
                        transport.close()
                        return@launch
                    }
                    session = Elm327Session(
                        transport = transport,
                        protocolPref = cfg.obdProtocol,
                        logger = { level, msg -> appendLog(level, msg) }
                    ).also {
                        it.verbosePollLogging = _verbosePollLogs.value
                    }
                }

                TransportType.DEMO -> {
                    val transport = SimulatedObdTransport()
                    transportLabel = transport.displayName
                    _connectionState.value = ConnectionState.Connecting(transportLabel)
                    transport.connect { level, msg -> appendLog(level, msg) }
                    session = Elm327Session(
                        transport = transport,
                        protocolPref = cfg.obdProtocol,
                        logger = { level, msg -> appendLog(level, msg) }
                    ).also {
                        it.verbosePollLogging = _verbosePollLogs.value
                    }
                }
            }

            try {
                _connectionState.value = ConnectionState.Initializing(
                    if (session.isDirectKline) "K-Line ECU handshake ($transportLabel)" else "ELM327 & ECU handshake"
                )

                val initResult = sessionMutex.withLock {
                    activeSession = session
                    session.initialize()
                }

                ecuBaroKpa = initResult.baroKpa
                val effectiveBaro = resolveEffectiveBaro()
                _milOn.value = initResult.milOn
                _telemetry.value = _telemetry.value.copy(
                    baroKpa = initResult.baroKpa,
                    milOn = initResult.milOn,
                    dtcCountReported = initResult.dtcCountReported,
                    activeProtocol = initResult.activeProtocol,
                    adapterVersion = initResult.adapterVersion,
                    supportedPids = initResult.supportedPids,
                    effectiveBaroKpa = effectiveBaro.first,
                    baroSource = effectiveBaro.second
                )

                _connectionState.value = if (cfg.transportType == TransportType.DEMO) {
                    ConnectionState.Demo
                } else {
                    ConnectionState.Connected(transportLabel, initResult.activeProtocol)
                }

                if (session.isDirectKline) {
                    startKlineKeepAlive(session)
                }

                if (cfg.transportType == TransportType.DEMO || initResult.dtcCountReported > 0) {
                    sessionMutex.withLock {
                        runDtcScanLocked(session)
                    }
                }

                runPollingLoop(session)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                appendLog(LogLevel.ERROR, "Connection error: $msg")
                _connectionState.value = ConnectionState.Error(msg)
                cleanupSession()
            }
        }
    }

    fun onUsbPermissionResult(granted: Boolean, deviceName: String?) {
        if (granted) {
            appendLog(LogLevel.INFO, "USB permission granted${deviceName?.let { " ($it)" } ?: ""}.")
            if (pendingUsbConnect) {
                pendingUsbConnect = false
                connect(TransportType.USB_KLINE)
            }
        } else {
            pendingUsbConnect = false
            val msg = "USB permission denied for Galletto cable."
            appendLog(LogLevel.ERROR, msg)
            _connectionState.value = ConnectionState.Error(msg)
        }
    }

    fun handleUsbDetached(deviceName: String?) {
        pendingUsbConnect = false
        if (TripRecorder.isRecording.value) {
            TripRecorder.stopAndSaveRecording()
            appendLog(LogLevel.INFO, "Auto-saved recorded trip on USB detach")
        }
        val session = activeSession
        if (session?.isDirectKline == true || _connectionState.value is ConnectionState.Connecting) {
            connectionJob?.cancel()
            connectionJob = null
            scope.launch {
                cleanupSession()
                _connectionState.value = ConnectionState.Disconnected
                appendLog(LogLevel.WARN, "USB K-Line cable unplugged${deviceName?.let { " ($it)" } ?: ""}")
            }
        } else {
            appendLog(LogLevel.INFO, "USB device unplugged${deviceName?.let { " ($it)" } ?: ""}")
        }
    }

    fun startDemo() {
        connect(overrideTransport = TransportType.DEMO)
    }

    fun disconnect() {
        if (TripRecorder.isRecording.value) {
            TripRecorder.stopAndSaveRecording()
            appendLog(LogLevel.INFO, "Auto-saved recorded trip")
        }
        pendingUsbConnect = false
        connectionJob?.cancel()
        connectionJob = null
        scope.launch {
            cleanupSession()
            _connectionState.value = ConnectionState.Disconnected
            appendLog(LogLevel.INFO, "Disconnected")
        }
    }

    fun scanCodes() {
        val session = activeSession
        if (session == null || !session.isConnected) {
            appendLog(LogLevel.WARN, "Cannot scan codes: not connected")
            return
        }
        scope.launch {
            _isDtcBusy.value = true
            try {
                sessionMutex.withLock {
                    runDtcScanLocked(session)
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                appendLog(LogLevel.ERROR, "Code scan failed: $msg")
                _dtcStatusText.value = "Scan failed: $msg"
            } finally {
                _isDtcBusy.value = false
            }
        }
    }

    fun clearCodes() {
        val session = activeSession
        if (session == null || !session.isConnected) {
            appendLog(LogLevel.WARN, "Cannot clear codes: not connected")
            return
        }
        scope.launch {
            _isDtcBusy.value = true
            try {
                sessionMutex.withLock {
                    val ok = session.clearDtcs()
                    if (ok) {
                        delay(400L)
                        runDtcScanLocked(session)
                        _dtcStatusText.value = "Clear command sent (Mode 04). Re-scanned ECU."
                    } else {
                        _dtcStatusText.value = "ECU did not confirm Mode 04 clear. Check Log tab."
                    }
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                appendLog(LogLevel.ERROR, "Clear codes failed: $msg")
                _dtcStatusText.value = "Clear failed: $msg"
            } finally {
                _isDtcBusy.value = false
            }
        }
    }

    fun sendRawCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        val session = activeSession
        if (session == null || !session.isConnected) {
            appendLog(LogLevel.WARN, "Cannot send '$trimmed': not connected to Galletto cable, Wi-Fi adapter, or Demo.")
            return
        }
        scope.launch {
            try {
                sessionMutex.withLock {
                    session.sendRawCommand(trimmed)
                }
            } catch (t: Throwable) {
                appendLog(LogLevel.ERROR, "Command '$trimmed' failed: ${t.message}")
            }
        }
    }

    fun resetPeaks() {
        val cur = _telemetry.value
        _telemetry.value = cur.copy(
            peakBoostPsi = cur.boostPsi,
            peakIatCelsius = cur.iatCelsius,
            peakCoolantCelsius = cur.coolantCelsius,
            minVoltage = cur.voltage,
            peakRpm = cur.rpm,
            peakHp = cur.estimatedHp,
            peakTorqueLbFt = cur.estimatedTorqueLbFt
        )
        appendLog(LogLevel.INFO, "All peak/min values reset")
    }

    fun resetBoostPeak() {
        val cur = _telemetry.value
        _telemetry.value = cur.copy(peakBoostPsi = cur.boostPsi)
        appendLog(LogLevel.INFO, "Reset Boost peak")
    }

    fun resetIatPeak() {
        val cur = _telemetry.value
        _telemetry.value = cur.copy(peakIatCelsius = cur.iatCelsius)
        appendLog(LogLevel.INFO, "Reset IAT peak")
    }

    fun resetCoolantPeak() {
        val cur = _telemetry.value
        _telemetry.value = cur.copy(peakCoolantCelsius = cur.coolantCelsius)
        appendLog(LogLevel.INFO, "Reset Coolant peak")
    }

    fun resetVoltageMin() {
        val cur = _telemetry.value
        _telemetry.value = cur.copy(minVoltage = cur.voltage)
        appendLog(LogLevel.INFO, "Reset Voltage min")
    }

    fun resetRpmPeak() {
        val cur = _telemetry.value
        _telemetry.value = cur.copy(peakRpm = cur.rpm)
        appendLog(LogLevel.INFO, "Reset RPM peak")
    }

    fun resetPowerPeak() {
        val cur = _telemetry.value
        _telemetry.value = cur.copy(
            peakHp = cur.estimatedHp,
            peakTorqueLbFt = cur.estimatedTorqueLbFt
        )
        appendLog(LogLevel.INFO, "Reset Power peak")
    }

    fun resetTorquePeak() {
        val cur = _telemetry.value
        _telemetry.value = cur.copy(peakTorqueLbFt = cur.estimatedTorqueLbFt)
        appendLog(LogLevel.INFO, "Reset Torque peak")
    }

    fun calibrateBaroFromCurrentMap(): Boolean {
        val currentMap = _telemetry.value.mapKpa ?: return false
        updateConfig { it.copy(manualBaroKpa = currentMap) }
        appendLog(LogLevel.INFO, "Calibrated ambient Baro from KOEO MAP: %.1f kPa".format(currentMap))
        return true
    }

    fun resetBaroToStandard() {
        updateConfig { it.copy(manualBaroKpa = null) }
        appendLog(LogLevel.INFO, "Cleared manual Baro calibration (using ECU 0133 or 101.3 kPa)")
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun appendLog(level: LogLevel, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        synchronized(_logs) {
            val current = _logs.value
            val next = if (current.size >= MAX_LOG_ENTRIES) {
                current.drop(current.size - MAX_LOG_ENTRIES + 1) + entry
            } else {
                current + entry
            }
            _logs.value = next
        }
    }

    private fun startKlineKeepAlive(session: ObdSession) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && session.isConnected) {
                delay(500L)
                try {
                    sessionMutex.withLock {
                        if (session.isConnected) {
                            session.keepAliveIfIdle()
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val msg = t.message ?: t.javaClass.simpleName
                    appendLog(LogLevel.ERROR, "K-Line keep-alive error: $msg")
                    _connectionState.value = ConnectionState.Error(msg)
                    cleanupSession()
                    break
                }
            }
        }
    }

    private suspend fun runDtcScanLocked(session: ObdSession) {
        val (mil, reportedCount, dtcs) = session.scanDtcs()
        _milOn.value = mil
        _dtcList.value = dtcs
        _telemetry.value = _telemetry.value.copy(milOn = mil, dtcCountReported = reportedCount)
        _dtcStatusText.value = if (dtcs.isEmpty()) {
            "No stored or pending engine fault codes detected."
        } else {
            "${dtcs.size} engine fault code(s) found."
        }
    }

    private suspend fun runPollingLoop(session: ObdSession) {
        var cycle = 0L
        var hzWindowStart = System.currentTimeMillis()
        var hzCount = 0

        while (scope.isActive && session.isConnected) {
            val snap = sessionMutex.withLock {
                session.pollCycle(cycle)
            }

            if (snap.baroKpa != null) {
                ecuBaroKpa = snap.baroKpa
            }
            val (effectiveBaro, baroLabel) = resolveEffectiveBaro()

            val cur = _telemetry.value
            val map = snap.mapKpa ?: cur.mapKpa
            val boost = map?.let { ObdParser.calculateBoostPsi(it, effectiveBaro) }
            val iat = snap.iatCelsius ?: cur.iatCelsius
            val coolant = snap.coolantCelsius ?: cur.coolantCelsius
            val volts = snap.voltage ?: cur.voltage
            val rpm = snap.rpm ?: cur.rpm

            val powerTorque = if (map != null && iat != null && rpm != null) {
                ObdParser.estimatePowerAndTorque(map, iat, rpm)
            } else {
                null
            }
            val estHp = powerTorque?.first ?: cur.estimatedHp
            val estTq = powerTorque?.second ?: cur.estimatedTorqueLbFt

            hzCount++
            val now = System.currentTimeMillis()
            val elapsed = now - hzWindowStart
            val measuredHz = if (elapsed >= 1500L) {
                val hz = (hzCount * 1000f) / elapsed.toFloat()
                hzWindowStart = now
                hzCount = 0
                hz
            } else {
                cur.pollingHz
            }

            val updatedMil = snap.milOn ?: cur.milOn
            if (snap.milOn != null) _milOn.value = snap.milOn

            _telemetry.value = cur.copy(
                mapKpa = map,
                baroKpa = snap.baroKpa ?: cur.baroKpa,
                boostPsi = boost,
                iatCelsius = iat,
                coolantCelsius = coolant,
                voltage = volts,
                rpm = rpm,
                loadPercent = snap.loadPercent ?: cur.loadPercent,
                timingAdvanceDeg = snap.timingAdvanceDeg ?: cur.timingAdvanceDeg,
                throttlePercent = snap.throttlePercent ?: cur.throttlePercent,
                speedKmh = snap.speedKmh ?: cur.speedKmh,
                estimatedHp = estHp,
                estimatedTorqueLbFt = estTq,
                peakBoostPsi = maxNullable(cur.peakBoostPsi, boost),
                peakIatCelsius = maxNullable(cur.peakIatCelsius, iat),
                peakCoolantCelsius = maxNullable(cur.peakCoolantCelsius, coolant),
                minVoltage = minNullable(cur.minVoltage, volts),
                peakRpm = maxNullableInt(cur.peakRpm, rpm),
                peakHp = maxNullable(cur.peakHp, estHp),
                peakTorqueLbFt = maxNullable(cur.peakTorqueLbFt, estTq),
                milOn = updatedMil,
                dtcCountReported = snap.dtcCountReported ?: cur.dtcCountReported,
                lastUpdateMs = now,
                pollingHz = measuredHz,
                effectiveBaroKpa = effectiveBaro,
                baroSource = baroLabel
            )

            TripRecorder.onTelemetryUpdate(_telemetry.value)

            cycle++
            delay(25L)
        }

        if (_connectionState.value.isStreaming()) {
            throw IOException("OBD connection stream closed")
        }
    }

    private fun resolveEffectiveBaro(): Pair<Float, String> {
        val manual = _config.value.manualBaroKpa
        if (manual != null) {
            return Pair(manual, "KOEO Calibrated (%.1f kPa)".format(manual))
        }
        val ecu = ecuBaroKpa
        if (ecu != null) {
            return Pair(ecu, "ECU 0133 (%.0f kPa)".format(ecu))
        }
        return Pair(ObdTelemetry.STANDARD_BARO_KPA, "Standard (101.3 kPa)")
    }

    private fun recomputeEffectiveBaro() {
        val (baro, label) = resolveEffectiveBaro()
        val cur = _telemetry.value
        val boost = cur.mapKpa?.let { ObdParser.calculateBoostPsi(it, baro) }
        _telemetry.value = cur.copy(
            effectiveBaroKpa = baro,
            baroSource = label,
            boostPsi = boost
        )
    }

    private suspend fun cleanupSession() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        sessionMutex.withLock {
            activeSession?.close()
            activeSession = null
        }
    }

    private fun maxNullable(a: Float?, b: Float?): Float? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun minNullable(a: Float?, b: Float?): Float? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    private fun maxNullableInt(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
}
