package com.personal.tempstatusbar

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

class TemperatureChartView(context: Context) : View(context) {
    private var records: List<TempRecord> = emptyList()
    private var selectedIndex = -1
    var onRecordSelected: ((TempRecord) -> Unit)? = null
    var isDarkMode = true

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF5722"); strokeWidth = 6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val screenOffPaint = Paint().apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E5FF"); strokeWidth = 3f; pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E5FF"); style = Paint.Style.FILL }
    private val dotHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3300E5FF"); style = Paint.Style.FILL }

    fun setData(newRecords: List<TempRecord>) {
        records = newRecords
        val wasUnset = selectedIndex == -1
        selectedIndex = if (records.isNotEmpty()) records.size - 1 else -1
        if (wasUnset && selectedIndex != -1) onRecordSelected?.invoke(records[selectedIndex])
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        gridPaint.color = if (isDarkMode) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        textPaint.color = if (isDarkMode) Color.parseColor("#8E8E93") else Color.parseColor("#98989D")
        screenOffPaint.color = if (isDarkMode) Color.parseColor("#0A0A0C") else Color.parseColor("#E0E0E0")

        if (records.size < 2) {
            canvas.drawText("Logging thermal history...", width / 2f - 100, height / 2f, textPaint)
            return
        }

        val padX = 70f; val padY = 40f
        val w = width - padX * 2; val h = height - padY * 2
        val minTemp = (records.minOf { it.temp } - 2).coerceAtLeast(15)
        val maxTemp = (records.maxOf { it.temp } + 2).coerceAtLeast(minTemp + 4)
        val stepX = w / (records.size - 1).toFloat()

        for (i in 0 until records.size - 1) {
            if (!records[i].screenOn) canvas.drawRect(padX + (i * stepX), padY, padX + ((i + 1) * stepX), padY + h, screenOffPaint)
        }

        for (i in 0..3) {
            val y = padY + (h / 3f) * i
            canvas.drawLine(padX, y, width - padX, y, gridPaint)
            canvas.drawText("${maxTemp - ((maxTemp - minTemp) / 3 * i)}°", 10f, y + 8, textPaint)
        }

        val path = Path(); val fillPath = Path()
        records.forEachIndexed { i, r ->
            val x = padX + (i * stepX)
            val y = padY + h - ((r.temp - minTemp).toFloat() / (maxTemp - minTemp)) * h
            if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, padY + h); fillPath.lineTo(x, y) } else { path.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo(padX + w, padY + h); fillPath.close()

        fillPaint.shader = LinearGradient(0f, padY, 0f, padY + h, if (isDarkMode) Color.parseColor("#44FF5722") else Color.parseColor("#22FF5722"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint); canvas.drawPath(path, linePaint)

        if (selectedIndex in records.indices) {
            val r = records[selectedIndex]
            val x = padX + (selectedIndex * stepX)
            val y = padY + h - ((r.temp - minTemp).toFloat() / (maxTemp - minTemp)) * h
            canvas.drawLine(x, padY, x, padY + h, guidePaint)
            canvas.drawCircle(x, y, 22f, dotHalo)
            canvas.drawCircle(x, y, 10f, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (records.isEmpty()) return false
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val padX = 70f
            val stepX = (width - padX * 2) / (records.size - 1).coerceAtLeast(1).toFloat()
            val idx = ((event.x - padX) / stepX).toInt().coerceIn(0, records.size - 1)
            if (idx != selectedIndex) {
                selectedIndex = idx
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onRecordSelected?.invoke(records[selectedIndex])
                invalidate()
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
