package com.personal.tempstatusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class ThermalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "ACTION_MUTE" -> {
                HardwareThermalControl.muteAlarm()
                Toast.makeText(context, "Thermal alarm muted", Toast.LENGTH_SHORT).show()
            }
            "ACTION_COOLDOWN" -> {
                HardwareThermalControl.muteAlarm()
                HardwareThermalControl.forceEmergencyCooldown()
                Toast.makeText(context, "Emergency Cooldown: PMIC Input Cut", Toast.LENGTH_LONG).show()
            }
        }
    }
}
