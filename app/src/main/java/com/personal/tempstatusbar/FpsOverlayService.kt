package com.personal.tempstatusbar

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

class FpsOverlayService : Service() {
    companion object { var isRunning = false }
    
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TextView
    private lateinit var dbHelper: DatabaseHelper
    private var isCounting = false
    private var sessionStartTime = 0L
    private var fpsReadings = mutableListOf<Int>()
    private var tempReadings = mutableListOf<Int>()
    private var foregroundApp = "Unknown App"
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        dbHelper = DatabaseHelper(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = TextView(this).apply {
            text = "TAP TO START"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(35, 15, 35, 15)
            background = GradientDrawable().apply {
                cornerRadius = 50f
                setColor(Color.parseColor("#E61C1C1E"))
                setStroke(3, Color.parseColor("#55FFFFFF"))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100; params.y = 200

        setupTouchListener(params)
        windowManager.addView(overlayView, params)
    }

    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isMoved = false
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            if (!isMoved) {
                overlayView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                endAndSaveSession()
                stopSelf()
            }
        }

        overlayView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isMoved = false
                    longPressHandler.postDelayed(longPressRunnable, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx > 10 || dy > 10) {
                        isMoved = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!isMoved) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        toggleFpsCounter()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleFpsCounter() {
        isCounting = !isCounting
        if (isCounting) {
            fpsReadings.clear()
            tempReadings.clear()
            sessionStartTime = System.currentTimeMillis()
            fetchForegroundApp()
            startFpsPoller()
            overlayView.background = GradientDrawable().apply {
                cornerRadius = 50f; setColor(Color.parseColor("#E600E676")); setStroke(3, Color.parseColor("#88FFFFFF"))
            }
            overlayView.setTextColor(Color.BLACK)
        } else {
            overlayView.text = "PAUSED"
            overlayView.background = GradientDrawable().apply {
                cornerRadius = 50f; setColor(Color.parseColor("#E61C1C1E")); setStroke(3, Color.parseColor("#55FFFFFF"))
            }
            overlayView.setTextColor(Color.WHITE)
        }
    }

    private fun fetchForegroundApp() {
        thread {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys activity activities | grep mResumedActivity"))
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                val line = reader.readLine()
                if (line != null && line.contains(" u0 ")) {
                    val pkg = line.substringAfter(" u0 ").substringBefore("/")
                    foregroundApp = pkg.substringAfterLast(".")
                }
                p.waitFor()
            } catch (e: Exception) {}
        }
    }

    private fun startFpsPoller() {
        val prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)
        
        thread {
            while (isCounting) {
                val showTemp = prefs.getBoolean("showTempInFpsOverlay", false)
                try {
                    // Strict Regex parse to fix the CALC... string extraction crash
                    val fpsProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /sys/class/drm/sde-crtc-0/measured_fps"))
                    val rawFpsString = BufferedReader(InputStreamReader(fpsProc.inputStream)).readLine()
                    fpsProc.waitFor()
                    
                    val fpsRaw = rawFpsString?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                    
                    val tempProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys battery | grep temperature"))
                    val rawTempString = BufferedReader(InputStreamReader(tempProc.inputStream)).readLine()
                    tempProc.waitFor()
                    
                    val tempRaw = rawTempString?.replace(Regex("[^0-9]"), "")?.toIntOrNull()?.div(10)

                    if (fpsRaw != null && fpsRaw > 0) { fpsReadings.add(fpsRaw) }
                    if (tempRaw != null && tempRaw > 0) { tempReadings.add(tempRaw) }

                    handler.post { 
                        if (isCounting) {
                            val f = fpsRaw ?: "--"
                            val t = tempRaw ?: "--"
                            overlayView.text = if (showTemp) "$f FPS  |  $t°C" else "$f FPS"
                        }
                    }
                } catch (e: Exception) {
                    handler.post { if (isCounting) overlayView.text = "ERROR" }
                }
                Thread.sleep(1000)
            }
        }
    }

    private fun endAndSaveSession() {
        isCounting = false
        if (fpsReadings.isNotEmpty()) {
            val duration = ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt()
            val min = fpsReadings.minOrNull() ?: 0
            val max = fpsReadings.maxOrNull() ?: 0
            val avg = fpsReadings.average().toInt()
            val avgT = if (tempReadings.isNotEmpty()) tempReadings.average().toInt() else 0
            dbHelper.saveFpsSession(foregroundApp, duration, min, max, avg, avgT)
        }
        windowManager.removeView(overlayView)
    }

    override fun onDestroy() {
        super.onDestroy()
        isCounting = false
        isRunning = false
    }
}
