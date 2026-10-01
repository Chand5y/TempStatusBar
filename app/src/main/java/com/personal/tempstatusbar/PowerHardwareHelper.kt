package com.personal.tempstatusbar

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

data class PowerStats(
    val currentMa: Int,
    val voltageMv: Int,
    val wattage: Double,
    val isCharging: Boolean
)

object PowerHardwareHelper {

    private var currentFile: RandomAccessFile? = null
    private var voltageFile: RandomAccessFile? = null
    private val buffer = ByteArray(32)

    init {
        findPowerNodes()
    }

    private fun findPowerNodes() {
        val currentPaths = arrayOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/bms/current_now"
        )
        val voltagePaths = arrayOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/bms/voltage_now"
        )

        for (p in currentPaths) {
            val f = File(p)
            if (f.exists() && f.canRead()) {
                try { currentFile = RandomAccessFile(f, "r"); break } catch (e: Exception) {}
            }
        }

        for (p in voltagePaths) {
            val f = File(p)
            if (f.exists() && f.canRead()) {
                try { voltageFile = RandomAccessFile(f, "r"); break } catch (e: Exception) {}
            }
        }
    }

    fun readPowerStats(isPlugged: Boolean): PowerStats {
        val rawCurrent = readNode(currentFile)
        val rawVoltage = readNode(voltageFile)

        // Microamps/Microvolts normalization for Qualcomm PMIC
        val currentMa = when {
            abs(rawCurrent) > 100000 -> rawCurrent / 1000
            else -> rawCurrent
        }

        val voltageMv = when {
            rawVoltage > 100000 -> rawVoltage / 1000
            rawVoltage > 0 -> rawVoltage
            else -> 4000 // Fallback nominal cell voltage
        }

        val calculatedWatts = (abs(currentMa).toDouble() * voltageMv.toDouble()) / 1_000_000.0

        return PowerStats(
            currentMa = currentMa,
            voltageMv = voltageMv,
            wattage = Math.round(calculatedWatts * 10.0) / 10.0,
            isCharging = isPlugged && currentMa >= 0
        )
    }

    private fun readNode(raf: RandomAccessFile?): Int {
        if (raf == null) return 0
        return try {
            raf.seek(0)
            val len = raf.read(buffer)
            if (len > 0) {
                var res = 0
                var neg = false
                var i = 0
                if (buffer[0] == '-'.code.toByte()) { neg = true; i = 1 }
                while (i < len) {
                    val b = buffer[i].toInt().toChar()
                    if (b in '0'..'9') {
                        res = res * 10 + (b - '0')
                    } else if (b == '\n') break
                    i++
                }
                if (neg) -res else res
            } else 0
        } catch (e: Exception) {
            0
        }
    }
}
