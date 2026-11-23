package com.cl600.obd.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cl600.obd.R
import com.cl600.obd.model.ObdTelemetry
import com.cl600.obd.model.TripSummary
import com.cl600.obd.model.UnitSystem
import com.cl600.obd.trips.TripStatsCalculator
import java.util.Locale
import kotlin.math.roundToInt

class TripsAdapter(
    private val onSelect: (TripSummary) -> Unit,
    private val onDelete: (TripSummary) -> Unit
) : RecyclerView.Adapter<TripsAdapter.Holder>() {

    private val items = ArrayList<TripSummary>()
    private var unitSystem: UnitSystem = UnitSystem.IMPERIAL

    fun submitList(list: List<TripSummary>, units: UnitSystem) {
        items.clear()
        items.addAll(list)
        unitSystem = units
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_trip, parent, false)
        return Holder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val s = items[position]
        val racingFont = FontCache.racingBoldItalic(holder.itemView.context)
        holder.tvPeakBoost.typeface = racingFont
        holder.tvPeakHp.typeface = racingFont
        holder.tvMaxRpm.typeface = racingFont
        holder.tvPeakIat.typeface = racingFont
        holder.tvMaxSpeed.typeface = racingFont
        holder.tvBoostPct.typeface = racingFont

        holder.tvTitle.text = s.title
        holder.tvDemoBadge.visibility = if (s.isDemo) View.VISIBLE else View.GONE

        val dur = TripStatsCalculator.formatDuration(s.durationMs)
        val dist = TripStatsCalculator.formatDistance(s.distanceKm, unitSystem)
        holder.tvDurationDist.text = "$dur • $dist • ${s.sampleCount} pts"

        holder.tvPeakBoost.text = s.peakBoostPsi?.let {
            if (unitSystem == UnitSystem.IMPERIAL) {
                String.format(Locale.US, "%+.1f PSI", it)
            } else {
                String.format(Locale.US, "%+.2f BAR", ObdTelemetry.psiToBar(it))
            }
        } ?: "--"

        holder.tvPeakHp.text = s.peakHp?.let { "${it.roundToInt()} HP" } ?: "--"
        holder.tvMaxRpm.text = s.peakRpm?.toString() ?: "--"

        val tempUnit = if (unitSystem == UnitSystem.IMPERIAL) "°F" else "°C"
        holder.tvPeakIat.text = s.peakIatCelsius?.let {
            "${ObdTelemetry.formatTemp(it, unitSystem).roundToInt()}$tempUnit"
        } ?: "--"

        holder.tvMaxSpeed.text = s.maxSpeedKmh?.let { kmh ->
            if (unitSystem == UnitSystem.IMPERIAL) {
                "${ObdTelemetry.kmhToMph(kmh).roundToInt()} MPH"
            } else {
                "$kmh km/h"
            }
        } ?: "--"

        holder.tvBoostPct.text = "${s.boostPercentOfTrip.roundToInt()}%"

        holder.tvInsight.text = TripStatsCalculator.buildInsightText(
            peakBoostPsi = s.peakBoostPsi,
            peakHp = s.peakHp,
            boostPct = s.boostPercentOfTrip,
            timeInBoostMs = s.timeInBoostMs,
            peakIatC = s.peakIatCelsius,
            minVoltage = s.minVoltage,
            unitSystem = unitSystem
        )

        holder.itemView.setOnClickListener { onSelect(s) }
        holder.btnDelete.setOnClickListener { onDelete(s) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvTripTitle)
        val tvDemoBadge: TextView = view.findViewById(R.id.tvTripDemoBadge)
        val tvDurationDist: TextView = view.findViewById(R.id.tvTripDurationDist)
        val btnDelete: TextView = view.findViewById(R.id.btnDeleteTripItem)
        val tvPeakBoost: TextView = view.findViewById(R.id.tvItemPeakBoost)
        val tvPeakHp: TextView = view.findViewById(R.id.tvItemPeakHp)
        val tvMaxRpm: TextView = view.findViewById(R.id.tvItemMaxRpm)
        val tvPeakIat: TextView = view.findViewById(R.id.tvItemPeakIat)
        val tvMaxSpeed: TextView = view.findViewById(R.id.tvItemMaxSpeed)
        val tvBoostPct: TextView = view.findViewById(R.id.tvItemBoostPct)
        val tvInsight: TextView = view.findViewById(R.id.tvTripInsight)
    }
}
