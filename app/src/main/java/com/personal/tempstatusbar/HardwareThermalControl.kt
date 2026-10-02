package com.personal.tempstatusbar

import android.media.AudioManager
import android.media.ToneGenerator
import java.io.BufferedReader
import java.io.InputStreamReader

object HardwareThermalControl {

    private var toneGen: ToneGenerator? = null
    var isChargingThrottled = false
        private set
    var isEmergencyCooldownActive = false
        private set
    private var isMuted = false

    private var cachedRootState: Boolean? = null
    private var lastRootCheckTime = 0L

    fun init() {
        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (e: Exception) {}
        isRootAvailable()
    }

    fun isRootAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (cachedRootState != null && (now - lastRootCheckTime < 60000L)) return cachedRootState!!
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val hasRoot = reader.readLine()?.contains("uid=0") == true
            p.waitFor()
            cachedRootState = hasRoot
            lastRootCheckTime = now
            hasRoot
        } catch (e: Exception) {
            cachedRootState = false
            false
        }
    }

    // --- PMIC POWER MANAGEMENT ---
    fun setChargingEnabled(enable: Boolean) {
        if (!isRootAvailable()) return
        val value = if (enable) "1" else "0"
        val suspendVal = if (enable) "0" else "1"
        try {
            val cmd = "echo $value > /sys/class/power_supply/battery/charging_enabled; " +
                      "echo $suspendVal > /sys/class/power_supply/battery/input_suspend"
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
            isChargingThrottled = !enable
        } catch (e: Exception) {}
    }

    fun forceEmergencyCooldown() {
        isEmergencyCooldownActive = true
        setChargingEnabled(false)
    }

    fun clearEmergencyCooldown() {
        isEmergencyCooldownActive = false
    }

    // --- CPU CORE & TASK MANAGEMENT ---
    fun setCoreOnline(coreId: Int, online: Boolean) {
        if (!isRootAvailable()) return
        val state = if (online) "1" else "0"
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "echo $state > /sys/devices/system/cpu/cpu$coreId/online")).waitFor()
        } catch (e: Exception) {}
    }

    fun killProcess(pid: Int) {
        if (!isRootAvailable()) return
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -9 $pid")).waitFor() } catch (e: Exception) {}
    }

    fun pinProcessToEfficiencyCores(pid: Int) {
        if (!isRootAvailable()) return
        // Mask 0f targets cores 0-3 (Silver Efficiency cluster)
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "taskset -p 0f $pid")).waitFor() } catch (e: Exception) {}
    }

    // --- ALERTS ---
    fun playThermalAlert() {
        if (isMuted) return
        try { toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500) } catch (e: Exception) {}
    }

    fun muteAlarm() {
        isMuted = true
        try { toneGen?.stopTone() } catch (e: Exception) {}
    }

    fun resetMute() {
        isMuted = false
    }

    fun getKernelProcessSnapshot(): Pair<String, Int> {
        if (!isRootAvailable()) return Pair("", -1)
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "top -b -n 1 -m 5"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var topPid = -1
            var line: String?
            var headerPassed = false
            var count = 0

            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.contains("PID") && (l.contains("CPU") || l.contains("ARGS"))) {
                    headerPassed = true
                    continue
                }
                if (headerPassed && l.isNotEmpty()) {
                    val tokens = l.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    if (tokens.size >= 5) {
                        val pid = tokens[0].toIntOrNull() ?: -1
                        var cpuStr = tokens.firstOrNull { it.endsWith("%") } ?: (tokens.getOrNull(4) ?: "0%")
                        if (!cpuStr.endsWith("%")) cpuStr = "$cpuStr%"
                        val rawName = tokens.last()

                        if (!rawName.startsWith("[") && !rawName.startsWith("top")) {
                            if (count == 0) topPid = pid
                            sb.append("• ").append(rawName).append(" — ").append(cpuStr).append(" (PID ").append(pid).append(")\n")
                            count++
                            if (count >= 3) break
                        }
                    }
                }
            }
            p.waitFor()
            Pair(sb.toString().trim(), topPid)
        } catch (e: Exception) {
            Pair("", -1)
        }
    }

    fun destroy() {
        try { setChargingEnabled(true); toneGen?.release() } catch (e: Exception) {}
    }
}
