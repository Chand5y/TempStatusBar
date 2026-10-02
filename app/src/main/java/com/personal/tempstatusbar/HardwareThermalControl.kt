package com.personal.tempstatusbar

import android.media.AudioManager
import android.media.ToneGenerator
import java.io.BufferedReader
import java.io.InputStreamReader

object HardwareThermalControl {

    private var toneGen: ToneGenerator? = null
    var isChargingThrottled = false
        private set

    private var cachedRootState: Boolean? = null
    private var lastRootCheckTime = 0L

    fun init() {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {}
        isRootAvailable()
    }

    // Direct, cached root verification that never false-flags APatch
    fun isRootAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (cachedRootState != null && (now - lastRootCheckTime < 60000L)) {
            return cachedRootState!!
        }

        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val line = reader.readLine()
            p.waitFor()
            val hasRoot = line != null && line.contains("uid=0")
            cachedRootState = hasRoot
            lastRootCheckTime = now
            hasRoot
        } catch (e: Exception) {
            cachedRootState = false
            false
        }
    }

    // Direct PMIC Hardware Switch: Cuts current at the motherboard level
    fun setChargingEnabled(enable: Boolean) {
        if (!isRootAvailable()) return
        val value = if (enable) "1" else "0"
        val suspendVal = if (enable) "0" else "1"

        try {
            val cmd = "echo $value > /sys/class/power_supply/battery/charging_enabled 2>/dev/null; " +
                      "echo $suspendVal > /sys/class/power_supply/battery/input_suspend 2>/dev/null; " +
                      "echo $value > /sys/class/power_supply/bms/charging_enabled 2>/dev/null"
            
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor()
            isChargingThrottled = !enable
        } catch (e: Exception) {}
    }

    fun playThermalAlert() {
        try {
            toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
        } catch (e: Exception) {}
    }

    // High-precision CPU & daemon inspector with exact CPU % extraction
    fun getKernelProcessSnapshot(): String {
        if (!isRootAvailable()) return ""

        return try {
            // Run one-shot top with batch format: PID, CPU%, and process command
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "top -b -n 1 -m 8"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var line: String?
            var headerPassed = false
            var count = 0

            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue

                // Locate the dynamic column header row
                if (l.contains("PID") && (l.contains("CPU") || l.contains("%CPU") || l.contains("ARGS") || l.contains("CMD") || l.contains("NAME"))) {
                    headerPassed = true
                    continue
                }

                if (headerPassed && l.isNotEmpty()) {
                    val tokens = l.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    if (tokens.size >= 5) {
                        val pid = tokens[0]
                        
                        // Locate the token containing CPU percentage
                        var cpuStr = tokens.firstOrNull { it.endsWith("%") && it.length <= 5 }
                        if (cpuStr == null) {
                            // Fallback index search if '%' sign is omitted by toybox
                            cpuStr = tokens.getOrNull(4) ?: "0%"
                        }
                        if (!cpuStr.endsWith("%")) cpuStr = "$cpuStr%"

                        val rawName = tokens.last()

                        // Filter internal idle worker threads to highlight real tasks
                        if (!rawName.startsWith("[") && !rawName.startsWith("top") && !rawName.startsWith("sh")) {
                            val friendlyName = when {
                                rawName.contains("hvdcp_opti") -> "hvdcp_opti (Qualcomm 67W Turbo Controller)"
                                rawName.contains("surfaceflinger") -> "surfaceflinger (Display Compositor)"
                                rawName.contains("system_server") -> "system_server (Android Core Engine)"
                                rawName.contains("com.miui.home") -> "Xiaomi Launcher"
                                else -> rawName
                            }

                            sb.append("• ").append(friendlyName)
                              .append(" — ").append(cpuStr)
                              .append(" (PID ").append(pid).append(")\n")

                            count++
                            if (count >= 5) break
                        }
                    }
                }
            }
            p.waitFor()
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun destroy() {
        try {
            setChargingEnabled(true)
            toneGen?.release()
        } catch (e: Exception) {}
    }
}
