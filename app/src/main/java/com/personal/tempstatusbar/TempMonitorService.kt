package com.personal.tempstatusbar

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.Icon
import android.os.*
import kotlin.concurrent.thread
import kotlin.math.abs

class TempMonitorService : Service() {

    private val CHANNEL_ID = "temp_channel"
    private val ALERT_CHANNEL_ID = "thermal_alert_channel"
    private val NOTIF_ID = 1001
    private val ALERT_NOTIF_ID = 1002

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var settings: SettingsManager
    private lateinit var backgroundHandler: Handler
    private lateinit var handlerThread: HandlerThread

    private var lastTemp = -999
    private var lastPlugged = -1
    private var lastLoggedWatts = -1.0
    private var lastLoggedMa = -999
    private var isScreenOn = true
    var isSmartGovernorActive = false

    private var isThermalCutoff = false
    private var isPctCutoff = false
    
    private var gpuLogCounter = 0

    private val iconSize = 64
    private val cachedBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
    private val cachedCanvas = Canvas(cachedBitmap)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 38f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }

    private val dbLogRunnable = object : Runnable {
        override fun run() {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val currentTemp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            if (currentTemp > 0) {
                triggerDatabaseSnapshot(currentTemp, plugged != 0, isScreenOn)
            }
            backgroundHandler.postDelayed(this, 60000L)
        }
    }

    private val gamingEngineRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn && settings.gamingModeEnabled && HardwareThermalControl.isRootAvailable()) {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val time = System.currentTimeMillis()
                val events = usm.queryEvents(time - 5000, time)
                var event = UsageEvents.Event()
                var currentForeground = ""
                
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        currentForeground = event.packageName
                    }
                }

                if (currentForeground.isNotEmpty()) {
                    val isGamingApp = settings.gamingApps.contains(currentForeground)
                    
                    if (isGamingApp) {
                        
                        // Dedicated GPU Diagnostics Tracker (Logs every ~6 seconds)
                        gpuLogCounter++
                        if (gpuLogCounter >= 2) {
                            thread {
                                val gpuLoad = HardwareThermalControl.getGpuUsage()
                                val gpuFreq = HardwareThermalControl.getGpuFrequency()
                                val primeCoreFreq = HardwareThermalControl.getCoreFrequencies().getOrNull(7) ?: "Offline"
                                backgroundHandler.post {
                                    AppLogger.log("📊 GPU DIAGNOSTICS -> Load: $gpuLoad | Clock: $gpuFreq | CPU C7: $primeCoreFreq")
                                }
                            }
                            gpuLogCounter = 0
                        }

                        // Gaming Mode Throttle Logic
                        if (lastTemp < settings.gameThrottleTemp) {
                            HardwareThermalControl.setPeakPerformanceMode(true)
                            HardwareThermalControl.throttlePrimeCore(false)
                            HardwareThermalControl.optimizeBackgroundForGaming(settings.exemptApps, currentForeground)
                        } else {
                            HardwareThermalControl.setPeakPerformanceMode(false)
                            HardwareThermalControl.throttlePrimeCore(true)
                        }
                    } else {
                        HardwareThermalControl.setPeakPerformanceMode(false)
                        HardwareThermalControl.clearGamingOptimization()
                        if (!isSmartGovernorActive || lastTemp < settings.warningTemp) {
                            HardwareThermalControl.throttlePrimeCore(false)
                        }
                    }
                }
            }
            backgroundHandler.postDelayed(this, 3000L)
        }
    }

    private val powerRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn && settings.showNotification && settings.showPowerMetrics) {
                checkPowerDeltaAndUpdate()
                backgroundHandler.postDelayed(this, 1500L)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val currentTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else 0

                    handleSafetyAndChargingLimits(currentTemp, pct)

                    if (currentTemp != lastTemp || plugged != lastPlugged) {
                        lastTemp = currentTemp; lastPlugged = plugged
                        pushNotificationUpdate()
                    }
                }
                Intent.ACTION_SCREEN_ON -> { isScreenOn = true; backgroundHandler.post(powerRunnable); backgroundHandler.post(gamingEngineRunnable) }
                Intent.ACTION_SCREEN_OFF -> { isScreenOn = false; backgroundHandler.removeCallbacks(powerRunnable); backgroundHandler.removeCallbacks(gamingEngineRunnable); HardwareThermalControl.setPeakPerformanceMode(false) }
                "ACTION_TOGGLE_SMART_GOVERNOR" -> {
                    isSmartGovernorActive = intent.getBooleanExtra("state", false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = DatabaseHelper(this)
        settings = SettingsManager(this)
        handlerThread = HandlerThread("ThermalWorkerThread", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        backgroundHandler = Handler(handlerThread.looper)

        HardwareThermalControl.init(this)
        createChannels()
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction("ACTION_TOGGLE_SMART_GOVERNOR")
        }, Context.RECEIVER_NOT_EXPORTED)
        
        backgroundHandler.post(dbLogRunnable)
        backgroundHandler.post(gamingEngineRunnable)
    }

    private fun triggerDatabaseSnapshot(temp: Int, isCharging: Boolean, screenOn: Boolean) {
        backgroundHandler.post {
            val chargeType = if (isCharging) "Charging AC" else "Discharging (Battery)"
            val hasRoot = HardwareThermalControl.isRootAvailable()
            val details = if (hasRoot) {
                val snapshotList = HardwareThermalControl.getKernelProcessSnapshot(applicationContext)
                if (snapshotList.isNotEmpty()) snapshotList.joinToString("\n") { "• ${it.name} — ${it.cpu}% CPU" } else "Kernel active (Idle)"
            } else { "Active app tracking requires Usage Access permission" }
            dbHelper.insertRecord(temp, isCharging, chargeType, details, hasRoot, screenOn)
        }
    }

    private fun handleSafetyAndChargingLimits(temp: Int, pct: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val safeTemp = settings.warningTemp - 1

        if (temp >= settings.warningTemp) {
            HardwareThermalControl.playThermalAlert()
            if (isSmartGovernorActive && !settings.gamingModeEnabled) backgroundHandler.post { HardwareThermalControl.applySmartThermalGovernor(applicationContext) }

            val muteIntent = PendingIntent.getBroadcast(this, 0, Intent(this, ThermalActionReceiver::class.java).apply { action = "ACTION_MUTE" }, PendingIntent.FLAG_IMMUTABLE)
            val coolIntent = PendingIntent.getBroadcast(this, 1, Intent(this, ThermalActionReceiver::class.java).apply { action = "ACTION_COOLDOWN" }, PendingIntent.FLAG_IMMUTABLE)

            val alertNotif = Notification.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("⚠️ OVERHEATING: ${temp}°C")
                .setContentText("Hardware limits exceeded.")
                .setSmallIcon(drawIcon("!"))
                .setColor(Color.RED)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "MUTE SOUND", muteIntent).build())
                .addAction(Notification.Action.Builder(null, "COOL DOWN NOW", coolIntent).build())
                .setContentIntent(getLaunchIntent())
                .build()
            manager?.notify(ALERT_NOTIF_ID, alertNotif)
        } else if (temp <= safeTemp) {
            manager?.cancel(ALERT_NOTIF_ID)
            HardwareThermalControl.resetMute()
            if (HardwareThermalControl.isEmergencyCooldownActive) {
                HardwareThermalControl.clearEmergencyCooldown()
            }
            if (isSmartGovernorActive && !settings.gamingModeEnabled) backgroundHandler.post { HardwareThermalControl.throttlePrimeCore(false) }
        }

        if (temp >= settings.cutoffTemp) isThermalCutoff = true
        else if (temp <= settings.resumeTemp) isThermalCutoff = false

        if (settings.chargeLimitEnabled) {
            if (pct >= settings.chargeLimitMax) isPctCutoff = true
            else if (pct <= settings.chargeLimitResume) isPctCutoff = false
        } else {
            isPctCutoff = false
        }

        if (!HardwareThermalControl.isEmergencyCooldownActive && !HardwareThermalControl.isManualBypassActive) {
            val shouldBeThrottled = isThermalCutoff || isPctCutoff
            if (shouldBeThrottled && !HardwareThermalControl.isChargingThrottled) {
                HardwareThermalControl.setChargingEnabled(false)
            } else if (!shouldBeThrottled && HardwareThermalControl.isChargingThrottled) {
                HardwareThermalControl.setChargingEnabled(true)
            }
        }
    }

    private fun checkPowerDeltaAndUpdate() {
        val stats = PowerHardwareHelper.readPowerStats(applicationContext, lastPlugged != 0)
        val wattDelta = abs(stats.wattage - lastLoggedWatts)
        val maDelta = abs(stats.currentMa - lastLoggedMa)
        if (wattDelta >= 0.5 || maDelta >= 50) { lastLoggedWatts = stats.wattage; lastLoggedMa = stats.currentMa; pushNotificationUpdate() }
    }

    private fun pushNotificationUpdate() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (!settings.showNotification) { manager?.cancel(NOTIF_ID); return }

        val stats = PowerHardwareHelper.readPowerStats(applicationContext, lastPlugged != 0)
        val bypassStr = if (HardwareThermalControl.isManualBypassActive && stats.isCharging) "\n🛡️ BYPASS" else ""
        
        val bodyText = if (settings.showPowerMetrics) {
            if (stats.isCharging) "⚡ Charging: ${stats.wattage}W (+${abs(stats.currentMa)} mA)$bypassStr" 
            else if (isThermalCutoff || isPctCutoff) "🛑 Charging Paused by Limits"
            else "🔋 Discharging: ${abs(stats.currentMa)} mA (-${stats.wattage}W)$bypassStr"
        } else { 
            if (stats.isCharging) "⚡ Charging$bypassStr" else "🔋 Discharging$bypassStr" 
        }

        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery: $lastTemp°C")
            .setContentText(bodyText)
            .setSmallIcon(drawIcon(if (lastTemp > 0) "$lastTemp°" else "--"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(getLaunchIntent())
            .build()
        manager?.notify(NOTIF_ID, notif)
    }

    private fun getLaunchIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun drawIcon(text: String): Icon {
        cachedBitmap.eraseColor(Color.TRANSPARENT)
        cachedCanvas.drawText(text, iconSize / 2f, (iconSize / 2 - (textPaint.descent() + textPaint.ascent()) / 2), textPaint)
        return Icon.createWithBitmap(cachedBitmap)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Thermal Monitor Active")
            .setSmallIcon(drawIcon("--"))
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        backgroundHandler.removeCallbacksAndMessages(null)
        HardwareThermalControl.destroy()
        handlerThread.quitSafely()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Live Monitor", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        manager?.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "Thermal Emergencies", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    }
}
