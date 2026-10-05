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
    private var isServiceActive = true
    private var isRecording = false
    private var sessionStartTime = 0L
    private val fpsReadings = mutableListOf<Int>()
    private val tempReadings = mutableListOf<Int>()
    private var foregroundApp = "Active App"
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        dbHelper = DatabaseHelper(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = TextView(this).apply {
            text = "INIT..."
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(40, 20, 40, 20)
            updatePillStyle(false)
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
        
        startHardwarePoller()
    }

    private fun updatePillStyle(recording: Boolean) {
        overlayView.background = GradientDrawable().apply {
            cornerRadius = 100f // Fully rounded modern pill
            if (recording) {
                setColor(Color.parseColor("#E6D32F2F")) // Translucent Red for recording
                setStroke(3, Color.parseColor("#FF5252"))
            } else {
                setColor(Color.parseColor("#E61C1C1E")) // Translucent Dark Gray for passive monitoring
                setStroke(3, Color.parseColor("#55FFFFFF"))
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

    private fun toggleRecordingState() {
        if (isRecording) {
            endAndSaveSession()
        } else {
            isRecording = true
            fpsReadings.clear()
            tempReadings.clear()
            sessionStartTime = System.currentTimeMillis()
            updatePillStyle(true)
            thread {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys window | grep -E 'mCurrentFocus|topResumedActivity'"))
                    val reader = BufferedReader(InputStreamReader(p.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val match = Regex("""([a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+)""").find(line!!)
                        val foundPkg = match?.value
                        if (foundPkg != null && !foundPkg.contains("tempstatusbar") && !foundPkg.contains("miui.home")) {
                            foregroundApp = AppLabelHelper.getAppName(applicationContext, foundPkg)
                            break
                        }
                    }
                    p.waitFor()
                } catch (e: Exception) { foregroundApp = "Active App" }
            }
        }
    }

    private fun startHardwarePoller() {
        val prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)

        thread {
            while (isServiceActive) {
                val showTemp = prefs.getBoolean("showTempInFpsOverlay", false)
                try {
                    val fpsProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /sys/class/drm/sde-crtc-0/measured_fps"))
                    val rawFpsString = BufferedReader(InputStreamReader(fpsProc.inputStream)).readLine()
                    fpsProc.waitFor()

                    val fpsRaw = rawFpsString?.split(Regex("[^0-9]+"))?.mapNotNull { it.toIntOrNull() }?.firstOrNull { it in 1..240 }

                    val tempProc = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys battery | grep temperature"))
                    val rawTempString = BufferedReader(InputStreamReader(tempProc.inputStream)).readLine()
                    tempProc.waitFor()

                    val tempRaw = rawTempString?.replace(Regex("[^0-9]"), "")?.toIntOrNull()?.div(10)

                    if (isRecording) {
                        if (fpsRaw != null && fpsRaw > 0) fpsReadings.add(fpsRaw)
                        if (tempRaw != null && tempRaw > 0) tempReadings.add(tempRaw)
                    }

                    handler.post {
                        if (isServiceActive) {
                            val f = fpsRaw ?: "--"
                            val t = tempRaw ?: "--"
                            val recIndicator = if (isRecording) "🔴 " else ""
                            overlayView.text = if (showTemp) "$recIndicator$f FPS | $t°C" else "$recIndicator$f FPS"
                        }
                    }
                } catch (e: Exception) {
                    handler.post { if (isServiceActive) overlayView.text = "FPS NODE BLOCKED" }
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

            dbHelper.saveFpsSession(foregroundApp, duration, min, max, avg, avgT, downsampledFps, downsampledTemp)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceActive = false
        isRunning = false
    }
}
