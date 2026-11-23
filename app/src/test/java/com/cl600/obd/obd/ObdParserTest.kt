package com.cl600.obd.obd

import com.cl600.obd.model.DtcCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {

    @Test
    fun parseMapAndBoost_imperialAndAltitude() {
        val baro = ObdParser.parseBaroKpa("41 33 65") // 101 kPa
        assertEquals(101f, baro!!, 0.01f)

        val mapBoost = ObdParser.parseMapKpa("41 0B BC") // 188 kPa
        assertEquals(188f, mapBoost!!, 0.01f)
        val psi = ObdParser.calculateBoostPsi(mapBoost, baro)
        assertEquals(12.618f, psi, 0.05f)

        val mapVac = ObdParser.parseMapKpa("410B26") // 38 kPa
        assertEquals(38f, mapVac!!, 0.01f)
        val vacPsi = ObdParser.calculateBoostPsi(mapVac, baro)
        assertEquals(-9.137f, vacPsi, 0.05f)

        // Custom Denver baro 84 kPa
        val denverBoost = ObdParser.calculateBoostPsi(171f, 84f)
        assertEquals(12.618f, denverBoost, 0.05f)
    }

    @Test
    fun parseCoreM275Pids_spacedAndSpaceless() {
        assertEquals(45f, ObdParser.parseIatCelsius("41 0F 55")!!, 0.01f)
        assertEquals(90f, ObdParser.parseCoolantCelsius("410582")!!, 0.01f)
        assertEquals(1726, ObdParser.parseRpm("41 0C 1A F8"))
        assertEquals(112, ObdParser.parseSpeedKmh("410D70"))
        assertEquals(50.196f, ObdParser.parseLoadPercent("41 04 80")!!, 0.1f)
        assertEquals(16.0f, ObdParser.parseTimingAdvance("41 0E A0")!!, 0.01f)
        assertEquals(100.0f, ObdParser.parseThrottlePercent("41 11 FF")!!, 0.01f)
    }

    @Test
    fun parseVoltage_pid42AndAtrv() {
        assertEquals(13.9f, ObdParser.parseControlModuleVoltage("41 42 36 4C")!!, 0.001f)
        assertEquals(13.8f, ObdParser.parseAtrvVoltage("13.8V\r>")!!, 0.01f)
        assertEquals(12.4f, ObdParser.parseAtrvVoltage("ATRV\r12.4 V\r>")!!, 0.01f)
    }

    @Test
    fun parseNoisyCloneResponsesAndErrors() {
        val noisy = "010B\rSEARCHING...\rBUS INIT: ...OK\r41 0B BE\r\r>"
        assertEquals(190f, ObdParser.parseMapKpa(noisy)!!, 0.01f)

        assertNull(ObdParser.parseMapKpa("NO DATA"))
        assertNull(ObdParser.parseRpm("UNABLE TO CONNECT"))
        assertNull(ObdParser.parseIatCelsius("?"))
        assertTrue(ObdParser.hasError("BUS INIT: ...ERROR"))
    }

    @Test
    fun parseSupportedPids_bitmaskDecoding() {
        // 0x983B8011 = 1001 1000 | 0011 1011 | 1000 0000 | 0001 0001
        // Expected PIDs: 01, 04, 05, 0B, 0C, 0D, 0F, 10, 11, 1C, 20
        val pids = ObdParser.parseSupportedPids("4100983B8011", 0)
        assertTrue("01" in pids)
        assertTrue("04" in pids)
        assertTrue("05" in pids)
        assertTrue("0B" in pids)
        assertTrue("0C" in pids)
        assertTrue("0D" in pids)
        assertTrue("0F" in pids)
        assertTrue("11" in pids)
        assertTrue("20" in pids)
        assertFalse("02" in pids)
    }

    @Test
    fun parseMilStatus() {
        val milOn = ObdParser.parseMilStatus("41 01 82 07 65 00")
        assertNotNull(milOn)
        assertTrue(milOn!!.first)
        assertEquals(2, milOn.second)

        val milOff = ObdParser.parseMilStatus("410100076500")
        assertNotNull(milOff)
        assertFalse(milOff!!.first)
        assertEquals(0, milOff.second)
    }

    @Test
    fun parseDtcs_kLineSingleAndMultiLine() {
        val kLineTwo = ObdParser.parseDtcs("43 03 07 01 13 00 00", "43", isCanProtocol = false)
        assertEquals(listOf("P0307", "P0113"), kLineTwo)

        // Auto-detected K-Line (even remaining length)
        val kLineAuto = ObdParser.parseDtcs("43030701130000", "43", isCanProtocol = null)
        assertEquals(listOf("P0307", "P0113"), kLineAuto)

        // Multi-line K-Line (5 codes)
        val kLineMulti = ObdParser.parseDtcs(
            "43 03 00 03 01 03 02\r43 03 07 04 10 00 00\r>",
            "43",
            isCanProtocol = false
        )
        assertEquals(listOf("P0300", "P0301", "P0302", "P0307", "P0410"), kLineMulti)

        // Empty K-Line
        val kLineEmpty = ObdParser.parseDtcs("43 00 00 00 00 00 00", "43", isCanProtocol = false)
        assertTrue(kLineEmpty.isEmpty())

        // Mode 07 pending
        val pending = ObdParser.parseDtcs("47011304200000", "47", isCanProtocol = false)
        assertEquals(listOf("P0113", "P0420"), pending)
    }

    @Test
    fun parseDtcs_canSingleAndIsoTpMultiFrame() {
        val canEmpty = ObdParser.parseDtcs("43 00", "43", isCanProtocol = true)
        assertTrue(canEmpty.isEmpty())

        val canTwo = ObdParser.parseDtcs("43 02 03 07 01 13", "43", isCanProtocol = null)
        assertEquals(listOf("P0307", "P0113"), canTwo)

        val isoTp = ObdParser.parseDtcs(
            "012\r0: 43 05 03 00 03 07\r1: 03 12 01 13 04 10 00\r>",
            "43",
            isCanProtocol = true
        )
        assertEquals(listOf("P0300", "P0307", "P0312", "P0113", "P0410"), isoTp)
    }

    @Test
    fun decodeDtcPrefixes_allCategories() {
        assertEquals("P0307", ObdParser.decodeDtcPair(0x03, 0x07))
        assertEquals("C0123", ObdParser.decodeDtcPair(0x41, 0x23))
        assertEquals("B1234", ObdParser.decodeDtcPair(0x92, 0x34))
        assertEquals("U0100", ObdParser.decodeDtcPair(0xC1, 0x00))
    }

    @Test
    fun dtcCatalog_m275V12CoverageAndFallback() {
        val cyl12 = DtcCatalog.lookup("p0312", DtcCategory.STORED)
        assertEquals("P0312", cyl12.code)
        assertTrue(cyl12.description.contains("Cylinder 12"))
        assertNotNull(cyl12.m275Note)
        assertTrue(cyl12.m275Note!!.contains("coil", ignoreCase = true))

        val secAir = DtcCatalog.lookup("P0410", DtcCategory.PENDING)
        assertNotNull(secAir.m275Note)

        val unknownMb = DtcCatalog.lookup("P1234", DtcCategory.STORED)
        assertTrue(unknownMb.description.contains("Mercedes-Benz"))
    }

    @Test
    fun estimatePowerAndTorque_m275SpeedDensity() {
        // Stock 2003 CL600 M275 pull (~12.1 PSI boost, 45C IAT, 5000 RPM)
        val (hpPull, tqPull) = ObdParser.estimatePowerAndTorque(
            mapKpa = 185f,
            iatCelsius = 45f,
            rpm = 5000
        )
        assertTrue("Expected ~480-510 HP, got $hpPull", hpPull in 480f..515f)
        assertTrue("Expected ~500-540 lb-ft, got $tqPull", tqPull in 500f..540f)

        // Midrange torque (~13.6 PSI boost, 40C IAT, 3000 RPM)
        val (_, tqMid) = ObdParser.estimatePowerAndTorque(
            mapKpa = 195f,
            iatCelsius = 40f,
            rpm = 3000
        )
        assertTrue("Expected ~530-585 lb-ft, got $tqMid", tqMid in 530f..585f)

        // Engine off and decel fuel cut return zero
        assertEquals(Pair(0f, 0f), ObdParser.estimatePowerAndTorque(101f, 30f, 0))
        assertEquals(Pair(0f, 0f), ObdParser.estimatePowerAndTorque(22f, 35f, 2500))
    }
}
