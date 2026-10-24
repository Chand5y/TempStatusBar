package com.personal.tempstatusbar

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.*
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
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var settings: SettingsManager
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var prefs: android.content.SharedPreferences

    // Theme Palette — Mature Midnight Slate & Frosted Titanium
    private val COLOR_BG_TOP = Color.parseColor("#0F172A")
    private val COLOR_BG_BOTTOM = Color.parseColor("#1E293B")
    private val COLOR_CARD_BG = Color.parseColor("#CC1E293B")
    private val COLOR_CARD_STROKE = Color.parseColor("#3394A3B8")
    private val COLOR_PILL_BG = Color.parseColor("#283548")
    private val COLOR_TEXT_PRIMARY = Color.parseColor("#F8FAFC")
    private val COLOR_TEXT_SECONDARY = Color.parseColor("#94A3B8")
    private val COLOR_ACCENT_CYAN = Color.parseColor("#38BDF8")
    private val COLOR_ACCENT_INDIGO = Color.parseColor("#6366F1")
    private val COLOR_ACCENT_EMERALD = Color.parseColor("#10B981")
    private val COLOR_ACCENT_AMBER = Color.parseColor("#F59E0B")
    private val COLOR_ACCENT_ROSE = Color.parseColor("#F43F5E")

    // Tab Navigation Views
    private val tabContainers = mutableListOf<View>()
    private val tabButtons = mutableListOf<TextView>()
    private var currentTabIndex = 0

    // Tab 1: Dashboard Views
    private lateinit var statusBadge: TextView
    private lateinit var tempValueText: TextView
    private lateinit var powerValueText: TextView
    private lateinit var gpuValueText: TextView
    private lateinit var coresValueText: TextView
    private lateinit var ramValueText: TextView
    private lateinit var liveTelemetryChart: SmoothGraphView
    private val liveTempBuffer = mutableListOf<Float>()
    private val liveFpsBuffer = mutableListOf<Float>()

    // Tab 2: Gaming & FPS Session Graph Views
    private lateinit var fpsChartView: SmoothGraphView
    private lateinit var fpsSessionTitle: TextView
    private lateinit var fpsStatsRowText: TextView
    private lateinit var gamingSwitch: Switch
    private lateinit var overlaySwitch: Switch
    private lateinit var tempInPillSwitch: Switch
    private lateinit var throttleCapBtn: TextView
    private var fpsSessions = listOf<FpsSessionRecord>()
    private var selectedFpsSessionIdx = 0

    // Tab 3: Thermals & History Graph Views
    private lateinit var tempHistoryChart: SmoothGraphView
    private lateinit var tempHistoryStatsText: TextView
    private lateinit var tempSnapshotDetailsText: TextView
    private lateinit var bypassSwitch: Switch
    private lateinit var smartGovSwitch: Switch
    private lateinit var chargeLimitSwitch: Switch
    private lateinit var warnTempBtn: TextView
    private lateinit var cutoffTempBtn: TextView
    private lateinit var maxChargePctBtn: TextView

    // Tab 4: System & Logs Views
    private lateinit var processListContainer: LinearLayout
    private lateinit var inlineLogText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isUiActive = false

    data class FpsSessionRecord(
        val appName: String,
        val durationSec: Int,
        val minFps: Int,
        val maxFps: Int,
        val avgFps: Int,
        val avgTemp: Int,
        val fpsSeries: List<Float>,
        val tempSeries: List<Float>
    )

    data class TempHistoryRecord(
        val temp: Int,
        val isCharging: Boolean,
        val details: String
    )

    private val telemetryLoop = object : Runnable {
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
                        "Silver: ${cores[0]}  •  Gold: ${cores[4]}  •  Prime: ${cores[7]}"
                    } else {
                        cores.joinToString(" | ")
                    }

                    uiHandler.post {
                        if (!isUiActive) return@post
                        tempValueText.text = "${temp}°C"
                        tempValueText.setTextColor(
                            when {
                                temp >= 41 -> COLOR_ACCENT_ROSE
                                temp >= 38 -> COLOR_ACCENT_AMBER
                                else -> COLOR_ACCENT_EMERALD
                            }
                        )
                        val sign = if (pwrStats.isCharging) "+" else "-"
                        powerValueText.text = "$sign${pwrStats.wattage}W (${abs(pwrStats.currentMa)} mA)"
                        gpuValueText.text = "$gpuLoad @ $gpuFreq"
                        coresValueText.text = coresFormatted
                        ramValueText.text = ramStr.replace("\n", "  •  ")

                        if (temp > 0) {
                            liveTempBuffer.add(temp.toFloat())
                            if (liveTempBuffer.size > 30) liveTempBuffer.removeAt(0)
                        }
                        val curFps = HardwareThermalControl.liveFps
                        if (curFps > 0) {
                            liveFpsBuffer.add(curFps.toFloat())
                            if (liveFpsBuffer.size > 30) liveFpsBuffer.removeAt(0)
                        }
                        liveTelemetryChart.setData(
                            primary = if (liveTempBuffer.size >= 2) liveTempBuffer else listOf(temp.toFloat(), temp.toFloat()),
                            secondary = liveFpsBuffer,
                            primaryColor = COLOR_ACCENT_CYAN,
                            secondaryColor = COLOR_ACCENT_EMERALD,
                            unitSuffix = "°C"
                        )
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
        dbHelper = DatabaseHelper(this)
        prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)
        HardwareThermalControl.init(this)

        ensureDefaultGameConfigured()

        try {
            window.statusBarColor = COLOR_BG_TOP
            window.navigationBarColor = COLOR_BG_BOTTOM
        } catch (e: Exception) {}

        val mainRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(COLOR_BG_TOP, COLOR_BG_BOTTOM)
            )
            setPadding(36, 44, 36, 28)
        }

        // ================= TOP HEADER BAR =================
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 28)
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "THERMAL ENGINE PRO"
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 19f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.04f
        })
        titleCol.addView(TextView(this).apply {
            text = "${HardwareThermalControl.getHardwareInfo()} • HyperOS Control Suite"
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 11.5f
            setPadding(0, 4, 0, 0)
        })

        statusBadge = TextView(this).apply {
            text = "ACTIVE"
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 10.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(26, 10, 26, 10)
        }
        headerRow.addView(titleCol)
        headerRow.addView(statusBadge)
        mainRoot.addView(headerRow)

        // ================= 4-TAB SEGMENTED NAVIGATION BAR =================
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            setPadding(10, 10, 10, 10)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#990F172A"))
                setStroke(2, COLOR_CARD_STROKE)
            }
        }

        val tabNames = listOf("Overview", "FPS & Game", "Thermals", "System")
        tabNames.forEachIndexed { idx, title ->
            val btn = TextView(this).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(12, 22, 12, 22)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(idx) }
            }
            tabButtons.add(btn)
            tabBar.addView(btn)
        }
        mainRoot.addView(tabBar)

        addSpacer(mainRoot, 28)

        // ================= TAB CONTENT FRAME =================
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val tab1 = buildDashboardTab()
        val tab2 = buildGamingAndFpsTab()
        val tab3 = buildThermalsTab()
        val tab4 = buildSystemAndLogsTab()

        listOf(tab1, tab2, tab3, tab4).forEach { view ->
            tabContainers.add(view)
            contentFrame.addView(view)
        }

        mainRoot.addView(contentFrame)
        setContentView(mainRoot)

        selectTab(0)
        startCoreServices()
    }

    private fun selectTab(index: Int) {
        currentTabIndex = index
        tabContainers.forEachIndexed { i, view ->
            view.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        tabButtons.forEachIndexed { i, btn ->
            if (i == index) {
                btn.setTextColor(Color.WHITE)
                btn.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(COLOR_ACCENT_INDIGO, COLOR_ACCENT_CYAN)
                ).apply { cornerRadius = 100f }
            } else {
                btn.setTextColor(COLOR_TEXT_SECONDARY)
                btn.background = null
            }
        }
        when (index) {
            1 -> loadFpsSessionsIntoGraph()
            2 -> loadThermalHistoryIntoGraph()
            3 -> {
                refreshProcessInspector()
                inlineLogText.text = readActionLogs().ifBlank { "No diagnostic entries yet." }
            }
        }
    }

    // =========================================================================
    // TAB 1: OVERVIEW (Live Telemetry + Live Curve + Display Refresh Controls)
    // =========================================================================
    private fun buildDashboardTab(): ScrollView {
        val scroll = createTabScroll()
        val col = createTabColumn()

        val heroCard = createGlassCard()
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val tempBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tempBox.addView(createLabel("BATTERY TEMPERATURE"))
        tempValueText = TextView(this).apply {
            text = "--°C"
            setTextColor(COLOR_ACCENT_EMERALD)
            textSize = 32f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setPadding(0, 4, 0, 0)
        }
        tempBox.addView(tempValueText)

        val pwrBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pwrBox.addView(createLabel("REAL-TIME POWER"))
        powerValueText = TextView(this).apply {
            text = "-- W"
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 16.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 18, 0, 0)
        }
        pwrBox.addView(powerValueText)

        topRow.addView(tempBox)
        topRow.addView(pwrBox)
        heroCard.addView(topRow)

        heroCard.addView(createDivider())
        heroCard.addView(createLabel("ADRENO 650 GPU & KRYO 585 CLUSTERS"))
        gpuValueText = createMetricText("Polling GPU...")
        coresValueText = createMetricText("Polling CPU...")
        heroCard.addView(gpuValueText)
        heroCard.addView(coresValueText)

        heroCard.addView(createDivider())
        heroCard.addView(createLabel("MEMORY STATUS"))
        ramValueText = createMetricText("Polling RAM...")
        heroCard.addView(ramValueText)

        col.addView(heroCard)
        addSpacer(col, 28)

        // Live Real-Time Telemetry Graph Card
        val liveGraphCard = createGlassCard()
        liveGraphCard.addView(createLabel("LIVE TELEMETRY STREAM (TEMP °C & LIVE FPS)"))
        liveTelemetryChart = SmoothGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300).apply {
                setMargins(0, 16, 0, 4)
            }
        }
        liveGraphCard.addView(liveTelemetryChart)
        col.addView(liveGraphCard)
        addSpacer(col, 28)

        // Display Refresh Rate & Engine Sync Card
        val hzCard = createGlassCard()
        hzCard.addView(createLabel("DISPLAY REFRESH LOCK & ENGINE"))
        val hzRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            setPadding(0, 16, 0, 16)
        }
        listOf(60, 90, 120).forEach { hz ->
            val pill = createPillButton("${hz}Hz") {
                HardwareThermalControl.forceRefreshRate(hz)
                Toast.makeText(this, "Display locked to ${hz}Hz", Toast.LENGTH_SHORT).show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 6, 0)
                }
            }
            hzRow.addView(pill)
        }
        hzCard.addView(hzRow)

        val syncBtn = createAccentButton("SYNC & RESTART THERMAL ENGINE") {
            startCoreServices()
            Toast.makeText(this, "Engine Services Synced", Toast.LENGTH_SHORT).show()
        }
        hzCard.addView(syncBtn)
        col.addView(hzCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 2: GAMING & FPS (FPS Session Graph Card + Nuclear 120 FPS Controls)
    // =========================================================================
    private fun buildGamingAndFpsTab(): ScrollView {
        val scroll = createTabScroll()
        val col = createTabColumn()

        // FPS Session Graph Card
        val fpsGraphCard = createGlassCard()
        val graphHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fpsSessionTitle = TextView(this).apply {
            text = "RECORDED FPS SESSIONS"
            setTextColor(COLOR_ACCENT_EMERALD)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switchSessionBtn = createPillButton("Switch Match") {
            showFpsSessionPicker()
        }.apply { setPadding(24, 14, 24, 14) }

        graphHeaderRow.addView(fpsSessionTitle)
        graphHeaderRow.addView(switchSessionBtn)
        fpsGraphCard.addView(graphHeaderRow)

        fpsStatsRowText = TextView(this).apply {
            text = "Tap the floating FPS pill in-game to record a session."
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 12f
            setPadding(0, 10, 0, 12)
        }
        fpsGraphCard.addView(fpsStatsRowText)

        fpsChartView = SmoothGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 340)
        }
        fpsGraphCard.addView(fpsChartView)
        col.addView(fpsGraphCard)

        addSpacer(col, 28)

        // Nuclear Gaming Engine Controls Card
        val nuclearCard = createGlassCard()
        nuclearCard.addView(createLabel("NUCLEAR 120 FPS ENGINE"))

        gamingSwitch = createSwitch("Nuclear Gaming Mode", settings.gamingModeEnabled) { enabled ->
            saveBoolean("gamingModeEnabled", enabled)
            if (!enabled) HardwareThermalControl.setPeakPerformanceMode(false)
        }
        overlaySwitch = createSwitch("Floating FPS Pill Overlay", FpsOverlayService.isRunning) { enabled ->
            toggleFpsOverlay(enabled)
        }
        tempInPillSwitch = createSwitch("Show Temperature in Pill", prefs.getBoolean("showTempInFpsOverlay", true)) { enabled ->
            prefs.edit().putBoolean("showTempInFpsOverlay", enabled).apply()
        }

        nuclearCard.addView(gamingSwitch)
        nuclearCard.addView(createDivider())
        nuclearCard.addView(overlaySwitch)
        nuclearCard.addView(createDivider())
        nuclearCard.addView(tempInPillSwitch)
        nuclearCard.addView(createDivider())

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            setPadding(0, 8, 0, 0)
        }
        val gamesBtn = createPillButton("Target Games (${settings.gamingApps.size})") {
            showAppSelectorDialog("Select Target Games", "gamingApps", settings.gamingApps, true)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
        }
        throttleCapBtn = createPillButton("Throttle Cap: ${settings.gameThrottleTemp}°C") {
            showNumberDialog("Game Thermal Ceiling (°C)", settings.gameThrottleTemp, 38, 46) { v ->
                saveInt("gameThrottleTemp", v)
                throttleCapBtn.text = "Throttle Cap: ${v}°C"
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
        }
        btnRow.addView(gamesBtn)
        btnRow.addView(throttleCapBtn)
        nuclearCard.addView(btnRow)

        val exemptBtn = createPillButton("Exempt Background Apps (${settings.exemptApps.size})") {
            showAppSelectorDialog("Exempt Background Apps", "exemptApps", settings.exemptApps, false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 16, 0, 0)
            }
        }
        nuclearCard.addView(exemptBtn)
        col.addView(nuclearCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 3: THERMALS (Temperature History Graph Card + Smart Battery Limits)
    // =========================================================================
    private fun buildThermalsTab(): ScrollView {
        val scroll = createTabScroll()
        val col = createTabColumn()

        val tempGraphCard = createGlassCard()
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "TEMPERATURE HISTORY GRAPH"
            setTextColor(COLOR_ACCENT_AMBER)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val refreshBtn = createPillButton("Refresh") {
            loadThermalHistoryIntoGraph()
        }.apply { setPadding(24, 12, 24, 12) }
        headerRow.addView(refreshBtn)
        tempGraphCard.addView(headerRow)

        tempHistoryStatsText = TextView(this).apply {
            text = "Loading thermal history..."
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 12f
            setPadding(0, 8, 0, 12)
        }
        tempGraphCard.addView(tempHistoryStatsText)

        tempHistoryChart = SmoothGraphView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 340)
        }
        tempGraphCard.addView(tempHistoryChart)

        tempSnapshotDetailsText = TextView(this).apply {
            text = ""
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 11.5f
            setPadding(0, 12, 0, 0)
        }
        tempGraphCard.addView(tempSnapshotDetailsText)
        col.addView(tempGraphCard)

        addSpacer(col, 28)

        // Thermal & Charging Protection Card
        val limitsCard = createGlassCard()
        limitsCard.addView(createLabel("BATTERY BYPASS & THERMAL THRESHOLDS"))

        bypassSwitch = createSwitch("Manual Battery Bypass (Isolate Charge)", HardwareThermalControl.isManualBypassActive) { enabled ->
            HardwareThermalControl.setChargingEnabled(!enabled, isManualToggle = true)
        }
        smartGovSwitch = createSwitch("Smart Thermal Governor (Idle/Normal)", false) { enabled ->
            sendBroadcast(Intent("ACTION_TOGGLE_SMART_GOVERNOR").apply {
                setPackage(packageName)
                putExtra("state", enabled)
            })
        }
        chargeLimitSwitch = createSwitch("Enable Max Charge % Limiter", settings.chargeLimitEnabled) { enabled ->
            saveBoolean("chargeLimitEnabled", enabled)
        }

        limitsCard.addView(bypassSwitch)
        limitsCard.addView(createDivider())
        limitsCard.addView(smartGovSwitch)
        limitsCard.addView(createDivider())
        limitsCard.addView(chargeLimitSwitch)
        limitsCard.addView(createDivider())

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            setPadding(0, 8, 0, 0)
        }
        warnTempBtn = createPillButton("Warn: ${settings.warningTemp}°C") {
            showNumberDialog("Warning Alert Temp (°C)", settings.warningTemp, 36, 46) { v ->
                saveInt("warningTemp", v)
                warnTempBtn.text = "Warn: ${v}°C"
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 6, 0)
            }
        }
        cutoffTempBtn = createPillButton("Cutoff: ${settings.cutoffTemp}°C") {
            showNumberDialog("Charge Cutoff Temp (°C)", settings.cutoffTemp, 37, 46) { v ->
                saveInt("cutoffTemp", v)
                cutoffTempBtn.text = "Cutoff: ${v}°C"
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(6, 0, 6, 0)
            }
        }
        maxChargePctBtn = createPillButton("Limit: ${settings.chargeLimitMax}%") {
            showNumberDialog("Max Charge Percentage (%)", settings.chargeLimitMax, 60, 100) { v ->
                saveInt("chargeLimitMax", v)
                maxChargePctBtn.text = "Limit: ${v}%"
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(6, 0, 0, 0)
            }
        }

        row1.addView(warnTempBtn)
        row1.addView(cutoffTempBtn)
        row1.addView(maxChargePctBtn)
        limitsCard.addView(row1)

        col.addView(limitsCard)
        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 4: SYSTEM & LOGS (Kernel Process Inspector + Live Action Log Console)
    // =========================================================================
    private fun buildSystemAndLogsTab(): ScrollView {
        val scroll = createTabScroll()
        val col = createTabColumn()

        val procCard = createGlassCard()
        val procHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        procHeader.addView(TextView(this).apply {
            text = "KERNEL PROCESS & RAM INSPECTOR"
            setTextColor(COLOR_ACCENT_CYAN)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val purgeBtn = createPillButton("Drop Caches") {
            HardwareThermalControl.clearRamCaches()
            Toast.makeText(this, "RAM Caches Cleared", Toast.LENGTH_SHORT).show()
            refreshProcessInspector()
        }.apply { setPadding(22, 12, 22, 12) }
        procHeader.addView(purgeBtn)
        procCard.addView(procHeader)

        procCard.addView(TextView(this).apply {
            text = "Tap any process below to pin it to Silver Cores (0-3) or terminate it."
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 11.5f
            setPadding(0, 6, 0, 12)
        })

        processListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        procCard.addView(processListContainer)
        col.addView(procCard)

        addSpacer(col, 28)

        // Deep Diagnostic Logger Console Card
        val logCard = createGlassCard()
        logCard.addView(createLabel("DEEP DIAGNOSTIC ACTION LOG"))

        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 420).apply {
                setMargins(0, 14, 0, 16)
            }
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(Color.parseColor("#990F172A"))
                setStroke(2, COLOR_CARD_STROKE)
            }
        }
        inlineLogText = TextView(this).apply {
            text = readActionLogs().ifBlank { "No diagnostic logs yet." }
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logScroll.addView(inlineLogText)
        logCard.addView(logScroll)

        val logBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        val refreshLogBtn = createPillButton("Refresh") {
            inlineLogText.text = readActionLogs().ifBlank { "No diagnostic logs yet." }
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 6, 0)
            }
        }
        val exportLogBtn = createPillButton("Export .txt") {
            exportActionLog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(6, 0, 6, 0)
            }
        }
        val clearLogBtn = createPillButton("Clear") {
            clearActionLogs()
            inlineLogText.text = readActionLogs()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(6, 0, 0, 0)
            }
        }
        logBtns.addView(refreshLogBtn)
        logBtns.addView(exportLogBtn)
        logBtns.addView(clearLogBtn)
        logCard.addView(logBtns)

        col.addView(logCard)
        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // SQLITE DATABASE GRAPH LOADERS (FPS Sessions & Thermal History)
    // =========================================================================
    private fun loadFpsSessionsIntoGraph() {
        thread {
            val loaded = mutableListOf<FpsSessionRecord>()
            try {
                val db = dbHelper.readableDatabase
                val tablesCursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                val tables = mutableListOf<String>()
                while (tablesCursor.moveToNext()) tables.add(tablesCursor.getString(0))
                tablesCursor.close()

                val fpsTable = tables.firstOrNull { it.contains("fps", true) || it.contains("session", true) }
                if (fpsTable != null) {
                    val c = db.rawQuery("SELECT * FROM $fpsTable ORDER BY rowid DESC LIMIT 15", null)
                    while (c.moveToNext()) {
                        var app = "BGMI / Game"
                        var dur = 0; var minF = 0; var maxF = 0; var avgF = 0; var avgT = 0
                        var fpsCsv = ""; var tempCsv = ""

                        for (i in 0 until c.columnCount) {
                            val col = c.getColumnName(i).lowercase()
                            val strVal = c.getString(i) ?: ""
                            when {
                                col.contains("app") || col.contains("pkg") || col.contains("name") -> app = strVal
                                col.contains("dur") -> dur = strVal.toIntOrNull() ?: 0
                                col.contains("min") -> minF = strVal.toIntOrNull() ?: 0
                                col.contains("max") -> maxF = strVal.toIntOrNull() ?: 0
                                col.contains("avg") && !col.contains("temp") && col != "avgt" -> avgF = strVal.toIntOrNull() ?: 0
                                col.contains("avgt") || (col.contains("avg") && col.contains("temp")) -> avgT = strVal.toIntOrNull() ?: 0
                                strVal.contains(",") && fpsCsv.isEmpty() -> fpsCsv = strVal
                                strVal.contains(",") && fpsCsv.isNotEmpty() -> tempCsv = strVal
                            }
                        }
                        val fpsList = fpsCsv.split(",").mapNotNull { it.trim().toFloatOrNull() }
                        val tempList = tempCsv.split(",").mapNotNull { it.trim().toFloatOrNull() }
                        if (fpsList.isNotEmpty()) {
                            loaded.add(
                                FpsSessionRecord(
                                    appName = app,
                                    durationSec = dur,
                                    minFps = if (minF > 0) minF else (fpsList.minOrNull()?.toInt() ?: 0),
                                    maxFps = if (maxF > 0) maxF else (fpsList.maxOrNull()?.toInt() ?: 0),
                                    avgFps = if (avgF > 0) avgF else fpsList.average().toInt(),
                                    avgTemp = if (avgT > 0) avgT else (tempList.average().takeIf { !it.isNaN() }?.toInt() ?: 0),
                                    fpsSeries = fpsList,
                                    tempSeries = tempList
                                )
                            )
                        }
                    }
                    c.close()
                }
            } catch (e: Exception) {}

            uiHandler.post {
                fpsSessions = loaded
                if (fpsSessions.isNotEmpty()) {
                    selectedFpsSessionIdx = selectedFpsSessionIdx.coerceIn(0, fpsSessions.size - 1)
                    renderSelectedFpsSession()
                } else {
                    fpsSessionTitle.text = "LIVE / DEMO 120 FPS CURVE"
                    fpsStatsRowText.text = "No recorded sessions yet — tap the Floating FPS Pill in-game to record."
                    fpsChartView.setData(
                        primary = if (liveFpsBuffer.size >= 2) liveFpsBuffer else listOf(115f, 118f, 120f, 116f, 119f, 114f, 118f),
                        secondary = liveTempBuffer,
                        primaryColor = COLOR_ACCENT_EMERALD,
                        secondaryColor = COLOR_ACCENT_AMBER,
                        unitSuffix = " FPS"
                    )
                }
            }
        }
    }

    private fun renderSelectedFpsSession() {
        if (fpsSessions.isEmpty()) return
        val s = fpsSessions[selectedFpsSessionIdx]
        val mins = s.durationSec / 60
        val secs = s.durationSec % 60
        fpsSessionTitle.text = "${s.appName.uppercase()}  •  #${selectedFpsSessionIdx + 1} (${mins}m ${secs}s)"
        fpsStatsRowText.text = "AVG: ${s.avgFps} FPS   •   PEAK: ${s.maxFps} FPS   •   MIN: ${s.minFps} FPS   •   TEMP: ${s.avgTemp}°C"
        fpsChartView.setData(
            primary = s.fpsSeries,
            secondary = s.tempSeries,
            primaryColor = COLOR_ACCENT_EMERALD,
            secondaryColor = COLOR_ACCENT_AMBER,
            unitSuffix = " FPS"
        )
    }

    private fun showFpsSessionPicker() {
        if (fpsSessions.isEmpty()) {
            Toast.makeText(this, "No recorded FPS sessions found yet", Toast.LENGTH_SHORT).show()
            return
        }
        val items = fpsSessions.mapIndexed { idx, s ->
            "#${idx + 1} ${s.appName} — Avg ${s.avgFps} FPS | Peak ${s.maxFps} FPS (${s.avgTemp}°C)"
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Recorded Match")
            .setItems(items) { _, which ->
                selectedFpsSessionIdx = which
                renderSelectedFpsSession()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun loadThermalHistoryIntoGraph() {
        thread {
            val records = mutableListOf<TempHistoryRecord>()
            try {
                val db = dbHelper.readableDatabase
                val tablesCursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                val tables = mutableListOf<String>()
                while (tablesCursor.moveToNext()) tables.add(tablesCursor.getString(0))
                tablesCursor.close()

                val tempTable = tables.firstOrNull {
                    !it.contains("fps", true) && !it.contains("sqlite", true) && !it.contains("android", true)
                }
                if (tempTable != null) {
                    val c = db.rawQuery("SELECT * FROM $tempTable ORDER BY rowid DESC LIMIT 50", null)
                    while (c.moveToNext()) {
                        var t = 0
                        var charging = false
                        var details = ""
                        for (i in 0 until c.columnCount) {
                            val col = c.getColumnName(i).lowercase()
                            val v = c.getString(i) ?: ""
                            when {
                                col.contains("temp") -> t = v.toIntOrNull() ?: 0
                                col.contains("charg") && v.length <= 2 -> charging = (v == "1" || v.equals("true", true))
                                col.contains("detail") || v.contains("CPU") -> details = v
                            }
                        }
                        if (t in 15..75) records.add(TempHistoryRecord(t, charging, details))
                    }
                    c.close()
                }
            } catch (e: Exception) {}

            val chronological = records.reversed()
            uiHandler.post {
                if (chronological.isNotEmpty()) {
                    val temps = chronological.map { it.temp.toFloat() }
                    val minT = temps.minOrNull()?.toInt() ?: 0
                    val maxT = temps.maxOrNull()?.toInt() ?: 0
                    val avgT = temps.average().toInt()
                    tempHistoryStatsText.text = "SAMPLES: ${temps.size}   •   AVG: ${avgT}°C   •   PEAK: ${maxT}°C   •   LOW: ${minT}°C"
                    tempHistoryChart.setData(
                        primary = temps,
                        secondary = emptyList(),
                        primaryColor = COLOR_ACCENT_AMBER,
                        secondaryColor = COLOR_ACCENT_ROSE,
                        unitSuffix = "°C"
                    )
                    val latestDetails = chronological.lastOrNull { it.details.isNotBlank() }?.details ?: ""
                    tempSnapshotDetailsText.text = if (latestDetails.isNotBlank()) {
                        "Latest Kernel Snapshot:\n$latestDetails"
                    } else ""
                } else {
                    val fallback = if (liveTempBuffer.size >= 2) liveTempBuffer else listOf(34f, 35f, 36f, 36f, 37f)
                    tempHistoryStatsText.text = "Showing live session thermal curve"
                    tempHistoryChart.setData(
                        primary = fallback,
                        secondary = emptyList(),
                        primaryColor = COLOR_ACCENT_AMBER,
                        secondaryColor = COLOR_ACCENT_ROSE,
                        unitSuffix = "°C"
                    )
                }
            }
        }
    }

    private fun refreshProcessInspector() {
        processListContainer.removeAllViews()
        thread {
            val procs = HardwareThermalControl.getKernelProcessSnapshot(applicationContext)
            val ramProcs = HardwareThermalControl.getDetailedRam().take(6)
            uiHandler.post {
                processListContainer.removeAllViews()
                if (procs.isEmpty() && ramProcs.isEmpty()) {
                    processListContainer.addView(createMetricText("No high-load processes active."))
                    return@post
                }
                procs.forEach { p ->
                    val row = createProcessRow("${p.name}  •  ${p.cpu}% CPU", "PID ${p.pid}") {
                        showProcessActionDialog(p.name, p.pid)
                    }
                    processListContainer.addView(row)
                }
                ramProcs.forEach { r ->
                    val row = createProcessRow("${r.name}  •  ${r.sizeMb} MB RAM", "PID ${r.pid}") {
                        showProcessActionDialog(r.name, r.pid)
                    }
                    processListContainer.addView(row)
                }
            }
        }
    }

    private fun createProcessRow(title: String, badge: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 6, 0, 6)
            }
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(COLOR_PILL_BG)
            }
            addView(TextView(context).apply {
                text = title
                setTextColor(COLOR_TEXT_PRIMARY)
                textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = badge
                setTextColor(COLOR_ACCENT_CYAN)
                textSize = 11f
                typeface = Typeface.MONOSPACE
            })
            setOnClickListener { onClick() }
        }
    }

    private fun showProcessActionDialog(name: String, pid: Int) {
        val actions = arrayOf("Pin to Silver Efficiency Cores (0-3)", "Force Kill Process (PID $pid)")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(name)
            .setItems(actions) { _, which ->
                if (which == 0) {
                    HardwareThermalControl.pinProcessToEfficiencyCores(pid)
                    Toast.makeText(this, "Pinned $name to Silver Cores", Toast.LENGTH_SHORT).show()
                } else {
                    HardwareThermalControl.killProcess(pid)
                    Toast.makeText(this, "Terminated PID $pid", Toast.LENGTH_SHORT).show()
                    refreshProcessInspector()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // CUSTOM SMOOTH BEZIER GRAPH VIEW (Supports Dual Series + Gradient Fill)
    // =========================================================================
    inner class SmoothGraphView(context: Context) : View(context) {
        private var primaryData: List<Float> = emptyList()
        private var secondaryData: List<Float> = emptyList()
        private var primaryColor: Int = COLOR_ACCENT_EMERALD
        private var secondaryColor: Int = COLOR_ACCENT_AMBER
        private var unitSuffix: String = ""

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2694A3B8")
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_SECONDARY
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 6f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val secondaryLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 4f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        fun setData(
            primary: List<Float>,
            secondary: List<Float>,
            primaryColor: Int,
            secondaryColor: Int,
            unitSuffix: String
        ) {
            this.primaryData = primary
            this.secondaryData = secondary
            this.primaryColor = primaryColor
            this.secondaryColor = secondaryColor
            this.unitSuffix = unitSuffix
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f || primaryData.isEmpty()) return

            val padLeft = 88f
            val padRight = 28f
            val padTop = 28f
            val padBottom = 36f
            val chartW = w - padLeft - padRight
            val chartH = h - padTop - padBottom

            val rawMin = primaryData.minOrNull() ?: 0f
            val rawMax = primaryData.maxOrNull() ?: 100f
            val minVal = max(0f, rawMin - (rawMax - rawMin).coerceAtLeast(6f) * 0.15f)
            val maxVal = rawMax + (rawMax - rawMin).coerceAtLeast(6f) * 0.15f
            val range = (maxVal - minVal).coerceAtLeast(1f)

            // Draw 4 Horizontal Grid Lines & Y-Axis Labels
            for (i in 0..3) {
                val frac = i / 3f
                val y = padTop + chartH * frac
                canvas.drawLine(padLeft, y, w - padRight, y, gridPaint)
                val labelVal = (maxVal - range * frac).toInt()
                canvas.drawText("$labelVal$unitSuffix", 8f, y + 8f, labelPaint)
            }

            val pts = primaryData.mapIndexed { idx, v ->
                val x = if (primaryData.size > 1) padLeft + (idx.toFloat() / (primaryData.size - 1)) * chartW else padLeft + chartW / 2f
                val y = padTop + chartH * (1f - ((v - minVal) / range).coerceIn(0f, 1f))
                PointF(x, y)
            }

            // Build Smooth Cubic Bezier Path
            val linePath = Path()
            val fillPath = Path()
            linePath.moveTo(pts.first().x, pts.first().y)
            fillPath.moveTo(pts.first().x, padTop + chartH)
            fillPath.lineTo(pts.first().x, pts.first().y)

            for (i in 0 until pts.size - 1) {
                val p0 = pts[i]
                val p1 = pts[i + 1]
                val cx = (p0.x + p1.x) / 2f
                linePath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                fillPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
            }
            fillPath.lineTo(pts.last().x, padTop + chartH)
            fillPath.close()

            fillPaint.shader = LinearGradient(
                0f, padTop, 0f, padTop + chartH,
                Color.argb(95, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor)),
                Color.argb(0, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor)),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)

            linePaint.color = primaryColor
            canvas.drawPath(linePath, linePaint)

            // Optional Secondary Series Overlay
            if (secondaryData.size >= 2) {
                val sMin = (secondaryData.minOrNull() ?: 0f) - 2f
                val sMax = (secondaryData.maxOrNull() ?: 100f) + 2f
                val sRange = (sMax - sMin).coerceAtLeast(1f)
                val sPath = Path()
                secondaryData.forEachIndexed { idx, v ->
                    val x = padLeft + (idx.toFloat() / (secondaryData.size - 1)) * chartW
                    val y = padTop + chartH * (1f - ((v - sMin) / sRange).coerceIn(0f, 1f))
                    if (idx == 0) sPath.moveTo(x, y) else sPath.lineTo(x, y)
                }
                secondaryLinePaint.color = secondaryColor
                canvas.drawPath(sPath, secondaryLinePaint)
            }

            // Highlight Last Point
            val lastPt = pts.last()
            dotPaint.color = primaryColor
            canvas.drawCircle(lastPt.x, lastPt.y, 9f, dotPaint)
            dotPaint.color = COLOR_TEXT_PRIMARY
            canvas.drawCircle(lastPt.x, lastPt.y, 4f, dotPaint)
        }
    }

    // =========================================================================
    // DIALOGS, LOGGING & PREFERENCE HELPERS
    // =========================================================================
    private fun ensureDefaultGameConfigured() {
        if (settings.gamingApps.isEmpty()) {
            saveStringSet("gamingApps", setOf("com.pubg.imobile", "com.tencent.ig"))
        }
        if (settings.gameThrottleTemp < 42) {
            saveInt("gameThrottleTemp", 43)
        }
    }

    private fun readActionLogs(): String {
        try {
            val methods = listOf("getLogs", "getLogText", "readLogs", "getAllLogs")
            for (mName in methods) {
                val m = AppLogger.javaClass.methods.firstOrNull { it.name == mName && it.parameterCount == 0 }
                if (m != null) {
                    val res = m.invoke(AppLogger)
                    if (res is String && res.isNotEmpty()) return res
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
        } catch (e: Exception) {}
        return ""
    }

    private fun clearActionLogs() {
        try {
            AppLogger.javaClass.methods.firstOrNull {
                it.name.contains("clear", true) && it.parameterCount == 0
            }?.invoke(AppLogger)
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

    private fun exportActionLog() {
        try {
            val content = readActionLogs()
            val fileName = "ActionLog_${System.currentTimeMillis()}.txt"
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeText(content)
            Toast.makeText(this, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppSelectorDialog(title: String, key: String, currentSet: Set<String>, includeBgmi: Boolean) {
        thread {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("pubg") || it.packageName.contains("tencent") }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }

            val labels = apps.map { "${it.loadLabel(pm)} (${it.packageName})" }.toTypedArray()
            val pkgs = apps.map { it.packageName }
            val checked = BooleanArray(pkgs.size) { currentSet.contains(pkgs[it]) }
            val selected = currentSet.toMutableSet()

            uiHandler.post {
                val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        if (isChecked) selected.add(pkgs[which]) else selected.remove(pkgs[which])
                    }
                    .setPositiveButton("Save") { _, _ ->
                        saveStringSet(key, selected)
                        recreate()
                    }
                    .setNegativeButton("Cancel", null)

                if (includeBgmi) {
                    b.setNeutralButton("+ Add BGMI") { _, _ ->
                        selected.add("com.pubg.imobile")
                        saveStringSet(key, selected)
                        recreate()
                    }
                }
                b.show()
            }
        }
    }

    private fun showNumberDialog(title: String, current: Int, minVal: Int, maxVal: Int, onSave: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setTextColor(Color.WHITE)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("$title ($minVal - $maxVal)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().toIntOrNull()?.coerceIn(minVal, maxVal) ?: current
                onSave(v)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        try {
            settings.javaClass.methods.firstOrNull {
                it.name.equals("set${key.replaceFirstChar { c -> c.uppercase() }}", true) && it.parameterCount == 1
            }?.invoke(settings, value)
        } catch (e: Exception) {}
    }

    private fun saveInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        try {
            settings.javaClass.methods.firstOrNull {
                it.name.equals("set${key.replaceFirstChar { c -> c.uppercase() }}", true) && it.parameterCount == 1
            }?.invoke(settings, value)
        } catch (e: Exception) {}
    }

    private fun saveStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
        try {
            settings.javaClass.methods.firstOrNull {
                it.name.equals("set${key.replaceFirstChar { c -> c.uppercase() }}", true) && it.parameterCount == 1
            }?.invoke(settings, value)
        } catch (e: Exception) {}
    }

    // =========================================================================
    // UI COMPONENT BUILDERS
    // =========================================================================
    private fun createTabScroll(): ScrollView {
        return ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
    }

    private fun createTabColumn(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 4, 4, 40)
        }
    }

    private fun createGlassCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 36, 42, 36)
            background = GradientDrawable().apply {
                cornerRadius = 36f
                setColor(COLOR_CARD_BG)
                setStroke(2, COLOR_CARD_STROKE)
            }
        }
    }

    private fun createLabel(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.07f
        }
    }

    private fun createMetricText(initial: String): TextView {
        return TextView(this).apply {
            text = initial
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 8, 0, 2)
        }
    }

    private fun createSwitch(title: String, initialValue: Boolean, onToggle: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            text = title
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isChecked = initialValue
            setPadding(0, 20, 0, 20)
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
    }

    private fun createPillButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 12.5f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(COLOR_PILL_BG)
                setStroke(2, COLOR_CARD_STROKE)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createAccentButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(24, 34, 24, 34)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(COLOR_ACCENT_INDIGO, COLOR_ACCENT_CYAN)
            ).apply { cornerRadius = 100f }
            setOnClickListener { onClick() }
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 16, 0, 16)
            }
            setBackgroundColor(Color.parseColor("#1F94A3B8"))
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
        if (enable) startService(Intent(this, FpsOverlayService::class.java))
        else stopService(Intent(this, FpsOverlayService::class.java))
    }

    private fun startCoreServices() {
        try {
            val intent = Intent(this, TempMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            updateServiceBadge()
        } catch (e: Exception) {}
    }

    private fun updateServiceBadge() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == TempMonitorService::class.java.name
        }
        statusBadge.text = if (running) "ENGINE ACTIVE" else "STOPPED"
        statusBadge.background = GradientDrawable().apply {
            cornerRadius = 100f
            setColor(if (running) Color.parseColor("#2610B981") else Color.parseColor("#26F43F5E"))
            setStroke(2, if (running) Color.parseColor("#8010B981") else Color.parseColor("#80F43F5E"))
        }
    }

    override fun onResume() {
        super.onResume()
        isUiActive = true
        overlaySwitch.isChecked = FpsOverlayService.isRunning
        bypassSwitch.isChecked = HardwareThermalControl.isManualBypassActive
        uiHandler.post(telemetryLoop)
    }

    override fun onPause() {
        super.onPause()
        isUiActive = false
        uiHandler.removeCallbacks(telemetryLoop)
    }
}
