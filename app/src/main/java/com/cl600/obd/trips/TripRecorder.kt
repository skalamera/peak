package com.cl600.obd.trips

import android.content.Context
import com.cl600.obd.model.ObdTelemetry
import com.cl600.obd.model.TripRecord
import com.cl600.obd.model.TripSample
import com.cl600.obd.model.TripSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TripRecorder {

    private const val SAMPLE_INTERVAL_MS = 250L
    private const val LIVE_SUMMARY_INTERVAL_MS = 500L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sampleLock = Any()

    private var repo: TripRepository? = null

    private var activeId: String = ""
    private var activeTitle: String = ""
    private var activeStartMs: Long = 0L
    private var activeIsDemo: Boolean = false
    private var lastSampleRecordedAtMs: Long = 0L
    private var lastSummaryComputedAtMs: Long = 0L
    private val activeBuffer = ArrayList<TripSample>()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _liveSamples = MutableStateFlow<List<TripSample>>(emptyList())
    val liveSamples: StateFlow<List<TripSample>> = _liveSamples.asStateFlow()

    private val _liveSummary = MutableStateFlow<TripSummary?>(null)
    val liveSummary: StateFlow<TripSummary?> = _liveSummary.asStateFlow()

    private val _savedTrips = MutableStateFlow<List<TripSummary>>(emptyList())
    val savedTrips: StateFlow<List<TripSummary>> = _savedTrips.asStateFlow()

    fun init(context: Context) {
        if (repo == null) {
            val repository = TripRepository(context.applicationContext)
            repo = repository
            ioScope.launch {
                _savedTrips.value = repository.loadSummaries()
            }
        }
    }

    fun startRecording(isDemo: Boolean) {
        val now = System.currentTimeMillis()
        val id = "trip_$now"
        val title = SimpleDateFormat("MMM d • h:mm a", Locale.US).format(Date(now))
        synchronized(sampleLock) {
            activeId = id
            activeTitle = title
            activeStartMs = now
            activeIsDemo = isDemo
            lastSampleRecordedAtMs = 0L
            lastSummaryComputedAtMs = 0L
            activeBuffer.clear()
        }
        _liveSamples.value = emptyList()
        _liveSummary.value = TripStatsCalculator.computeSummary(
            id = id,
            title = title,
            startTimeMs = now,
            endTimeMs = now,
            isDemo = isDemo,
            samples = emptyList()
        )
        _isRecording.value = true
    }

    fun onTelemetryUpdate(telemetry: ObdTelemetry) {
        if (!_isRecording.value) return
        val now = if (telemetry.lastUpdateMs > 0L) telemetry.lastUpdateMs else System.currentTimeMillis()

        val snapshotList: List<TripSample>
        val id: String
        val title: String
        val startMs: Long
        val isDemo: Boolean
        val shouldUpdateSummary: Boolean

        synchronized(sampleLock) {
            if (!_isRecording.value) return
            if (lastSampleRecordedAtMs > 0L && (now - lastSampleRecordedAtMs) < SAMPLE_INTERVAL_MS) {
                return
            }
            lastSampleRecordedAtMs = now
            val elapsed = (now - activeStartMs).coerceAtLeast(0L)
            val sample = TripSample(
                elapsedMs = elapsed,
                boostPsi = telemetry.boostPsi,
                iatCelsius = telemetry.iatCelsius,
                coolantCelsius = telemetry.coolantCelsius,
                rpm = telemetry.rpm,
                speedKmh = telemetry.speedKmh,
                estimatedHp = telemetry.estimatedHp,
                estimatedTorqueLbFt = telemetry.estimatedTorqueLbFt,
                throttlePercent = telemetry.throttlePercent,
                loadPercent = telemetry.loadPercent,
                timingAdvanceDeg = telemetry.timingAdvanceDeg,
                voltage = telemetry.voltage,
                wmiActive = (telemetry.boostPsi ?: -1f) > 0f
            )
            activeBuffer.add(sample)
            snapshotList = activeBuffer.toList()
            id = activeId
            title = activeTitle
            startMs = activeStartMs
            isDemo = activeIsDemo
            shouldUpdateSummary = snapshotList.size <= 3 || (now - lastSummaryComputedAtMs) >= LIVE_SUMMARY_INTERVAL_MS
            if (shouldUpdateSummary) {
                lastSummaryComputedAtMs = now
            }
        }

        _liveSamples.value = snapshotList
        if (shouldUpdateSummary) {
            _liveSummary.value = TripStatsCalculator.computeSummary(
                id = id,
                title = title,
                startTimeMs = startMs,
                endTimeMs = now,
                isDemo = isDemo,
                samples = snapshotList
            )
        }
    }

    fun stopAndSaveRecording(onSaved: ((TripSummary?) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        val id: String
        val title: String
        val startMs: Long
        val isDemo: Boolean
        val samples: List<TripSample>

        synchronized(sampleLock) {
            if (!_isRecording.value) {
                onSaved?.invoke(null)
                return
            }
            _isRecording.value = false
            id = activeId
            title = activeTitle
            startMs = activeStartMs
            isDemo = activeIsDemo
            samples = activeBuffer.toList()
            activeBuffer.clear()
        }

        _liveSamples.value = emptyList()
        _liveSummary.value = null

        if (samples.isEmpty()) {
            onSaved?.invoke(null)
            return
        }

        val finalSummary = TripStatsCalculator.computeSummary(
            id = id,
            title = title,
            startTimeMs = startMs,
            endTimeMs = now,
            isDemo = isDemo,
            samples = samples
        )
        val record = TripRecord(summary = finalSummary, samples = samples)

        ioScope.launch {
            val updatedList = repo?.saveTrip(record)
            if (updatedList != null) {
                _savedTrips.value = updatedList
            }
            withContext(Dispatchers.Main) {
                onSaved?.invoke(finalSummary)
            }
        }
    }

    fun discardRecording() {
        synchronized(sampleLock) {
            _isRecording.value = false
            activeBuffer.clear()
        }
        _liveSamples.value = emptyList()
        _liveSummary.value = null
    }

    suspend fun loadTripRecord(tripId: String): TripRecord? = withContext(Dispatchers.IO) {
        val summary = _savedTrips.value.firstOrNull { it.id == tripId }
            ?: repo?.loadSummaries()?.firstOrNull { it.id == tripId }
            ?: return@withContext null
        val samples = repo?.loadSamples(tripId) ?: emptyList()
        TripRecord(summary = summary, samples = samples)
    }

    fun renameTrip(tripId: String, newTitle: String) {
        ioScope.launch {
            val updated = repo?.renameTrip(tripId, newTitle) ?: return@launch
            _savedTrips.value = updated
        }
    }

    fun deleteTrip(tripId: String) {
        ioScope.launch {
            val updated = repo?.deleteTrip(tripId) ?: return@launch
            _savedTrips.value = updated
        }
    }
}
