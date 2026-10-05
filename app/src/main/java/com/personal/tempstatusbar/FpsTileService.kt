package com.personal.tempstatusbar

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class FpsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = if (FpsOverlayService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, FpsOverlayService::class.java)
        if (FpsOverlayService.isRunning) {
            stopService(intent)
            qsTile?.state = Tile.STATE_INACTIVE
        } else {
            startService(intent)
            qsTile?.state = Tile.STATE_ACTIVE
        }
        qsTile?.updateTile()
    }
}
