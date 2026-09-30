package com.personal.tempstatusbar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TempMonitorService : Service() {

    private val CHANNEL_ID = "temp_channel"
    private val NOTIF_ID = 1001

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                updateNotification(raw / 10)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Temp Monitor Active")
            .setSmallIcon(createIcon("--"))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    private fun updateNotification(temp: Int) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery: $temp°C")
            .setSmallIcon(createIcon("$temp°"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun createIcon(text: String): Icon {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = 38f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val yPos = (size / 2 - (paint.descent() + paint.ascent()) / 2)
        canvas.drawText(text, (size / 2).toFloat(), yPos, paint)
        return Icon.createWithBitmap(bmp)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "Live Temp", NotificationManager.IMPORTANCE_LOW)
            chan.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }
}
