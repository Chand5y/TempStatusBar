package com.personal.tempstatusbar

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.concurrent.thread

class NetworkInfoTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return

        tile.state = Tile.STATE_INACTIVE
        tile.label = "Phone Info"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = "5G / Network"
        }

        val customIcon = HardwareThermalControl.loadQuickTileIcon(this, "qs_info.png")
        tile.icon = customIcon ?: drawCleanFallbackIcon()
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        // 1. If Root is available, bypass Android 15 SecurityException on RadioInfo & collapse QS shade cleanly
        if (HardwareThermalControl.isRootAvailable()) {
            thread {
                val cmd = """
                    cmd statusbar collapse
                    am start -n com.android.phone/com.android.phone.settings.RadioInfo 2>/dev/null || \
                    am start -n com.android.settings/com.android.settings.TestingSettings 2>/dev/null || \
                    am start -a android.settings.NETWORK_OPERATOR_SETTINGS 2>/dev/null
                """.trimIndent()
                HardwareThermalControl.runRootShell(cmd)
            }
            return
        }

        // 2. Non-Root Safe Fallback using PendingIntent (Never calls deprecated startActivityAndCollapse(Intent) on API 34+)
        val candidates = listOf(
            Intent().apply { component = ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo") },
            Intent().apply { component = ComponentName("com.android.settings", "com.android.settings.TestingSettings") },
            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )

        for (candidate in candidates) {
            if (tryLaunchWithPendingIntent(candidate)) return
        }
    }

    private fun tryLaunchWithPendingIntent(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageManager.resolveActivity(intent, 0) == null) return false

            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this,
                    201,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun drawCleanFallbackIcon(): Icon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Clean signal/network antenna vector silhouette on transparent background
        canvas.drawArc(RectF(16f, 16f, 80f, 80f), -135f, 90f, false, paint)
        canvas.drawArc(RectF(28f, 28f, 68f, 68f), -135f, 90f, false, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(48f, 52f, 6f, paint)
        canvas.drawRect(44f, 56f, 52f, 82f, paint)
        return Icon.createWithBitmap(bmp)
    }
}
