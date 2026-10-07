package com.personal.tempstatusbar

import android.content.Context
import android.graphics.*
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

class TemperatureChartView(context: Context) : View(context) {
    private var records: List<TempRecord> = emptyList()
    var onRecordSelected: ((TempRecord) -> Unit)? = null
    var isDarkMode = true
    private var scrubIndex = -1

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    
    private val scrubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
        color = Color.parseColor("#2196F3") 
        strokeWidth = 4f 
        style = Paint.Style.STROKE 
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }
    private val scrubDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#00E5FF") }

    fun setData(data: List<TempRecord>) {
        records = data; scrubIndex = -1; invalidate()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0 || records.isEmpty()) return

        val padL = 70f; val padR = 40f; val padT = 30f; val padB = 40f
        val plotW = w - padL - padR; val plotH = h - padT - padB

        val minTemp = 25f
        val maxTemp = 45f
        val tempRange = maxTemp - minTemp

        gridPaint.color = if (isDarkMode) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        textPaint.color = if (isDarkMode) Color.parseColor("#8E8E93") else Color.parseColor("#98989D")
        textPaint.textAlign = Paint.Align.RIGHT

        listOf(25, 30, 35, 40, 45).forEach { lvl ->
            val y = padT + plotH - (((lvl - minTemp) / tempRange) * plotH)
            if (y in padT..padT+plotH) {
                canvas.drawLine(padL, y, w - padR, y, gridPaint)
                canvas.drawText("${lvl}°", padL - 12f, y + 8f, textPaint)
            }
        }

        // Dynamic Temperature Gradient: Green < 32, Yellow 32-34, Orange 35-39, Red 40+
        val y40 = padT + plotH - (((40f - minTemp) / tempRange) * plotH)
        val y35 = padT + plotH - (((35f - minTemp) / tempRange) * plotH)
        val y32 = padT + plotH - (((32f - minTemp) / tempRange) * plotH)

        val p40 = (y40 / h).coerceIn(0f, 1f)
        val p35 = (y35 / h).coerceIn(0f, 1f)
        val p32 = (y32 / h).coerceIn(0f, 1f)

        linePaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#F44336"), Color.parseColor("#FF9800"), Color.parseColor("#FFEB3B"), Color.parseColor("#4CAF50")),
            floatArrayOf(p40, p35, p32, 1f),
            Shader.TileMode.CLAMP
        )

        val path = Path()
        val fillPath = Path()
        val stepX = plotW / (records.size - 1).coerceAtLeast(1).toFloat()

        for (i in records.indices) {
            val x = padL + i * stepX
            val y = padT + plotH - (((records[i].temp - minTemp).coerceIn(0f, tempRange) / tempRange) * plotH)
            
            if (i == 0) { 
                path.moveTo(x, y)
                fillPath.moveTo(x, padT + plotH)
                fillPath.lineTo(x, y)
            } else { 
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(padL + (records.size - 1) * stepX, padT + plotH); fillPath.close()
        fillPaint.shader = LinearGradient(0f, padT, 0f, padT + plotH, Color.parseColor("#33FF9800"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        
        val bgPaint = Paint().apply { style = Paint.Style.FILL }
        for (i in 0 until records.size - 1) {
            if (!records[i].screenOn) {
                bgPaint.color = if (isDarkMode) Color.parseColor("#1AFFFFFF") else Color.parseColor("#0D000000")
                canvas.drawRect(padL + i * stepX, padT, padL + (i + 1) * stepX, padT + plotH, bgPaint)
            }
        }

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        if (scrubIndex in records.indices) {
            val x = padL + scrubIndex * stepX
            canvas.drawLine(x, padT, x, padT + plotH, scrubPaint)
            val y = padT + plotH - (((records[scrubIndex].temp - minTemp).coerceIn(0f, tempRange) / tempRange) * plotH)
            canvas.drawCircle(x, y, 10f, scrubDotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (records.isEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val padL = 70f; val plotW = width.toFloat() - padL - 40f
                val stepX = plotW / (records.size - 1).coerceAtLeast(1).toFloat()
                val idx = ((event.x - padL) / stepX).toInt().coerceIn(0, records.size - 1)
                
                if (idx != scrubIndex) {
                    scrubIndex = idx
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onRecordSelected?.invoke(records[idx])
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }
}
