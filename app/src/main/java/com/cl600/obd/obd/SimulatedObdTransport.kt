package com.cl600.obd.obd

import com.cl600.obd.model.LogLevel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Simulates an ELM327 v1.5 connected over KWP2000 Fast (ISO 14230-4) to a 2003 CL600 (M275 V12).
 * Responds in authentic hex frames so the full Elm327Session + ObdParser pipeline is exercised.
 */
class SimulatedObdTransport : ObdTransport {

    override val displayName: String = "M275 V12 Demo Simulator"

    @Volatile
    private var connected = false
    private var startTimeMs = 0L
    private var protocolNumber = 5 // ISO 14230-4 KWP Fast

    // Simulated stored & pending fault codes that clear when Mode 04 is issued
    private val storedDtcs = mutableListOf(0x0307) // P0307 Cylinder 7 Misfire
    private val pendingDtcs = mutableListOf(0x0113) // P0113 IAT Sensor High

    override val isConnected: Boolean
        get() = connected

    override suspend fun connect(logger: (LogLevel, String) -> Unit) {
        logger(LogLevel.INFO, "Starting M275 V12 biturbo simulator...")
        delay(250L)
        startTimeMs = System.currentTimeMillis()
        connected = true
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String {
        if (!connected) return "NO DATA"
        delay(18L)

        val cmd = command.trim().replace(" ", "").uppercase(Locale.US)
        return when {
            cmd == "ATZ" -> "ELM327 v1.5"
            cmd == "ATE0" || cmd == "ATL0" || cmd == "ATS0" || cmd == "ATH0" -> "OK"
            cmd.startsWith("ATST") -> "OK"
            cmd.startsWith("ATSP") -> {
                val p = cmd.removePrefix("ATSP").toIntOrNull()
                if (p != null && p != 0) protocolNumber = p else protocolNumber = 5
                "OK"
            }
            cmd == "ATDP" -> when (protocolNumber) {
                3 -> "ISO 9141-2"
                4 -> "ISO 14230-4 (KWP 5BAUD)"
                6 -> "ISO 15765-4 (CAN 11/500)"
                else -> "ISO 14230-4 (KWP FAST)"
            }
            cmd == "ATDPN" -> "A$protocolNumber"
            cmd == "ATRV" -> String.format(Locale.US, "%.1fV", currentVoltage())

            // Supported PIDs: 01, 04, 05, 0B, 0C, 0D, 0E, 0F, 11, 20, 33, 40, 42
            cmd == "0100" -> "4100983B8011"
            cmd == "0120" -> "412000002001"
            cmd == "0140" -> "414040000000"

            cmd == "0101" -> {
                val milBit = if (storedDtcs.isNotEmpty()) 0x80 else 0x00
                val count = (storedDtcs.size and 0x7F) or milBit
                String.format(Locale.US, "4101%02X076500", count)
            }

            cmd == "010B" -> {
                val map = currentMapKpa().coerceIn(20, 220)
                String.format(Locale.US, "410B%02X", map)
            }

            cmd == "0133" -> "413365" // 101 kPa Baro

            cmd == "010F" -> {
                val iatByte = (currentIatC() + 40).coerceIn(0, 255)
                String.format(Locale.US, "410F%02X", iatByte)
            }

            cmd == "0105" -> {
                val ectByte = (currentCoolantC() + 40).coerceIn(0, 255)
                String.format(Locale.US, "4105%02X", ectByte)
            }

            cmd == "010C" -> {
                val rpmEncoded = currentRpm() * 4
                val a = (rpmEncoded ushr 8) and 0xFF
                val b = rpmEncoded and 0xFF
                String.format(Locale.US, "410C%02X%02X", a, b)
            }

            cmd == "010D" -> {
                val speed = currentSpeedKmh().coerceIn(0, 255)
                String.format(Locale.US, "410D%02X", speed)
            }

            cmd == "0104" -> {
                val load = ((currentLoadPercent() * 255f) / 100f).toInt().coerceIn(0, 255)
                String.format(Locale.US, "4104%02X", load)
            }

            cmd == "010E" -> {
                val timing = ((currentTimingDeg() + 64f) * 2f).toInt().coerceIn(0, 255)
                String.format(Locale.US, "410E%02X", timing)
            }

            cmd == "0111" -> {
                val tps = ((currentThrottlePercent() * 255f) / 100f).toInt().coerceIn(0, 255)
                String.format(Locale.US, "4111%02X", tps)
            }

            cmd == "0142" -> {
                val mv = (currentVoltage() * 1000f).toInt()
                String.format(Locale.US, "4142%02X%02X", (mv ushr 8) and 0xFF, mv and 0xFF)
            }

            cmd == "03" -> formatKLineDtcs("43", storedDtcs)
            cmd == "07" -> formatKLineDtcs("47", pendingDtcs)
            cmd == "0A" -> "NO DATA"
            cmd == "04" -> {
                storedDtcs.clear()
                pendingDtcs.clear()
                "44"
            }

            else -> "NO DATA"
        }
    }

    private fun formatKLineDtcs(modeHex: String, list: List<Int>): String {
        if (list.isEmpty()) return "${modeHex}000000000000"
        val padded = list.take(3).toMutableList()
        while (padded.size < 3) padded.add(0x0000)
        return buildString {
            append(modeHex)
            for (code in padded) {
                append(String.format(Locale.US, "%04X", code))
            }
        }
    }

    private fun cyclePhase(): Double {
        val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
        return (elapsedSec % 20.0) / 20.0 // 20-second drive cycle
    }

    private fun pullEnvelope(): Double {
        val p = cyclePhase()
        return when {
            p < 0.25 -> 0.05 * sin(p / 0.25 * PI) // Idle vacuum
            p < 0.50 -> 0.35 * sin((p - 0.25) / 0.25 * PI) // Part-throttle cruise
            p < 0.80 -> sin((p - 0.50) / 0.30 * PI) // Spirited V12 biturbo pull!
            else -> 0.10 // Decel vacuum
        }
    }

    private fun currentMapKpa(): Int {
        val env = pullEnvelope()
        val p = cyclePhase()
        return if (p >= 0.80) {
            34 // overrun vacuum (~ -9.7 PSI / ~ -20 inHg)
        } else {
            (48.0 + env * 141.0).toInt() // up to ~189 kPa => ~12.7 PSI boost
        }
    }

    private fun currentRpm(): Int {
        val env = pullEnvelope()
        return (680.0 + env * 5100.0).toInt()
    }

    private fun currentIatC(): Int {
        val env = pullEnvelope()
        return (32.0 + env * 23.0).toInt() // ~90°F -> ~131°F under boost
    }

    private fun currentCoolantC(): Int {
        val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
        return (89.0 + 2.0 * sin(elapsedSec * 0.2)).toInt() // ~192-196°F
    }

    private fun currentVoltage(): Float {
        val env = pullEnvelope()
        return (13.8f + (env.toFloat() * 0.3f))
    }

    private fun currentSpeedKmh(): Int {
        val env = pullEnvelope()
        return (10.0 + env * 135.0).toInt()
    }

    private fun currentLoadPercent(): Float {
        val env = pullEnvelope()
        return (18f + env.toFloat() * 79f)
    }

    private fun currentTimingDeg(): Float {
        val env = pullEnvelope()
        return (14f + (1f - env.toFloat()) * 16f - env.toFloat() * 4f)
    }

    private fun currentThrottlePercent(): Float {
        val env = pullEnvelope()
        return (11f + env.toFloat() * 88f)
    }

    override fun close() {
        connected = false
    }
}
