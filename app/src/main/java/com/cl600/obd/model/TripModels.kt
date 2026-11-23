package com.cl600.obd.model

import java.util.Locale
import kotlin.math.roundToInt

enum class TripMetric(
    val shortLabel: String,
    val displayName: String,
    val colorInt: Int,
    val minCanonical: Float,
    val maxCanonical: Float
) {
    BOOST("BOOST", "Boost (PSI/BAR)", 0xFF38BDF8.toInt(), -15f, 25f),
    RPM("RPM", "Engine RPM", 0xFFF4F4F5.toInt(), 500f, 6500f),
    POWER("HP", "Est. Power (HP)", 0xFF22C55E.toInt(), 0f, 650f),
    IAT("IAT", "Intake Air Temp", 0xFFF59E0B.toInt(), 10f, 95f),
    SPEED("SPD", "Vehicle Speed", 0xFFA78BFA.toInt(), 0f, 260f),
    COOLANT("ECT", "Coolant Temp", 0xFFF43F5E.toInt(), 40f, 120f),
    THROTTLE("TPS", "Throttle %", 0xFF2DD4BF.toInt(), 0f, 100f),
    TORQUE("TQ", "Est. Torque", 0xFFA3E635.toInt(), 0f, 750f),
    LOAD("LOAD", "Engine Load %", 0xFFFB923C.toInt(), 0f, 100f),
    TIMING("IGN", "Timing Advance", 0xFFF472B6.toInt(), -10f, 50f),
    VOLTAGE("VOLT", "Battery Voltage", 0xFF818CF8.toInt(), 11.0f, 15.5f);

    fun extractCanonical(sample: TripSample): Float? = when (this) {
        BOOST -> sample.boostPsi
        RPM -> sample.rpm?.toFloat()
        POWER -> sample.estimatedHp
        IAT -> sample.iatCelsius
        SPEED -> sample.speedKmh?.toFloat()
        COOLANT -> sample.coolantCelsius
        THROTTLE -> sample.throttlePercent
        TORQUE -> sample.estimatedTorqueLbFt
        LOAD -> sample.loadPercent
        TIMING -> sample.timingAdvanceDeg
        VOLTAGE -> sample.voltage
    }

    fun normalize(sample: TripSample): Float? {
        val raw = extractCanonical(sample) ?: return null
        val span = maxCanonical - minCanonical
        if (span <= 0f) return 0f
        return ((raw - minCanonical) / span).coerceIn(0f, 1f)
    }

    fun formatValue(sample: TripSample, unitSystem: UnitSystem): String {
        return when (this) {
            BOOST -> {
                val psi = sample.boostPsi ?: return "--"
                if (unitSystem == UnitSystem.IMPERIAL) {
                    String.format(Locale.US, "%+.1f PSI", psi)
                } else {
                    String.format(Locale.US, "%+.2f BAR", ObdTelemetry.psiToBar(psi))
                }
            }
            RPM -> sample.rpm?.toString() ?: "--"
            POWER -> sample.estimatedHp?.let { "${it.roundToInt()} HP" } ?: "--"
            IAT -> {
                val c = sample.iatCelsius ?: return "--"
                val u = if (unitSystem == UnitSystem.IMPERIAL) "°F" else "°C"
                "${ObdTelemetry.formatTemp(c, unitSystem).roundToInt()}$u"
            }
            SPEED -> {
                val kmh = sample.speedKmh ?: return "--"
                if (unitSystem == UnitSystem.IMPERIAL) {
                    "${ObdTelemetry.kmhToMph(kmh).roundToInt()} MPH"
                } else {
                    "$kmh km/h"
                }
            }
            COOLANT -> {
                val c = sample.coolantCelsius ?: return "--"
                val u = if (unitSystem == UnitSystem.IMPERIAL) "°F" else "°C"
                "${ObdTelemetry.formatTemp(c, unitSystem).roundToInt()}$u"
            }
            THROTTLE -> sample.throttlePercent?.let { "${it.roundToInt()}%" } ?: "--"
            TORQUE -> {
                val lbFt = sample.estimatedTorqueLbFt ?: return "--"
                if (unitSystem == UnitSystem.IMPERIAL) {
                    "${lbFt.roundToInt()} lb-ft"
                } else {
                    "${ObdTelemetry.lbFtToNm(lbFt).roundToInt()} Nm"
                }
            }
            LOAD -> sample.loadPercent?.let { "${it.roundToInt()}%" } ?: "--"
            TIMING -> sample.timingAdvanceDeg?.let { String.format(Locale.US, "%+.1f°", it) } ?: "--"
            VOLTAGE -> sample.voltage?.let { String.format(Locale.US, "%.1fV", it) } ?: "--"
        }
    }

    fun scaleHint(unitSystem: UnitSystem): String = when (this) {
        BOOST -> if (unitSystem == UnitSystem.IMPERIAL) "-15..25 PSI" else "-1.0..1.7 BAR"
        RPM -> "500..6500 RPM"
        POWER -> "0..650 HP"
        IAT -> if (unitSystem == UnitSystem.IMPERIAL) "50..203 °F" else "10..95 °C"
        SPEED -> if (unitSystem == UnitSystem.IMPERIAL) "0..162 MPH" else "0..260 km/h"
        COOLANT -> if (unitSystem == UnitSystem.IMPERIAL) "104..248 °F" else "40..120 °C"
        THROTTLE -> "0..100%"
        TORQUE -> if (unitSystem == UnitSystem.IMPERIAL) "0..750 lb-ft" else "0..1017 Nm"
        LOAD -> "0..100%"
        TIMING -> "-10..+50°"
        VOLTAGE -> "11.0..15.5V"
    }
}

data class TripSample(
    val elapsedMs: Long,
    val boostPsi: Float?,
    val iatCelsius: Float?,
    val coolantCelsius: Float?,
    val rpm: Int?,
    val speedKmh: Int?,
    val estimatedHp: Float?,
    val estimatedTorqueLbFt: Float?,
    val throttlePercent: Float?,
    val loadPercent: Float?,
    val timingAdvanceDeg: Float?,
    val voltage: Float?,
    val wmiActive: Boolean
)

data class TripSummary(
    val id: String,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val sampleCount: Int,
    val isDemo: Boolean,
    val distanceKm: Float,
    val maxSpeedKmh: Int?,
    val avgSpeedKmh: Float?,
    val peakBoostPsi: Float?,
    val avgBoostPsi: Float?,
    val timeInBoostMs: Long,
    val boostPercentOfTrip: Float,
    val peakHp: Float?,
    val peakTorqueLbFt: Float?,
    val peakRpm: Int?,
    val avgRpm: Float?,
    val maxLoadPercent: Float?,
    val maxThrottlePercent: Float?,
    val minTimingDeg: Float?,
    val maxTimingDeg: Float?,
    val minIatCelsius: Float?,
    val avgIatCelsius: Float?,
    val peakIatCelsius: Float?,
    val peakCoolantCelsius: Float?,
    val avgCoolantCelsius: Float?,
    val minVoltage: Float?,
    val avgVoltage: Float?,
    val insightText: String
)

data class TripRecord(
    val summary: TripSummary,
    val samples: List<TripSample>
)
