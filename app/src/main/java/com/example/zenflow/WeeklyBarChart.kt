package com.example.zenflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/** Minimal 7-day bar chart — pure Canvas, no library. */
class WeeklyBarChart @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var minutes = FloatArray(7)
    private var labels = List(7) { "" }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        color = tv.data
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.scaledDensity
        color = Color.parseColor("#6C63FF")
    }
    private val rect = RectF()

    fun setData(minutes: FloatArray, labels: List<String>) {
        this.minutes = minutes.copyOf()
        this.labels = labels
        invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = dp(16f)
        val valueH = dp(14f)
        val chartH = (h - labelH - valueH).coerceAtLeast(dp(10f))
        val max = maxOf(30f, minutes.maxOrNull() ?: 30f)
        val colW = w / 7f
        val barW = colW * 0.42f

        for (i in 0..6) {
            val cx = colW * i + colW / 2f
            val v = minutes.getOrElse(i) { 0f }
            val frac = (v / max).coerceIn(0f, 1f)
            val barH = if (v > 0f) maxOf(chartH * frac, dp(4f)) else dp(3f)
            val left = cx - barW / 2f
            val bottom = valueH + chartH
            val top = bottom - barH
            val radius = minOf(barW / 2f, barH / 2f)

            barPaint.color = if (i == 6) Color.parseColor("#6C63FF")
                             else Color.parseColor("#D9D7FA")
            rect.set(left, top, left + barW, bottom)
            canvas.drawRoundRect(rect, radius, radius, barPaint)

            if (v > 0f) canvas.drawText("${v.toInt()}", cx, top - dp(3f), valuePaint)
            labelPaint.color =
                if (i == 6) Color.parseColor("#2B2B43") else Color.parseColor("#8A8AA3")
            canvas.drawText(labels.getOrElse(i) { "" }, cx, h - dp(3f), labelPaint)
        }
    }
}
