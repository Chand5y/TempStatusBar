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
import kotlin.concurrent.thread

data class RamProc(val name: String, val sizeMb: Int, val pid: Int)
data class ProcessData(val pid: Int, val name: String, val cpu: String)

object HardwareThermalControl {
    private var toneGen: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var appContext: Context? = null

    var isChargingThrottled = false; private set
    var isManualBypassActive = false; private set
    var isEmergencyCooldownActive = false; private set
    private var isMuted = false
    private var cachedRootState: Boolean? = null
    private var lastRootCheckTime = 0L
    
    private var isPeakPerformanceActive = false
    private var isPrimeCoreThrottled: Boolean? = null
    private var lastOptimizedGame = ""

    fun init(context: Context) {
        appContext = context.applicationContext
        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (e: Exception) {}
        try { vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator } catch (e: Exception) {}
        isRootAvailable()
    }

    fun executeRootCommand(action: String, command: String): Boolean {
        if (!isRootAvailable()) {
            AppLogger.log("KERNEL DENIED: Root not available for [$action]")
            return false
        }
        var exitCode = -1
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            exitCode = process.waitFor()
            if (action != "Force Prime Core Max") {
                AppLogger.log("KERNEL ACTION: $action | EXIT: $exitCode")
            }
            return exitCode == 0
        } catch (e: Exception) { return false }
    }

    fun forceRefreshRate(hz: Int) {
        val cmd = "settings put system peak_refresh_rate $hz; settings put system min_refresh_rate $hz; settings put system user_refresh_rate $hz; settings put secure miui_refresh_rate $hz; settings put system miui_refresh_rate $hz; service call SurfaceFlinger 1035 i32 $hz"
        executeRootCommand("Force Display Refresh to ${hz}Hz", cmd)
    }

    fun setPeakPerformanceMode(enable: Boolean) {
        if (!isRootAvailable() || isPeakPerformanceActive == enable) return
        isPeakPerformanceActive = enable
        
        thread {
            if (enable) {
                AppLogger.log("GAMING ENGINE: Peak Performance Engaged")
                
                // 1. Force CPU Gold & Prime cores to max governor
                executeRootCommand("Governor -> Performance", "echo performance > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor; echo performance > /sys/devices/system/cpu/cpufreq/policy4/scaling_governor; echo performance > /sys/devices/system/cpu/cpufreq/policy7/scaling_governor")
                
                // 2. Qualcomm Adreno Boost: Turns off GPU idling and forces the hardware bus to stay aggressively active
                val gpuBoostCmd = "echo 1 > /sys/class/kgsl/kgsl-3d0/devfreq/adreno_boost 2>/dev/null; echo 0 > /sys/class/kgsl/kgsl-3d0/devfreq/adreno_idler_active 2>/dev/null; echo 1 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null"
                executeRootCommand("GPU -> Adreno Boost", gpuBoostCmd)
                
                // 3. Suspend Xiaomi Throttling Daemons
                val stopCmd = "for d in joyose mi_thermald thermal-engine; do p=\$(pidof \$d); if [ ! -z \"\$p\" ]; then kill -STOP \$p; fi; done"
                executeRootCommand("Freeze Thermal Daemons", stopCmd)
            } else {
                AppLogger.log("GAMING ENGINE: Normal Performance Restored")
                
                // 1. Restore CPU
                executeRootCommand("Governor -> Schedutil", "echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor; echo schedutil > /sys/devices/system/cpu/cpufreq/policy4/scaling_governor; echo schedutil > /sys/devices/system/cpu/cpufreq/policy7/scaling_governor")
                
                // 2. Restore GPU (Turns idling back on to save battery)
                val gpuRestoreCmd = "echo 0 > /sys/class/kgsl/kgsl-3d0/devfreq/adreno_boost 2>/dev/null; echo 1 > /sys/class/kgsl/kgsl-3d0/devfreq/adreno_idler_active 2>/dev/null; echo 0 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null"
                executeRootCommand("GPU -> Auto Mode", gpuRestoreCmd)

                // 3. Restore Xiaomi Throttling Daemons
                val resumeCmd = "for d in joyose mi_thermald thermal-engine; do p=\$(pidof \$d); if [ ! -z \"\$p\" ]; then kill -CONT \$p; fi; done"
                executeRootCommand("Resume Thermal Daemons", resumeCmd)
            }
        }
    }

    fun forceUnthrottlePrimeCore() {
        executeRootCommand("Force Prime Core Max", "cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq")
    }

    fun optimizeBackgroundForGaming(exemptPackages: Set<String>, foregroundPkg: String) {
        if (!isRootAvailable() || lastOptimizedGame == foregroundPkg) return
        lastOptimizedGame = foregroundPkg
        thread {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ps -A -o pid,NAME"))
                val reader = p.inputStream.bufferedReader()
                var line: String?
                val pidsToPin = mutableListOf<Int>()
                
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.trim().split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val pid = parts[0].toIntOrNull() ?: continue
                        val name = parts[1]
                        
                        if (name.contains(".") && !name.startsWith("android.") && !name.startsWith("com.android.") && !name.startsWith("com.miui.") && !name.startsWith("com.qualcomm.")) {
                            val isExempt = exemptPackages.any { name.contains(it) }
                            val isForeground = name.contains(foregroundPkg)
                            val isSelf = name.contains("tempstatusbar")
                            
                            if (!isExempt && !isForeground && !isSelf) pidsToPin.add(pid)
                        }
                    }
                }
                
                if (pidsToPin.isNotEmpty()) {
                    val pidList = pidsToPin.joinToString(" ")
                    executeRootCommand("Pin Background Apps to Silver Cores", "for p in $pidList; do taskset -p 0f \$p; done")
                }
            } catch (e: Exception) {}
        }
    }

    fun clearGamingOptimization() {
        lastOptimizedGame = ""
    }

    fun getHardwareInfo(): String {
        val soc = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE).uppercase(java.util.Locale.getDefault())
        return when {
            soc.contains("SM8250") -> "Snapdragon 870"
            soc.contains("SM8350") -> "Snapdragon 888"
            soc.contains("SM8450") -> "Snapdragon 8 Gen 1"
            soc.contains("SM8475") -> "Snapdragon 8+ Gen 1"
            soc.contains("SM8550") -> "Snapdragon 8 Gen 2"
            else -> soc
        }
    }

    fun getGpuUsage(): String {
        val paths = listOf("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load", "/sys/class/kgsl/kgsl-3d0/gpubusy")
        for (path in paths) {
            try {
                val f = File(path)
                if (f.exists()) {
                    val raw = f.readText().trim()
                    if (raw.contains(" ")) {
                        val parts = raw.split(" ")
                        if (parts.size == 2) {
                            val busy = parts[0].toFloatOrNull() ?: 0f
                            val total = parts[1].toFloatOrNull() ?: 1f
                            return "${((busy / total) * 100).toInt()}%"
                        }
                    }
                    return "${raw.replace("%", "")}%"
                }
            } catch (e: Exception) {}
        }
        return "--%"
    }

    fun getGpuFrequency(): String {
        if (!isRootAvailable()) return "-- MHz"
        try {
            val cmd = "cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null || cat /sys/kernel/gpu/gpu_clock 2>/dev/null"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val raw = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()
            p.waitFor()
            val hz = raw?.toLongOrNull()
            if (hz != null) {
                return if (hz > 1000000) "${hz / 1000000} MHz" else "$hz MHz"
            }
        } catch (e: Exception) {}
        return "-- MHz"
    }

    fun isRootAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (cachedRootState != null && (now - lastRootCheckTime < 60000L)) return cachedRootState!!
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val hasRoot = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.contains("uid=0") == true
            p.waitFor()
            cachedRootState = hasRoot; lastRootCheckTime = now
            hasRoot
        } catch (e: Exception) { false }
    }

    fun setChargingEnabled(enable: Boolean, isManualToggle: Boolean = false) {
        if (isManualToggle) isManualBypassActive = !enable
        val action = if (enable) "Restore Charge" else "Isolate Battery"
        val cmd = if (enable) {
            "echo 1 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null; echo 1 > /sys/class/power_supply/battery/battery_charging_enabled 2>/dev/null; echo 0 > /sys/class/power_supply/battery/input_suspend 2>/dev/null; echo 0 > /sys/class/qcom-battery/input_suspend 2>/dev/null"
        } else {
            "echo 0 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null; echo 0 > /sys/class/power_supply/battery/battery_charging_enabled 2>/dev/null; echo 1 > /sys/class/power_supply/battery/input_suspend 2>/dev/null; echo 1 > /sys/class/qcom-battery/input_suspend 2>/dev/null"
        }
        executeRootCommand(action, cmd)
        isChargingThrottled = !enable
    }

    fun forceEmergencyCooldown() { isEmergencyCooldownActive = true; setChargingEnabled(false) }
    fun clearEmergencyCooldown() { isEmergencyCooldownActive = false }

    fun setCoreOnline(coreId: Int, online: Boolean) {
        executeRootCommand(if (online) "Enable Core $coreId" else "Disable Core $coreId", "echo ${if (online) "1" else "0"} > /sys/devices/system/cpu/cpu$coreId/online")
    }

    fun throttlePrimeCore(throttle: Boolean) {
        if (isPrimeCoreThrottled == throttle) return
        isPrimeCoreThrottled = throttle
        val action = if (throttle) "Throttle Prime Core (C7)" else "Restore Prime Core (C7)"
        val cmd = if (throttle) "cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq"
                  else "cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq"
        executeRootCommand(action, cmd)
    }

    fun applySmartThermalGovernor(context: Context) {
        if (!isRootAvailable()) return
        throttlePrimeCore(true)
        val procs = getKernelProcessSnapshot(context)
        procs.forEach { p ->
            val cpuVal = p.cpu.toFloatOrNull() ?: 0f
            if (cpuVal > 5.0f && !p.name.contains(context.packageName) && !p.name.contains("Android System") && !p.name.contains("Display Compositor")) {
                pinProcessToEfficiencyCores(p.pid)
            }
        }
    }

    fun killProcess(pid: Int) { executeRootCommand("Kill Process PID: $pid", "kill -9 $pid") }
    fun pinProcessToEfficiencyCores(pid: Int) { executeRootCommand("Pin to Cores 0-3 PID: $pid", "taskset -p 0f $pid") }
    fun clearRamCaches() { executeRootCommand("Clear RAM Caches", "echo 3 > /proc/sys/vm/drop_caches") }

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
                    val pid = it.groupValues[3].toInt()
                    val friendly = AppLabelHelper.getAppName(appContext ?: return@let, it.groupValues[2], pid)
                    if (mb > 10) list.add(RamProc(friendly, mb, pid))
                }
            }
            p.waitFor()
        } catch (e: Exception) {}
        return list.sortedByDescending { it.sizeMb }
    }

    fun getKernelProcessSnapshot(context: Context): List<ProcessData> {
        val list = mutableListOf<ProcessData>()
        if (!isRootAvailable()) return list
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "top -b -n 1 -m 8"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var headerPassed = false; var count = 0
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
                            val friendlyName = AppLabelHelper.getAppName(context, rawName, pid)
                            list.add(ProcessData(pid, friendlyName, cpu))
                            count++; if (count >= 7) break
                        }
                    }
                }
            }
            p.waitFor()
        } catch (e: Exception) {}
        return list
    }

    fun destroy() { try { setPeakPerformanceMode(false); setChargingEnabled(true); toneGen?.release(); vibrator?.cancel() } catch (e: Exception) {} }
}
