package com.cl600.obd.trips

import com.cl600.obd.model.TripSample

object TripCsvCodec {

    private const val HEADER =
        "elapsed_ms,boost_psi,iat_c,coolant_c,rpm,speed_kmh,hp,tq_lbft,throttle_pct,load_pct,timing_deg,voltage,wmi"

    fun encode(samples: List<TripSample>): String {
        val sb = StringBuilder(samples.size * 64 + HEADER.length + 2)
        sb.append(HEADER).append('\n')
        for (s in samples) {
            sb.append(s.elapsedMs).append(',')
                .append(fmtFloat(s.boostPsi)).append(',')
                .append(fmtFloat(s.iatCelsius)).append(',')
                .append(fmtFloat(s.coolantCelsius)).append(',')
                .append(s.rpm?.toString() ?: "").append(',')
                .append(s.speedKmh?.toString() ?: "").append(',')
                .append(fmtFloat(s.estimatedHp)).append(',')
                .append(fmtFloat(s.estimatedTorqueLbFt)).append(',')
                .append(fmtFloat(s.throttlePercent)).append(',')
                .append(fmtFloat(s.loadPercent)).append(',')
                .append(fmtFloat(s.timingAdvanceDeg)).append(',')
                .append(fmtFloat(s.voltage)).append(',')
                .append(if (s.wmiActive) '1' else '0')
                .append('\n')
        }
        return sb.toString()
    }

    fun decode(csv: String): List<TripSample> {
        val lines = csv.lineSequence()
        val out = ArrayList<TripSample>()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("elapsed_ms")) continue
            val cols = line.split(',')
            if (cols.size < 13) continue
            val elapsedMs = cols[0].toLongOrNull() ?: continue
            out.add(
                TripSample(
                    elapsedMs = elapsedMs,
                    boostPsi = cols[1].toFloatOrNull(),
                    iatCelsius = cols[2].toFloatOrNull(),
                    coolantCelsius = cols[3].toFloatOrNull(),
                    rpm = cols[4].toIntOrNull(),
                    speedKmh = cols[5].toIntOrNull(),
                    estimatedHp = cols[6].toFloatOrNull(),
                    estimatedTorqueLbFt = cols[7].toFloatOrNull(),
                    throttlePercent = cols[8].toFloatOrNull(),
                    loadPercent = cols[9].toFloatOrNull(),
                    timingAdvanceDeg = cols[10].toFloatOrNull(),
                    voltage = cols[11].toFloatOrNull(),
                    wmiActive = cols[12] == "1" || cols[12].equals("true", ignoreCase = true)
                )
            )
        }
        return out
    }

    private fun fmtFloat(value: Float?): String {
        if (value == null || !value.isFinite()) return ""
        return value.toString()
    }
}
