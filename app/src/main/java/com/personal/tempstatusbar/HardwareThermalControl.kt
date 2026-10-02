package com.personal.tempstatusbar

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale

data class RamProc(val name: String, val sizeMb: Int, val pid: Int)
data class ProcessData(val pid: Int, val name: String, val cpu: String)

object HardwareThermalControl {
    private var toneGen: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    var isChargingThrottled = false; private set
    var isEmergencyCooldownActive = false; private set
    private var isMuted = false
    private var cachedRootState: Boolean? = null
    private var lastRootCheckTime = 0L

    fun init(context: Context) {
        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (e: Exception) {}
        try { vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator } catch (e: Exception) {}
        isRootAvailable()
    }

    fun getHardwareInfo(): String {
        return (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE).uppercase(Locale.getDefault())
    }

    fun getGpuUsage(): String {
        return try {
            val f = File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            if (f.exists()) "${f.readText().trim()}%" else "--%"
        } catch (e: Exception) { "--%" }
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
    fun setCoreOnline(coreId: Int, online: Boolean) { if (!isRootAvailable()) return; try { Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ${if (online) "1" else "0"} > /sys/devices/system/cpu/cpu$coreId/online")).waitFor() } catch (e: Exception) {} }
    fun killProcess(pid: Int) { if (!isRootAvailable()) return; try { Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -9 $pid")).waitFor() } catch (e: Exception) {} }
    fun pinProcessToEfficiencyCores(pid: Int) { if (!isRootAvailable()) return; try { Runtime.getRuntime().exec(arrayOf("su", "-c", "taskset -p 0f $pid")).waitFor() } catch (e: Exception) {} }
    fun clearRamCaches() { if (!isRootAvailable()) return; try { Runtime.getRuntime().exec(arrayOf("su", "-c", "echo 3 > /proc/sys/vm/drop_caches")).waitFor() } catch (e: Exception) {} }

    fun playThermalAlert() {
        if (isMuted) return
        try { toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500) } catch (e: Exception) {}
        try { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0)) } catch (e: Exception) {}
    }
    fun muteAlarm() { isMuted = true; try { toneGen?.stopTone(); vibrator?.cancel() } catch (e: Exception) {} }
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
        return "Used: ${uMb}MB / Total: ${tMb}MB\n(${((uMb.toDouble()/tMb)*100).toInt()}% Load)"
    }

    fun getDetailedRam(): List<RamProc> {
        if (!isRootAvailable()) return emptyList()
        val list = mutableListOf<RamProc>()
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys meminfo | grep -A 20 'Total PSS by process:'"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var line: String?
            val reg = Regex("""([\d,]+)K:\s+([\w\.]+)\s+\(pid\s+(\d+)\)""")
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("OOM adjustment:")) break
                reg.find(line!!)?.let {
                    val mb = it.groupValues[1].replace(",", "").toIntOrNull()?.div(1024) ?: 0
                    if (mb > 10) list.add(RamProc(it.groupValues[2], mb, it.groupValues[3].toInt()))
                }
            }
            p.waitFor()
        } catch (e: Exception) {}
        return list.sortedByDescending { it.sizeMb }
    }

    // Resolves package names to friendly names, hides PID structurally
    fun getKernelProcessSnapshot(context: Context): List<ProcessData> {
        val list = mutableListOf<ProcessData>()
        if (!isRootAvailable()) return list
        try {
            val pm = context.packageManager
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "top -n 1 -m 8"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
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
                        val rawName = tokens.last()
                        
                        if (pid > 0 && !rawName.startsWith("[") && !rawName.startsWith("top") && rawName.length > 2) {
                            var friendlyName = rawName
                            if (rawName.contains(".")) {
                                try { friendlyName = pm.getApplicationLabel(pm.getApplicationInfo(rawName, 0)).toString() } catch (e: Exception) {}
                            } else {
                                friendlyName = when {
                                    rawName.contains("hvdcp") -> "Fast Charge Controller"
                                    rawName.contains("system_server") -> "Android System Core"
                                    rawName.contains("surfaceflinger") -> "Display Compositor"
                                    else -> rawName
                                }
                            }
                            list.add(ProcessData(pid, friendlyName, cpu))
                            count++
                            if (count >= 5) break
                        }
                    }
                }
            }
            p.waitFor()
        } catch (e: Exception) {}
        return list
    }

    fun getAppBatteryDrain(): String {
        if (!isRootAvailable()) return "Root required to parse batterystats."
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys batterystats | grep -iE 'Device battery use:|Estimated power use:' -A 15"))
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

    fun destroy() { try { setChargingEnabled(true); toneGen?.release(); vibrator?.cancel() } catch (e: Exception) {} }
}
