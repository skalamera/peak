package com.cl600.obd.ui

import android.graphics.Color
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.cl600.obd.R
import com.cl600.obd.model.ConnectionState
import com.cl600.obd.model.ObdTelemetry
import com.cl600.obd.model.UnitSystem
import com.cl600.obd.obd.ObdManager
import java.util.Locale
import kotlin.math.roundToInt

class GaugesViewBinder(root: View) {

    enum class ExtraMetric(val title: String) {
        POWER("EST. POWER"),
        TORQUE("EST. TORQUE"),
        LOAD("ENGINE LOAD"),
        TIMING("TIMING ADV"),
        THROTTLE("THROTTLE"),
        SPEED("VEHICLE SPEED")
    }

    private val gaugeBoost: AnalogGaugeView = root.findViewById(R.id.gaugeBoost)
    private val gaugeIat: AnalogGaugeView = root.findViewById(R.id.gaugeIat)
    private val btnResetBoostPeak: View = root.findViewById(R.id.btnResetBoostPeak)
    private val btnResetIatPeak: View = root.findViewById(R.id.btnResetIatPeak)
    private val wmiIndicator: WmiIndicatorView = root.findViewById(R.id.wmiIndicator)
    private val tvPowerPillHp: TextView = root.findViewById(R.id.tvPowerPillHp)
    private val tvPowerPillTq: TextView = root.findViewById(R.id.tvPowerPillTq)
    private val cardCoolant: DigitalGaugeCard = root.findViewById(R.id.cardCoolant)
    private val cardVoltage: DigitalGaugeCard = root.findViewById(R.id.cardVoltage)
    private val cardRpm: DigitalGaugeCard = root.findViewById(R.id.cardRpm)
    private val cardExtra: DigitalGaugeCard = root.findViewById(R.id.cardExtra)
    private val tvFooter: TextView = root.findViewById(R.id.tvGaugeFooter)

    private var currentUnitSystem: UnitSystem? = null
    private var extraMetricIndex: Int = 0
    private var lastTelemetry: ObdTelemetry = ObdTelemetry()
    private var lastState: ConnectionState = ConnectionState.Disconnected

    init {
        val ctx = root.context
        val racyBold = FontCache.racingBoldItalic(ctx)
        tvPowerPillHp.typeface = racyBold
        tvPowerPillTq.typeface = racyBold

        val resetBoostAction: (View) -> Unit = {
            ObdManager.resetBoostPeak()
            gaugeBoost.flashReset()
            Toast.makeText(ctx, "Boost peak reset", Toast.LENGTH_SHORT).show()
        }
        gaugeBoost.setOnClickListener(resetBoostAction)
        btnResetBoostPeak.setOnClickListener(resetBoostAction)

        val resetIatAction: (View) -> Unit = {
            ObdManager.resetIatPeak()
            gaugeIat.flashReset()
            Toast.makeText(ctx, "IAT peak reset", Toast.LENGTH_SHORT).show()
        }
        gaugeIat.setOnClickListener(resetIatAction)
        btnResetIatPeak.setOnClickListener(resetIatAction)

        cardCoolant.setHeader("COOLANT")
        cardCoolant.setOnResetClickListener("↺ PK") {
            ObdManager.resetCoolantPeak()
            Toast.makeText(ctx, "Coolant peak reset", Toast.LENGTH_SHORT).show()
        }

        cardVoltage.setHeader("VOLTAGE")
        cardVoltage.setOnResetClickListener("↺ MIN") {
            ObdManager.resetVoltageMin()
            Toast.makeText(ctx, "Voltage min reset", Toast.LENGTH_SHORT).show()
        }

        cardRpm.setHeader("ENGINE RPM")
        cardRpm.setOnResetClickListener("↺ PK") {
            ObdManager.resetRpmPeak()
            Toast.makeText(ctx, "RPM peak reset", Toast.LENGTH_SHORT).show()
        }

        cardExtra.setHeader(ExtraMetric.values()[extraMetricIndex].title, "TAP ↻")

        cardExtra.setOnClickListener {
            extraMetricIndex = (extraMetricIndex + 1) % ExtraMetric.values().size
            cardExtra.setHeader(ExtraMetric.values()[extraMetricIndex].title, "TAP ↻")
            render(lastTelemetry, currentUnitSystem ?: UnitSystem.IMPERIAL, lastState)
        }

        configureAnalogDials(UnitSystem.IMPERIAL)
    }

    fun render(telemetry: ObdTelemetry, unitSystem: UnitSystem, state: ConnectionState) {
        lastTelemetry = telemetry
        lastState = state

        if (currentUnitSystem != unitSystem) {
            configureAnalogDials(unitSystem)
        }

        // 1. Boost / Vacuum
        val boostDisplay = telemetry.boostPsi?.let {
            if (unitSystem == UnitSystem.IMPERIAL) it else ObdTelemetry.psiToBar(it)
        }
        val peakBoostDisplay = telemetry.peakBoostPsi?.let {
            if (unitSystem == UnitSystem.IMPERIAL) it else ObdTelemetry.psiToBar(it)
        }
        gaugeBoost.setValue(boostDisplay, peakBoostDisplay)

        // 2. IAT
        val iatDisplay = telemetry.iatCelsius?.let { ObdTelemetry.formatTemp(it, unitSystem) }
        val peakIatDisplay = telemetry.peakIatCelsius?.let { ObdTelemetry.formatTemp(it, unitSystem) }
        gaugeIat.setValue(iatDisplay, peakIatDisplay)

        // 2b. Water/Methanol Injection Indicator (Green whenever in positive boost)
        val wmiState = when {
            telemetry.boostPsi == null -> WmiIndicatorView.WmiState.OFF
            telemetry.boostPsi > 0f -> WmiIndicatorView.WmiState.ACTIVE
            else -> WmiIndicatorView.WmiState.STANDBY
        }
        wmiIndicator.setWmiState(wmiState)

        // 2c. Estimated Power & Torque Pill above IAT
        val tqUnitLabel = if (unitSystem == UnitSystem.IMPERIAL) "LB-FT" else "Nm"
        val hpVal = telemetry.estimatedHp
        val tqVal = telemetry.estimatedTorqueLbFt?.let {
            if (unitSystem == UnitSystem.IMPERIAL) it else ObdTelemetry.lbFtToNm(it)
        }
        tvPowerPillHp.text = if (hpVal != null) "${hpVal.roundToInt()} HP" else "-- HP"
        tvPowerPillTq.text = if (tqVal != null) "${tqVal.roundToInt()} $tqUnitLabel" else "-- $tqUnitLabel"

        // 3. Coolant Card
        val ectC = telemetry.coolantCelsius
        val tempUnit = if (unitSystem == UnitSystem.IMPERIAL) "°F" else "°C"
        if (ectC == null) {
            cardCoolant.updateValue("--", tempUnit, "PK --", 0f, DigitalGaugeCard.AlertLevel.INACTIVE)
        } else {
            val ectVal = ObdTelemetry.formatTemp(ectC, unitSystem)
            val pkEct = telemetry.peakCoolantCelsius?.let { ObdTelemetry.formatTemp(it, unitSystem) }
            val alert = when {
                ectC >= 110f -> DigitalGaugeCard.AlertLevel.CRIT
                ectC >= 102f -> DigitalGaugeCard.AlertLevel.WARN
                else -> DigitalGaugeCard.AlertLevel.NORMAL
            }
            val ratio = ((ectC - 40f) / (120f - 40f)).coerceIn(0f, 1f)
            val sub = pkEct?.let { "PK ${it.roundToInt()}$tempUnit" } ?: "NORMAL"
            cardCoolant.updateValue("${ectVal.roundToInt()}", tempUnit, sub, ratio, alert)
        }

        // 4. Battery Voltage Card
        val volts = telemetry.voltage
        if (volts == null) {
            cardVoltage.updateValue("--", "V", "MIN --", 0f, DigitalGaugeCard.AlertLevel.INACTIVE)
        } else {
            val alert = when {
                volts < 11.6f || volts > 15.3f -> DigitalGaugeCard.AlertLevel.CRIT
                volts < 12.2f || volts > 14.9f -> DigitalGaugeCard.AlertLevel.WARN
                else -> DigitalGaugeCard.AlertLevel.NORMAL
            }
            val ratio = ((volts - 10.5f) / (15.5f - 10.5f)).coerceIn(0f, 1f)
            val sub = telemetry.minVoltage?.let { String.format(Locale.US, "MIN %.1fV", it) } ?: "ALT OK"
            cardVoltage.updateValue(String.format(Locale.US, "%.1f", volts), "V", sub, ratio, alert)
        }

        // 5. RPM Card
        val rpm = telemetry.rpm
        if (rpm == null) {
            cardRpm.updateValue("--", "RPM", "PK --", 0f, DigitalGaugeCard.AlertLevel.INACTIVE)
        } else {
            val alert = when {
                rpm >= 6000 -> DigitalGaugeCard.AlertLevel.CRIT
                rpm >= 5500 -> DigitalGaugeCard.AlertLevel.WARN
                else -> DigitalGaugeCard.AlertLevel.NORMAL
            }
            val ratio = (rpm / 6500f).coerceIn(0f, 1f)
            val sub = telemetry.peakRpm?.let { "PK $it" } ?: "M275 V12"
            cardRpm.updateValue("$rpm", "RPM", sub, ratio, alert)
        }

        // 6. Extra Tappable Metric Card
        renderExtraCard(telemetry, unitSystem)

        // 7. Footer Status
        tvFooter.text = when (state) {
            is ConnectionState.Connected -> {
                val hzStr = String.format(Locale.US, "%.1f Hz", telemetry.pollingHz)
                "${state.protocol}  •  Baro: ${telemetry.baroSource}  •  $hzStr"
            }
            is ConnectionState.Demo -> {
                val hzStr = String.format(Locale.US, "%.1f Hz", telemetry.pollingHz)
                "DEMO M275 V12  •  Baro: ${telemetry.baroSource}  •  $hzStr"
            }
            is ConnectionState.Connecting -> "Connecting to ${state.detail}..."
            is ConnectionState.Initializing -> "Initializing: ${state.detail}..."
            is ConnectionState.Error -> "Error: ${state.message}"
            is ConnectionState.Disconnected -> "Not connected — plug in Galletto USB cable and tap CONNECT, or launch Demo mode"
        }
    }

    private fun renderExtraCard(telemetry: ObdTelemetry, unitSystem: UnitSystem) {
        val metric = ExtraMetric.values()[extraMetricIndex]
        cardExtra.setHeader(metric.title, "TAP ↻")
        val ctx = cardExtra.context

        when (metric) {
            ExtraMetric.POWER -> {
                cardExtra.setOnResetClickListener("↺ PK") {
                    ObdManager.resetPowerPeak()
                    Toast.makeText(ctx, "Power peak reset", Toast.LENGTH_SHORT).show()
                }
                val hp = telemetry.estimatedHp
                val tqUnit = if (unitSystem == UnitSystem.IMPERIAL) "lb-ft" else "Nm"
                if (hp == null) {
                    cardExtra.updateValue("--", "HP", "PK -- HP", 0f, DigitalGaugeCard.AlertLevel.INACTIVE)
                } else {
                    val tqDisp = telemetry.estimatedTorqueLbFt?.let {
                        if (unitSystem == UnitSystem.IMPERIAL) it else ObdTelemetry.lbFtToNm(it)
                    }
                    val pkHp = telemetry.peakHp?.roundToInt() ?: hp.roundToInt()
                    val tqStr = tqDisp?.let { "${it.roundToInt()} $tqUnit" } ?: "--"
                    val alert = if (hp >= 520f) DigitalGaugeCard.AlertLevel.WARN else DigitalGaugeCard.AlertLevel.NORMAL
                    cardExtra.updateValue("${hp.roundToInt()}", "HP", "PK $pkHp HP • $tqStr", (hp / 650f), alert)
                }
            }
            ExtraMetric.TORQUE -> {
                cardExtra.setOnResetClickListener("↺ PK") {
                    ObdManager.resetTorquePeak()
                    Toast.makeText(ctx, "Torque peak reset", Toast.LENGTH_SHORT).show()
                }
                val tqLbFt = telemetry.estimatedTorqueLbFt
                val tqUnit = if (unitSystem == UnitSystem.IMPERIAL) "LB-FT" else "Nm"
                if (tqLbFt == null) {
                    cardExtra.updateValue("--", tqUnit, "PK --", 0f, DigitalGaugeCard.AlertLevel.INACTIVE)
                } else {
                    val tqDisp = if (unitSystem == UnitSystem.IMPERIAL) tqLbFt else ObdTelemetry.lbFtToNm(tqLbFt)
                    val pkDisp = (telemetry.peakTorqueLbFt ?: tqLbFt).let {
                        if (unitSystem == UnitSystem.IMPERIAL) it else ObdTelemetry.lbFtToNm(it)
                    }
                    val alert = if (tqLbFt >= 600f) DigitalGaugeCard.AlertLevel.WARN else DigitalGaugeCard.AlertLevel.NORMAL
                    cardExtra.updateValue("${tqDisp.roundToInt()}", tqUnit, "PK ${pkDisp.roundToInt()} $tqUnit", (tqLbFt / 700f), alert)
                }
            }
            ExtraMetric.LOAD -> {
                cardExtra.setOnResetClickListener(listener = null)
                val load = telemetry.loadPercent
                if (load == null) {
                    cardExtra.updateValue("--", "%", "CALC LOAD", 0f, DigitalGaugeCard.AlertLevel.INACTIVE)
                } else {
                    val alert = if (load >= 95f) DigitalGaugeCard.AlertLevel.WARN else DigitalGaugeCard.AlertLevel.NORMAL
                    cardExtra.updateValue("${load.roundToInt()}", "%", "PID 0104", (load / 100f), alert)
                }
            }
            ExtraMetric.TIMING -> {
                cardExtra.setOnResetClickListener(listener = null)
                val adv = telemetry.timingAdvanceDeg
                if (adv == null) {
                    cardExtra.updateValue("--", "°", "IGN TIMING", 0f, DigitalGaugeCard.AlertLevel.INACTIVE)
                } else {
                    val ratio = ((adv + 15f) / 60f).coerceIn(0f, 1f)
                    cardExtra.updateValue(String.format(Locale.US, "%+.1f", adv), "°", "CYL 1 ADV", ratio, DigitalGaugeCard.AlertLevel.NORMAL)
                }
            }
            ExtraMetric.THROTTLE -> {
                cardExtra.setOnResetClickListener(listener = null)
                val tps = telemetry.throttlePercent
                if (tps == null) {
                    cardExtra.updateValue("--", "%", "THROTTLE", 0f, DigitalGaugeCard.AlertLevel.INACTIVE)
                } else {
                    cardExtra.updateValue("${tps.roundToInt()}", "%", "PID 0111", (tps / 100f), DigitalGaugeCard.AlertLevel.NORMAL)
                }
            }
            ExtraMetric.SPEED -> {
                cardExtra.setOnResetClickListener(listener = null)
                val kmh = telemetry.speedKmh
                val speedUnit = if (unitSystem == UnitSystem.IMPERIAL) "MPH" else "KM/H"
                if (kmh == null) {
                    cardExtra.updateValue("--", speedUnit, "OBD SPEED", 0f, DigitalGaugeCard.AlertLevel.INACTIVE)
                } else {
                    val valDisplay = if (unitSystem == UnitSystem.IMPERIAL) {
                        ObdTelemetry.kmhToMph(kmh).roundToInt()
                    } else {
                        kmh
                    }
                    cardExtra.updateValue("$valDisplay", speedUnit, "PID 010D", (kmh / 255f), DigitalGaugeCard.AlertLevel.NORMAL)
                }
            }
        }
    }

    private fun configureAnalogDials(unitSystem: UnitSystem) {
        currentUnitSystem = unitSystem
        val vacColor = Color.parseColor("#2E2E33")
        val okColor = Color.parseColor("#3F3F46")
        val boostColor = Color.parseColor("#D4D4D8")
        val warnColor = Color.parseColor("#D97706")
        val redColor = Color.parseColor("#EF4444")

        val boostGlowColor = Color.parseColor("#38BDF8")

        if (unitSystem == UnitSystem.IMPERIAL) {
            gaugeBoost.configure(
                title = "BOOST / VAC",
                unit = "PSI",
                minValue = -15f,
                maxValue = 25f,
                majorStep = 5f,
                minorDivisions = 5,
                zones = listOf(
                    AnalogGaugeView.GaugeZone(-15f, 0f, vacColor),
                    AnalogGaugeView.GaugeZone(0f, 20f, boostColor),
                    AnalogGaugeView.GaugeZone(20f, 25f, redColor)
                ),
                warningThreshold = 20f,
                decimalPlaces = 1,
                showPlusSign = true,
                glowThreshold = 0f,
                glowColor = boostGlowColor,
                subTextFormatter = { cur, peak ->
                    when {
                        cur != null && cur < -0.25f ->
                            String.format(Locale.US, "%.1f inHg", ObdTelemetry.psiToInHg(cur))
                        peak != null ->
                            String.format(Locale.US, "PK %+.1f", peak)
                        else -> "M275 BITURBO"
                    }
                }
            )

            gaugeIat.configure(
                title = "INTAKE AIR",
                unit = "°F",
                minValue = 40f,
                maxValue = 200f,
                majorStep = 20f,
                minorDivisions = 4,
                zones = listOf(
                    AnalogGaugeView.GaugeZone(40f, 125f, okColor),
                    AnalogGaugeView.GaugeZone(125f, 150f, warnColor),
                    AnalogGaugeView.GaugeZone(150f, 200f, redColor)
                ),
                warningThreshold = 150f,
                decimalPlaces = 0,
                showPlusSign = false,
                subTextFormatter = { _, peak ->
                    if (peak != null) "PK ${peak.roundToInt()}°F" else "INTERCOOLER"
                }
            )
        } else {
            gaugeBoost.configure(
                title = "BOOST / VAC",
                unit = "BAR",
                minValue = -1.0f,
                maxValue = 2.0f,
                majorStep = 0.5f,
                minorDivisions = 5,
                zones = listOf(
                    AnalogGaugeView.GaugeZone(-1.0f, 0f, vacColor),
                    AnalogGaugeView.GaugeZone(0f, 1.38f, boostColor),
                    AnalogGaugeView.GaugeZone(1.38f, 2.0f, redColor)
                ),
                warningThreshold = 1.38f,
                decimalPlaces = 2,
                showPlusSign = true,
                glowThreshold = 0f,
                glowColor = boostGlowColor,
                subTextFormatter = { _, peak ->
                    if (peak != null) String.format(Locale.US, "PK %+.2f", peak) else "M275 BITURBO"
                }
            )

            gaugeIat.configure(
                title = "INTAKE AIR",
                unit = "°C",
                minValue = 0f,
                maxValue = 100f,
                majorStep = 20f,
                minorDivisions = 4,
                zones = listOf(
                    AnalogGaugeView.GaugeZone(0f, 52f, okColor),
                    AnalogGaugeView.GaugeZone(52f, 65f, warnColor),
                    AnalogGaugeView.GaugeZone(65f, 100f, redColor)
                ),
                warningThreshold = 65f,
                decimalPlaces = 0,
                showPlusSign = false,
                subTextFormatter = { _, peak ->
                    if (peak != null) "PK ${peak.roundToInt()}°C" else "INTERCOOLER"
                }
            )
        }
    }
}
