package com.cl600.obd.model

enum class UnitSystem(val shortLabel: String) {
    IMPERIAL("US (PSI / °F)"),
    METRIC("METRIC (BAR / °C)")
}

data class ObdTelemetry(
    val mapKpa: Float? = null,
    val baroKpa: Float? = null,
    val boostPsi: Float? = null,
    val iatCelsius: Float? = null,
    val coolantCelsius: Float? = null,
    val voltage: Float? = null,
    val rpm: Int? = null,
    val loadPercent: Float? = null,
    val timingAdvanceDeg: Float? = null,
    val throttlePercent: Float? = null,
    val speedKmh: Int? = null,
    val estimatedHp: Float? = null,
    val estimatedTorqueLbFt: Float? = null,
    val peakBoostPsi: Float? = null,
    val peakIatCelsius: Float? = null,
    val peakCoolantCelsius: Float? = null,
    val minVoltage: Float? = null,
    val peakRpm: Int? = null,
    val peakHp: Float? = null,
    val peakTorqueLbFt: Float? = null,
    val milOn: Boolean = false,
    val dtcCountReported: Int = 0,
    val activeProtocol: String = "",
    val adapterVersion: String = "",
    val lastUpdateMs: Long = 0L,
    val pollingHz: Float = 0f,
    val supportedPids: Set<String> = emptySet(),
    val effectiveBaroKpa: Float = STANDARD_BARO_KPA,
    val baroSource: String = "Default (101.3 kPa)"
) {
    companion object {
        const val STANDARD_BARO_KPA = 101.325f
        const val KPA_TO_PSI = 0.14503774f
        const val PSI_TO_BAR = 0.06894757f
        const val PSI_TO_INHG = 2.036021f
        const val KMH_TO_MPH = 0.6213712f
        const val LBFT_TO_NM = 1.355818f

        fun cToF(celsius: Float): Float = (celsius * 9f / 5f) + 32f
        fun psiToBar(psi: Float): Float = psi * PSI_TO_BAR
        fun psiToInHg(psi: Float): Float = psi * PSI_TO_INHG
        fun kmhToMph(kmh: Int): Float = kmh * KMH_TO_MPH
        fun lbFtToNm(lbFt: Float): Float = lbFt * LBFT_TO_NM

        fun formatTemp(celsius: Float, unitSystem: UnitSystem): Float =
            if (unitSystem == UnitSystem.IMPERIAL) cToF(celsius) else celsius
    }
}
