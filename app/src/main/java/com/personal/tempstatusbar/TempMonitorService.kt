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
    private var isCurrentlyGaming = false

    private val iconSize = 64
    private val cachedBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
    private val cachedCanvas = Canvas(cachedBitmap)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 38f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val dbLogRunnable = object : Runnable {
        override fun run() {
            // Do not run heavy top/dumpsys DB snapshots while actively in a game match!
            if (!isCurrentlyGaming) {
                val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val currentTemp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
                val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                if (currentTemp > 0) {
                    triggerDatabaseSnapshot(currentTemp, plugged != 0, isScreenOn)
                }
            }
            backgroundHandler.postDelayed(this, 60000L)
        }
    }

    // ZERO-OVERHEAD GAME DETECTOR: Uses Android UsageStatsManager (no su shell spam!) and engages locks ONCE per match
    private val gamingEngineRunnable = object : Runnable {
        override fun run() {
            if (!settings.gamingModeEnabled) {
                if (isCurrentlyGaming || HardwareThermalControl.isPeakPerformanceActive) {
                    isCurrentlyGaming = false
                    HardwareThermalControl.setPeakPerformanceMode(false)
                    HardwareThermalControl.clearGamingOptimization()
                }
                backgroundHandler.postDelayed(this, 5000L)
                return
            }

            if (isScreenOn && HardwareThermalControl.isRootAvailable()) {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val time = System.currentTimeMillis()
                val events = usm.queryEvents(time - 10000, time)
                val event = UsageEvents.Event()
                var latestResumedPkg = ""

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        val pkg = event.packageName
                        if (!pkg.contains("systemui") && !pkg.contains("tempstatusbar") && !pkg.contains("permissioncontroller")) {
                            latestResumedPkg = pkg
                        }
                    }
                }

                val targetGames = settings.gamingApps
                if (latestResumedPkg.isNotEmpty()) {
                    val isGamingApp = targetGames.any { latestResumedPkg.contains(it, ignoreCase = true) }
                    if (isGamingApp && !isCurrentlyGaming) {
                        isCurrentlyGaming = true
                        AppLogger.log("🎮 [GAME DETECTED] $latestResumedPkg -> Engaging Turbo Mode (Zero-Polling)")
                        HardwareThermalControl.setPeakPerformanceMode(true)
                        HardwareThermalControl.optimizeBackgroundForGaming(settings.exemptApps, latestResumedPkg)
                    } else if (!isGamingApp && isCurrentlyGaming && (latestResumedPkg.contains("home") || latestResumedPkg.contains("launcher"))) {
                        isCurrentlyGaming = false
                        AppLogger.log("🏁 [GAME EXITED] Returned to Home -> Restoring Stock Mode")
                        HardwareThermalControl.setPeakPerformanceMode(false)
                        HardwareThermalControl.clearGamingOptimization()
                    }
                }

                // Only check thermal cap using already-cached battery temperature (zero root shell calls!)
                if (isCurrentlyGaming && lastTemp >= settings.gameThrottleTemp) {
                    AppLogger.log("⚠️ [THERMAL CAP] Battery reached ${lastTemp}°C (Cap: ${settings.gameThrottleTemp}°C)")
                    HardwareThermalControl.setPeakPerformanceMode(false)
                    HardwareThermalControl.throttleCPU(true)
                }
            }
            backgroundHandler.postDelayed(this, 4000L)
        }
    }

    private val powerRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn && !isCurrentlyGaming && settings.showNotification && settings.showPowerMetrics) {
                checkPowerDeltaAndUpdate()
                backgroundHandler.postDelayed(this, 2000L)
            } else if (isScreenOn) {
                backgroundHandler.postDelayed(this, 5000L)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_DISCONNECTED -> {
                    lastPlugged = 0
                    isThermalCutoff = false
                    isPctCutoff = false
                    if (HardwareThermalControl.isManualBypassActive || HardwareThermalControl.isChargingThrottled) {
                        HardwareThermalControl.setChargingEnabled(true, isManualToggle = true)
                    }
                    pushNotificationUpdate()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val currentTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val pct = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else 0

                    if (plugged == 0) {
                        isThermalCutoff = false
                        isPctCutoff = false
                        if (lastPlugged > 0 && HardwareThermalControl.isManualBypassActive) {
                            HardwareThermalControl.setChargingEnabled(true, isManualToggle = true)
                        }
                    }

                    lastPlugged = plugged
                    lastTemp = currentTemp
                    handleSafetyAndChargingLimits(currentTemp, pct, plugged != 0)
                    pushNotificationUpdate()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    backgroundHandler.post(powerRunnable)
                    backgroundHandler.post(gamingEngineRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    isCurrentlyGaming = false
                    backgroundHandler.removeCallbacks(powerRunnable)
                    backgroundHandler.removeCallbacks(gamingEngineRunnable)
                    HardwareThermalControl.setPeakPerformanceMode(false)
                }
                "ACTION_TOGGLE_SMART_GOVERNOR" -> {
                    isSmartGovernorActive = intent.getBooleanExtra("state", false)
                }
                "ACTION_FORCE_NOTIF_UPDATE" -> {
                    refreshBatteryStateAndNotify()
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
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction("ACTION_TOGGLE_SMART_GOVERNOR")
            addAction("ACTION_FORCE_NOTIF_UPDATE")
        }, Context.RECEIVER_NOT_EXPORTED)

        backgroundHandler.post(dbLogRunnable)
        backgroundHandler.post(gamingEngineRunnable)
        backgroundHandler.post(powerRunnable)
    }

    private fun refreshBatteryStateAndNotify() {
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            lastTemp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
            lastPlugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            if (lastPlugged == 0) {
                isThermalCutoff = false
                isPctCutoff = false
            }
        }
        pushNotificationUpdate()
    }

    private fun triggerDatabaseSnapshot(temp: Int, isCharging: Boolean, screenOn: Boolean) {
        thread {
            val chargeType = if (isCharging) "Charging AC" else "Discharging (Battery)"
            val hasRoot = HardwareThermalControl.isRootAvailable()
            val details = if (hasRoot) {
                val snapshotList = HardwareThermalControl.getKernelProcessSnapshot(applicationContext)
                if (snapshotList.isNotEmpty()) snapshotList.joinToString("\n") { "• ${it.name} — ${it.cpu}% CPU" } else "Kernel active (Idle)"
            } else { "Active app tracking requires Usage Access permission" }
            dbHelper.insertRecord(temp, isCharging, chargeType, details, hasRoot, screenOn)
        }
    }

    private fun handleSafetyAndChargingLimits(temp: Int, pct: Int, isPluggedIn: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val activeWarnLimit = if (isCurrentlyGaming || HardwareThermalControl.isPeakPerformanceActive) {
            settings.gameThrottleTemp
        } else {
            settings.warningTemp
        }
        val safeTemp = activeWarnLimit - 1

        if (temp >= activeWarnLimit) {
            HardwareThermalControl.playThermalAlert()
            if (isSmartGovernorActive && !settings.gamingModeEnabled && !HardwareThermalControl.isPeakPerformanceActive) {
                backgroundHandler.post { HardwareThermalControl.applySmartThermalGovernor(applicationContext) }
            }

            val muteIntent = PendingIntent.getBroadcast(this, 0, Intent(this, ThermalActionReceiver::class.java).apply { action = "ACTION_MUTE" }, PendingIntent.FLAG_IMMUTABLE)
            val coolIntent = PendingIntent.getBroadcast(this, 1, Intent(this, ThermalActionReceiver::class.java).apply { action = "ACTION_COOLDOWN" }, PendingIntent.FLAG_IMMUTABLE)

            val alertNotif = Notification.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("OVERHEATING: ${temp}°C")
                .setContentText("Hardware thermal ceiling ($activeWarnLimit°C) reached.")
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
            HardwareThermalControl.stopThermalAlert()
            HardwareThermalControl.resetMute()
            if (HardwareThermalControl.isEmergencyCooldownActive) {
                HardwareThermalControl.clearEmergencyCooldown()
            }
            if (isSmartGovernorActive && !settings.gamingModeEnabled && !HardwareThermalControl.isPeakPerformanceActive) {
                backgroundHandler.post { HardwareThermalControl.throttleCPU(false) }
            }
        }

        if (!isPluggedIn) {
            isThermalCutoff = false
            isPctCutoff = false
            if (HardwareThermalControl.isChargingThrottled && !HardwareThermalControl.isManualBypassActive) {
                HardwareThermalControl.setChargingEnabled(true)
            }
            return
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
        if (wattDelta >= 0.5 || maDelta >= 50) {
            lastLoggedWatts = stats.wattage; lastLoggedMa = stats.currentMa
            pushNotificationUpdate()
        }
    }

    private fun buildLiveNotification(): Notification {
        val isPluggedIn = lastPlugged != 0
        val stats = PowerHardwareHelper.readPowerStats(applicationContext, isPluggedIn)

        val bodyText = if (settings.showPowerMetrics) {
            when {
                isPluggedIn && HardwareThermalControl.isManualBypassActive -> "Pass-Through Active (${abs(stats.currentMa)} mA)"
                isPluggedIn && (isThermalCutoff || isPctCutoff) -> "Charging Paused by Limits"
                isPluggedIn && stats.isCharging -> "Charging: ${stats.wattage}W (+${abs(stats.currentMa)} mA)"
                else -> "Discharging: ${abs(stats.currentMa)} mA (-${stats.wattage}W)"
            }
        } else {
            when {
                isPluggedIn && HardwareThermalControl.isManualBypassActive -> "Pass-Through Active"
                isPluggedIn && (isThermalCutoff || isPctCutoff) -> "Charging Paused by Limits"
                isPluggedIn && stats.isCharging -> "Charging"
                else -> "Discharging"
            }
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery: ${if (lastTemp > 0) "$lastTemp°C" else "--°C"}")
            .setContentText(bodyText)
            .setSmallIcon(drawIcon(if (lastTemp > 0) "$lastTemp°" else "--"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(getLaunchIntent())
            .build()
    }

    private fun pushNotificationUpdate() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (!settings.showNotification) {
            manager?.cancel(NOTIF_ID)
            return
        }
        manager?.notify(NOTIF_ID, buildLiveNotification())
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
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) {
            lastTemp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
            lastPlugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        }
        val notif = buildLiveNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        if (!settings.showNotification) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)
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
