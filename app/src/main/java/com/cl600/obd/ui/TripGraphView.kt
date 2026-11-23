package com.cl600.obd.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.cl600.obd.model.TripMetric
import com.cl600.obd.model.TripSample
import com.cl600.obd.model.UnitSystem
import com.cl600.obd.trips.TripStatsCalculator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class TripGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ZoomMode(val label: String) {
        FIT("FIT"),
        X1("1×"),
        X2("2×");

        fun next(): ZoomMode = when (this) {
            FIT -> X1
            X1 -> X2
            X2 -> FIT
        }
    }

    private var samples: List<TripSample> = emptyList()
    private var visibleMetrics: Set<TripMetric> = TripMetric.values().toSet()
    private var liveMode: Boolean = false
    private var unitSystem: UnitSystem = UnitSystem.IMPERIAL
    private var zoomMode: ZoomMode = ZoomMode.FIT

    private var scrollXOffset: Float = 0f
    private var followLiveTail: Boolean = true
    private var scrubSample: TripSample? = null
    private var onScrubListener: ((TripSample?) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private val bgRect = RectF()
    private val plotClipRect = RectF()
    private val pillRect = RectF()
    private val wmiRect = RectF()
    private val scrollTrackRect = RectF()
    private val scrollThumbRect = RectF()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF070709.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1E1E22.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C1C22.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val vGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF15151A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF71717A.toInt()
        textSize = sp(9.5f)
        textAlign = Paint.Align.CENTER
    }
    private val pctTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3F3F46.toInt()
        textSize = sp(8f)
        textAlign = Paint.Align.LEFT
    }
    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B6B74.toInt()
        textSize = sp(13f)
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val wmiFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1022C55E
        style = Paint.Style.FILL
    }
    private val wmiBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5522C55E
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE111114.toInt()
        style = Paint.Style.FILL
    }
    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A42.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = sp(9.5f)
        textAlign = Paint.Align.CENTER
        typeface = FontCache.racingSemiBoldItalic(context)
    }
    private val scrollTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF16161B.toInt()
        style = Paint.Style.FILL
    }
    private val scrollThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF52525B.toInt()
        style = Paint.Style.FILL
    }

    private val workPath = Path()
    private val scroller = OverScroller(context)

    private var plotLeft = 0f
    private var plotRight = 0f
    private var plotTop = 0f
    private var plotBottom = 0f
    private var plotW = 1f
    private var plotH = 1f

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (!scroller.isFinished) {
                    scroller.abortAnimation()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val maxScroll = maxScrollX()
                if (maxScroll <= 1f) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                val next = (scrollXOffset + distanceX).coerceIn(0f, maxScroll)
                scrollXOffset = next
                if (liveMode) {
                    followLiveTail = (maxScroll - next) < dp(14f)
                }
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val maxScroll = maxScrollX()
                if (maxScroll <= 1f) return false
                scroller.fling(
                    scrollXOffset.toInt(),
                    0,
                    (-velocityX).toInt(),
                    0,
                    0,
                    maxScroll.toInt(),
                    0,
                    0
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (samples.isEmpty() || plotW <= 1f) return false
                val touchPlotX = (e.x - plotLeft).coerceIn(0f, plotW)
                val contentW = contentWidthPx()
                val totalSpanMs = effectiveSpanMs().toFloat().coerceAtLeast(1f)
                val contentX = touchPlotX + scrollXOffset
                val targetMs = ((contentX / contentW) * totalSpanMs).toLong()
                val nearest = findNearestSample(targetMs)
                if (nearest != null) {
                    scrubSample = if (scrubSample?.elapsedMs == nearest.elapsedMs) null else nearest
                    onScrubListener?.invoke(scrubSample)
                    invalidate()
                }
                return true
            }
        }
    )

    fun setData(samples: List<TripSample>, liveMode: Boolean, unitSystem: UnitSystem) {
        val wasEmpty = this.samples.isEmpty()
        this.samples = samples
        this.liveMode = liveMode
        this.unitSystem = unitSystem
        if (!liveMode && wasEmpty && samples.isNotEmpty()) {
            val totalMs = samples.last().elapsedMs
            zoomMode = if (totalMs > 40_000L) ZoomMode.X1 else ZoomMode.FIT
            scrollXOffset = 0f
            scrubSample = null
        } else if (liveMode) {
            if (followLiveTail) {
                scrollXOffset = maxScrollX()
            } else {
                scrollXOffset = scrollXOffset.coerceIn(0f, maxScrollX())
            }
        } else {
            scrollXOffset = scrollXOffset.coerceIn(0f, maxScrollX())
        }
        invalidate()
    }

    fun setVisibleMetrics(metrics: Set<TripMetric>) {
        visibleMetrics = metrics
        invalidate()
    }

    fun setZoomMode(mode: ZoomMode) {
        if (zoomMode == mode) return
        val prevContentW = contentWidthPx().coerceAtLeast(1f)
        val centerFraction = ((scrollXOffset + plotW * 0.5f) / prevContentW).coerceIn(0f, 1f)
        zoomMode = mode
        val nextContentW = contentWidthPx()
        val maxScroll = (nextContentW - plotW).coerceAtLeast(0f)
        scrollXOffset = if (liveMode && followLiveTail) {
            maxScroll
        } else {
            (centerFraction * nextContentW - plotW * 0.5f).coerceIn(0f, maxScroll)
        }
        invalidate()
    }

    fun getZoomMode(): ZoomMode = zoomMode

    fun cycleZoomMode(): ZoomMode {
        val next = zoomMode.next()
        setZoomMode(next)
        return next
    }

    fun jumpToLiveTail() {
        followLiveTail = true
        scrollXOffset = maxScrollX()
        invalidate()
    }

    fun setOnScrubListener(listener: ((TripSample?) -> Unit)?) {
        onScrubListener = listener
    }

    fun clearScrub() {
        if (scrubSample != null) {
            scrubSample = null
            onScrubListener?.invoke(null)
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        plotLeft = dp(10f)
        plotRight = (w - dp(10f)).coerceAtLeast(plotLeft + 10f)
        plotTop = dp(12f)
        plotBottom = (h - dp(22f)).coerceAtLeast(plotTop + 10f)
        plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        plotH = (plotBottom - plotTop).coerceAtLeast(1f)
        scrollXOffset = if (liveMode && followLiveTail) maxScrollX() else scrollXOffset.coerceIn(0f, maxScrollX())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled || super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val maxScroll = maxScrollX()
            scrollXOffset = scroller.currX.toFloat().coerceIn(0f, maxScroll)
            if (liveMode) {
                followLiveTail = (maxScroll - scrollXOffset) < dp(14f)
            }
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        bgRect.set(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f))
        canvas.drawRoundRect(bgRect, dp(8f), dp(8f), bgPaint)
        canvas.drawRoundRect(bgRect, dp(8f), dp(8f), borderPaint)

        drawHorizontalGrid(canvas)

        if (samples.isEmpty()) {
            val emptyMsg = if (liveMode) {
                "Recording started — awaiting telemetry frames..."
            } else {
                "No telemetry samples in this trip"
            }
            canvas.drawText(emptyMsg, w * 0.5f, plotTop + plotH * 0.52f, emptyTextPaint)
            return
        }

        val spanMs = effectiveSpanMs()
        val contentW = contentWidthPx()
        val maxScroll = (contentW - plotW).coerceAtLeast(0f)
        val clampedScroll = scrollXOffset.coerceIn(0f, maxScroll)

        drawTimeAxisGrid(canvas, spanMs, contentW, clampedScroll)

        canvas.save()
        plotClipRect.set(plotLeft, plotTop - dp(4f), plotRight, plotBottom + dp(4f))
        canvas.clipRect(plotClipRect)

        drawWmiBands(canvas, spanMs, contentW, clampedScroll)
        drawMetricLines(canvas, spanMs, contentW, clampedScroll)
        drawScrubOverlay(canvas, spanMs, contentW, clampedScroll)

        canvas.restore()

        if (maxScroll > 2f) {
            drawScrollIndicator(canvas, contentW, clampedScroll)
        }
    }

    private fun drawHorizontalGrid(canvas: Canvas) {
        val levels = floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1.0f)
        for (lvl in levels) {
            val y = plotBottom - lvl * plotH
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
        }
        canvas.drawText("100%", plotLeft + dp(3f), plotTop + dp(8f), pctTextPaint)
        canvas.drawText("50%", plotLeft + dp(3f), plotTop + plotH * 0.5f - dp(2f), pctTextPaint)
        canvas.drawText("0%", plotLeft + dp(3f), plotBottom - dp(3f), pctTextPaint)
    }

    private fun drawTimeAxisGrid(
        canvas: Canvas,
        spanMs: Long,
        contentW: Float,
        scroll: Float
    ) {
        val visibleStartMs = ((scroll / contentW) * spanMs).toLong().coerceAtLeast(0L)
        val visibleEndMs = (((scroll + plotW) / contentW) * spanMs).toLong().coerceAtLeast(visibleStartMs)
        val visibleDurationMs = (visibleEndMs - visibleStartMs).coerceAtLeast(1000L)

        val stepMs = chooseTickStepMs(visibleDurationMs, maxOf(2, (plotW / dp(84f)).toInt()))
        val firstTick = ((visibleStartMs / stepMs) * stepMs).coerceAtLeast(0L)

        var tickMs = firstTick
        val labelY = plotBottom + dp(13f)
        while (tickMs <= visibleEndMs + stepMs) {
            val x = plotLeft + ((tickMs.toFloat() / spanMs.toFloat()) * contentW) - scroll
            if (x in (plotLeft - dp(2f))..(plotRight + dp(2f))) {
                canvas.drawLine(x, plotTop, x, plotBottom, vGridPaint)
                if (x in (plotLeft + dp(14f))..(plotRight - dp(14f))) {
                    canvas.drawText(TripStatsCalculator.formatAxisTime(tickMs), x, labelY, axisTextPaint)
                }
            }
            tickMs += stepMs
        }
    }

    private fun drawWmiBands(
        canvas: Canvas,
        spanMs: Long,
        contentW: Float,
        scroll: Float
    ) {
        var inBand = false
        var bandStartX = 0f
        for (s in samples) {
            val x = plotLeft + ((s.elapsedMs.toFloat() / spanMs.toFloat()) * contentW) - scroll
            if (s.wmiActive && !inBand) {
                inBand = true
                bandStartX = x
            } else if (!s.wmiActive && inBand) {
                drawSingleWmiSpan(canvas, bandStartX, x)
                inBand = false
            }
        }
        if (inBand) {
            val endX = plotLeft + ((samples.last().elapsedMs.toFloat() / spanMs.toFloat()) * contentW) - scroll
            drawSingleWmiSpan(canvas, bandStartX, maxOf(endX, bandStartX + dp(3f)))
        }
    }

    private fun drawSingleWmiSpan(canvas: Canvas, xStart: Float, xEnd: Float) {
        val left = maxOf(plotLeft, xStart)
        val right = minOf(plotRight, maxOf(xEnd, xStart + dp(2f)))
        if (right <= left) return
        wmiRect.set(left, plotTop, right, plotBottom)
        canvas.drawRect(wmiRect, wmiFillPaint)
        wmiRect.set(left, plotBottom - dp(3.5f), right, plotBottom)
        canvas.drawRect(wmiRect, wmiBarPaint)
    }

    private fun drawMetricLines(
        canvas: Canvas,
        spanMs: Long,
        contentW: Float,
        scroll: Float
    ) {
        if (samples.isEmpty()) return
        val visibleStartMs = ((scroll / contentW) * spanMs).toLong() - 1000L
        val visibleEndMs = (((scroll + plotW) / contentW) * spanMs).toLong() + 1000L

        val startIdx = (lowerBoundIndex(visibleStartMs) - 1).coerceAtLeast(0)
        val endIdx = (upperBoundIndex(visibleEndMs) + 1).coerceAtMost(samples.lastIndex)
        if (endIdx < startIdx) return

        val visibleCount = endIdx - startIdx + 1
        val maxRenderPoints = maxOf(120, plotW.toInt())
        val stride = if (visibleCount > maxRenderPoints * 2) {
            ceil(visibleCount.toFloat() / maxRenderPoints.toFloat()).toInt().coerceAtLeast(1)
        } else {
            1
        }

        for (metric in TripMetric.values()) {
            if (metric !in visibleMetrics) continue
            linePaint.color = metric.colorInt
            workPath.reset()
            var hasActiveSegment = false
            var lastX = 0f
            var lastY = 0f
            var lastElapsed = -1L

            var idx = startIdx
            while (idx <= endIdx) {
                val (norm, sample) = if (stride == 1) {
                    Pair(metric.normalize(samples[idx]), samples[idx])
                } else {
                    pickBucketRepresentative(metric, idx, minOf(endIdx, idx + stride - 1))
                }
                if (norm == null || sample == null) {
                    hasActiveSegment = false
                } else {
                    val x = plotLeft + ((sample.elapsedMs.toFloat() / spanMs.toFloat()) * contentW) - scroll
                    val y = plotBottom - norm * plotH
                    val gapTooLarge = lastElapsed >= 0L && (sample.elapsedMs - lastElapsed) > 4000L
                    if (!hasActiveSegment || gapTooLarge) {
                        workPath.moveTo(x, y)
                        hasActiveSegment = true
                    } else {
                        workPath.lineTo(x, y)
                    }
                    lastX = x
                    lastY = y
                    lastElapsed = sample.elapsedMs
                }
                idx += stride
            }

            if (lastElapsed >= 0L) {
                canvas.drawPath(workPath, linePaint)
                if (liveMode) {
                    dotHaloPaint.color = (metric.colorInt and 0x00FFFFFF) or 0x44000000
                    dotPaint.color = metric.colorInt
                    canvas.drawCircle(lastX, lastY, dp(4.5f), dotHaloPaint)
                    canvas.drawCircle(lastX, lastY, dp(2.4f), dotPaint)
                }
            }
        }
    }

    private fun pickBucketRepresentative(
        metric: TripMetric,
        fromIdx: Int,
        toIdx: Int
    ): Pair<Float?, TripSample?> {
        var bestSample: TripSample? = null
        var bestNorm: Float? = null
        var maxDeviation = -1f
        for (i in fromIdx..toIdx) {
            val s = samples[i]
            val n = metric.normalize(s) ?: continue
            val dev = abs(n - 0.5f)
            if (dev > maxDeviation) {
                maxDeviation = dev
                bestSample = s
                bestNorm = n
            }
        }
        return Pair(bestNorm, bestSample)
    }

    private fun drawScrubOverlay(
        canvas: Canvas,
        spanMs: Long,
        contentW: Float,
        scroll: Float
    ) {
        val target = scrubSample ?: return
        val x = plotLeft + ((target.elapsedMs.toFloat() / spanMs.toFloat()) * contentW) - scroll
        if (x < plotLeft || x > plotRight) return

        canvas.drawLine(x, plotTop, x, plotBottom, crosshairPaint)

        for (metric in TripMetric.values()) {
            if (metric !in visibleMetrics) continue
            val norm = metric.normalize(target) ?: continue
            val y = plotBottom - norm * plotH
            dotPaint.color = metric.colorInt
            canvas.drawCircle(x, y, dp(3.8f), dotPaint)
            dotPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawCircle(x, y, dp(1.5f), dotPaint)
        }

        val timeLabel = TripStatsCalculator.formatScrubTime(target.elapsedMs)
        val badgeText = if (target.wmiActive) "$timeLabel • WMI" else timeLabel
        val textW = pillTextPaint.measureText(badgeText)
        val pillW = textW + dp(14f)
        val pillH = dp(17f)
        val pillX = x.coerceIn(plotLeft + pillW * 0.5f + dp(2f), plotRight - pillW * 0.5f - dp(2f))
        pillRect.set(pillX - pillW * 0.5f, plotTop + dp(2f), pillX + pillW * 0.5f, plotTop + dp(2f) + pillH)
        canvas.drawRoundRect(pillRect, dp(4f), dp(4f), pillBgPaint)
        canvas.drawRoundRect(pillRect, dp(4f), dp(4f), pillStrokePaint)
        val fm = pillTextPaint.fontMetrics
        val baseline = pillRect.centerY() - (fm.ascent + fm.descent) * 0.5f
        canvas.drawText(badgeText, pillX, baseline, pillTextPaint)
    }

    private fun drawScrollIndicator(canvas: Canvas, contentW: Float, scroll: Float) {
        val trackTop = height - dp(5f)
        val trackBottom = height - dp(2.5f)
        scrollTrackRect.set(plotLeft, trackTop, plotRight, trackBottom)
        canvas.drawRoundRect(scrollTrackRect, dp(1.5f), dp(1.5f), scrollTrackPaint)

        val ratio = (plotW / contentW).coerceIn(0.06f, 1f)
        val thumbW = (plotW * ratio).coerceAtLeast(dp(24f))
        val available = (plotW - thumbW).coerceAtLeast(1f)
        val frac = (scroll / (contentW - plotW).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val thumbLeft = plotLeft + frac * available
        scrollThumbRect.set(thumbLeft, trackTop, thumbLeft + thumbW, trackBottom)
        canvas.drawRoundRect(scrollThumbRect, dp(1.5f), dp(1.5f), scrollThumbPaint)
    }

    private fun effectiveSpanMs(): Long {
        val lastMs = samples.lastOrNull()?.elapsedMs ?: 0L
        return if (liveMode) {
            maxOf(lastMs, 20_000L)
        } else {
            maxOf(lastMs, 1_000L)
        }
    }

    private fun contentWidthPx(): Float {
        if (samples.isEmpty() || zoomMode == ZoomMode.FIT) return plotW
        val pxPerSec = when (zoomMode) {
            ZoomMode.FIT -> return plotW
            ZoomMode.X1 -> dp(22f)
            ZoomMode.X2 -> dp(44f)
        }
        val spanSec = effectiveSpanMs() / 1000f
        return maxOf(plotW, spanSec * pxPerSec)
    }

    private fun maxScrollX(): Float = (contentWidthPx() - plotW).coerceAtLeast(0f)

    private fun lowerBoundIndex(targetMs: Long): Int {
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].elapsedMs < targetMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upperBoundIndex(targetMs: Long): Int {
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].elapsedMs <= targetMs) lo = mid + 1 else hi = mid
        }
        return (lo - 1).coerceAtLeast(0)
    }

    private fun findNearestSample(targetMs: Long): TripSample? {
        if (samples.isEmpty()) return null
        val idx = lowerBoundIndex(targetMs)
        return when {
            idx <= 0 -> samples.first()
            idx >= samples.size -> samples.last()
            else -> {
                val before = samples[idx - 1]
                val after = samples[idx]
                if (abs(targetMs - before.elapsedMs) <= abs(after.elapsedMs - targetMs)) before else after
            }
        }
    }

    private fun chooseTickStepMs(visibleMs: Long, desiredTicks: Int): Long {
        val targetMs = (visibleMs.toFloat() / desiredTicks.coerceAtLeast(2)).toLong()
        val steps = longArrayOf(
            1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L,
            60_000L, 120_000L, 300_000L, 600_000L, 1_800_000L
        )
        for (step in steps) {
            if (step >= targetMs) return step
        }
        return steps.last()
    }
}
