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
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Prominent Eurocharged-styled Water / Methanol Injection status module with
 * a vector-drawn droplet + injector spray graphic and pulsing active LED beacon.
 */
class WmiIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class WmiState {
        OFF,
        STANDBY,
        ACTIVE
    }

    private var currentState: WmiState = WmiState.OFF
    private var pulsePhase: Float = 0f
    private var pulseAnimator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emblemFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emblemRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val dropFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dropStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val sprayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val sprayDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }

    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ledRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#ECFDF5")
    }

    private val pillRect = RectF()
    private val dropPath = Path()

    init {
        contentDescription = "WMI: OFF"
    }

    fun setWmiState(state: WmiState) {
        if (currentState == state) return
        currentState = state
        contentDescription = "WMI: ${state.name}"

        if (state == WmiState.ACTIVE) {
            startPulse()
        } else {
            stopPulse()
        }
        invalidate()
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                pulsePhase = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulsePhase = 0f
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val isActive = currentState == WmiState.ACTIVE
        val dp = resources.displayMetrics.density
        val strokeW = (if (isActive) 2.2f else 1.3f) * dp
        val halfStroke = strokeW / 2f
        val cornerR = h * 0.26f

        // 1. Pill Container
        pillRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        bgPaint.color = if (isActive) {
            // Subtle breathing emerald tint while injecting
            val greenChannel = (34 + (14 * pulsePhase)).toInt()
            Color.rgb(6, greenChannel, 18)
        } else {
            Color.parseColor("#0A0A0B")
        }
        canvas.drawRoundRect(pillRect, cornerR, cornerR, bgPaint)

        borderPaint.strokeWidth = strokeW
        borderPaint.color = if (isActive) Color.parseColor("#22C55E") else Color.parseColor("#26262B")
        canvas.drawRoundRect(pillRect, cornerR, cornerR, borderPaint)

        // 2. Left Droplet + Injector Spray Graphic Emblem
        val pad = h * 0.14f
        val cy = h / 2f
        val iconR = h * 0.34f
        val iconCx = pad + iconR + h * 0.04f

        if (isActive) {
            val haloR = iconR * (1.35f + 0.15f * pulsePhase)
            glowPaint.shader = RadialGradient(
                iconCx, cy, haloR,
                intArrayOf(Color.parseColor("#5522C55E"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(iconCx, cy, haloR, glowPaint)
            glowPaint.shader = null
        }

        emblemFillPaint.color = if (isActive) Color.parseColor("#0B3B20") else Color.parseColor("#111114")
        canvas.drawCircle(iconCx, cy, iconR, emblemFillPaint)

        emblemRingPaint.strokeWidth = (1.3f * dp)
        emblemRingPaint.color = if (isActive) Color.parseColor("#4ADE80") else Color.parseColor("#2E2E34")
        canvas.drawCircle(iconCx, cy, iconR, emblemRingPaint)

        drawDropletAndSpray(canvas, iconCx, cy, iconR, isActive, dp)

        // 3. Right Beacon LED
        val ledR = h * 0.14f
        val ledCx = w - pad - ledR - h * 0.08f

        if (isActive) {
            val ledHaloR = ledR * (2.0f + 0.45f * pulsePhase)
            glowPaint.shader = RadialGradient(
                ledCx, cy, ledHaloR,
                intArrayOf(Color.parseColor("#8822C55E"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(ledCx, cy, ledHaloR, glowPaint)
            glowPaint.shader = null

            ledPaint.color = Color.parseColor("#22C55E")
            canvas.drawCircle(ledCx, cy, ledR, ledPaint)

            ledRingPaint.strokeWidth = 1.6f * dp
            ledRingPaint.color = Color.parseColor("#BBF7D0")
            canvas.drawCircle(ledCx, cy, ledR, ledRingPaint)

            specularPaint.alpha = 220
            canvas.drawCircle(ledCx - ledR * 0.28f, cy - ledR * 0.28f, ledR * 0.26f, specularPaint)
        } else {
            ledPaint.color = Color.parseColor("#18181C")
            canvas.drawCircle(ledCx, cy, ledR, ledPaint)

            ledRingPaint.strokeWidth = 1.2f * dp
            ledRingPaint.color = Color.parseColor("#3A3A40")
            canvas.drawCircle(ledCx, cy, ledR, ledRingPaint)
        }

        // 4. Center Title + Status Text (auto-fitted cleanly between emblem and beacon)
        val textLeft = iconCx + iconR + h * 0.20f
        val textRight = ledCx - ledR - h * 0.18f
        val availW = (textRight - textLeft).coerceAtLeast(20f)

        titlePaint.textSize = (h * 0.24f).coerceAtLeast(10f)
        titlePaint.color = if (isActive) Color.parseColor("#FFFFFF") else Color.parseColor("#D4D4D8")
        fitTextScale(titlePaint, "WATER / METH", availW)
        canvas.drawText("WATER / METH", textLeft, cy - h * 0.05f, titlePaint)

        val statusLabel = when (currentState) {
            WmiState.ACTIVE -> "ACTIVE • INJECTING"
            WmiState.STANDBY -> "STANDBY"
            WmiState.OFF -> "OFF"
        }
        statusPaint.textSize = (h * 0.19f).coerceAtLeast(8.5f)
        statusPaint.color = when (currentState) {
            WmiState.ACTIVE -> Color.parseColor("#4ADE80")
            WmiState.STANDBY -> Color.parseColor("#71717A")
            WmiState.OFF -> Color.parseColor("#52525B")
        }
        fitTextScale(statusPaint, statusLabel, availW)
        canvas.drawText(statusLabel, textLeft, cy + h * 0.26f, statusPaint)
    }

    private fun drawDropletAndSpray(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        iconR: Float,
        isActive: Boolean,
        dp: Float
    ) {
        val dropCx = cx - iconR * 0.18f
        val dropTop = cy - iconR * 0.56f
        val dropBot = cy + iconR * 0.52f
        val halfW = iconR * 0.36f

        dropPath.reset()
        dropPath.moveTo(dropCx, dropTop)
        dropPath.cubicTo(
            dropCx + halfW * 0.85f, cy - iconR * 0.04f,
            dropCx + halfW, cy + iconR * 0.24f,
            dropCx, dropBot
        )
        dropPath.cubicTo(
            dropCx - halfW, cy + iconR * 0.24f,
            dropCx - halfW * 0.85f, cy - iconR * 0.04f,
            dropCx, dropTop
        )
        dropPath.close()

        if (isActive) {
            dropFillPaint.color = Color.parseColor("#22C55E")
            canvas.drawPath(dropPath, dropFillPaint)

            dropStrokePaint.strokeWidth = 1.4f * dp
            dropStrokePaint.color = Color.parseColor("#BBF7D0")
            canvas.drawPath(dropPath, dropStrokePaint)

            // Specular highlight inside droplet
            specularPaint.alpha = 200
            canvas.drawCircle(dropCx - halfW * 0.26f, cy + iconR * 0.12f, iconR * 0.08f, specularPaint)
        } else {
            dropStrokePaint.strokeWidth = 1.6f * dp
            dropStrokePaint.color = Color.parseColor("#71717A")
            canvas.drawPath(dropPath, dropStrokePaint)
        }

        // Atomized injector mist / spray rays fanning to the right of the droplet
        val originX = dropCx + halfW * 0.65f
        val originY = cy
        val rStart = iconR * 0.20f
        val rEnd = iconR * 0.54f
        val anglesDeg = floatArrayOf(-30f, 0f, 30f)

        sprayPaint.strokeWidth = (if (isActive) 1.7f else 1.2f) * dp
        sprayPaint.color = if (isActive) {
            Color.argb((185 + 70 * pulsePhase).toInt().coerceIn(0, 255), 134, 239, 172)
        } else {
            Color.parseColor("#3F3F46")
        }
        sprayDotPaint.color = sprayPaint.color

        for (deg in anglesDeg) {
            val rad = Math.toRadians(deg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val sx = originX + cosA * rStart
            val sy = originY + sinA * rStart
            val ex = originX + cosA * rEnd
            val ey = originY + sinA * rEnd
            canvas.drawLine(sx, sy, ex, ey, sprayPaint)
            if (isActive) {
                canvas.drawCircle(
                    originX + cosA * (rEnd + iconR * 0.12f),
                    originY + sinA * (rEnd + iconR * 0.12f),
                    min(2.2f * dp, iconR * 0.075f),
                    sprayDotPaint
                )
            }
        }
    }

    private fun fitTextScale(paint: Paint, text: String, maxWidth: Float) {
        paint.textScaleX = 1.0f
        val measured = paint.measureText(text)
        if (measured > maxWidth && measured > 0f) {
            paint.textScaleX = (maxWidth / measured).coerceAtLeast(0.65f)
        }
    }
}
