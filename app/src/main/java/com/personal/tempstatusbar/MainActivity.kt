package com.personal.tempstatusbar

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
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
        val toggleTrackOn: Int,
        val toggleTrackOff: Int,
        val toggleBorderOff: Int,
        val toggleThumbOn: Int,
        val toggleThumbOff: Int,
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
    private lateinit var navIndicatorView: View
    private lateinit var navSegmentedBar: FrameLayout

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
    private val coreFreqLabels = mutableListOf<TextView>()
    private val coreSwitches = mutableListOf<Switch?>()
    private var isUpdatingCoreUi = false

    // Tab 3: Gaming (Unified with GPU Tuning)
    private lateinit var turboModeSwitch: Switch
    private var isUpdatingTurboSwitch = false
    private lateinit var hwGpuText: TextView
    private lateinit var gpuLockToggleBtn: LinearLayout
    private lateinit var gpuLockTitleText: TextView
    private lateinit var gpuLockSubText: TextView
    private lateinit var gpuLockIconView: ImageView
    private lateinit var fpsGraphView: InteractiveGraphView
    private lateinit var fpsSessionHeader: TextView
    private lateinit var fpsScrubReadout: TextView
    private var fpsSessionsList = listOf<FpsSessionData>()
    private var currentFpsSessionIdx = 0

    // Tab 4: Logs
    private lateinit var hwRamText: TextView
    private lateinit var processContainer: LinearLayout
    private lateinit var logConsoleText: TextView
    private lateinit var logScrollBox: ScrollView

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
                HardwareThermalControl.syncGpuLockState()
                val gpuLoad = HardwareThermalControl.getGpuUsage()
                val gpuFreq = HardwareThermalControl.getGpuFrequency()
                val coreStatuses = HardwareThermalControl.getCoreStatuses()

                uiHandler.post {
                    if (!isUiActive) return@post
                    updateAccessChips(hasRoot)

                    heroTempText.text = "${temp}°C"
                    heroTempText.setTextColor(getColorForTemperature(temp.toFloat()))
                    heroStateText.text = when {
                        plugged && HardwareThermalControl.isManualBypassActive -> "BYPASS ACTIVE ($level%)"
                        pwr.isCharging -> "CHARGING ($level%)"
                        else -> "DISCHARGING ($level%)"
                    }
                    val sign = if (pwr.isCharging) "+" else "-"
                    heroWattText.text = "$sign${pwr.wattage}W  •  ${abs(pwr.currentMa)} mA"

                    if (::hwGpuText.isInitialized) {
                        hwGpuText.text = "Adreno 650 Load: $gpuLoad  •  Clock: $gpuFreq"
                        updateGpuLockButtonAppearance()
                    }
                    if (::turboModeSwitch.isInitialized) {
                        isUpdatingTurboSwitch = true
                        turboModeSwitch.isChecked = settings.gamingModeEnabled
                        isUpdatingTurboSwitch = false
                    }
                    if (::hwRamText.isInitialized) {
                        hwRamText.text = ram.replace("\n", "  •  ")
                    }

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

                    if (currentTabIndex == 3 && ::logConsoleText.isInitialized) {
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

        val savedTheme = prefs.getString("appThemeModeV4", "DEFAULT") ?: "DEFAULT"
        theme = resolveTheme(savedTheme)

        buildFullInterface()
        startAndSyncService()
    }

    // =========================================================================
    // CORNER-KEY TRANSPARENCY PNG LOADER
    // =========================================================================
    private fun removeSolidCornerBackground(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)

        val c0 = pixels[0]
        if (Color.alpha(c0) < 40) return out

        val bgR = Color.red(c0)
        val bgG = Color.green(c0)
        val bgB = Color.blue(c0)

        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        fun matchesBg(px: Int): Boolean {
            val dr = abs(Color.red(px) - bgR)
            val dg = abs(Color.green(px) - bgG)
            val db = abs(Color.blue(px) - bgB)
            return dr <= 48 && dg <= 48 && db <= 48
        }

        fun enqueue(x: Int, y: Int) {
            val idx = y * w + x
            if (!visited[idx] && matchesBg(pixels[idx])) {
                visited[idx] = true
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
            pixels[idx] = Color.TRANSPARENT
            val x = idx % w
            val y = idx / w
            if (x > 0) enqueue(x - 1, y)
            if (x + 1 < w) enqueue(x + 1, y)
            if (y > 0) enqueue(x, y - 1)
            if (y + 1 < h) enqueue(x, y + 1)
        }

        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun loadCustomBitmap(exactFileName: String): Bitmap? {
        if (iconCache.containsKey(exactFileName)) return iconCache[exactFileName]
        val candidates = listOf(exactFileName, "icons/$exactFileName", "Icons/$exactFileName")
        for (path in candidates) {
            try {
                assets.open(path).use { stream ->
                    val raw = BitmapFactory.decodeStream(stream)
                    if (raw != null) {
                        val cleaned = removeSolidCornerBackground(raw)
                        iconCache[exactFileName] = cleaned
                        return cleaned
                    }
                }
            } catch (e: Exception) {}
        }
        try {
            val allAssets = assets.list("") ?: emptyArray()
            val matched = allAssets.firstOrNull { it.equals(exactFileName, ignoreCase = true) }
            if (matched != null) {
                assets.open(matched).use { stream ->
                    val raw = BitmapFactory.decodeStream(stream)
                    if (raw != null) {
                        val cleaned = removeSolidCornerBackground(raw)
                        iconCache[exactFileName] = cleaned
                        return cleaned
                    }
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
            if (bmp != null) setImageBitmap(bmp)
        }
    }

    private fun createIconBadge(exactFileName: String, iconDp: Int = 24, badgeDp: Int = 42): FrameLayout {
        return FrameLayout(this).apply {
            val bPx = dp(badgeDp)
            layoutParams = LinearLayout.LayoutParams(bPx, bPx).apply {
                setMargins(0, 0, dp(12), 0)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(theme.pillBg)
            }
            val img = createIconView(exactFileName, iconDp).apply {
                layoutParams = FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER)
            }
            addView(img)
        }
    }

    private fun getColorForTemperature(tempC: Float): Int {
        return when {
            tempC >= 40f -> Color.parseColor("#E11D48")
            tempC >= 38f -> Color.parseColor("#EA580C")
            tempC >= 36f -> Color.parseColor("#D97706")
            else -> Color.parseColor("#059669")
        }
    }

    // =========================================================================
    // THEME PALETTES
    // =========================================================================
    private fun resolveTheme(mode: String): ThemePalette {
        val sysNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return when (mode) {
            "DARK" -> ThemePalette(
                name = "Dark",
                bgTop = Color.parseColor("#000000"),
                bgBottom = Color.parseColor("#000000"),
                cardBg = Color.parseColor("#0D0D10"),
                cardStroke = Color.parseColor("#222228"),
                pillBg = Color.parseColor("#17171C"),
                navBarBg = Color.parseColor("#111115"),
                textPrimary = Color.parseColor("#F4F4F6"),
                textSecondary = Color.parseColor("#A1A1AA"),
                accentPrimary = Color.parseColor("#27272E"),
                accentSecondary = Color.parseColor("#32323B"),
                toggleTrackOn = Color.parseColor("#10B981"),
                toggleTrackOff = Color.parseColor("#27272E"),
                toggleBorderOff = Color.parseColor("#52525B"),
                toggleThumbOn = Color.parseColor("#FFFFFF"),
                toggleThumbOff = Color.parseColor("#A1A1AA"),
                good = Color.parseColor("#10B981"),
                warn = Color.parseColor("#F59E0B"),
                danger = Color.parseColor("#EF4444"),
                isLight = false
            )
            "AURORA" -> ThemePalette(
                name = "Aurora",
                bgTop = Color.parseColor("#171126"),
                bgBottom = Color.parseColor("#210E2E"),
                cardBg = Color.parseColor("#281C3D"),
                cardStroke = Color.parseColor("#4C3278"),
                pillBg = Color.parseColor("#352452"),
                navBarBg = Color.parseColor("#1A122B"),
                textPrimary = Color.parseColor("#FAF5FF"),
                textSecondary = Color.parseColor("#D8B4FE"),
                accentPrimary = Color.parseColor("#9333EA"),
                accentSecondary = Color.parseColor("#A855F7"),
                toggleTrackOn = Color.parseColor("#A855F7"),
                toggleTrackOff = Color.parseColor("#3B2559"),
                toggleBorderOff = Color.parseColor("#6B4696"),
                toggleThumbOn = Color.parseColor("#FFFFFF"),
                toggleThumbOff = Color.parseColor("#D8B4FE"),
                good = Color.parseColor("#34D399"),
                warn = Color.parseColor("#FBBF24"),
                danger = Color.parseColor("#F43F5E"),
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
                            navBarBg = getColor(android.R.color.system_neutral2_800),
                            textPrimary = getColor(android.R.color.system_neutral1_50),
                            textSecondary = getColor(android.R.color.system_neutral2_200),
                            accentPrimary = Color.parseColor("#27272E"),
                            accentSecondary = Color.parseColor("#32323B"),
                            toggleTrackOn = Color.parseColor("#10B981"),
                            toggleTrackOff = getColor(android.R.color.system_neutral2_700),
                            toggleBorderOff = getColor(android.R.color.system_neutral2_400),
                            toggleThumbOn = Color.WHITE,
                            toggleThumbOff = getColor(android.R.color.system_neutral1_200),
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
                            accentPrimary = Color.parseColor("#1E1E22"),
                            accentSecondary = Color.parseColor("#2C2C32"),
                            toggleTrackOn = Color.parseColor("#0D7A53"),
                            toggleTrackOff = getColor(android.R.color.system_neutral2_200),
                            toggleBorderOff = getColor(android.R.color.system_neutral2_400),
                            toggleThumbOn = Color.WHITE,
                            toggleThumbOff = getColor(android.R.color.system_neutral1_700),
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
                bgTop = Color.parseColor("#F5F4F0"),
                bgBottom = Color.parseColor("#ECEAE3"),
                cardBg = Color.parseColor("#FFFFFF"),
                cardStroke = Color.parseColor("#E2DFD7"),
                pillBg = Color.parseColor("#EFECE4"),
                navBarBg = Color.parseColor("#FFFFFF"),
                textPrimary = Color.parseColor("#1C1C1E"),
                textSecondary = Color.parseColor("#686662"),
                accentPrimary = Color.parseColor("#1E1E22"),
                accentSecondary = Color.parseColor("#2D2D33"),
                toggleTrackOn = Color.parseColor("#0D7A53"),
                toggleTrackOff = Color.parseColor("#DFDCD2"),
                toggleBorderOff = Color.parseColor("#B5B0A4"),
                toggleThumbOn = Color.parseColor("#FFFFFF"),
                toggleThumbOff = Color.parseColor("#57534E"),
                good = Color.parseColor("#0D7A53"),
                warn = Color.parseColor("#C46800"),
                danger = Color.parseColor("#DC2626"),
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
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(OvershootInterpolator(2f)).start()
                    hapticTap(v)
                    onClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            true
        }
    }

    // =========================================================================
    // MAIN INTERFACE SETUP
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
            setPadding(dp(16), dp(16), dp(16), 0)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "TempStatusBar"
            setTextColor(theme.textPrimary)
            textSize = 21f
            isSingleLine = true
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })
        titleCol.addView(TextView(this).apply {
            text = "${HardwareThermalControl.getHardwareInfo()}  •  ${theme.name}"
            setTextColor(theme.textSecondary)
            textSize = 12f
            isSingleLine = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(1), 0, 0)
        })

        val themeBtn = createIconPillButton("theme.png", "Theme", isAccent = false) {
            showThemeModal()
        }
        header.addView(titleCol)
        header.addView(themeBtn)
        mainBody.addView(header)

        addSpace(mainBody, dp(12))

        // Permission Status Chips
        val accessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
        }
        val rPair = createAccessChip("root.png", "ROOT") {
            showPermissionModal(
                title = "Root Access",
                desc = "Grants direct access to the Linux kernel sysfs interface. Required to blind thermal throttling zones, lock CPU/GPU clock speeds, and manage hardware charging bypass.",
                actionLabel = "Verify Access",
                onAction = {
                    thread {
                        val ok = HardwareThermalControl.isRootAvailable()
                        uiHandler.post {
                            updateAccessChips(ok)
                            Toast.makeText(this@MainActivity, if (ok) "Root access confirmed." else "Root access denied.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
        rootChipBox = rPair.first; rootChipLabel = rPair.second

        val uPair = createAccessChip("graph.png", "USAGE") {
            showPermissionModal(
                title = "Usage Access",
                desc = "Allows the background service to detect when your target games are running so Turbo Mode engages automatically.",
                actionLabel = "Open Settings",
                onAction = { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            )
        }
        usageChipBox = uPair.first; usageChipLabel = uPair.second

        val nPair = createAccessChip("TemperatureBadge.png", "ICON") {
            showPermissionModal(
                title = "Status Bar Notification",
                desc = "Required by Android to render the live temperature icon and power telemetry in your status bar and notification tray.",
                actionLabel = "Enable Icon",
                onAction = {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                    } else {
                        settings.showNotification = true
                        startAndSyncService()
                    }
                }
            )
        }
        notifChipBox = nPair.first; notifChipLabel = nPair.second

        val oPair = createAccessChip("FloatingPill.png", "PILL") {
            showPermissionModal(
                title = "Screen Overlay",
                desc = "Allows the interactive FPS counter and temperature pill to float over full-screen games and apps.",
                actionLabel = "Configure",
                onAction = { toggleOverlayPill(!FpsOverlayService.isRunning) }
            )
        }
        overlayChipBox = oPair.first; overlayChipLabel = oPair.second

        accessRow.addView(rootChipBox)
        accessRow.addView(usageChipBox)
        accessRow.addView(notifChipBox)
        accessRow.addView(overlayChipBox)
        mainBody.addView(accessRow)

        addSpace(mainBody, dp(12))

        // Center Content Frame
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

        // Bottom Navigation Bar
        val bottomDockContainer = FrameLayout(this).apply {
            setPadding(dp(18), dp(6), dp(18), dp(16))
            clipChildren = false
            clipToPadding = false
        }

        navSegmentedBar = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(68))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(34).toFloat()
                setColor(theme.navBarBg)
                setStroke(dp(1.5f.roundToInt().coerceAtLeast(1)), theme.cardStroke)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = 0f
        }

        navIndicatorView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, dp(56)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(theme.accentPrimary)
            }
        }
        navSegmentedBar.addView(navIndicatorView)

        val navTabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 4f
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val tabSpecs = listOf(
            "Monitor" to "thermometer.png",
            "Hardware" to "processor.png",
            "Gaming" to "game.png",
            "Logs" to "clipboard.png"
        )

        tabSpecs.forEach { (label, iconFile) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
            val img = createIconView(iconFile, 22).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val txt = TextView(this).apply {
                text = label
                textSize = 11.5f
                isSingleLine = true
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(0, dp(3), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            item.addView(img)
            item.addView(txt)
            navTabItems.add(item)
            navTabLabels.add(txt)
            navTabsRow.addView(item)
        }
        navSegmentedBar.addView(navTabsRow)

        navSegmentedBar.setOnTouchListener { bar, event ->
            val totalW = bar.width.toFloat().coerceAtLeast(1f)
            val slotW = totalW / 4f
            val targetIdx = (event.x / slotW).toInt().coerceIn(0, 3)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hapticTap(bar)
                    animateIndicatorToPosition(targetIdx, animate = true)
                    if (targetIdx != currentTabIndex) {
                        switchTab(targetIdx)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val indicatorW = slotW - dp(12)
                    val rawX = (event.x - indicatorW / 2f).coerceIn(dp(6).toFloat(), totalW - indicatorW - dp(6))
                    navIndicatorView.x = rawX
                    if (targetIdx != currentTabIndex) {
                        hapticTick(bar)
                        switchTab(targetIdx)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    animateIndicatorToPosition(currentTabIndex, animate = true)
                    true
                }
                else -> true
            }
        }

        bottomDockContainer.addView(navSegmentedBar)
        root.addView(bottomDockContainer)

        setContentView(root)

        navSegmentedBar.post {
            animateIndicatorToPosition(currentTabIndex, animate = false)
            updateTabHighlightColors(currentTabIndex)
            tabViews.forEachIndexed { i, v -> v.visibility = if (i == currentTabIndex) View.VISIBLE else View.GONE }
        }
    }

    private fun animateIndicatorToPosition(index: Int, animate: Boolean) {
        val barW = navSegmentedBar.width.toFloat()
        if (barW <= 0f) return
        val slotW = barW / 4f
        val indicatorW = slotW - dp(12)
        val targetX = (index * slotW) + dp(6)

        val lp = navIndicatorView.layoutParams
        if (lp.width != indicatorW.roundToInt()) {
            lp.width = indicatorW.roundToInt()
            navIndicatorView.layoutParams = lp
        }

        if (animate) {
            navIndicatorView.animate()
                .x(targetX)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.05f))
                .start()
        } else {
            navIndicatorView.x = targetX
        }
    }

    private fun updateTabHighlightColors(activeIndex: Int) {
        navTabLabels.forEachIndexed { i, txt ->
            val active = (i == activeIndex)
            txt.setTextColor(if (active) Color.WHITE else theme.textSecondary)
        }
    }

    private fun switchTab(targetIndex: Int) {
        if (targetIndex == currentTabIndex) return
        val forward = targetIndex > currentTabIndex
        val oldIndex = currentTabIndex
        currentTabIndex = targetIndex

        animateIndicatorToPosition(targetIndex, animate = true)
        updateTabHighlightColors(targetIndex)

        val oldView = tabViews[oldIndex]
        val newView = tabViews[targetIndex]
        val containerW = (oldView.parent as View).width.toFloat()

        oldView.animate()
            .translationX(if (forward) -containerW * 0.45f else containerW * 0.45f)
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                oldView.visibility = View.GONE
                oldView.translationX = 0f
                oldView.alpha = 1f
            }
            .start()

        newView.visibility = View.VISIBLE
        newView.translationX = if (forward) containerW * 0.45f else -containerW * 0.45f
        newView.alpha = 0f
        newView.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()

        when (targetIndex) {
            0 -> loadThermalGraphData()
            2 -> loadFpsGraphData()
            3 -> {
                refreshProcessesList()
                if (::logConsoleText.isInitialized) {
                    logConsoleText.text = AppLogger.getLogs()
                }
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
        heroRow.addView(createIconBadge("TemperatureBadge.png", 26, 46))

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
            setTextColor(theme.textSecondary)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(3), 0, 0)
        }
        rightCol.addView(heroStateText)
        rightCol.addView(heroWattText)
        heroRow.addView(heroTempText)
        heroRow.addView(rightCol)
        heroCard.addView(heroRow)

        heroCard.addView(createDivider())

        heroCard.addView(createIconSwitchRow("TemperatureBadge.png", "Status Bar Temperature", "Render live temperature icon in device status bar", settings.showNotification) { on ->
            settings.showNotification = on
            startAndSyncService()
        })
        heroCard.addView(createIconSwitchRow("charging.png", "Live Power Metrics", "Display active Wattage and mA draw in notification", settings.showPowerMetrics) { on ->
            settings.showPowerMetrics = on
            startAndSyncService()
        })
        heroCard.addView(createIconSwitchRow("battery.png", "Battery Bypass (Pause Charge)", "Directs power to device hardware without charging battery", HardwareThermalControl.isManualBypassActive) { on ->
            HardwareThermalControl.setChargingEnabled(!on, isManualToggle = true)
            startAndSyncService()
        })
        col.addView(heroCard)

        addSpace(col, dp(14))

        // Temperature Timeline Graph Card
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
            setTextColor(theme.textSecondary)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        gHead.addView(createPillButton("Refresh") { loadThermalGraphData() }.apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
        })
        graphCard.addView(gHead)

        tempScrubReadout = TextView(this).apply {
            text = "Drag horizontally across curve to scrub history"
            setTextColor(theme.textSecondary)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(8))
        }
        graphCard.addView(tempScrubReadout)

        tempGraphView = InteractiveGraphView(this, isTemperatureMode = true) { idx: Int ->
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
            text = "Scrub the timeline above to view recorded CPU process snapshots."
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

        // Protection Thresholds Card
        val threshCard = createCard()
        threshCard.addView(createSectionLabel("THERMAL & CHARGING PROTECTION"))
        threshCard.addView(createIconSwitchRow("thermometer.png", "Charge Level Limiter", "Stops AC charging when battery reaches target level", settings.chargeLimitEnabled) { on ->
            settings.chargeLimitEnabled = on
        })

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; setPadding(0, dp(8), 0, dp(8)) }
        lateinit var warnBtn: TextView
        lateinit var cutBtn: TextView
        lateinit var resBtn: TextView

        warnBtn = createPillButton("Warn: ${settings.warningTemp}°C") {
            showRotaryDrumModal("Warning Temperature", settings.warningTemp, 36, 48, "°C") { v ->
                settings.warningTemp = v; warnBtn.text = "Warn: ${v}°C"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) } }

        cutBtn = createPillButton("Cutoff: ${settings.cutoffTemp}°C") {
            showRotaryDrumModal("Charge Cutoff Temperature", settings.cutoffTemp, 37, 48, "°C") { v ->
                settings.cutoffTemp = v; cutBtn.text = "Cutoff: ${v}°C"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) } }

        resBtn = createPillButton("Resume: ${settings.resumeTemp}°C") {
            showRotaryDrumModal("Charge Resume Temperature", settings.resumeTemp, 34, 45, "°C") { v ->
                settings.resumeTemp = v; resBtn.text = "Resume: ${v}°C"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) } }

        row1.addView(warnBtn); row1.addView(cutBtn); row1.addView(resBtn)
        threshCard.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        lateinit var maxPctBtn: TextView
        lateinit var resPctBtn: TextView

        maxPctBtn = createPillButton("Stop Level: ${settings.chargeLimitMax}%") {
            showRotaryDrumModal("Stop Charge Level", settings.chargeLimitMax, 60, 100, "%") { v ->
                settings.chargeLimitMax = v; maxPctBtn.text = "Stop Level: ${v}%"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) } }

        resPctBtn = createPillButton("Resume Level: ${settings.chargeLimitResume}%") {
            showRotaryDrumModal("Resume Charge Level", settings.chargeLimitResume, 50, 95, "%") { v ->
                settings.chargeLimitResume = v; resPctBtn.text = "Resume Level: ${v}%"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) } }

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

        val hzCard = createCard()
        hzCard.addView(createSectionLabel("DISPLAY REFRESH RATE"))
        val hzRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; setPadding(0, dp(10), 0, 0) }
        listOf(60, 90, 120).forEach { hz ->
            hzRow.addView(createPillButton("${hz}Hz") {
                HardwareThermalControl.forceRefreshRate(hz)
                Toast.makeText(this, "Display refresh set to ${hz}Hz", Toast.LENGTH_SHORT).show()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) } })
        }
        hzCard.addView(hzRow)
        col.addView(hzCard)

        addSpace(col, dp(14))

        val cpuCard = createCard()
        cpuCard.addView(createSectionLabel("PROCESSOR ARCHITECTURE & CORES"))
        cpuCard.addView(createIconSwitchRow("leaf.png", "Smart Thermal Governor", "Dynamically throttles CPU cores during thermal spikes", false) { on ->
            sendBroadcast(Intent("ACTION_TOGGLE_SMART_GOVERNOR").apply {
                setPackage(packageName); putExtra("state", on)
            })
        })
        cpuCard.addView(createIconSwitchRow("processor.png", "Prime Core Throttle", "Caps Prime Core 7 clock speed to preserve power", false) { on ->
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
                val sw = createCustomSwitch(checked = true) { online ->
                    if (isUpdatingCoreUi) return@createCustomSwitch
                    HardwareThermalControl.setCoreOnline(coreId, online)
                }
                coreSwitches.add(sw)
                row.addView(sw)
            } else {
                coreSwitches.add(null)
                row.addView(TextView(this).apply {
                    text = "BOOT CORE"
                    setTextColor(theme.textSecondary)
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

    // =========================================================================
    // TAB 3: GAMING (Turbo Mode, GPU Tuning, Pinned App Picker, Match Records)
    // =========================================================================
    private fun buildGamingTab(): ScrollView {
        val scroll = createScroll()
        val col = createCol()

        val gameCard = createCard()
        gameCard.addView(createSectionLabel("TURBO PERFORMANCE ENGINE"))

        // FIXED: Only arms the detector; does not trigger setPeakPerformanceMode immediately
        val turboRow = createIconSwitchRow("game.png", "Turbo Mode", "Automatically engages when a target game is in the foreground", settings.gamingModeEnabled) { on ->
            if (isUpdatingTurboSwitch) return@createIconSwitchRow
            settings.gamingModeEnabled = on
            if (!on) {
                HardwareThermalControl.restoreStockHardwareState()
            }
            uiHandler.postDelayed({ updateGpuLockButtonAppearance() }, 300)
        }
        turboModeSwitch = turboRow.getChildAt(2) as Switch
        gameCard.addView(turboRow)

        gameCard.addView(createIconSwitchRow("FloatingPill.png", "Floating Performance Pill", "Overlays live FPS and battery metrics above active apps", FpsOverlayService.isRunning) { on ->
            toggleOverlayPill(on)
        })
        gameCard.addView(createIconSwitchRow("TemperatureBadge.png", "Temperature in Pill", "Displays real-time battery temperature inside the floating pill", prefs.getBoolean("showTempInFpsOverlay", true)) { on ->
            prefs.edit().putBoolean("showTempInFpsOverlay", on).apply()
        })

        gameCard.addView(createDivider())

        lateinit var capBtn: TextView
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setPadding(0, dp(4), 0, dp(8)) }
        row.addView(createPillButton("Add Games (${settings.gamingApps.size})") {
            showAppSelectionModal(
                title = "Add Games to Turbo Mode",
                desc = "Selected games are pinned to the top. When any selected game runs, Turbo Mode engages automatically.",
                selectedSet = settings.gamingApps,
                onSave = { updated ->
                    settings.gamingApps = updated
                    buildFullInterface()
                }
            )
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) } })

        capBtn = createPillButton("Thermal Cap: ${settings.gameThrottleTemp}°C") {
            showRotaryDrumModal("Turbo Thermal Ceiling", settings.gameThrottleTemp, 38, 48, "°C") { v ->
                settings.gameThrottleTemp = v; capBtn.text = "Thermal Cap: ${v}°C"
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, 0, 0) } }
        row.addView(capBtn)
        gameCard.addView(row)

        gameCard.addView(createPillButton("Exempt Background Apps (${settings.exemptApps.size})") {
            showAppSelectionModal(
                title = "Exempt Background Apps",
                desc = "Selected background apps stay protected from being closed or throttled while gaming.",
                selectedSet = settings.exemptApps,
                onSave = { updated ->
                    settings.exemptApps = updated
                    buildFullInterface()
                }
            )
        })

        col.addView(gameCard)
        addSpace(col, dp(14))

        // Adreno GPU Control Card
        val gpuCard = createCard()
        gpuCard.addView(createSectionLabel("GRAPHICS CLOCK CONTROL"))
        hwGpuText = createBodyText("Polling GPU...")
        gpuCard.addView(hwGpuText)

        addSpace(gpuCard, dp(10))

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
            setPadding(0, dp(2), 0, 0)
        }
        gpuCol.addView(gpuLockTitleText)
        gpuCol.addView(gpuLockSubText)
        gpuLockToggleBtn.addView(gpuLockIconView)
        gpuLockToggleBtn.addView(gpuCol)

        attachSpringAnimation(gpuLockToggleBtn) {
            val targetLock = !HardwareThermalControl.isGpuPeakLocked
            HardwareThermalControl.setGpuPeakLock(targetLock) { isLockedNow, liveFreq ->
                uiHandler.post {
                    updateGpuLockButtonAppearance()
                    hwGpuText.text = "Adreno 650 Load: ${HardwareThermalControl.getGpuUsage()}  •  Clock: $liveFreq"
                    Toast.makeText(this, if (isLockedNow) "GPU locked to 670 MHz" else "GPU restored to automatic scaling", Toast.LENGTH_SHORT).show()
                }
            }
        }
        gpuCard.addView(gpuLockToggleBtn)
        updateGpuLockButtonAppearance()
        col.addView(gpuCard)

        addSpace(col, dp(14))

        // Match FPS History Graph
        val fpsCard = createCard()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(createIconView("trophy.png", 22).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { setMargins(0, 0, dp(8), 0) }
        })
        fpsSessionHeader = TextView(this).apply {
            text = "MATCH FPS SESSIONS"
            setTextColor(theme.good)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        head.addView(fpsSessionHeader)
        head.addView(createIconPillButton("trophy.png", "Matches", false) {
            showFpsSessionDialog()
        })
        fpsCard.addView(head)

        fpsScrubReadout = TextView(this).apply {
            text = "Scrub across curve to inspect FPS variance"
            setTextColor(theme.textSecondary)
            textSize = 12f
            setPadding(0, dp(6), 0, dp(8))
        }
        fpsCard.addView(fpsScrubReadout)

        fpsGraphView = InteractiveGraphView(this, isTemperatureMode = false) { idx: Int ->
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

        scroll.addView(col)
        return scroll
    }

    private fun updateGpuLockButtonAppearance() {
        if (!::gpuLockToggleBtn.isInitialized) return
        val locked = HardwareThermalControl.isGpuPeakLocked
        if (locked) {
            gpuLockIconView.setImageBitmap(loadCustomBitmap("charging.png"))
            gpuLockTitleText.text = "Locked at 670 MHz (Tap to Unlock)"
            gpuLockTitleText.setTextColor(Color.WHITE)
            gpuLockSubText.text = "Adreno 650 pinned at Power Level 0 • Zero thermal downclocking"
            gpuLockSubText.setTextColor(Color.parseColor("#E4E4E7"))
            gpuLockToggleBtn.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(theme.toggleTrackOn)
            }
        } else {
            gpuLockIconView.setImageBitmap(loadCustomBitmap("leaf.png"))
            gpuLockTitleText.text = "Automatic Scaling (Tap to Lock 670 MHz)"
            gpuLockTitleText.setTextColor(theme.textPrimary)
            gpuLockSubText.text = "GPU dynamically scales frequencies based on processing load"
            gpuLockSubText.setTextColor(theme.textSecondary)
            gpuLockToggleBtn.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(theme.pillBg)
                setStroke(2, theme.cardStroke)
            }
        }
    }

    // =========================================================================
    // TAB 4: LOGS (Isolated Touch Scroll & Process Manager)
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
            text = "DIAGNOSTIC & ACTION LOG"
            setTextColor(theme.textSecondary)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        logCard.addView(logHeader)

        logScrollBox = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)).apply {
                setMargins(0, dp(10), 0, dp(10))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(theme.pillBg)
                setStroke(2, theme.cardStroke)
            }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
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
            logConsoleText.text = AppLogger.getLogs()
            logScrollBox.post { logScrollBox.fullScroll(View.FOCUS_DOWN) }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(4), 0) } })

        logBtns.addView(createIconPillButton("document.png", "Export", false) {
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
            text = "MEMORY & PROCESS MANAGER"
            setTextColor(theme.textSecondary)
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(createIconPillButton("ram.png", "Purge Memory", true) {
            HardwareThermalControl.clearRamCaches(settings.exemptApps) { count, freedMb ->
                uiHandler.post {
                    Toast.makeText(this, "Closed $count apps (Freed ${freedMb}MB)", Toast.LENGTH_LONG).show()
                    logConsoleText.text = AppLogger.getLogs()
                    logScrollBox.post { logScrollBox.fullScroll(View.FOCUS_DOWN) }
                    refreshProcessesList()
                }
            }
        })
        procCard.addView(head)

        hwRamText = createBodyText("Polling RAM...")
        procCard.addView(hwRamText)

        processContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        procCard.addView(processContainer)
        col.addView(procCard)

        scroll.addView(col)
        return scroll
    }

    // =========================================================================
    // INTERACTIVE GRAPH VIEW
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

        private var touchStartX = 0f
        private var touchStartY = 0f
        private var isHorizontalScrubbing = false
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

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
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    isHorizontalScrubbing = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(event.x - touchStartX)
                    val dy = abs(event.y - touchStartY)

                    if (!isHorizontalScrubbing) {
                        if (dx > touchSlop && dx > dy * 1.25f) {
                            isHorizontalScrubbing = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (dy > touchSlop) {
                            parent?.requestDisallowInterceptTouchEvent(false)
                            return false
                        }
                    }

                    if (isHorizontalScrubbing) {
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
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isHorizontalScrubbing = false
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
                fun yForTemp(t: Float): Float = padT + ch * (1f - ((t - minV) / range).coerceIn(0f, 1f))
                val yTop = yForTemp(41f)
                val yBottom = yForTemp(34f).coerceAtLeast(yTop + 10f)

                val colorsStroke = intArrayOf(
                    Color.parseColor("#E11D48"),
                    Color.parseColor("#EA580C"),
                    Color.parseColor("#D97706"),
                    Color.parseColor("#0D7A53")
                )
                val positions = floatArrayOf(0f, 0.35f, 0.68f, 1f)
                curvePaint.shader = LinearGradient(0f, yTop, 0f, yBottom, colorsStroke, positions, Shader.TileMode.CLAMP)

                val colorsFill = intArrayOf(
                    Color.argb(100, 225, 29, 72),
                    Color.argb(75, 234, 88, 12),
                    Color.argb(55, 217, 119, 6),
                    Color.argb(15, 13, 122, 83)
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
    // CENTERED ROTARY DRUM ROLLER MODAL
    // =========================================================================
    private fun showRotaryDrumModal(
        title: String,
        current: Int,
        minV: Int,
        maxV: Int,
        unit: String,
        onSelected: (Int) -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(theme.cardBg)
                setStroke(2, theme.cardStroke)
            }
        }

        card.addView(TextView(this).apply {
            text = title
            setTextColor(theme.textPrimary)
            textSize = 17f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })

        card.addView(TextView(this).apply {
            text = "Swipe vertically to rotate value"
            setTextColor(theme.textSecondary)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        })

        var activeValue = current.coerceIn(minV, maxV)
        val rollerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(140)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(theme.pillBg)
                setStroke(2, theme.cardStroke)
            }
        }

        val topPreview = TextView(this).apply {
            text = if (activeValue > minV) "${activeValue - 1}$unit" else "--"
            textSize = 15f
            alpha = 0.4f
            gravity = Gravity.CENTER
            setTextColor(theme.textSecondary)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val mainCenter = TextView(this).apply {
            text = "$activeValue$unit"
            textSize = 34f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(theme.textPrimary)
            setPadding(0, dp(4), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val bottomPreview = TextView(this).apply {
            text = if (activeValue < maxV) "${activeValue + 1}$unit" else "--"
            textSize = 15f
            alpha = 0.4f
            gravity = Gravity.CENTER
            setTextColor(theme.textSecondary)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        rollerLayout.addView(topPreview)
        rollerLayout.addView(mainCenter)
        rollerLayout.addView(bottomPreview)

        var lastY = 0f
        rollerLayout.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = event.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lastY
                    if (abs(dy) > dp(16)) {
                        val steps = (dy / dp(16)).toInt()
                        val newVal = (activeValue - steps).coerceIn(minV, maxV)
                        if (newVal != activeValue) {
                            activeValue = newVal
                            hapticTick(v)
                            mainCenter.text = "$activeValue$unit"
                            topPreview.text = if (activeValue > minV) "${activeValue - 1}$unit" else "--"
                            bottomPreview.text = if (activeValue < maxV) "${activeValue + 1}$unit" else "--"
                        }
                        lastY = event.y
                    }
                    true
                }
                else -> true
            }
        }
        card.addView(rollerLayout)

        addSpace(card, dp(20))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val cancelBtn = createPillButton("Cancel") { dialog.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) }
        }
        val saveBtn = createAccentPillButton("Apply") {
            onSelected(activeValue)
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) }
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(saveBtn)
        card.addView(btnRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }

    // =========================================================================
    // PINNED APP SELECTION MODAL
    // =========================================================================
    private fun showAppSelectionModal(
        title: String,
        desc: String,
        selectedSet: Set<String>,
        onSave: (Set<String>) -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(theme.cardBg)
                setStroke(2, theme.cardStroke)
            }
        }

        rootBox.addView(TextView(this).apply {
            text = title
            setTextColor(theme.textPrimary)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        rootBox.addView(TextView(this).apply {
            text = desc
            setTextColor(theme.textSecondary)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        })

        val currentSelected = selectedSet.toMutableSet()
        val scrollApps = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280))
        }
        val appsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        thread {
            val allApps = HardwareThermalControl.getInstalledUserApps(this@MainActivity, currentSelected)
            uiHandler.post {
                appsCol.removeAllViews()
                for (app in allApps) {
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(8), 0, dp(8))
                    }
                    val cb = CheckBox(this@MainActivity).apply {
                        isChecked = currentSelected.contains(app.packageName)
                        buttonTintList = ColorStateList.valueOf(theme.toggleTrackOn)
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) currentSelected.add(app.packageName)
                            else currentSelected.remove(app.packageName)
                        }
                    }
                    val labelCol = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), 0, 0, 0)
                    }
                    labelCol.addView(TextView(this@MainActivity).apply {
                        text = app.label
                        setTextColor(theme.textPrimary)
                        textSize = 13.5f
                        typeface = Typeface.create("sans-serif-medium", if (app.isSelected) Typeface.BOLD else Typeface.NORMAL)
                    })
                    labelCol.addView(TextView(this@MainActivity).apply {
                        text = app.packageName
                        setTextColor(theme.textSecondary)
                        textSize = 11f
                    })
                    row.addView(cb)
                    row.addView(labelCol)
                    row.setOnClickListener { cb.toggle() }
                    appsCol.addView(row)
                }
            }
        }
        scrollApps.addView(appsCol)
        rootBox.addView(scrollApps)

        addSpace(rootBox, dp(16))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val cancelBtn = createPillButton("Cancel") { dialog.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) }
        }
        val saveBtn = createAccentPillButton("Save") {
            onSave(currentSelected)
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) }
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(saveBtn)
        rootBox.addView(btnRow)

        dialog.setContentView(rootBox)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }

    private fun showPermissionModal(title: String, desc: String, actionLabel: String, onAction: () -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(theme.cardBg)
                setStroke(2, theme.cardStroke)
            }
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(theme.textPrimary)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = desc
            setTextColor(theme.textSecondary)
            textSize = 12.5f
            setPadding(0, dp(8), 0, dp(20))
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val cancelBtn = createPillButton("Dismiss") { dialog.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) }
        }
        val actBtn = createAccentPillButton(actionLabel) {
            onAction()
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) }
        }
        btnRow.addView(cancelBtn)
        btnRow.addView(actBtn)
        card.addView(btnRow)

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }

    private fun showThemeModal() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(theme.cardBg)
                setStroke(2, theme.cardStroke)
            }
        }
        card.addView(TextView(this).apply {
            text = "Select Interface Theme"
            setTextColor(theme.textPrimary)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        })

        val options = listOf(
            "DEFAULT" to "Default (Warm Alabaster)",
            "DARK" to "Dark",
            "MATERIAL_YOU" to "Material You",
            "AURORA" to "Aurora"
        )

        for ((key, label) in options) {
            val row = TextView(this).apply {
                text = label
                setTextColor(theme.textPrimary)
                textSize = 14f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (prefs.getString("appThemeModeV4", "DEFAULT") == key) theme.pillBg else Color.TRANSPARENT)
                }
            }
            row.setOnClickListener {
                prefs.edit().putString("appThemeModeV4", key).apply()
                theme = resolveTheme(key)
                buildFullInterface()
                dialog.dismiss()
            }
            card.addView(row)
        }

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }

    // =========================================================================
    // DATA LOADERS & HELPERS
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

        styleAccessChip(rootChipBox, rootChipLabel, if (hasRoot) "ROOT: OK" else "ROOT: FIX", hasRoot)
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
                    thermalHistoryList = listOf(ThermalPoint(curT, "Live", "Waiting for database snapshot...", nowLabel))
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
                    fpsSessionHeader.text = "${s.app.uppercase()} • AVG ${s.avg} FPS"
                    fpsScrubReadout.text = "Min: ${s.min} FPS • Avg: ${s.avg} FPS • Peak: ${s.max} FPS • Avg Temp: ${s.avgTemp}°C"
                    fpsGraphView.setGraphData(s.fpsList, s.tempList, theme.good, theme.warn, "")
                } else {
                    fpsSessionHeader.text = "NO RECORDED MATCHES YET"
                    fpsScrubReadout.text = "Tap Floating Performance Pill during gameplay to record."
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
                    processContainer.addView(createBodyText("Active processes will appear here when permissions are granted."))
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
                text = "PID $pid"; setTextColor(theme.textSecondary); textSize = 11f; typeface = Typeface.MONOSPACE
            })
        }
        attachSpringAnimation(row) {
            showPermissionModal(
                title = label,
                desc = "Assign this process to efficiency cores to minimize heat, or terminate it immediately.",
                actionLabel = "Pin to Silver Cores",
                onAction = {
                    HardwareThermalControl.pinProcessToEfficiencyCores(pid)
                    refreshProcessesList()
                }
            )
        }
        return row
    }

    private fun showFpsSessionDialog() {
        if (fpsSessionsList.isEmpty()) {
            Toast.makeText(this, "No recorded matches found.", Toast.LENGTH_SHORT).show()
            return
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(theme.cardBg)
                setStroke(2, theme.cardStroke)
            }
        }
        card.addView(TextView(this).apply {
            text = "Recorded Match History"
            setTextColor(theme.textPrimary)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })

        fpsSessionsList.forEachIndexed { i, s ->
            val row = TextView(this).apply {
                text = "#${i + 1}  ${s.app}  •  Avg ${s.avg} FPS  •  Peak ${s.max} FPS"
                setTextColor(theme.textPrimary)
                textSize = 13.5f
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (i == currentFpsSessionIdx) theme.pillBg else Color.TRANSPARENT)
                }
            }
            row.setOnClickListener {
                currentFpsSessionIdx = i
                loadFpsGraphData()
                dialog.dismiss()
            }
            card.addView(row)
        }

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        dialog.show()
    }

    private fun exportLogsToFile() {
        try {
            val txt = AppLogger.getLogs()
            val name = "ActionLog_${System.currentTimeMillis()}.txt"
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(txt)
            Toast.makeText(this, "Log saved to Downloads/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleOverlayPill(enable: Boolean) {
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Please grant screen overlay permission.", Toast.LENGTH_LONG).show()
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
