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
import android.text.TextUtils
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val navTabItems = mutableListOf<LinearLayout>()
    private val navTabLabels = mutableListOf<TextView>()

    // Access Chips
    private lateinit var rootChipLabel: TextView
    private lateinit var usageChipLabel: TextView
    private lateinit var notifChipLabel: TextView
    private lateinit var overlayChipLabel: TextView
    private lateinit var rootChipBox: LinearLayout
    private lateinit var usageChipBox: LinearLayout
    private lateinit var notifChipBox: LinearLayout
    private lateinit var overlayChipBox: LinearLayout

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
    private lateinit var gpuLockToggleBtn: LinearLayout
    private lateinit var gpuLockTitleText: TextView
    private lateinit var gpuLockSubText: TextView
    private lateinit var gpuLockIconView: ImageView
    private val coreFreqLabels = mutableListOf<TextView>()
    private val coreSwitches = mutableListOf<Switch?>()
    private var isUpdatingCoreUi = false

    // Tab 3: Gaming
    private lateinit var fpsGraphView: InteractiveGraphView
    private lateinit var fpsSessionHeader: TextView
    private lateinit var fpsScrubReadout: TextView
    private var fpsSessionsList = listOf<FpsSessionData>()
    private var currentFpsSessionIdx = 0

    // Tab 4: Logs
    private lateinit var processContainer: LinearLayout
    private lateinit var logConsoleText: TextView
    private lateinit var logScrollBox: ScrollView
    private lateinit var autoTestStatusBanner: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isUiActive = false
    private val iconCache = mutableMapOf<String, Bitmap?>()
    private val timeFormatter = SimpleDateFormat("hh:mm a", Locale.US)

    data class ThermalPoint(val temp: Int, val chargeType: String, val details: String, val timeLabel: String)
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
                val coreStatuses = HardwareThermalControl.getCoreStatuses()

                uiHandler.post {
                    if (!isUiActive) return@post
                    updateAccessChips(hasRoot)

                    heroTempText.text = "${temp}°C"
                    heroTempText.setTextColor(getColorForTemperature(temp.toFloat()))
                    heroStateText.text = when {
                        HardwareThermalControl.isManualBypassActive -> "BYPASS ISOLATED ($level%)"
                        pwr.isCharging -> "CHARGING ($level%)"
                        else -> "DISCHARGING ($level%)"
                    }
                    val sign = if (pwr.isCharging) "+" else "-"
                    heroWattText.text = "$sign${pwr.wattage}W  •  ${abs(pwr.currentMa)} mA"

                    hwGpuText.text = "Adreno 650 Load: $gpuLoad  •  Live Clock: $gpuFreq"
                    hwRamText.text = ram.replace("\n", "  •  ")
                    updateGpuLockButtonAppearance()

                    isUpdatingCoreUi = true
                    for (status in coreStatuses) {
                        val i = status.coreId
                        if (i in coreFreqLabels.indices) {
                            val tag = if (i <= 3) "Silver" else if (i <= 6) "Gold" else "Prime"
                            coreFreqLabels[i].text = "Core $i ($tag)  •  ${status.freqText}"
                            coreFreqLabels[i].setTextColor(if (status.online) theme.textPrimary else theme.danger)
                        }
                        if (i in coreSwitches.indices) {
                            coreSwitches[i]?.isChecked = status.online
                        }
                    }
                    isUpdatingCoreUi = false

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

        // Ensure Show Battery Temp in Pill is initialized to true on fresh install so FpsOverlayService reads it immediately
        if (!prefs.contains("showTempInFpsOverlay")) {
            prefs.edit().putBoolean("showTempInFpsOverlay", true).apply()
        }

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

        val savedTheme = prefs.getString("appThemeModeV2", "DEFAULT") ?: "DEFAULT"
        theme = resolveTheme(savedTheme)

        AppLogger.log("[UI] MainActivity launched | Theme: ${theme.name}")
        buildFullInterface()
        startAndSyncService()
    }

    // =========================================================================
    // CASE-SENSITIVE CUSTOM PNG ICON LOADER (Reads from ../icons assets)
    // =========================================================================
    private fun loadCustomBitmap(exactFileName: String): Bitmap? {
        if (iconCache.containsKey(exactFileName)) return iconCache[exactFileName]
        val candidates = listOf(exactFileName, "icons/$exactFileName")
        for (path in candidates) {
            try {
                assets.open(path).use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        iconCache[exactFileName] = bmp
                        return bmp
                    }
                }
            } catch (e: Exception) {}
        }
        try {
            val allAssets = assets.list("") ?: emptyArray()
            val matched = allAssets.firstOrNull { it.equals(exactFileName, ignoreCase = true) }
            if (matched != null) {
                assets.open(matched).use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    iconCache[exactFileName] = bmp
                    return bmp
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun createIconView(exactFileName: String, sizeDp: Int = 24): ImageView {
        return ImageView(this).apply {
            val px = dp(sizeDp)
            layoutParams = LinearLayout.LayoutParams(px, px)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val bmp = loadCustomBitmap(exactFileName)
            if (bmp != null) {
                setImageBitmap(bmp)
            }
        }
    }

    private fun createIconBadge(exactFileName: String, iconDp: Int = 22, badgeDp: Int = 40): FrameLayout {
        return FrameLayout(this).apply {
            val bPx = dp(badgeDp)
            layoutParams = LinearLayout.LayoutParams(bPx, bPx).apply {
                setMargins(0, 0, dp(12), 0)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(theme.pillBg)
                setStroke(2, theme.cardStroke)
            }
            val img = createIconView(exactFileName, iconDp).apply {
                layoutParams = FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER)
            }
            addView(img)
        }
    }

    // Dynamic Temperature Color Function (Green -> Yellow -> Orange -> Red)
    private fun getColorForTemperature(tempC: Float): Int {
        return when {
            tempC >= 40f -> Color.parseColor("#EF4444") // High: Red
            tempC >= 38f -> Color.parseColor("#F97316") // Warm-Moderate: Orange
            tempC >= 36f -> Color.parseColor("#F59E0B") // Moderate: Yellow-Amber
            else -> Color.parseColor("#10B981")         // Cool / Normal: Emerald Green
        }
    }

    // =========================================================================
    // HANDS-FREE AUTOMATED TEST RUNNER UI
    // =========================================================================
    private fun startHandsFreeAutoTest() {
        if (HardwareThermalControl.isAutoTestRunning) {
            Toast.makeText(this, "Automated test is already running...", Toast.LENGTH_SHORT).show()
            return
        }

        switchTab(3, animate = true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val hasUsage = hasUsageStatsPermission()
        val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val hasNotif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        autoTestStatusBanner.visibility = View.VISIBLE
        autoTestStatusBanner.text = "HANDS-FREE AUTO-TEST RUNNING...\nKeep phone untouched (~15s)"

        HardwareThermalControl.runAutomatedTestSuite(
            context = applicationContext,
            hasUsagePerm = hasUsage,
            hasOverlayPerm = hasOverlay,
            hasNotifPerm = hasNotif,
            onProgress = { stage, total, title ->
                uiHandler.post {
                    autoTestStatusBanner.text = "STAGE $stage/$total — KEEP PHONE UNTOUCHED\n$title"
                    logConsoleText.text = AppLogger.getLogs()
                    logScrollBox.post { logScrollBox.fullScroll(View.FOCUS_DOWN) }
                }
            },
            onFinished = { passed, failed, manualActions, exportedFile ->
                uiHandler.post {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    hapticTap()
                    autoTestStatusBanner.text = "AUTO-TEST DONE: $passed PASSED • $failed FAILED\nSaved: $exportedFile"
                    logConsoleText.text = AppLogger.getLogs()
                    logScrollBox.post { logScrollBox.fullScroll(View.FOCUS_DOWN) }

                    val summaryMsg = StringBuilder()
                    summaryMsg.append("Passed Checks: $passed\n")
                    summaryMsg.append("Failed Checks: $failed\n")
                    summaryMsg.append("Auto-Saved Report:\n$exportedFile\n\n")
                    if (manualActions.isNotEmpty()) {
                        summaryMsg.append("Manual Actions Needed:\n")
                        manualActions.forEachIndexed { idx, act ->
                            summaryMsg.append("${idx + 1}. $act\n")
                        }
                    } else {
                        summaryMsg.append("All 15/15 permissions & hardware controls verified!")
                    }

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Hands-Free Auto-Test Report")
                        .setMessage(summaryMsg.toString().trim())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
    }

    // =========================================================================
    // THEME ENGINE (Default = Bright Colorful Daylight, Aurora, Material You, Dark)
    // =========================================================================
    private fun resolveTheme(mode: String): ThemePalette {
        val sysNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return when (mode) {
            "AURORA" -> ThemePalette(
                name = "Aurora",
                bgTop = Color.parseColor("#1E1B4B"),
                bgBottom = Color.parseColor("#311042"),
                cardBg = Color.parseColor("#2E235C"),
                cardStroke = Color.parseColor("#6366F1"),
                pillBg = Color.parseColor("#3B2D72"),
                navBarBg = Color.parseColor("#241B4B"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#C7D2FE"),
                accentPrimary = Color.parseColor("#EC4899"),
                accentSecondary = Color.parseColor("#8B5CF6"),
                good = Color.parseColor("#10B981"),
                warn = Color.parseColor("#F59E0B"),
                danger = Color.parseColor("#F43F5E"),
                isLight = false
            )
            "DARK" -> ThemePalette(
                name = "Dark",
                bgTop = Color.parseColor("#0F172A"),
                bgBottom = Color.parseColor("#1E293B"),
                cardBg = Color.parseColor("#1E293B"),
                cardStroke = Color.parseColor("#334155"),
                pillBg = Color.parseColor("#283548"),
                navBarBg = Color.parseColor("#0F172A"),
                textPrimary = Color.parseColor("#F8FAFC"),
                textSecondary = Color.parseColor("#94A3B8"),
                accentPrimary = Color.parseColor("#6366F1"),
                accentSecondary = Color.parseColor("#06B6D4"),
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
                bgTop = Color.parseColor("#EEF2FF"),
                bgBottom = Color.parseColor("#E0F2FE"),
                cardBg = Color.parseColor("#FFFFFF"),
                cardStroke = Color.parseColor("#C7D2FE"),
                pillBg = Color.parseColor("#EEF2FF"),
                navBarBg = Color.parseColor("#FFFFFF"),
                textPrimary = Color.parseColor("#1E1B4B"),
                textSecondary = Color.parseColor("#475569"),
                accentPrimary = Color.parseColor("#4F46E5"),
                accentSecondary = Color.parseColor("#06B6D4"),
                good = Color.parseColor("#059669"),
                warn = Color.parseColor("#D97706"),
                danger = Color.parseColor("#E11D48"),
                isLight = true
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
    // MAIN UI LAYOUT WITH TALL SLIDE-TO-SWITCH BOTTOM TAB BAR
    // =========================================================================
    private fun buildFullInterface() {
        tabViews.clear()
        navTabItems.clear()
        navTabLabels.clear()
        coreFreqLabels.clear()
        coreSwitches.clear()

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
        }

        val mainBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(dp(16), dp(14), dp(16), 0)
        }

        // 1. Clean Top Header Bar with AppIcon.png, RobotHead.png, and theme.png
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val appLogoView = createIconView("AppIcon.png", 38).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(0, 0, dp(10), 0) }
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "TempStatusBar"
            setTextColor(theme.textPrimary)
            textSize = 19f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })
        titleCol.addView(TextView(this).apply {
            text = "${HardwareThermalControl.getHardwareInfo()} • ${theme.name}"
            setTextColor(theme.accentPrimary)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })

        val autoTestTopBtn = createIconPillButton("RobotHead.png", "Auto-Test", isAccent = true) {
            startHandsFreeAutoTest()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }

        val themeBtn = createIconPillButton("theme.png", "Theme", isAccent = false) {
            AppLogger.logUi("Theme Button", "Opened Theme Selector")
            showThemePickerDialog()
        }

        header.addView(appLogoView)
        header.addView(titleCol)
        header.addView(autoTestTopBtn)
        header.addView(themeBtn)
        mainBody.addView(header)

        addSpace(mainBody, dp(12))

        // 2. Access Status Strip with Custom PNG Icons (root.png, graph.png, TemperatureBadge.png, FloatingPill.png)
        val accessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        val rPair = createAccessChip("root.png", "ROOT") {
            AppLogger.logUi("Access Chip", "Tapped ROOT verification chip")
            thread {
                val ok = HardwareThermalControl.isRootAvailable()
                uiHandler.post {
                    updateAccessChips(ok)
                    Toast.makeText(this, if (ok) "Root Verified (uid=0)" else "Root Denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
        rootChipBox = rPair.first; rootChipLabel = rPair.second

        val uPair = createAccessChip("graph.png", "USAGE") {
            AppLogger.logUi("Access Chip", "Opened Usage Access Settings")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        usageChipBox = uPair.first; usageChipLabel = uPair.second

        val nPair = createAccessChip("TemperatureBadge.png", "ICON") {
            AppLogger.logUi("Access Chip", "Triggered Status Bar Icon Refresh")
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } else {
                settings.showNotification = true
                startAndSyncService()
                Toast.makeText(this, "Status Bar Temperature Active", Toast.LENGTH_SHORT).show()
            }
        }
        notifChipBox = nPair.first; notifChipLabel = nPair.second

        val oPair = createAccessChip("FloatingPill.png", "PILL") {
            AppLogger.logUi("Access Chip", "Toggled Floating FPS Pill")
            toggleOverlayPill(!FpsOverlayService.isRunning)
        }
        overlayChipBox = oPair.first; overlayChipLabel = oPair.second

        accessRow.addView(rootChipBox)
        accessRow.addView(usageChipBox)
        accessRow.addView(notifChipBox)
        accessRow.addView(overlayChipBox)
        mainBody.addView(accessRow)

        addSpace(mainBody, dp(12))

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

        // 4. Taller, Interactive Slide-to-Switch Bottom Tab Bar
        val bottomDockContainer = FrameLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(14))
        }
        val segmentedPillBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(theme.navBarBg)
                setStroke(3, theme.cardStroke)
            }
            elevation = dp(8).toFloat()
        }

        val tabSpecs = listOf(
            "Monitor" to "thermometer.png",
            "Hardware" to "processor.png",
            "Gaming" to "game.png",
            "Logs" to "clipboard.png"
        )

        tabSpecs.forEachIndexed { idx, (label, iconFile) ->
            val tabItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
            }
            val iconImg = createIconView(iconFile, 24)
            val txt = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                isSingleLine = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
            }
            tabItem.addView(iconImg)
            tabItem.addView(txt)

            navTabItems.add(tabItem)
            navTabLabels.add(txt)
            segmentedPillBar.addView(tabItem)
        }

        // Slide-to-Switch Touch Handler: glide your finger left/right across the bottom bar to switch tabs!
        segmentedPillBar.setOnTouchListener { barView, event ->
            val w = barView.width.toFloat().coerceAtLeast(1f)
            val targetIdx = ((event.x / w) * 4f).toInt().coerceIn(0, 3)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (targetIdx != currentTabIndex) {
                        hapticTap(barView)
                        AppLogger.logUi("Bottom Tab", "Switched to [${tabSpecs[targetIdx].first}]")
                        switchTab(targetIdx, animate = true)
                    } else {
                        hapticTick(barView)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (targetIdx != currentTabIndex) {
                        hapticTick(barView)
                        AppLogger.logUi("Bottom Tab Slide", "Glided to [${tabSpecs[targetIdx].first}]")
                        switchTab(targetIdx, animate = true)
                    }
                    true
                }
                else -> true
            }
        }

        bottomDockContainer.addView(segmentedPillBar)
        root.addView(bottomDockContainer)

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
                    v.translationY = 20f
                    v.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(180)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            } else {
                v.visibility = View.GONE
            }
        }

        navTabItems.forEachIndexed { i, item ->
            val active = (i == idx)
            if (active) {
                navTabLabels[i].setTextColor(Color.WHITE)
                item.background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(theme.accentPrimary, theme.accentSecondary)
                ).apply {
                    cornerRadius = dp(24).toFloat()
                }
                item.animate().scaleX(1.03f).scaleY(1.03f).setDuration(160).setInterpolator(OvershootInterpolator()).start()
            } else {
                navTabLabels[i].setTextColor(theme.textSecondary)
                item.background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(theme.pillBg)
                }
                item.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
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
    // TAB 1: MONITOR (Hero Temp Card, Dynamic Color Temp Graph, Thresholds)
    // =========================================================================
    private fun buildMonitorTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

        val heroCard = createCard()
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heroRow.addView(createIconBadge("battery.png", 26, 48))

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
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        heroWattText = TextView(this).apply {
            text = "-- W  •  -- mA"
            setTextColor(theme.accentPrimary)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 4, 0, 0)
        }
        rightCol.addView(heroStateText)
        rightCol.addView(heroWattText)
        heroRow.addView(heroTempText)
        heroRow.addView(rightCol)
        heroCard.addView(heroRow)

        heroCard.addView(createDivider())

        heroCard.addView(createIconSwitchRow("TemperatureBadge.png", "Status Bar Temperature", "Display live battery °C icon in status bar", settings.showNotification) { on ->
            AppLogger.logUi("Status Bar Temp Switch", if (on) "ENABLED" else "DISABLED")
            settings.showNotification = on
            startAndSyncService()
        })
        heroCard.addView(createIconSwitchRow("charging.png", "Notification Power Metrics", "Show live Wattage & mA current in notification", settings.showPowerMetrics) { on ->
            AppLogger.logUi("Notification Power Metrics Switch", if (on) "ENABLED" else "DISABLED")
            settings.showPowerMetrics = on
            startAndSyncService()
        })
        heroCard.addView(createIconSwitchRow("battery.png", "Battery Bypass (Pause Charge)", "Isolate battery and run off direct adapter power", HardwareThermalControl.isManualBypassActive) { on ->
            AppLogger.logUi("Battery Bypass Switch", if (on) "ENGAGED" else "RELEASED")
            HardwareThermalControl.setChargingEnabled(!on, isManualToggle = true)
            startAndSyncService()
        })
        col.addView(heroCard)

        addSpace(col, dp(14))

        // Interactive Temperature Graph Card (Green -> Yellow/Orange -> Red)
        val graphCard = createCard()
        val gHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gHead.addView(createIconView("graph.png", 20).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(0, 0, dp(8), 0) }
        })
        gHead.addView(TextView(this).apply {
            text = "TEMPERATURE TIMELINE"
            setTextColor(theme.accentPrimary)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        gHead.addView(createPillButton("Refresh") {
            AppLogger.logUi("Thermal Graph", "Manual Refresh")
            loadThermalGraphData()
        }.apply { setPadding(dp(14), dp(8), dp(14), dp(8)) })
        graphCard.addView(gHead)

        tempScrubReadout = TextView(this).apply {
            text = "Drag across the graph to inspect exact time & temperature"
            setTextColor(theme.textSecondary)
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(8))
        }
        graphCard.addView(tempScrubReadout)

        tempGraphView = InteractiveGraphView(this, isTemperatureMode = true) { idx ->
            if (idx in thermalHistoryList.indices) {
                val pt = thermalHistoryList[idx]
                tempScrubReadout.text = "${pt.timeLabel}   •   ${pt.temp}°C   •   ${pt.chargeType}"
                tempScrubReadout.setTextColor(getColorForTemperature(pt.temp.toFloat()))
                tempSnapshotBox.text = "Recorded at ${pt.timeLabel} (${pt.temp}°C):\n${pt.details}"
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
        }
        graphCard.addView(tempGraphView)

        tempSnapshotBox = TextView(this).apply {
            text = "Slide your finger along the curve above to inspect recorded CPU activity."
            setTextColor(theme.textPrimary)
            textSize = 11.5f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(theme.pillBg)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(10), 0, 0)
            }
        }
        graphCard.addView(tempSnapshotBox)
        col.addView(graphCard)

        addSpace(col, dp(14))

        // Thresholds Card
        val threshCard = createCard()
        threshCard.addView(createSectionLabel("THERMAL & CHARGING PROTECTION"))
        threshCard.addView(createIconSwitchRow("thermometer.png", "Max Charge % Limiter", "Automatically stop charging at target battery %", settings.chargeLimitEnabled) { on ->
            AppLogger.logUi("Max Charge % Cutoff Switch", if (on) "ENABLED (${settings.chargeLimitMax}%)" else "DISABLED")
            settings.chargeLimitEnabled = on
        })

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; setPadding(0, dp(8), 0, dp(8)) }
        lateinit var warnBtn: TextView
        lateinit var cutBtn: TextView
        lateinit var resBtn: TextView

        warnBtn = createPillButton("Warn: ${settings.warningTemp}°C") {
            showNumberDialog("Overheat Warning (°C)", settings.warningTemp, 36, 48) { v ->
                settings.warningTemp = v; warnBtn.text = "Warn: ${v}°C"
                AppLogger.logUi("Threshold Change", "Warning Temp set to ${v}°C")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) } }

        cutBtn = createPillButton("Cutoff: ${settings.cutoffTemp}°C") {
            showNumberDialog("Charge Cutoff (°C)", settings.cutoffTemp, 37, 48) { v ->
                settings.cutoffTemp = v; cutBtn.text = "Cutoff: ${v}°C"
                AppLogger.logUi("Threshold Change", "Charge Cutoff Temp set to ${v}°C")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) } }

        resBtn = createPillButton("Resume: ${settings.resumeTemp}°C") {
            showNumberDialog("Charge Resume (°C)", settings.resumeTemp, 34, 45) { v ->
                settings.resumeTemp = v; resBtn.text = "Resume: ${v}°C"
                AppLogger.logUi("Threshold Change", "Charge Resume Temp set to ${v}°C")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) } }

        row1.addView(warnBtn); row1.addView(cutBtn); row1.addView(resBtn)
        threshCard.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        lateinit var maxPctBtn: TextView
        lateinit var resPctBtn: TextView

        maxPctBtn = createPillButton("Stop Charge: ${settings.chargeLimitMax}%") {
            showNumberDialog("Stop Charging At (%)", settings.chargeLimitMax, 60, 100) { v ->
                settings.chargeLimitMax = v; maxPctBtn.text = "Stop Charge: ${v}%"
                AppLogger.logUi("Threshold Change", "Max Charge % set to ${v}%")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) } }

        resPctBtn = createPillButton("Resume Charge: ${settings.chargeLimitResume}%") {
            showNumberDialog("Resume Charging At (%)", settings.chargeLimitResume, 50, 95) { v ->
                settings.chargeLimitResume = v; resPctBtn.text = "Resume Charge: ${v}%"
                AppLogger.logUi("Threshold Change", "Resume Charge % set to ${v}%")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) } }

        row2.addView(maxPctBtn); row2.addView(resPctBtn)
        threshCard.addView(row2)
        col.addView(threshCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // TAB 2: HARDWARE (Stateful GPU 670MHz Lock/Unlock Toggle, Display Hz, Cores)
    // =========================================================================
    private fun buildHardwareTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

        val gpuCard = createCard()
        gpuCard.addView(createSectionLabel("GRAPHICS & MEMORY ENGINE"))
        hwGpuText = createBodyText("Polling GPU...")
        hwRamText = createBodyText("Polling RAM...")
        gpuCard.addView(hwGpuText)
        gpuCard.addView(hwRamText)

        addSpace(gpuCard, dp(10))

        // Single Interactive GPU 670 MHz Lock / Unlock Toggle Card
        gpuLockToggleBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        gpuLockIconView = createIconView("charging.png", 26).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { setMargins(0, 0, dp(12), 0) }
        }
        val gpuCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        gpuLockTitleText = TextView(this).apply {
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        gpuLockSubText = TextView(this).apply {
            textSize = 11.5f
            setPadding(0, 2, 0, 0)
        }
        gpuCol.addView(gpuLockTitleText)
        gpuCol.addView(gpuLockSubText)
        gpuLockToggleBtn.addView(gpuLockIconView)
        gpuLockToggleBtn.addView(gpuCol)

        attachSpringAnimation(gpuLockToggleBtn) {
            val targetLock = !HardwareThermalControl.isGpuPeakLocked
            AppLogger.logUi("GPU 670MHz Toggle", if (targetLock) "LOCKING TO 670 MHz" else "UNLOCKING TO AUTO (305-670 MHz)")
            HardwareThermalControl.setGpuPeakLock(targetLock) { isLockedNow, liveFreq ->
                uiHandler.post {
                    updateGpuLockButtonAppearance()
                    hwGpuText.text = "Adreno 650 Load: ${HardwareThermalControl.getGpuUsage()}  •  Live Clock: $liveFreq"
                    Toast.makeText(
                        this,
                        if (isLockedNow) "GPU Locked at Peak ($liveFreq)" else "GPU Unlocked to Dynamic Auto ($liveFreq)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        gpuCard.addView(gpuLockToggleBtn)
        updateGpuLockButtonAppearance()
        col.addView(gpuCard)

        addSpace(col, dp(14))

        val hzCard = createCard()
        hzCard.addView(createSectionLabel("DISPLAY REFRESH RATE"))
        val hzRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; setPadding(0, dp(10), 0, 0) }
        listOf(60, 90, 120).forEach { hz ->
            hzRow.addView(createPillButton("${hz}Hz") {
                AppLogger.logUi("Display Refresh Button", "User requested ${hz}Hz lock")
                HardwareThermalControl.forceRefreshRate(hz)
                Toast.makeText(this, "Display set to ${hz}Hz", Toast.LENGTH_SHORT).show()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) } })
        }
        hzCard.addView(hzRow)
        col.addView(hzCard)

        addSpace(col, dp(14))

        val cpuCard = createCard()
        cpuCard.addView(createSectionLabel("KRYO 585 PROCESSOR CORES"))
        cpuCard.addView(createIconSwitchRow("leaf.png", "Smart Thermal Governor", "Automatically cool down CPU when idle", false) { on ->
            AppLogger.logUi("Smart Thermal Governor Switch", if (on) "ENABLED" else "DISABLED")
            sendBroadcast(Intent("ACTION_TOGGLE_SMART_GOVERNOR").apply {
                setPackage(packageName); putExtra("state", on)
            })
        })
        cpuCard.addView(createIconSwitchRow("processor.png", "Limit Prime Core (C7)", "Cap Prime Core 7 to minimum clock to save power", false) { on ->
            AppLogger.logUi("Prime Core C7 Throttle Switch", if (on) "THROTTLED" else "UNTHROTTLED")
            HardwareThermalControl.throttlePrimeCore(on)
        })
        cpuCard.addView(createDivider())

        for (coreId in 0..7) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val clusterTag = if (coreId <= 3) "Silver" else if (coreId <= 6) "Gold" else "Prime"
            val lbl = TextView(this).apply {
                text = "Core $coreId ($clusterTag)  •  -- MHz"
                setTextColor(theme.textPrimary)
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            coreFreqLabels.add(lbl)
            row.addView(lbl)

            if (coreId > 0) {
                val sw = Switch(this).apply {
                    isChecked = true
                    setOnCheckedChangeListener { v, online ->
                        if (isUpdatingCoreUi) return@setOnCheckedChangeListener
                        hapticTap(v)
                        AppLogger.logUi("CPU Core $coreId Switch", if (online) "ONLINE" else "OFFLINE")
                        HardwareThermalControl.setCoreOnline(coreId, online)
                    }
                }
                coreSwitches.add(sw)
                row.addView(sw)
            } else {
                coreSwitches.add(null)
                row.addView(TextView(this).apply {
                    text = "ALWAYS ON"
                    setTextColor(theme.accentPrimary)
                    textSize = 10.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                })
            }
            cpuCard.addView(row)
        }

        col.addView(cpuCard)
        scroll.addView(col)
        return scroll
    }

    private fun updateGpuLockButtonAppearance() {
        if (!::gpuLockToggleBtn.isInitialized) return
        val locked = HardwareThermalControl.isGpuPeakLocked
        if (locked) {
            gpuLockIconView.setImageBitmap(loadCustomBitmap("charging.png"))
            gpuLockTitleText.text = "GPU Locked at 670 MHz (Tap to Unlock)"
            gpuLockTitleText.setTextColor(Color.WHITE)
            gpuLockSubText.text = "Adreno 650 pinned at Power Level 0 • Zero thermal downclocking"
            gpuLockSubText.setTextColor(Color.parseColor("#E0F2FE"))
            gpuLockToggleBtn.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(theme.accentPrimary, theme.accentSecondary)
            ).apply { cornerRadius = dp(18).toFloat() }
        } else {
            gpuLockIconView.setImageBitmap(loadCustomBitmap("leaf.png"))
            gpuLockTitleText.text = "GPU in Dynamic Auto Mode (Tap to Lock 670 MHz)"
            gpuLockTitleText.setTextColor(theme.textPrimary)
            gpuLockSubText.text = "Scales dynamically between 305 MHz and 670 MHz based on load"
            gpuLockSubText.setTextColor(theme.textSecondary)
            gpuLockToggleBtn.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(theme.pillBg)
                setStroke(2, theme.cardStroke)
            }
        }
    }

    // =========================================================================
    // TAB 3: GAMING & FPS
    // =========================================================================
    private fun buildGamingTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

        val fpsCard = createCard()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(createIconView("trophy.png", 22).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { setMargins(0, 0, dp(8), 0) }
        })
        fpsSessionHeader = TextView(this).apply {
            text = "RECORDED MATCH FPS GRAPH"
            setTextColor(theme.good)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        head.addView(fpsSessionHeader)
        head.addView(createIconPillButton("trophy.png", "Matches", false) {
            AppLogger.logUi("FPS Graph", "Opened Recorded Matches Picker")
            showFpsSessionDialog()
        })
        fpsCard.addView(head)

        fpsScrubReadout = TextView(this).apply {
            text = "Drag across the graph to inspect exact FPS & Temperature"
            setTextColor(theme.textSecondary)
            textSize = 12f
            setPadding(0, dp(6), 0, dp(8))
        }
        fpsCard.addView(fpsScrubReadout)

        fpsGraphView = InteractiveGraphView(this, isTemperatureMode = false) { idx ->
            if (fpsSessionsList.isNotEmpty()) {
                val s = fpsSessionsList[currentFpsSessionIdx]
                val fVal = s.fpsList.getOrNull(idx)?.toInt() ?: 0
                val tVal = s.tempList.getOrNull(idx)?.toInt() ?: s.avgTemp
                fpsScrubReadout.text = "$fVal FPS  •  ${tVal}°C   (Avg ${s.avg} FPS | Peak ${s.max} FPS)"
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
        }
        fpsCard.addView(fpsGraphView)
        col.addView(fpsCard)

        addSpace(col, dp(14))

        val gameCard = createCard()
        gameCard.addView(createSectionLabel("120 FPS GAME ENGINE & FLOATING PILL"))

        gameCard.addView(createIconSwitchRow("game.png", "120 FPS Gaming Mode", "Automatically unlock 3.18 GHz CPU, 670 MHz GPU & 120Hz in games", settings.gamingModeEnabled) { on ->
            AppLogger.logUi("120 FPS Gaming Mode Switch", if (on) "ARMED" else "DISARMED")
            settings.gamingModeEnabled = on
            if (!on) HardwareThermalControl.setPeakPerformanceMode(false)
        })
        gameCard.addView(createIconSwitchRow("FloatingPill.png", "Floating FPS Pill", "Show draggable live FPS counter over any app", FpsOverlayService.isRunning) { on ->
            AppLogger.logUi("Floating FPS Pill Switch", if (on) "LAUNCHED" else "STOPPED")
            toggleOverlayPill(on)
        })
        gameCard.addView(createIconSwitchRow("TemperatureBadge.png", "Temperature in FPS Pill", "Display live battery °C alongside FPS in the floating pill", prefs.getBoolean("showTempInFpsOverlay", true)) { on ->
            AppLogger.logUi("Show Temp in Pill Switch", if (on) "ENABLED" else "DISABLED")
            prefs.edit().putBoolean("showTempInFpsOverlay", on).apply()
        })

        gameCard.addView(createDivider())

        lateinit var capBtn: TextView
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setPadding(0, dp(6), 0, dp(8)) }
        row.addView(createPillButton("Games (${settings.gamingApps.size})") {
            AppLogger.logUi("Gaming Apps Button", "Opened Target Games Selector")
            showAppPicker("Select Target Games", settings.gamingApps, true) { updated ->
                settings.gamingApps = updated
                AppLogger.logUi("Target Games Saved", updated.joinToString(", "))
                buildFullInterface()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) } })

        capBtn = createPillButton("Thermal Cap: ${settings.gameThrottleTemp}°C") {
            showNumberDialog("Game Thermal Ceiling (°C)", settings.gameThrottleTemp, 38, 48) { v ->
                settings.gameThrottleTemp = v; capBtn.text = "Thermal Cap: ${v}°C"
                AppLogger.logUi("Game Thermal Ceiling", "Set to ${v}°C")
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) } }
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
    // TAB 4: LOGS (Verified Action Log + Force-Stop RAM Purge & Process Manager)
    // =========================================================================
    private fun buildTasksAndLogTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

        val logCard = createCard()
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeader.addView(createIconView("clipboard.png", 20).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(0, 0, dp(8), 0) }
        })
        logHeader.addView(TextView(this).apply {
            text = "DIAGNOSTIC & VERIFICATION LOG"
            setTextColor(theme.accentPrimary)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        logHeader.addView(createIconPillButton("RobotHead.png", "Run Auto-Test", true) {
            startHandsFreeAutoTest()
        })
        logCard.addView(logHeader)

        autoTestStatusBanner = TextView(this).apply {
            visibility = View.GONE
            setTextColor(theme.good)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(30, Color.red(theme.good), Color.green(theme.good), Color.blue(theme.good)))
                setStroke(2, theme.good)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(10), 0, 0)
            }
        }
        logCard.addView(autoTestStatusBanner)

        logScrollBox = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)).apply { setMargins(0, dp(10), 0, dp(10)) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
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
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) } })

        logBtns.addView(createIconPillButton("document.png", "Export .txt", false) {
            AppLogger.logUi("Log Console", "Exporting ActionLog to Downloads")
            exportLogsToFile()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) } })

        logBtns.addView(createPillButton("Clear") {
            AppLogger.clear()
            logConsoleText.text = AppLogger.getLogs()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) } })

        logCard.addView(logBtns)
        col.addView(logCard)

        addSpace(col, dp(14))

        val procCard = createCard()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(createIconView("ram.png", 20).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { setMargins(0, 0, dp(8), 0) }
        })
        head.addView(TextView(this).apply {
            text = "PROCESS & RAM MANAGER"
            setTextColor(theme.accentPrimary)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(createIconPillButton("ram.png", "Purge RAM & Apps", true) {
            AppLogger.logUi("Purge RAM & Kill Apps Button", "Force-stopping user background apps + drop_caches=3")
            HardwareThermalControl.clearRamCaches(settings.exemptApps) { count, freedMb ->
                uiHandler.post {
                    Toast.makeText(this, "Stopped $count Apps (+${freedMb}MB Freed)", Toast.LENGTH_LONG).show()
                    logConsoleText.text = AppLogger.getLogs()
                    logScrollBox.post { logScrollBox.fullScroll(View.FOCUS_DOWN) }
                    refreshProcessesList()
                }
            }
        })
        procCard.addView(head)

        processContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        procCard.addView(processContainer)
        col.addView(procCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // INTERACTIVE GRAPH VIEW (Dynamic Temperature Gradient: Green -> Yellow/Orange -> Red)
    // =========================================================================
    inner class InteractiveGraphView(
        context: Context,
        private val isTemperatureMode: Boolean,
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
            strokeWidth = 6.5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val secCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 3.5f
            style = Paint.Style.STROKE
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val scrubLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.accentPrimary
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

            val padL = 84f; val padR = 24f; val padT = 24f; val padB = 28f
            val cw = w - padL - padR
            val ch = h - padT - padB

            val rawMin = primarySeries.minOrNull() ?: 0f
            val rawMax = primarySeries.maxOrNull() ?: 100f
            val minV = if (isTemperatureMode) max(28f, rawMin - 2f) else max(0f, rawMin - (rawMax - rawMin).coerceAtLeast(6f) * 0.15f)
            val maxV = if (isTemperatureMode) max(43f, rawMax + 2f) else rawMax + (rawMax - rawMin).coerceAtLeast(6f) * 0.15f
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

            if (isTemperatureMode) {
                // Map Y-coordinates of 41°C (Red), 38.5°C (Orange), 36.5°C (Yellow), and 34°C (Green)
                fun yForTemp(t: Float): Float = padT + ch * (1f - ((t - minV) / range).coerceIn(0f, 1f))
                val yTop = yForTemp(41f)
                val yBottom = yForTemp(34f).coerceAtLeast(yTop + 10f)

                val colorsStroke = intArrayOf(
                    Color.parseColor("#EF4444"), // Red (High)
                    Color.parseColor("#F97316"), // Orange
                    Color.parseColor("#F59E0B"), // Yellow (Moderate)
                    Color.parseColor("#10B981")  // Emerald Green (Cool)
                )
                val positions = floatArrayOf(0f, 0.35f, 0.68f, 1f)
                curvePaint.shader = LinearGradient(0f, yTop, 0f, yBottom, colorsStroke, positions, Shader.TileMode.CLAMP)

                val colorsFill = intArrayOf(
                    Color.argb(115, 239, 68, 68),
                    Color.argb(85, 249, 115, 22),
                    Color.argb(65, 245, 158, 11),
                    Color.argb(20, 16, 185, 129)
                )
                fillPaint.shader = LinearGradient(0f, yTop, 0f, padT + ch, colorsFill, positions, Shader.TileMode.CLAMP)
            } else {
                curvePaint.shader = null
                curvePaint.color = lineColor
                fillPaint.shader = LinearGradient(
                    0f, padT, 0f, padT + ch,
                    Color.argb(90, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
                    Color.argb(0, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor)),
                    Shader.TileMode.CLAMP
                )
            }

            canvas.drawPath(fill, fillPaint)
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
            val activeVal = primarySeries[idx]
            val activeDotColor = if (isTemperatureMode) getColorForTemperature(activeVal) else lineColor

            scrubLinePaint.color = activeDotColor
            canvas.drawLine(activePt.x, padT, activePt.x, padT + ch, scrubLinePaint)
            dotPaint.color = activeDotColor
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

        styleAccessChip(rootChipBox, rootChipLabel, if (hasRoot) "ROOT: OK" else "ROOT: NO", hasRoot)
        styleAccessChip(usageChipBox, usageChipLabel, if (hasUsage) "USAGE: OK" else "USAGE: FIX", hasUsage)
        styleAccessChip(notifChipBox, notifChipLabel, if (hasNotif) "ICON: ON" else "ICON: OFF", hasNotif)
        styleAccessChip(overlayChipBox, overlayChipLabel, if (hasOverlay) "PILL: OK" else "PILL: FIX", hasOverlay)
    }

    private fun styleAccessChip(box: LinearLayout, labelView: TextView, label: String, granted: Boolean) {
        labelView.text = label
        val c = if (granted) theme.good else theme.danger
        labelView.setTextColor(c)
        box.background = GradientDrawable().apply {
            cornerRadius = 100f
            setColor(Color.argb(26, Color.red(c), Color.green(c), Color.blue(c)))
            setStroke(2, Color.argb(140, Color.red(c), Color.green(c), Color.blue(c)))
        }
    }

    private fun formatRawTimestamp(raw: String, fallbackMillis: Long): String {
        val trimmed = raw.trim()
        val asLong = trimmed.toLongOrNull()
        if (asLong != null && asLong > 1000000000L) {
            val ms = if (asLong < 100000000000L) asLong * 1000L else asLong
            return timeFormatter.format(Date(ms))
        }
        if (trimmed.contains(":")) {
            return trimmed.takeLast(8).trim()
        }
        return timeFormatter.format(Date(fallbackMillis))
    }

    private fun loadThermalGraphData() {
        thread {
            val rawRows = mutableListOf<Triple<Int, String, Pair<String, String>>>()
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
                        var t = 0; var cType = "Battery"; var det = ""; var rawTime = ""
                        for (i in 0 until c.columnCount) {
                            val col = c.getColumnName(i).lowercase()
                            val v = c.getString(i) ?: ""
                            when {
                                col.contains("temp") -> t = v.toIntOrNull() ?: 0
                                col.contains("type") -> cType = v
                                col.contains("detail") -> det = v
                                col.contains("time") || col.contains("date") || col.contains("created") -> rawTime = v
                            }
                        }
                        if (t in 15..75) {
                            rawRows.add(Triple(t, cType, det.ifBlank { "No CPU snapshot recorded" } to rawTime))
                        }
                    }
                    c.close()
                }
            } catch (e: Exception) {}

            val now = System.currentTimeMillis()
            val ordered = rawRows.mapIndexed { revIdx, triple ->
                val estMillis = now - (revIdx * 60_000L)
                val formattedTime = formatRawTimestamp(triple.third.second, estMillis)
                ThermalPoint(
                    temp = triple.first,
                    chargeType = triple.second,
                    details = triple.third.first,
                    timeLabel = formattedTime
                )
            }.reversed()

            uiHandler.post {
                if (ordered.isNotEmpty()) {
                    thermalHistoryList = ordered
                    tempGraphView.setGraphData(ordered.map { it.temp.toFloat() }, emptyList(), theme.accentPrimary, theme.danger, "°C")
                    val last = ordered.last()
                    tempScrubReadout.text = "${last.timeLabel}   •   ${last.temp}°C   •   ${last.chargeType}"
                    tempScrubReadout.setTextColor(getColorForTemperature(last.temp.toFloat()))
                    tempSnapshotBox.text = "Recorded at ${last.timeLabel} (${last.temp}°C):\n${last.details}"
                } else {
                    val curT = (registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 350) ?: 350) / 10
                    val nowLabel = timeFormatter.format(Date(now))
                    thermalHistoryList = listOf(ThermalPoint(curT, "Live", "Waiting for 60s database snapshot...", nowLabel))
                    tempGraphView.setGraphData(listOf(curT.toFloat(), curT.toFloat()), emptyList(), theme.accentPrimary, theme.danger, "°C")
                    tempScrubReadout.text = "$nowLabel   •   ${curT}°C   •   Live"
                    tempScrubReadout.setTextColor(getColorForTemperature(curT.toFloat()))
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
                    processContainer.addView(createProcItem("processor.png", "${p.name} (${p.cpu}% CPU)", p.pid))
                }
                ramProcs.forEach { r ->
                    processContainer.addView(createProcItem("ram.png", "${r.name} (${r.sizeMb} MB RAM)", r.pid))
                }
            }
        }
    }

    private fun createProcItem(iconFile: String, label: String, pid: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) }
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(theme.pillBg) }
            addView(createIconView(iconFile, 18).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { setMargins(0, 0, dp(10), 0) }
            })
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
        val modes = arrayOf("DEFAULT", "MATERIAL_YOU", "AURORA", "DARK")
        val names = arrayOf("Default (Vibrant Daylight)", "Material You (Dynamic System)", "Aurora Violet", "Dark Mode")
        AlertDialog.Builder(this)
            .setTitle("Select App Theme")
            .setItems(names) { _, which ->
                prefs.edit().putString("appThemeModeV2", modes[which]).apply()
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
            setPadding(dp(20), dp(14), dp(20), dp(14))
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
            AppLogger.log("[EXPORT] Saved diagnostic log to Downloads/$name")
            Toast.makeText(this, "Saved to Downloads/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // UI COMPONENT BUILDERS
    // =========================================================================
    private fun createAccessChip(iconFile: String, label: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val tv = TextView(this).apply {
            text = label
            textSize = 10f
            isSingleLine = true
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
            addView(createIconView(iconFile, 14).apply {
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { setMargins(0, 0, dp(4), 0) }
            })
            addView(tv)
        }
        attachSpringAnimation(box, onClick)
        return box to tv
    }

    private fun createIconSwitchRow(
        iconFile: String,
        title: String,
        subtitle: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(createIconBadge(iconFile, 22, 38))

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(context).apply {
                text = title
                setTextColor(theme.textPrimary)
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            textCol.addView(TextView(context).apply {
                text = subtitle
                setTextColor(theme.textSecondary)
                textSize = 11f
                setPadding(0, 2, 0, 0)
            })
            addView(textCol)

            val sw = Switch(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { v, isChecked ->
                    hapticTap(v)
                    onToggle(isChecked)
                }
            }
            addView(sw)
        }
    }

    private fun createIconPillButton(iconFile: String, label: String, isAccent: Boolean, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = if (isAccent) {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(theme.accentPrimary, theme.accentSecondary)).apply {
                    cornerRadius = dp(18).toFloat()
                }
            } else {
                GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(theme.pillBg)
                    setStroke(2, theme.cardStroke)
                }
            }
            addView(createIconView(iconFile, 16).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { setMargins(0, 0, dp(6), 0) }
            })
            addView(TextView(context).apply {
                text = label
                setTextColor(if (isAccent) Color.WHITE else theme.textPrimary)
                textSize = 11.5f
                isSingleLine = true
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
        }
        attachSpringAnimation(box, onClick)
        return box
    }

    private fun createScroll() = ScrollView(this).apply {
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    }

    private fun createCol() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), dp(2), dp(2), dp(16))
    }

    private fun createCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(theme.cardBg)
            setStroke(2, theme.cardStroke)
        }
        elevation = if (theme.isLight) dp(3).toFloat() else dp(1).toFloat()
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
        setPadding(0, dp(4), 0, dp(2))
    }

    private fun createPillButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(theme.textPrimary)
        textSize = 12f
        gravity = Gravity.CENTER
        isSingleLine = true
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(theme.pillBg)
            setStroke(2, theme.cardStroke)
        }
        attachSpringAnimation(this, onClick)
    }

    private fun createDivider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { setMargins(0, dp(8), 0, dp(8)) }
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
