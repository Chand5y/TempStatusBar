package com.personal.tempstatusbar

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var settings: SettingsManager
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var prefs: android.content.SharedPreferences

    // Theme Engine State
    data class ThemePalette(
        val name: String,
        val bgTop: Int,
        val bgBottom: Int,
        val cardBg: Int,
        val cardStroke: Int,
        val pillBg: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val accentPrimary: Int,
        val accentSecondary: Int,
        val good: Int,
        val warn: Int,
        val danger: Int,
        val isLight: Boolean
    )

    private lateinit var theme: ThemePalette
    private var currentTabIndex = 0
    private val tabViews = mutableListOf<View>()
    private val tabBtns = mutableListOf<TextView>()

    // Access & Permission Chips
    private lateinit var rootChip: TextView
    private lateinit var usageChip: TextView
    private lateinit var notifChip: TextView
    private lateinit var overlayChip: TextView

    // Tab 1: Monitor & Thermals Views
    private lateinit var heroTempText: TextView
    private lateinit var heroStateText: TextView
    private lateinit var heroWattText: TextView
    private lateinit var tempGraphView: InteractiveGraphView
    private lateinit var tempScrubReadout: TextView
    private lateinit var tempSnapshotBox: TextView
    private var thermalHistoryList = listOf<ThermalPoint>()

    // Tab 2: Hardware Control Views
    private lateinit var hwGpuText: TextView
    private lateinit var hwRamText: TextView
    private val coreFreqLabels = mutableListOf<TextView>()
    private val coreToggleSwitches = mutableListOf<Switch>()

    // Tab 3: Gaming & FPS Views
    private lateinit var fpsGraphView: InteractiveGraphView
    private lateinit var fpsSessionHeader: TextView
    private lateinit var fpsScrubReadout: TextView
    private var fpsSessionsList = listOf<FpsSessionData>()
    private var currentFpsSessionIdx = 0

    // Tab 4: Process & Logs Views
    private lateinit var processContainer: LinearLayout
    private lateinit var logConsoleText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isUiActive = false

    data class ThermalPoint(val temp: Int, val chargeType: String, val details: String, val timestamp: String)
    data class FpsSessionData(val app: String, val duration: Int, val min: Int, val max: Int, val avg: Int, val avgTemp: Int, val fpsList: List<Float>, val tempList: List<Float>)

    private val livePoller = object : Runnable {
        override fun run() {
            if (!isUiActive) return
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
            val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val pwr = PowerHardwareHelper.readPowerStats(applicationContext, plugged)
            val ram = HardwareThermalControl.getRamUsage(applicationContext)

            thread {
                val hasRoot = HardwareThermalControl.isRootAvailable()
                val gpuLoad = HardwareThermalControl.getGpuUsage()
                val gpuFreq = HardwareThermalControl.getGpuFrequency()
                val cores = HardwareThermalControl.getCoreFrequencies()

                uiHandler.post {
                    if (!isUiActive) return@post
                    updateAccessChips(hasRoot)

                    heroTempText.text = "${temp}°C"
                    heroTempText.setTextColor(
                        when {
                            temp >= settings.warningTemp -> theme.danger
                            temp >= settings.warningTemp - 3 -> theme.warn
                            else -> theme.good
                        }
                    )
                    val modeStr = if (HardwareThermalControl.isManualBypassActive) "🛡️ BYPASS ACTIVE"
                    else if (pwr.isCharging) "⚡ CHARGING ($level%)" else "🔋 DISCHARGING ($level%)"
                    heroStateText.text = modeStr
                    val sign = if (pwr.isCharging) "+" else "-"
                    heroWattText.text = "$sign${pwr.wattage}W  •  ${abs(pwr.currentMa)} mA"

                    hwGpuText.text = "Load: $gpuLoad  •  Clock: $gpuFreq"
                    hwRamText.text = ram.replace("\n", "  •  ")

                    for (i in 0 until minOf(cores.size, coreFreqLabels.size)) {
                        coreFreqLabels[i].text = "Core $i: ${cores[i]}"
                    }
                }
            }
            uiHandler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager(this)
        dbHelper = DatabaseHelper(this)
        prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)
        HardwareThermalControl.init(this)

        // Ensure default BGMI package & safe 43°C game throttle cap
        if (settings.gamingApps.isEmpty()) {
            settings.gamingApps = setOf("com.pubg.imobile", "com.tencent.ig")
        }
        if (settings.gameThrottleTemp < 42) {
            settings.gameThrottleTemp = 43
        }

        // Request Android 13+ Notification Permission so Status Bar Temp works immediately
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Trigger root handshake in background on launch so Magisk/KernelSU prompts immediately
        thread { HardwareThermalControl.isRootAvailable() }

        theme = resolveTheme(prefs.getString("appThemeMode", "SIGNATURE") ?: "SIGNATURE")
        buildFullInterface()
        startAndSyncService()
    }

    // =========================================================================
    // MULTI-THEME ENGINE (Signature Default, Material You, Light, Dark, Crimson)
    // =========================================================================
    private fun resolveTheme(mode: String): ThemePalette {
        val sysNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return when (mode) {
            "LIGHT" -> ThemePalette(
                name = "Light Mode",
                bgTop = Color.parseColor("#F1F5F9"),
                bgBottom = Color.parseColor("#E2E8F0"),
                cardBg = Color.parseColor("#FFFFFF"),
                cardStroke = Color.parseColor("#CBD5E1"),
                pillBg = Color.parseColor("#F1F5F9"),
                textPrimary = Color.parseColor("#0F172A"),
                textSecondary = Color.parseColor("#475569"),
                accentPrimary = Color.parseColor("#0284C7"),
                accentSecondary = Color.parseColor("#4F46E5"),
                good = Color.parseColor("#059669"),
                warn = Color.parseColor("#D97706"),
                danger = Color.parseColor("#E11D48"),
                isLight = true
            )
            "DARK" -> ThemePalette(
                name = "Dark Mode",
                bgTop = Color.parseColor("#090D16"),
                bgBottom = Color.parseColor("#111827"),
                cardBg = Color.parseColor("#1E293B"),
                cardStroke = Color.parseColor("#334155"),
                pillBg = Color.parseColor("#0F172A"),
                textPrimary = Color.parseColor("#F8FAFC"),
                textSecondary = Color.parseColor("#94A3B8"),
                accentPrimary = Color.parseColor("#38BDF8"),
                accentSecondary = Color.parseColor("#818CF8"),
                good = Color.parseColor("#10B981"),
                warn = Color.parseColor("#F59E0B"),
                danger = Color.parseColor("#F43F5E"),
                isLight = false
            )
            "MATERIAL_YOU" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (sysNight) {
                        ThemePalette(
                            name = "Material You (Dynamic)",
                            bgTop = getColor(android.R.color.system_neutral1_900),
                            bgBottom = getColor(android.R.color.system_neutral1_800),
                            cardBg = getColor(android.R.color.system_neutral2_800),
                            cardStroke = getColor(android.R.color.system_neutral2_600),
                            pillBg = getColor(android.R.color.system_neutral1_900),
                            textPrimary = getColor(android.R.color.system_neutral1_50),
                            textSecondary = getColor(android.R.color.system_neutral2_200),
                            accentPrimary = getColor(android.R.color.system_accent1_200),
                            accentSecondary = getColor(android.R.color.system_accent2_200),
                            good = Color.parseColor("#34D399"),
                            warn = Color.parseColor("#FBBF24"),
                            danger = Color.parseColor("#FB7185"),
                            isLight = false
                        )
                    } else {
                        ThemePalette(
                            name = "Material You (Dynamic)",
                            bgTop = getColor(android.R.color.system_neutral1_50),
                            bgBottom = getColor(android.R.color.system_neutral1_100),
                            cardBg = getColor(android.R.color.system_neutral1_0),
                            cardStroke = getColor(android.R.color.system_neutral2_200),
                            pillBg = getColor(android.R.color.system_neutral1_100),
                            textPrimary = getColor(android.R.color.system_neutral1_900),
                            textSecondary = getColor(android.R.color.system_neutral2_600),
                            accentPrimary = getColor(android.R.color.system_accent1_600),
                            accentSecondary = getColor(android.R.color.system_accent2_600),
                            good = Color.parseColor("#059669"),
                            warn = Color.parseColor("#D97706"),
                            danger = Color.parseColor("#E11D48"),
                            isLight = true
                        )
                    }
                } else resolveTheme("SIGNATURE")
            }
            else -> ThemePalette(
                name = "Signature Default",
                bgTop = Color.parseColor("#0B132B"),
                bgBottom = Color.parseColor("#1C2541"),
                cardBg = Color.parseColor("#232F4E"),
                cardStroke = Color.parseColor("#3A506B"),
                pillBg = Color.parseColor("#162038"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#A5B4FC"),
                accentPrimary = Color.parseColor("#00F5D4"),
                accentSecondary = Color.parseColor("#00BBF9"),
                good = Color.parseColor("#00F5D4"),
                warn = Color.parseColor("#FEE440"),
                danger = Color.parseColor("#FF0055"),
                isLight = false
            )
        }
    }

    private fun hapticTap(view: View? = window?.decorView) {
        view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun hapticTick(view: View? = window?.decorView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        } else {
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    // =========================================================================
    // MAIN INTERFACE BUILDER
    // =========================================================================
    private fun buildFullInterface() {
        tabViews.clear()
        tabBtns.clear()
        coreFreqLabels.clear()
        coreToggleSwitches.clear()

        try {
            window.statusBarColor = theme.bgTop
            window.navigationBarColor = theme.bgBottom
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (theme.isLight) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
            }
        } catch (e: Exception) {}

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(theme.bgTop, theme.bgBottom))
            setPadding(32, 36, 32, 24)
        }

        // 1. Header Row with Theme Switcher
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "TempStatusBar Pro"
            setTextColor(theme.textPrimary)
            textSize = 20f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })
        titleCol.addView(TextView(this).apply {
            text = "${HardwareThermalControl.getHardwareInfo()} • ${theme.name}"
            setTextColor(theme.textSecondary)
            textSize = 11.5f
        })

        val themeBtn = createPillButton("🎨 Theme") {
            showThemePickerDialog()
        }.apply { setPadding(28, 16, 28, 16) }

        header.addView(titleCol)
        header.addView(themeBtn)
        root.addView(header)

        addSpace(root, 20)

        // 2. Live Access & Permission Status Bar (Root, Usage, Status Bar, Overlay)
        val accessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        rootChip = createAccessChip("ROOT") {
            thread {
                val ok = HardwareThermalControl.isRootAvailable()
                uiHandler.post {
                    updateAccessChips(ok)
                    Toast.makeText(this, if (ok) "Root Access Verified (uid=0)" else "Root Denied — Check Magisk/KernelSU", Toast.LENGTH_SHORT).show()
                }
            }
        }
        usageChip = createAccessChip("USAGE") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Toast.makeText(this, "Enable Usage Access for TempStatusBar", Toast.LENGTH_LONG).show()
        }
        notifChip = createAccessChip("STATUS BAR") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } else {
                settings.showNotification = true
                startAndSyncService()
                Toast.makeText(this, "Status Bar Temperature Refreshed", Toast.LENGTH_SHORT).show()
            }
        }
        overlayChip = createAccessChip("OVERLAY") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                toggleOverlayPill(!FpsOverlayService.isRunning)
            }
        }
        accessRow.addView(rootChip)
        accessRow.addView(usageChip)
        accessRow.addView(notifChip)
        accessRow.addView(overlayChip)
        root.addView(accessRow)

        addSpace(root, 22)

        // 3. 4-Tab Segmented Navigation
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            setPadding(8, 8, 8, 8)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(theme.pillBg)
                setStroke(2, theme.cardStroke)
            }
        }
        listOf("Monitor", "Hardware", "Gaming/FPS", "Tasks/Log").forEachIndexed { idx, label ->
            val tBtn = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(8, 20, 8, 20)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    hapticTap(this)
                    switchTab(idx)
                }
            }
            tabBtns.add(tBtn)
            tabBar.addView(tBtn)
        }
        root.addView(tabBar)

        addSpace(root, 20)

        // 4. Tab Content Area
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val t1 = buildMonitorTab()
        val t2 = buildHardwareTab()
        val t3 = buildGamingTab()
        val t4 = buildTasksAndLogTab()

        listOf(t1, t2, t3, t4).forEach { v ->
            tabViews.add(v)
            contentFrame.addView(v)
        }
        root.addView(contentFrame)

        setContentView(root)
        switchTab(currentTabIndex)
    }

    private fun switchTab(idx: Int) {
        currentTabIndex = idx
        tabViews.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
        tabBtns.forEachIndexed { i, b ->
            if (i == idx) {
                b.setTextColor(if (theme.isLight) Color.WHITE else Color.parseColor("#090D16"))
                b.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(theme.accentPrimary, theme.accentSecondary)
                ).apply { cornerRadius = 100f }
            } else {
                b.setTextColor(theme.textSecondary)
                b.background = null
            }
        }
        when (idx) {
            0 -> loadThermalGraphData()
            2 -> loadFpsGraphData()
            3 -> {
                refreshProcessesList()
                logConsoleText.text = readAllLogs().ifBlank { "No logs recorded yet." }
            }
        }
    }

    // =========================================================================
    // TAB 1: MONITOR (Status Bar Temp, Interactive Thermal Graph, Thresholds)
    // =========================================================================
    private fun buildMonitorTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

        // Live Hero Status Card
        val heroCard = createCard()
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroTempText = TextView(this).apply {
            text = "--°C"
            setTextColor(theme.good)
            textSize = 36f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        heroStateText = TextView(this).apply {
            text = "POLLING..."
            setTextColor(theme.textPrimary)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        heroWattText = TextView(this).apply {
            text = "-- W  •  -- mA"
            setTextColor(theme.accentSecondary)
            textSize = 13f
            setPadding(0, 4, 0, 0)
        }
        rightCol.addView(heroStateText)
        rightCol.addView(heroWattText)
        heroRow.addView(heroTempText)
        heroRow.addView(rightCol)
        heroCard.addView(heroRow)

        heroCard.addView(createDivider())

        heroCard.addView(createSwitch("Show Live Temperature in Status Bar", settings.showNotification) { on ->
            settings.showNotification = on
            startAndSyncService()
        })
        heroCard.addView(createSwitch("Show Live Wattage & mA in Notification", settings.showPowerMetrics) { on ->
            settings.showPowerMetrics = on
            startAndSyncService()
        })
        heroCard.addView(createSwitch("Battery Bypass (Isolate Charger)", HardwareThermalControl.isManualBypassActive) { on ->
            HardwareThermalControl.setChargingEnabled(!on, isManualToggle = true)
            startAndSyncService()
        })
        col.addView(heroCard)

        addSpace(col, 22)

        // Interactive Touch-Draggable Temperature Graph Card
        val graphCard = createCard()
        val gHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gHead.addView(TextView(this).apply {
            text = "INTERACTIVE TEMPERATURE GRAPH (DRAG TO INSPECT)"
            setTextColor(theme.accentPrimary)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        gHead.addView(createPillButton("↻") { loadThermalGraphData() }.apply { setPadding(22, 10, 22, 10) })
        graphCard.addView(gHead)

        tempScrubReadout = TextView(this).apply {
            text = "Drag across the graph to inspect historical temps & CPU usage"
            setTextColor(theme.textSecondary)
            textSize = 12f
            setPadding(0, 8, 0, 10)
        }
        graphCard.addView(tempScrubReadout)

        tempGraphView = InteractiveGraphView(this) { idx ->
            if (idx in thermalHistoryList.indices) {
                val pt = thermalHistoryList[idx]
                tempScrubReadout.text = "Sample #${idx + 1}/${thermalHistoryList.size}  •  ${pt.temp}°C  •  ${pt.chargeType}"
                tempSnapshotBox.text = "CPU Snapshot at Sample #${idx + 1} (${pt.temp}°C):\n${pt.details}"
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 340)
        }
        graphCard.addView(tempGraphView)

        tempSnapshotBox = TextView(this).apply {
            text = "Touch and drag the graph above to view recorded processes."
            setTextColor(theme.textPrimary)
            textSize = 11.5f
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(theme.pillBg)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 14, 0, 0)
            }
        }
        graphCard.addView(tempSnapshotBox)
        col.addView(graphCard)

        addSpace(col, 22)

        // Thermal & Charging Thresholds Card
        val threshCard = createCard()
        threshCard.addView(createSectionLabel("THERMAL & CHARGING THRESHOLDS"))
        threshCard.addView(createSwitch("Enable Max Charge % Cutoff", settings.chargeLimitEnabled) { on ->
            settings.chargeLimitEnabled = on
        })

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; setPadding(0, 10, 0, 10) }
        lateinit var warnBtn: TextView
        lateinit var cutBtn: TextView
        lateinit var resBtn: TextView

        warnBtn = createPillButton("Warn: ${settings.warningTemp}°C") {
            showNumberDialog("Overheat Warning (°C)", settings.warningTemp, 36, 48) { v ->
                settings.warningTemp = v; warnBtn.text = "Warn: ${v}°C"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } }

        cutBtn = createPillButton("Cutoff: ${settings.cutoffTemp}°C") {
            showNumberDialog("Charge Cutoff (°C)", settings.cutoffTemp, 37, 48) { v ->
                settings.cutoffTemp = v; cutBtn.text = "Cutoff: ${v}°C"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 6, 0) } }

        resBtn = createPillButton("Resume: ${settings.resumeTemp}°C") {
            showNumberDialog("Charge Resume (°C)", settings.resumeTemp, 34, 45) { v ->
                settings.resumeTemp = v; resBtn.text = "Resume: ${v}°C"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } }

        row1.addView(warnBtn); row1.addView(cutBtn); row1.addView(resBtn)
        threshCard.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        lateinit var maxPctBtn: TextView
        lateinit var resPctBtn: TextView

        maxPctBtn = createPillButton("Max Charge: ${settings.chargeLimitMax}%") {
            showNumberDialog("Stop Charging At (%)", settings.chargeLimitMax, 60, 100) { v ->
                settings.chargeLimitMax = v; maxPctBtn.text = "Max Charge: ${v}%"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } }

        resPctBtn = createPillButton("Resume Pct: ${settings.chargeLimitResume}%") {
            showNumberDialog("Resume Charging At (%)", settings.chargeLimitResume, 50, 95) { v ->
                settings.chargeLimitResume = v; resPctBtn.text = "Resume Pct: ${v}%"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } }

        row2.addView(maxPctBtn); row2.addView(resPctBtn)
        threshCard.addView(row2)
        col.addView(threshCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 2: HARDWARE (Per-Core Control, Prime Throttle, GPU, Refresh Rate)
    // =========================================================================
    private fun buildHardwareTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

        val gpuCard = createCard()
        gpuCard.addView(createSectionLabel("ADRENO 650 GPU & SYSTEM MEMORY"))
        hwGpuText = createBodyText("Polling GPU...")
        hwRamText = createBodyText("Polling RAM...")
        gpuCard.addView(hwGpuText)
        gpuCard.addView(hwRamText)

        val gpuBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setPadding(0, 14, 0, 0) }
        gpuBtns.addView(createPillButton("⚡ Force 670MHz Nuclear") {
            HardwareThermalControl.forceUnthrottleGPU()
            Toast.makeText(this, "GPU Forced to Power Level 0 (670MHz)", Toast.LENGTH_SHORT).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } })

        gpuBtns.addView(createPillButton("🌱 Restore Auto GPU") {
            HardwareThermalControl.executeRootCommand("GPU -> Auto Mode", "echo 6 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel 2>/dev/null; echo 5 > /sys/class/kgsl/kgsl-3d0/default_pwrlevel 2>/dev/null")
            Toast.makeText(this, "GPU Restored to Dynamic Scaling", Toast.LENGTH_SHORT).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } })

        gpuCard.addView(gpuBtns)
        col.addView(gpuCard)

        addSpace(col, 22)

        // Display Refresh Rate Override Card
        val hzCard = createCard()
        hzCard.addView(createSectionLabel("DISPLAY REFRESH RATE LOCK"))
        val hzRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; setPadding(0, 12, 0, 0) }
        listOf(60, 90, 120).forEach { hz ->
            hzRow.addView(createPillButton("${hz}Hz") {
                HardwareThermalControl.forceRefreshRate(hz)
                Toast.makeText(this, "Display Refresh Locked to ${hz}Hz", Toast.LENGTH_SHORT).show()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 6, 0) } })
        }
        hzCard.addView(hzRow)
        col.addView(hzCard)

        addSpace(col, 22)

        // Kryo 585 Per-Core Control Card
        val cpuCard = createCard()
        cpuCard.addView(createSectionLabel("KRYO 585 PER-CORE CONTROL & GOVERNOR"))
        cpuCard.addView(createSwitch("Smart Thermal Governor (Auto Cool)", false) { on ->
            sendBroadcast(Intent("ACTION_TOGGLE_SMART_GOVERNOR").apply {
                setPackage(packageName); putExtra("state", on)
            })
        })
        cpuCard.addView(createSwitch("Manual Throttle Prime Core (C7 Min Freq)", false) { on ->
            HardwareThermalControl.throttlePrimeCore(on)
        })
        cpuCard.addView(createDivider())

        for (coreId in 0..7) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val clusterTag = when {
                coreId <= 3 -> "Silver"
                coreId <= 6 -> "Gold"
                else -> "Prime"
            }
            val lbl = TextView(this).apply {
                text = "Core $coreId ($clusterTag): -- MHz"
                setTextColor(theme.textPrimary)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            coreFreqLabels.add(lbl)
            row.addView(lbl)

            if (coreId > 0) {
                val sw = Switch(this).apply {
                    isChecked = true
                    setOnCheckedChangeListener { v, online ->
                        hapticTap(v)
                        HardwareThermalControl.setCoreOnline(coreId, online)
                    }
                }
                coreToggleSwitches.add(sw)
                row.addView(sw)
            } else {
                row.addView(TextView(this).apply {
                    text = "PRIMARY"
                    setTextColor(theme.accentPrimary)
                    textSize = 10.5f
                })
            }
            cpuCard.addView(row)
        }

        col.addView(cpuCard)
        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 3: GAMING & FPS (Interactive FPS Graph + Nuclear 120 FPS Controls)
    // =========================================================================
    private fun buildGamingTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

        // Interactive FPS Session Graph Card
        val fpsCard = createCard()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fpsSessionHeader = TextView(this).apply {
            text = "INTERACTIVE FPS SESSION GRAPH"
            setTextColor(theme.good)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        head.addView(fpsSessionHeader)
        head.addView(createPillButton("Matches") { showFpsSessionDialog() }.apply { setPadding(24, 12, 24, 12) })
        fpsCard.addView(head)

        fpsScrubReadout = TextView(this).apply {
            text = "Drag across the graph to inspect exact FPS & Temperature"
            setTextColor(theme.textSecondary)
            textSize = 12f
            setPadding(0, 8, 0, 10)
        }
        fpsCard.addView(fpsScrubReadout)

        fpsGraphView = InteractiveGraphView(this) { idx ->
            if (fpsSessionsList.isNotEmpty()) {
                val s = fpsSessionsList[currentFpsSessionIdx]
                val fVal = s.fpsList.getOrNull(idx)?.toInt() ?: 0
                val tVal = s.tempList.getOrNull(idx)?.toInt() ?: s.avgTemp
                fpsScrubReadout.text = "Point #${idx + 1}/${s.fpsList.size}  •  $fVal FPS  •  ${tVal}°C  (Avg ${s.avg} | Max ${s.max})"
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 340)
        }
        fpsCard.addView(fpsGraphView)
        col.addView(fpsCard)

        addSpace(col, 22)

        // Nuclear 120 FPS Gaming Engine Card
        val gameCard = createCard()
        gameCard.addView(createSectionLabel("NUCLEAR 120 FPS ENGINE & OVERLAY"))

        gameCard.addView(createSwitch("Nuclear 120 FPS Gaming Mode", settings.gamingModeEnabled) { on ->
            settings.gamingModeEnabled = on
            if (!on) HardwareThermalControl.setPeakPerformanceMode(false)
        })
        gameCard.addView(createSwitch("Launch Floating FPS Pill Overlay", FpsOverlayService.isRunning) { on ->
            toggleOverlayPill(on)
        })
        gameCard.addView(createSwitch("Show Battery Temp in FPS Pill", prefs.getBoolean("showTempInFpsOverlay", true)) { on ->
            prefs.edit().putBoolean("showTempInFpsOverlay", on).apply()
        })

        gameCard.addView(createDivider())

        lateinit var capBtn: TextView
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setPadding(0, 8, 0, 12) }
        row.addView(createPillButton("Target Games (${settings.gamingApps.size})") {
            showAppPicker("Select Target Games", settings.gamingApps, true) { updated ->
                settings.gamingApps = updated; buildFullInterface()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } })

        capBtn = createPillButton("Throttle Cap: ${settings.gameThrottleTemp}°C") {
            showNumberDialog("Game Thermal Ceiling (°C)", settings.gameThrottleTemp, 38, 48) { v ->
                settings.gameThrottleTemp = v; capBtn.text = "Throttle Cap: ${v}°C"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } }
        row.addView(capBtn)
        gameCard.addView(row)

        gameCard.addView(createPillButton("Exempt Background Apps (${settings.exemptApps.size})") {
            showAppPicker("Select Exempt Apps", settings.exemptApps, false) { updated ->
                settings.exemptApps = updated; buildFullInterface()
            }
        })

        col.addView(gameCard)
        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 4: TASKS & LOGS (Kernel Process Manager + Action Log Console)
    // =========================================================================
    private fun buildTasksAndLogTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

        val procCard = createCard()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "LIVE KERNEL PROCESS & RAM MANAGER"
            setTextColor(theme.accentPrimary)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(createPillButton("Purge RAM") {
            HardwareThermalControl.clearRamCaches()
            Toast.makeText(this, "Kernel RAM Caches Dropped", Toast.LENGTH_SHORT).show()
            refreshProcessesList()
        }.apply { setPadding(22, 10, 22, 10) })
        procCard.addView(head)

        processContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
        }
        procCard.addView(processContainer)
        col.addView(procCard)

        addSpace(col, 22)

        val logCard = createCard()
        logCard.addView(createSectionLabel("DEEP DIAGNOSTIC ACTION LOG"))
        val logBox = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 420).apply { setMargins(0, 12, 0, 14) }
            setPadding(22, 18, 22, 18)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(theme.pillBg)
                setStroke(2, theme.cardStroke)
            }
        }
        logConsoleText = TextView(this).apply {
            text = readAllLogs().ifBlank { "No diagnostic logs recorded yet." }
            setTextColor(theme.textPrimary)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logBox.addView(logConsoleText)
        logCard.addView(logBox)

        val logBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        logBtns.addView(createPillButton("Refresh") {
            logConsoleText.text = readAllLogs().ifBlank { "No diagnostic logs recorded yet." }
            logBox.post { logBox.fullScroll(View.FOCUS_DOWN) }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } })

        logBtns.addView(createPillButton("Export .txt") { exportLogsToFile() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 6, 0) }
        })

        logBtns.addView(createPillButton("Clear") {
            clearAllLogs()
            logConsoleText.text = readAllLogs()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } })

        logCard.addView(logBtns)
        col.addView(logCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // INTERACTIVE TOUCH-DRAGGABLE GRAPH VIEW WITH HAPTIC SCRUBBING
    // =========================================================================
    inner class InteractiveGraphView(
        context: Context,
        private val onScrubPoint: (Int) -> Unit
    ) : View(context) {

        private var primarySeries: List<Float> = emptyList()
        private var secondarySeries: List<Float> = emptyList()
        private var lineColor: Int = theme.accentPrimary
        private var secondaryColor: Int = theme.warn
        private var suffix: String = ""
        private var scrubIndex: Int = -1

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.cardStroke
            strokeWidth = 2f
        }
        private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.textSecondary
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 6f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val secCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 3.5f
            style = Paint.Style.STROKE
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val scrubLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.textPrimary
            strokeWidth = 3f
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun setGraphData(primary: List<Float>, secondary: List<Float>, color1: Int, color2: Int, unit: String) {
            primarySeries = primary
            secondarySeries = secondary
            lineColor = color1
            secondaryColor = color2
            suffix = unit
            scrubIndex = if (primary.isNotEmpty()) primary.size - 1 else -1
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (primarySeries.isEmpty()) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val padL = 84f
                    val padR = 24f
                    val w = (width - padL - padR).coerceAtLeast(1f)
                    val frac = ((event.x - padL) / w).coerceIn(0f, 1f)
                    val newIdx = (frac * (primarySeries.size - 1)).roundToInt().coerceIn(0, primarySeries.size - 1)
                    if (newIdx != scrubIndex) {
                        scrubIndex = newIdx
                        hapticTick(this)
                        onScrubPoint(newIdx)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f || primarySeries.isEmpty()) return

            val padL = 84f; val padR = 24f; val padT = 26f; val padB = 30f
            val cw = w - padL - padR
            val ch = h - padT - padB

            val rawMin = primarySeries.minOrNull() ?: 0f
            val rawMax = primarySeries.maxOrNull() ?: 100f
            val span = (rawMax - rawMin).coerceAtLeast(6f)
            val minV = max(0f, rawMin - span * 0.15f)
            val maxV = rawMax + span * 0.15f
            val range = (maxV - minV).coerceAtLeast(1f)

            for (i in 0..3) {
                val frac = i / 3f
                val y = padT + ch * frac
                canvas.drawLine(padL, y, w - padR, y, gridPaint)
                val vLbl = (maxV - range * frac).roundToInt()
                canvas.drawText("$vLbl$suffix", 6f, y + 8f, axisTextPaint)
            }

            val pts = primarySeries.mapIndexed { i, v ->
                val x = if (primarySeries.size > 1) padL + (i.toFloat() / (primarySeries.size - 1)) * cw else padL + cw / 2f
                val y = padT + ch * (1f - ((v - minV) / range).coerceIn(0f, 1f))
                PointF(x, y)
            }

            val path = Path()
            val fill = Path()
            path.moveTo(pts.first().x, pts.first().y)
            fill.moveTo(pts.first().x, padT + ch)
            fill.lineTo(pts.first().x, pts.first().y)

            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]; val p1 = pts[i + 1]
                val cx = (p0.x + p1.x) / 2f
                path.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                fill.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            }
            fill.lineTo(pts.last().x, padT + ch)
            fill.close()

            fillPaint.shader = LinearGradient(
                0f, padT, 0f, padT + ch,
                Color.argb(95, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
                Color.argb(0, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fill, fillPaint)

            curvePaint.color = lineColor
            canvas.drawPath(path, curvePaint)

            if (secondarySeries.size >= 2) {
                val sMin = (secondarySeries.minOrNull() ?: 0f) - 2f
                val sMax = (secondarySeries.maxOrNull() ?: 100f) + 2f
                val sRange = (sMax - sMin).coerceAtLeast(1f)
                val sPath = Path()
                secondarySeries.forEachIndexed { i, v ->
                    val x = padL + (i.toFloat() / (secondarySeries.size - 1)) * cw
                    val y = padT + ch * (1f - ((v - sMin) / sRange).coerceIn(0f, 1f))
                    if (i == 0) sPath.moveTo(x, y) else sPath.lineTo(x, y)
                }
                secCurvePaint.color = secondaryColor
                canvas.drawPath(sPath, secCurvePaint)
            }

            // Draw Interactive Scrubbing Crosshair & Node
            val idx = scrubIndex.coerceIn(0, pts.size - 1)
            val activePt = pts[idx]
            canvas.drawLine(activePt.x, padT, activePt.x, padT + ch, scrubLinePaint)
            dotPaint.color = lineColor
            canvas.drawCircle(activePt.x, activePt.y, 12f, dotPaint)
            dotPaint.color = theme.cardBg
            canvas.drawCircle(activePt.x, activePt.y, 5f, dotPaint)
        }
    }

    // =========================================================================
    // DATA LOADERS & PERMISSION VERIFIERS
    // =========================================================================
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updateAccessChips(hasRoot: Boolean) {
        val hasUsage = hasUsageStatsPermission()
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasNotif = (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && settings.showNotification

        styleAccessChip(rootChip, if (hasRoot) "ROOT: OK" else "ROOT: NO", hasRoot)
        styleAccessChip(usageChip, if (hasUsage) "USAGE: OK" else "USAGE: FIX", hasUsage)
        styleAccessChip(notifChip, if (hasNotif) "ICON: ON" else "ICON: OFF", hasNotif)
        styleAccessChip(overlayChip, if (hasOverlay) "PILL: OK" else "PILL: FIX", hasOverlay)
    }

    private fun styleAccessChip(chip: TextView, label: String, granted: Boolean) {
        chip.text = label
        val c = if (granted) theme.good else theme.danger
        chip.setTextColor(c)
        chip.background = GradientDrawable().apply {
            cornerRadius = 100f
            setColor(Color.argb(32, Color.red(c), Color.green(c), Color.blue(c)))
            setStroke(2, Color.argb(140, Color.red(c), Color.green(c), Color.blue(c)))
        }
    }

    private fun loadThermalGraphData() {
        thread {
            val pts = mutableListOf<ThermalPoint>()
            try {
                val db = dbHelper.readableDatabase
                val tc = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                val tables = mutableListOf<String>()
                while (tc.moveToNext()) tables.add(tc.getString(0))
                tc.close()

                val tName = tables.firstOrNull { !it.contains("fps", true) && !it.contains("sqlite", true) && !it.contains("android", true) }
                if (tName != null) {
                    val c = db.rawQuery("SELECT * FROM $tName ORDER BY rowid DESC LIMIT 60", null)
                    while (c.moveToNext()) {
                        var t = 0; var cType = "Battery"; var det = ""; var ts = ""
                        for (i in 0 until c.columnCount) {
                            val col = c.getColumnName(i).lowercase()
                            val v = c.getString(i) ?: ""
                            when {
                                col.contains("temp") -> t = v.toIntOrNull() ?: 0
                                col.contains("type") -> cType = v
                                col.contains("detail") -> det = v
                                col.contains("time") || col.contains("date") -> ts = v
                            }
                        }
                        if (t in 15..75) pts.add(ThermalPoint(t, cType, det.ifBlank { "No CPU snapshot recorded" }, ts))
                    }
                    c.close()
                }
            } catch (e: Exception) {}

            val ordered = pts.reversed()
            uiHandler.post {
                if (ordered.isNotEmpty()) {
                    thermalHistoryList = ordered
                    tempGraphView.setGraphData(ordered.map { it.temp.toFloat() }, emptyList(), theme.accentPrimary, theme.danger, "°C")
                    val last = ordered.last()
                    tempScrubReadout.text = "Latest (${ordered.size} pts): ${last.temp}°C • ${last.chargeType} (Drag graph to scrub)"
                    tempSnapshotBox.text = "Latest CPU Snapshot (${last.temp}°C):\n${last.details}"
                } else {
                    val curT = (registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 350) ?: 350) / 10
                    thermalHistoryList = listOf(ThermalPoint(curT, "Live", "Waiting for 60s database snapshot...", "Now"))
                    tempGraphView.setGraphData(listOf(curT.toFloat(), curT.toFloat()), emptyList(), theme.accentPrimary, theme.danger, "°C")
                }
            }
        }
    }

    private fun loadFpsGraphData() {
        thread {
            val list = mutableListOf<FpsSessionData>()
            try {
                val db = dbHelper.readableDatabase
                val tc = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                val tables = mutableListOf<String>()
                while (tc.moveToNext()) tables.add(tc.getString(0))
                tc.close()

                val fTable = tables.firstOrNull { it.contains("fps", true) || it.contains("session", true) }
                if (fTable != null) {
                    val c = db.rawQuery("SELECT * FROM $fTable ORDER BY rowid DESC LIMIT 20", null)
                    while (c.moveToNext()) {
                        var app = "Game"; var dur = 0; var minF = 0; var maxF = 0; var avgF = 0; var avgT = 0
                        var fCsv = ""; var tCsv = ""
                        for (i in 0 until c.columnCount) {
                            val col = c.getColumnName(i).lowercase()
                            val v = c.getString(i) ?: ""
                            when {
                                col.contains("app") || col.contains("name") -> app = v
                                col.contains("dur") -> dur = v.toIntOrNull() ?: 0
                                col.contains("min") -> minF = v.toIntOrNull() ?: 0
                                col.contains("max") -> maxF = v.toIntOrNull() ?: 0
                                col.contains("avg") && !col.contains("temp") && col != "avgt" -> avgF = v.toIntOrNull() ?: 0
                                col.contains("avgt") || col.contains("temp") && !v.contains(",") -> avgT = v.toIntOrNull() ?: 0
                                v.contains(",") && fCsv.isEmpty() -> fCsv = v
                                v.contains(",") && fCsv.isNotEmpty() -> tCsv = v
                            }
                        }
                        val fSeries = fCsv.split(",").mapNotNull { it.trim().toFloatOrNull() }
                        val tSeries = tCsv.split(",").mapNotNull { it.trim().toFloatOrNull() }
                        if (fSeries.isNotEmpty()) {
                            list.add(FpsSessionData(app, dur, minF, maxF, avgF, avgT, fSeries, tSeries))
                        }
                    }
                    c.close()
                }
            } catch (e: Exception) {}

            uiHandler.post {
                fpsSessionsList = list
                if (list.isNotEmpty()) {
                    currentFpsSessionIdx = currentFpsSessionIdx.coerceIn(0, list.size - 1)
                    val s = list[currentFpsSessionIdx]
                    fpsSessionHeader.text = "${s.app.uppercase()} • AVG ${s.avg} FPS (${s.duration}s)"
                    fpsScrubReadout.text = "Min: ${s.min} FPS • Avg: ${s.avg} FPS • Peak: ${s.max} FPS • Avg Temp: ${s.avgTemp}°C"
                    fpsGraphView.setGraphData(s.fpsList, s.tempList, theme.good, theme.warn, "")
                } else {
                    fpsSessionHeader.text = "NO RECORDED FPS SESSIONS YET"
                    fpsScrubReadout.text = "Enable the Floating FPS Pill and tap it in-game to record."
                    fpsGraphView.setGraphData(listOf(60f, 90f, 120f, 118f, 120f), emptyList(), theme.good, theme.warn, "")
                }
            }
        }
    }

    private fun refreshProcessesList() {
        processContainer.removeAllViews()
        thread {
            val cpuProcs = HardwareThermalControl.getKernelProcessSnapshot(applicationContext)
            val ramProcs = HardwareThermalControl.getDetailedRam().take(6)
            uiHandler.post {
                processContainer.removeAllViews()
                if (cpuProcs.isEmpty() && ramProcs.isEmpty()) {
                    processContainer.addView(createBodyText("Grant Root access to inspect kernel processes."))
                    return@post
                }
                cpuProcs.forEach { p ->
                    processContainer.addView(createProcItem("${p.name} (${p.cpu}% CPU)", p.pid))
                }
                ramProcs.forEach { r ->
                    processContainer.addView(createProcItem("${r.name} (${r.sizeMb} MB RAM)", r.pid))
                }
            }
        }
    }

    private fun createProcItem(label: String, pid: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22, 18, 22, 18)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 6, 0, 6) }
            background = GradientDrawable().apply { cornerRadius = 18f; setColor(theme.pillBg) }
            addView(TextView(context).apply {
                text = label; setTextColor(theme.textPrimary); textSize = 12.5f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "PID $pid"; setTextColor(theme.accentPrimary); textSize = 11f; typeface = Typeface.MONOSPACE
            })
            setOnClickListener {
                hapticTap(this)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(label)
                    .setItems(arrayOf("Pin to Silver Cores (0-3)", "Kill Process (PID $pid)")) { _, w ->
                        if (w == 0) HardwareThermalControl.pinProcessToEfficiencyCores(pid)
                        else { HardwareThermalControl.killProcess(pid); refreshProcessesList() }
                    }
                    .show()
            }
        }
    }

    // =========================================================================
    // DIALOGS & LOG HELPERS
    // =========================================================================
    private fun showThemePickerDialog() {
        val modes = arrayOf("SIGNATURE", "MATERIAL_YOU", "LIGHT", "DARK")
        val names = arrayOf("Signature Default (Cyber Thermal)", "Material You (Dynamic System)", "Light Mode (Clean Alabaster)", "Dark Mode (Deep Slate)")
        AlertDialog.Builder(this)
            .setTitle("Select App Theme")
            .setItems(names) { _, which ->
                prefs.edit().putString("appThemeMode", modes[which]).apply()
                theme = resolveTheme(modes[which])
                buildFullInterface()
            }
            .show()
    }

    private fun showFpsSessionDialog() {
        if (fpsSessionsList.isEmpty()) {
            Toast.makeText(this, "No recorded sessions yet", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = fpsSessionsList.mapIndexed { i, s -> "#${i + 1} ${s.app} — Avg ${s.avg} FPS | Max ${s.max} FPS (${s.avgTemp}°C)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Recorded FPS Matches")
            .setItems(labels) { _, which ->
                currentFpsSessionIdx = which
                loadFpsGraphData()
            }
            .show()
    }

    private fun showAppPicker(title: String, current: Set<String>, includeBgmi: Boolean, onSave: (Set<String>) -> Unit) {
        thread {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("pubg") || it.packageName.contains("tencent") }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
            val names = apps.map { "${it.loadLabel(pm)} (${it.packageName})" }.toTypedArray()
            val pkgs = apps.map { it.packageName }
            val checked = BooleanArray(pkgs.size) { current.contains(pkgs[it]) }
            val selected = current.toMutableSet()

            uiHandler.post {
                val b = AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMultiChoiceItems(names, checked) { _, w, isChecked ->
                        if (isChecked) selected.add(pkgs[w]) else selected.remove(pkgs[w])
                    }
                    .setPositiveButton("Save") { _, _ -> onSave(selected) }
                    .setNegativeButton("Cancel", null)
                if (includeBgmi) {
                    b.setNeutralButton("+ Add BGMI") { _, _ ->
                        selected.add("com.pubg.imobile"); onSave(selected)
                    }
                }
                b.show()
            }
        }
    }

    private fun showNumberDialog(title: String, current: Int, minV: Int, maxV: Int, onSave: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("$title ($minV - $maxV)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                onSave(input.text.toString().toIntOrNull()?.coerceIn(minV, maxV) ?: current)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun readAllLogs(): String {
        try {
            for (m in AppLogger.javaClass.methods) {
                if (m.parameterCount == 0 && (m.returnType == String::class.java || List::class.java.isAssignableFrom(m.returnType)) && m.name != "toString") {
                    val res = m.invoke(AppLogger)
                    if (res is String && res.isNotBlank()) return res
                    if (res is List<*> && res.isNotEmpty()) return res.joinToString("\n")
                }
            }
            for (f in AppLogger.javaClass.declaredFields) {
                f.isAccessible = true
                val v = f.get(AppLogger)
                if (v is List<*> && v.isNotEmpty()) return v.joinToString("\n")
                if (v is StringBuilder && v.isNotEmpty()) return v.toString()
                if (v is File && v.exists()) return v.readText()
            }
            filesDir.listFiles()?.firstOrNull { it.name.contains("log", true) }?.let { return it.readText() }
        } catch (e: Exception) {}
        return ""
    }

    private fun clearAllLogs() {
        try {
            AppLogger.javaClass.methods.firstOrNull { it.name.contains("clear", true) && it.parameterCount == 0 }?.invoke(AppLogger)
            for (f in AppLogger.javaClass.declaredFields) {
                f.isAccessible = true
                val v = f.get(AppLogger)
                if (v is MutableList<*>) v.clear()
                if (v is StringBuilder) v.clear()
                if (v is File && v.exists()) v.writeText("")
            }
        } catch (e: Exception) {}
        AppLogger.log("LOGS CLEARED BY USER")
    }

    private fun exportLogsToFile() {
        try {
            val txt = readAllLogs()
            val name = "ActionLog_${System.currentTimeMillis()}.txt"
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(txt)
            Toast.makeText(this, "Saved to Downloads/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // UI COMPONENT HELPERS
    // =========================================================================
    private fun createAccessChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(10, 14, 10, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { hapticTap(this); onClick() }
        }
    }

    private fun createScroll() = ScrollView(this).apply {
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    }

    private fun createCol() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(2, 2, 2, 36)
    }

    private fun createCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(38, 32, 38, 32)
        background = GradientDrawable().apply {
            cornerRadius = 32f
            setColor(theme.cardBg)
            setStroke(2, theme.cardStroke)
        }
    }

    private fun createSectionLabel(txt: String) = TextView(this).apply {
        text = txt
        setTextColor(theme.accentPrimary)
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.06f
    }

    private fun createBodyText(txt: String) = TextView(this).apply {
        text = txt
        setTextColor(theme.textPrimary)
        textSize = 13f
        setPadding(0, 6, 0, 2)
    }

    private fun createSwitch(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) = Switch(this).apply {
        text = title
        setTextColor(theme.textPrimary)
        textSize = 13.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isChecked = checked
        setPadding(0, 18, 0, 18)
        setOnCheckedChangeListener { v, isChecked ->
            hapticTap(v)
            onToggle(isChecked)
        }
    }

    private fun createPillButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(theme.textPrimary)
        textSize = 12f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(22, 22, 22, 22)
        background = GradientDrawable().apply {
            cornerRadius = 22f
            setColor(theme.pillBg)
            setStroke(2, theme.cardStroke)
        }
        setOnClickListener { hapticTap(this); onClick() }
    }

    private fun createDivider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { setMargins(0, 14, 0, 14) }
        setBackgroundColor(theme.cardStroke)
    }

    private fun addSpace(parent: LinearLayout, px: Int) {
        parent.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, px) })
    }

    private fun toggleOverlayPill(enable: Boolean) {
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Grant Overlay Permission first", Toast.LENGTH_LONG).show()
            return
        }
        if (enable) startService(Intent(this, FpsOverlayService::class.java))
        else stopService(Intent(this, FpsOverlayService::class.java))
    }

    private fun startAndSyncService() {
        try {
            val intent = Intent(this, TempMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            sendBroadcast(Intent("ACTION_FORCE_NOTIF_UPDATE").apply { setPackage(packageName) })
        } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        isUiActive = true
        uiHandler.post(livePoller)
        startAndSyncService()
    }

    override fun onPause() {
        super.onPause()
        isUiActive = false
        uiHandler.removeCallbacks(livePoller)
    }
}
