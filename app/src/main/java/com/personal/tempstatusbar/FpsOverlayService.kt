package com.personal.tempstatusbar

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    companion object {
        var isRunning = false
        private var activeOverlay: View? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TextView
    private lateinit var dbHelper: DatabaseHelper
    private var isServiceActive = true
    private var isRecording = false
    private var sessionStartTime = 0L
    private val fpsReadings = mutableListOf<Int>()
    private val tempReadings = mutableListOf<Int>()
    private var foregroundApp = "Active App"
    private val handler = Handler(Looper.getMainLooper())

    private var nonRootFrameCount = 0
    private var nonRootLastTime = 0L
    private var nonRootCurrentFps = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            nonRootFrameCount++
            val now = System.currentTimeMillis()
            if (now - nonRootLastTime >= 1000) {
                nonRootCurrentFps = nonRootFrameCount
                nonRootFrameCount = 0
                nonRootLastTime = now
            }
            if (isServiceActive) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        dbHelper = DatabaseHelper(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)
        if (!prefs.contains("showTempInFpsOverlay")) {
            prefs.edit().putBoolean("showTempInFpsOverlay", true).apply()
        }

        activeOverlay?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }

        overlayView = TextView(this).apply {
            text = "TAP TO RECORD"
            setTextColor(Color.WHITE)
            textSize = 10.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(32, 11, 32, 11)
            setShadowLayer(3f, 0f, 1f, Color.parseColor("#80000000"))
        }
        updatePillStyle(false)

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
        activeOverlay = overlayView

        if (!HardwareThermalControl.isRootAvailable()) {
            nonRootLastTime = System.currentTimeMillis()
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        startHardwarePoller()
    }

    private fun updatePillStyle(recording: Boolean) {
        overlayView.background = GradientDrawable().apply {
            cornerRadius = 100f
            setColor(Color.parseColor("#88121212"))
            if (recording) {
                setStroke(2, Color.parseColor("#B3E53935"))
            } else {
                setStroke(2, Color.parseColor("#B343A047"))
            }
        }
    }

    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        var isMoved = false
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            if (!isMoved) {
                overlayView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (isRecording) endAndSaveSession()
                activeOverlay?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
                activeOverlay = null
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
                        toggleRecordingState()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // PURE PASSIVE RECORDER: Never turns on Turbo Mode or touches CPU/GPU nodes!
    private fun toggleRecordingState() {
        if (isRecording) {
            endAndSaveSession()
        } else {
            isRecording = true
            fpsReadings.clear()
            tempReadings.clear()
            sessionStartTime = System.currentTimeMillis()
            updatePillStyle(true)

            if (HardwareThermalControl.isRootAvailable()) {
                thread {
                    val foundPkg = HardwareThermalControl.getForegroundPackageRoot()
                    foregroundApp = if (foundPkg.isNotEmpty() && foundPkg != "TRANSIENT_SYSTEM_SHADE" && !foundPkg.contains("miui.home")) {
                        AppLabelHelper.getAppName(applicationContext, foundPkg)
                    } else {
                        "Battlegrounds India"
                    }
                    val modeTag = if (HardwareThermalControl.isPeakPerformanceActive) "Turbo ACTIVE" else "Stock Hardware / Turbo OFF"
                    AppLogger.log("🔴 [FPS RECORDING STARTED] Target: $foregroundApp ($modeTag)")
                }
            } else {
                foregroundApp = "Android System (Non-Root)"
            }
        }
    }

    private fun startHardwarePoller() {
        val prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)

        thread {
            while (isServiceActive) {
                val showTemp = prefs.getBoolean("showTempInFpsOverlay", true)
                val hasRoot = HardwareThermalControl.isRootAvailable()

                var fpsRaw: Int? = null
                var tempRaw: Int? = null

                if (hasRoot) {
                    try {
                        val fpsProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /sys/class/drm/sde-crtc-0/measured_fps"))
                        val rawFpsString = BufferedReader(InputStreamReader(fpsProc.inputStream)).readLine()
                        fpsProc.waitFor()
                        fpsRaw = rawFpsString?.split(Regex("[^0-9]+"))?.mapNotNull { it.toIntOrNull() }?.firstOrNull { it in 1..240 }
                    } catch (e: Exception) {}
                } else {
                    fpsRaw = nonRootCurrentFps
                }

                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                tempRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10)

                if (isRecording) {
                    if (fpsRaw != null && fpsRaw > 0) fpsReadings.add(fpsRaw)
                    if (tempRaw != null && tempRaw > 0) tempReadings.add(tempRaw)
                }

                HardwareThermalControl.liveFps = fpsRaw ?: -1

                handler.post {
                    if (isServiceActive) {
                        val f = fpsRaw ?: "--"
                        val t = if (tempRaw != null && tempRaw > 0) tempRaw else "--"
                        overlayView.text = if (showTemp) "$f FPS | $t°C" else "$f FPS"
                    }
                }
                Thread.sleep(1000)
            }
        }
    }

    private fun endAndSaveSession() {
        isRecording = false
        updatePillStyle(false)
        if (fpsReadings.isNotEmpty()) {
            val duration = ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt()
            val min = fpsReadings.minOrNull() ?: 0
            val max = fpsReadings.maxOrNull() ?: 0
            val avg = fpsReadings.average().toInt()
            val avgT = if (tempReadings.isNotEmpty()) tempReadings.average().toInt() else 0

            val step = (fpsReadings.size / 100).coerceAtLeast(1)
            val downsampledFps = fpsReadings.filterIndexed { index, _ -> index % step == 0 }.take(100).joinToString(",")
            val downsampledTemp = tempReadings.filterIndexed { index, _ -> index % step == 0 }.take(100).joinToString(",")

            AppLogger.log("⏹️ [FPS RECORDING SAVED] $foregroundApp (${duration}s) | Avg: $avg FPS | Peak: $max FPS | Min: $min FPS | AvgTemp: ${avgT}°C")
            dbHelper.saveFpsSession(foregroundApp, duration, min, max, avg, avgT, downsampledFps, downsampledTemp)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceActive = false
        isRunning = false
        activeOverlay?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        activeOverlay = null
    }
}
