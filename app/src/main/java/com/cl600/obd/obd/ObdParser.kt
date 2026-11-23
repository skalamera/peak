package com.cl600.obd.obd

import java.util.Locale

object ObdParser {

    private val ERROR_TOKENS = listOf(
        "NO DATA",
        "UNABLE TO CONNECT",
        "BUS ERROR",
        "CAN ERROR",
        "FB ERROR",
        "DATA ERROR",
        "STOPPED",
        "BUFFER FULL",
        "ERROR"
    )

    private val ATRV_REGEX = Regex("""(\d+(?:\.\d+)?)\s*V?""", RegexOption.IGNORE_CASE)
    private val ISOTOP_LINE_REGEX = Regex("""^([0-9A-F]):\s*(.*)$""")
    private val HEX_ONLY_REGEX = Regex("""^[0-9A-F]+$""")

    fun hasError(raw: String): Boolean {
        val upper = raw.uppercase(Locale.US)
        if (upper.trim() == "?") return true
        return ERROR_TOKENS.any { token ->
            // Avoid false positive on "BUS INIT: ...OK" unless it says ERROR
            upper.contains(token)
        }
    }

    fun cleanResponse(raw: String, sentCommand: String? = null): List<String> {
        val normalizedSent = sentCommand?.replace(" ", "")?.uppercase(Locale.US)
        val lines = raw
            .replace("\r", "\n")
            .replace(">", "")
            .split("\n")

        val result = ArrayList<String>(lines.size)
        for (rawLine in lines) {
            var line = rawLine.trim().uppercase(Locale.US)
            if (line.isEmpty()) continue

            // Remove "SEARCHING..." prefix or line
            if (line.startsWith("SEARCHING")) {
                line = line.removePrefix("SEARCHING...").removePrefix("SEARCHING").trim()
                if (line.isEmpty()) continue
            }

            // Handle "BUS INIT: ...OK" or "BUS INIT: OK"
            if (line.startsWith("BUS INIT")) {
                if (line.contains("ERROR")) {
                    result.add(line)
                    continue
                }
                val okIdx = line.indexOf("OK")
                if (okIdx >= 0) {
                    line = line.substring(okIdx + 2).trim()
                    if (line.isEmpty()) continue
                } else {
                    continue
                }
            }

            val noSpaces = line.replace(" ", "")
            if (normalizedSent != null && noSpaces == normalizedSent) {
                continue
            }

            result.add(line)
        }
        return result
    }

    /**
     * Converts a single line of ELM327 hex (spaced or spaceless) into an IntArray of bytes.
     * Returns null if the line contains non-hex characters or odd nibble count.
     */
    fun hexLineToBytes(line: String): IntArray? {
        val compact = line.replace(" ", "").uppercase(Locale.US)
        if (compact.isEmpty() || compact.length % 2 != 0) return null
        if (!HEX_ONLY_REGEX.matches(compact)) return null
        val count = compact.length / 2
        val bytes = IntArray(count)
        for (i in 0 until count) {
            bytes[i] = compact.substring(i * 2, i * 2 + 2).toInt(16)
        }
        return bytes
    }

    /**
     * Extracts payload bytes following `41 <pidByte>` from an OBD Mode 01 response.
     * Supports spaced ("41 0B 65"), spaceless ("410B65"), noisy prefixes ("SEARCHING..."),
     * and echoed commands.
     */
    fun extractMode01Bytes(raw: String, expectedPidHex: String, sentCommand: String? = null): IntArray? {
        if (hasError(raw)) return null
        val targetPid = expectedPidHex.uppercase(Locale.US).toIntOrNull(16) ?: return null
        val lines = cleanResponse(raw, sentCommand)

        for (line in lines) {
            val bytes = hexLineToBytes(line) ?: continue
            // Usually byte 0 is 0x41 and byte 1 is targetPid; scan in case of header bytes
            for (i in 0 until bytes.size - 1) {
                if (bytes[i] == 0x41 && bytes[i + 1] == targetPid) {
                    val payloadStart = i + 2
                    if (payloadStart <= bytes.size) {
                        return bytes.copyOfRange(payloadStart, bytes.size)
                    }
                }
            }
        }
        return null
    }

    fun parseMapKpa(raw: String): Float? {
        val data = extractMode01Bytes(raw, "0B") ?: return null
        return if (data.isNotEmpty()) data[0].toFloat() else null
    }

    fun parseBaroKpa(raw: String): Float? {
        val data = extractMode01Bytes(raw, "33") ?: return null
        return if (data.isNotEmpty()) data[0].toFloat() else null
    }

    fun calculateBoostPsi(mapKpa: Float, baroKpa: Float): Float {
        return (mapKpa - baroKpa) * 0.14503774f
    }

    /**
     * Estimates crank HP & torque (lb-ft) for the 2003 CL600 M275 5.513L V12 using Speed-Density
     * from MAP (010B), IAT (010F), and RPM (010C) — or directly from MAF (g/s) if provided.
     */
    fun estimatePowerAndTorque(
        mapKpa: Float,
        iatCelsius: Float,
        rpm: Int,
        displacementLiters: Float = 5.513f,
        volumetricEfficiency: Float = 0.88f,
        mafGramsPerSec: Float? = null
    ): Pair<Float, Float> {
        if (rpm < 300 || mapKpa < 25f) return Pair(0f, 0f)
        val maf = mafGramsPerSec ?: run {
            val tempKelvin = (iatCelsius + 273.15f).coerceAtLeast(200f)
            val airDensityGPerL = mapKpa / (0.28705f * tempKelvin)
            val sweptLitersPerSec = (displacementLiters * rpm.toFloat()) / 120f
            airDensityGPerL * sweptLitersPerSec * volumetricEfficiency
        }
        val grossHp = maf / 0.80f
        val frictionHp = (rpm / 1000f) * 3.5f
        val estHp = (grossHp - frictionHp).coerceAtLeast(0f)
        val estTorqueLbFt = if (estHp > 0f) (estHp * 5252f) / rpm.toFloat() else 0f
        return Pair(estHp, estTorqueLbFt)
    }

    fun parseIatCelsius(raw: String): Float? {
        val data = extractMode01Bytes(raw, "0F") ?: return null
        return if (data.isNotEmpty()) (data[0] - 40).toFloat() else null
    }

    fun parseCoolantCelsius(raw: String): Float? {
        val data = extractMode01Bytes(raw, "05") ?: return null
        return if (data.isNotEmpty()) (data[0] - 40).toFloat() else null
    }

    fun parseRpm(raw: String): Int? {
        val data = extractMode01Bytes(raw, "0C") ?: return null
        if (data.size < 2) return null
        return ((256 * data[0]) + data[1]) / 4
    }

    fun parseSpeedKmh(raw: String): Int? {
        val data = extractMode01Bytes(raw, "0D") ?: return null
        return if (data.isNotEmpty()) data[0] else null
    }

    fun parseLoadPercent(raw: String): Float? {
        val data = extractMode01Bytes(raw, "04") ?: return null
        return if (data.isNotEmpty()) (data[0] * 100f) / 255f else null
    }

    fun parseTimingAdvance(raw: String): Float? {
        val data = extractMode01Bytes(raw, "0E") ?: return null
        return if (data.isNotEmpty()) (data[0] / 2f) - 64f else null
    }

    fun parseThrottlePercent(raw: String): Float? {
        val data = extractMode01Bytes(raw, "11") ?: return null
        return if (data.isNotEmpty()) (data[0] * 100f) / 255f else null
    }

    fun parseControlModuleVoltage(raw: String): Float? {
        val data = extractMode01Bytes(raw, "42") ?: return null
        if (data.size < 2) return null
        return ((256 * data[0]) + data[1]) / 1000f
    }

    fun parseAtrvVoltage(raw: String): Float? {
        val lines = cleanResponse(raw, "ATRV")
        for (line in lines) {
            val match = ATRV_REGEX.find(line) ?: continue
            val value = match.groupValues[1].toFloatOrNull()
            if (value != null && value in 4.0f..24.0f) {
                return value
            }
        }
        return null
    }

    fun parseSupportedPids(raw: String, baseOffset: Int): Set<String> {
        val baseHex = String.format(Locale.US, "%02X", baseOffset)
        val data = extractMode01Bytes(raw, baseHex) ?: return emptySet()
        if (data.size < 4) return emptySet()

        val supported = LinkedHashSet<String>()
        for (byteIndex in 0 until 4) {
            val byteVal = data[byteIndex]
            for (bit in 7 downTo 0) {
                if ((byteVal and (1 shl bit)) != 0) {
                    val pidNumber = baseOffset + (byteIndex * 8) + (7 - bit) + 1
                    supported.add(String.format(Locale.US, "%02X", pidNumber))
                }
            }
        }
        return supported
    }

    /**
     * Parses PID 0101 response into (milOn, reportedDtcCount).
     */
    fun parseMilStatus(raw: String): Pair<Boolean, Int>? {
        val data = extractMode01Bytes(raw, "01") ?: return null
        if (data.isEmpty()) return null
        val byteA = data[0]
        val milOn = (byteA and 0x80) != 0
        val count = byteA and 0x7F
        return Pair(milOn, count)
    }

    /**
     * Parses DTC responses for Mode 03 ("43"), Mode 07 ("47"), or Mode 0A ("4A").
     *
     * Handles:
     * - K-Line (ISO 9141-2 / KWP2000): no count byte after mode byte; multi-line repeats mode byte.
     * - CAN (ISO 15765-4): 1-byte DTC count after mode byte; supports single-frame and ISO-TP multi-frame (`0:`, `1:`, ...).
     * - If `isCanProtocol == null`, automatically infers CAN vs K-Line from ISO-TP prefixes or byte parity.
     */
    fun parseDtcs(
        raw: String,
        expectedModeResponseHex: String = "43",
        isCanProtocol: Boolean? = null
    ): List<String> {
        if (hasError(raw)) return emptyList()
        val expectedModeByte = expectedModeResponseHex.uppercase(Locale.US).toIntOrNull(16) ?: return emptyList()
        val lines = cleanResponse(raw)
        if (lines.isEmpty()) return emptyList()

        val codes = LinkedHashSet<String>()

        // Check for ISO-TP multi-frame format (lines like "0: 43 06 ..." or "1: 03 00 ...")
        val isoTpFrames = lines.mapNotNull { line ->
            val match = ISOTOP_LINE_REGEX.matchEntire(line)
            if (match != null) {
                val seq = match.groupValues[1].toInt(16)
                val bytes = hexLineToBytes(match.groupValues[2])
                if (bytes != null) Pair(seq, bytes) else null
            } else {
                null
            }
        }

        if (isoTpFrames.isNotEmpty()) {
            val combined = isoTpFrames
                .sortedBy { it.first }
                .flatMap { it.second.toList() }
                .toIntArray()
            decodeDtcStream(combined, expectedModeByte, hasCountByte = true, outCodes = codes)
            return codes.toList()
        }

        // Standard single/multi-line responses
        for (line in lines) {
            val bytes = hexLineToBytes(line) ?: continue
            // Skip CAN ISO-TP byte-length header line (e.g. "014" has 3 chars so hexLineToBytes already returns null,
            // but guard against 1-byte length headers if any)
            val modeIndex = bytes.indexOfFirst { it == expectedModeByte }
            if (modeIndex < 0) continue

            val payload = bytes.copyOfRange(modeIndex, bytes.size)
            val remaining = payload.size - 1
            if (remaining <= 0) continue

            val hasCountByte = when (isCanProtocol) {
                true -> true
                false -> false
                null -> {
                    // CAN single frame has 1 count byte + 2*N DTC bytes => odd remaining count.
                    // K-Line has 2*N DTC bytes => even remaining count.
                    remaining % 2 == 1
                }
            }
            decodeDtcStream(payload, expectedModeByte, hasCountByte = hasCountByte, outCodes = codes)
        }

        return codes.toList()
    }

    private fun decodeDtcStream(
        bytes: IntArray,
        expectedModeByte: Int,
        hasCountByte: Boolean,
        outCodes: MutableSet<String>
    ) {
        val modeIndex = bytes.indexOfFirst { it == expectedModeByte }
        if (modeIndex < 0) return

        var cursor = modeIndex + 1
        var maxDtcs = Int.MAX_VALUE

        if (hasCountByte) {
            if (cursor >= bytes.size) return
            maxDtcs = bytes[cursor]
            cursor += 1
            if (maxDtcs == 0) return
        }

        var decoded = 0
        while (cursor + 1 < bytes.size && decoded < maxDtcs) {
            val b1 = bytes[cursor]
            val b2 = bytes[cursor + 1]
            cursor += 2
            decoded += 1

            if (b1 == 0 && b2 == 0) continue
            val code = decodeDtcPair(b1, b2)
            if (code != "P0000") {
                outCodes.add(code)
            }
        }
    }

    fun decodeDtcPair(b1: Int, b2: Int): String {
        val prefix = when ((b1 ushr 6) and 0x03) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val d1 = (b1 ushr 4) and 0x03
        val d2 = b1 and 0x0F
        val d3 = (b2 ushr 4) and 0x0F
        val d4 = b2 and 0x0F
        return String.format(Locale.US, "%c%d%X%X%X", prefix, d1, d2, d3, d4)
    }

    private fun IntArray.indexOfFirst(predicate: (Int) -> Boolean): Int {
        for (i in indices) {
            if (predicate(this[i])) return i
        }
        return -1
    }
}
