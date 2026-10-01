package com.personal.tempstatusbar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import kotlin.concurrent.thread

class TempMonitorService : Service() {

    private val CHANNEL_ID = "temp_channel"
    private val NOTIF_ID = 1001

    private lateinit var dbHelper: DatabaseHelper
    private var lastTemp = -999
    private var lastPlugged = -1
    private var lastLogTime = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val currentTemp = rawTemp / 10
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val isCharging = plugged != 0

                val chargeType = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "Fast AC Charger"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB Cable"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Dock"
                    else -> "Discharging (Battery)"
                }

                // Update status bar notification
                if (currentTemp != lastTemp) {
                    updateNotification(currentTemp)
                }

                // Smart Logging: Log if temp or charging state changed, throttled to 30s minimum
                val now = System.currentTimeMillis()
                if ((currentTemp != lastTemp || plugged != lastPlugged || (now - lastLogTime > 300000L)) && (now - lastLogTime > 30000L)) {
                    lastTemp = currentTemp
                    lastPlugged = plugged
                    lastLogTime = now

                    // Capture asynchronously to keep system broadcast instantly responsive
                    thread {
                        val (isRoot, details) = ProcessInspector.captureProcesses(applicationContext)
                        dbHelper.insertRecord(currentTemp, isCharging, chargeType, details, isRoot)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = DatabaseHelper(this)
        createChannel()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Thermal Monitor Active")
            .setSmallIcon(drawIcon("--"))
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    private fun updateNotification(temp: Int) {
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery: $temp°C")
            .setSmallIcon(drawIcon("$temp°"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIF_ID, notif)
    }

    private fun drawIcon(text: String): Icon {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 38f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val yPos = (size / 2 - (paint.descent() + paint.ascent()) / 2)
        canvas.drawText(text, size / 2f, yPos, paint)
        return Icon.createWithBitmap(bmp)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "Live Temp", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(chan)
        }
    }
}
