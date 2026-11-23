package com.cl600.obd.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.cl600.obd.R

class DigitalGaugeCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class AlertLevel {
        INACTIVE,
        NORMAL,
        WARN,
        CRIT
    }

    private val rootCard: View
    private val titleView: TextView
    private val hintView: TextView
    private val valueView: TextView
    private val unitView: TextView
    private val subView: TextView
    private val barFill: View

    init {
        LayoutInflater.from(context).inflate(R.layout.view_digital_gauge_card, this, true)
        rootCard = findViewById(R.id.cardRoot)
        titleView = findViewById(R.id.tvCardTitle)
        hintView = findViewById(R.id.tvCardHint)
        valueView = findViewById(R.id.tvCardValue)
        unitView = findViewById(R.id.tvCardUnit)
        subView = findViewById(R.id.tvCardSub)
        barFill = findViewById(R.id.viewBarFill)

        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            valueView,
            16,
            72,
            2,
            TypedValue.COMPLEX_UNIT_SP
        )
    }

    fun setHeader(title: String, hint: String? = null) {
        titleView.text = title
        if (hint.isNullOrBlank()) {
            hintView.visibility = View.GONE
        } else {
            hintView.visibility = View.VISIBLE
            hintView.text = hint
        }
    }

    fun updateValue(
        displayValue: String,
        unit: String,
        subLabel: String,
        progressRatio: Float,
        alertLevel: AlertLevel
    ) {
        valueView.text = displayValue
        unitView.text = unit
        subView.text = subLabel

        val bgRes = when (alertLevel) {
            AlertLevel.WARN -> R.drawable.bg_card_warn
            AlertLevel.CRIT -> R.drawable.bg_card_crit
            else -> R.drawable.bg_card_dark
        }
        rootCard.setBackgroundResource(bgRes)

        val valueColor = when (alertLevel) {
            AlertLevel.INACTIVE -> ContextCompat.getColor(context, R.color.text_muted)
            AlertLevel.NORMAL -> ContextCompat.getColor(context, R.color.text_primary)
            AlertLevel.WARN -> ContextCompat.getColor(context, R.color.accent_amber)
            AlertLevel.CRIT -> ContextCompat.getColor(context, R.color.status_error)
        }
        valueView.setTextColor(valueColor)

        val barColor = when (alertLevel) {
            AlertLevel.INACTIVE -> Color.parseColor("#27272A")
            AlertLevel.NORMAL -> Color.parseColor("#FFFFFF")
            AlertLevel.WARN -> ContextCompat.getColor(context, R.color.alert_warn_border)
            AlertLevel.CRIT -> ContextCompat.getColor(context, R.color.status_error)
        }
        barFill.setBackgroundColor(barColor)
        barFill.pivotX = 0f
        barFill.scaleX = progressRatio.coerceIn(0.02f, 1.0f)
    }
}
