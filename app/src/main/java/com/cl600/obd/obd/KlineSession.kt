package com.cl600.obd.obd

import com.cl600.obd.model.DtcCategory
import com.cl600.obd.model.DtcEntry
import com.cl600.obd.model.LogLevel
import com.cl600.obd.model.ObdProtocol
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.util.Locale

interface KlineClock {
    fun nowMs(): Long
    fun nanoTime(): Long
    fun sleepMs(ms: Long)
    fun sleepUntilNanos(targetNanos: Long)
}

object SystemKlineClock : KlineClock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun nanoTime(): Long = System.nanoTime()

    override fun sleepMs(ms: Long) {
        if (ms > 0L) Thread.sleep(ms)
    }

    override fun sleepUntilNanos(targetNanos: Long) {
        val remainingNs = targetNanos - System.nanoTime()
        if (remainingNs > 2_000_000L) {
            Thread.sleep((remainingNs - 1_500_000L) / 1_000_000L)
        }
        while (System.nanoTime() < targetNanos) {
            // Precision spin tail (<1.5ms) for 5-baud bits and 25ms KWP fast-init pulses
        }
    }
}

class KlineSession(
    private val port: KlineSerialPort,
    private val protocolPref: ObdProtocol,
    private val logger: (LogLevel, String) -> Unit,
    private val clock: KlineClock = SystemKlineClock
) : ObdSession {

    companion object {
        private const val KLINE_BAUD = 10400
        private const val P3_MIN_MS = 58L
        private const val P4_ISO9141_INTERBYTE_MS = 5L
        private const val KEEPALIVE_IDLE_MS = 2000L
        private const val MAX_CONSECUTIVE_TIMEOUTS = 6
    }

    override val isDirectKline: Boolean = true
    override var verbosePollLogging: Boolean = false

    @Volatile
    private var connected = false

    override val isConnected: Boolean
        get() = connected && port.isOpen

    private val rxOverflow = ArrayDeque<Byte>()
    private val usbReadChunk = ByteArray(256)

    private var framing: KlineFraming = KlineFraming.ISO_9141_2
    private var activeProtocolLabel: String = KlineFraming.ISO_9141_2.displayName
    private var supportedPids: Set<String> = emptySet()
    private var supportsPid42Voltage: Boolean = false
    private var loggedFirstPollCycle: Boolean = false
    private var lastRxEndMs: Long = 0L
    private var lastBusActivityMs: Long = 0L
    private var consecutiveTimeouts: Int = 0

    override suspend fun initialize(): ObdInitResult {
        logger(LogLevel.INFO, "Opening USB K-Line port ${port.displayName} at $KLINE_BAUD baud (8N1)...")
        port.open()
        port.setParameters(KLINE_BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        val lowLatency = port.setLatencyTimer(1)
        logger(
            LogLevel.INFO,
            if (lowLatency) "FTDI latency timer set to 1 ms" else "USB serial port configured (default latency timer)"
        )
        port.setDTR(false)
        setLinesLow(false)

        val initKeywords = performWakeupHandshake()

        // Query PID 01 00; if auto mode picked wrong framing vs ECU keywords, retry alternate framing
        var pids00 = querySupportedPids("00", 0)
        if (pids00.isEmpty() && protocolPref == ObdProtocol.AUTO_WITH_KLINE_FALLBACK) {
            val altFraming = if (framing == KlineFraming.KWP_2000) KlineFraming.ISO_9141_2 else KlineFraming.KWP_2000
            logger(LogLevel.WARN, "0100 gave no response under ${framing.displayName}; retrying ${altFraming.displayName}...")
            framing = altFraming
            pids00 = querySupportedPids("00", 0)
        }

        if (pids00.isEmpty()) {
            throw IOException(
                "ECU woke up ($initKeywords), but did not answer OBD Mode 0100. " +
                    "Verify ignition is at Position 2 and try selecting ISO 9141-2 or KWP2000 manually."
            )
        }

        val allSupported = LinkedHashSet<String>()
        allSupported.addAll(pids00)
        if ("20" in allSupported) {
            allSupported.addAll(querySupportedPids("20", 0x20))
        }
        if ("40" in allSupported) {
            allSupported.addAll(querySupportedPids("40", 0x40))
        }

        supportedPids = allSupported
        supportsPid42Voltage = "42" in allSupported
        logger(LogLevel.INFO, "Supported K-Line PIDs (${allSupported.size}): ${allSupported.joinToString(",")}")

        var baroKpa: Float? = null
        if ("33" in allSupported) {
            baroKpa = decodeFramesAsObdText(sendMode01(0x33, logTxRx = true))?.let {
                ObdParser.parseBaroKpa(it)
            }
        }

        var milOn = false
        var dtcCount = 0
        if ("01" in allSupported) {
            val milText = decodeFramesAsObdText(sendMode01(0x01, logTxRx = true))
            val parsedMil = milText?.let { ObdParser.parseMilStatus(it) }
            if (parsedMil != null) {
                milOn = parsedMil.first
                dtcCount = parsedMil.second
            }
        }

        activeProtocolLabel = "${framing.displayName} [$initKeywords]"
        connected = true
        consecutiveTimeouts = 0
        lastBusActivityMs = clock.nowMs()

        return ObdInitResult(
            adapterVersion = "Galletto 1260 K-Line",
            activeProtocol = activeProtocolLabel,
            isCanProtocol = false,
            supportedPids = allSupported,
            baroKpa = baroKpa,
            milOn = milOn,
            dtcCountReported = dtcCount
        )
    }

    override suspend fun pollCycle(cycleIndex: Long): ObdPollSnapshot {
        val logThis = !loggedFirstPollCycle || verbosePollLogging

        val mapKpa = if (isPidEligible("0B")) {
            queryMode01Text(0x0B, logThis)?.let { ObdParser.parseMapKpa(it) }
        } else null

        val rpm = if (isPidEligible("0C")) {
            queryMode01Text(0x0C, logThis)?.let { ObdParser.parseRpm(it) }
        } else null

        // Stagger one secondary PID per cycle so 10400-baud K-Line stays snappy (>3-4 Hz MAP/RPM)
        var iatC: Float? = null
        var coolantC: Float? = null
        var loadPct: Float? = null
        var timingDeg: Float? = null
        var throttlePct: Float? = null
        var speedKmh: Int? = null
        var voltage: Float? = null

        when ((cycleIndex % 8L).toInt()) {
            0, 3 -> if (isPidEligible("0F")) {
                iatC = queryMode01Text(0x0F, logThis)?.let { ObdParser.parseIatCelsius(it) }
            }
            1 -> if (isPidEligible("05")) {
                coolantC = queryMode01Text(0x05, logThis)?.let { ObdParser.parseCoolantCelsius(it) }
            }
            2 -> if (isPidEligible("04")) {
                loadPct = queryMode01Text(0x04, logThis)?.let { ObdParser.parseLoadPercent(it) }
            }
            4 -> if (isPidEligible("0E")) {
                timingDeg = queryMode01Text(0x0E, logThis)?.let { ObdParser.parseTimingAdvance(it) }
            }
            5 -> if (isPidEligible("11")) {
                throttlePct = queryMode01Text(0x11, logThis)?.let { ObdParser.parseThrottlePercent(it) }
            }
            6 -> if (isPidEligible("0D")) {
                speedKmh = queryMode01Text(0x0D, logThis)?.let { ObdParser.parseSpeedKmh(it) }
            }
            7 -> if (supportsPid42Voltage) {
                voltage = queryMode01Text(0x42, logThis)?.let { ObdParser.parseControlModuleVoltage(it) }
            }
        }

        var baroKpa: Float? = null
        var milOn: Boolean? = null
        var dtcCount: Int? = null
        if (cycleIndex % 80L == 0L) {
            if (isPidEligible("33")) {
                baroKpa = queryMode01Text(0x33, logThis)?.let { ObdParser.parseBaroKpa(it) }
            }
            if (isPidEligible("01")) {
                queryMode01Text(0x01, logThis)?.let { raw ->
                    ObdParser.parseMilStatus(raw)?.let {
                        milOn = it.first
                        dtcCount = it.second
                    }
                }
            }
        }

        if (!loggedFirstPollCycle && cycleIndex >= 0L) {
            loggedFirstPollCycle = true
        }

        return ObdPollSnapshot(
            mapKpa = mapKpa,
            baroKpa = baroKpa,
            iatCelsius = iatC,
            coolantCelsius = coolantC,
            voltage = voltage,
            rpm = rpm,
            loadPercent = loadPct,
            timingAdvanceDeg = timingDeg,
            throttlePercent = throttlePct,
            speedKmh = speedKmh,
            milOn = milOn,
            dtcCountReported = dtcCount
        )
    }

    override suspend fun scanDtcs(): Triple<Boolean, Int, List<DtcEntry>> {
        logger(LogLevel.INFO, "Scanning K-Line fault codes (Mode 0101, 03, 07)...")

        var milOn = false
        var reportedCount = 0
        queryMode01Text(0x01, logTxRx = true)?.let { raw ->
            ObdParser.parseMilStatus(raw)?.let {
                milOn = it.first
                reportedCount = it.second
            }
        }

        val entries = ArrayList<DtcEntry>()
        val seenCodes = HashSet<String>()

        val storedFrames = sendObdPayload(
            payload = byteArrayOf(0x03),
            responseTimeoutMs = 1000,
            interByteGapMs = 120,
            logTxRx = true
        )
        val storedText = decodeFramesAsObdText(storedFrames).orEmpty()
        for (code in ObdParser.parseDtcs(storedText, "43", isCanProtocol = false)) {
            if (seenCodes.add("STORED:$code")) {
                entries.add(DtcCatalog.lookup(code, DtcCategory.STORED))
            }
        }

        val pendingFrames = sendObdPayload(
            payload = byteArrayOf(0x07),
            responseTimeoutMs = 1000,
            interByteGapMs = 120,
            logTxRx = true
        )
        val pendingText = decodeFramesAsObdText(pendingFrames).orEmpty()
        for (code in ObdParser.parseDtcs(pendingText, "47", isCanProtocol = false)) {
            if (seenCodes.add("PENDING:$code")) {
                entries.add(DtcCatalog.lookup(code, DtcCategory.PENDING))
            }
        }

        logger(
            LogLevel.INFO,
            "K-Line DTC scan complete: MIL=${if (milOn) "ON" else "OFF"}, found ${entries.size} code(s)"
        )
        return Triple(milOn, reportedCount, entries)
    }

    override suspend fun clearDtcs(): Boolean {
        logger(LogLevel.WARN, "Sending Mode 04 on K-Line to clear fault codes and reset MIL...")
        val frames = sendObdPayload(
            payload = byteArrayOf(0x04),
            responseTimeoutMs = 2000,
            interByteGapMs = 80,
            logTxRx = true
        )
        val ok = frames.any { it.payload.isNotEmpty() && (it.payload[0].toInt() and 0xFF) == 0x44 }
        logger(
            if (ok) LogLevel.INFO else LogLevel.ERROR,
            if (ok) "Mode 04 acknowledged (44) on K-Line." else "ECU did not acknowledge Mode 04 clear."
        )
        return ok
    }

    override suspend fun sendRawCommand(command: String): String {
        val trimmed = command.trim()
        val upper = trimmed.uppercase(Locale.US)
        if (upper == "INIT" || upper == "REINIT") {
            logger(LogLevel.TX, upper)
            setLinesLow(false)
            clock.sleepMs(350L)
            val detail = performWakeupHandshake()
            activeProtocolLabel = "${framing.displayName} [$detail]"
            val msg = "K-Line re-initialized: $activeProtocolLabel"
            logger(LogLevel.RX, msg)
            return msg
        }

        if (upper.startsWith("AT")) {
            val msg = "Galletto 1260 is a direct USB K-Line cable (no ELM327 chip), so AT commands are not used. Try 0105, 010B, 010C, 03, or INIT."
            logger(LogLevel.WARN, msg)
            return msg
        }

        val payload = KlineProtocol.parseManualHexCommand(trimmed)
            ?: throw IOException("Enter raw OBD hex bytes (e.g. 010C, 01 05, 03) or INIT.")

        val frames = sendObdPayload(
            payload = payload,
            responseTimeoutMs = 1000,
            interByteGapMs = 90,
            logTxRx = true
        )
        return decodeFramesAsObdText(frames) ?: "<no reply>"
    }

    override suspend fun keepAliveIfIdle(): Boolean {
        if (!isConnected) return false
        val idleMs = clock.nowMs() - lastBusActivityMs
        if (idleMs < KEEPALIVE_IDLE_MS) return true

        val frames = sendObdPayload(
            payload = byteArrayOf(0x01, 0x05),
            responseTimeoutMs = 450,
            interByteGapMs = 45,
            logTxRx = verbosePollLogging
        )
        return frames.isNotEmpty()
    }

    override fun close() {
        connected = false
        port.close()
    }

    private fun performWakeupHandshake(): String {
        return when (protocolPref) {
            ObdProtocol.ISO_9141_3 -> {
                val (_, kw1, kw2) = performFiveBaudInit()
                framing = KlineFraming.ISO_9141_2
                "5-Baud KW=%02X %02X".format(kw1, kw2)
            }
            ObdProtocol.KWP_5BAUD_4 -> {
                val (_, kw1, kw2) = performFiveBaudInit()
                framing = KlineFraming.KWP_2000
                "5-Baud KWP KW=%02X %02X".format(kw1, kw2)
            }
            ObdProtocol.KWP_FAST_5 -> {
                val (kw1, kw2) = performFastInit()
                framing = KlineFraming.KWP_2000
                "FastInit KW=%02X %02X".format(kw1, kw2)
            }
            ObdProtocol.CAN_11_500_6, ObdProtocol.AUTO_WITH_KLINE_FALLBACK -> {
                if (protocolPref == ObdProtocol.CAN_11_500_6) {
                    logger(LogLevel.WARN, "Galletto 1260 is K-Line only (no CAN bus); running Auto K-Line init.")
                }
                val fastAttempt = runCatching { performFastInit() }
                if (fastAttempt.isSuccess) {
                    val (kw1, kw2) = fastAttempt.getOrThrow()
                    framing = KlineFraming.KWP_2000
                    "FastInit KW=%02X %02X".format(kw1, kw2)
                } else {
                    logger(
                        LogLevel.INFO,
                        "KWP Fast Init no response (${fastAttempt.exceptionOrNull()?.message}); trying ISO 9141 5-baud slow init..."
                    )
                    setLinesLow(false)
                    clock.sleepMs(350L)
                    val (_, kw1, kw2) = performFiveBaudInit()
                    framing = KlineProtocol.detectFramingFromKeywords(kw1, kw2)
                    "5-Baud KW=%02X %02X -> ${framing.name}".format(kw1, kw2)
                }
            }
        }
    }

    private fun performFiveBaudInit(): Triple<Int, Int, Int> {
        logger(LogLevel.INFO, "Starting 5-baud K-Line wakeup (0x33 -> ME 2.7.1 ECU)...")
        setLinesLow(false)
        clock.sleepMs(300L)

        val bits = KlineProtocol.fiveBaudBitSequence(0x33)
        val t0 = clock.nanoTime()
        for (i in bits.indices) {
            setLinesLow(bits[i])
            if (i == bits.lastIndex) {
                // Purge mid-Stop-bit so FTDI break-induced 0x00 noise clears before ECU sends 0x55
                clock.sleepUntilNanos(t0 + i * 200_000_000L + 40_000_000L)
                purgeRx()
                clock.sleepUntilNanos(t0 + (i + 1) * 200_000_000L)
            } else {
                clock.sleepUntilNanos(t0 + (i + 1) * 200_000_000L)
            }
        }

        // Read [0x55, KW1, KW2] skipping any stray leading 0x00 break artifact bytes
        val (sync, kw1, kw2) = readSyncBytesAfterBreak(timeoutMs = 1200)
        logger(LogLevel.RX, String.format(Locale.US, "Sync: %02X %02X %02X", sync, kw1, kw2))

        // W4 wait (25-50ms) then transmit inverted KW2
        clock.sleepMs(30L)
        val invKw2 = byteArrayOf(kw2.toByte().toInt().inv().toByte())
        purgeRx()
        port.write(invKw2, 500)
        logger(LogLevel.TX, String.format(Locale.US, "~KW2: %02X", invKw2[0].toInt() and 0xFF))

        // Consume echo of ~KW2 and ECU 0xCC acknowledgment
        val ackDeadline = clock.nowMs() + 1000L
        val ackBuf = ArrayList<Byte>(4)
        while (clock.nowMs() < ackDeadline && ackBuf.size < 4) {
            val rem = (ackDeadline - clock.nowMs()).toInt().coerceAtLeast(1)
            val b = readOneByteBuffered(minOf(rem, 150))
            if (b != null) {
                ackBuf.add(b)
                if ((b.toInt() and 0xFF) == 0xCC) break
            }
        }
        val ackBytes = ackBuf.toByteArray()
        logger(LogLevel.RX, "Ack: ${KlineProtocol.formatHex(ackBytes).ifEmpty { "<empty>" }}")
        if (ackBytes.none { (it.toInt() and 0xFF) == 0xCC }) {
            throw IOException("ECU did not return 0xCC ack during 5-baud init (got ${KlineProtocol.formatHex(ackBytes)})")
        }

        val now = clock.nowMs()
        lastRxEndMs = now
        lastBusActivityMs = now
        return Triple(sync, kw1, kw2)
    }

    private fun performFastInit(): Pair<Int, Int> {
        logger(LogLevel.INFO, "Trying KWP2000 Fast Init (25ms LOW / 25ms HIGH + C1 33 F1 81 66)...")
        setLinesLow(false)
        clock.sleepMs(300L)

        val tStart = clock.nanoTime()
        setLinesLow(true)
        clock.sleepUntilNanos(tStart + 25_000_000L)
        setLinesLow(false)
        clock.sleepUntilNanos(tStart + 50_000_000L)

        purgeRx()
        val startReq = KlineProtocol.buildKwpStartCommRequest()
        port.write(startReq, 500)
        logger(LogLevel.TX, "StartComm: ${KlineProtocol.formatHex(startReq)}")

        val echo = readExact(startReq.size, timeoutMs = 200)
        if (echo.isEmpty()) {
            throw IOException("No K-Line local echo during Fast Init")
        }

        val replyRaw = readVariableReply(responseTimeoutMs = 300, interByteGapMs = 45)
        if (replyRaw.isEmpty()) {
            throw IOException("No ECU reply to StartComm (C1)")
        }
        logger(LogLevel.RX, "StartComm: ${KlineProtocol.formatHex(replyRaw)}")

        val now = clock.nowMs()
        lastRxEndMs = now
        lastBusActivityMs = now

        val frames = KlineProtocol.extractKwp2000Frames(replyRaw)
        val pos = frames.firstOrNull { it.payload.isNotEmpty() && (it.payload[0].toInt() and 0xFF) == 0xC1 }
            ?: throw IOException("Unexpected StartComm response: ${KlineProtocol.formatHex(replyRaw)}")

        val kw1 = pos.payload.getOrNull(1)?.toInt()?.and(0xFF) ?: 0xE9
        val kw2 = pos.payload.getOrNull(2)?.toInt()?.and(0xFF) ?: 0x8F
        return Pair(kw1, kw2)
    }

    private fun readSyncBytesAfterBreak(timeoutMs: Int): Triple<Int, Int, Int> {
        val deadline = clock.nowMs() + timeoutMs
        val raw = ArrayList<Int>(8)
        var syncIndex = -1

        while (clock.nowMs() < deadline) {
            val remaining = (deadline - clock.nowMs()).toInt().coerceAtLeast(1)
            val byteRead = readOneByteBuffered(minOf(remaining, 150)) ?: continue
            val b = byteRead.toInt() and 0xFF
            if (syncIndex < 0) {
                if (b == 0x00) {
                    continue // Discard break-induced 0x00 noise before 0x55
                }
                raw.add(b)
                if (b == 0x55) {
                    syncIndex = raw.lastIndex
                } else {
                    throw IOException(
                        String.format(Locale.US, "Expected 0x55 sync byte, got 0x%02X", b)
                    )
                }
            } else {
                raw.add(b)
            }
            if (syncIndex >= 0 && raw.size - syncIndex >= 3) {
                return Triple(raw[syncIndex], raw[syncIndex + 1], raw[syncIndex + 2])
            }
        }

        val gotHex = raw.joinToString(" ") { String.format(Locale.US, "%02X", it) }
        throw IOException("Timeout waiting for ECU 0x55 sync bytes (got ${gotHex.ifEmpty { "<nothing>" }})")
    }

    private fun sendMode01(pidByte: Int, logTxRx: Boolean): List<KlineFrame> {
        return sendObdPayload(
            payload = byteArrayOf(0x01, pidByte.toByte()),
            responseTimeoutMs = 450,
            interByteGapMs = 45,
            logTxRx = logTxRx
        )
    }

    private fun queryMode01Text(pidByte: Int, logTxRx: Boolean): String? {
        return decodeFramesAsObdText(sendMode01(pidByte, logTxRx))
    }

    private fun querySupportedPids(pidHex: String, baseOffset: Int): Set<String> {
        val pidInt = pidHex.toInt(16)
        val frames = sendObdPayload(
            payload = byteArrayOf(0x01, pidInt.toByte()),
            responseTimeoutMs = 600,
            interByteGapMs = 60,
            logTxRx = true
        )
        val merged = LinkedHashSet<String>()
        for (frame in frames) {
            merged.addAll(ObdParser.parseSupportedPids(KlineProtocol.formatHex(frame.payload), baseOffset))
        }
        return merged
    }

    private fun sendObdPayload(
        payload: ByteArray,
        responseTimeoutMs: Int,
        interByteGapMs: Int,
        logTxRx: Boolean
    ): List<KlineFrame> {
        val nowBefore = clock.nowMs()
        val elapsedSinceRx = nowBefore - lastRxEndMs
        if (lastRxEndMs > 0L && elapsedSinceRx in 0 until P3_MIN_MS) {
            clock.sleepMs(P3_MIN_MS - elapsedSinceRx)
        }

        val txFrame = when (framing) {
            KlineFraming.ISO_9141_2 -> KlineProtocol.buildIso9141Request(payload)
            KlineFraming.KWP_2000 -> KlineProtocol.buildKwp2000Request(payload)
        }

        purgeRx()
        writeFrame(txFrame)
        if (logTxRx) logger(LogLevel.TX, KlineProtocol.formatHex(txFrame))

        // Half-duplex K-Line ALWAYS echoes TX bytes before ECU can reply
        val echo = readExact(txFrame.size, timeoutMs = 250)
        if (echo.isEmpty()) {
            consecutiveTimeouts++
            if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                connected = false
                throw IOException("No K-Line local echo — verify Galletto cable is plugged into OBD2 port and ignition is ON (Pos 2).")
            }
            throw IOException("No K-Line local echo from adapter")
        }
        if (echo.size < txFrame.size || !echo.contentEquals(txFrame)) {
            logger(
                LogLevel.WARN,
                "K-Line echo mismatch: sent [${KlineProtocol.formatHex(txFrame)}] got [${KlineProtocol.formatHex(echo)}]"
            )
        }

        val replyRaw = readVariableReply(responseTimeoutMs = responseTimeoutMs, interByteGapMs = interByteGapMs)
        val doneMs = clock.nowMs()
        lastRxEndMs = doneMs
        lastBusActivityMs = doneMs

        if (replyRaw.isEmpty()) {
            consecutiveTimeouts++
            if (logTxRx) logger(LogLevel.WARN, "No ECU reply (${KlineProtocol.formatHex(txFrame)})")
            if (consecutiveTimeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                connected = false
                throw IOException("ECU stopped responding on K-Line (ignition off or session lost).")
            }
            return emptyList()
        }

        val parsed = when (framing) {
            KlineFraming.ISO_9141_2 -> KlineProtocol.extractIso9141Frames(replyRaw)
            KlineFraming.KWP_2000 -> KlineProtocol.extractKwp2000Frames(replyRaw)
        }

        if (logTxRx) {
            logger(LogLevel.RX, KlineProtocol.formatHex(replyRaw))
        }
        if (parsed.isEmpty()) {
            logger(LogLevel.WARN, "Checksum/frame mismatch on K-Line RX: ${KlineProtocol.formatHex(replyRaw)}")
            return emptyList()
        }

        consecutiveTimeouts = 0
        return parsed
    }

    private fun writeFrame(txFrame: ByteArray) {
        if (framing == KlineFraming.ISO_9141_2 && P4_ISO9141_INTERBYTE_MS > 0L) {
            val single = ByteArray(1)
            for (i in txFrame.indices) {
                single[0] = txFrame[i]
                port.write(single, 200)
                if (i < txFrame.lastIndex) {
                    clock.sleepMs(P4_ISO9141_INTERBYTE_MS)
                }
            }
        } else {
            port.write(txFrame, 500)
        }
    }

    private fun purgeRx() {
        rxOverflow.clear()
        port.purgeHwBuffers(false, true)
    }

    private fun fillRxBuffer(timeoutMs: Int): Int {
        if (rxOverflow.isNotEmpty()) return rxOverflow.size
        val n = port.read(usbReadChunk, timeoutMs)
        for (i in 0 until n) {
            rxOverflow.addLast(usbReadChunk[i])
        }
        return n
    }

    private fun readOneByteBuffered(timeoutMs: Int): Byte? {
        if (rxOverflow.isEmpty()) {
            fillRxBuffer(timeoutMs)
        }
        return if (rxOverflow.isNotEmpty()) rxOverflow.removeFirst() else null
    }

    private fun readExact(count: Int, timeoutMs: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        val deadline = clock.nowMs() + timeoutMs
        while (offset < count && clock.nowMs() < deadline) {
            if (rxOverflow.isEmpty()) {
                val remaining = (deadline - clock.nowMs()).toInt().coerceAtLeast(1)
                fillRxBuffer(minOf(remaining, 80))
            }
            while (offset < count && rxOverflow.isNotEmpty()) {
                out[offset++] = rxOverflow.removeFirst()
            }
        }
        return if (offset == count) out else out.copyOf(offset)
    }

    private fun readVariableReply(responseTimeoutMs: Int, interByteGapMs: Int): ByteArray {
        val acc = ArrayList<Byte>(64)
        var gotFirst = false
        var waitingAfterPending = false
        var deadline = clock.nowMs() + responseTimeoutMs

        while (clock.nowMs() < deadline || rxOverflow.isNotEmpty()) {
            if (rxOverflow.isEmpty()) {
                val remaining = (deadline - clock.nowMs()).toInt().coerceAtLeast(1)
                val pollTimeout = if (gotFirst && !waitingAfterPending) {
                    minOf(remaining, interByteGapMs)
                } else {
                    minOf(remaining, 50)
                }
                val n = fillRxBuffer(pollTimeout)
                if (n <= 0) {
                    if (gotFirst && !waitingAfterPending) break
                    continue
                }
            }

            gotFirst = true
            while (rxOverflow.isNotEmpty()) {
                acc.add(rxOverflow.removeFirst())
            }
            val soFar = acc.toByteArray()
            if (endsWithResponsePending(soFar)) {
                waitingAfterPending = true
                deadline = clock.nowMs() + 2000L
            } else {
                waitingAfterPending = false
                deadline = clock.nowMs() + interByteGapMs
            }
        }
        return acc.toByteArray()
    }

    private fun endsWithResponsePending(raw: ByteArray): Boolean {
        val frames = when (framing) {
            KlineFraming.ISO_9141_2 -> KlineProtocol.extractIso9141Frames(raw)
            KlineFraming.KWP_2000 -> KlineProtocol.extractKwp2000Frames(raw)
        }
        val lastPayload = frames.lastOrNull()?.payload ?: return false
        return lastPayload.size >= 3 &&
            (lastPayload[0].toInt() and 0xFF) == 0x7F &&
            (lastPayload[2].toInt() and 0xFF) == 0x78
    }

    private fun decodeFramesAsObdText(frames: List<KlineFrame>): String? {
        if (frames.isEmpty()) return null
        return frames.joinToString("\n") { KlineProtocol.formatHex(it.payload) }
    }

    private fun isPidEligible(pidHex: String): Boolean {
        return supportedPids.isEmpty() || pidHex in supportedPids
    }

    private fun setLinesLow(low: Boolean) {
        port.setBreak(low)
        port.setRTS(low)
    }
}
