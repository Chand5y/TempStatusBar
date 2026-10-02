package com.personal.tempstatusbar

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.Icon
import android.os.*
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

    private val iconSize = 64
    private val cachedBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
    private val cachedCanvas = Canvas(cachedBitmap)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 38f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }

    // Forces a DB log every 60 seconds so the chart never stays empty
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
                    val stats = PowerHardwareHelper.readPowerStats(applicationContext, plugged != 0)

                    handleThermalSafety(currentTemp, stats.isCharging)

                    if (currentTemp != lastTemp || plugged != lastPlugged) {
                        lastTemp = currentTemp
                        lastPlugged = plugged
                        pushNotificationUpdate()
                    }
                }
                Intent.ACTION_SCREEN_ON -> { isScreenOn = true; backgroundHandler.post(powerRunnable) }
                Intent.ACTION_SCREEN_OFF -> { isScreenOn = false; backgroundHandler.removeCallbacks(powerRunnable) }
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
        })
        
        // Start the 60-second database logger
        backgroundHandler.post(dbLogRunnable)
    }

    private fun triggerDatabaseSnapshot(temp: Int, isCharging: Boolean, screenOn: Boolean) {
        backgroundHandler.post {
            val chargeType = if (isCharging) "Charging Connected" else "Discharging (Battery)"
            val hasRoot = HardwareThermalControl.isRootAvailable()
            val details = if (hasRoot) {
                val snapshotList = HardwareThermalControl.getKernelProcessSnapshot(applicationContext)
                if (snapshotList.isNotEmpty()) snapshotList.joinToString("\n") { "• ${it.name} — ${it.cpu}% CPU" } else "Kernel active (Idle)"
            } else { ProcessInspector.captureNonRootActiveApps(applicationContext) }
            dbHelper.insertRecord(temp, isCharging, chargeType, details, hasRoot, screenOn)
        }
    }

    private fun handleThermalSafety(temp: Int, isCharging: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val safeTemp = settings.warningTemp - 1

        if (temp >= settings.warningTemp) {
            HardwareThermalControl.playThermalAlert()
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
                HardwareThermalControl.setChargingEnabled(true)
            }
        }

        if (temp >= settings.cutoffTemp && isCharging && !HardwareThermalControl.isChargingThrottled && !HardwareThermalControl.isEmergencyCooldownActive) {
            HardwareThermalControl.setChargingEnabled(false)
        } else if (temp <= settings.resumeTemp && HardwareThermalControl.isChargingThrottled && !HardwareThermalControl.isEmergencyCooldownActive) {
            HardwareThermalControl.setChargingEnabled(true)
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
        val bodyText = if (settings.showPowerMetrics) {
            if (stats.isCharging) "⚡ Charging: ${stats.wattage}W (+${abs(stats.currentMa)} mA)" else "🔋 Discharging: ${abs(stats.currentMa)} mA (-${stats.wattage}W)"
        } else { if (stats.isCharging) "⚡ Charging" else "🔋 Discharging" }

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
        startForeground(NOTIF_ID, Notification.Builder(this, CHANNEL_ID).setContentTitle("Thermal Monitor Active").setSmallIcon(drawIcon("--")).build())
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
