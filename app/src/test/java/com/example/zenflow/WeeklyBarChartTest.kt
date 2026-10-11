package com.example.zenflow

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklyBarChartTest {

    private fun drawChart(minutes: FloatArray, labels: List<String>): Bitmap {
        val activity = Robolectric.setupActivity(AppCompatActivity::class.java)
        val chart = WeeklyBarChart(activity)
        chart.setData(minutes, labels)
        val width = 700; val height = 220
        chart.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        chart.layout(0, 0, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(bmp))
        return bmp
    }

    @Test
    fun `measures to the requested size`() {
        val activity = Robolectric.setupActivity(AppCompatActivity::class.java)
        val chart = WeeklyBarChart(activity)
        chart.measure(
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY)
        )
        chart.layout(0, 0, 700, 220)
        assertEquals(700, chart.width)
        assertEquals(220, chart.height)
    }

    @Test
    fun `renders empty full and extreme weeks without crashing`() {
        drawChart(FloatArray(7), List(7) { "" })
        drawChart(floatArrayOf(5f, 0f, 12f, 30f, 45f, 60f, 20f),
            listOf("S", "M", "T", "W", "T", "F", "S"))
        drawChart(FloatArray(7) { 10_000f }, List(7) { "X" })
    }
}
