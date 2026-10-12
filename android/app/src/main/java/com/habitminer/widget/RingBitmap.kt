package com.habitminer.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient

/**
 * The Today rings drawn into a bitmap, for the home-screen widget (widgets can't run Compose
 * drawing code). Same colours and shapes as the rings in the app.
 */
object RingBitmap {
    /** (darker, brighter) ends of each ring's gradient. */
    val SCREEN = 0xFFFF1F5A.toInt() to 0xFFFF6A93.toInt()
    val PICKUPS = 0xFF8BE000.toInt() to 0xFFD2FF45.toInt()
    val FREE = 0xFF00B9F2.toInt() to 0xFF6EF0FF.toInt()

    fun render(
        sizePx: Int,
        rings: List<Pair<Float, Pair<Int, Int>>>,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val stroke = sizePx * 0.12f
        val gap = sizePx * 0.02f
        rings.forEachIndexed { i, (progress, colors) ->
            val (start, end) = colors
            val r = sizePx / 2f - stroke / 2 - i * (stroke + gap)
            if (r <= stroke / 2) return@forEachIndexed
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)
            val track =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                    color = (start and 0x00FFFFFF) or (0x40 shl 24)
                }
            canvas.drawCircle(cx, cy, r, track)
            val first = progress.coerceIn(0f, 1f)
            if (first <= 0.001f) return@forEachIndexed
            val shader =
                SweepGradient(cx, cy, intArrayOf(start, end, end), floatArrayOf(0f, first, 1f)).apply {
                    setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
                }
            val arc =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                    strokeCap = Paint.Cap.ROUND
                    this.shader = shader
                }
            canvas.drawArc(rect, -90f, 360f * first, false, arc)
            val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = start }
            canvas.drawCircle(cx, cy - r, stroke / 2, cap)
            if (progress > 1f) {
                val extra = ((progress - 1f) % 1f).let { if (it == 0f) 1f else it }.coerceAtLeast(0.02f)
                val lap =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = stroke
                        strokeCap = Paint.Cap.ROUND
                        color = end
                    }
                canvas.drawArc(rect, -90f, 360f * extra, false, lap)
            }
        }
        return bmp
    }
}
