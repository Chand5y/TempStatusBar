package com.personal.tempstatusbar

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class NetworkInfoTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return

        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
        val tempText = if (temp > 0) "$temp°C" else "--°C"

        tile.state = Tile.STATE_ACTIVE
        tile.label = "Battery $tempText"

        // Converts qs_info.png into a true monochrome alpha-mask silhouette
        val silhouette = HardwareThermalControl.loadQuickTileIcon(this, "qs_info.png")
        if (silhouette != null) {
            tile.icon = silhouette
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivityAndCollapse(launchIntent)
    }
}
