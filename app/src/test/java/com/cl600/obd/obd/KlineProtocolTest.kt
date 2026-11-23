package com.cl600.obd.obd

import com.cl600.obd.model.LogLevel
import com.cl600.obd.model.ObdProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KlineProtocolTest {

    @Test
    fun checksumAndFrameBuilders_matchIso9141AndKwp2000Specs() {
        // Mode 03 ISO 9141-2: [0x68, 0x6A, 0xF1, 0x03, 0xC6]
        val isoMode03 = KlineProtocol.buildIso9141Request(byteArrayOf(0x03))
        assertArrayEquals(
            byteArrayOf(0x68, 0x6A, 0xF1.toByte(), 0x03, 0xC6.toByte()),
            isoMode03
        )

        // Mode 04 ISO 9141-2: [0x68, 0x6A, 0xF1, 0x04, 0xC7]
        val isoMode04 = KlineProtocol.buildIso9141Request(byteArrayOf(0x04))
        assertArrayEquals(
            byteArrayOf(0x68, 0x6A, 0xF1.toByte(), 0x04, 0xC7.toByte()),
            isoMode04
        )

        // Mode 01 PID 0C ISO 9141-2: [0x68, 0x6A, 0xF1, 0x01, 0x0C, 0xD0]
        val isoRpm = KlineProtocol.buildIso9141Request(byteArrayOf(0x01, 0x0C))
        assertArrayEquals(
            byteArrayOf(0x68, 0x6A, 0xF1.toByte(), 0x01, 0x0C, 0xD0.toByte()),
            isoRpm
        )

        // KWP2000 StartCommunication: [0xC1, 0x33, 0xF1, 0x81, 0x66]
        assertArrayEquals(
            byteArrayOf(0xC1.toByte(), 0x33, 0xF1.toByte(), 0x81.toByte(), 0x66),
            KlineProtocol.buildKwpStartCommRequest()
        )

        // KWP2000 Mode 01 PID 0C: [0xC2, 0x33, 0xF1, 0x01, 0x0C, 0xF3]
        val kwpRpm = KlineProtocol.buildKwp2000Request(byteArrayOf(0x01, 0x0C))
        assertArrayEquals(
            byteArrayOf(0xC2.toByte(), 0x33, 0xF1.toByte(), 0x01, 0x0C, 0xF3.toByte()),
            kwpRpm
        )

        // KWP2000 Mode 03: [0xC1, 0x33, 0xF1, 0x03, 0xE8]
        val kwpMode03 = KlineProtocol.buildKwp2000Request(byteArrayOf(0x03))
        assertArrayEquals(
            byteArrayOf(0xC1.toByte(), 0x33, 0xF1.toByte(), 0x03, 0xE8.toByte()),
            kwpMode03
        )
    }

    @Test
    fun fiveBaudBitSequenceAndKeywordDetection() {
        // 0x33 LSB-first: Start(0=true), 1,1,0,0,1,1,0,0, Stop(1=false)
        assertEquals(
            listOf(true, false, false, true, true, false, false, true, true, false),
            KlineProtocol.fiveBaudBitSequence(0x33)
        )

        assertEquals(KlineFraming.ISO_9141_2, KlineProtocol.detectFramingFromKeywords(0x08, 0x08))
        assertEquals(KlineFraming.ISO_9141_2, KlineProtocol.detectFramingFromKeywords(0x94, 0x94))
        assertEquals(KlineFraming.KWP_2000, KlineProtocol.detectFramingFromKeywords(0xE9, 0x8F))
        assertEquals(KlineFraming.KWP_2000, KlineProtocol.detectFramingFromKeywords(0xEF, 0x8F))
    }

    @Test
    fun extractIso9141AndKwp2000Frames_singleMultiAndBadChecksum() {
        val f1 = wrapIso9141(0x10, byteArrayOf(0x43, 0x03, 0x00, 0x03, 0x01, 0x03, 0x02))
        val f2 = wrapIso9141(0x10, byteArrayOf(0x43, 0x03, 0x07, 0x04, 0x10, 0x00, 0x00))
        val isoFrames = KlineProtocol.extractIso9141Frames(f1 + f2)
        assertEquals(2, isoFrames.size)
        assertEquals(0x10, isoFrames[0].sourceAddr)
        assertEquals("43 03 00 03 01 03 02", KlineProtocol.formatHex(isoFrames[0].payload))
        assertEquals("43 03 07 04 10 00 00", KlineProtocol.formatHex(isoFrames[1].payload))

        // Corrupt checksum should reject
        val corrupted = f1.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 0xFF).toByte() }
        assertTrue(KlineProtocol.extractIso9141Frames(corrupted).isEmpty())

        // KWP2000 concatenated frames + extended length frame
        val k1 = wrapKwp2000(0x10, byteArrayOf(0xC1.toByte(), 0xE9.toByte(), 0x8F.toByte()))
        val k2 = wrapKwp2000Extended(0x10, byteArrayOf(0x41, 0x0C, 0x1A, 0xF8.toByte()))
        val kwpFrames = KlineProtocol.extractKwp2000Frames(k1 + k2)
        assertEquals(2, kwpFrames.size)
        assertEquals("C1 E9 8F", KlineProtocol.formatHex(kwpFrames[0].payload))
        assertEquals("41 0C 1A F8", KlineProtocol.formatHex(kwpFrames[1].payload))
    }

    @Test
    fun parseManualHexCommand_validAndRejectedInputs() {
        assertArrayEquals(byteArrayOf(0x01, 0x0C), KlineProtocol.parseManualHexCommand("010C"))
        assertArrayEquals(byteArrayOf(0x01, 0x05), KlineProtocol.parseManualHexCommand("01 05"))
        assertArrayEquals(byteArrayOf(0x03), KlineProtocol.parseManualHexCommand("03"))
        assertNull(KlineProtocol.parseManualHexCommand("ATZ"))
        assertNull(KlineProtocol.parseManualHexCommand("ATDP"))
        assertNull(KlineProtocol.parseManualHexCommand("010")) // odd nibble
        assertNull(KlineProtocol.parseManualHexCommand("ZZ"))
    }

    @Test
    fun klineSession_iso9141FiveBaudInitPollDtcClearKeepAlive() = runTest {
        val clock = FakeKlineClock()
        val port = FakeEcuKlinePort(
            clock = clock,
            mode = FakeEcuKlinePort.EcuMode.ISO9141_SLOW_ONLY,
            injectBreakZeroNoise = true
        )
        val logs = ArrayList<Pair<LogLevel, String>>()
        val session = KlineSession(
            port = port,
            protocolPref = ObdProtocol.ISO_9141_3,
            logger = { lvl, msg -> logs.add(lvl to msg) },
            clock = clock
        )

        val init = session.initialize()
        assertTrue(port.lowLatencyEnabled)
        assertFalse(init.isCanProtocol)
        assertTrue(init.activeProtocol.contains("ISO 9141-2"))
        assertEquals(101f, init.baroKpa!!, 0.01f)
        assertTrue(init.milOn)
        assertEquals(2, init.dtcCountReported)
        assertTrue("0B" in init.supportedPids)
        assertTrue("0C" in init.supportedPids)

        // Poll cycles
        val snap0 = session.pollCycle(0)
        assertEquals(188f, snap0.mapKpa!!, 0.01f)
        assertEquals(1726, snap0.rpm)
        assertEquals(45f, snap0.iatCelsius!!, 0.01f)

        val snap1 = session.pollCycle(1)
        assertEquals(90f, snap1.coolantCelsius!!, 0.01f)

        val snap7 = session.pollCycle(7)
        assertEquals(13.9f, snap7.voltage!!, 0.01f)

        // Multi-frame DTC scan (Mode 03 + Mode 07)
        val (milOn, _, dtcs) = session.scanDtcs()
        assertTrue(milOn)
        assertEquals(listOf("P0300", "P0307", "P0410", "P0113"), dtcs.map { it.code })

        // Mode 04 clear
        assertTrue(session.clearDtcs())
        val (milAfter, _, dtcsAfter) = session.scanDtcs()
        assertFalse(milAfter)
        assertTrue(dtcsAfter.isEmpty())

        // Keep-alive idle behavior (<2000ms skips, >=2000ms sends 0105)
        val txBeforeKeepAlive = port.txPayloadCount
        assertTrue(session.keepAliveIfIdle())
        assertEquals(txBeforeKeepAlive, port.txPayloadCount)
        clock.advanceMs(2100L)
        assertTrue(session.keepAliveIfIdle())
        assertEquals(txBeforeKeepAlive + 1, port.txPayloadCount)

        // Manual raw hex + AT guidance
        val atMsg = session.sendRawCommand("ATZ")
        assertTrue(atMsg.contains("no ELM327"))
        val raw010c = session.sendRawCommand("01 0C")
        assertTrue(raw010c.contains("41 0C 1A F8"))

        session.close()
        assertFalse(session.isConnected)
    }

    @Test
    fun klineSession_kwpFastInitAndAutoFallback() = runTest {
        // 1. Direct KWP2000 Fast Init in AUTO mode
        val kwpClock = FakeKlineClock()
        val kwpPort = FakeEcuKlinePort(
            clock = kwpClock,
            mode = FakeEcuKlinePort.EcuMode.KWP_FAST_AND_SLOW
        )
        val kwpSession = KlineSession(
            port = kwpPort,
            protocolPref = ObdProtocol.AUTO_WITH_KLINE_FALLBACK,
            logger = { _, _ -> },
            clock = kwpClock
        )
        val kwpInit = kwpSession.initialize()
        assertTrue(kwpInit.activeProtocol.contains("KWP2000"))
        assertTrue(kwpPort.fastInitAttempted)
        assertFalse(kwpPort.fiveBaudAttempted)
        val kwpSnap = kwpSession.pollCycle(0)
        assertEquals(188f, kwpSnap.mapKpa!!, 0.01f)
        assertEquals(1726, kwpSnap.rpm)
        kwpSession.close()

        // 2. AUTO mode falling back from unanswered KWP Fast Init -> 5-Baud Slow Init
        val fallbackClock = FakeKlineClock()
        val fallbackPort = FakeEcuKlinePort(
            clock = fallbackClock,
            mode = FakeEcuKlinePort.EcuMode.ISO9141_SLOW_ONLY,
            injectBreakZeroNoise = true
        )
        val logs = ArrayList<String>()
        val fallbackSession = KlineSession(
            port = fallbackPort,
            protocolPref = ObdProtocol.AUTO_WITH_KLINE_FALLBACK,
            logger = { _, m -> logs.add(m) },
            clock = fallbackClock
        )
        val fbInit = fallbackSession.initialize()
        assertTrue(fallbackPort.fastInitAttempted)
        assertTrue(fallbackPort.fiveBaudAttempted)
        assertTrue(fbInit.activeProtocol.contains("ISO 9141-2"))
        assertNotNull(logs.firstOrNull { it.contains("trying ISO 9141 5-baud slow init", ignoreCase = true) })
        fallbackSession.close()
    }

    private fun wrapIso9141(ecuAddr: Int, payload: ByteArray): ByteArray {
        val raw = ByteArray(3 + payload.size + 1)
        raw[0] = 0x48
        raw[1] = 0x6B
        raw[2] = ecuAddr.toByte()
        System.arraycopy(payload, 0, raw, 3, payload.size)
        raw[raw.lastIndex] = KlineProtocol.computeChecksum(raw, raw.size - 1)
        return raw
    }

    private fun wrapKwp2000(ecuAddr: Int, payload: ByteArray): ByteArray {
        val raw = ByteArray(3 + payload.size + 1)
        raw[0] = (0x80 or payload.size).toByte()
        raw[1] = 0xF1.toByte()
        raw[2] = ecuAddr.toByte()
        System.arraycopy(payload, 0, raw, 3, payload.size)
        raw[raw.lastIndex] = KlineProtocol.computeChecksum(raw, raw.size - 1)
        return raw
    }

    private fun wrapKwp2000Extended(ecuAddr: Int, payload: ByteArray): ByteArray {
        val raw = ByteArray(4 + payload.size + 1)
        raw[0] = 0x80.toByte()
        raw[1] = 0xF1.toByte()
        raw[2] = ecuAddr.toByte()
        raw[3] = payload.size.toByte()
        System.arraycopy(payload, 0, raw, 4, payload.size)
        raw[raw.lastIndex] = KlineProtocol.computeChecksum(raw, raw.size - 1)
        return raw
    }
}

private class FakeKlineClock : KlineClock {
    private var timeMs = 10_000L
    private var nanos = 10_000_000_000L

    override fun nowMs(): Long = timeMs
    override fun nanoTime(): Long = nanos

    override fun sleepMs(ms: Long) {
        advanceMs(ms)
    }

    override fun sleepUntilNanos(targetNanos: Long) {
        if (targetNanos > nanos) {
            val deltaNs = targetNanos - nanos
            nanos = targetNanos
            timeMs += (deltaNs / 1_000_000L).coerceAtLeast(1L)
        }
    }

    fun advanceMs(ms: Long) {
        if (ms > 0L) {
            timeMs += ms
            nanos += ms * 1_000_000L
        }
    }
}

private class FakeEcuKlinePort(
    private val clock: FakeKlineClock,
    private val mode: EcuMode,
    private val injectBreakZeroNoise: Boolean = false
) : KlineSerialPort {

    enum class EcuMode {
        ISO9141_SLOW_ONLY,
        KWP_FAST_AND_SLOW
    }

    override val displayName: String = "Fake Galletto FTDI"
    override val vendorId: Int = 0x0403
    override val productId: Int = 0x6001
    override var isOpen: Boolean = false
        private set

    var lowLatencyEnabled: Boolean = false
        private set
    var fastInitAttempted: Boolean = false
        private set
    var fiveBaudAttempted: Boolean = false
        private set
    var txPayloadCount: Int = 0
        private set

    private val rxQueue = ArrayDeque<Byte>()
    private val breakTransitions = ArrayList<Boolean>()
    private var awaitingInvKw2 = false
    private var syncQueuedForAttempt = false
    private var dtcsCleared = false

    private val txBuffer = ArrayList<Byte>()

    override fun open() {
        isOpen = true
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {}

    override fun setLatencyTimer(latencyMs: Int): Boolean {
        lowLatencyEnabled = latencyMs == 1
        return true
    }

    override fun setBreak(enabled: Boolean) {
        breakTransitions.add(enabled)
        // Detect Fast Init ~25ms pulse vs 5-baud 0x33 sequence
        if (!enabled && breakTransitions.size == 2) {
            // Will be followed immediately by StartComm if Fast Init
        }
        // Detect 5-baud 0x33 completion (11 states: initial HIGH + 10 bits ending with HIGH)
        if (!enabled && breakTransitions.count { it } >= 4 && breakTransitions.size >= 8) {
            fiveBaudAttempted = true
            awaitingInvKw2 = true
            syncQueuedForAttempt = false
            if (injectBreakZeroNoise) {
                rxQueue.addLast(0x00) // Pre-purge break artifact before purgeHwBuffers
            }
        }
    }

    override fun setRTS(enabled: Boolean) {}
    override fun setDTR(enabled: Boolean) {}

    override fun purgeHwBuffers(purgeWrite: Boolean, purgeRead: Boolean) {
        if (purgeRead) {
            rxQueue.clear()
            if (fiveBaudAttempted && awaitingInvKw2 && !syncQueuedForAttempt) {
                syncQueuedForAttempt = true
                // ECU replies [0x55, 0x08, 0x08] (with optional post-purge 0x00 noise) shortly after Stop bit
                if (injectBreakZeroNoise) {
                    rxQueue.addLast(0x00)
                }
                rxQueue.addLast(0x55)
                rxQueue.addLast(0x08)
                rxQueue.addLast(0x08)
            }
        }
    }

    override fun write(data: ByteArray, timeoutMs: Int) {
        if (awaitingInvKw2 && data.size == 1 && (data[0].toInt() and 0xFF) == 0xF7) {
            awaitingInvKw2 = false
            // Half-duplex echo of ~KW2 + ECU 0xCC ack
            rxQueue.addLast(data[0])
            rxQueue.addLast(0xCC.toByte())
            return
        }

        // Half-duplex K-Line echo of every TX byte
        for (b in data) {
            rxQueue.addLast(b)
            txBuffer.add(b)
        }

        maybeRespondToBufferedFrame()
    }

    private fun maybeRespondToBufferedFrame() {
        val raw = txBuffer.toByteArray()
        if (raw.size < 5) return

        // Verify complete ISO 9141-2 or KWP2000 request checksum before replying
        val b0 = raw[0].toInt() and 0xFF
        val expectedCs = KlineProtocol.computeChecksum(raw, raw.size - 1)
        if (raw.last() != expectedCs) return

        val payload: ByteArray
        val useKwpResponse: Boolean
        if (b0 == 0x68 && (raw[1].toInt() and 0xFF) == 0x6A && (raw[2].toInt() and 0xFF) == 0xF1) {
            payload = raw.copyOfRange(3, raw.size - 1)
            useKwpResponse = false
        } else if ((b0 and 0xC0) == 0xC0 && raw.size == 3 + (b0 and 0x3F) + 1) {
            payload = raw.copyOfRange(3, raw.size - 1)
            useKwpResponse = true
        } else {
            return
        }

        txBuffer.clear()
        txPayloadCount++

        val sid = payload[0].toInt() and 0xFF
        if (sid == 0x81) {
            fastInitAttempted = true
            if (mode == EcuMode.KWP_FAST_AND_SLOW) {
                enqueueResponse(byteArrayOf(0xC1.toByte(), 0xE9.toByte(), 0x8F.toByte()), kwp = true)
            }
            return
        }

        val replies = buildEcuReplies(payload)
        for (rep in replies) {
            enqueueResponse(rep, kwp = useKwpResponse)
        }
    }

    private fun buildEcuReplies(payload: ByteArray): List<ByteArray> {
        val sid = payload[0].toInt() and 0xFF
        val pid = if (payload.size > 1) (payload[1].toInt() and 0xFF) else -1
        return when (sid) {
            0x01 -> when (pid) {
                0x00 -> listOf(byteArrayOf(0x41, 0x00, 0x98.toByte(), 0x3B, 0x80.toByte(), 0x11))
                0x20 -> listOf(byteArrayOf(0x41, 0x20, 0x00, 0x00, 0x20, 0x01))
                0x40 -> listOf(byteArrayOf(0x41, 0x40, 0x40, 0x00, 0x00, 0x00))
                0x01 -> {
                    val countByte = if (dtcsCleared) 0x00 else 0x82.toByte()
                    listOf(byteArrayOf(0x41, 0x01, countByte, 0x07, 0x65, 0x00))
                }
                0x04 -> listOf(byteArrayOf(0x41, 0x04, 0x80.toByte()))
                0x05 -> listOf(byteArrayOf(0x41, 0x05, 0x82.toByte())) // 90C
                0x0B -> listOf(byteArrayOf(0x41, 0x0B, 0xBC.toByte())) // 188 kPa
                0x0C -> listOf(byteArrayOf(0x41, 0x0C, 0x1A, 0xF8.toByte())) // 1726 RPM
                0x0D -> listOf(byteArrayOf(0x41, 0x0D, 0x70)) // 112 km/h
                0x0E -> listOf(byteArrayOf(0x41, 0x0E, 0xA0.toByte())) // +16 deg
                0x0F -> listOf(byteArrayOf(0x41, 0x0F, 0x55)) // 45C
                0x11 -> listOf(byteArrayOf(0x41, 0x11, 0xFF.toByte())) // 100%
                0x33 -> listOf(byteArrayOf(0x41, 0x33, 0x65)) // 101 kPa
                0x42 -> listOf(byteArrayOf(0x41, 0x42, 0x36, 0x4C)) // 13.9V
                else -> emptyList()
            }
            0x03 -> if (dtcsCleared) {
                listOf(byteArrayOf(0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            } else {
                listOf(
                    byteArrayOf(0x43, 0x03, 0x00, 0x03, 0x07, 0x04, 0x10),
                    byteArrayOf(0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
                )
            }
            0x07 -> if (dtcsCleared) {
                listOf(byteArrayOf(0x47, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            } else {
                listOf(byteArrayOf(0x47, 0x01, 0x13, 0x00, 0x00, 0x00, 0x00))
            }
            0x04 -> {
                dtcsCleared = true
                listOf(byteArrayOf(0x44))
            }
            else -> emptyList()
        }
    }

    private fun enqueueResponse(payload: ByteArray, kwp: Boolean) {
        val frame = if (kwp) {
            val out = ByteArray(3 + payload.size + 1)
            out[0] = (0x80 or payload.size).toByte()
            out[1] = 0xF1.toByte()
            out[2] = 0x10
            System.arraycopy(payload, 0, out, 3, payload.size)
            out[out.lastIndex] = KlineProtocol.computeChecksum(out, out.size - 1)
            out
        } else {
            val out = ByteArray(3 + payload.size + 1)
            out[0] = 0x48
            out[1] = 0x6B
            out[2] = 0x10
            System.arraycopy(payload, 0, out, 3, payload.size)
            out[out.lastIndex] = KlineProtocol.computeChecksum(out, out.size - 1)
            out
        }
        for (b in frame) rxQueue.addLast(b)
    }

    override fun read(dest: ByteArray, timeoutMs: Int): Int {
        if (rxQueue.isEmpty()) {
            clock.advanceMs(timeoutMs.toLong())
            return 0
        }
        var count = 0
        while (count < dest.size && rxQueue.isNotEmpty()) {
            dest[count++] = rxQueue.removeFirst()
        }
        return count
    }

    override fun close() {
        isOpen = false
    }
}
