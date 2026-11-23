package com.cl600.obd.trips

import android.content.Context
import com.cl600.obd.model.TripRecord
import com.cl600.obd.model.TripSample
import com.cl600.obd.model.TripSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TripRepository(context: Context) {

    private val tripsDir = File(context.applicationContext.filesDir, "trips").apply { mkdirs() }
    private val indexFile = File(tripsDir, "trips_index.json")
    private val fileLock = Any()

    fun loadSummaries(): List<TripSummary> {
        synchronized(fileLock) {
            if (!indexFile.exists()) return emptyList()
            return runCatching {
                val arr = JSONArray(indexFile.readText())
                val list = ArrayList<TripSummary>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    summaryFromJson(obj)?.let { list.add(it) }
                }
                list.sortedByDescending { it.startTimeMs }
            }.getOrDefault(emptyList())
        }
    }

    fun saveTrip(record: TripRecord): List<TripSummary> {
        synchronized(fileLock) {
            tripsDir.mkdirs()
            val csvFile = File(tripsDir, "${record.summary.id}.csv")
            writeAtomic(csvFile, TripCsvCodec.encode(record.samples))

            val updated = loadSummariesUnlocked()
                .filterNot { it.id == record.summary.id }
                .toMutableList()
            updated.add(0, record.summary)
            val sorted = updated.sortedByDescending { it.startTimeMs }
            writeIndexUnlocked(sorted)
            return sorted
        }
    }

    fun loadSamples(tripId: String): List<TripSample> {
        synchronized(fileLock) {
            val csvFile = File(tripsDir, "$tripId.csv")
            if (!csvFile.exists()) return emptyList()
            return runCatching { TripCsvCodec.decode(csvFile.readText()) }.getOrDefault(emptyList())
        }
    }

    fun renameTrip(tripId: String, newTitle: String): List<TripSummary> {
        synchronized(fileLock) {
            val trimmed = newTitle.trim()
            if (trimmed.isEmpty()) return loadSummariesUnlocked()
            val updated = loadSummariesUnlocked().map {
                if (it.id == tripId) it.copy(title = trimmed) else it
            }
            writeIndexUnlocked(updated)
            return updated
        }
    }

    fun deleteTrip(tripId: String): List<TripSummary> {
        synchronized(fileLock) {
            val csvFile = File(tripsDir, "$tripId.csv")
            if (csvFile.exists()) {
                csvFile.delete()
            }
            val updated = loadSummariesUnlocked().filterNot { it.id == tripId }
            writeIndexUnlocked(updated)
            return updated
        }
    }

    private fun loadSummariesUnlocked(): List<TripSummary> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            val list = ArrayList<TripSummary>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                summaryFromJson(obj)?.let { list.add(it) }
            }
            list.sortedByDescending { it.startTimeMs }
        }.getOrDefault(emptyList())
    }

    private fun writeIndexUnlocked(list: List<TripSummary>) {
        val arr = JSONArray()
        for (summary in list) {
            arr.put(summaryToJson(summary))
        }
        writeAtomic(indexFile, arr.toString())
    }

    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    private fun summaryToJson(s: TripSummary): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("title", s.title)
        put("startTimeMs", s.startTimeMs)
        put("endTimeMs", s.endTimeMs)
        put("durationMs", s.durationMs)
        put("sampleCount", s.sampleCount)
        put("isDemo", s.isDemo)
        put("distanceKm", s.distanceKm.toDouble())
        putNullableDouble("maxSpeedKmh", s.maxSpeedKmh?.toDouble())
        putNullableDouble("avgSpeedKmh", s.avgSpeedKmh?.toDouble())
        putNullableDouble("peakBoostPsi", s.peakBoostPsi?.toDouble())
        putNullableDouble("avgBoostPsi", s.avgBoostPsi?.toDouble())
        put("timeInBoostMs", s.timeInBoostMs)
        put("boostPercentOfTrip", s.boostPercentOfTrip.toDouble())
        putNullableDouble("peakHp", s.peakHp?.toDouble())
        putNullableDouble("peakTorqueLbFt", s.peakTorqueLbFt?.toDouble())
        putNullableInt("peakRpm", s.peakRpm)
        putNullableDouble("avgRpm", s.avgRpm?.toDouble())
        putNullableDouble("maxLoadPercent", s.maxLoadPercent?.toDouble())
        putNullableDouble("maxThrottlePercent", s.maxThrottlePercent?.toDouble())
        putNullableDouble("minTimingDeg", s.minTimingDeg?.toDouble())
        putNullableDouble("maxTimingDeg", s.maxTimingDeg?.toDouble())
        putNullableDouble("minIatCelsius", s.minIatCelsius?.toDouble())
        putNullableDouble("avgIatCelsius", s.avgIatCelsius?.toDouble())
        putNullableDouble("peakIatCelsius", s.peakIatCelsius?.toDouble())
        putNullableDouble("peakCoolantCelsius", s.peakCoolantCelsius?.toDouble())
        putNullableDouble("avgCoolantCelsius", s.avgCoolantCelsius?.toDouble())
        putNullableDouble("minVoltage", s.minVoltage?.toDouble())
        putNullableDouble("avgVoltage", s.avgVoltage?.toDouble())
        put("insightText", s.insightText)
    }

    private fun summaryFromJson(obj: JSONObject): TripSummary? {
        val id = obj.optString("id", "").ifBlank { return null }
        val start = obj.optLong("startTimeMs", 0L)
        if (start <= 0L) return null
        return TripSummary(
            id = id,
            title = obj.optString("title", "CL600 Trip"),
            startTimeMs = start,
            endTimeMs = obj.optLong("endTimeMs", start),
            durationMs = obj.optLong("durationMs", 0L),
            sampleCount = obj.optInt("sampleCount", 0),
            isDemo = obj.optBoolean("isDemo", false),
            distanceKm = obj.optDouble("distanceKm", 0.0).toFloat(),
            maxSpeedKmh = obj.optNullableInt("maxSpeedKmh"),
            avgSpeedKmh = obj.optNullableFloat("avgSpeedKmh"),
            peakBoostPsi = obj.optNullableFloat("peakBoostPsi"),
            avgBoostPsi = obj.optNullableFloat("avgBoostPsi"),
            timeInBoostMs = obj.optLong("timeInBoostMs", 0L),
            boostPercentOfTrip = obj.optDouble("boostPercentOfTrip", 0.0).toFloat(),
            peakHp = obj.optNullableFloat("peakHp"),
            peakTorqueLbFt = obj.optNullableFloat("peakTorqueLbFt"),
            peakRpm = obj.optNullableInt("peakRpm"),
            avgRpm = obj.optNullableFloat("avgRpm"),
            maxLoadPercent = obj.optNullableFloat("maxLoadPercent"),
            maxThrottlePercent = obj.optNullableFloat("maxThrottlePercent"),
            minTimingDeg = obj.optNullableFloat("minTimingDeg"),
            maxTimingDeg = obj.optNullableFloat("maxTimingDeg"),
            minIatCelsius = obj.optNullableFloat("minIatCelsius"),
            avgIatCelsius = obj.optNullableFloat("avgIatCelsius"),
            peakIatCelsius = obj.optNullableFloat("peakIatCelsius"),
            peakCoolantCelsius = obj.optNullableFloat("peakCoolantCelsius"),
            avgCoolantCelsius = obj.optNullableFloat("avgCoolantCelsius"),
            minVoltage = obj.optNullableFloat("minVoltage"),
            avgVoltage = obj.optNullableFloat("avgVoltage"),
            insightText = obj.optString("insightText", "")
        )
    }

    private fun JSONObject.putNullableDouble(key: String, value: Double?) {
        if (value != null && value.isFinite()) put(key, value) else put(key, JSONObject.NULL)
    }

    private fun JSONObject.putNullableInt(key: String, value: Int?) {
        if (value != null) put(key, value) else put(key, JSONObject.NULL)
    }

    private fun JSONObject.optNullableFloat(key: String): Float? {
        if (!has(key) || isNull(key)) return null
        val d = optDouble(key, Double.NaN)
        return if (d.isFinite()) d.toFloat() else null
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }
}
