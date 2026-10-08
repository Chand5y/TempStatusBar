package com.personal.tempstatusbar

import android.service.quicksettings.TileService
import kotlin.concurrent.thread

class NetworkInfoTileService : TileService() {
    override fun onClick() {
        super.onClick()
        
        // Log the tap immediately so we know the button is responding
        AppLogger.log("QS TILE ACTION: Network Info Tapped")
        
        thread {
            // 1. Forcefully collapse the notification panel via root
            HardwareThermalControl.executeRootCommand("Collapse Panel", "cmd statusbar collapse")
            
            // 2. Force launch the hidden Radio menu, bypassing HyperOS background restrictions
            val success = HardwareThermalControl.executeRootCommand("Launch RadioInfo (Standard)", "am start -n com.android.settings/.RadioInfo")
            
            // 3. If standard Android namespace fails, use the Xiaomi-specific namespace
            if (!success) {
                HardwareThermalControl.executeRootCommand("Launch RadioInfo (Xiaomi)", "am start -n com.android.phone/com.android.phone.settings.RadioInfo")
            }
        }
    }
}
