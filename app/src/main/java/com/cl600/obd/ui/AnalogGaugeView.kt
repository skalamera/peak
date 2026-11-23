package com.cl600.obd.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class AnalogGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class GaugeZone(
        val startValue: Float,
        val endValue: Float,
        val color: Int
    )

    private var title: String = "GAUGE"
    private var unit: String = ""
    private var minValue: Float = 0f
    private var maxValue: Float = 100f
    private var majorStep: Float = 20f
    private var minorDivisions: Int = 4
    private var zones: List<GaugeZone> = emptyList()
    private var warningThreshold: Float? = null
    private var glowThreshold: Float? = null
    private var glowColor: Int = Color.parseColor("#38BDF8")
    private var decimalPlaces: Int = 0
    private var showPlusSign: Boolean = false
    private var subTextFormatter: ((Float?, Float?) -> String?)? = null

    private var currentValue: Float? = null
    private var peakValue: Float? = null
    private var animatedNeedleValue: Float = 0f
    private var needleAnimator: ValueAnimator? = null
    private var resetFlashAlpha: Float = 0f
    private var flashAnimator: ValueAnimator? = null

    private val startAngleDeg = 145f
    private val sweepAngleDeg = 250f

    private val bgFacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowFacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowSweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val glowHubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val outerArchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val innerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#19191D")
        strokeCap = Paint.Cap.ROUND
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        strokeCap = Paint.Cap.ROUND
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A40")
        strokeCap = Paint.Cap.ROUND
    }
    private val scaleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4D4D8")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#71717A")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val valueBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#050506")
    }
    private val valueBoxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#202024")
    }
    private val digitalValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A1A1AA")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#63636A")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }
    private val needleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }
    private val hubFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#000000")
        style = Paint.Style.FILL
    }
    private val hubRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
    }

    private val outerArchRect = RectF()
    private val arcRect = RectF()
    private val boxRect = RectF()
    private val needlePath = Path()
    private val peakPath = Path()

    init {
        if (!isInEditMode) {
            val semi = FontCache.racingSemiBoldItalic(context)
            val bold = FontCache.racingBoldItalic(context)
            scaleLabelPaint.typeface = semi
            subTextPaint.typeface = semi
            digitalValuePaint.typeface = bold
        }
    }

    fun configure(
        title: String,
        unit: String,
        minValue: Float,
        maxValue: Float,
        majorStep: Float,
        minorDivisions: Int,
        zones: List<GaugeZone>,
        warningThreshold: Float? = null,
        decimalPlaces: Int = 0,
        showPlusSign: Boolean = false,
        glowThreshold: Float? = null,
        glowColor: Int = Color.parseColor("#38BDF8"),
        subTextFormatter: ((Float?, Float?) -> String?)? = null
    ) {
        this.title = title
        this.unit = unit
        this.minValue = minValue
        this.maxValue = maxValue
        this.majorStep = majorStep
        this.minorDivisions = minorDivisions.coerceAtLeast(1)
        this.zones = zones
        this.warningThreshold = warningThreshold
        this.decimalPlaces = decimalPlaces
        this.showPlusSign = showPlusSign
        this.glowThreshold = glowThreshold
        this.glowColor = glowColor
        this.subTextFormatter = subTextFormatter

        if (width > 0 && height > 0) {
            rebuildShaders(width, height)
        }
        if (currentValue == null) {
            animatedNeedleValue = minValue
        } else {
            animatedNeedleValue = currentValue!!.coerceIn(minValue, maxValue)
        }
        invalidate()
    }

    fun setValue(value: Float?, peak: Float? = null) {
        val valueChanged = currentValue != value || peakValue != peak
        currentValue = value
        peakValue = peak
        if (!valueChanged) return

        val target = (value ?: minValue).coerceIn(minValue, maxValue)
        needleAnimator?.cancel()
        val start = animatedNeedleValue
        if (kotlin.math.abs(target - start) < 0.01f) {
            animatedNeedleValue = target
            invalidate()
            return
        }

        needleAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                animatedNeedleValue = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun flashReset() {
        flashAnimator?.cancel()
        flashAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 280L
            addUpdateListener { anim ->
                resetFlashAlpha = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        needleAnimator?.cancel()
        flashAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        rebuildShaders(w, h)
    }

    private fun rebuildShaders(w: Int, h: Int) {
        val cx = w / 2f
        val cy = h * 0.52f
        val r = min(w, h) * 0.46f
        if (r > 0f) {
            bgFacePaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    Color.parseColor("#0E0E10"),
                    Color.parseColor("#060607"),
                    Color.parseColor("#000000")
                ),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            glowFacePaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    withAlpha(glowColor, 76),
                    withAlpha(glowColor, 26),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h * 0.52f
        val radius = min(w * 0.46f, h * 0.46f)
        if (radius <= 12f) return

        val isAlert = currentValue != null &&
            warningThreshold != null &&
            currentValue!! >= warningThreshold!!

        val glowThresh = glowThreshold
        val glowIntensity = if (glowThresh != null &&
            currentValue != null &&
            !isAlert &&
            animatedNeedleValue > glowThresh
        ) {
            val denom = ((warningThreshold ?: maxValue) - glowThresh).coerceAtLeast(1f)
            val progress = ((animatedNeedleValue - glowThresh) / denom).coerceIn(0f, 1f)
            (0.45f + 0.55f * progress).coerceIn(0f, 1f)
        } else {
            0f
        }
        val isGlowing = glowIntensity > 0.01f

        // 1. Pure black dial face & signature Eurocharged clean white (or blue-glow) open arch
        canvas.drawCircle(cx, cy, radius, bgFacePaint)
        if (isGlowing) {
            glowFacePaint.alpha = (255f * glowIntensity).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, glowFacePaint)
        }

        val archR = radius * 0.94f
        outerArchRect.set(cx - archR, cy - archR, cx + archR, cy + archR)
        val archStroke = (radius * 0.036f).coerceAtLeast(2.2f)
        if (isGlowing) {
            glowStrokePaint.strokeWidth = archStroke * 3.4f
            glowStrokePaint.color = withAlpha(glowColor, (38f * glowIntensity).roundToInt())
            canvas.drawArc(outerArchRect, startAngleDeg, sweepAngleDeg, false, glowStrokePaint)

            glowStrokePaint.strokeWidth = archStroke * 1.9f
            glowStrokePaint.color = withAlpha(glowColor, (90f * glowIntensity).roundToInt())
            canvas.drawArc(outerArchRect, startAngleDeg, sweepAngleDeg, false, glowStrokePaint)
        }
        outerArchPaint.strokeWidth = archStroke
        outerArchPaint.color = when {
            isAlert -> Color.parseColor("#EF4444")
            isGlowing -> glowColor
            else -> Color.parseColor("#FFFFFF")
        }
        canvas.drawArc(outerArchRect, startAngleDeg, sweepAngleDeg, false, outerArchPaint)

        // 2. Subtle inner hairline track & restrained zone arcs
        val zoneRadius = radius * 0.855f
        arcRect.set(cx - zoneRadius, cy - zoneRadius, cx + zoneRadius, cy + zoneRadius)
        innerTrackPaint.strokeWidth = (radius * 0.022f).coerceAtLeast(1.5f)
        canvas.drawArc(arcRect, startAngleDeg, sweepAngleDeg, false, innerTrackPaint)

        val zoneStroke = (radius * 0.022f).coerceAtLeast(1.5f)
        zonePaint.strokeWidth = zoneStroke

        val span = (maxValue - minValue).coerceAtLeast(0.001f)
        for (zone in zones) {
            val zStart = zone.startValue.coerceIn(minValue, maxValue)
            val zEnd = zone.endValue.coerceIn(minValue, maxValue)
            if (zEnd <= zStart) continue
            val startAng = startAngleDeg + ((zStart - minValue) / span) * sweepAngleDeg
            val sweep = ((zEnd - zStart) / span) * sweepAngleDeg
            zonePaint.color = zone.color
            canvas.drawArc(arcRect, startAng, sweep, false, zonePaint)
        }

        // Illuminated positive-boost sweep arc from glowThreshold up to active needle
        if (isGlowing && glowThresh != null) {
            val gStart = glowThresh.coerceIn(minValue, maxValue)
            val gEnd = animatedNeedleValue.coerceIn(gStart, maxValue)
            if (gEnd > gStart) {
                val gStartAng = startAngleDeg + ((gStart - minValue) / span) * sweepAngleDeg
                val gSweep = ((gEnd - gStart) / span) * sweepAngleDeg
                glowSweepPaint.strokeWidth = zoneStroke * 2.8f
                glowSweepPaint.color = withAlpha(glowColor, (72f * glowIntensity).roundToInt())
                canvas.drawArc(arcRect, gStartAng, gSweep, false, glowSweepPaint)

                glowSweepPaint.strokeWidth = zoneStroke * 1.4f
                glowSweepPaint.color = withAlpha(Color.parseColor("#7DD3FC"), (215f * glowIntensity).roundToInt())
                canvas.drawArc(arcRect, gStartAng, gSweep, false, glowSweepPaint)
            }
        }

        // 3. Ticks & numeric scale labels
        val tickOuter = radius * 0.89f
        val majorTickInner = radius * 0.77f
        val minorTickInner = radius * 0.825f
        val labelRadius = radius * 0.65f

        majorTickPaint.strokeWidth = (radius * 0.016f).coerceAtLeast(1.5f)
        minorTickPaint.strokeWidth = (radius * 0.009f).coerceAtLeast(1f)
        scaleLabelPaint.textSize = (radius * 0.118f).coerceAtLeast(10f)

        val totalMajorSteps = ((maxValue - minValue) / majorStep).roundToInt().coerceAtLeast(1)
        val totalMinorSteps = totalMajorSteps * minorDivisions

        for (i in 0..totalMinorSteps) {
            val frac = i.toFloat() / totalMinorSteps.toFloat()
            val angleDeg = startAngleDeg + frac * sweepAngleDeg
            val rad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val isMajor = (i % minorDivisions == 0)
            val innerR = if (isMajor) majorTickInner else minorTickInner
            val paint = if (isMajor) majorTickPaint else minorTickPaint

            canvas.drawLine(
                cx + cosA * innerR,
                cy + sinA * innerR,
                cx + cosA * tickOuter,
                cy + sinA * tickOuter,
                paint
            )

            if (isMajor) {
                val stepValue = minValue + (i / minorDivisions) * majorStep
                val label = formatScaleLabel(stepValue)
                val lx = cx + cosA * labelRadius
                val ly = cy + sinA * labelRadius + (scaleLabelPaint.textSize * 0.36f)
                canvas.drawText(label, lx, ly, scaleLabelPaint)
            }
        }

        // 4. Peak-hold indicator marker on outer ring
        peakValue?.let { pk ->
            val clampedPk = pk.coerceIn(minValue, maxValue)
            val pkAngle = startAngleDeg + ((clampedPk - minValue) / span) * sweepAngleDeg
            val pkRad = Math.toRadians(pkAngle.toDouble())
            val perpRad = pkRad + Math.PI / 2.0

            val tipR = radius * 0.77f
            val baseR = radius * 0.905f
            val halfW = radius * 0.036f

            peakPath.reset()
            peakPath.moveTo(
                cx + cos(pkRad).toFloat() * tipR,
                cy + sin(pkRad).toFloat() * tipR
            )
            peakPath.lineTo(
                cx + cos(pkRad).toFloat() * baseR + cos(perpRad).toFloat() * halfW,
                cy + sin(pkRad).toFloat() * baseR + sin(perpRad).toFloat() * halfW
            )
            peakPath.lineTo(
                cx + cos(pkRad).toFloat() * baseR - cos(perpRad).toFloat() * halfW,
                cy + sin(pkRad).toFloat() * baseR - sin(perpRad).toFloat() * halfW
            )
            peakPath.close()
            canvas.drawPath(peakPath, peakPaint)
        }

        // 5. Title above center hub
        titlePaint.textSize = (radius * 0.115f).coerceAtLeast(10f)
        canvas.drawText(title, cx, cy - radius * 0.22f, titlePaint)

        // 6. Recessed digital display window in bottom mouth of dial
        val boxHalfW = radius * 0.36f
        val boxTop = cy + radius * 0.30f
        val boxBottom = cy + radius * 0.87f
        boxRect.set(cx - boxHalfW, boxTop, cx + boxHalfW, boxBottom)
        val baseBoxStroke = (radius * 0.012f).coerceAtLeast(1f)
        canvas.drawRoundRect(boxRect, radius * 0.08f, radius * 0.08f, valueBoxPaint)
        if (resetFlashAlpha > 0.01f) {
            valueBoxBorderPaint.color = Color.argb((80 + 175 * resetFlashAlpha).toInt().coerceIn(0, 255), 255, 255, 255)
            valueBoxBorderPaint.strokeWidth = baseBoxStroke * (1f + resetFlashAlpha)
            canvas.drawRoundRect(boxRect, radius * 0.08f, radius * 0.08f, valueBoxBorderPaint)
        } else if (isGlowing) {
            valueBoxBorderPaint.color = withAlpha(glowColor, (60f * glowIntensity).roundToInt())
            valueBoxBorderPaint.strokeWidth = baseBoxStroke * 2.4f
            canvas.drawRoundRect(boxRect, radius * 0.08f, radius * 0.08f, valueBoxBorderPaint)

            valueBoxBorderPaint.color = withAlpha(glowColor, (150f + 95f * glowIntensity).roundToInt())
            valueBoxBorderPaint.strokeWidth = baseBoxStroke * 1.3f
            canvas.drawRoundRect(boxRect, radius * 0.08f, radius * 0.08f, valueBoxBorderPaint)
        } else {
            valueBoxBorderPaint.color = Color.parseColor("#202024")
            valueBoxBorderPaint.strokeWidth = baseBoxStroke
            canvas.drawRoundRect(boxRect, radius * 0.08f, radius * 0.08f, valueBoxBorderPaint)
        }

        val formattedVal = formatDigitalValue(currentValue)
        digitalValuePaint.textSize = (radius * 0.205f).coerceAtLeast(14f)
        digitalValuePaint.color = when {
            currentValue == null -> Color.parseColor("#52525B")
            isAlert -> Color.parseColor("#EF4444")
            isGlowing -> Color.parseColor("#7DD3FC")
            else -> Color.parseColor("#FFFFFF")
        }
        canvas.drawText(formattedVal, cx, cy + radius * 0.53f, digitalValuePaint)

        unitPaint.textSize = (radius * 0.11f).coerceAtLeast(9f)
        canvas.drawText(unit, cx, cy + radius * 0.68f, unitPaint)

        val subText = subTextFormatter?.invoke(currentValue, peakValue)
        if (!subText.isNullOrBlank()) {
            subTextPaint.textSize = (radius * 0.092f).coerceAtLeast(8f)
            canvas.drawText(subText, cx, cy + radius * 0.81f, subTextPaint)
        }

        // 7. Signature Eurocharged razor-tapered pure-white needle & minimalist hub
        val needleFrac = ((animatedNeedleValue.coerceIn(minValue, maxValue) - minValue) / span)
        val needleAngleDeg = startAngleDeg + needleFrac * sweepAngleDeg
        val nRad = Math.toRadians(needleAngleDeg.toDouble())
        val perpRad = nRad + Math.PI / 2.0

        val tipLen = radius * 0.80f
        val tailLen = radius * 0.14f
        val baseHalf = radius * 0.026f
        val tipHalf = radius * 0.004f

        val cosN = cos(nRad).toFloat()
        val sinN = sin(nRad).toFloat()
        val cosP = cos(perpRad).toFloat()
        val sinP = sin(perpRad).toFloat()

        needlePaint.color = if (isAlert) Color.parseColor("#EF4444") else Color.parseColor("#FFFFFF")
        needlePaint.alpha = if (currentValue == null) 85 else 255

        // Slight shadow
        needlePath.reset()
        needlePath.moveTo(cx - cosN * tailLen + cosP * baseHalf, cy - sinN * tailLen + sinP * baseHalf + 2f)
        needlePath.lineTo(cx + cosN * tipLen + cosP * tipHalf, cy + sinN * tipLen + sinP * tipHalf + 2f)
        needlePath.lineTo(cx + cosN * tipLen - cosP * tipHalf, cy + sinN * tipLen - sinP * tipHalf + 2f)
        needlePath.lineTo(cx - cosN * tailLen - cosP * baseHalf, cy - sinN * tailLen - sinP * baseHalf + 2f)
        needlePath.close()
        canvas.drawPath(needlePath, needleShadowPaint)

        // Needle body
        needlePath.reset()
        needlePath.moveTo(cx - cosN * tailLen + cosP * baseHalf, cy - sinN * tailLen + sinP * baseHalf)
        needlePath.lineTo(cx + cosN * tipLen + cosP * tipHalf, cy + sinN * tipLen + sinP * tipHalf)
        needlePath.lineTo(cx + cosN * tipLen - cosP * tipHalf, cy + sinN * tipLen - sinP * tipHalf)
        needlePath.lineTo(cx - cosN * tailLen - cosP * baseHalf, cy - sinN * tailLen - sinP * baseHalf)
        needlePath.close()
        canvas.drawPath(needlePath, needlePaint)

        // Minimalist black hub with crisp white (or electric-blue glowing) ring
        val hubR = radius * 0.088f
        canvas.drawCircle(cx, cy, hubR, hubFillPaint)
        val hubStroke = (radius * 0.018f).coerceAtLeast(1.5f)
        if (isGlowing) {
            glowHubPaint.strokeWidth = hubStroke * 2.6f
            glowHubPaint.color = withAlpha(glowColor, (85f * glowIntensity).roundToInt())
            canvas.drawCircle(cx, cy, hubR, glowHubPaint)
        }
        hubRingPaint.strokeWidth = hubStroke
        hubRingPaint.color = when {
            isAlert -> Color.parseColor("#EF4444")
            isGlowing -> glowColor
            else -> Color.parseColor("#FFFFFF")
        }
        canvas.drawCircle(cx, cy, hubR, hubRingPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    private fun formatScaleLabel(value: Float): String {
        val roundedInt = value.roundToInt()
        return if (kotlin.math.abs(value - roundedInt) < 0.05f) {
            roundedInt.toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    private fun formatDigitalValue(value: Float?): String {
        if (value == null) return "--"
        val prefix = if (showPlusSign && value > 0.05f) "+" else ""
        return when (decimalPlaces) {
            0 -> "$prefix${value.roundToInt()}"
            1 -> "$prefix${String.format(Locale.US, "%.1f", value)}"
            else -> "$prefix${String.format(Locale.US, "%.2f", value)}"
        }
    }
}
