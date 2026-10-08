package com.personal.tempstatusbar

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import android.widget.Toast

class NetworkInfoTileService : TileService() {
    override fun onClick() {
        super.onClick()
        
        // Target the hidden Android RadioInfo/Testing menu
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName("com.android.settings", "com.android.settings.RadioInfo")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ requires a PendingIntent to collapse the notification panel
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            try {
                // Fallback for some Xiaomi/HyperOS variants that move it to the phone package
                intent.setClassName("com.android.phone", "com.android.phone.settings.RadioInfo")
                if (Build.VERSION.SDK_INT >= 34) {
                    val pendingIntent = PendingIntent.getActivity(
                        this, 0, intent, 
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    startActivityAndCollapse(pendingIntent)
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            } catch (e2: Exception) {
                AppLogger.log("TileService Error: ${e2.message}")
                Toast.makeText(this, "Hidden network menu blocked by OEM", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
