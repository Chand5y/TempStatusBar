package com.personal.tempstatusbar

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var settings: SettingsManager
    private lateinit var statusBadge: TextView
    private lateinit var tempValueText: TextView
    private lateinit var powerValueText: TextView
    private lateinit var gpuValueText: TextView
    private lateinit var ramValueText: TextView
    private lateinit var coresValueText: TextView
    private lateinit var throttleLimitBtn: TextView

    private lateinit var gamingSwitch: Switch
    private lateinit var overlaySwitch: Switch
    private lateinit var tempInPillSwitch: Switch
    private lateinit var bypassSwitch: Switch
    private lateinit var smartGovSwitch: Switch
    private lateinit var notifSwitch: Switch
    private lateinit var powerMetricSwitch: Switch

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isUiActive = false

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            if (!isUiActive) return
            try {
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val temp = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
                val plugged = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                val pwrStats = PowerHardwareHelper.readPowerStats(applicationContext, plugged)
                val ramStr = HardwareThermalControl.getRamUsage(applicationContext)

                thread {
                    val gpuLoad = HardwareThermalControl.getGpuUsage()
                    val gpuFreq = HardwareThermalControl.getGpuFrequency()
                    val cores = HardwareThermalControl.getCoreFrequencies()
                    val coresFormatted = if (cores.size >= 8) {
                        "Silver (C0-3): ${cores[0]}   •   Gold (C4-6): ${cores[4]}\nPrime (C7): ${cores[7]}"
                    } else {
                        cores.joinToString(" | ")
                    }

                    uiHandler.post {
                        if (!isUiActive) return@post
                        tempValueText.text = "${temp}°C"
                        tempValueText.setTextColor(
                            when {
                                temp >= 41 -> Color.parseColor("#FF5252")
                                temp >= 38 -> Color.parseColor("#FFB300")
                                else -> Color.parseColor("#00E676")
                            }
                        )
                        val sign = if (pwrStats.isCharging) "+" else "-"
                        powerValueText.text = "$sign${pwrStats.wattage}W (${abs(pwrStats.currentMa)} mA)"
                        gpuValueText.text = "$gpuLoad @ $gpuFreq"
                        ramValueText.text = ramStr.replace("\n", " ")
                        coresValueText.text = coresFormatted
                        updateServiceBadge()
                    }
                }
            } catch (e: Exception) {}
            uiHandler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager(this)
        HardwareThermalControl.init(this)

        AppLogger.log("=================================")
        AppLogger.log("APP LAUNCHED / TRACKER INITIALIZED")

        try {
            window.statusBarColor = Color.parseColor("#0A0A0C")
            window.navigationBarColor = Color.parseColor("#0A0A0C")
        } catch (e: Exception) {}

        val prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0C"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 56, 44, 80)
        }

        // ================= HEADER =================
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "THERMAL ENGINE"
            setTextColor(Color.WHITE)
            textSize = 21f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.06f
        })
        titleCol.addView(TextView(this).apply {
            text = "${HardwareThermalControl.getHardwareInfo()} • Nuclear Core"
            setTextColor(Color.parseColor("#8AFFFFFF"))
            textSize = 12f
            setPadding(0, 4, 0, 0)
        })

        statusBadge = TextView(this).apply {
            text = "ACTIVE"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(28, 12, 28, 12)
        }
        headerRow.addView(titleCol)
        headerRow.addView(statusBadge)
        root.addView(headerRow)

        addSpacer(root, 40)

        // ================= LIVE TELEMETRY CARD =================
        val telemetryCard = createGlassCard()
        val topMetricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val tempCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tempCol.addView(createSectionLabel("BATTERY TEMP"))
        tempValueText = TextView(this).apply {
            text = "--°C"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 34f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        tempCol.addView(tempValueText)

        val pwrCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pwrCol.addView(createSectionLabel("POWER DRAW"))
        powerValueText = TextView(this).apply {
            text = "-- W"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 20, 0, 0)
        }
        pwrCol.addView(powerValueText)

        topMetricsRow.addView(tempCol)
        topMetricsRow.addView(pwrCol)
        telemetryCard.addView(topMetricsRow)

        telemetryCard.addView(createDivider())
        telemetryCard.addView(createSectionLabel("ADRENO 650 GPU"))
        gpuValueText = createValueText("Polling...")
        telemetryCard.addView(gpuValueText)

        telemetryCard.addView(createDivider())
        telemetryCard.addView(createSectionLabel("KRYO 585 CPU CLUSTERS"))
        coresValueText = createValueText("Polling...")
        telemetryCard.addView(coresValueText)

        telemetryCard.addView(createDivider())
        telemetryCard.addView(createSectionLabel("SYSTEM MEMORY"))
        ramValueText = createValueText("Polling...")
        telemetryCard.addView(ramValueText)

        root.addView(telemetryCard)
        addSpacer(root, 36)

        // ================= NUCLEAR GAMING ENGINE CARD =================
        val gamingCard = createGlassCard()
        gamingCard.addView(createSectionLabel("NUCLEAR 120 FPS ENGINE"))

        gamingSwitch = createSwitchRow("Nuclear Gaming Mode", settings.gamingModeEnabled) { enabled ->
            savePrefBoolean(prefs, "gamingModeEnabled", enabled)
            if (!enabled) HardwareThermalControl.setPeakPerformanceMode(false)
        }
        gamingCard.addView(gamingSwitch)
        gamingCard.addView(createDivider())

        overlaySwitch = createSwitchRow("Floating FPS Pill Overlay", FpsOverlayService.isRunning) { enabled ->
            toggleFpsOverlay(enabled)
        }
        gamingCard.addView(overlaySwitch)
        gamingCard.addView(createDivider())

        tempInPillSwitch = createSwitchRow("Show Temp Inside FPS Pill", prefs.getBoolean("showTempInFpsOverlay", true)) { enabled ->
            prefs.edit().putBoolean("showTempInFpsOverlay", enabled).apply()
        }
        gamingCard.addView(tempInPillSwitch)
        gamingCard.addView(createDivider())

        val gameBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, 12, 0, 4)
        }
        val selectGamesBtn = createPillButton("Gaming Apps (${settings.gamingApps.size})") {
            showAppSelectorDialog("Select Gaming Apps", "gamingApps", settings.gamingApps, true) {
                recreate()
            }
        }
        selectGamesBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, 10, 0)
        }

        throttleLimitBtn = createPillButton("Throttle Cap: ${settings.gameThrottleTemp}°C") {
            showNumberPickerDialog("Game Thermal Ceiling (°C)", settings.gameThrottleTemp, 38, 46) { newVal ->
                savePrefInt(prefs, "gameThrottleTemp", newVal)
                throttleLimitBtn.text = "Throttle Cap: ${newVal}°C"
            }
        }
        throttleLimitBtn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(10, 0, 0, 0)
        }

        gameBtnRow.addView(selectGamesBtn)
        gameBtnRow.addView(throttleLimitBtn)
        gamingCard.addView(gameBtnRow)

        val exemptBtn = createPillButton("Exempt Background Apps (${settings.exemptApps.size})") {
            showAppSelectorDialog("Exempt Background Apps", "exemptApps", settings.exemptApps, false) {
                recreate()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 20, 0, 0)
            }
        }
        gamingCard.addView(exemptBtn)

        root.addView(gamingCard)
        addSpacer(root, 36)

        // ================= DISPLAY & HARDWARE ACTIONHUB =================
        val actionCard = createGlassCard()
        actionCard.addView(createSectionLabel("DISPLAY REFRESH & HARDWARE CONTROL"))

        val hzRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            setPadding(0, 18, 0, 10)
        }
        listOf(60, 90, 120).forEach { hz ->
            val btn = createPillButton("${hz}Hz") {
                HardwareThermalControl.forceRefreshRate(hz)
                Toast.makeText(this, "Display locked to ${hz}Hz", Toast.LENGTH_SHORT).show()
            }
            btn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 8, 0)
            }
            hzRow.addView(btn)
        }
        actionCard.addView(hzRow)

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, 14, 0, 0)
        }
        val purgeRamBtn = createPillButton("Purge RAM Cache") {
            HardwareThermalControl.clearRamCaches()
            Toast.makeText(this, "RAM caches dropped", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 10, 0)
            }
        }
        val procMgrBtn = createPillButton("Process Inspector") {
            showProcessInspectorDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(10, 0, 0, 0)
            }
        }
        toolsRow.addView(purgeRamBtn)
        toolsRow.addView(procMgrBtn)
        actionCard.addView(toolsRow)

        root.addView(actionCard)
        addSpacer(root, 36)

        // ================= BATTERY & THERMAL PROTECTION CARD =================
        val powerCard = createGlassCard()
        powerCard.addView(createSectionLabel("BATTERY BYPASS & PROTECTION"))

        bypassSwitch = createSwitchRow("Manual Battery Bypass (Pause Charge)", HardwareThermalControl.isManualBypassActive) { enabled ->
            HardwareThermalControl.setChargingEnabled(!enabled, isManualToggle = true)
        }
        powerCard.addView(bypassSwitch)
        powerCard.addView(createDivider())

        smartGovSwitch = createSwitchRow("Smart Governor (When Not Gaming)", false) { enabled ->
            sendBroadcast(Intent("ACTION_TOGGLE_SMART_GOVERNOR").apply {
                setPackage(packageName)
                putExtra("state", enabled)
            })
        }
        powerCard.addView(smartGovSwitch)
        powerCard.addView(createDivider())

        notifSwitch = createSwitchRow("Status Bar Live Notification", settings.showNotification) { enabled ->
            savePrefBoolean(prefs, "showNotification", enabled)
            startCoreServices()
        }
        powerCard.addView(notifSwitch)
        powerCard.addView(createDivider())

        powerMetricSwitch = createSwitchRow("Show Wattage in Notification", settings.showPowerMetrics) { enabled ->
            savePrefBoolean(prefs, "showPowerMetrics", enabled)
            startCoreServices()
        }
        powerCard.addView(powerMetricSwitch)

        root.addView(powerCard)
        addSpacer(root, 36)

        // ================= DEEP DIAGNOSTIC ACTION LOG CARD =================
        val logCard = createGlassCard()
        logCard.addView(createSectionLabel("DEEP DIAGNOSTIC LOGGER"))

        val viewLogBtn = createPillButton("View Live Action Log") {
            showActionLogDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 18, 0, 16)
            }
        }
        logCard.addView(viewLogBtn)

        val logActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        val exportLogBtn = createPillButton("Export ActionLog.txt") {
            exportActionLog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 10, 0)
            }
        }
        val clearLogBtn = createPillButton("Clear Logs") {
            clearActionLogs()
            Toast.makeText(this, "Action Logs Cleared", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(10, 0, 0, 0)
            }
        }
        logActionRow.addView(exportLogBtn)
        logActionRow.addView(clearLogBtn)
        logCard.addView(logActionRow)

        root.addView(logCard)
        addSpacer(root, 44)

        // ================= PRIMARY SYNC BUTTON =================
        val syncBtn = TextView(this).apply {
            text = "SYNC & RESTART ENGINE"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, 40, 0, 40)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#1E88E5"))
            }
            setOnClickListener {
                startCoreServices()
                Toast.makeText(this@MainActivity, "Engine Synced", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(syncBtn)

        scrollView.addView(root)
        setContentView(scrollView)

        ensureDefaultGameConfigured(prefs)
        startCoreServices()
    }

    private fun ensureDefaultGameConfigured(prefs: android.content.SharedPreferences) {
        if (settings.gamingApps.isEmpty()) {
            val defaultSet = setOf("com.pubg.imobile", "com.tencent.ig")
            savePrefStringSet(prefs, "gamingApps", defaultSet)
        }
        if (settings.gameThrottleTemp < 42) {
            savePrefInt(prefs, "gameThrottleTemp", 43)
            throttleLimitBtn.text = "Throttle Cap: 43°C"
        }
    }

    // ================= DIALOGS & HELPERS =================

    private fun showActionLogDialog() {
        val logText = readActionLogs()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0D0F14"))
            setPadding(36, 36, 36, 36)
        }
        val tv = TextView(this).apply {
            text = if (logText.isNotBlank()) logText else "No logs recorded yet."
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Deep Diagnostic Action Log")
            .setView(scroll)
            .setPositiveButton("Export .txt") { _, _ -> exportActionLog() }
            .setNeutralButton("Clear") { _, _ -> clearActionLogs() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun exportActionLog() {
        try {
            val content = readActionLogs()
            val fileName = "ActionLog_${System.currentTimeMillis()}.txt"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val outFile = File(downloadsDir, fileName)
            outFile.writeText(content)
            Toast.makeText(this, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readActionLogs(): String {
        try {
            val methods = listOf("getLogs", "getLogText", "readLogs", "getAllLogs", "toString")
            for (mName in methods) {
                val m = AppLogger.javaClass.methods.firstOrNull { it.name == mName && it.parameterCount == 0 }
                if (m != null) {
                    val res = m.invoke(AppLogger)
                    if (res is String && res.isNotEmpty() && !res.startsWith("com.personal")) return res
                    if (res is List<*>) return res.joinToString("\n")
                }
            }
            for (field in AppLogger.javaClass.declaredFields) {
                field.isAccessible = true
                val v = field.get(AppLogger)
                if (v is List<*>) return v.joinToString("\n")
                if (v is StringBuilder) return v.toString()
                if (v is File && v.exists()) return v.readText()
            }
            filesDir.listFiles()?.firstOrNull { it.name.contains("log", true) }?.let { return it.readText() }
        } catch (e: Exception) {}
        return ""
    }

    private fun clearActionLogs() {
        try {
            val clearMethod = AppLogger.javaClass.methods.firstOrNull {
                (it.name.contains("clear", true) || it.name.contains("reset", true)) && it.parameterCount == 0
            }
            clearMethod?.invoke(AppLogger)
            for (field in AppLogger.javaClass.declaredFields) {
                field.isAccessible = true
                val v = field.get(AppLogger)
                if (v is MutableList<*>) v.clear()
                if (v is StringBuilder) v.clear()
                if (v is File && v.exists()) v.writeText("")
            }
        } catch (e: Exception) {}
        AppLogger.log("LOGS CLEARED BY USER")
    }

    private fun showProcessInspectorDialog() {
        Toast.makeText(this, "Scanning kernel processes...", Toast.LENGTH_SHORT).show()
        thread {
            val procs = HardwareThermalControl.getKernelProcessSnapshot(applicationContext)
            val ramProcs = HardwareThermalControl.getDetailedRam().take(8)
            uiHandler.post {
                val items = mutableListOf<String>()
                val pids = mutableListOf<Int>()
                procs.forEach {
                    items.add("CPU: ${it.name} (${it.cpu}%) [PID ${it.pid}]")
                    pids.add(it.pid)
                }
                ramProcs.forEach {
                    items.add("RAM: ${it.name} (${it.sizeMb} MB) [PID ${it.pid}]")
                    pids.add(it.pid)
                }
                if (items.isEmpty()) {
                    Toast.makeText(this, "No active high-load processes found", Toast.LENGTH_SHORT).show()
                    return@post
                }
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Tap Process to Pin to Silver Cores")
                    .setItems(items.toTypedArray()) { _, which ->
                        val pid = pids[which]
                        HardwareThermalControl.pinProcessToEfficiencyCores(pid)
                        Toast.makeText(this, "Pinned PID $pid to Silver Cores", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun showAppSelectorDialog(
        title: String,
        prefKey: String,
        currentSet: Set<String>,
        includeBgmiPreset: Boolean,
        onSaved: () -> Unit
    ) {
        thread {
            val pm = packageManager
            val installed = pm.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("pubg") || it.packageName.contains("tencent") }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }

            val labels = installed.map { "${it.loadLabel(pm)} (${it.packageName})" }.toTypedArray()
            val pkgs = installed.map { it.packageName }
            val checked = BooleanArray(pkgs.size) { currentSet.contains(pkgs[it]) }
            val selected = currentSet.toMutableSet()

            uiHandler.post {
                val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        if (isChecked) selected.add(pkgs[which]) else selected.remove(pkgs[which])
                    }
                    .setPositiveButton("Save") { _, _ ->
                        val prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)
                        savePrefStringSet(prefs, prefKey, selected)
                        onSaved()
                    }
                    .setNegativeButton("Cancel", null)

                if (includeBgmiPreset) {
                    builder.setNeutralButton("+ Add BGMI") { _, _ ->
                        selected.add("com.pubg.imobile")
                        val prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)
                        savePrefStringSet(prefs, prefKey, selected)
                        onSaved()
                    }
                }
                builder.show()
            }
        }
    }

    private fun showNumberPickerDialog(title: String, current: Int, min: Int, max: Int, onResult: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setTextColor(Color.WHITE)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("$title ($min - $max)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().toIntOrNull()?.coerceIn(min, max) ?: current
                onResult(v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePrefBoolean(prefs: android.content.SharedPreferences, key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        try {
            val setter = settings.javaClass.methods.firstOrNull {
                it.name.equals("set${key.replaceFirstChar { c -> c.uppercase() }}", true) && it.parameterCount == 1
            }
            setter?.invoke(settings, value)
        } catch (e: Exception) {}
    }

    private fun savePrefInt(prefs: android.content.SharedPreferences, key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        try {
            val setter = settings.javaClass.methods.firstOrNull {
                it.name.equals("set${key.replaceFirstChar { c -> c.uppercase() }}", true) && it.parameterCount == 1
            }
            setter?.invoke(settings, value)
        } catch (e: Exception) {}
    }

    private fun savePrefStringSet(prefs: android.content.SharedPreferences, key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
        try {
            val setter = settings.javaClass.methods.firstOrNull {
                it.name.equals("set${key.replaceFirstChar { c -> c.uppercase() }}", true) && it.parameterCount == 1
            }
            setter?.invoke(settings, value)
        } catch (e: Exception) {}
    }

    // ================= UI COMPONENT BUILDERS =================

    private fun createGlassCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 38, 44, 38)
            background = GradientDrawable().apply {
                cornerRadius = 36f
                setColor(Color.parseColor("#14161C"))
                setStroke(2, Color.parseColor("#24FFFFFF"))
            }
        }
    }

    private fun createSectionLabel(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.parseColor("#78FFFFFF"))
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        }
    }

    private fun createValueText(initial: String): TextView {
        return TextView(this).apply {
            text = initial
            setTextColor(Color.parseColor("#F0FFFFFF"))
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 8, 0, 2)
        }
    }

    private fun createSwitchRow(title: String, initialValue: Boolean, onToggle: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isChecked = initialValue
            setPadding(0, 24, 0, 24)
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
    }

    private fun createPillButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12.5f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(24, 26, 24, 26)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#1F232D"))
                setStroke(2, Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 20, 0, 20)
            }
            setBackgroundColor(Color.parseColor("#18FFFFFF"))
        }
    }

    private fun addSpacer(parent: LinearLayout, heightPx: Int) {
        parent.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, heightPx)
        })
    }

    private fun toggleFpsOverlay(enable: Boolean) {
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Grant Overlay Permission first", Toast.LENGTH_LONG).show()
            overlaySwitch.isChecked = false
            return
        }
        if (enable) {
            startService(Intent(this, FpsOverlayService::class.java))
        } else {
            stopService(Intent(this, FpsOverlayService::class.java))
        }
    }

    private fun startCoreServices() {
        try {
            val intent = Intent(this, TempMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            updateServiceBadge()
        } catch (e: Exception) {}
    }

    private fun updateServiceBadge() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == TempMonitorService::class.java.name
        }
        statusBadge.text = if (running) "ACTIVE" else "STOPPED"
        statusBadge.background = GradientDrawable().apply {
            cornerRadius = 100f
            setColor(if (running) Color.parseColor("#2600E676") else Color.parseColor("#26FF5252"))
            setStroke(2, if (running) Color.parseColor("#8000E676") else Color.parseColor("#80FF5252"))
        }
    }

    override fun onResume() {
        super.onResume()
        isUiActive = true
        overlaySwitch.isChecked = FpsOverlayService.isRunning
        bypassSwitch.isChecked = HardwareThermalControl.isManualBypassActive
        uiHandler.post(telemetryRunnable)
    }

    override fun onPause() {
        super.onPause()
        isUiActive = false
        uiHandler.removeCallbacks(telemetryRunnable)
    }
}
