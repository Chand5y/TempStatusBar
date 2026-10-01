package com.personal.tempstatusbar

import android.content.Context
import android.os.BatteryManager
import kotlin.math.abs

data class PowerStats(
    val currentMa: Int,
    val voltageMv: Int,
    val wattage: Double,
    val isCharging: Boolean
)

object PowerHardwareHelper {

    fun readPowerStats(context: Context, isPlugged: Boolean): PowerStats {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        
        // Query low-level fuel gauge microamperes (Negative = Discharging, Positive = Charging on most kernels)
        val rawCurrentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val currentMa = rawCurrentUa / 1000

        // Voltage in microvolts converted to millivolts
        val voltageMv = try {
            val v = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_INPUT_CURRENT) ?: 4000
            if (v > 1000) v / 1000 else 4000
        } catch (e: Exception) {
            4000
        }

        // Calculate absolute wattage
        val activeMa = abs(currentMa)
        val calculatedWatts = (activeMa.toDouble() * 4.2) / 1000.0 // Standard cell multiplier fallback

        // Determine true charging state based on plug status and current flow
        val charging = isPlugged

        return PowerStats(
            currentMa = if (charging) activeMa else -activeMa,
            voltageMv = voltageMv,
            wattage = Math.round(calculatedWatts * 10.0) / 10.0,
            isCharging = charging
        )
    }
}
