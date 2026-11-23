package com.cl600.obd.trips

import com.cl600.obd.model.TripMetric
import com.cl600.obd.model.TripSample
import com.cl600.obd.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStatsAndCsvTest {

    @Test
    fun computeSummary_aggregatesBoostDistancePowerAndThermals() {
        val t0 = 1_700_000_000_000L
        val samples = listOf(
            TripSample(
                elapsedMs = 0L,
                boostPsi = -8.0f,
                iatCelsius = 30f,
                coolantCelsius = 88f,
                rpm = 700,
                speedKmh = 0,
                estimatedHp = 18f,
                estimatedTorqueLbFt = 135f,
                throttlePercent = 12f,
                loadPercent = 20f,
                timingAdvanceDeg = 18f,
                voltage = 13.9f,
                wmiActive = false
            ),
            TripSample(
                elapsedMs = 3_600_000L / 2L, // 30 minutes
                boostPsi = 12.0f,
                iatCelsius = 45f,
                coolantCelsius = 92f,
                rpm = 4800,
                speedKmh = 100,
                estimatedHp = 540f,
                estimatedTorqueLbFt = 610f,
                throttlePercent = 98f,
                loadPercent = 95f,
                timingAdvanceDeg = 14f,
                voltage = 13.7f,
                wmiActive = true
            ),
            TripSample(
                elapsedMs = 3_600_000L, // 60 minutes
                boostPsi = 4.0f,
                iatCelsius = 50f,
                coolantCelsius = 94f,
                rpm = 3200,
                speedKmh = 100,
                estimatedHp = 260f,
                estimatedTorqueLbFt = 425f,
                throttlePercent = 55f,
                loadPercent = 64f,
                timingAdvanceDeg = 26f,
                voltage = 13.8f,
                wmiActive = true
            )
        )

        val summary = TripStatsCalculator.computeSummary(
            id = "trip_1",
            title = "Test Pull",
            startTimeMs = t0,
            endTimeMs = t0 + 3_600_000L,
            isDemo = false,
            samples = samples
        )

        assertEquals(3, summary.sampleCount)
        assertEquals(3_600_000L, summary.durationMs)
        // First 30m: avg(0,100)*0.5h = 25km. Second 30m: avg(100,100)*0.5h = 50km. Total = 75km.
        assertEquals(75.0f, summary.distanceKm, 0.05f)
        assertEquals(12.0f, summary.peakBoostPsi!!, 0.01f)
        // Boost intervals: [0..30m] mixed -> 15m; [30m..60m] both >0 -> 30m => 45m = 75%
        assertEquals(2_700_000L, summary.timeInBoostMs)
        assertEquals(75.0f, summary.boostPercentOfTrip, 0.1f)
        assertEquals(540f, summary.peakHp!!, 0.01f)
        assertEquals(610f, summary.peakTorqueLbFt!!, 0.01f)
        assertEquals(4800, summary.peakRpm)
        assertEquals(100, summary.maxSpeedKmh)
        assertEquals(30f, summary.minIatCelsius!!, 0.01f)
        assertEquals(50f, summary.peakIatCelsius!!, 0.01f)
        assertEquals(94f, summary.peakCoolantCelsius!!, 0.01f)
        assertEquals(13.7f, summary.minVoltage!!, 0.01f)
        assertEquals(14f, summary.minTimingDeg!!, 0.01f)
        assertEquals(26f, summary.maxTimingDeg!!, 0.01f)
        assertTrue(summary.insightText.contains("Peak boost"))
        assertTrue(summary.insightText.contains("WMI active"))
    }

    @Test
    fun metricNormalizationAndFormatting_clampsAndConvertsUnits() {
        val sample = TripSample(
            elapsedMs = 1500L,
            boostPsi = 5.0f, // midpoint of -15..25 -> 0.5
            iatCelsius = 52.5f,
            coolantCelsius = 90f,
            rpm = 3500, // midpoint of 500..6500 -> 0.5
            speedKmh = 130, // midpoint of 0..260 -> 0.5
            estimatedHp = 325f, // midpoint of 0..650 -> 0.5
            estimatedTorqueLbFt = 375f,
            throttlePercent = 50f,
            loadPercent = 80f,
            timingAdvanceDeg = 20f,
            voltage = null,
            wmiActive = true
        )

        assertEquals(0.5f, TripMetric.BOOST.normalize(sample)!!, 0.01f)
        assertEquals(0.5f, TripMetric.RPM.normalize(sample)!!, 0.01f)
        assertEquals(0.5f, TripMetric.SPEED.normalize(sample)!!, 0.01f)
        assertEquals(0.5f, TripMetric.POWER.normalize(sample)!!, 0.01f)
        assertNull(TripMetric.VOLTAGE.normalize(sample))

        assertTrue(TripMetric.BOOST.formatValue(sample, UnitSystem.IMPERIAL).contains("PSI"))
        assertTrue(TripMetric.BOOST.formatValue(sample, UnitSystem.METRIC).contains("BAR"))
        assertEquals("--", TripMetric.VOLTAGE.formatValue(sample, UnitSystem.IMPERIAL))
    }

    @Test
    fun csvCodec_roundTripsSamplesAndNulls() {
        val original = listOf(
            TripSample(
                elapsedMs = 0L,
                boostPsi = -9.4f,
                iatCelsius = 34f,
                coolantCelsius = 91f,
                rpm = 720,
                speedKmh = 0,
                estimatedHp = 22.5f,
                estimatedTorqueLbFt = 164f,
                throttlePercent = 11.5f,
                loadPercent = 21f,
                timingAdvanceDeg = 19.5f,
                voltage = 13.8f,
                wmiActive = false
            ),
            TripSample(
                elapsedMs = 260L,
                boostPsi = 13.2f,
                iatCelsius = 48f,
                coolantCelsius = null,
                rpm = 5120,
                speedKmh = 142,
                estimatedHp = 562f,
                estimatedTorqueLbFt = 576.5f,
                throttlePercent = 99f,
                loadPercent = 97f,
                timingAdvanceDeg = 13.5f,
                voltage = null,
                wmiActive = true
            )
        )

        val csv = TripCsvCodec.encode(original)
        val decoded = TripCsvCodec.decode(csv)
        assertEquals(2, decoded.size)
        assertEquals(original[0], decoded[0])
        assertEquals(original[1], decoded[1])
    }
}
