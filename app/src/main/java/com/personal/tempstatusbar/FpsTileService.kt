package com.personal.tempstatusbar

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class FpsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val isRunning = FpsOverlayService.isRunning

        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "FPS Pill"

        // Converts qs_fps.png into a true monochrome alpha-mask silhouette[span_0](start_span)[span_0](end_span)
        val silhouette = HardwareThermalControl.loadQuickTileIcon(this, "qs_fps.png")
        if (silhouette != null) {
            tile.icon = silhouette
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = FpsOverlayService.isRunning
        if (!isRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivityAndCollapse(intent)
                return
            }
            startService(Intent(this, FpsOverlayService::class.java))
        } else {
            stopService(Intent(this, FpsOverlayService::class.java))
        }
        onStartListening()
    }
}
