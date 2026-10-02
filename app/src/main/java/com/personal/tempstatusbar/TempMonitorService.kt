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
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
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
                    val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    val currentTemp = rawTemp / 10
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    val isCharging = plugged != 0

                    handleThermalSafety(currentTemp, isCharging)

                    if (currentTemp != lastTemp || plugged != lastPlugged) {
                        lastTemp = currentTemp
                        lastPlugged = plugged
                        pushNotificationUpdate()

                        triggerDatabaseSnapshot(currentTemp, plugged)
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    backgroundHandler.post(powerRunnable)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    backgroundHandler.removeCallbacks(powerRunnable)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = DatabaseHelper(this)
        settings = SettingsManager(this)

        handlerThread = HandlerThread("ThermalWorkerThread", Process.THREAD_PRIORITY_BACKGROUND)
        handlerThread.start()
        backgroundHandler = Handler(handlerThread.looper)

        HardwareThermalControl.init()
        createChannels()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val batteryIntent = registerReceiver(receiver, filter)

        batteryIntent?.let {
            val initialTemp = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
            val initialPlugged = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            lastTemp = initialTemp
            lastPlugged = initialPlugged
            triggerDatabaseSnapshot(initialTemp, initialPlugged)
        }
    }

    private fun triggerDatabaseSnapshot(temp: Int, plugged: Int) {
        backgroundHandler.post {
            val isCharging = plugged != 0
            val chargeType = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "Fast AC Charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB Cable"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Dock"
                else -> "Discharging (Battery)"
            }

            val hasRoot = HardwareThermalControl.isRootAvailable()
            val details: String

            if (hasRoot) {
                val kernelSnapshot = HardwareThermalControl.getKernelProcessSnapshot()
                details = if (kernelSnapshot.isNotEmpty()) kernelSnapshot else "Kernel active (System running within normal thresholds)"
            } else {
                details = ProcessInspector.captureNonRootActiveApps(applicationContext)
            }

            dbHelper.insertRecord(temp, isCharging, chargeType, details, hasRoot)
        }
    }

    private fun handleThermalSafety(temp: Int, isCharging: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val warnLimit = settings.warningTemp
        val cutoffLimit = settings.cutoffTemp
        val resumeLimit = settings.resumeTemp

        if (temp >= warnLimit) {
            HardwareThermalControl.playThermalAlert()

            val alertNotif = Notification.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("THERMAL ALERT: ${temp}°C")
                .setContentText("Hardware warning threshold ($warnLimit°C) reached!")
                .setSmallIcon(drawIcon("!"))
                .setColor(Color.RED)
                .setOngoing(true)
                .setContentIntent(getLaunchIntent())
                .build()

            manager?.notify(ALERT_NOTIF_ID, alertNotif)
        } else {
            manager?.cancel(ALERT_NOTIF_ID)
        }

        if (temp >= cutoffLimit && isCharging && !HardwareThermalControl.isChargingThrottled) {
            HardwareThermalControl.setChargingEnabled(false)
        } else if (temp <= resumeLimit && HardwareThermalControl.isChargingThrottled) {
            HardwareThermalControl.setChargingEnabled(true)
        }
    }

    private fun checkPowerDeltaAndUpdate() {
        val stats = PowerHardwareHelper.readPowerStats(applicationContext, lastPlugged != 0)
        val wattDelta = abs(stats.wattage - lastLoggedWatts)
        val maDelta = abs(stats.currentMa - lastLoggedMa)

        if (wattDelta >= 0.5 || maDelta >= 50) {
            lastLoggedWatts = stats.wattage
            lastLoggedMa = stats.currentMa
            pushNotificationUpdate()
        }
    }

    private fun pushNotificationUpdate() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (!settings.showNotification) {
            manager?.cancel(NOTIF_ID)
            return
        }

        val stats = PowerHardwareHelper.readPowerStats(applicationContext, lastPlugged != 0)
        val bodyText = if (settings.showPowerMetrics) {
            if (stats.isCharging) "⚡ Charging: ${stats.wattage}W (+${abs(stats.currentMa)} mA)"
            else "🔋 Discharging: ${abs(stats.currentMa)} mA (-${stats.wattage}W)"
        } else {
            if (stats.isCharging) "⚡ Charging" else "🔋 Discharging"
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
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun drawIcon(text: String): Icon {
        cachedBitmap.eraseColor(Color.TRANSPARENT)
        val yPos = (iconSize / 2 - (textPaint.descent() + textPaint.ascent()) / 2)
        cachedCanvas.drawText(text, iconSize / 2f, yPos, textPaint)
        return Icon.createWithBitmap(cachedBitmap)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Thermal Monitor Active")
            .setSmallIcon(drawIcon("--"))
            .setOngoing(true)
            .setContentIntent(getLaunchIntent())
            .build()

        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        backgroundHandler.removeCallbacksAndMessages(null)
        HardwareThermalControl.destroy()
        handlerThread.quitSafely()
        cachedBitmap.recycle()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val chan = NotificationChannel(CHANNEL_ID, "Live Monitor", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            val alertChan = NotificationChannel(ALERT_CHANNEL_ID, "Thermal Emergencies", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
            manager?.createNotificationChannel(chan)
            manager?.createNotificationChannel(alertChan)
        }
    }
}
