package com.cl600.obd.obd

import java.util.Locale

enum class KlineFraming(val displayName: String) {
    ISO_9141_2("ISO 9141-2 (K-Line)"),
    KWP_2000("ISO 14230-4 KWP2000 (K-Line)")
}

data class KlineFrame(
    val sourceAddr: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KlineFrame) return false
        return sourceAddr == other.sourceAddr && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        return 31 * sourceAddr + payload.contentHashCode()
    }
}

object KlineProtocol {

    private val HEX_ONLY_REGEX = Regex("""^[0-9A-F]+$""")

    fun computeChecksum(bytes: ByteArray, length: Int = bytes.size): Byte {
        var sum = 0
        for (i in 0 until minOf(length, bytes.size)) {
            sum += (bytes[i].toInt() and 0xFF)
        }
        return (sum and 0xFF).toByte()
    }

    /**
     * Produces the 10 bit states (Start=0, Bit0..Bit7 LSB-first, Stop=1) for 5-baud wakeup.
     * Each entry is `true` when K-Line/L-Line should be pulled LOW (logical 0 / BREAK active).
     */
    fun fiveBaudBitSequence(addressByte: Int = 0x33): List<Boolean> {
        val bits = ArrayList<Boolean>(10)
        bits.add(true) // Start bit = 0 (LOW)
        for (bitIdx in 0 until 8) {
            val bitIsZero = ((addressByte ushr bitIdx) and 0x01) == 0
            bits.add(bitIsZero)
        }
        bits.add(false) // Stop bit = 1 (HIGH)
        return bits
    }

    fun detectFramingFromKeywords(kw1: Int, kw2: Int): KlineFraming {
        val k1 = kw1 and 0xFF
        val k2 = kw2 and 0xFF
        return when {
            k1 == 0x08 && k2 == 0x08 -> KlineFraming.ISO_9141_2
            k1 == 0x94 && k2 == 0x94 -> KlineFraming.ISO_9141_2
            k2 == 0x8F -> KlineFraming.KWP_2000
            else -> KlineFraming.ISO_9141_2
        }
    }

    fun buildIso9141Request(payload: ByteArray): ByteArray {
        val out = ByteArray(3 + payload.size + 1)
        out[0] = 0x68
        out[1] = 0x6A
        out[2] = 0xF1.toByte()
        System.arraycopy(payload, 0, out, 3, payload.size)
        out[out.lastIndex] = computeChecksum(out, out.size - 1)
        return out
    }

    fun buildKwp2000Request(
        payload: ByteArray,
        targetAddr: Byte = 0x33,
        physical: Boolean = false
    ): ByteArray {
        val modeBits = if (physical) 0x80 else 0xC0
        return if (payload.size in 1..63) {
            val out = ByteArray(3 + payload.size + 1)
            out[0] = (modeBits or payload.size).toByte()
            out[1] = targetAddr
            out[2] = 0xF1.toByte()
            System.arraycopy(payload, 0, out, 3, payload.size)
            out[out.lastIndex] = computeChecksum(out, out.size - 1)
            out
        } else {
            val out = ByteArray(4 + payload.size + 1)
            out[0] = modeBits.toByte()
            out[1] = targetAddr
            out[2] = 0xF1.toByte()
            out[3] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, out, 4, payload.size)
            out[out.lastIndex] = computeChecksum(out, out.size - 1)
            out
        }
    }

    fun buildKwpStartCommRequest(): ByteArray {
        return buildKwp2000Request(byteArrayOf(0x81.toByte()), targetAddr = 0x33, physical = false)
    }

    /**
     * Parses one or more ISO 9141-2 response frames:
     * `[0x48, 0x6B, <ECU_ADDR>, <Payload...>, <Checksum>]`
     */
    fun extractIso9141Frames(rxBytes: ByteArray): List<KlineFrame> {
        val frames = ArrayList<KlineFrame>()
        parseIso9141Recursive(rxBytes, 0, frames)
        return frames
    }

    private fun parseIso9141Recursive(rx: ByteArray, startPos: Int, out: MutableList<KlineFrame>): Boolean {
        var pos = startPos
        while (pos + 4 < rx.size) {
            if ((rx[pos].toInt() and 0xFF) == 0x48 && (rx[pos + 1].toInt() and 0xFF) == 0x6B) {
                break
            }
            pos++
        }
        if (pos + 4 >= rx.size) return out.isNotEmpty()

        // Try frame end candidates from smallest valid size (pos + 5) to rx.size
        for (end in (pos + 5)..rx.size) {
            val expectedCs = computeChecksumSlice(rx, pos, end - 1)
            val actualCs = rx[end - 1].toInt() and 0xFF
            if (expectedCs != actualCs) continue

            val frame = KlineFrame(
                sourceAddr = rx[pos + 2].toInt() and 0xFF,
                payload = rx.copyOfRange(pos + 3, end - 1)
            )
            if (end == rx.size) {
                out.add(frame)
                return true
            }
            if ((end + 4 < rx.size) && (rx[end].toInt() and 0xFF) == 0x48 && (rx[end + 1].toInt() and 0xFF) == 0x6B) {
                val snapshotSize = out.size
                out.add(frame)
                if (parseIso9141Recursive(rx, end, out)) {
                    return true
                }
                while (out.size > snapshotSize) {
                    out.removeAt(out.lastIndex)
                }
            }
        }

        // Fall back to keeping the first longest checksummed frame if tail has trailing noise bytes
        for (end in rx.size downTo (pos + 5)) {
            val expectedCs = computeChecksumSlice(rx, pos, end - 1)
            val actualCs = rx[end - 1].toInt() and 0xFF
            if (expectedCs == actualCs) {
                out.add(
                    KlineFrame(
                        sourceAddr = rx[pos + 2].toInt() and 0xFF,
                        payload = rx.copyOfRange(pos + 3, end - 1)
                    )
                )
                return true
            }
        }
        return false
    }

    /**
     * Parses one or more ISO 14230-4 KWP2000 response frames (inline or extended length byte).
     */
    fun extractKwp2000Frames(rxBytes: ByteArray): List<KlineFrame> {
        val frames = ArrayList<KlineFrame>()
        var pos = 0
        while (pos + 2 < rxBytes.size) {
            val fmt = rxBytes[pos].toInt() and 0xFF
            val addrMode = (fmt ushr 6) and 0x03
            val inlineLen = fmt and 0x3F

            val addrHeaderLen = when (addrMode) {
                2, 3 -> 3
                0 -> 1
                else -> 1
            }

            if (pos + addrHeaderLen >= rxBytes.size) {
                pos++
                continue
            }

            val payloadStart: Int
            val payloadLen: Int
            if (inlineLen > 0) {
                payloadStart = pos + addrHeaderLen
                payloadLen = inlineLen
            } else {
                if (pos + addrHeaderLen + 1 > rxBytes.size) {
                    pos++
                    continue
                }
                payloadLen = rxBytes[pos + addrHeaderLen].toInt() and 0xFF
                payloadStart = pos + addrHeaderLen + 1
            }

            val frameEnd = payloadStart + payloadLen + 1
            if (payloadLen <= 0 || frameEnd > rxBytes.size) {
                pos++
                continue
            }

            val expectedCs = computeChecksumSlice(rxBytes, pos, frameEnd - 1)
            val actualCs = rxBytes[frameEnd - 1].toInt() and 0xFF
            if (expectedCs == actualCs) {
                val srcAddr = if (addrMode == 2 || addrMode == 3) {
                    rxBytes[pos + 2].toInt() and 0xFF
                } else {
                    0
                }
                frames.add(
                    KlineFrame(
                        sourceAddr = srcAddr,
                        payload = rxBytes.copyOfRange(payloadStart, payloadStart + payloadLen)
                    )
                )
                pos = frameEnd
            } else {
                pos++
            }
        }
        return frames
    }

    fun parseManualHexCommand(input: String): ByteArray? {
        val compact = input
            .trim()
            .replace(" ", "")
            .replace(":", "")
            .replace("-", "")
            .replace("0x", "", ignoreCase = true)
            .uppercase(Locale.US)

        if (compact.isEmpty() || compact.startsWith("AT")) return null
        if (compact.length < 2 || compact.length % 2 != 0) return null
        if (!HEX_ONLY_REGEX.matches(compact)) return null

        val out = ByteArray(compact.length / 2)
        for (i in out.indices) {
            out[i] = compact.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun formatHex(bytes: ByteArray, length: Int = bytes.size): String {
        val count = minOf(length, bytes.size)
        if (count <= 0) return ""
        val sb = StringBuilder(count * 3)
        for (i in 0 until count) {
            if (i > 0) sb.append(' ')
            sb.append(String.format(Locale.US, "%02X", bytes[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun computeChecksumSlice(bytes: ByteArray, from: Int, toExclusive: Int): Int {
        var sum = 0
        for (i in from until toExclusive) {
            sum += (bytes[i].toInt() and 0xFF)
        }
        return sum and 0xFF
    }
}
