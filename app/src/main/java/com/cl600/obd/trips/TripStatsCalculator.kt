package com.cl600.obd.trips

import com.cl600.obd.model.ObdTelemetry
import com.cl600.obd.model.TripSample
import com.cl600.obd.model.TripSummary
import com.cl600.obd.model.UnitSystem
import java.util.Locale
import kotlin.math.roundToInt

object TripStatsCalculator {

    fun computeSummary(
        id: String,
        title: String,
        startTimeMs: Long,
        endTimeMs: Long,
        isDemo: Boolean,
        samples: List<TripSample>
    ): TripSummary {
        val rawSpanMs = (endTimeMs - startTimeMs).coerceAtLeast(0L)
        val sampleSpanMs = samples.lastOrNull()?.elapsedMs?.coerceAtLeast(0L) ?: 0L
        val durationMs = maxOf(rawSpanMs, sampleSpanMs)

        var distanceKm = 0f
        var timeInBoostMs = 0L

        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            val dtMs = (curr.elapsedMs - prev.elapsedMs).coerceAtLeast(0L)
            if (dtMs <= 0L) continue

            val s0 = prev.speedKmh
            val s1 = curr.speedKmh
            if (s0 != null && s1 != null) {
                val avgKmh = (s0 + s1) * 0.5f
                distanceKm += avgKmh * (dtMs / 3_600_000f)
            }

            val b0Positive = (prev.boostPsi ?: -99f) > 0f
            val b1Positive = (curr.boostPsi ?: -99f) > 0f
            when {
                b0Positive && b1Positive -> timeInBoostMs += dtMs
                b0Positive || b1Positive -> timeInBoostMs += (dtMs / 2L)
            }
        }

        val effectiveTimeForBoostPct = if (durationMs > 0L) durationMs else sampleSpanMs
        val boostPct = if (effectiveTimeForBoostPct > 0L) {
            ((timeInBoostMs * 100f) / effectiveTimeForBoostPct.toFloat()).coerceIn(0f, 100f)
        } else {
            0f
        }

        val speeds = samples.mapNotNull { it.speedKmh?.toFloat() }
        val boosts = samples.mapNotNull { it.boostPsi }
        val hps = samples.mapNotNull { it.estimatedHp }
        val tqs = samples.mapNotNull { it.estimatedTorqueLbFt }
        val rpms = samples.mapNotNull { it.rpm?.toFloat() }
        val loads = samples.mapNotNull { it.loadPercent }
        val throttles = samples.mapNotNull { it.throttlePercent }
        val timings = samples.mapNotNull { it.timingAdvanceDeg }
        val iats = samples.mapNotNull { it.iatCelsius }
        val coolants = samples.mapNotNull { it.coolantCelsius }
        val volts = samples.mapNotNull { it.voltage }

        val peakBoostPsi = boosts.maxOrNull()
        val peakHp = hps.maxOrNull()
        val peakIatC = iats.maxOrNull()
        val minVolt = volts.minOrNull()

        val insight = buildInsightText(
            peakBoostPsi = peakBoostPsi,
            peakHp = peakHp,
            boostPct = boostPct,
            timeInBoostMs = timeInBoostMs,
            peakIatC = peakIatC,
            minVoltage = minVolt
        )

        return TripSummary(
            id = id,
            title = title,
            startTimeMs = startTimeMs,
            endTimeMs = startTimeMs + durationMs,
            durationMs = durationMs,
            sampleCount = samples.size,
            isDemo = isDemo,
            distanceKm = distanceKm,
            maxSpeedKmh = speeds.maxOrNull()?.roundToInt(),
            avgSpeedKmh = averageOrNull(speeds),
            peakBoostPsi = peakBoostPsi,
            avgBoostPsi = averageOrNull(boosts),
            timeInBoostMs = timeInBoostMs,
            boostPercentOfTrip = boostPct,
            peakHp = peakHp,
            peakTorqueLbFt = tqs.maxOrNull(),
            peakRpm = rpms.maxOrNull()?.roundToInt(),
            avgRpm = averageOrNull(rpms),
            maxLoadPercent = loads.maxOrNull(),
            maxThrottlePercent = throttles.maxOrNull(),
            minTimingDeg = timings.minOrNull(),
            maxTimingDeg = timings.maxOrNull(),
            minIatCelsius = iats.minOrNull(),
            avgIatCelsius = averageOrNull(iats),
            peakIatCelsius = peakIatC,
            peakCoolantCelsius = coolants.maxOrNull(),
            avgCoolantCelsius = averageOrNull(coolants),
            minVoltage = minVolt,
            avgVoltage = averageOrNull(volts),
            insightText = insight
        )
    }

    fun buildInsightText(
        peakBoostPsi: Float?,
        peakHp: Float?,
        boostPct: Float,
        timeInBoostMs: Long,
        peakIatC: Float?,
        minVoltage: Float?,
        unitSystem: UnitSystem = UnitSystem.IMPERIAL
    ): String {
        val parts = ArrayList<String>(4)
        if (peakBoostPsi != null) {
            val boostStr = if (unitSystem == UnitSystem.IMPERIAL) {
                String.format(Locale.US, "Peak boost %+.1f PSI", peakBoostPsi)
            } else {
                String.format(Locale.US, "Peak boost %+.2f BAR", ObdTelemetry.psiToBar(peakBoostPsi))
            }
            parts.add(boostStr)
        }
        if (peakHp != null && peakHp > 0f) {
            parts.add("Peak ${peakHp.roundToInt()} HP")
        }
        if (timeInBoostMs > 0L) {
            parts.add("WMI active ${boostPct.roundToInt()}% (${formatDuration(timeInBoostMs)})")
        } else {
            parts.add("Vacuum cruise (no WMI trigger)")
        }
        if (peakIatC != null) {
            val tempVal = ObdTelemetry.formatTemp(peakIatC, unitSystem).roundToInt()
            val unit = if (unitSystem == UnitSystem.IMPERIAL) "°F" else "°C"
            if (peakIatC >= 65f) {
                parts.add("High IAT heat-soak $tempVal$unit")
            } else {
                parts.add("Peak IAT $tempVal$unit")
            }
        }
        if (minVoltage != null && minVoltage < 12.2f) {
            parts.add(String.format(Locale.US, "Low volt dip %.1fV", minVoltage))
        }
        return parts.joinToString(" • ").ifEmpty { "Waiting for telemetry samples..." }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return when {
            hours > 0L -> String.format(Locale.US, "%dh %02dm", hours, mins)
            mins > 0L -> String.format(Locale.US, "%dm %02ds", mins, secs)
            else -> "${secs}s"
        }
    }

    fun formatAxisTime(elapsedMs: Long): String {
        val totalSec = (elapsedMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%d:%02d", mins, secs)
        }
    }

    fun formatScrubTime(elapsedMs: Long): String {
        val tenths = ((elapsedMs.coerceAtLeast(0L) % 1000L) / 100L)
        return "${formatAxisTime(elapsedMs)}.$tenths"
    }

    fun formatDistance(distanceKm: Float, unitSystem: UnitSystem): String {
        return if (unitSystem == UnitSystem.IMPERIAL) {
            val miles = distanceKm * ObdTelemetry.KMH_TO_MPH
            String.format(Locale.US, "%.2f mi", miles)
        } else {
            String.format(Locale.US, "%.2f km", distanceKm)
        }
    }

    private fun averageOrNull(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        var sum = 0.0
        for (v in values) sum += v
        return (sum / values.size).toFloat()
    }
}
