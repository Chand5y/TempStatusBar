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
    
    var liveFps = -1
    
    private var isMuted = false
    private var cachedRootState: Boolean? = null
    private var lastRootCheckTime = 0L
    
    private var isPeakPerformanceActive = false
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
            if (action != "Deep Hardware Snapshot") {
                AppLogger.log("KERNEL ACTION: $action | EXIT: $exitCode")
            }
            return exitCode == 0
        } catch (e: Exception) { return false }
    }

    // PERFECTED: Single, bundled, zero-latency heartbeat that does not wait for an exit code or log anything.
    fun enforceNuclearHeartbeat() {
        if (!isRootAvailable()) return
        val cmd = "cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq 2>/dev/null; " +
                  "cat /sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_max_freq > /sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq 2>/dev/null; " +
                  "echo 0 > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null"
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        } catch (e: Exception) {}
    }

    fun getDeepHardwareSnapshot(): String {
        if (!isRootAvailable()) return "NO ROOT"
        try {
            val cmd = """
                gl=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null | awk '{print ${'$'}1}' || echo '--')
                gf=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null || echo '0')
                c0=${'$'}(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null || echo '0')
                c4=${'$'}(cat /sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq 2>/dev/null || echo '0')
                c7=${'$'}(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq 2>/dev/null || echo '0')
                echo "${'$'}gl|${'$'}gf|${'$'}c0|${'$'}c4|${'$'}c7"
            """.trimIndent()
            
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val raw = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim() ?: return "READ_ERROR"
            p.waitFor()
            
            val parts = raw.split("|")
            if (parts.size == 5) {
                val gLoad = parts[0].replace("%", "").trim()
                val gFreq = (parts[1].toLongOrNull() ?: 0L) / 1000000L
                val c0Freq = (parts[2].toLongOrNull() ?: 0L) / 1000L
                val c4Freq = (parts[3].toLongOrNull() ?: 0L) / 1000L
                val c7Freq = (parts[4].toLongOrNull() ?: 0L) / 1000L
                return "GPU: $gLoad% @ ${gFreq}MHz | C0: ${c0Freq}MHz | C4: ${c4Freq}MHz | C7: ${c7Freq}MHz"
            }
        } catch (e: Exception) {}
        return "DATA_PARSE_ERROR"
    }

    fun setPeakPerformanceMode(enable: Boolean) {
        if (!isRootAvailable() || isPeakPerformanceActive == enable) return
        isPeakPerformanceActive = enable
        
        thread {
            if (enable) {
                AppLogger.log("GAMING ENGINE: NUCLEAR PERFORMANCE ENGAGED")
                executeRootCommand("Disable Kernel Thermals", "for z in /sys/class/thermal/thermal_zone*/mode; do echo disabled > \$z 2>/dev/null; done")
                executeRootCommand("GPU -> Nuclear Power Level", "echo 0 > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null; echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null; echo 0 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null; echo 0 > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null")
                executeRootCommand("Governor -> Performance", "echo performance > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor; echo performance > /sys/devices/system/cpu/cpufreq/policy4/scaling_governor; echo performance > /sys/devices/system/cpu/cpufreq/policy7/scaling_governor")
                executeRootCommand("Freeze Thermal Daemons", "for d in joyose mi_thermald thermal-engine; do p=\$(pidof \$d); if [ ! -z \"\$p\" ]; then kill -STOP \$p; fi; done")
            } else {
                AppLogger.log("GAMING ENGINE: Normal Performance Restored")
                executeRootCommand("Enable Kernel Thermals", "for z in /sys/class/thermal/thermal_zone*/mode; do echo enabled > \$z 2>/dev/null; done")
                executeRootCommand("GPU -> Auto Mode", "echo 6 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null; echo 5 > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null")
                executeRootCommand("Governor -> Schedutil", "echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor; echo schedutil > /sys/devices/system/cpu/cpufreq/policy4/scaling_governor; echo schedutil > /sys/devices/system/cpu/cpufreq/policy7/scaling_governor")
                executeRootCommand("Restore CPU Min Freq", "cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq; cat /sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq; cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq")
                executeRootCommand("Resume Thermal Daemons", "for d in joyose mi_thermald thermal-engine; do p=\$(pidof \$d); if [ ! -z \"\$p\" ]; then kill -CONT \$p; fi; done")
            }
        }
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

    fun clearGamingOptimization() { lastOptimizedGame = "" }

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
            "echo 1 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null; echo 1 > /sys/class/power_supply/battery/battery_charging_enabled 2>/dev/null; echo 0 > /sys/class/power_supply/battery/input_suspend 2>/dev/null; echo 0 > /sys/class/power_supply/usb/input_suspend 2>/dev/null; echo 0 > /sys/class/power_supply/main/input_suspend 2>/dev/null; exit 0"
        } else {
            "echo 0 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null; echo 0 > /sys/class/power_supply/battery/battery_charging_enabled 2>/dev/null; echo 1 > /sys/class/power_supply/battery/input_suspend 2>/dev/null; echo 1 > /sys/class/power_supply/usb/input_suspend 2>/dev/null; echo 1 > /sys/class/power_supply/main/input_suspend 2>/dev/null; exit 0"
        }
        executeRootCommand(action, cmd)
        isChargingThrottled = !enable
    }

    fun forceEmergencyCooldown() { isEmergencyCooldownActive = true; setChargingEnabled(false) }
    fun clearEmergencyCooldown() { isEmergencyCooldownActive = false }

    fun playThermalAlert() {
        if (isMuted) return
        try { toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500) } catch (e: Exception) {}
        try { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0)) } catch (e: Exception) {}
    }
    fun muteAlarm() { isMuted = true; try { toneGen?.stopTone(); vibrator?.cancel() } catch (e: Exception) {} }
    fun resetMute() { isMuted = false }

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
