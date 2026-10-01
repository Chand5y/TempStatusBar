package com.personal.tempstatusbar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

class SubtlePulseView(context: Context) : View(context) {

    private var pulseFraction = 0f
    private var isCharging = false

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var animator: ValueAnimator? = null

    fun setMode(charging: Boolean) {
        if (isCharging != charging || animator == null) {
            isCharging = charging
            setupAnimator()
        }
    }

    private fun setupAnimator() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (isCharging) 1200L else 2400L // Subtle fast breathing on charge, slow on idle
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulseFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun startAnimation() {
        if (animator?.isStarted != true) setupAnimator()
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (width.coerceAtMost(height) / 2f) - 12f

        val activeColor = if (isCharging) Color.parseColor("#00E676") else Color.parseColor("#FF9800")
        corePaint.color = activeColor

        // Subtle expanding breathing aura
        ringPaint.color = activeColor
        ringPaint.alpha = ((1f - pulseFraction) * 140).toInt()
        val pulseRadius = baseRadius + (pulseFraction * 10f)

        canvas.drawCircle(cx, cy, baseRadius, corePaint)
        canvas.drawCircle(cx, cy, pulseRadius, ringPaint)
    }
}
