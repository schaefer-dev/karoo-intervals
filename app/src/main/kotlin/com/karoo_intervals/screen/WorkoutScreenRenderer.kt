package com.karoo_intervals.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.karoo_intervals.engine.TargetStatus
import com.karoo_intervals.engine.WorkoutState
import com.karoo_intervals.workout.Profile
import com.karoo_intervals.workout.Workout
import com.karoo_intervals.workout.formatClock
import com.karoo_intervals.workout.summary

/**
 * Draws the full-page workout screen into a bitmap.
 *
 * Vertical layout is expressed as fractions of the height in [Layout] — the tap-zone XML
 * (`res/layout/field_workout.xml`) uses the same weights, so keep them in sync.
 */
object WorkoutScreenRenderer {
    object Layout {
        /** Small top margin. Karoo's banner (GPS / notifications) can temporarily cover the header. */
        const val SAFE_TOP = 0.01f
        const val HEADER_END = 0.09f
        const val POWER_END = 0.40f
        const val INTERVAL_END = 0.49f
        const val PROGRESS_END = 0.52f
        const val TILES_END = 0.66f
        const val NEXT_START = 0.67f
        const val NEXT_END = 0.80f
        const val SIDE_ZONE = 0.20f // gear / pause tap width (fraction of width)
        const val SKIP_ZONE = 0.25f // skip button width (fraction of width)
    }

    private class Theme(dark: Boolean) {
        val bg = if (dark) Color.BLACK else Color.WHITE
        val fg = if (dark) Color.WHITE else Color.BLACK
        val muted = if (dark) 0xFFB8B8B8.toInt() else 0xFF505050.toInt()
        val tile = if (dark) 0xFF262626.toInt() else 0xFFF0EEE9.toInt()
        val track = if (dark) 0xFF3A3A3A.toInt() else 0xFFDDDBD5.toInt()
        val outline = if (dark) 0xFF8A8A8A.toInt() else 0xFF9A9A9A.toInt()
        val skip = 0xFF8AB8EC.toInt()
        val skipFg = 0xFF0B3A6E.toInt()
        val heart = 0xFFE5484D.toInt()
        val done = if (dark) 0x99000000.toInt() else 0x99FFFFFF.toInt()
        val below = if (dark) 0xFF5AB0FF.toInt() else 0xFF1E6FD9.toInt()
        val inRange = if (dark) 0xFF3DD68C.toInt() else 0xFF14995A.toInt()
        val above = if (dark) 0xFFFF5A5F.toInt() else 0xFFD62F35.toInt()

        fun forStatus(s: TargetStatus): Int = when (s) {
            TargetStatus.BELOW -> below
            TargetStatus.IN_RANGE -> inRange
            TargetStatus.ABOVE -> above
            TargetStatus.NONE -> fg
        }
    }

    data class Input(
        val state: WorkoutState?,
        /** Shown when no workout is selected. */
        val livePower: Int?,
        val liveHr: Int?,
        val paused: Boolean,
        val simulated: Boolean,
    )

    fun render(input: Input, w: Int, h: Int, dark: Boolean, reuse: Bitmap? = null): Bitmap {
        val bmp = reuse?.takeIf { it.width == w && it.height == h && it.isMutable }
            ?: Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val t = Theme(dark)
        c.drawColor(t.bg)
        val W = w.toFloat()
        val H = h.toFloat()
        val pad = W * 0.035f
        val st = input.state

        // ---- header: gear · ♥ HR · pause
        val hy = H * (Layout.SAFE_TOP + Layout.HEADER_END) / 2
        val iconR = H * 0.025f
        drawGear(c, pad + iconR, hy, iconR, t.muted)
        val hr = st?.heartRate ?: input.liveHr
        val hrPaint = text(H * 0.048f, t.fg, bold = true)
        val hrText = hr?.toString() ?: "--"
        val hrW = hrPaint.measureText(hrText) + iconR * 2.6f
        drawHeart(c, W / 2 - hrW / 2 + iconR, hy, iconR, t.heart)
        c.drawText(hrText, W / 2 - hrW / 2 + iconR * 2.6f, hy + hrPaint.textSize * 0.35f, hrPaint)
        if (input.paused) drawPlay(c, W - pad - iconR, hy, iconR, t.muted) else drawPause(c, W - pad - iconR, hy, iconR, t.muted)
        if (input.simulated) {
            c.drawText("SIM", W / 2 + hrW / 2 + pad, hy + H * 0.012f, text(H * 0.025f, t.heart, bold = true))
        }

        // ---- big power
        val power = st?.power3s ?: input.livePower
        val pStatus = st?.powerStatus ?: TargetStatus.NONE
        val bigPaint = text(H * 0.25f, t.forStatus(pStatus), heavy = true, align = Paint.Align.CENTER)
        val powerMid = (Layout.HEADER_END + Layout.POWER_END) / 2 * H
        val powerText = power?.toString() ?: "--"
        c.drawText(powerText, W / 2, powerMid + bigPaint.textSize * 0.36f, bigPaint)
        val arrowX = W / 2 + bigPaint.measureText(powerText) / 2 + H * 0.05f
        drawStatusArrow(c, pStatus, arrowX, powerMid, H * 0.04f, t.forStatus(pStatus))

        if (st == null) {
            val p = text(H * 0.035f, t.muted, align = Paint.Align.CENTER)
            c.drawText("No workout selected", W / 2, H * 0.52f, p)
            c.drawText("Tap here to choose one", W / 2, H * 0.57f, p)
            return bmp
        }

        // ---- interval row
        val rowBase = H * (Layout.INTERVAL_END - 0.015f)
        val label = when {
            st.finished -> "Workout complete"
            st.paused -> "Workout paused"
            !st.started -> "Ready · starts with ride"
            else -> "Interval ${st.stepIndex + 1} / ${st.stepCount}"
        }
        c.drawText(label, pad, rowBase, text(H * 0.034f, t.muted, bold = true))
        val clock = formatClock(if (st.started) st.stepRemainingSec else st.step.durationSec)
        c.drawText(clock, W - pad, rowBase, text(H * 0.092f, t.fg, heavy = true, align = Paint.Align.RIGHT))

        // ---- progress bar
        val pTop = H * (Layout.INTERVAL_END + 0.008f)
        val pBot = pTop + H * 0.012f
        val track = RectF(pad, pTop, W - pad, pBot)
        c.drawRoundRect(track, H, H, fill(t.track))
        if (st.stepProgress > 0f) {
            val fillR = RectF(pad, pTop, pad + (W - 2 * pad) * st.stepProgress, pBot)
            c.drawRoundRect(fillR, H, H, fill(com.karoo_intervals.workout.ZoneColors.forZone(st.step.zone)))
        }

        // ---- tiles: Lap avg | Target
        val tTop = H * Layout.PROGRESS_END + pad * 0.3f
        val tBot = H * Layout.TILES_END - pad * 0.3f
        val gap = pad * 0.6f
        val tileW = (W - 2 * pad - gap) / 2
        tile(c, t, RectF(pad, tTop, pad + tileW, tBot), "Lap avg", st.lapAvgW?.toString() ?: "--", H, st.lapStatus)
        val targetText = when {
            st.finished -> "--"
            st.targetW != null -> st.targetW.toString()
            st.step.hrLow != null -> "${st.step.hrLow}-${st.step.hrHigh}"
            else -> "free"
        }
        tile(c, t, RectF(pad + tileW + gap, tTop, W - pad, tBot), "Target", targetText, H)

        // ---- next card
        val nTop = H * Layout.NEXT_START
        val nBot = H * Layout.NEXT_END
        val card = RectF(pad, nTop, W - pad, nBot)
        val r = H * 0.015f
        c.drawRoundRect(card, r, r, stroke(t.outline, H * 0.003f))
        val next = st.nextStep
        val nextTitle = when {
            st.finished -> "Done"
            next == null -> "Last interval"
            else -> "Next · Interval ${st.stepIndex + 2} / ${st.stepCount}"
        }
        c.drawText(nextTitle, pad * 2, nTop + (nBot - nTop) * 0.36f, text(H * 0.03f, t.muted))
        val nextLine = next?.let { it.summary() } ?: if (st.finished) st.workout.name else "Finish strong"
        val nextPaint = text(H * 0.05f, t.fg, heavy = true)
        val maxTextW = W * (1 - Layout.SKIP_ZONE) - pad * 2
        // Shrink to fit (down to 75 %) before falling back to an ellipsis.
        while (nextPaint.measureText(nextLine) > maxTextW && nextPaint.textSize > H * 0.0375f) nextPaint.textSize *= 0.95f
        c.drawText(ellipsize(nextLine, nextPaint, maxTextW), pad * 2, nTop + (nBot - nTop) * 0.78f, nextPaint)
        if (!st.finished && st.started) {
            val bSize = (nBot - nTop) * 0.72f
            val bx = W - pad - (nBot - nTop - bSize) / 2 - bSize
            val by = nTop + (nBot - nTop - bSize) / 2
            val btn = RectF(bx, by, bx + bSize, by + bSize)
            c.drawRoundRect(btn, r, r, fill(t.skip))
            drawSkip(c, btn.centerX(), btn.centerY(), bSize * 0.22f, t.skipFg)
        }

        // ---- profile graph
        drawProfile(c, st.workout, RectF(pad, H * Layout.NEXT_END + pad, W - pad, H - pad * 1.8f), st, t, H)
        return bmp
    }

    private fun drawProfile(c: Canvas, workout: Workout, area: RectF, st: WorkoutState, t: Theme, H: Float) {
        val bars = Profile.bars(workout, area.width())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val gapPx = if (bars.size < 60) 1.5f else 0f
        for (b in bars) {
            p.color = b.color
            val path = Path().apply {
                moveTo(area.left + b.left, area.bottom)
                lineTo(area.left + b.left, area.bottom - area.height() * b.startFrac)
                lineTo(area.left + b.right - gapPx, area.bottom - area.height() * b.endFrac)
                lineTo(area.left + b.right - gapPx, area.bottom)
                close()
            }
            c.drawPath(path, p)
        }
        // Fade the completed part and mark the current position.
        val x = area.left + area.width() * st.totalProgress
        if (st.started && x > area.left) c.drawRect(area.left, area.top, x, area.bottom, fill(t.done))
        c.drawRect(x - H * 0.002f, area.top - H * 0.01f, x + H * 0.002f, area.bottom, fill(t.fg))
        val small = text(H * 0.022f, t.muted)
        c.drawText(Profile.scaleMaxW(workout).toString(), area.left, area.top - H * 0.005f, small)
        val total = text(H * 0.024f, t.fg, align = Paint.Align.CENTER)
        c.drawText(formatClock(workout.totalSec), area.centerX(), area.bottom + H * 0.03f, total)
    }

    private fun tile(
        c: Canvas, t: Theme, r: RectF, label: String, value: String, H: Float,
        status: TargetStatus = TargetStatus.NONE,
    ) {
        val rad = H * 0.012f
        c.drawRoundRect(r, rad, rad, fill(t.tile))
        val x = r.left + r.width() * 0.08f
        c.drawText(label, x, r.top + r.height() * 0.32f, text(H * 0.03f, t.muted))
        val color = t.forStatus(status)
        val vp = text(H * 0.078f, color, heavy = true)
        val baseline = r.top + r.height() * 0.86f
        c.drawText(value, x, baseline, vp)
        drawStatusArrow(c, status, x + vp.measureText(value) + H * 0.03f, baseline - vp.textSize * 0.36f, H * 0.02f, color)
    }

    /** ▲ = more power needed (below target), ▼ = ease off (above target). Nothing when in range. */
    private fun drawStatusArrow(c: Canvas, status: TargetStatus, cx: Float, cy: Float, r: Float, color: Int) {
        val up = when (status) {
            TargetStatus.BELOW -> true
            TargetStatus.ABOVE -> false
            else -> return
        }
        val path = Path().apply {
            if (up) {
                moveTo(cx, cy - r); lineTo(cx + r, cy + r * 0.8f); lineTo(cx - r, cy + r * 0.8f)
            } else {
                moveTo(cx, cy + r); lineTo(cx + r, cy - r * 0.8f); lineTo(cx - r, cy - r * 0.8f)
            }
            close()
        }
        c.drawPath(path, fill(color))
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var e = s
        while (e.isNotEmpty() && p.measureText("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }

    // ---- icons (drawn, so no font glyph dependency on the Karoo)
    private fun drawGear(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = stroke(color, r * 0.28f)
        c.drawCircle(cx, cy, r * 0.62f, p)
        c.drawCircle(cx, cy, r * 0.22f, p)
        p.strokeWidth = r * 0.3f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val cos = Math.cos(a).toFloat()
            val sin = Math.sin(a).toFloat()
            c.drawLine(cx + cos * r * 0.72f, cy + sin * r * 0.72f, cx + cos * r, cy + sin * r, p)
        }
    }

    private fun drawHeart(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val path = Path().apply {
            moveTo(cx, cy + r * 0.9f)
            cubicTo(cx - r * 1.6f, cy - r * 0.1f, cx - r * 0.7f, cy - r * 1.3f, cx, cy - r * 0.45f)
            cubicTo(cx + r * 0.7f, cy - r * 1.3f, cx + r * 1.6f, cy - r * 0.1f, cx, cy + r * 0.9f)
            close()
        }
        c.drawPath(path, fill(color))
    }

    private fun drawPause(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = stroke(color, r * 0.18f)
        c.drawRoundRect(RectF(cx - r * 0.7f, cy - r, cx - r * 0.15f, cy + r), r * 0.15f, r * 0.15f, p)
        c.drawRoundRect(RectF(cx + r * 0.15f, cy - r, cx + r * 0.7f, cy + r), r * 0.15f, r * 0.15f, p)
    }

    private fun drawPlay(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val path = Path().apply {
            moveTo(cx - r * 0.6f, cy - r); lineTo(cx + r * 0.9f, cy); lineTo(cx - r * 0.6f, cy + r); close()
        }
        c.drawPath(path, fill(color))
    }

    private fun drawSkip(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = stroke(color, r * 0.22f)
        val path = Path().apply {
            moveTo(cx - r, cy - r); lineTo(cx + r * 0.5f, cy); lineTo(cx - r, cy + r); close()
        }
        p.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, p)
        c.drawLine(cx + r, cy - r, cx + r, cy + r, p)
    }

    // ---- paints
    private val boldFace: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    /** Extra-bold for the glanceable numbers (weight API needs Android 9; Karoo 2 falls back to bold). */
    private val heavyFace: Typeface =
        if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 800, false) else boldFace

    private fun text(
        size: Float, color: Int, bold: Boolean = false, heavy: Boolean = false,
        align: Paint.Align = Paint.Align.LEFT,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = align
        typeface = when {
            heavy -> heavyFace
            bold -> boldFace
            else -> Typeface.SANS_SERIF
        }
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width; strokeCap = Paint.Cap.ROUND
    }
}
