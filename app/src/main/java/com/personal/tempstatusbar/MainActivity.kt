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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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

    data class ThemePalette(
        val name: String,
        val bgTop: Int,
        val bgBottom: Int,
        val cardBg: Int,
        val cardStroke: Int,
        val pillBg: Int,
        val navBarBg: Int,
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
    private val navTabContainers = mutableListOf<LinearLayout>()
    private val navTabLabels = mutableListOf<TextView>()
    private val navTabIndicators = mutableListOf<View>()

    // Access Chips
    private lateinit var rootChip: TextView
    private lateinit var usageChip: TextView
    private lateinit var notifChip: TextView
    private lateinit var overlayChip: TextView

    // Tab 1: Monitor
    private lateinit var heroTempText: TextView
    private lateinit var heroStateText: TextView
    private lateinit var heroWattText: TextView
    private lateinit var tempGraphView: InteractiveGraphView
    private lateinit var tempScrubReadout: TextView
    private lateinit var tempSnapshotBox: TextView
    private var thermalHistoryList = listOf<ThermalPoint>()

    // Tab 2: Hardware
    private lateinit var hwGpuText: TextView
    private lateinit var hwRamText: TextView
    private val coreFreqLabels = mutableListOf<TextView>()

    // Tab 3: Gaming & FPS
    private lateinit var fpsGraphView: InteractiveGraphView
    private lateinit var fpsSessionHeader: TextView
    private lateinit var fpsScrubReadout: TextView
    private var fpsSessionsList = listOf<FpsSessionData>()
    private var currentFpsSessionIdx = 0

    // Tab 4: Tasks & Logs
    private lateinit var processContainer: LinearLayout
    private lateinit var logConsoleText: TextView
    private lateinit var logScrollBox: ScrollView
    private lateinit var autoTestStatusBanner: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isUiActive = false

    data class ThermalPoint(val temp: Int, val chargeType: String, val details: String)
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
                    heroStateText.text = when {
                        HardwareThermalControl.isManualBypassActive -> "🛡️ BYPASS ISOLATED ($level%)"
                        pwr.isCharging -> "⚡ CHARGING ($level%)"
                        else -> "🔋 DISCHARGING ($level%)"
                    }
                    val sign = if (pwr.isCharging) "+" else "-"
                    heroWattText.text = "$sign${pwr.wattage}W  •  ${abs(pwr.currentMa)} mA"

                    hwGpuText.text = "Load: $gpuLoad  •  Clock: $gpuFreq"
                    hwRamText.text = ram.replace("\n", "  •  ")

                    for (i in 0 until minOf(cores.size, coreFreqLabels.size)) {
                        val tag = if (i <= 3) "Silver" else if (i <= 6) "Gold" else "Prime"
                        coreFreqLabels[i].text = "Core $i ($tag): ${cores[i]}"
                    }

                    if (currentTabIndex == 3) {
                        logConsoleText.text = AppLogger.getLogs()
                    }
                }
            }
            uiHandler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(applicationContext)
        settings = SettingsManager(this)
        dbHelper = DatabaseHelper(this)
        prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)
        HardwareThermalControl.init(this)

        if (settings.gamingApps.isEmpty()) {
            settings.gamingApps = setOf("com.pubg.imobile", "com.tencent.ig")
        }
        if (settings.gameThrottleTemp < 42) {
            settings.gameThrottleTemp = 43
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        val savedTheme = prefs.getString("appThemeMode", "DEFAULT") ?: "DEFAULT"
        theme = resolveTheme(savedTheme)

        AppLogger.log("📱 [UI] MainActivity launched | Theme: ${theme.name}")
        buildFullInterface()
        startAndSyncService()
    }

    // =========================================================================
    // HANDS-FREE AUTOMATED TEST RUNNER UI
    // =========================================================================
    private fun startHandsFreeAutoTest() {
        if (HardwareThermalControl.isAutoTestRunning) {
            Toast.makeText(this, "Automated test is already running...", Toast.LENGTH_SHORT).show()
            return
        }

        // Switch straight to Logs & Tasks tab so user can watch live execution hands-free
        switchTab(3, animate = true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val hasUsage = hasUsageStatsPermission()
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasNotif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        autoTestStatusBanner.visibility = View.VISIBLE
        autoTestStatusBanner.text = "🤖 HANDS-FREE AUTO-TEST RUNNING...\nKeep phone untouched (~18s)"

        HardwareThermalControl.runAutomatedTestSuite(
            context = applicationContext,
            hasUsagePerm = hasUsage,
            hasOverlayPerm = hasOverlay,
            hasNotifPerm = hasNotif,
            onProgress = { stage, total, title ->
                uiHandler.post {
                    autoTestStatusBanner.text = "🤖 STAGE $stage/$total — KEEP PHONE UNTOUCHED\n$title"
                    logConsoleText.text = AppLogger.getLogs()
                    logScrollBox.post { logScrollBox.fullScroll(View.FOCUS_DOWN) }
                }
            },
            onFinished = { passed, failed, manualActions, exportedFile ->
                uiHandler.post {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    hapticTap()
                    autoTestStatusBanner.text = "🏁 AUTO-TEST DONE: $passed PASSED • $failed FAILED\nSaved: $exportedFile"
                    logConsoleText.text = AppLogger.getLogs()
                    logScrollBox.post { logScrollBox.fullScroll(View.FOCUS_DOWN) }

                    val summaryMsg = StringBuilder()
                    summaryMsg.append("✅ Passed Checks: $passed\n")
                    summaryMsg.append("❌ Failed Checks: $failed\n")
                    summaryMsg.append("💾 Auto-Saved Report:\n$exportedFile\n\n")
                    if (manualActions.isNotEmpty()) {
                        summaryMsg.append("⚠️ Manual Actions Needed:\n")
                        manualActions.forEachIndexed { idx, act ->
                            summaryMsg.append("${idx + 1}. $act\n")
                        }
                    } else {
                        summaryMsg.append("✨ All permissions & hardware controls verified!")
                    }

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("🤖 Hands-Free Auto-Test Report")
                        .setMessage(summaryMsg.toString().trim())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
    }

    // =========================================================================
    // EXECUTIVE THEME ENGINE (Default, Material You, Light, Dark)
    // =========================================================================
    private fun resolveTheme(mode: String): ThemePalette {
        val sysNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return when (mode) {
            "LIGHT" -> ThemePalette(
                name = "Light",
                bgTop = Color.parseColor("#F8FAFC"),
                bgBottom = Color.parseColor("#EEF2F6"),
                cardBg = Color.parseColor("#FFFFFF"),
                cardStroke = Color.parseColor("#E2E8F0"),
                pillBg = Color.parseColor("#F1F5F9"),
                navBarBg = Color.parseColor("#FFFFFF"),
                textPrimary = Color.parseColor("#0F172A"),
                textSecondary = Color.parseColor("#64748B"),
                accentPrimary = Color.parseColor("#2563EB"),
                accentSecondary = Color.parseColor("#3B82F6"),
                good = Color.parseColor("#059669"),
                warn = Color.parseColor("#D97706"),
                danger = Color.parseColor("#DC2626"),
                isLight = true
            )
            "DARK" -> ThemePalette(
                name = "Dark",
                bgTop = Color.parseColor("#090A0F"),
                bgBottom = Color.parseColor("#11131A"),
                cardBg = Color.parseColor("#171922"),
                cardStroke = Color.parseColor("#262936"),
                pillBg = Color.parseColor("#202430"),
                navBarBg = Color.parseColor("#14161E"),
                textPrimary = Color.parseColor("#F3F4F6"),
                textSecondary = Color.parseColor("#9CA3AF"),
                accentPrimary = Color.parseColor("#6366F1"),
                accentSecondary = Color.parseColor("#818CF8"),
                good = Color.parseColor("#10B981"),
                warn = Color.parseColor("#F59E0B"),
                danger = Color.parseColor("#EF4444"),
                isLight = false
            )
            "MATERIAL_YOU" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (sysNight) {
                        ThemePalette(
                            name = "Material You",
                            bgTop = getColor(android.R.color.system_neutral1_900),
                            bgBottom = getColor(android.R.color.system_neutral1_800),
                            cardBg = getColor(android.R.color.system_neutral2_800),
                            cardStroke = getColor(android.R.color.system_neutral2_600),
                            pillBg = getColor(android.R.color.system_neutral1_900),
                            navBarBg = getColor(android.R.color.system_neutral1_900),
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
                            name = "Material You",
                            bgTop = getColor(android.R.color.system_neutral1_50),
                            bgBottom = getColor(android.R.color.system_neutral1_100),
                            cardBg = getColor(android.R.color.system_neutral1_0),
                            cardStroke = getColor(android.R.color.system_neutral2_200),
                            pillBg = getColor(android.R.color.system_neutral1_100),
                            navBarBg = getColor(android.R.color.system_neutral1_0),
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
                } else resolveTheme("DEFAULT")
            }
            else -> ThemePalette(
                name = "Default",
                bgTop = Color.parseColor("#111318"),
                bgBottom = Color.parseColor("#181C24"),
                cardBg = Color.parseColor("#1E232D"),
                cardStroke = Color.parseColor("#2E3646"),
                pillBg = Color.parseColor("#262C3A"),
                navBarBg = Color.parseColor("#161922"),
                textPrimary = Color.parseColor("#F9FAFB"),
                textSecondary = Color.parseColor("#9CA3AF"),
                accentPrimary = Color.parseColor("#3B82F6"),
                accentSecondary = Color.parseColor("#60A5FA"),
                good = Color.parseColor("#10B981"),
                warn = Color.parseColor("#F59E0B"),
                danger = Color.parseColor("#F43F5E"),
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

    private fun attachSpringAnimation(view: View, onClick: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator(2.2f)).start()
                    hapticTap(v)
                    onClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }
            }
            true
        }
    }

    // =========================================================================
    // MAIN UI LAYOUT WITH BOTTOM NAVIGATION BAR
    // =========================================================================
    private fun buildFullInterface() {
        tabViews.clear()
        navTabContainers.clear()
        navTabLabels.clear()
        navTabIndicators.clear()
        coreFreqLabels.clear()

        try {
            window.statusBarColor = theme.bgTop
            window.navigationBarColor = theme.navBarBg
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (theme.isLight) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
            }
        } catch (e: Exception) {}

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(theme.bgTop, theme.bgBottom))
        }

        val mainBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(34, 34, 34, 0)
        }

        // 1. Header Bar with Auto-Test & Theme Buttons
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
            textSize = 19.5f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.02f
        })
        titleCol.addView(TextView(this).apply {
            text = "${HardwareThermalControl.getHardwareInfo()} • ${theme.name}"
            setTextColor(theme.textSecondary)
            textSize = 11.5f
            setPadding(0, 3, 0, 0)
        })

        val autoTestTopBtn = createPillButton("🤖 Auto-Test") {
            startHandsFreeAutoTest()
        }.apply {
            setPadding(24, 15, 24, 15)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 10, 0)
            }
        }

        val themeBtn = createPillButton("Theme") {
            AppLogger.logUi("Theme Button", "Opened Theme Selector")
            showThemePickerDialog()
        }.apply { setPadding(24, 15, 24, 15) }

        header.addView(titleCol)
        header.addView(autoTestTopBtn)
        header.addView(themeBtn)
        mainBody.addView(header)

        addSpace(mainBody, 18)

        // 2. Access Status Strip
        val accessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        rootChip = createAccessChip("ROOT") {
            AppLogger.logUi("Access Chip", "Tapped ROOT verification chip")
            thread {
                val ok = HardwareThermalControl.isRootAvailable()
                uiHandler.post {
                    updateAccessChips(ok)
                    Toast.makeText(this, if (ok) "Root Verified (uid=0)" else "Root Denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
        usageChip = createAccessChip("USAGE") {
            AppLogger.logUi("Access Chip", "Opened Usage Access Settings")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        notifChip = createAccessChip("ICON") {
            AppLogger.logUi("Access Chip", "Triggered Status Bar Icon Refresh")
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } else {
                settings.showNotification = true
                startAndSyncService()
                Toast.makeText(this, "Status Bar Temperature Active", Toast.LENGTH_SHORT).show()
            }
        }
        overlayChip = createAccessChip("PILL") {
            AppLogger.logUi("Access Chip", "Toggled Floating FPS Pill")
            toggleOverlayPill(!FpsOverlayService.isRunning)
        }
        accessRow.addView(rootChip)
        accessRow.addView(usageChip)
        accessRow.addView(notifChip)
        accessRow.addView(overlayChip)
        mainBody.addView(accessRow)

        addSpace(mainBody, 18)

        // 3. Center Tab View Container
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
        mainBody.addView(contentFrame)
        root.addView(mainBody)

        // 4. Docked Bottom Navigation Bar
        val bottomNavWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(theme.navBarBg)
                setStroke(2, theme.cardStroke)
            }
        }
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            setPadding(16, 14, 16, 18)
        }

        val navItems = listOf(
            "Monitor" to "🌡️",
            "Hardware" to "⚙️",
            "Gaming" to "🚀",
            "Logs & Tasks" to "📋"
        )

        navItems.forEachIndexed { idx, (label, icon) ->
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(8, 12, 8, 12)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(6, 0, 6, 0)
                }
            }
            val indicator = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(48, 6).apply { setMargins(0, 0, 0, 8) }
                background = GradientDrawable().apply {
                    cornerRadius = 10f
                    setColor(theme.accentPrimary)
                }
            }
            val txt = TextView(this).apply {
                text = "$icon  $label"
                textSize = 11.5f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            itemLayout.addView(indicator)
            itemLayout.addView(txt)

            attachSpringAnimation(itemLayout) {
                if (currentTabIndex != idx) {
                    AppLogger.logUi("Bottom Nav", "Switched to tab [$label]")
                    switchTab(idx, animate = true)
                }
            }

            navTabContainers.add(itemLayout)
            navTabLabels.add(txt)
            navTabIndicators.add(indicator)
            bottomNav.addView(itemLayout)
        }

        bottomNavWrapper.addView(bottomNav)
        root.addView(bottomNavWrapper)

        setContentView(root)
        switchTab(currentTabIndex, animate = false)
    }

    private fun switchTab(idx: Int, animate: Boolean) {
        currentTabIndex = idx
        tabViews.forEachIndexed { i, v ->
            if (i == idx) {
                v.visibility = View.VISIBLE
                if (animate) {
                    v.alpha = 0f
                    v.translationY = 28f
                    v.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            } else {
                v.visibility = View.GONE
            }
        }
        navTabContainers.forEachIndexed { i, container ->
            val active = (i == idx)
            navTabIndicators[i].visibility = if (active) View.VISIBLE else View.INVISIBLE
            navTabLabels[i].setTextColor(if (active) theme.accentPrimary else theme.textSecondary)
            container.background = if (active) {
                GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(Color.argb(28, Color.red(theme.accentPrimary), Color.green(theme.accentPrimary), Color.blue(theme.accentPrimary)))
                }
            } else null
        }

        when (idx) {
            0 -> loadThermalGraphData()
            2 -> loadFpsGraphData()
            3 -> {
                refreshProcessesList()
                logConsoleText.text = AppLogger.getLogs()
                logScrollBox.post { logScrollBox.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    // =========================================================================
    // TAB 1: MONITOR
    // =========================================================================
    private fun buildMonitorTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

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
            AppLogger.logUi("Status Bar Temp Switch", if (on) "ENABLED" else "DISABLED")
            settings.showNotification = on
            startAndSyncService()
        })
        heroCard.addView(createSwitch("Show Live Wattage & mA in Notification", settings.showPowerMetrics) { on ->
            AppLogger.logUi("Notification Power Metrics Switch", if (on) "ENABLED" else "DISABLED")
            settings.showPowerMetrics = on
            startAndSyncService()
        })
        heroCard.addView(createSwitch("Battery Bypass (Isolate Charger)", HardwareThermalControl.isManualBypassActive) { on ->
            AppLogger.logUi("Battery Bypass Switch", if (on) "ENGAGED" else "RELEASED")
            HardwareThermalControl.setChargingEnabled(!on, isManualToggle = true)
            startAndSyncService()
        })
        col.addView(heroCard)

        addSpace(col, 22)

        val graphCard = createCard()
        val gHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gHead.addView(TextView(this).apply {
            text = "INTERACTIVE TEMPERATURE GRAPH (DRAG TO SCRUB)"
            setTextColor(theme.accentPrimary)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        gHead.addView(createPillButton("↻") {
            AppLogger.logUi("Thermal Graph", "Manual Refresh")
            loadThermalGraphData()
        }.apply { setPadding(22, 10, 22, 10) })
        graphCard.addView(gHead)

        tempScrubReadout = TextView(this).apply {
            text = "Drag across the graph to inspect historical temps & CPU load"
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 330)
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

        val threshCard = createCard()
        threshCard.addView(createSectionLabel("THERMAL & CHARGING THRESHOLDS"))
        threshCard.addView(createSwitch("Enable Max Charge % Cutoff", settings.chargeLimitEnabled) { on ->
            AppLogger.logUi("Max Charge % Cutoff Switch", if (on) "ENABLED (${settings.chargeLimitMax}%)" else "DISABLED")
            settings.chargeLimitEnabled = on
        })

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; setPadding(0, 10, 0, 10) }
        lateinit var warnBtn: TextView
        lateinit var cutBtn: TextView
        lateinit var resBtn: TextView

        warnBtn = createPillButton("Warn: ${settings.warningTemp}°C") {
            showNumberDialog("Overheat Warning (°C)", settings.warningTemp, 36, 48) { v ->
                settings.warningTemp = v; warnBtn.text = "Warn: ${v}°C"
                AppLogger.logUi("Threshold Change", "Warning Temp set to ${v}°C")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } }

        cutBtn = createPillButton("Cutoff: ${settings.cutoffTemp}°C") {
            showNumberDialog("Charge Cutoff (°C)", settings.cutoffTemp, 37, 48) { v ->
                settings.cutoffTemp = v; cutBtn.text = "Cutoff: ${v}°C"
                AppLogger.logUi("Threshold Change", "Charge Cutoff Temp set to ${v}°C")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 6, 0) } }

        resBtn = createPillButton("Resume: ${settings.resumeTemp}°C") {
            showNumberDialog("Charge Resume (°C)", settings.resumeTemp, 34, 45) { v ->
                settings.resumeTemp = v; resBtn.text = "Resume: ${v}°C"
                AppLogger.logUi("Threshold Change", "Charge Resume Temp set to ${v}°C")
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
                AppLogger.logUi("Threshold Change", "Max Charge % set to ${v}%")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } }

        resPctBtn = createPillButton("Resume Pct: ${settings.chargeLimitResume}%") {
            showNumberDialog("Resume Charging At (%)", settings.chargeLimitResume, 50, 95) { v ->
                settings.chargeLimitResume = v; resPctBtn.text = "Resume Pct: ${v}%"
                AppLogger.logUi("Threshold Change", "Resume Charge % set to ${v}%")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } }

        row2.addView(maxPctBtn); row2.addView(resPctBtn)
        threshCard.addView(row2)
        col.addView(threshCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 2: HARDWARE
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
            AppLogger.logUi("GPU Control", "User tapped Force 670MHz Nuclear")
            HardwareThermalControl.forceUnthrottleGPU()
            Toast.makeText(this, "GPU Forced to 670MHz", Toast.LENGTH_SHORT).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } })

        gpuBtns.addView(createPillButton("🌱 Restore Auto GPU") {
            AppLogger.logUi("GPU Control", "User tapped Restore Auto GPU")
            thread {
                HardwareThermalControl.executeVerifiedCommand(
                    "GPU -> Auto Mode",
                    "echo 6 > /sys/class/kgsl/kgsl-3d0/min_pwrlevel; echo 5 > /sys/class/kgsl/kgsl-3d0/default_pwrlevel",
                    "/sys/class/kgsl/kgsl-3d0/min_pwrlevel",
                    "6"
                )
            }
            Toast.makeText(this, "GPU Restored to Auto Scaling", Toast.LENGTH_SHORT).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } })

        gpuCard.addView(gpuBtns)
        col.addView(gpuCard)

        addSpace(col, 22)

        val hzCard = createCard()
        hzCard.addView(createSectionLabel("DISPLAY REFRESH RATE LOCK"))
        val hzRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; setPadding(0, 12, 0, 0) }
        listOf(60, 90, 120).forEach { hz ->
            hzRow.addView(createPillButton("${hz}Hz") {
                AppLogger.logUi("Display Refresh Button", "User requested ${hz}Hz lock")
                HardwareThermalControl.forceRefreshRate(hz)
                Toast.makeText(this, "Display Locked to ${hz}Hz", Toast.LENGTH_SHORT).show()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 6, 0) } })
        }
        hzCard.addView(hzRow)
        col.addView(hzCard)

        addSpace(col, 22)

        val cpuCard = createCard()
        cpuCard.addView(createSectionLabel("KRYO 585 PER-CORE CONTROL & GOVERNOR"))
        cpuCard.addView(createSwitch("Smart Thermal Governor (Auto Cool)", false) { on ->
            AppLogger.logUi("Smart Thermal Governor Switch", if (on) "ENABLED" else "DISABLED")
            sendBroadcast(Intent("ACTION_TOGGLE_SMART_GOVERNOR").apply {
                setPackage(packageName); putExtra("state", on)
            })
        })
        cpuCard.addView(createSwitch("Manual Throttle Prime Core (C7 Min Freq)", false) { on ->
            AppLogger.logUi("Prime Core C7 Throttle Switch", if (on) "THROTTLED" else "UNTHROTTLED")
            HardwareThermalControl.throttlePrimeCore(on)
        })
        cpuCard.addView(createDivider())

        for (coreId in 0..7) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val clusterTag = if (coreId <= 3) "Silver" else if (coreId <= 6) "Gold" else "Prime"
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
                        AppLogger.logUi("CPU Core $coreId Switch", if (online) "ONLINE" else "OFFLINE")
                        HardwareThermalControl.setCoreOnline(coreId, online)
                    }
                }
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
    // TAB 3: GAMING & FPS
    // =========================================================================
    private fun buildGamingTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

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
        head.addView(createPillButton("Matches") {
            AppLogger.logUi("FPS Graph", "Opened Recorded Matches Picker")
            showFpsSessionDialog()
        }.apply { setPadding(24, 12, 24, 12) })
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 330)
        }
        fpsCard.addView(fpsGraphView)
        col.addView(fpsCard)

        addSpace(col, 22)

        val gameCard = createCard()
        gameCard.addView(createSectionLabel("NUCLEAR 120 FPS ENGINE & OVERLAY"))

        gameCard.addView(createSwitch("Nuclear 120 FPS Gaming Mode", settings.gamingModeEnabled) { on ->
            AppLogger.logUi("Nuclear Gaming Mode Switch", if (on) "ARMED" else "DISARMED")
            settings.gamingModeEnabled = on
            if (!on) HardwareThermalControl.setPeakPerformanceMode(false)
        })
        gameCard.addView(createSwitch("Launch Floating FPS Pill Overlay", FpsOverlayService.isRunning) { on ->
            AppLogger.logUi("Floating FPS Pill Switch", if (on) "LAUNCHED" else "STOPPED")
            toggleOverlayPill(on)
        })
        gameCard.addView(createSwitch("Show Battery Temp in FPS Pill", prefs.getBoolean("showTempInFpsOverlay", true)) { on ->
            AppLogger.logUi("Show Temp in Pill Switch", if (on) "ENABLED" else "DISABLED")
            prefs.edit().putBoolean("showTempInFpsOverlay", on).apply()
        })

        gameCard.addView(createDivider())

        lateinit var capBtn: TextView
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setPadding(0, 8, 0, 12) }
        row.addView(createPillButton("Target Games (${settings.gamingApps.size})") {
            AppLogger.logUi("Gaming Apps Button", "Opened Target Games Selector")
            showAppPicker("Select Target Games", settings.gamingApps, true) { updated ->
                settings.gamingApps = updated
                AppLogger.logUi("Target Games Saved", updated.joinToString(", "))
                buildFullInterface()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } })

        capBtn = createPillButton("Throttle Cap: ${settings.gameThrottleTemp}°C") {
            showNumberDialog("Game Thermal Ceiling (°C)", settings.gameThrottleTemp, 38, 48) { v ->
                settings.gameThrottleTemp = v; capBtn.text = "Throttle Cap: ${v}°C"
                AppLogger.logUi("Game Thermal Ceiling", "Set to ${v}°C")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } }
        row.addView(capBtn)
        gameCard.addView(row)

        gameCard.addView(createPillButton("Exempt Background Apps (${settings.exemptApps.size})") {
            AppLogger.logUi("Exempt Apps Button", "Opened Exempt Apps Selector")
            showAppPicker("Select Exempt Apps", settings.exemptApps, false) { updated ->
                settings.exemptApps = updated
                AppLogger.logUi("Exempt Apps Saved", "${updated.size} packages")
                buildFullInterface()
            }
        })

        col.addView(gameCard)
        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 4: LOGS & TASKS (With Hands-Free Automated Test Runner)
    // =========================================================================
    private fun buildTasksAndLogTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

        val logCard = createCard()
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeader.addView(TextView(this).apply {
            text = "DEEP DIAGNOSTIC & VERIFICATION LOG"
            setTextColor(theme.accentPrimary)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        logHeader.addView(createPillButton("🤖 Run Auto-Test") {
            startHandsFreeAutoTest()
        }.apply { setPadding(26, 14, 26, 14) })
        logCard.addView(logHeader)

        autoTestStatusBanner = TextView(this).apply {
            visibility = View.GONE
            setTextColor(theme.good)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(Color.argb(30, Color.red(theme.good), Color.green(theme.good), Color.blue(theme.good)))
                setStroke(2, theme.good)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 12, 0, 0)
            }
        }
        logCard.addView(autoTestStatusBanner)

        logScrollBox = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 480).apply { setMargins(0, 14, 0, 14) }
            setPadding(22, 18, 22, 18)
            background = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(theme.pillBg)
                setStroke(2, theme.cardStroke)
            }
        }
        logConsoleText = TextView(this).apply {
            text = AppLogger.getLogs()
            setTextColor(theme.textPrimary)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logScrollBox.addView(logConsoleText)
        logCard.addView(logScrollBox)

        val logBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        logBtns.addView(createPillButton("Refresh") {
            AppLogger.logUi("Log Console", "Refreshed view")
            logConsoleText.text = AppLogger.getLogs()
            logScrollBox.post { logScrollBox.fullScroll(View.FOCUS_DOWN) }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 6, 0) } })

        logBtns.addView(createPillButton("Export .txt") {
            AppLogger.logUi("Log Console", "Exporting ActionLog to Downloads")
            exportLogsToFile()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 6, 0) } })

        logBtns.addView(createPillButton("Clear") {
            AppLogger.clear()
            logConsoleText.text = AppLogger.getLogs()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(6, 0, 0, 0) } })

        logCard.addView(logBtns)
        col.addView(logCard)

        addSpace(col, 22)

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
            AppLogger.logUi("Purge RAM Button", "User requested kernel drop_caches=3")
            HardwareThermalControl.clearRamCaches()
            Toast.makeText(this, "RAM Purge Executed", Toast.LENGTH_SHORT).show()
            refreshProcessesList()
        }.apply { setPadding(22, 10, 22, 10) })
        procCard.addView(head)

        processContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 0)
        }
        procCard.addView(processContainer)
        col.addView(procCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // INTERACTIVE TOUCH-DRAGGABLE GRAPH VIEW
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
                    val padL = 84f; val padR = 24f
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
            val w = width.toFloat(); val h = height.toFloat()
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
                Color.argb(85, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
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
            setColor(Color.argb(28, Color.red(c), Color.green(c), Color.blue(c)))
            setStroke(2, Color.argb(130, Color.red(c), Color.green(c), Color.blue(c)))
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
                        var t = 0; var cType = "Battery"; var det = ""
                        for (i in 0 until c.columnCount) {
                            val col = c.getColumnName(i).lowercase()
                            val v = c.getString(i) ?: ""
                            when {
                                col.contains("temp") -> t = v.toIntOrNull() ?: 0
                                col.contains("type") -> cType = v
                                col.contains("detail") -> det = v
                            }
                        }
                        if (t in 15..75) pts.add(ThermalPoint(t, cType, det.ifBlank { "No CPU snapshot recorded" }))
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
                    thermalHistoryList = listOf(ThermalPoint(curT, "Live", "Waiting for 60s database snapshot..."))
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 6, 0, 6) }
            background = GradientDrawable().apply { cornerRadius = 18f; setColor(theme.pillBg) }
            addView(TextView(context).apply {
                text = label; setTextColor(theme.textPrimary); textSize = 12.5f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "PID $pid"; setTextColor(theme.accentPrimary); textSize = 11f; typeface = Typeface.MONOSPACE
            })
        }
        attachSpringAnimation(row) {
            AppLogger.logUi("Process Row Tapped", "$label [PID $pid]")
            AlertDialog.Builder(this@MainActivity)
                .setTitle(label)
                .setItems(arrayOf("Pin to Silver Cores (0-3)", "Kill Process (PID $pid)")) { _, w ->
                    if (w == 0) {
                        AppLogger.logUi("Process Action", "Pinning PID $pid to Silver Cores")
                        HardwareThermalControl.pinProcessToEfficiencyCores(pid)
                    } else {
                        AppLogger.logUi("Process Action", "Killing PID $pid")
                        HardwareThermalControl.killProcess(pid)
                        refreshProcessesList()
                    }
                }
                .show()
        }
        return row
    }

    private fun showThemePickerDialog() {
        val modes = arrayOf("DEFAULT", "MATERIAL_YOU", "LIGHT", "DARK")
        val names = arrayOf("Default (Obsidian Titanium)", "Material You (Dynamic System)", "Light Mode", "Dark Mode")
        AlertDialog.Builder(this)
            .setTitle("Select App Theme")
            .setItems(names) { _, which ->
                prefs.edit().putString("appThemeMode", modes[which]).apply()
                theme = resolveTheme(modes[which])
                AppLogger.logUi("Theme Changed", "Applied ${theme.name} theme")
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
                AppLogger.logUi("FPS Session Selected", labels[which])
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

    private fun exportLogsToFile() {
        try {
            val txt = AppLogger.getLogs()
            val name = "ActionLog_${System.currentTimeMillis()}.txt"
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(txt)
            AppLogger.log("💾 [EXPORT] Saved diagnostic log to Downloads/$name")
            Toast.makeText(this, "Saved to Downloads/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // UI COMPONENT BUILDERS
    // =========================================================================
    private fun createAccessChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(10, 14, 10, 14)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) }
            attachSpringAnimation(this, onClick)
        }
    }

    private fun createScroll() = ScrollView(this).apply {
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    }

    private fun createCol() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(2, 4, 2, 32)
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
            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(OvershootInterpolator()).start()
            }.start()
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
        attachSpringAnimation(this, onClick)
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
