package com.personal.tempstatusbar

import android.graphics.*
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class TurboTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileUi()
    }

    private fun syncTileUi() {
        val tile = qsTile ?: return
        val settings = SettingsManager(this)
        val isTurboOn = HardwareThermalControl.isPeakPerformanceActive || settings.gamingModeEnabled

        tile.state = if (isTurboOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Turbo Mode"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isTurboOn) "Active" else "Stock"
        }

        val customIcon = HardwareThermalControl.loadQuickTileIcon(this, "qs_turbo.png")
            ?: HardwareThermalControl.loadQuickTileIcon(this, "game.png")
        tile.icon = customIcon ?: drawCleanTurboFallbackIcon()
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!HardwareThermalControl.isRootAvailable()) {
            Toast.makeText(this, "Root access required for Turbo Mode", Toast.LENGTH_SHORT).show()
            return
        }

        val settings = SettingsManager(this)
        val newState = !(HardwareThermalControl.isPeakPerformanceActive || settings.gamingModeEnabled)

        settings.gamingModeEnabled = newState
        HardwareThermalControl.setPeakPerformanceMode(newState)

        qsTile?.let { tile ->
            tile.state = if (newState) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = "Turbo Mode"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (newState) "Active" else "Stock"
            }
            tile.updateTile()
        }

        Toast.makeText(
            this,
            if (newState) "Turbo Mode ON" else "Turbo Mode OFF (Stock Restored)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun drawCleanTurboFallbackIcon(): Icon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        // Clean lightning bolt silhouette for Quick Settings
        val path = Path().apply {
            moveTo(54f, 12f)
            lineTo(24f, 54f)
            lineTo(46f, 54f)
            lineTo(40f, 84f)
            lineTo(72f, 42f)
            lineTo(50f, 42f)
            close()
        }
        canvas.drawPath(path, paint)
        return Icon.createWithBitmap(bmp)
    }
}
