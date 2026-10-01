package com.personal.tempstatusbar

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs
import kotlin.math.roundToInt

data class PowerStats(
    val currentMa: Int,
    val voltageMv: Int,
    val wattage: Double,
    val isCharging: Boolean
)

object PowerHardwareHelper {

    fun readPowerStats(context: Context, isPlugged: Boolean): PowerStats {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        // Standard Android API: fuel gauge current in microamperes (µA)
        val rawCurrentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val absCurrentMa = when {
            abs(rawCurrentUa) > 100_000 -> abs(rawCurrentUa) / 1000
            abs(rawCurrentUa) > 0 -> abs(rawCurrentUa)
            else -> 0
        }

        // Standard Android API: read hardware voltage in mV directly from system broadcast
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawVoltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4000) ?: 4000
        val voltageMv = if (rawVoltage > 1000) rawVoltage else 4000

        // Accurate net wattage calculation: (mA * mV) / 1,000,000
        val calculatedWatts = (absCurrentMa.toDouble() * voltageMv.toDouble()) / 1_000_000.0
        val roundedWatts = (calculatedWatts * 10.0).roundToInt() / 10.0

        return PowerStats(
            currentMa = if (isPlugged) absCurrentMa else -absCurrentMa,
            voltageMv = voltageMv,
            wattage = roundedWatts,
            isCharging = isPlugged
        )
    }
}
