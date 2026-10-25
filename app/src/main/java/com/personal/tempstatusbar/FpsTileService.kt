package com.personal.tempstatusbar

import android.app.PendingIntent
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.concurrent.thread

class FpsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = FpsOverlayService.isRunning

        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "FPS Pill"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) "Active" else "Off"
        }

        val customIcon = HardwareThermalControl.loadQuickTileIcon(this, "qs_fps.png")
        tile.icon = customIcon ?: drawCleanFpsFallbackIcon()
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = FpsOverlayService.isRunning

        if (!isRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        val pi = PendingIntent.getActivity(
                            this,
                            202,
                            permIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        startActivityAndCollapse(pi)
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(permIntent)
                    }
                } catch (e: Exception) {}
                return
            }

            startService(Intent(this, FpsOverlayService::class.java))
            if (HardwareThermalControl.isRootAvailable()) {
                thread { HardwareThermalControl.runRootShell("cmd statusbar collapse") }
            }
        } else {
            stopService(Intent(this, FpsOverlayService::class.java))
        }

        qsTile?.let {
            it.state = if (!isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.subtitle = if (!isRunning) "Active" else "Off"
            }
            it.updateTile()
        }
    }

    private fun drawCleanFpsFallbackIcon(): Icon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRoundRect(RectF(12f, 24f, 84f, 72f), 24f, 24f, paint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("FPS", 48f, 57f, textPaint)
        return Icon.createWithBitmap(bmp)
    }
}
