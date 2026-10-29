package com.personal.tempstatusbar

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread
import kotlin.math.abs

data class RamProc(val name: String, val sizeMb: Int, val pid: Int)
data class ProcessData(val pid: Int, val name: String, val cpu: String)
data class CoreStatus(val coreId: Int, val online: Boolean, val freqText: String)
data class AppEntry(val label: String, val packageName: String, val isSelected: Boolean = false)

object HardwareThermalControl {
    private var toneGen: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var appContext: Context? = null

    var isChargingThrottled = false; private set
    var isManualBypassActive = false; private set
    var isEmergencyCooldownActive = false; private set
    @Volatile var isGpuPeakLocked = false; private set

    @Volatile var liveFps = -1

    private var isMuted = false
    private var cachedRootState: Boolean? = null
    private var lastRootCheckTime = 0L

    @Volatile var isPeakPerformanceActive = false; private set

    fun init(context: Context) {
        appContext = context.applicationContext
        AppLogger.init(context.applicationContext)
        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (e: Exception) {}
        try { vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator } catch (e: Exception) {}
        thread {
            val root = isRootAvailable()
            if (root) {
                // Ensure 100% stock hardware state on startup
                restoreStockHardwareState()
                autoAddQuickSettingsTiles(context.applicationContext)
            }
            AppLogger.log("⚙️ [INIT] SoC: ${getHardwareInfo()} | Android ${Build.VERSION.RELEASE} | Root: ${if (root) "GRANTED (Pure Monitor Mode)" else "DENIED"}")
        }
    }

    fun restoreStockHardwareState() {
        isPeakPerformanceActive = false
        isGpuPeakLocked = false
        val cmd = """
            echo 0 > /proc/sys/kernel/sched_boost 2>/dev/null
            chmod 0666 /sys/class/thermal/thermal_message/sconfig 2>/dev/null
            echo 0 > /sys/class/thermal/thermal_message/sconfig 2>/dev/null
            for z in /sys/class/thermal/thermal_zone*/mode; do echo enabled > ${'$'}z 2>/dev/null; done
            cmd power set-fixed-performance-mode-enabled false 2>/dev/null
            chmod 0644 /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null
            echo "0:1804800 1:1804800 2:1804800 3:1804800 4:2419200 5:2419200 6:2419200 7:3187200" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null
            for p in /sys/devices/system/cpu/cpufreq/policy0 /sys/devices/system/cpu/cpufreq/policy4 /sys/devices/system/cpu/cpufreq/policy7 /sys/devices/system/cpu/cpu0/cpufreq /sys/devices/system/cpu/cpu4/cpufreq /sys/devices/system/cpu/cpu7/cpufreq; do
                chmod 0644 ${'$'}p/scaling_governor ${'$'}p/scaling_max_freq ${'$'}p/scaling_min_freq 2>/dev/null
                echo schedutil > ${'$'}p/scaling_governor 2>/dev/null
            done
            cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq 2>/dev/null
            cat /sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq 2>/dev/null
            cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq 2>/dev/null
            cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq 2>/dev/null
            chmod 0644 /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel /sys/class/kgsl/kgsl-3d0/max_pwrlevel /sys/class/kgsl/kgsl-3d0/min_pwrlevel /sys/class/kgsl/kgsl-3d0/default_pwrlevel /sys/class/kgsl/kgsl-3d0/force_clk_on /sys/class/kgsl/kgsl-3d0/force_bus_on /sys/class/kgsl/kgsl-3d0/force_rail_on /sys/class/kgsl/kgsl-3d0/bus_split /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null
            echo 6 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null
            echo 5 > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null
            echo 0 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null
            echo 0 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null
            echo 0 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null
            echo 1 > /sys/class/kgsl/kgsl-3d0/bus_split 2>/dev/null
            echo 305000000 > /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null
            for b in /sys/class/devfreq/*cpu-cpu-llbw* /sys/class/devfreq/*llccbw*; do
                if [ -d "${'$'}b" ]; then
                    chmod 0644 ${'$'}b/governor ${'$'}b/min_freq 2>/dev/null
                    echo bw_hwmon > ${'$'}b/governor 2>/dev/null
                fi
            done
            for b in /sys/class/devfreq/*kgsl-busmon*; do
                if [ -d "${'$'}b" ]; then
                    chmod 0644 ${'$'}b/governor ${'$'}b/min_freq 2>/dev/null
                    echo gpubw_mon > ${'$'}b/governor 2>/dev/null
                fi
            done
            for d in joyose mi_thermald thermal-engine com.miui.powerkeeper; do
                p=${'$'}(pidof ${'$'}d)
                if [ ! -z "${'$'}p" ]; then kill -CONT ${'$'}p 2>/dev/null; fi
            done
        """.trimIndent()
        runRootShell(cmd)
    }

    fun syncGpuLockState(): Boolean {
        isGpuPeakLocked = false
        return false
    }

    fun getForegroundPackageRoot(): String {
        if (!isRootAvailable()) return ""
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|topResumedActivity'"))
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            p.waitFor()
            if (out.contains("NotificationShade", ignoreCase = true) || out.contains("StatusBar", ignoreCase = true)) {
                return "TRANSIENT_SYSTEM_SHADE"
            }
            val regex = Regex("""([a-zA-Z0-9_]+(\.[a-zA-Z0-9_]+)+)/""")
            val matches = regex.findAll(out).map { it.groupValues[1] }.toList()
            matches.firstOrNull {
                !it.contains("systemui") &&
                !it.contains("tempstatusbar") &&
                !it.contains("permissioncontroller") &&
                !it.contains("miui.securitycenter")
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun isAnyGameProcessRunning(gamePackages: Set<String>): Boolean = false

    fun loadQuickTileIcon(context: Context, exactFileName: String): Icon? {
        try {
            val candidates = listOf(exactFileName, "Icons/$exactFileName", "icons/$exactFileName")
            var raw: Bitmap? = null
            for (path in candidates) {
                try {
                    context.assets.open(path).use { stream ->
                        raw = BitmapFactory.decodeStream(stream)
                    }
                    if (raw != null) break
                } catch (e: Exception) {}
            }
            if (raw == null) {
                val allAssets = context.assets.list("") ?: emptyArray()
                val matched = allAssets.firstOrNull { it.equals(exactFileName, ignoreCase = true) }
                if (matched != null) {
                    context.assets.open(matched).use { stream ->
                        raw = BitmapFactory.decodeStream(stream)
                    }
                }
            }

            val src = raw ?: return null
            val scaled = Bitmap.createScaledBitmap(src, 96, 96, true)
            val w = scaled.width
            val h = scaled.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)

            val c0 = pixels[0]
            val bgR = Color.red(c0)
            val bgG = Color.green(c0)
            val bgB = Color.blue(c0)

            val isBg = BooleanArray(w * h)
            val visited = BooleanArray(w * h)
            val queue = IntArray(w * h)
            var head = 0
            var tail = 0

            fun matchesCorner(px: Int): Boolean {
                if (Color.alpha(px) < 40) return true
                val dr = abs(Color.red(px) - bgR)
                val dg = abs(Color.green(px) - bgG)
                val db = abs(Color.blue(px) - bgB)
                return dr <= 55 && dg <= 55 && db <= 55
            }

            fun enqueue(x: Int, y: Int) {
                val idx = y * w + x
                if (!visited[idx] && matchesCorner(pixels[idx])) {
                    visited[idx] = true
                    isBg[idx] = true
                    queue[tail++] = idx
                }
            }

            for (x in 0 until w) {
                enqueue(x, 0)
                enqueue(x, h - 1)
            }
            for (y in 0 until h) {
                enqueue(0, y)
                enqueue(w - 1, y)
            }

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                if (x > 0) enqueue(x - 1, y)
                if (x + 1 < w) enqueue(x + 1, y)
                if (y > 0) enqueue(x, y - 1)
                if (y + 1 < h) enqueue(x, y + 1)
            }

            var nonBgCount = 0
            var lumSum = 0L
            for (i in pixels.indices) {
                if (!isBg[i] && Color.alpha(pixels[i]) >= 40) {
                    val px = pixels[i]
                    val lum = (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000
                    lumSum += lum
                    nonBgCount++
                }
            }

            val avgLum = if (nonBgCount > 0) (lumSum / nonBgCount).toInt() else 128
            val fillRatio = nonBgCount.toFloat() / (w * h).toFloat()

            for (i in pixels.indices) {
                if (isBg[i] || Color.alpha(pixels[i]) < 40) {
                    pixels[i] = Color.TRANSPARENT
                } else {
                    val px = pixels[i]
                    val lum = (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000

                    if (fillRatio > 0.48f) {
                        val delta = lum - avgLum
                        if (delta <= 18) {
                            pixels[i] = Color.TRANSPARENT
                        } else {
                            val alpha = (delta * 3.2f).toInt().coerceIn(150, 255)
                            pixels[i] = Color.argb(alpha, 255, 255, 255)
                        }
                    } else {
                        val alpha = Color.alpha(px).coerceIn(180, 255)
                        pixels[i] = Color.argb(alpha, 255, 255, 255)
                    }
                }
            }

            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return Icon.createWithBitmap(out)
        } catch (e: Exception) {
            return null
        }
    }

    fun getInstalledUserApps(context: Context, selectedSet: Set<String> = emptySet()): List<AppEntry> {
        val pm = context.packageManager
        val map = linkedMapOf<String, String>()

        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolves = pm.queryIntentActivities(mainIntent, 0)
            for (ri in resolves) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().takeIf { !it.isNullOrBlank() } ?: pkg
                map[pkg] = label
            }
        } catch (e: Exception) {}

        try {
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (ai in apps) {
                val pkg = ai.packageName
                if (pkg == context.packageName) continue
                if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    if (!map.containsKey(pkg)) {
                        val label = pm.getApplicationLabel(ai)?.toString()?.trim().takeIf { !it.isNullOrBlank() } ?: pkg
                        map[pkg] = label
                    }
                }
            }
        } catch (e: Exception) {}

        return map.entries
            .map { AppEntry(it.value, it.key, selectedSet.contains(it.key)) }
            .sortedWith(compareByDescending<AppEntry> { it.isSelected }.thenBy { it.label.lowercase() })
    }

    fun autoAddQuickSettingsTiles(context: Context) {
        thread {
            if (!isRootAvailable()) return@thread
            try {
                val pm = context.packageManager
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
                }
                val tileServices = pkgInfo.services?.filter {
                    it.permission == "android.permission.BIND_QUICK_SETTINGS_TILE"
                } ?: emptyList()

                if (tileServices.isEmpty()) return@thread

                val (_, currentTilesRaw) = runRootShell("settings get secure sysui_qs_tiles")
                val currentTiles = if (currentTilesRaw == "null" || currentTilesRaw.isBlank()) {
                    "wifi,bt,cell,flashlight,rotation,battery"
                } else {
                    currentTilesRaw.trim()
                }

                val existingList = currentTiles.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                var modified = false

                for (svc in tileServices) {
                    val shortName = if (svc.name.startsWith(context.packageName)) {
                        svc.name.removePrefix(context.packageName)
                    } else svc.name
                    val customEntry = "custom(${context.packageName}/$shortName)"
                    val fullEntry = "custom(${context.packageName}/${svc.name})"

                    val alreadyAdded = existingList.any {
                        it.equals(customEntry, ignoreCase = true) ||
                        it.equals(fullEntry, ignoreCase = true) ||
                        it.contains(svc.name.substringAfterLast("."))
                    }
                    if (!alreadyAdded) {
                        existingList.add(0, customEntry)
                        modified = true
                    }
                }

                if (modified) {
                    val newTilesString = existingList.joinToString(",")
                    runRootShell("settings put secure sysui_qs_tiles \"$newTilesString\"")
                }
            } catch (e: Exception) {}
        }
    }

    fun readSysfsNode(path: String): String {
        try {
            val f = File(path)
            if (f.exists() && f.canRead()) {
                return f.readText().trim().lines().firstOrNull() ?: "EMPTY"
            }
            if (!isRootAvailable()) return "NO_ROOT"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path 2>&1"))
            val out = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim() ?: "EMPTY"
            p.waitFor()
            return out
        } catch (e: Exception) {
            return "ERR:${e.message}"
        }
    }

    fun runRootShell(cmd: String): Pair<Int, String> {
        if (!isRootAvailable()) return -1 to "NO_ROOT"
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "$cmd 2>&1"))
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText().trim()
            val code = p.waitFor()
            code to out
        } catch (e: Exception) {
            -1 to "EXCEPTION: ${e.message}"
        }
    }

    fun executeVerifiedCommand(
        action: String,
        command: String,
        verifyPath: String? = null,
        expectedValue: String? = null
    ): Boolean {
        if (!isRootAvailable()) return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    fun executeRootCommand(action: String, command: String): Boolean {
        if (!isRootAvailable()) return false
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            p.waitFor() == 0
        } catch (e: Exception) { false }
    }

    // DISABLED: All performance/clock manipulation functions are neutralized for pure stock monitoring
    fun setGpuPeakLock(lockTo670Mhz: Boolean, onVerified: ((Boolean, String) -> Unit)? = null) {
        isGpuPeakLocked = false
        onVerified?.invoke(false, getGpuFrequency())
    }

    fun forceUnthrottleGPU() {}
    fun enforceNuclearHeartbeat(currentTemp: Int = 37) {}
    fun getDeepHardwareSnapshot(): String = "Stock Monitoring Mode"
    fun forceRefreshRate(hz: Int) {}
    fun setPeakPerformanceMode(enable: Boolean) {
        isPeakPerformanceActive = false
    }
    fun forceUnthrottleCPU() {}
    fun forceUnthrottlePrimeCore() {}
    fun throttleCPU(throttle: Boolean) {}
    fun throttlePrimeCore(throttle: Boolean) {}
    fun optimizeBackgroundForGaming(exemptPackages: Set<String>, foregroundPkg: String) {}
    fun clearGamingOptimization() {}

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
        if (isRootAvailable()) {
            val raw = readSysfsNode("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            val num = Regex("""\d+""").find(raw)?.value
            if (num != null) return "$num%"
        }
        val paths = listOf("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load", "/sys/class/kgsl/kgsl-3d0/gpubusy")
        for (path in paths) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val raw = f.readText().trim()
                    if (raw.contains(" ")) {
                        val parts = raw.split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val busy = parts[0].toFloatOrNull() ?: 0f
                            val total = parts[1].toFloatOrNull() ?: 1f
                            if (total > 0f) return "${((busy / total) * 100).toInt()}%"
                        }
                    }
                    val num = Regex("""\d+""").find(raw)?.value
                    if (num != null) {
                        val pct = num.toIntOrNull() ?: 0
                        if (pct <= 100) return "$pct%"
                    }
                }
            } catch (e: Exception) {}
        }
        return "0%"
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
        if (cachedRootState != null && (now - lastRootCheckTime < 15000L)) return cachedRootState!!
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
        isChargingThrottled = !enable
        thread {
            if (enable) {
                val cmd = """
                    echo 0 > /sys/class/power_supply/battery/input_suspend 2>/dev/null
                    echo 0 > /sys/class/qcom-battery/input_suspend 2>/dev/null
                    echo 0 > /sys/class/power_supply/usb/input_suspend 2>/dev/null
                    echo 0 > /sys/class/power_supply/main/input_suspend 2>/dev/null
                    echo 1 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null
                    echo 1 > /sys/class/power_supply/battery/battery_charging_enabled 2>/dev/null
                    chmod 0644 /sys/class/power_supply/battery/constant_charge_current 2>/dev/null
                    echo 6000000 > /sys/class/power_supply/battery/constant_charge_current 2>/dev/null
                    exit 0
                """.trimIndent()
                executeVerifiedCommand("Restore Normal Charging", cmd)
            } else {
                val cmd = """
                    chmod 0644 /sys/class/power_supply/battery/constant_charge_current 2>/dev/null
                    echo 0 > /sys/class/power_supply/battery/constant_charge_current 2>/dev/null
                    echo 0 > /sys/class/power_supply/battery/charging_enabled 2>/dev/null
                    echo 0 > /sys/class/power_supply/battery/battery_charging_enabled 2>/dev/null
                    echo 1 > /sys/class/power_supply/battery/input_suspend 2>/dev/null
                    exit 0
                """.trimIndent()
                executeVerifiedCommand("Pause Battery Charging", cmd)
            }
        }
    }

    fun forceEmergencyCooldown() { isEmergencyCooldownActive = true; setChargingEnabled(false) }
    fun clearEmergencyCooldown() { isEmergencyCooldownActive = false }

    fun setCoreOnline(coreId: Int, online: Boolean) {
        thread {
            val target = if (online) "1" else "0"
            val path = "/sys/devices/system/cpu/cpu$coreId/online"
            executeVerifiedCommand("Core Online", "chmod 0644 $path 2>/dev/null; echo $target > $path")
        }
    }

    fun getCoreStatuses(): List<CoreStatus> {
        val results = mutableListOf<CoreStatus>()
        if (isRootAvailable()) {
            try {
                val cmd = "for i in 0 1 2 3 4 5 6 7; do " +
                          "on=\$(cat /sys/devices/system/cpu/cpu\$i/online 2>/dev/null || echo 1); " +
                          "fr=\$(cat /sys/devices/system/cpu/cpu\$i/cpufreq/scaling_cur_freq 2>/dev/null || echo 0); " +
                          "echo \"\$i:\$on:\$fr\"; done"
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val lines = BufferedReader(InputStreamReader(p.inputStream)).readLines()
                p.waitFor()
                for (line in lines) {
                    val parts = line.trim().split(":")
                    if (parts.size == 3) {
                        val id = parts[0].toIntOrNull() ?: continue
                        val isOnline = parts[1].trim() != "0"
                        val freqKhz = parts[2].trim().toIntOrNull() ?: 0
                        val freqText = if (!isOnline) "OFFLINE (0 MHz)" else "${freqKhz / 1000} MHz"
                        results.add(CoreStatus(id, isOnline, freqText))
                    }
                }
                if (results.size == 8) return results
            } catch (e: Exception) {}
        }

        for (i in 0..7) {
            try {
                val onlineFile = File("/sys/devices/system/cpu/cpu$i/online")
                val isOnline = if (i == 0) true else if (onlineFile.exists() && onlineFile.canRead()) onlineFile.readText().trim() != "0" else true
                if (!isOnline) {
                    results.add(CoreStatus(i, false, "OFFLINE (0 MHz)"))
                } else {
                    val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                    val mhz = if (f.exists()) "${f.readText().trim().toInt() / 1000} MHz" else "Offline"
                    results.add(CoreStatus(i, true, mhz))
                }
            } catch (e: Exception) {
                results.add(CoreStatus(i, false, "OFFLINE"))
            }
        }
        return results
    }

    fun getCoreFrequencies(): List<String> {
        return getCoreStatuses().map { it.freqText }
    }

    fun applySmartThermalGovernor(context: Context) {}

    fun killProcess(pid: Int) {
        thread { executeVerifiedCommand("Kill Process", "kill -9 $pid") }
    }

    fun pinProcessToEfficiencyCores(pid: Int) {
        thread { executeVerifiedCommand("Pin Process", "taskset -p 0f $pid") }
    }

    fun clearRamCaches(exemptPackages: Set<String> = emptySet(), onDone: ((Int, Long) -> Unit)? = null) {
        thread {
            val ctx = appContext ?: return@thread
            val miBefore = ActivityManager.MemoryInfo()
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getMemoryInfo(miBefore)
            val availBeforeMb = miBefore.availMem / 1048576L

            val killedApps = mutableListOf<String>()
            try {
                val pm = ctx.packageManager
                val (_, psOut) = runRootShell("ps -A -o NAME")
                val runningPkgs = psOut.lines()
                    .map { it.trim() }
                    .filter { it.contains(".") && !it.startsWith("com.android.") && !it.startsWith("android.") && !it.startsWith("com.miui.") && !it.startsWith("com.qualcomm.") && !it.startsWith("com.xiaomi.") && !it.contains("tempstatusbar") && !it.contains("inputmethod") && !it.contains("gboard") && !it.contains("launcher") && !it.contains("home") }
                    .distinct()

                val stopCmds = StringBuilder()
                for (pkg in runningPkgs) {
                    if (exemptPackages.any { pkg.contains(it) }) continue
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                            stopCmds.append("am force-stop $pkg >/dev/null 2>&1; ")
                            killedApps.add(pm.getApplicationLabel(ai).toString())
                        }
                    } catch (e: Exception) {}
                }
                stopCmds.append("am kill-all >/dev/null 2>&1; sync; echo 3 > /proc/sys/vm/drop_caches")
                executeVerifiedCommand("Force-Stop & Drop Caches", stopCmds.toString())
            } catch (e: Exception) {
                executeVerifiedCommand("Purge Kernel RAM", "sync; echo 3 > /proc/sys/vm/drop_caches")
            }

            Thread.sleep(350)
            val miAfter = ActivityManager.MemoryInfo()
            am.getMemoryInfo(miAfter)
            val availAfterMb = miAfter.availMem / 1048576L
            val freedMb = (availAfterMb - availBeforeMb).coerceAtLeast(0L)

            val appsListStr = if (killedApps.isNotEmpty()) killedApps.take(8).joinToString(", ") else "Kernel Caches Only"
            AppLogger.log("🧹 [RAM & APP PURGE] Stopped ${killedApps.size} apps ($appsListStr) | Freed: +${freedMb}MB")
            onDone?.invoke(killedApps.size, freedMb)
        }
    }

    fun playThermalAlert() {
        if (isMuted) return
        try { toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1200) } catch (e: Exception) {}
        try { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 250, 400), -1)) } catch (e: Exception) {}
    }

    fun stopThermalAlert() {
        try { toneGen?.stopTone() } catch (e: Exception) {}
        try { vibrator?.cancel() } catch (e: Exception) {}
    }

    fun muteAlarm() {
        isMuted = true
        stopThermalAlert()
    }

    fun resetMute() {
        isMuted = false
        stopThermalAlert()
    }

    fun getRamUsage(context: Context): String {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val tMb = mi.totalMem / 1048576L
        val uMb = tMb - (mi.availMem / 1048576L)
        return "Used: ${uMb}MB / Total: ${tMb}MB\n(${((uMb.toDouble() / tMb) * 100).toInt()}% Load)"
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

    fun destroy() {
        try {
            stopThermalAlert()
            setChargingEnabled(true)
            toneGen?.release()
            vibrator?.cancel()
        } catch (e: Exception) {}
    }
}
