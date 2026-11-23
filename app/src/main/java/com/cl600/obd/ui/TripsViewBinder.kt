package com.cl600.obd.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cl600.obd.R
import com.cl600.obd.model.ConnectionState
import com.cl600.obd.model.ObdTelemetry
import com.cl600.obd.model.TripMetric
import com.cl600.obd.model.TripRecord
import com.cl600.obd.model.TripSample
import com.cl600.obd.model.TripSummary
import com.cl600.obd.model.UnitSystem
import com.cl600.obd.obd.ObdManager
import com.cl600.obd.trips.TripRecorder
import com.cl600.obd.trips.TripStatsCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.EnumSet
import java.util.Locale
import kotlin.math.roundToInt

class TripsViewBinder(
    root: View,
    private val context: Context,
    private val uiScope: CoroutineScope,
    private val onDetailVisibilityChanged: (Boolean) -> Unit
) {
    private val layoutTripsHome: View = root.findViewById(R.id.layoutTripsHome)
    private val layoutTripDetail: View = root.findViewById(R.id.layoutTripDetail)

    private val viewRecDot: View = root.findViewById(R.id.viewRecDot)
    private val tvRecorderTitle: TextView = root.findViewById(R.id.tvRecorderTitle)
    private val tvRecorderSub: TextView = root.findViewById(R.id.tvRecorderSub)
    private val btnStartTrip: Button = root.findViewById(R.id.btnStartTrip)
    private val btnDiscardTrip: Button = root.findViewById(R.id.btnDiscardTrip)
    private val btnStopSaveTrip: Button = root.findViewById(R.id.btnStopSaveTrip)

    private val layoutSavedListContainer: View = root.findViewById(R.id.layoutSavedListContainer)
    private val rvTrips: RecyclerView = root.findViewById(R.id.rvTrips)
    private val tvEmptyTrips: TextView = root.findViewById(R.id.tvEmptyTrips)

    private val layoutLiveRecorder: View = root.findViewById(R.id.layoutLiveRecorder)
    private val llLiveLegend: LinearLayout = root.findViewById(R.id.llLiveLegend)
    private val liveGraphView: TripGraphView = root.findViewById(R.id.liveGraphView)
    private val btnLiveZoom: TextView = root.findViewById(R.id.btnLiveZoom)
    private val tvLiveStatsFooter: TextView = root.findViewById(R.id.tvLiveStatsFooter)

    private val btnBackToTrips: View = root.findViewById(R.id.btnBackToTrips)
    private val tvDetailTitle: TextView = root.findViewById(R.id.tvDetailTitle)
    private val tvDetailMeta: TextView = root.findViewById(R.id.tvDetailMeta)
    private val btnRenameTrip: View = root.findViewById(R.id.btnRenameTrip)
    private val btnDeleteTripDetail: View = root.findViewById(R.id.btnDeleteTripDetail)
    private val llDetailSummaryCards: LinearLayout = root.findViewById(R.id.llDetailSummaryCards)
    private val llDetailLegend: LinearLayout = root.findViewById(R.id.llDetailLegend)
    private val detailGraphView: TripGraphView = root.findViewById(R.id.detailGraphView)
    private val btnDetailZoom: TextView = root.findViewById(R.id.btnDetailZoom)
    private val tvDetailFooter: TextView = root.findViewById(R.id.tvDetailFooter)

    private val density = context.resources.displayMetrics.density
    private val racingFont = FontCache.racingBoldItalic(context)

    private val adapter: TripsAdapter
    private val liveVisibleMetrics = EnumSet.allOf(TripMetric::class.java)
    private val detailVisibleMetrics = EnumSet.allOf(TripMetric::class.java)

    private val liveChipValueViews = LinkedHashMap<TripMetric, TextView>()
    private val detailChipValueViews = LinkedHashMap<TripMetric, TextView>()

    private var currentUnitSystem = UnitSystem.IMPERIAL
    private var currentConnectionState: ConnectionState = ConnectionState.Disconnected
    private var openedRecord: TripRecord? = null
    private var detailScrubSample: TripSample? = null
    private var liveScrubSample: TripSample? = null

    init {
        adapter = TripsAdapter(
            onSelect = { summary -> openTripDetail(summary) },
            onDelete = { summary -> confirmDeleteTrip(summary) }
        )
        rvTrips.layoutManager = LinearLayoutManager(context)
        rvTrips.adapter = adapter

        liveGraphView.setZoomMode(TripGraphView.ZoomMode.X1)
        btnLiveZoom.text = TripGraphView.ZoomMode.X1.label
        liveGraphView.setOnScrubListener { sample ->
            liveScrubSample = sample
            updateLiveLegendValues(sample)
        }
        btnLiveZoom.setOnClickListener {
            val mode = liveGraphView.cycleZoomMode()
            btnLiveZoom.text = mode.label
            if (mode == TripGraphView.ZoomMode.X1) {
                liveGraphView.jumpToLiveTail()
            }
        }

        detailGraphView.setOnScrubListener { sample ->
            detailScrubSample = sample
            updateDetailLegendValues(sample)
            updateDetailFooter()
        }
        btnDetailZoom.setOnClickListener {
            val mode = detailGraphView.cycleZoomMode()
            btnDetailZoom.text = mode.label
        }

        btnStartTrip.setOnClickListener { handleStartTripClick() }
        btnStopSaveTrip.setOnClickListener {
            TripRecorder.stopAndSaveRecording { saved ->
                val msg = if (saved != null) {
                    "Trip saved (${saved.sampleCount} samples)"
                } else {
                    "No telemetry samples captured yet"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
        btnDiscardTrip.setOnClickListener { confirmDiscardLiveRecording() }

        btnBackToTrips.setOnClickListener { closeDetail() }
        btnRenameTrip.setOnClickListener { openedRecord?.summary?.let { promptRenameTrip(it) } }
        btnDeleteTripDetail.setOnClickListener { openedRecord?.summary?.let { confirmDeleteTrip(it) } }

        buildLegendChips(
            container = llLiveLegend,
            metrics = TripMetric.values().toList(),
            visibleSet = liveVisibleMetrics,
            valueViewOut = liveChipValueViews
        ) {
            liveGraphView.setVisibleMetrics(liveVisibleMetrics.toSet())
        }
    }

    fun isInDetailView(): Boolean = layoutTripDetail.visibility == View.VISIBLE

    fun handleBackPressed(): Boolean {
        if (isInDetailView()) {
            closeDetail()
            return true
        }
        return false
    }

    fun closeDetail() {
        openedRecord = null
        detailScrubSample = null
        detailGraphView.clearScrub()
        layoutTripDetail.visibility = View.GONE
        layoutTripsHome.visibility = View.VISIBLE
        onDetailVisibilityChanged(false)
    }

    fun render(
        isRecording: Boolean,
        liveSamples: List<TripSample>,
        liveSummary: TripSummary?,
        savedTrips: List<TripSummary>,
        unitSystem: UnitSystem,
        connectionState: ConnectionState
    ) {
        val unitsChanged = currentUnitSystem != unitSystem
        currentUnitSystem = unitSystem
        currentConnectionState = connectionState

        if (isRecording) {
            if (isInDetailView()) {
                closeDetail()
            }
            btnStartTrip.visibility = View.GONE
            btnDiscardTrip.visibility = View.VISIBLE
            btnStopSaveTrip.visibility = View.VISIBLE
            layoutSavedListContainer.visibility = View.GONE
            layoutLiveRecorder.visibility = View.VISIBLE

            val dotColor = ContextCompat.getColor(context, R.color.status_error)
            (viewRecDot.background as? GradientDrawable)?.setColor(dotColor)

            val durStr = TripStatsCalculator.formatAxisTime(liveSummary?.durationMs ?: 0L)
            tvRecorderTitle.text = "● RECORDING  $durStr"
            tvRecorderTitle.setTextColor(dotColor)

            val pkBoostStr = liveSummary?.peakBoostPsi?.let {
                if (unitSystem == UnitSystem.IMPERIAL) {
                    String.format(Locale.US, "%+.1f PSI", it)
                } else {
                    String.format(Locale.US, "%+.2f BAR", ObdTelemetry.psiToBar(it))
                }
            } ?: "--"
            val pkHpStr = liveSummary?.peakHp?.let { "${it.roundToInt()} HP" } ?: "--"
            val pts = liveSamples.size
            tvRecorderSub.text = "$pts pts • Peak Boost $pkBoostStr • Peak $pkHpStr"

            liveGraphView.setData(liveSamples, liveMode = true, unitSystem = unitSystem)
            btnLiveZoom.text = liveGraphView.getZoomMode().label

            val activeSample = liveScrubSample ?: liveSamples.lastOrNull()
            updateLiveLegendValues(activeSample)

            val distStr = TripStatsCalculator.formatDistance(liveSummary?.distanceKm ?: 0f, unitSystem)
            val wmiPct = liveSummary?.boostPercentOfTrip?.roundToInt() ?: 0
            val pkIatStr = liveSummary?.peakIatCelsius?.let {
                val u = if (unitSystem == UnitSystem.IMPERIAL) "°F" else "°C"
                "${ObdTelemetry.formatTemp(it, unitSystem).roundToInt()}$u"
            } ?: "--"
            tvLiveStatsFooter.text =
                "Dist $distStr • Boost/WMI ${wmiPct}% • Peak IAT $pkIatStr • Tap chips to toggle or tap graph to inspect"
        } else {
            btnStartTrip.visibility = View.VISIBLE
            btnDiscardTrip.visibility = View.GONE
            btnStopSaveTrip.visibility = View.GONE
            layoutSavedListContainer.visibility = View.VISIBLE
            layoutLiveRecorder.visibility = View.GONE
            liveScrubSample = null

            val dotColor = ContextCompat.getColor(
                context,
                if (connectionState.isStreaming()) R.color.status_connected else R.color.status_disconnected
            )
            (viewRecDot.background as? GradientDrawable)?.setColor(dotColor)
            tvRecorderTitle.text = "TRIP RECORDER"
            tvRecorderTitle.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            tvRecorderSub.text = if (savedTrips.isEmpty()) {
                "Connect OBD or use Demo Mode to record a trip"
            } else {
                "${savedTrips.size} saved trip${if (savedTrips.size == 1) "" else "s"} — tap a trip to explore full telemetry"
            }

            adapter.submitList(savedTrips, unitSystem)
            if (savedTrips.isEmpty()) {
                rvTrips.visibility = View.GONE
                tvEmptyTrips.visibility = View.VISIBLE
            } else {
                rvTrips.visibility = View.VISIBLE
                tvEmptyTrips.visibility = View.GONE
            }
        }

        // Refresh open trip detail if renamed or unit system changed
        openedRecord?.let { current ->
            val updatedSummary = savedTrips.firstOrNull { it.id == current.summary.id }
            if (updatedSummary != null && (updatedSummary != current.summary || unitsChanged)) {
                val refreshed = current.copy(summary = updatedSummary)
                openedRecord = refreshed
                bindDetail(refreshed, resetZoom = false)
            }
        }
    }

    private fun handleStartTripClick() {
        if (currentConnectionState.isStreaming()) {
            TripRecorder.startRecording(isDemo = currentConnectionState is ConnectionState.Demo)
            Toast.makeText(context, "Trip recording started", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(context)
            .setTitle("Start Trip Recording")
            .setMessage(
                "You're not connected to the car ECU yet.\n\n" +
                    "Start M275 V12 Demo Mode and record a live simulated trip to test the real-time graph and summaries?"
            )
            .setPositiveButton("Start Demo & Record") { _, _ ->
                ObdManager.startDemo()
                TripRecorder.startRecording(isDemo = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openTripDetail(summary: TripSummary) {
        uiScope.launch {
            val loaded = TripRecorder.loadTripRecord(summary.id)
            if (loaded == null) {
                Toast.makeText(context, "Could not load trip samples", Toast.LENGTH_SHORT).show()
                return@launch
            }
            detailScrubSample = null
            detailVisibleMetrics.clear()
            detailVisibleMetrics.addAll(TripMetric.values())
            openedRecord = loaded
            layoutTripsHome.visibility = View.GONE
            layoutTripDetail.visibility = View.VISIBLE
            onDetailVisibilityChanged(true)
            bindDetail(loaded, resetZoom = true)
        }
    }

    private fun bindDetail(record: TripRecord, resetZoom: Boolean) {
        val s = record.summary
        val dur = TripStatsCalculator.formatDuration(s.durationMs)
        val dist = TripStatsCalculator.formatDistance(s.distanceKm, currentUnitSystem)
        tvDetailTitle.text = if (s.isDemo) "${s.title}  [DEMO]" else s.title
        tvDetailMeta.text = "$dur • $dist • ${s.sampleCount} samples"

        populateDetailSummaryCards(s)

        val availableMetrics = TripMetric.values().filter { metric ->
            record.samples.any { metric.extractCanonical(it) != null }
        }.ifEmpty { TripMetric.values().toList() }

        buildLegendChips(
            container = llDetailLegend,
            metrics = availableMetrics,
            visibleSet = detailVisibleMetrics,
            valueViewOut = detailChipValueViews
        ) {
            detailGraphView.setVisibleMetrics(detailVisibleMetrics.toSet())
        }

        if (resetZoom) {
            detailGraphView.setData(emptyList(), liveMode = false, unitSystem = currentUnitSystem)
        }
        detailGraphView.setData(record.samples, liveMode = false, unitSystem = currentUnitSystem)
        detailGraphView.setVisibleMetrics(detailVisibleMetrics.toSet())
        btnDetailZoom.text = detailGraphView.getZoomMode().label

        updateDetailLegendValues(detailScrubSample)
        updateDetailFooter()
    }

    private fun populateDetailSummaryCards(s: TripSummary) {
        llDetailSummaryCards.removeAllViews()
        val u = currentUnitSystem
        val tempUnit = if (u == UnitSystem.IMPERIAL) "°F" else "°C"

        val pkBoost = s.peakBoostPsi?.let {
            if (u == UnitSystem.IMPERIAL) String.format(Locale.US, "%+.1f PSI", it)
            else String.format(Locale.US, "%+.2f BAR", ObdTelemetry.psiToBar(it))
        } ?: "--"
        val avgBoost = s.avgBoostPsi?.let {
            if (u == UnitSystem.IMPERIAL) String.format(Locale.US, "AVG %+.1f", it)
            else String.format(Locale.US, "AVG %+.2f", ObdTelemetry.psiToBar(it))
        } ?: "AVG --"
        addSummaryCard("BOOST / WMI", pkBoost, 0xFF38BDF8.toInt(), "$avgBoost • WMI ${s.boostPercentOfTrip.roundToInt()}%")

        val pkHp = s.peakHp?.let { "${it.roundToInt()} HP" } ?: "--"
        val pkTq = s.peakTorqueLbFt?.let {
            if (u == UnitSystem.IMPERIAL) "PK ${it.roundToInt()} LB-FT"
            else "PK ${ObdTelemetry.lbFtToNm(it).roundToInt()} Nm"
        } ?: "PK --"
        addSummaryCard("POWER / TORQUE", pkHp, 0xFF22C55E.toInt(), pkTq)

        val pkRpm = s.peakRpm?.let { "$it RPM" } ?: "--"
        val avgRpm = s.avgRpm?.let { "AVG ${it.roundToInt()}" } ?: "AVG --"
        val loadTps = buildString {
            s.maxLoadPercent?.let { append("LD ${it.roundToInt()}%") }
            s.maxThrottlePercent?.let {
                if (isNotEmpty()) append(" • ")
                append("TPS ${it.roundToInt()}%")
            }
        }.ifEmpty { avgRpm }
        addSummaryCard("ENGINE / LOAD", pkRpm, 0xFFF4F4F5.toInt(), "$avgRpm • $loadTps")

        val dist = TripStatsCalculator.formatDistance(s.distanceKm, u)
        val maxSpd = s.maxSpeedKmh?.let {
            if (u == UnitSystem.IMPERIAL) "MAX ${ObdTelemetry.kmhToMph(it).roundToInt()} MPH" else "MAX $it km/h"
        } ?: "MAX --"
        val avgSpd = s.avgSpeedKmh?.let {
            if (u == UnitSystem.IMPERIAL) "AVG ${ObdTelemetry.kmhToMph(it.roundToInt()).roundToInt()}"
            else "AVG ${it.roundToInt()}"
        } ?: ""
        addSummaryCard("SPEED / DIST", dist, 0xFFA78BFA.toInt(), if (avgSpd.isNotEmpty()) "$maxSpd • $avgSpd" else maxSpd)

        val pkIat = s.peakIatCelsius?.let { "${ObdTelemetry.formatTemp(it, u).roundToInt()}$tempUnit" } ?: "--"
        val avgIat = s.avgIatCelsius?.let { "AVG ${ObdTelemetry.formatTemp(it, u).roundToInt()}$tempUnit" } ?: "IAT --"
        val pkEct = s.peakCoolantCelsius?.let { "ECT ${ObdTelemetry.formatTemp(it, u).roundToInt()}$tempUnit" } ?: "ECT --"
        addSummaryCard("THERMALS (IAT/ECT)", pkIat, 0xFFF59E0B.toInt(), "$avgIat • $pkEct")

        val minVolt = s.minVoltage?.let { String.format(Locale.US, "%.1fV MIN", it) } ?: "--"
        val ignStr = if (s.minTimingDeg != null && s.maxTimingDeg != null) {
            String.format(Locale.US, "IGN %+.0f..%+.0f°", s.minTimingDeg, s.maxTimingDeg)
        } else {
            s.avgVoltage?.let { String.format(Locale.US, "AVG %.1fV", it) } ?: "ECU OK"
        }
        addSummaryCard("VOLTAGE / TIMING", minVolt, 0xFF818CF8.toInt(), ignStr)
    }

    private fun addSummaryCard(title: String, value: String, valueColor: Int, sub: String) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card_dark)
            setPadding(dp(9), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = dp(6)
            }
            minimumWidth = dp(128)
        }

        val tvTitle = TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
        }
        val tvVal = TextView(context).apply {
            text = value
            setTextColor(valueColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = racingFont
            includeFontPadding = false
            setPadding(0, dp(2), dp(2), dp(2))
            maxLines = 1
        }
        val tvSub = TextView(context).apply {
            text = sub
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            includeFontPadding = false
            maxLines = 1
        }

        card.addView(tvTitle)
        card.addView(tvVal)
        card.addView(tvSub)
        llDetailSummaryCards.addView(card)
    }

    private fun buildLegendChips(
        container: LinearLayout,
        metrics: List<TripMetric>,
        visibleSet: EnumSet<TripMetric>,
        valueViewOut: MutableMap<TripMetric, TextView>,
        onToggleChanged: () -> Unit
    ) {
        container.removeAllViews()
        valueViewOut.clear()

        val allChip = createTogglePill(
            dotColor = null,
            label = "ALL",
            initialSelected = visibleSet.containsAll(metrics)
        )
        allChip.first.setOnClickListener {
            if (visibleSet.containsAll(metrics)) {
                visibleSet.clear()
                visibleSet.add(TripMetric.BOOST)
                visibleSet.add(TripMetric.RPM)
            } else {
                visibleSet.addAll(TripMetric.values())
            }
            refreshChipSelections(container, visibleSet, metrics)
            onToggleChanged()
        }
        container.addView(allChip.first)

        for (metric in metrics) {
            val (chip, tvVal) = createTogglePill(
                dotColor = metric.colorInt,
                label = metric.shortLabel,
                initialSelected = metric in visibleSet
            )
            chip.tag = metric
            valueViewOut[metric] = tvVal
            chip.setOnClickListener {
                if (metric in visibleSet) {
                    if (visibleSet.size > 1) {
                        visibleSet.remove(metric)
                    } else {
                        visibleSet.addAll(TripMetric.values())
                    }
                } else {
                    visibleSet.add(metric)
                }
                refreshChipSelections(container, visibleSet, metrics)
                onToggleChanged()
            }
            container.addView(chip)
        }
    }

    private fun createTogglePill(
        dotColor: Int?,
        label: String,
        initialSelected: Boolean
    ): Pair<LinearLayout, TextView> {
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(25)
            ).apply {
                marginEnd = dp(5)
            }
        }
        applyPillBackground(pill, initialSelected, dotColor)

        if (dotColor != null) {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    marginEnd = dp(5)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
            }
            pill.addView(dot)
        }

        val tvName = TextView(context).apply {
            text = label
            setTextColor(if (initialSelected) 0xFFFFFFFF.toInt() else 0xFF71717A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        val tvVal = TextView(context).apply {
            text = ""
            setTextColor(dotColor ?: 0xFFD4D4D8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(android.graphics.Typeface.MONOSPACE)
            maxLines = 1
            visibility = View.GONE
            setPadding(dp(4), 0, 0, 0)
        }

        pill.addView(tvName)
        pill.addView(tvVal)
        return Pair(pill, tvVal)
    }

    private fun refreshChipSelections(
        container: LinearLayout,
        visibleSet: EnumSet<TripMetric>,
        metrics: List<TripMetric>
    ) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) as? LinearLayout ?: continue
            val metric = child.tag as? TripMetric
            val isSelected = if (metric == null) {
                visibleSet.containsAll(metrics)
            } else {
                metric in visibleSet
            }
            applyPillBackground(child, isSelected, metric?.colorInt)
            val labelIdx = if (metric == null) 0 else 1
            (child.getChildAt(labelIdx) as? TextView)?.setTextColor(
                if (isSelected) 0xFFFFFFFF.toInt() else 0xFF71717A.toInt()
            )
        }
    }

    private fun applyPillBackground(pill: LinearLayout, selected: Boolean, dotColor: Int?) {
        pill.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(if (selected) 0xFF141418.toInt() else 0xFF09090B.toInt())
            setStroke(
                dp(1),
                when {
                    selected && dotColor != null -> (dotColor and 0x00FFFFFF) or 0x88000000.toInt()
                    selected -> 0xFF71717A.toInt()
                    else -> 0xFF222228.toInt()
                }
            )
        }
    }

    private fun updateLiveLegendValues(sample: TripSample?) {
        for ((metric, tv) in liveChipValueViews) {
            if (sample == null) {
                tv.visibility = View.GONE
            } else {
                val formatted = metric.formatValue(sample, currentUnitSystem)
                if (formatted == "--") {
                    tv.visibility = View.GONE
                } else {
                    tv.text = formatted
                    tv.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun updateDetailLegendValues(sample: TripSample?) {
        for ((metric, tv) in detailChipValueViews) {
            if (sample == null) {
                tv.visibility = View.GONE
            } else {
                val formatted = metric.formatValue(sample, currentUnitSystem)
                if (formatted == "--") {
                    tv.visibility = View.GONE
                } else {
                    tv.text = formatted
                    tv.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun updateDetailFooter() {
        val sample = detailScrubSample
        if (sample != null) {
            val t = TripStatsCalculator.formatScrubTime(sample.elapsedMs)
            val wmi = if (sample.wmiActive) "WMI ON (in boost)" else "Vacuum / Standby"
            tvDetailFooter.text = "Inspecting @ $t • $wmi • Tap graph again to clear crosshair"
            return
        }
        val s = openedRecord?.summary
        tvDetailFooter.text = if (s != null) {
            val insight = TripStatsCalculator.buildInsightText(
                peakBoostPsi = s.peakBoostPsi,
                peakHp = s.peakHp,
                boostPct = s.boostPercentOfTrip,
                timeInBoostMs = s.timeInBoostMs,
                peakIatC = s.peakIatCelsius,
                minVoltage = s.minVoltage,
                unitSystem = currentUnitSystem
            )
            "$insight  •  Drag horizontally to scroll • Tap point to inspect"
        } else {
            "Drag horizontally to scroll • Tap any point to inspect values"
        }
    }

    private fun promptRenameTrip(summary: TripSummary) {
        val input = EditText(context).apply {
            setText(summary.title)
            setSelection(summary.title.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(0xFFFFFFFF.toInt())
        }
        AlertDialog.Builder(context)
            .setTitle("Rename Trip")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    TripRecorder.renameTrip(summary.id, newTitle)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteTrip(summary: TripSummary) {
        AlertDialog.Builder(context)
            .setTitle("Delete Trip?")
            .setMessage("Delete '${summary.title}' (${summary.sampleCount} samples)? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                TripRecorder.deleteTrip(summary.id)
                if (openedRecord?.summary?.id == summary.id) {
                    closeDetail()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDiscardLiveRecording() {
        AlertDialog.Builder(context)
            .setTitle("Discard Recording?")
            .setMessage("Stop recording and discard the current trip data without saving?")
            .setPositiveButton("Discard") { _, _ ->
                TripRecorder.discardRecording()
            }
            .setNegativeButton("Keep Recording", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * density).roundToInt()
}
