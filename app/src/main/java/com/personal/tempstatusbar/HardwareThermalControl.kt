package com.personal.tempstatusbar

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object HardwareThermalControl {
    private var toneGen: ToneGenerator? = null
    var isChargingThrottled = false; private set
    var isEmergencyCooldownActive = false; private set
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
            val hasRoot = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.contains("uid=0") == true
            p.waitFor()
            cachedRootState = hasRoot
            lastRootCheckTime = now
            hasRoot
        } catch (e: Exception) { false }
    }

    fun setChargingEnabled(enable: Boolean) {
        if (!isRootAvailable()) return
        val v = if (enable) "1" else "0"
        val sv = if (enable) "0" else "1"
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "echo $v > /sys/class/power_supply/battery/charging_enabled; echo $sv > /sys/class/power_supply/battery/input_suspend")).waitFor()
            isChargingThrottled = !enable
        } catch (e: Exception) {}
    }

    fun forceEmergencyCooldown() { isEmergencyCooldownActive = true; setChargingEnabled(false) }
    fun clearEmergencyCooldown() { isEmergencyCooldownActive = false }

    fun setCoreOnline(coreId: Int, online: Boolean) {
        if (!isRootAvailable()) return
        val state = if (online) "1" else "0"
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "echo $state > /sys/devices/system/cpu/cpu$coreId/online")).waitFor() } catch (e: Exception) {}
    }

    fun killProcess(pid: Int) {
        if (!isRootAvailable()) return
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -9 $pid")).waitFor() } catch (e: Exception) {}
    }

    fun pinProcessToEfficiencyCores(pid: Int) {
        if (!isRootAvailable()) return
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "taskset -p 0f $pid")).waitFor() } catch (e: Exception) {}
    }

    // NEW: Force clear kernel RAM caches
    fun clearRamCaches() {
        if (!isRootAvailable()) return
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "echo 3 > /proc/sys/vm/drop_caches")).waitFor() } catch (e: Exception) {}
    }

    fun playThermalAlert() {
        if (isMuted) return
        try { toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500) } catch (e: Exception) {}
    }
    fun muteAlarm() { isMuted = true; try { toneGen?.stopTone() } catch (e: Exception) {} }
    fun resetMute() { isMuted = false }

    fun getCoreFrequencies(): List<String> {
        val freqs = mutableListOf<String>()
        for (i in 0..7) {
            try {
                val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                if (f.exists()) freqs.add("${f.readText().trim().toInt() / 1000} MHz") else freqs.add("Offline")
            } catch (e: Exception) { freqs.add("Offline") }
        }
        return freqs
    }

    fun getRamUsage(context: Context): String {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val tMb = mi.totalMem / 1048576L
        val uMb = tMb - (mi.availMem / 1048576L)
        return "Used: ${uMb}MB / Total: ${tMb}MB (${((uMb.toDouble()/tMb)*100).toInt()}% Load)"
    }

    fun getKernelProcessSnapshot(): Pair<String, Int> {
        if (!isRootAvailable()) return Pair("", -1)
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "top -n 1 -m 8"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var topPid = -1
            var headerPassed = false
            var count = 0
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                if (l.contains("PID") && l.contains("USER")) { headerPassed = true; continue }
                if (headerPassed && l.isNotEmpty()) {
                    val tokens = l.split("\\s+".toRegex())
                    if (tokens.size >= 8) {
                        val pid = tokens[0].toIntOrNull() ?: -1
                        val cpu = tokens.subList(1, tokens.size).firstOrNull { it.matches(Regex("^\\d+(\\.\\d+)?$")) } ?: "0.0"
                        val name = tokens.last()
                        
                        if (pid > 0 && !name.startsWith("[") && !name.startsWith("top") && name.length > 2) {
                            if (count == 0) topPid = pid
                            val friendlyName = when {
                                name.contains("hvdcp_opti") -> "hvdcp (Fast Charge Engine)"
                                name.contains("system_server") -> "Android Core Engine"
                                name.contains("surfaceflinger") -> "Display Compositor"
                                else -> name
                            }
                            sb.append("• $friendlyName — $cpu% CPU (PID $pid)\n")
                            count++
                            if (count >= 5) break
                        }
                    }
                }
            }
            p.waitFor()
            Pair(sb.toString().trim(), topPid)
        } catch (e: Exception) { Pair("", -1) }
    }

    fun getAppBatteryDrain(): String {
        if (!isRootAvailable()) return "Root required to parse batterystats."
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys batterystats --charged | grep -E 'Estimated power use|Uid' | head -n 15"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("Uid")) sb.append(line!!.trim()).append("\n")
            }
            p.waitFor()
            if (sb.isEmpty()) "Gathering battery statistics (Needs more uptime)..." else sb.toString().trim()
        } catch (e: Exception) { "Failed to read batterystats." }
    }

    fun destroy() { try { setChargingEnabled(true); toneGen?.release() } catch (e: Exception) {} }
}
