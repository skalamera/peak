package com.cl600.obd.obd

import com.cl600.obd.model.DtcCategory
import com.cl600.obd.model.DtcEntry
import com.cl600.obd.model.LogLevel
import com.cl600.obd.model.ObdProtocol
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.Locale

class Elm327Session(
    private val transport: ObdTransport,
    private val protocolPref: ObdProtocol,
    private val logger: (LogLevel, String) -> Unit
) {
    data class InitResult(
        val adapterVersion: String,
        val activeProtocol: String,
        val isCanProtocol: Boolean,
        val supportedPids: Set<String>,
        val baroKpa: Float?,
        val milOn: Boolean,
        val dtcCountReported: Int
    )

    data class PollSnapshot(
        val mapKpa: Float? = null,
        val baroKpa: Float? = null,
        val iatCelsius: Float? = null,
        val coolantCelsius: Float? = null,
        val voltage: Float? = null,
        val rpm: Int? = null,
        val loadPercent: Float? = null,
        val timingAdvanceDeg: Float? = null,
        val throttlePercent: Float? = null,
        val speedKmh: Int? = null,
        val milOn: Boolean? = null,
        val dtcCountReported: Int? = null
    )

    var isCanProtocol: Boolean = false
        private set

    var supportedPids: Set<String> = emptySet()
        private set

    private var supportsPid42Voltage: Boolean = false
    private var loggedFirstPollCycle: Boolean = false
    var verbosePollLogging: Boolean = false

    val isConnected: Boolean
        get() = transport.isConnected

    suspend fun initialize(): InitResult {
        logger(LogLevel.INFO, "Initializing ELM327 adapter on ${transport.displayName}...")

        // Reset adapter
        val atzResp = execLogged("ATZ", timeoutMs = 3000L)
        delay(300L)
        val adapterVersion = ObdParser.cleanResponse(atzResp, "ATZ")
            .firstOrNull { it.isNotBlank() && it != "OK" }
            ?: "ELM327"
        logger(LogLevel.INFO, "Adapter version: $adapterVersion")

        // Basic ELM327 configuration (best-effort on clones)
        sendOptionalAt("ATE0")
        sendOptionalAt("ATL0")
        sendOptionalAt("ATS0")
        sendOptionalAt("ATH0")
        sendOptionalAt("ATST96") // 600ms timeout helps slow 2003 K-Line ECU handshake

        // Protocol selection & ECU handshake via 0100
        var pid0100Resp = ""
        var pids00 = emptySet<String>()

        if (protocolPref == ObdProtocol.AUTO_WITH_KLINE_FALLBACK) {
            val candidates = listOf(
                ObdProtocol.AUTO_WITH_KLINE_FALLBACK,
                ObdProtocol.KWP_FAST_5,
                ObdProtocol.ISO_9141_3,
                ObdProtocol.KWP_5BAUD_4,
                ObdProtocol.CAN_11_500_6
            )
            var matched = false
            for (proto in candidates) {
                execLogged(proto.atCommand, timeoutMs = 2000L)
                val resp = execLogged("0100", timeoutMs = 6000L)
                val parsed = ObdParser.parseSupportedPids(resp, 0)
                if (parsed.isNotEmpty()) {
                    pid0100Resp = resp
                    pids00 = parsed
                    matched = true
                    logger(LogLevel.INFO, "ECU responded on ${proto.displayName}")
                    break
                } else {
                    logger(LogLevel.WARN, "${proto.atCommand} -> 0100 did not connect ($resp)")
                }
            }
            if (!matched) {
                throw IOException(
                    "Adapter connected, but car ECU did not respond to 0100 on Auto or K-Line/CAN fallbacks. " +
                        "Make sure ignition key is turned ON (position 2) and adapter is seated firmly."
                )
            }
        } else {
            execLogged(protocolPref.atCommand, timeoutMs = 2000L)
            pid0100Resp = execLogged("0100", timeoutMs = 6000L)
            pids00 = ObdParser.parseSupportedPids(pid0100Resp, 0)
            if (pids00.isEmpty()) {
                throw IOException(
                    "ECU did not respond on ${protocolPref.displayName} (0100 -> ${pid0100Resp.trim()}). " +
                        "Verify ignition is ON or switch protocol to Auto + K-Line Fallback."
                )
            }
        }

        // Read active protocol details
        val dpResp = execLogged("ATDP", timeoutMs = 1500L)
        val dpnResp = execLogged("ATDPN", timeoutMs = 1500L)
        val activeProtocol = ObdParser.cleanResponse(dpResp, "ATDP")
            .firstOrNull { it.isNotBlank() }
            ?: protocolPref.displayName
        val dpnClean = ObdParser.cleanResponse(dpnResp, "ATDPN")
            .firstOrNull()
            ?.removePrefix("A")
            ?.trim()
            .orEmpty()

        isCanProtocol = detectIsCan(activeProtocol, dpnClean)
        logger(
            LogLevel.INFO,
            "Active protocol: $activeProtocol (code=$dpnClean, CAN=$isCanProtocol)"
        )

        // Gather 0120 and 0140 PID bitmasks if indicated
        val allSupported = LinkedHashSet<String>()
        allSupported.addAll(pids00)

        if ("20" in allSupported) {
            val resp20 = execLogged("0120", timeoutMs = 2500L)
            allSupported.addAll(ObdParser.parseSupportedPids(resp20, 0x20))
        }
        if ("40" in allSupported) {
            val resp40 = execLogged("0140", timeoutMs = 2500L)
            allSupported.addAll(ObdParser.parseSupportedPids(resp40, 0x40))
        }

        supportedPids = allSupported
        supportsPid42Voltage = "42" in allSupported
        logger(LogLevel.INFO, "Supported PIDs (${allSupported.size}): ${allSupported.joinToString(",")}")

        // Read initial barometric pressure if supported
        var baroKpa: Float? = null
        if ("33" in allSupported) {
            val baroResp = execLogged("0133", timeoutMs = 2000L)
            baroKpa = ObdParser.parseBaroKpa(baroResp)
        }

        // Read initial MIL & DTC count
        var milOn = false
        var dtcCount = 0
        if (allSupported.isEmpty() || "01" in allSupported) {
            val milResp = execLogged("0101", timeoutMs = 2000L)
            val parsedMil = ObdParser.parseMilStatus(milResp)
            if (parsedMil != null) {
                milOn = parsedMil.first
                dtcCount = parsedMil.second
            }
        }

        return InitResult(
            adapterVersion = adapterVersion,
            activeProtocol = activeProtocol,
            isCanProtocol = isCanProtocol,
            supportedPids = allSupported,
            baroKpa = baroKpa,
            milOn = milOn,
            dtcCountReported = dtcCount
        )
    }

    suspend fun pollCycle(cycleIndex: Long): PollSnapshot {
        val logThisCycle = !loggedFirstPollCycle || verbosePollLogging

        val mapKpa = if (isPidEligible("0B")) {
            val raw = execPoll("010B", logThisCycle)
            ObdParser.parseMapKpa(raw)
        } else null

        val rpm = if (isPidEligible("0C")) {
            val raw = execPoll("010C", logThisCycle)
            ObdParser.parseRpm(raw)
        } else null

        var iatC: Float? = null
        var loadPct: Float? = null
        var timingDeg: Float? = null
        var throttlePct: Float? = null
        var speedKmh: Int? = null

        if (cycleIndex % 3L == 0L) {
            if (isPidEligible("0F")) {
                iatC = ObdParser.parseIatCelsius(execPoll("010F", logThisCycle))
            }
            if (isPidEligible("04")) {
                loadPct = ObdParser.parseLoadPercent(execPoll("0104", logThisCycle))
            }
            if (isPidEligible("0E")) {
                timingDeg = ObdParser.parseTimingAdvance(execPoll("010E", logThisCycle))
            }
            if (isPidEligible("11")) {
                throttlePct = ObdParser.parseThrottlePercent(execPoll("0111", logThisCycle))
            }
            if (isPidEligible("0D")) {
                speedKmh = ObdParser.parseSpeedKmh(execPoll("010D", logThisCycle))
            }
        }

        var coolantC: Float? = null
        var voltage: Float? = null
        var baroKpa: Float? = null
        var milOn: Boolean? = null
        var dtcCount: Int? = null

        if (cycleIndex % 8L == 0L) {
            if (isPidEligible("05")) {
                coolantC = ObdParser.parseCoolantCelsius(execPoll("0105", logThisCycle))
            }
            voltage = if (supportsPid42Voltage) {
                ObdParser.parseControlModuleVoltage(execPoll("0142", logThisCycle))
                    ?: ObdParser.parseAtrvVoltage(execPoll("ATRV", logThisCycle))
            } else {
                ObdParser.parseAtrvVoltage(execPoll("ATRV", logThisCycle))
            }
        }

        if (cycleIndex % 80L == 0L) {
            if (isPidEligible("33")) {
                baroKpa = ObdParser.parseBaroKpa(execPoll("0133", logThisCycle))
            }
            if (isPidEligible("01")) {
                val mil = ObdParser.parseMilStatus(execPoll("0101", logThisCycle))
                if (mil != null) {
                    milOn = mil.first
                    dtcCount = mil.second
                }
            }
        }

        if (!loggedFirstPollCycle && cycleIndex >= 0L) {
            loggedFirstPollCycle = true
        }

        return PollSnapshot(
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

    suspend fun scanDtcs(): Triple<Boolean, Int, List<DtcEntry>> {
        logger(LogLevel.INFO, "Scanning engine fault codes (Mode 0101, 03, 07, 0A)...")

        var milOn = false
        var reportedCount = 0
        val milRaw = execLogged("0101", timeoutMs = 2500L)
        ObdParser.parseMilStatus(milRaw)?.let {
            milOn = it.first
            reportedCount = it.second
        }

        val entries = ArrayList<DtcEntry>()
        val seenCodes = HashSet<String>()

        val storedRaw = execLogged("03", timeoutMs = 4000L)
        val storedCodes = ObdParser.parseDtcs(storedRaw, "43", isCanProtocol)
        for (code in storedCodes) {
            if (seenCodes.add("STORED:$code")) {
                entries.add(DtcCatalog.lookup(code, DtcCategory.STORED))
            }
        }

        val pendingRaw = execLogged("07", timeoutMs = 4000L)
        val pendingCodes = ObdParser.parseDtcs(pendingRaw, "47", isCanProtocol)
        for (code in pendingCodes) {
            if (seenCodes.add("PENDING:$code")) {
                entries.add(DtcCatalog.lookup(code, DtcCategory.PENDING))
            }
        }

        // Mode 0A (Permanent DTCs) - optional, typically unsupported on pre-2010 K-Line ECUs
        val permRaw = execLogged("0A", timeoutMs = 2500L)
        if (!ObdParser.hasError(permRaw)) {
            val permCodes = ObdParser.parseDtcs(permRaw, "4A", isCanProtocol)
            for (code in permCodes) {
                if (seenCodes.add("PERMANENT:$code")) {
                    entries.add(DtcCatalog.lookup(code, DtcCategory.PERMANENT))
                }
            }
        }

        logger(
            LogLevel.INFO,
            "DTC scan complete: MIL=${if (milOn) "ON" else "OFF"}, found ${entries.size} code(s)"
        )
        return Triple(milOn, reportedCount, entries)
    }

    suspend fun clearDtcs(): Boolean {
        logger(LogLevel.WARN, "Sending Mode 04 to clear fault codes and reset MIL...")
        val resp = execLogged("04", timeoutMs = 5000L)
        val lines = ObdParser.cleanResponse(resp, "04")
        val ok = !ObdParser.hasError(resp) && (
            lines.any { it.replace(" ", "").startsWith("44") } ||
                lines.any { it.contains("OK") }
            )
        logger(
            if (ok) LogLevel.INFO else LogLevel.ERROR,
            if (ok) "Mode 04 acknowledged (fault codes cleared)." else "Mode 04 response unclear/rejected: $resp"
        )
        return ok
    }

    suspend fun sendRawCommand(command: String): String {
        return execLogged(command, timeoutMs = 4000L)
    }

    fun close() {
        transport.close()
    }

    private fun isPidEligible(pidHex: String): Boolean {
        return supportedPids.isEmpty() || pidHex in supportedPids
    }

    private suspend fun sendOptionalAt(cmd: String) {
        val resp = execLogged(cmd, timeoutMs = 1500L)
        if (resp.trim() == "?" || ObdParser.hasError(resp)) {
            logger(LogLevel.WARN, "Adapter ignored optional command $cmd ($resp)")
        }
    }

    private suspend fun execLogged(cmd: String, timeoutMs: Long): String {
        logger(LogLevel.TX, cmd)
        val resp = transport.sendCommand(cmd, timeoutMs)
        val compact = resp.replace("\r", " ").replace("\n", " | ").trim()
        logger(LogLevel.RX, compact.ifEmpty { "<empty>" })
        return resp
    }

    private suspend fun execPoll(cmd: String, logIt: Boolean): String {
        if (logIt) logger(LogLevel.TX, cmd)
        val resp = transport.sendCommand(cmd, 2000L)
        if (logIt || ObdParser.hasError(resp)) {
            val compact = resp.replace("\r", " ").replace("\n", " | ").trim()
            if (!logIt) logger(LogLevel.TX, cmd)
            logger(
                if (ObdParser.hasError(resp)) LogLevel.WARN else LogLevel.RX,
                compact.ifEmpty { "<empty>" }
            )
        }
        return resp
    }

    private fun detectIsCan(dpDesc: String, dpnCode: String): Boolean {
        val upperDesc = dpDesc.uppercase(Locale.US)
        if (upperDesc.contains("CAN") || upperDesc.contains("15765")) return true
        if (upperDesc.contains("9141") || upperDesc.contains("14230") || upperDesc.contains("KWP")) return false
        return when (dpnCode) {
            "6", "7", "8", "9", "A", "B", "C" -> true
            else -> false
        }
    }
}
