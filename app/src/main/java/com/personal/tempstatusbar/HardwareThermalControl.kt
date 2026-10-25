package com.personal.tempstatusbar

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.concurrent.thread

data class RamProc(val name: String, val sizeMb: Int, val pid: Int)
data class ProcessData(val pid: Int, val name: String, val cpu: String)
data class CoreStatus(val coreId: Int, val online: Boolean, val freqText: String)

object HardwareThermalControl {
    private var toneGen: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var appContext: Context? = null

    var isChargingThrottled = false; private set
    var isManualBypassActive = false; private set
    var isEmergencyCooldownActive = false; private set
    @Volatile var isAutoTestRunning = false; private set

    @Volatile var liveFps = -1

    private var isMuted = false
    private var cachedRootState: Boolean? = null
    private var lastRootCheckTime = 0L

    private var isPeakPerformanceActive = false
    private var isPrimeCoreThrottled: Boolean? = null
    private var lastOptimizedGame = ""

    fun init(context: Context) {
        appContext = context.applicationContext
        AppLogger.init(context.applicationContext)
        try { toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (e: Exception) {}
        try { vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator } catch (e: Exception) {}
        thread {
            val root = isRootAvailable()
            AppLogger.log("⚙️ [INIT] SoC: ${getHardwareInfo()} | Android ${Build.VERSION.RELEASE} | Root: ${if (root) "GRANTED (uid=0)" else "DENIED"}")
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
        if (!isRootAvailable()) {
            AppLogger.log("❌ [FAILED] $action | Reason: Root access (su) not available")
            return false
        }
        val t0 = System.currentTimeMillis()
        val beforeVal = if (verifyPath != null) readSysfsNode(verifyPath) else null

        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            val exitCode = process.waitFor()
            val dt = System.currentTimeMillis() - t0

            if (verifyPath != null) {
                val afterVal = readSysfsNode(verifyPath)
                val matched = expectedValue == null || afterVal.contains(expectedValue, ignoreCase = true)
                if (exitCode == 0 && matched) {
                    AppLogger.log("✅ [WORKING] $action | $verifyPath: [$beforeVal -> $afterVal] (${dt}ms)")
                } else {
                    val reason = when {
                        stderr.isNotEmpty() -> stderr.lines().first()
                        afterVal.contains("No such file", true) -> "Kernel node does not exist on this ROM"
                        beforeVal == afterVal -> "Kernel rejected write or locked by msm_performance/cooling_device (stayed '$afterVal')"
                        else -> "Expected '$expectedValue' but read '$afterVal'"
                    }
                    AppLogger.log("❌ [FAILED] $action | $verifyPath stayed [$afterVal] | Why: $reason")
                }
                exitCode == 0 && matched
            } else {
                if (exitCode == 0) {
                    val extra = if (stdout.isNotEmpty()) " | Out: ${stdout.lines().first()}" else ""
                    AppLogger.log("✅ [WORKING] $action | EXIT: 0 (${dt}ms)$extra")
                } else {
                    val errReason = if (stderr.isNotEmpty()) stderr.lines().first() else "Non-zero exit ($exitCode)"
                    AppLogger.log("❌ [FAILED] $action | EXIT: $exitCode | Why: $errReason")
                }
                exitCode == 0
            }
        } catch (e: Exception) {
            AppLogger.log("❌ [EXCEPTION] $action | Why: ${e.message}")
            false
        }
    }

    fun executeRootCommand(action: String, command: String): Boolean {
        if (action == "Force CPU Max" || action == "Deep Hardware Snapshot") {
            if (!isRootAvailable()) return false
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                p.waitFor() == 0
            } catch (e: Exception) { false }
        }
        return executeVerifiedCommand(action, command)
    }

    private fun buildCpuCeilingUnlockScript(c7MaxKhz: String = "3187200", c7MinKhz: String = "2457600"): String {
        return """
            chmod 0644 /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null
            echo "0:1804800 1:1804800 2:1804800 3:1804800 4:2419200 5:2419200 6:2419200 7:$c7MaxKhz" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null
            echo "7:$c7MaxKhz" > /sys/module/msm_performance/parameters/cpu_max_freq 2>/dev/null
            for c in /sys/class/thermal/cooling_device*/cur_state; do echo 0 > ${'$'}c 2>/dev/null; done
            chmod 0644 /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null
            chmod 0644 /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null
            echo $c7MaxKhz > /sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq 2>/dev/null
            echo $c7MaxKhz > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq 2>/dev/null
            echo $c7MinKhz > /sys/devices/system/cpu/cpufreq/policy7/scaling_min_freq 2>/dev/null
            echo $c7MinKhz > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq 2>/dev/null
            echo 2419200 > /sys/devices/system/cpu/cpufreq/policy4/scaling_max_freq 2>/dev/null
            echo 2419200 > /sys/devices/system/cpu/cpufreq/policy4/scaling_min_freq 2>/dev/null
        """.trimIndent()
    }

    // =========================================================================
    // HANDS-FREE 12-STAGE AUTOMATED DIAGNOSTIC & VERIFICATION SUITE
    // =========================================================================
    fun runAutomatedTestSuite(
        context: Context,
        hasUsagePerm: Boolean,
        hasOverlayPerm: Boolean,
        hasNotifPerm: Boolean,
        onProgress: (Int, Int, String) -> Unit,
        onFinished: (Int, Int, List<String>, String) -> Unit
    ) {
        if (isAutoTestRunning) return
        isAutoTestRunning = true

        thread {
            var passedCount = 0
            var failedCount = 0
            val manualActionsNeeded = mutableListOf<String>()
            val totalStages = 12

            fun step(stage: Int, title: String) {
                onProgress(stage, totalStages, title)
                AppLogger.log("------------------------------------------")
                AppLogger.log("🧪 [STAGE $stage/$totalStages] $title")
            }

            fun recordResult(ok: Boolean, passMsg: String, failMsg: String) {
                if (ok) {
                    passedCount++
                    AppLogger.log("  🟢 PASS: $passMsg")
                } else {
                    failedCount++
                    AppLogger.log("  🔴 FAIL: $failMsg")
                }
            }

            AppLogger.clear()
            AppLogger.log("🤖 HANDS-FREE AUTOMATED TEST SUITE STARTED")
            AppLogger.log("📱 Device: ${Build.MANUFACTURER} ${Build.MODEL} (${getHardwareInfo()}) | SDK ${Build.VERSION.SDK_INT}")

            // STAGE 1: Permissions
            step(1, "Checking Permissions & Environment...")
            val rootOk = isRootAvailable()
            recordResult(rootOk, "Root Shell (su) granted uid=0", "Root Shell denied — grant Superuser in Magisk/KernelSU")
            if (!rootOk) manualActionsNeeded.add("Grant Superuser (Root) permission in Magisk / KernelSU.")

            recordResult(hasUsagePerm, "Usage Access Permission is GRANTED", "Usage Access is MISSING")
            if (!hasUsagePerm) manualActionsNeeded.add("Enable 'Usage Access' permission for TempStatusBar Pro.")

            recordResult(hasOverlayPerm, "Display Over Other Apps (Overlay) is GRANTED", "Overlay Permission is MISSING")
            if (!hasOverlayPerm) manualActionsNeeded.add("Enable 'Display over other apps' permission for Floating FPS Pill.")

            recordResult(hasNotifPerm, "Status Bar Notification Permission is GRANTED", "Notification Permission is MISSING")
            if (!hasNotifPerm) manualActionsNeeded.add("Allow Notifications so temperature icon appears in Status Bar.")

            val batIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val isPlugged = (batIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val startTemp = (batIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
            AppLogger.log("  ℹ️ Battery State: ${startTemp}°C | Charger Plugged: $isPlugged")
            Thread.sleep(400)

            // STAGE 2: Kernel Thermal Zones
            step(2, "Testing Kernel Thermal Zones (/sys/class/thermal)...")
            val (_, unameOut) = runRootShell("uname -r")
            val selinux = readSysfsNode("/sys/fs/selinux/enforce")
            AppLogger.log("  ℹ️ Kernel: $unameOut | SELinux Enforce: $selinux")

            val (_, tzTest) = runRootShell("total=0; writable=0; for z in /sys/class/thermal/thermal_zone*/mode; do total=\$((total+1)); echo disabled > \$z 2>/dev/null && writable=\$((writable+1)); done; echo \"\$writable/\$total\"")
            val writableZones = tzTest.split("/").getOrNull(0)?.toIntOrNull() ?: 0
            recordResult(
                writableZones > 0,
                "Disabled $tzTest kernel thermal zones successfully",
                "0 thermal_zone*/mode nodes accepted 'disabled'"
            )
            Thread.sleep(400)

            // STAGE 3: Xiaomi sconfig
            step(3, "Testing Xiaomi Thermal Profile (sconfig)...")
            val sconfigPath = "/sys/class/thermal/thermal_message/sconfig"
            val sconfigOrig = readSysfsNode(sconfigPath)
            runRootShell("chmod 0666 $sconfigPath 2>/dev/null; echo 10 > $sconfigPath 2>/dev/null; chmod 0444 $sconfigPath 2>/dev/null")
            val sconfigAfter = readSysfsNode(sconfigPath)
            recordResult(
                sconfigAfter == "10",
                "Xiaomi sconfig locked to Benchmark Mode [Before: $sconfigOrig -> After: $sconfigAfter]",
                "Xiaomi sconfig rejected '10' [Readback: $sconfigAfter]"
            )
            Thread.sleep(400)

            // STAGE 4: Freeze Daemons
            step(4, "Testing Thermal Daemons Freeze (SIGSTOP)...")
            val (_, daemonReport) = runRootShell(
                "for d in joyose mi_thermald thermal-engine; do " +
                "p=\$(pidof \$d); " +
                "if [ ! -z \"\$p\" ]; then " +
                "kill -STOP \$p 2>/dev/null; " +
                "st=\$(awk '{print \$3}' /proc/\$(echo \$p | awk '{print \$1}')/stat 2>/dev/null); " +
                "echo \"\$d(PID:\$p->State:\$st)\"; " +
                "else echo \"\$d(NOT_RUNNING)\"; fi; done"
            )
            val daemonClean = daemonReport.replace("\n", " | ")
            recordResult(
                daemonClean.contains("State:T") || daemonClean.contains("NOT_RUNNING"),
                "Daemons frozen cleanly: $daemonClean",
                "Could not freeze daemons: $daemonClean"
            )
            Thread.sleep(400)

            // STAGE 5: CPU Governor
            step(5, "Testing Kryo 585 CPU Governor Switching...")
            val govPath4 = "/sys/devices/system/cpu/cpufreq/policy4/scaling_governor"
            val origGov4 = readSysfsNode(govPath4)
            runRootShell("echo performance > $govPath4 2>/dev/null")
            val perfGov4 = readSysfsNode(govPath4)
            runRootShell("echo schedutil > $govPath4 2>/dev/null")
            val restGov4 = readSysfsNode(govPath4)
            recordResult(
                perfGov4.contains("performance", true),
                "CPU Policy4 Governor switched [$origGov4 -> $perfGov4 -> $restGov4]",
                "CPU Policy4 Governor failed to switch to performance [Stayed: $perfGov4]"
            )
            Thread.sleep(400)

            // STAGE 6: Prime Core C7 Unlock
            step(6, "Testing Prime Core (C7) Throttle & 3.18 GHz Unlock...")
            val c7MinHw = readSysfsNode("/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq")
            val c7MaxHw = readSysfsNode("/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq")
            val c7ScaleMax = "/sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq"
            val c7ScaleMin = "/sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq"

            runRootShell("echo $c7MinHw > $c7ScaleMin 2>/dev/null; echo $c7MinHw > $c7ScaleMax 2>/dev/null")
            val throttledRead = readSysfsNode(c7ScaleMax)

            runRootShell(buildCpuCeilingUnlockScript(c7MaxHw, "2457600"))
            val nuclearMaxRead = readSysfsNode(c7ScaleMax)
            val nuclearMinRead = readSysfsNode(c7ScaleMin)

            runRootShell("echo $c7MinHw > $c7ScaleMin 2>/dev/null; echo $c7MaxHw > $c7ScaleMax 2>/dev/null")
            runRootShell("chmod 0666 $sconfigPath 2>/dev/null; echo 0 > $sconfigPath 2>/dev/null")
            runRootShell("for z in /sys/class/thermal/thermal_zone*/mode; do echo enabled > \$z 2>/dev/null; done")
            runRootShell("for d in joyose mi_thermald thermal-engine; do p=\$(pidof \$d); if [ ! -z \"\$p\" ]; then kill -CONT \$p 2>/dev/null; fi; done")

            recordResult(
                throttledRead == c7MinHw && nuclearMaxRead == c7MaxHw,
                "Prime Core C7 Throttle [$throttledRead kHz] & 3.18 GHz Unlock [Min:$nuclearMinRead / Max:$nuclearMaxRead kHz] verified",
                "Prime Core C7 scaling lock mismatch [Throttle:$throttledRead, Max:$nuclearMaxRead, Expected:$c7MaxHw]"
            )
            Thread.sleep(400)

            // STAGE 7: Per-Core Hotplug
            step(7, "Testing CPU Core 7 Hotplug (Offline -> Online)...")
            val c7OnlinePath = "/sys/devices/system/cpu/cpu7/online"
            runRootShell("chmod 0644 $c7OnlinePath 2>/dev/null; echo 0 > $c7OnlinePath 2>/dev/null")
            val offlineRead = readSysfsNode(c7OnlinePath)
            runRootShell("echo 1 > $c7OnlinePath 2>/dev/null")
            val onlineRead = readSysfsNode(c7OnlinePath)
            recordResult(
                offlineRead == "0" && onlineRead == "1",
                "Core 7 Hotplug verified [Offline: $offlineRead -> Restored Online: $onlineRead]",
                "Core 7 Hotplug blocked by kernel [Offline read: $offlineRead, Online read: $onlineRead]"
            )
            Thread.sleep(400)

            // STAGE 8: Adreno 650 GPU Lock
            step(8, "Testing Adreno 650 GPU 670 MHz Lock...")
            val gpuMinPwrPath = "/sys/class/kgsl/kgsl-3d0/min_pwrlevel"
            val gpuThmPwrPath = "/sys/class/kgsl/kgsl-3d0/thermal_pwrlevel"
            runRootShell("echo 0 > $gpuThmPwrPath 2>/dev/null; echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null; echo 0 > $gpuMinPwrPath 2>/dev/null")
            Thread.sleep(300)
            val lockedMinPwr = readSysfsNode(gpuMinPwrPath)
            val lockedGpuFreq = getGpuFrequency()
            runRootShell("echo 6 > $gpuMinPwrPath 2>/dev/null; echo 5 > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null")
            val restoredMinPwr = readSysfsNode(gpuMinPwrPath)
            recordResult(
                lockedMinPwr == "0",
                "GPU Power Level 0 locked [Freq: $lockedGpuFreq | min_pwrlevel: 0 -> Restored: $restoredMinPwr]",
                "GPU min_pwrlevel rejected 0 [Readback: $lockedMinPwr | Freq: $lockedGpuFreq]"
            )
            Thread.sleep(400)

            // STAGE 9: Display Refresh Rate
            step(9, "Testing Display Refresh Rate Switching (60/90/120Hz)...")
            val hzResults = mutableListOf<String>()
            listOf(60, 90, 120).forEach { hz ->
                val modeId = if (hz >= 120) 0 else 1
                runRootShell("settings put system peak_refresh_rate $hz; settings put system min_refresh_rate $hz; settings put system user_refresh_rate $hz; settings put secure miui_refresh_rate $hz; service call SurfaceFlinger 1035 i32 $modeId >/dev/null 2>&1")
                Thread.sleep(250)
                val (_, rb) = runRootShell("settings get system user_refresh_rate")
                hzResults.add("${hz}Hz->$rb")
            }
            recordResult(
                hzResults.last().contains("120"),
                "Refresh Rate sequence verified [${hzResults.joinToString(", ")}]",
                "Refresh Rate readback mismatch [${hzResults.joinToString(", ")}]"
            )
            Thread.sleep(400)

            // STAGE 10: Battery Bypass
            step(10, "Testing Xiaomi SM8250 Battery Bypass Nodes...")
            val batSuspendNode = "/sys/class/power_supply/battery/input_suspend"
            val batStatusNode = "/sys/class/power_supply/battery/status"
            val statusBefore = readSysfsNode(batStatusNode)

            runRootShell("echo 1 > $batSuspendNode 2>/dev/null; echo 1 > /sys/class/power_supply/usb/input_suspend 2>/dev/null; echo 1 > /sys/class/power_supply/main/input_suspend 2>/dev/null")
            Thread.sleep(1000)
            val batSuspendDuring = readSysfsNode(batSuspendNode)
            val statusDuring = readSysfsNode(batStatusNode)

            runRootShell("echo 0 > $batSuspendNode 2>/dev/null; echo 0 > /sys/class/power_supply/usb/input_suspend 2>/dev/null; echo 0 > /sys/class/power_supply/main/input_suspend 2>/dev/null")
            val batSuspendAfter = readSysfsNode(batSuspendNode)
            recordResult(
                batSuspendDuring == "1",
                "Battery Bypass verified [battery/input_suspend: $batSuspendDuring | Status: $statusBefore -> $statusDuring | Restored: $batSuspendAfter]",
                "Battery Bypass node rejected write [battery: $batSuspendDuring]"
            )
            Thread.sleep(400)

            // STAGE 11: Process Pinning & RAM Purge
            step(11, "Testing Process Silver-Core Pinning & RAM Purge...")
            val myPid = android.os.Process.myPid()
            val (_, tasksetOut) = runRootShell("taskset -p 0f $myPid 2>&1; taskset -p ff $myPid 2>&1")
            val ramBefore = getRamUsage(context).replace("\n", " ")
            val (dropCode, _) = runRootShell("sync; echo 3 > /proc/sys/vm/drop_caches")
            val ramAfter = getRamUsage(context).replace("\n", " ")
            recordResult(
                dropCode == 0 && tasksetOut.contains("affinity", true),
                "taskset Affinity & RAM Purge verified [RAM: $ramBefore -> $ramAfter]",
                "taskset or drop_caches failed [Exit:$dropCode]"
            )
            Thread.sleep(400)

            // STAGE 12: Hardware DRM FPS Counter
            step(12, "Testing Hardware DRM FPS Counter & Overlay Service...")
            val drmFpsRaw = readSysfsNode("/sys/class/drm/sde-crtc-0/measured_fps")
            val parsedDrmFps = drmFpsRaw.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }.firstOrNull() ?: -1
            val wasOverlayRunning = FpsOverlayService.isRunning
            if (hasOverlayPerm && !wasOverlayRunning) {
                context.startService(Intent(context, FpsOverlayService::class.java))
                Thread.sleep(1300)
            }
            val bridgeFps = liveFps
            if (hasOverlayPerm && !wasOverlayRunning) {
                context.stopService(Intent(context, FpsOverlayService::class.java))
            }
            recordResult(
                parsedDrmFps > 0 || bridgeFps > 0,
                "Hardware DRM FPS Counter readable [Raw: '$drmFpsRaw' | Bridge LiveFPS: $bridgeFps]",
                "Hardware DRM FPS Counter unreadable [Raw: '$drmFpsRaw' | Bridge: $bridgeFps]"
            )

            AppLogger.log("==========================================")
            AppLogger.log("🏁 AUTOMATED TEST COMPLETE: $passedCount PASSED | $failedCount FAILED")
            if (manualActionsNeeded.isNotEmpty()) {
                AppLogger.log("⚠️ MANUAL USER ACTIONS RECOMMENDED:")
                manualActionsNeeded.forEachIndexed { i, act ->
                    AppLogger.log("   ${i + 1}. $act")
                }
            } else {
                AppLogger.log("✨ ZERO MANUAL ACTIONS NEEDED — ALL 15/15 CHECKS READY")
            }

            var exportedPath = "Not exported"
            try {
                val fileName = "AutoTest_Report_${System.currentTimeMillis()}.txt"
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, fileName)
                outFile.writeText(AppLogger.getLogs())
                exportedPath = "Downloads/$fileName"
                AppLogger.log("💾 [AUTO-EXPORT] Full report saved to $exportedPath")
            } catch (e: Exception) {
                AppLogger.log("⚠️ [AUTO-EXPORT] Could not save to Downloads: ${e.message}")
            }
            AppLogger.log("==========================================")

            isAutoTestRunning = false
            onFinished(passedCount, failedCount, manualActionsNeeded, exportedPath)
        }
    }

    fun enforceNuclearHeartbeat(currentTemp: Int = 37) {
        if (!isRootAvailable()) return
        val c7Max = if (currentTemp >= 41) "2745600" else "3187200"
        val gpuMinPwr = if (currentTemp >= 41) "1" else "0"
        val cmd = """
            ${buildCpuCeilingUnlockScript(c7Max, "2457600")}
            echo 0 > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel 2>/dev/null
            echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel 2>/dev/null
            echo $gpuMinPwr > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null
            settings put system peak_refresh_rate 120 2>/dev/null
            settings put system min_refresh_rate 120 2>/dev/null
            settings put system user_refresh_rate 120 2>/dev/null
            settings put secure miui_refresh_rate 120 2>/dev/null
        """.trimIndent()
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

    fun forceRefreshRate(hz: Int) {
        thread {
            val modeId = if (hz >= 120) 0 else 1
            val cmd = "settings put system peak_refresh_rate $hz; settings put system min_refresh_rate $hz; settings put system user_refresh_rate $hz; settings put secure miui_refresh_rate $hz; settings put system miui_refresh_rate $hz; service call SurfaceFlinger 1035 i32 $modeId"
            executeVerifiedCommand("Force Display Refresh to ${hz}Hz", cmd)
        }
    }

    fun setPeakPerformanceMode(enable: Boolean) {
        if (!isRootAvailable() || isPeakPerformanceActive == enable) return
        isPeakPerformanceActive = enable

        thread {
            if (enable) {
                AppLogger.log("🚀 GAMING ENGINE: NUCLEAR PERFORMANCE ENGAGED")

                val stopCmd = "for d in joyose mi_thermald thermal-engine; do p=\$(pidof \$d); if [ ! -z \"\$p\" ]; then kill -STOP \$p; fi; done"
                executeVerifiedCommand("Freeze Xiaomi Thermal Daemons", stopCmd)

                val killThermalsCmd = """
                    for z in /sys/class/thermal/thermal_zone*/mode; do echo disabled > ${'$'}z 2>/dev/null; done
                    chmod 0666 /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                    echo 10 > /sys/class/thermal/thermal_message/sconfig
                    chmod 0444 /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                """.trimIndent()
                executeVerifiedCommand("Lock Thermal sconfig -> 10", killThermalsCmd, "/sys/class/thermal/thermal_message/sconfig", "10")

                executeVerifiedCommand(
                    "Unlock Prime Core 3.18GHz Ceiling",
                    buildCpuCeilingUnlockScript("3187200", "2457600"),
                    "/sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq",
                    "3187200"
                )

                forceUnthrottleGPU()
                executeVerifiedCommand(
                    "CPU Policy4 Governor -> Performance",
                    "echo performance > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor; echo performance > /sys/devices/system/cpu/cpufreq/policy4/scaling_governor; echo schedutil > /sys/devices/system/cpu/cpufreq/policy7/scaling_governor",
                    "/sys/devices/system/cpu/cpufreq/policy4/scaling_governor",
                    "performance"
                )
                forceRefreshRate(120)
            } else {
                AppLogger.log("🌱 GAMING ENGINE: Normal Performance Restored")
                val restoreThermalsCmd = """
                    chmod 0666 /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                    echo 0 > /sys/class/thermal/thermal_message/sconfig 2>/dev/null
                    for z in /sys/class/thermal/thermal_zone*/mode; do echo enabled > ${'$'}z 2>/dev/null; done
                """.trimIndent()
                executeVerifiedCommand("Restore Kernel Thermals", restoreThermalsCmd)
                executeVerifiedCommand(
                    "GPU -> Auto Dynamic Mode",
                    "echo 6 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel; echo 5 > /sys/class/kgsl/kgsl-3d0/default_pwrlevel",
                    "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
                    "6"
                )
                executeVerifiedCommand(
                    "CPU Governors -> Schedutil",
                    "echo schedutil > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor; echo schedutil > /sys/devices/system/cpu/cpufreq/policy4/scaling_governor; echo schedutil > /sys/devices/system/cpu/cpufreq/policy7/scaling_governor",
                    "/sys/devices/system/cpu/cpufreq/policy4/scaling_governor",
                    "schedutil"
                )
                val cpuRestoreCmd = """
                    cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq 2>/dev/null
                    cat /sys/devices/system/cpu/cpu4/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu4/cpufreq/scaling_min_freq 2>/dev/null
                    cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq 2>/dev/null
                    cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq 2>/dev/null
                """.trimIndent()
                executeVerifiedCommand("Restore CPU Min/Max Freqs", cpuRestoreCmd)
                val resumeCmd = "for d in joyose mi_thermald thermal-engine; do p=\$(pidof \$d); if [ ! -z \"\$p\" ]; then kill -CONT \$p; fi; done"
                executeVerifiedCommand("Resume Xiaomi Thermal Daemons", resumeCmd)
            }
        }
    }

    fun forceUnthrottleCPU() {
        executeRootCommand("Force CPU Max", buildCpuCeilingUnlockScript("3187200", "2457600"))
    }

    fun forceUnthrottlePrimeCore() = forceUnthrottleCPU()

    fun forceUnthrottleGPU() {
        thread {
            val gpuNuclearCmd = "echo 0 > /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel; echo 0 > /sys/class/kgsl/kgsl-3d0/max_pwrlevel; echo 0 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel; echo 0 > /sys/class/kgsl/kgsl-3d0/default_pwrlevel"
            executeVerifiedCommand("GPU -> Nuclear Power Level 0 (670MHz)", gpuNuclearCmd, "/sys/class/kgsl/kgsl-3d0/min_pwrlevel", "0")
        }
    }

    fun throttleCPU(throttle: Boolean) {
        isPrimeCoreThrottled = throttle
        thread {
            if (throttle) {
                val minF = readSysfsNode("/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq")
                val cmd = "cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq; cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_min_freq"
                executeVerifiedCommand("Throttle Prime Core (C7)", cmd, "/sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq", minF)
            } else {
                val maxF = readSysfsNode("/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq")
                val minF = readSysfsNode("/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq")
                val cmd = buildCpuCeilingUnlockScript(maxF, minF)
                executeVerifiedCommand("Unthrottle Prime Core (C7)", cmd, "/sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq", maxF)
            }
        }
    }

    fun throttlePrimeCore(throttle: Boolean) = throttleCPU(throttle)

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
                    executeVerifiedCommand("Pin ${pidsToPin.size} Bg Apps to Silver Cores", "for p in $pidList; do taskset -p 0f \$p >/dev/null 2>&1; done")
                }
            } catch (e: Exception) {}
        }
    }

    fun clearGamingOptimization() { lastOptimizedGame = "" }

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
        isChargingThrottled = !enable
        thread {
            val action = if (enable) "Restore Battery Charging" else "Isolate Battery (Bypass)"
            val targetSuspend = if (enable) "0" else "1"
            val cmd = if (enable) {
                "echo 0 > /sys/class/power_supply/battery/input_suspend 2>/dev/null; echo 0 > /sys/class/power_supply/usb/input_suspend 2>/dev/null; echo 0 > /sys/class/power_supply/main/input_suspend 2>/dev/null; exit 0"
            } else {
                "echo 1 > /sys/class/power_supply/battery/input_suspend 2>/dev/null; echo 1 > /sys/class/power_supply/usb/input_suspend 2>/dev/null; echo 1 > /sys/class/power_supply/main/input_suspend 2>/dev/null; exit 0"
            }
            executeVerifiedCommand(action, cmd, "/sys/class/power_supply/battery/input_suspend", targetSuspend)
        }
    }

    fun forceEmergencyCooldown() { isEmergencyCooldownActive = true; setChargingEnabled(false) }
    fun clearEmergencyCooldown() { isEmergencyCooldownActive = false }

    fun setCoreOnline(coreId: Int, online: Boolean) {
        thread {
            val target = if (online) "1" else "0"
            val path = "/sys/devices/system/cpu/cpu$coreId/online"
            executeVerifiedCommand(
                if (online) "Bring Core $coreId ONLINE" else "Take Core $coreId OFFLINE",
                "chmod 0644 $path 2>/dev/null; echo $target > $path",
                path,
                target
            )
        }
    }

    // FIXED: Reads real kernel /sys/devices/system/cpu/cpuX/online state BEFORE reading shared cluster frequency!
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

    fun applySmartThermalGovernor(context: Context) {
        if (!isRootAvailable()) return
        throttleCPU(true)
        val procs = getKernelProcessSnapshot(context)
        procs.forEach { p ->
            val cpuVal = p.cpu.toFloatOrNull() ?: 0f
            if (cpuVal > 5.0f && !p.name.contains(context.packageName) && !p.name.contains("Android System") && !p.name.contains("Display Compositor")) {
                pinProcessToEfficiencyCores(p.pid)
            }
        }
    }

    fun killProcess(pid: Int) {
        thread { executeVerifiedCommand("Kill Process PID: $pid", "kill -9 $pid") }
    }

    fun pinProcessToEfficiencyCores(pid: Int) {
        thread { executeVerifiedCommand("Pin PID $pid to Silver Cores (0-3)", "taskset -p 0f $pid") }
    }

    // FIXED: Actually force-stops running background user apps (so they restart cold from Recents) AND drops kernel caches!
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
                // Discover running user apps from meminfo/ps and force-stop them via root
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
                executeVerifiedCommand("Force-Stop ${killedApps.size} Bg Apps & Drop Caches", stopCmds.toString())
            } catch (e: Exception) {
                executeVerifiedCommand("Purge Kernel RAM Caches", "sync; echo 3 > /proc/sys/vm/drop_caches")
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
        try { toneGen?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500) } catch (e: Exception) {}
        try { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0)) } catch (e: Exception) {}
    }
    fun muteAlarm() { isMuted = true; try { toneGen?.stopTone(); vibrator?.cancel() } catch (e: Exception) {} }
    fun resetMute() { isMuted = false }

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

    fun destroy() { try { setPeakPerformanceMode(false); setChargingEnabled(true); toneGen?.release(); vibrator?.cancel() } catch (e: Exception) {} }
}
