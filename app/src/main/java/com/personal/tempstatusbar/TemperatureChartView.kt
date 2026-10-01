package com.personal.tempstatusbar

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class TemperatureChartView(context: Context) : View(context) {

    private var records: List<TempRecord> = emptyList()
    private var selectedIndex = -1
    var onRecordSelected: ((TempRecord) -> Unit)? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5722") // Thermal Coral
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E8E93")
        textSize = 28f
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Highlighting touch line
        strokeWidth = 3f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    fun setData(newRecords: List<TempRecord>) {
        records = newRecords
        selectedIndex = if (records.isNotEmpty()) records.size - 1 else -1
        if (selectedIndex != -1) onRecordSelected?.invoke(records[selectedIndex])
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#121212")) // Dark background

        if (records.size < 2) {
            val emptyText = "Collecting thermal history... (Waiting for updates)"
            canvas.drawText(emptyText, width / 2f - 240, height / 2f, textPaint)
            return
        }

        val padding = 70f
        val w = width - padding * 2
        val h = height - padding * 2

        val minTemp = (records.minOf { it.temp } - 2).coerceAtLeast(20)
        val maxTemp = (records.maxOf { it.temp } + 2).coerceAtLeast(minTemp + 5)

        // Draw horizontal grid lines
        for (i in 0..4) {
            val y = padding + (h / 4f) * i
            canvas.drawLine(padding, y, width - padding, y, gridPaint)
            val tempLabel = "${maxTemp - ((maxTemp - minTemp) / 4 * i)}°C"
            canvas.drawText(tempLabel, 10f, y + 10, textPaint)
        }

        // Build curve path
        val path = Path()
        val stepX = w / (records.size - 1).toFloat()

        records.forEachIndexed { i, r ->
            val x = padding + (i * stepX)
            val y = padding + h - ((r.temp - minTemp).toFloat() / (maxTemp - minTemp)) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        // Draw touched selection marker
        if (selectedIndex in records.indices) {
            val r = records[selectedIndex]
            val x = padding + (selectedIndex * stepX)
            val y = padding + h - ((r.temp - minTemp).toFloat() / (maxTemp - minTemp)) * h

            canvas.drawLine(x, padding, x, height - padding, highlightPaint)
            canvas.drawCircle(x, y, 10f, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (records.isEmpty()) return false
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val padding = 70f
            val w = width - padding * 2
            val stepX = w / (records.size - 1).coerceAtLeast(1).toFloat()
            val touchX = event.x - padding
            val index = (touchX / stepX).toInt().coerceIn(0, records.size - 1)

            if (index != selectedIndex) {
                selectedIndex = index
                onRecordSelected?.invoke(records[selectedIndex])
                invalidate()
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
