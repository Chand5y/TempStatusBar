package com.personal.tempstatusbar

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var settings: SettingsManager
    private lateinit var sharedPrefs: android.content.SharedPreferences
    private lateinit var contentFrame: FrameLayout
    private lateinit var tab1Thermal: View
    private lateinit var tab2Battery: View
    private lateinit var tab3CPU: View
    private lateinit var tab4Display: View
    private lateinit var tabButtons: List<TextView>

    private lateinit var chartView: TemperatureChartView
    private var thermalTimeRangeMs = 86400000L 
    private var thermalRangeLabel = "Last 24 Hours"

    private lateinit var fpsChartView: FpsChartView
    private lateinit var fpsDetailText: TextView
    private lateinit var fpsSessionHScroll: HorizontalScrollView
    private lateinit var fpsSessionButtonContainer: LinearLayout
    private lateinit var refreshRateButtons: List<Button>
    private lateinit var screenTimeText: TextView
    private lateinit var batteryEstText: TextView
    
    private lateinit var warnS: SeekBar
    private lateinit var cutS: SeekBar
    private lateinit var resS: SeekBar
    private lateinit var maxChargeS: SeekBar
    private lateinit var resChargeS: SeekBar

    private lateinit var detailTimeText: TextView
    private lateinit var detailBadgesText: TextView
    private lateinit var detailTempText: TextView
    private lateinit var detailAppContent: TextView
    private lateinit var warnLabel: TextView
    private lateinit var cutoffLabel: TextView
    private lateinit var resumeLabel: TextView
    private lateinit var maxChargeLabel: TextView
    private lateinit var resChargeLabel: TextView
    private lateinit var batteryGraphic: BatteryGraphicView
    private lateinit var livePowerText: TextView
    private lateinit var currentLevelText: TextView
    private lateinit var healthPercentText: TextView
    private lateinit var actualCapacityText: TextView
    private lateinit var ramUsageText: TextView
    private lateinit var gpuText: TextView
    private lateinit var cpuArchitectureGrid: LinearLayout
    private lateinit var coreBlocks: Array<TextView>
    private lateinit var processListContainer: LinearLayout

    private var currentTabIndex = 0
    private var isCpuTabActive = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isPaused = false
    private var isSmartGovernorEnabled = false
    
    private var downX = 0f
    private var downY = 0f
    private var hasRoot = false

    private val liveHardwarePoller = object : Runnable {
        override fun run() {
            if (!isPaused) { refreshLiveHardware(); uiHandler.postDelayed(this, 1000L) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(cacheDir)
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> AppLogger.handleFatalCrash(throwable); System.exit(1) }

        try {
            HardwareThermalControl.init(this)
            hasRoot = HardwareThermalControl.isRootAvailable()
            dbHelper = DatabaseHelper(this)
            settings = SettingsManager(this)
            sharedPrefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)
            
            buildBaseLayout()
            checkAndRequestUsageAccess()

            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
            startMonitorService()
        } catch (t: Throwable) { AppLogger.handleFatalCrash(t) }
    }

    private fun styleControl(view: View) {
        val accentColor = ColorStateList.valueOf(Color.parseColor("#2196F3"))
        if (view is SeekBar) {
            view.progressTintList = accentColor
            view.thumbTintList = accentColor
        } else if (view is Switch) {
            view.thumbTintList = accentColor
            view.trackTintList = ColorStateList.valueOf(Color.parseColor("#90CAF9"))
        }
    }

    private fun checkAndRequestUsageAccess() {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Usage Access Required")
                .setMessage("To track app screen time and background limits, Temp Monitor requires Usage Access.")
                .setPositiveButton("GRANT") { _, _ -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                .setCancelable(false).show()
        }
    }

    override fun onResume() {
        super.onResume()
        isPaused = false
        uiHandler.post(liveHardwarePoller)
        refreshDashboardData()
        if (currentTabIndex == 3) updateActiveRefreshRateUI()
        if (isCpuTabActive) startLiveCpuUpdates()
    }

    override fun onPause() {
        super.onPause()
        isPaused = true
        isCpuTabActive = false
        uiHandler.removeCallbacks(liveHardwarePoller)
    }

    private fun startMonitorService() {
        val i = Intent(this, TempMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun haptic(v: View) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    private fun showModal(title: String, content: String) {
        val sv = ScrollView(this).apply { setPadding(40, 20, 40, 20) }
        sv.addView(txt(content, 13f, Color.GRAY).apply { typeface = Typeface.MONOSPACE })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(sv).setPositiveButton("Close", null).show()
    }

    private fun showTrackerModal() {
        val sv = ScrollView(this).apply { setPadding(40, 20, 40, 20) }
        sv.addView(txt(AppLogger.getLogs(), 11f, Color.GRAY).apply { typeface = Typeface.MONOSPACE })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Global Action Tracker").setView(sv)
            .setPositiveButton("CLOSE", null)
            .setNeutralButton("SAVE LOG") { _, _ -> AppLogger.exportLogsToDownloads("ActionLog_${System.currentTimeMillis()}.txt", AppLogger.getLogs()); Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("CLEAR") { _, _ -> AppLogger.clearLogs(); Toast.makeText(this, "Logs Cleared", Toast.LENGTH_SHORT).show() }.show()
    }

    private fun showRamDetailsModal() {
        val sv = ScrollView(this).apply { setPadding(40, 20, 40, 20) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(list)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Detailed RAM Usage").setView(sv).setPositiveButton("Close", null).show()
        thread {
            val procs = HardwareThermalControl.getDetailedRam()
            uiHandler.post {
                if (procs.isEmpty()) { list.addView(txt("Failed to read RAM via Root.", 12f, Color.GRAY)); return@post }
                procs.forEach { p ->
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,15,0,15); gravity=Gravity.CENTER_VERTICAL }
                    row.addView(txt("${p.name}\n${p.sizeMb} MB", 12f, Color.WHITE).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                    row.addView(createBtn("KILL", Color.parseColor("#D32F2F")) {
                        AppLogger.log("Killed RAM Process: ${p.name}")
                        HardwareThermalControl.killProcess(p.pid)
                        list.removeView(row)
                        Toast.makeText(this@MainActivity, "Killed ${p.name}", Toast.LENGTH_SHORT).show()
                    }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2) })
                    list.addView(row)
                }
            }
        }
    }

    private fun createBtn(t: String, bgCol: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = t; textSize = 11f; setTextColor(Color.WHITE); isAllCaps = false
            background = GradientDrawable().apply { cornerRadius = 40f; setColor(bgCol) }
            setPadding(40, 20, 40, 20); layoutParams = LinearLayout.LayoutParams(-2, -2)
            setOnClickListener { haptic(it); onClick() }
        }
    }

    private fun txt(t: String, sz: Float, col: Int, bold: Boolean = false): TextView = TextView(this).apply { text = t; textSize = sz; setTextColor(col); if (bold) typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }

    private fun card(bg: Int, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(45, 40, 45, 40); background = GradientDrawable().apply { cornerRadius = 40f; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 30 }
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { haptic(it); onClick() } }
    }

    private fun buildBaseLayout() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (isDark) Color.parseColor("#0B141A") else Color.WHITE
        
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = bg
        val decorView = window.decorView
        var flags = decorView.systemUiVisibility
        if (!isDark) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        decorView.systemUiVisibility = flags

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(-1, 150)
            setPadding(45, 0, 45, 0)
            gravity = Gravity.CENTER_VERTICAL
            elevation = 4f
        }
        
        topBar.addView(txt("Temp Monitor", 22f, Color.parseColor("#FF9800"), true).apply { 
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f) 
        })
        
        val trackerBtn = Button(this).apply {
            text = "Tracker"; textSize = 11f; isAllCaps = false
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#008069"))
            background = GradientDrawable().apply { 
                cornerRadius = 60f
                setColor(if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1A008069")) 
            }
            setPadding(35, 0, 35, 0)
            layoutParams = LinearLayout.LayoutParams(-2, 85)
            setOnClickListener { haptic(it); showTrackerModal() }
        }
        topBar.addView(trackerBtn)
        root.addView(topBar)

        contentFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(contentFrame)

        tab1Thermal = buildTab1(isDark); tab2Battery = buildTab2(isDark); tab3CPU = buildTab3(isDark); tab4Display = buildTab4(isDark)
        contentFrame.addView(tab1Thermal); contentFrame.addView(tab2Battery); contentFrame.addView(tab3CPU); contentFrame.addView(tab4Display)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, 180); setPadding(20, 10, 20, 10); elevation = 16f
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                    val idx = (event.x / (v.width / 4f)).toInt().coerceIn(0, 3)
                    if (currentTabIndex != idx) { haptic(v); switchTab(idx) }
                }
                true
            }
        }
        tabButtons = listOf("Thermal", "Battery", "CPU", "Display").mapIndexed { i, t ->
            txt(t, 13f, Color.GRAY, true).apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setOnClickListener { haptic(it); switchTab(i) }
            }
        }
        tabButtons.forEach { nav.addView(it) }
        root.addView(nav)

        setContentView(root)
        tab1Thermal.visibility = View.VISIBLE; tab2Battery.visibility = View.GONE; tab3CPU.visibility = View.GONE; tab4Display.visibility = View.GONE
        tabButtons[0].setTextColor(Color.parseColor("#00E5FF"))
    }

    private fun isTouchInside(ev: MotionEvent, view: View?): Boolean {
        if (view == null || view.visibility != View.VISIBLE || !view.isShown) return false
        val loc = IntArray(2); view.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + view.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + view.height
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (currentTabIndex == 0 && (isTouchInside(ev, chartView) || isTouchInside(ev, warnS) || isTouchInside(ev, cutS) || isTouchInside(ev, resS))) return super.dispatchTouchEvent(ev)
        if (currentTabIndex == 1 && (isTouchInside(ev, maxChargeS) || isTouchInside(ev, resChargeS))) return super.dispatchTouchEvent(ev)
        if (currentTabIndex == 3 && (isTouchInside(ev, fpsChartView) || isTouchInside(ev, fpsSessionHScroll))) return super.dispatchTouchEvent(ev)
        
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                if (abs(dx) > 150 && abs(dx) > abs(dy) * 2) {
                    if (dx > 0 && currentTabIndex > 0) { haptic(contentFrame); switchTab(currentTabIndex - 1) }
                    else if (dx < 0 && currentTabIndex < 3) { haptic(contentFrame); switchTab(currentTabIndex + 1) }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun switchTab(newIdx: Int) {
        if (newIdx == currentTabIndex) return
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val movingLeft = newIdx > currentTabIndex

        val oldTab = when(currentTabIndex) { 0 -> tab1Thermal; 1 -> tab2Battery; 2 -> tab3CPU; else -> tab4Display }
        val newTab = when(newIdx) { 0 -> tab1Thermal; 1 -> tab2Battery; 2 -> tab3CPU; else -> tab4Display }

        val sw = contentFrame.width.toFloat()
        oldTab.animate().translationX(if (movingLeft) -sw else sw).alpha(0f).setDuration(250).withEndAction { oldTab.visibility = View.GONE }.start()
        newTab.translationX = if (movingLeft) sw else -sw; newTab.alpha = 0f; newTab.visibility = View.VISIBLE
        newTab.animate().translationX(0f).alpha(1f).setDuration(250).start()

        tabButtons.forEachIndexed { i, b -> b.setTextColor(if (i == newIdx) Color.parseColor("#00E5FF") else (if (isDark) Color.GRAY else Color.DKGRAY)) }
        currentTabIndex = newIdx
        isCpuTabActive = (newIdx == 2)
        if (isCpuTabActive) startLiveCpuUpdates()
        if (newIdx == 3) updateActiveRefreshRateUI()
    }

    private fun showCustomRangeDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "e.g., 8, 36, 72"
        }
        val container = FrameLayout(this).apply { setPadding(50, 20, 50, 20); addView(input) }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Enter Custom Range (Hours)")
            .setView(container)
            .setPositiveButton("APPLY") { _, _ ->
                val hours = input.text.toString().toLongOrNull()
                if (hours != null && hours > 0) {
                    thermalTimeRangeMs = hours * 3600000L
                    thermalRangeLabel = "Last $hours Hours"
                    refreshDashboardData()
                    Toast.makeText(this, "Set range to $hours hours", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid number of hours", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun buildTab1(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.parseColor("#F4F4F6")
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        val c1 = card(cBg)
        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f; setPadding(0,0,0,15); gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(txt("📈 THERMAL HISTORY", 12f, Color.GRAY).apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f) })
        headerRow.addView(createBtn("VIEW LOGS", Color.parseColor("#333333")) {
            try {
                val validRecords = dbHelper.getAllRecords().filter { it.appDetails.isNotEmpty() && !it.appDetails.contains("TempRecord(") }
                val records = validRecords.takeLast(50).joinToString("\n\n") { "[$it.chargeType] ${it.temp}°C\n${it.appDetails}" }
                showModal("Raw Thermal Database", if (records.isEmpty()) "No logs yet." else records)
            } catch (e: Exception) {}
        }.apply { layoutParams = LinearLayout.LayoutParams(0, 80, 1f) })
        c1.addView(headerRow)

        chartView = TemperatureChartView(this).apply { isDarkMode = isDark; layoutParams = LinearLayout.LayoutParams(-1, 450) }
        c1.addView(chartView)

        c1.setOnLongClickListener {
            haptic(it)
            val options = arrayOf(
                "Last 6 Hours",
                "Last 12 Hours",
                "Last 24 Hours (1 Day)",
                "Last 48 Hours (2 Days)",
                "Last 3 Days",
                "Last 7 Days (1 Week)",
                "All Time",
                "Custom Hours..."
            )
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Select Graph Range")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> { thermalTimeRangeMs = 6 * 3600000L; thermalRangeLabel = "Last 6 Hours" }
                        1 -> { thermalTimeRangeMs = 12 * 3600000L; thermalRangeLabel = "Last 12 Hours" }
                        2 -> { thermalTimeRangeMs = 24 * 3600000L; thermalRangeLabel = "Last 24 Hours" }
                        3 -> { thermalTimeRangeMs = 48 * 3600000L; thermalRangeLabel = "Last 48 Hours" }
                        4 -> { thermalTimeRangeMs = 72 * 3600000L; thermalRangeLabel = "Last 3 Days" }
                        5 -> { thermalTimeRangeMs = 7 * 24 * 3600000L; thermalRangeLabel = "Last 7 Days" }
                        6 -> { thermalTimeRangeMs = 315360000000L; thermalRangeLabel = "All Time" }
                        7 -> { showCustomRangeDialog(); return@setItems }
                    }
                    refreshDashboardData()
                    Toast.makeText(this@MainActivity, "Range set to $thermalRangeLabel", Toast.LENGTH_SHORT).show()
                }.show()
            true
        }

        val detailRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,25,0,0); gravity = Gravity.CENTER_VERTICAL }
        
        detailTempText = txt("--°C", 30f, Color.parseColor("#FF3B30"), true).apply { 
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 25 }
            setSingleLine(true)
        }
        detailRow.addView(detailTempText)

        val infoCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        detailTimeText = txt("Tap chart to inspect", 12f, tPri, true); infoCol.addView(detailTimeText)
        
        detailBadgesText = txt("Long-press card to filter range", 11f, if(isDark) Color.LTGRAY else Color.DKGRAY).apply { setSingleLine(true) }
        infoCol.addView(detailBadgesText)
        
        detailAppContent = txt("Awaiting selection...", 11f, if(isDark) Color.LTGRAY else Color.DKGRAY).apply { setPadding(0, 8, 0, 0) }
        infoCol.addView(detailAppContent)
        
        detailRow.addView(infoCol)
        c1.addView(detailRow)
        lay.addView(c1)

        chartView.onRecordSelected = { r ->
            val time = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(r.timestamp))
            val screenIcon = if (r.screenOn) "🔆 Screen On" else "🌙 Screen Off"
            val powerIcon = if (r.isCharging) "⚡ Charging" else "🔋 Discharging"
            val traceIcon = if (r.isRoot) "🛡️ Root" else "📱 User"

            detailTimeText.text = time
            detailBadgesText.text = "$screenIcon  •  $powerIcon  •  $traceIcon"
            detailTempText.text = "${r.temp}°C"

            detailAppContent.text = r.appDetails.lines().joinToString("\n") { line ->
                if (line.startsWith("• ")) {
                    val raw = line.removePrefix("• "); val parts = raw.split(" — ")
                    "• ${AppLabelHelper.getAppName(this, parts.getOrNull(0) ?: "")} — ${parts.getOrNull(1) ?: ""}"
                } else line
            }
        }

        val c3 = card(cBg) { showModal("PMIC Safety Engine", "Cut Off (🛑): Breaks circuit when limit is reached.\nResume (🔄): Restores circuit when cooled.") }
        val rootTag = if (!hasRoot) " (Root Required)" else ""
        c3.addView(txt("🛡 HARDWARE PROTECTION$rootTag", 12f, Color.GRAY).apply { setPadding(0,0,0,25) })

        warnLabel = txt("🔔 Warning Sound Alert: ${settings.warningTemp}°C", 14f, tPri); c3.addView(warnLabel)
        warnS = SeekBar(this).apply { 
            max = 13; progress = settings.warningTemp - 35; setPadding(0,10,0,20)
            styleControl(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { 
                    val v = 35 + p; settings.warningTemp = v; warnLabel.text = "🔔 Warning Sound Alert: $v°C"
                    if (b) s?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { s?.let { haptic(it) } }
            })
        }
        c3.addView(warnS)

        cutoffLabel = txt("🛑 Cut Off Charging (PMIC): ${settings.cutoffTemp}°C", 14f, tPri).apply { setPadding(0,10,0,0) }; c3.addView(cutoffLabel)
        cutS = SeekBar(this).apply { 
            max = 12; progress = settings.cutoffTemp - 38; setPadding(0,10,0,20)
            styleControl(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { 
                    val v = 38 + p; settings.cutoffTemp = v; cutoffLabel.text = "🛑 Cut Off Charging (PMIC): $v°C"
                    if (settings.resumeTemp >= v - 1) { settings.resumeTemp = v - 2; resS.progress = (v - 2) - 32; resumeLabel.text = "🔄 Resume Charging: ${v - 2}°C" }
                    if (b) s?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { s?.let { haptic(it) } }
            })
        }
        c3.addView(cutS)

        resumeLabel = txt("🔄 Resume Charging: ${settings.resumeTemp}°C", 14f, tPri).apply { setPadding(0,10,0,0) }; c3.addView(resumeLabel)
        resS = SeekBar(this).apply { 
            max = 13; progress = settings.resumeTemp - 32; setPadding(0,10,0,10)
            styleControl(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { 
                    var v = 32 + p
                    if (v > settings.cutoffTemp - 2) { v = settings.cutoffTemp - 2; s?.progress = v - 32 }
                    settings.resumeTemp = v; resumeLabel.text = "🔄 Resume Charging: $v°C"
                    if (b) s?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { s?.let { haptic(it) } }
            })
        }
        c3.addView(resS); lay.addView(c3)
        
        if (!hasRoot) { warnS.isEnabled=false; cutS.isEnabled=false; resS.isEnabled=false; c3.alpha=0.4f }

        val c4 = card(cBg)
        c4.addView(txt("⚙️ NOTIFICATION PREFERENCES", 12f, Color.GRAY).apply { setPadding(0,0,0,20) })
        val s1 = Switch(this).apply { text = "📱 Show Status Bar Notification"; setTextColor(tPri); setPadding(0,0,0,15); isChecked = settings.showNotification; styleControl(this); setOnCheckedChangeListener { v, c -> haptic(v); settings.showNotification = c; startMonitorService() } }
        val s2 = Switch(this).apply { text = "⚡ Show Real-Time Wattage View"; setTextColor(tPri); isChecked = settings.showPowerMetrics; styleControl(this); setOnCheckedChangeListener { v, c -> haptic(v); settings.showPowerMetrics = c; startMonitorService() } }
        c4.addView(s1); c4.addView(s2)
        lay.addView(c4)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun buildTab2(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.parseColor("#F4F4F6")
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        val c1 = card(cBg)
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        batteryGraphic = BatteryGraphicView(this).apply { layoutParams = LinearLayout.LayoutParams(220, 120).apply{ rightMargin = 40 } }
        r1.addView(batteryGraphic)

        val pInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        currentLevelText = txt("--%", 28f, tPri, true); pInfo.addView(currentLevelText)
        livePowerText = txt("Calculating...", 13f, Color.GRAY); pInfo.addView(livePowerText)
        r1.addView(pInfo)
        c1.addView(r1)
        lay.addView(c1)

        val cEst = card(cBg)
        cEst.addView(txt("ESTIMATED TIME REMAINING", 12f, Color.GRAY).apply { setPadding(0,0,0,15) })
        batteryEstText = txt("Calculating based on live drain...", 18f, Color.parseColor("#00E676"), true)
        cEst.addView(batteryEstText); lay.addView(cEst)

        val cLimiter = card(cBg) {
            showModal("Smart Charge Limiter", "Preserve battery health by limiting the maximum charge level. The device will automatically disconnect power when it hits the limit and reconnect when it drops to the resume point.")
        }
        val rootTag = if (!hasRoot) " (Root Required)" else ""
        cLimiter.addView(txt("⚡ SMART CHARGE LIMITER$rootTag", 12f, Color.GRAY).apply { setPadding(0,0,0,20) })

        val swLimiter = Switch(this).apply { text = "Enable Charge Limiter"; setTextColor(tPri); setPadding(0,0,0,15); isChecked = settings.chargeLimitEnabled; styleControl(this) }
        cLimiter.addView(swLimiter)

        maxChargeLabel = txt("🛑 Cut Off Charging At: ${settings.chargeLimitMax}%", 14f, tPri).apply { setPadding(0,10,0,0) }
        maxChargeS = SeekBar(this).apply { 
            max = 50; progress = settings.chargeLimitMax - 50; setPadding(0,10,0,20)
            styleControl(this)
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                    val v = 50 + p; settings.chargeLimitMax = v; maxChargeLabel.text = "🛑 Cut Off Charging At: $v%"
                    if (settings.chargeLimitResume >= v) {
                        settings.chargeLimitResume = v - 1; resChargeS.progress = (v - 1) - 40
                        resChargeLabel.text = "🔄 Resume Charging At: ${v - 1}%"
                    }
                    if (b) s?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { s?.let{haptic(it)} }
            })
        }
        
        resChargeLabel = txt("🔄 Resume Charging At: ${settings.chargeLimitResume}%", 14f, tPri).apply { setPadding(0,10,0,0) }
        resChargeS = SeekBar(this).apply { 
            max = 50; progress = settings.chargeLimitResume - 40; setPadding(0,10,0,10)
            styleControl(this)
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                    var v = 40 + p
                    if (v >= settings.chargeLimitMax) { v = settings.chargeLimitMax - 1; s?.progress = v - 40 }
                    settings.chargeLimitResume = v; resChargeLabel.text = "🔄 Resume Charging At: $v%"
                    if (b) s?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { s?.let{haptic(it)} }
            })
        }

        swLimiter.setOnCheckedChangeListener { v, c -> haptic(v); settings.chargeLimitEnabled = c; maxChargeS.isEnabled = c; resChargeS.isEnabled = c }
        maxChargeS.isEnabled = settings.chargeLimitEnabled; resChargeS.isEnabled = settings.chargeLimitEnabled

        cLimiter.addView(maxChargeLabel); cLimiter.addView(maxChargeS)
        cLimiter.addView(resChargeLabel); cLimiter.addView(resChargeS)
        
        if (!hasRoot) { swLimiter.isEnabled=false; maxChargeS.isEnabled=false; resChargeS.isEnabled=false; cLimiter.alpha=0.4f }
        lay.addView(cLimiter)

        val c3 = card(cBg) {
            try {
                val h = BatteryHealthHelper.getHealthData(this@MainActivity)
                showModal("Battery Diagnostic", "Design: ${h.designCapacityMah} mAh\nActual: ${h.actualCapacityMah} mAh\nCycles: ${h.cycleCount}\nWear: ${100 - h.healthPercent}%\nStatus: ${h.statusText}")
            } catch (e: Exception) {}
        }
        c3.addView(txt("BATTERY DEGRADATION HEALTH (TAP DETAILS)", 12f, Color.GRAY))
        healthPercentText = txt("--%", 32f, tPri, true); c3.addView(healthPercentText)
        actualCapacityText = txt("Loading metrics...", 14f, tPri).apply { setPadding(0,10,0,0) }; c3.addView(actualCapacityText)
        lay.addView(c3)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun buildTab3(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.parseColor("#F4F4F6")
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        val rCard = card(cBg) { if(hasRoot) showRamDetailsModal() else Toast.makeText(this,"Root Required", Toast.LENGTH_SHORT).show() }
        val hdRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hdRow.addView(txt("SoC: ${HardwareThermalControl.getHardwareInfo()}", 14f, tPri, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        gpuText = txt("GPU: --%", 14f, Color.parseColor("#00E5FF"), true); hdRow.addView(gpuText)
        rCard.addView(hdRow)
        rCard.addView(txt("LIVE MEMORY (RAM) - TAP FOR DETAILS", 12f, Color.GRAY).apply{setPadding(0,25,0,15)})

        val ramRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ramUsageText = txt("Loading...", 15f, tPri, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        ramRow.addView(ramUsageText)
        val cleanBtn = createBtn("CLEAN RAM", Color.parseColor("#2196F3")) { HardwareThermalControl.clearRamCaches(); Toast.makeText(this@MainActivity, "Kernel RAM caches dropped.", Toast.LENGTH_SHORT).show() }
        if (!hasRoot) { cleanBtn.isEnabled=false; cleanBtn.alpha=0.5f }
        ramRow.addView(cleanBtn)
        rCard.addView(ramRow); lay.addView(rCard)

        val rootTag = if (!hasRoot) " (Root Required)" else ""
        val sgCard = card(cBg) { showModal("Smart Thermal Governor", "Throttles Prime Core (C7) and pins background apps to silver cores when Warning Temp is hit.") }
        val sgSwitch = Switch(this).apply {
            text = "Auto-Thermal Smart Governor$rootTag"; setTextColor(tPri); isChecked = isSmartGovernorEnabled; styleControl(this)
            setOnCheckedChangeListener { v, c -> haptic(v); isSmartGovernorEnabled = c; sendBroadcast(Intent("ACTION_TOGGLE_SMART_GOVERNOR").apply { putExtra("state", c); setPackage(packageName) }) }
        }
        if (!hasRoot) { sgSwitch.isEnabled=false; sgCard.alpha=0.4f }
        sgCard.addView(sgSwitch)
        lay.addView(sgCard)

        val cCard = card(cBg)
        val cCardHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,0,0,20) }
        cCardHeader.addView(txt("CPU ARCHITECTURE MAP$rootTag", 11f, Color.GRAY).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        cCardHeader.addView(Button(this).apply {
            text = "ℹ️ INFO"; textSize = 10f; setTextColor(Color.GRAY); setBackgroundColor(Color.TRANSPARENT); setPadding(0,0,0,0)
            setOnClickListener { haptic(this); showModal("Core Map Instructions", "Long-press any core to forcefully toggle it ONLINE or OFFLINE in the kernel.\n\nNote: The Snapdragon 870 kernel instantly crashes if the Prime Core (C7) is offlined. Long-pressing C7 will THROTTLE its max frequency instead.\n\nAll actions are actively recorded in the Global Tracker log.") }
        })
        cCard.addView(cCardHeader)
        
        cpuArchitectureGrid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; layoutParams = LinearLayout.LayoutParams(-1, 380) }
        coreBlocks = Array(8) { TextView(this) }

        fun coreBox(idx: Int): TextView = txt("C$idx", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(6,6,6,6) }
            background = GradientDrawable().apply { cornerRadius = 30f; setColor(Color.DKGRAY) }
            setOnLongClickListener { v ->
                haptic(v)
                if (!hasRoot) { Toast.makeText(context, "Root Required", Toast.LENGTH_SHORT).show(); return@setOnLongClickListener true }
                val success: Boolean
                if (idx == 7) { 
                    val throttle = !text.toString().contains("THROTTLED")
                    success = HardwareThermalControl.executeRootCommand("Throttle C7", if(throttle) "cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_min_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq" else "cat /sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq > /sys/devices/system/cpu/cpu7/cpufreq/scaling_max_freq")
                } else { 
                    val offline = !text.toString().contains("OFF")
                    success = HardwareThermalControl.executeRootCommand("Toggle C$idx", "echo ${if (offline) "0" else "1"} > /sys/devices/system/cpu/cpu$idx/online")
                }
                
                if (success) Toast.makeText(context, "Success: Core $idx modified", Toast.LENGTH_SHORT).show()
                else Toast.makeText(context, "Failed: Kernel Denied", Toast.LENGTH_SHORT).show()
                true
            }
        }

        val silCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-1,1f) }; for(i in 0..3) { val b = coreBox(i); coreBlocks[i] = b; silCol.addView(b) }
        val gldCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-1,1f) }; for(i in 4..6) { val b = coreBox(i); coreBlocks[i] = b; gldCol.addView(b) }
        val prmCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-1,1f) }; val c7 = coreBox(7); coreBlocks[7] = c7; prmCol.addView(c7)
        cpuArchitectureGrid.addView(silCol); cpuArchitectureGrid.addView(gldCol); cpuArchitectureGrid.addView(prmCol)
        if (!hasRoot) cCard.alpha = 0.4f
        cCard.addView(cpuArchitectureGrid); lay.addView(cCard)

        val pCard = card(cBg)
        pCard.addView(txt("LIVE CPU STRESS & TERMINATION$rootTag", 12f, Color.GRAY).apply{setPadding(0,0,0,20)})
        processListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutTransition = LayoutTransition() }
        if (!hasRoot) pCard.alpha = 0.4f
        pCard.addView(processListContainer); lay.addView(pCard)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun buildTab4(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.parseColor("#F4F4F6")
        val tPri = if (isDark) Color.WHITE else Color.BLACK
        val rootTag = if (!hasRoot) " (Root Required)" else ""

        val c1 = card(cBg)
        c1.addView(txt("DISPLAY REFRESH RATE$rootTag", 12f, Color.GRAY).apply { setPadding(0, 0, 0, 20) })
        val rrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        
        refreshRateButtons = listOf(60, 90, 120).map { r ->
            createBtn("${r}Hz", Color.parseColor("#333333")) {
                HardwareThermalControl.forceRefreshRate(r)
                Toast.makeText(this@MainActivity, "Forcing ${r}Hz", Toast.LENGTH_SHORT).show()
                uiHandler.postDelayed({ updateActiveRefreshRateUI() }, 1500)
            }.apply { 
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(10,0,10,0) } 
                if (!hasRoot) isEnabled = false
            }
        }
        if (!hasRoot) c1.alpha = 0.4f
        refreshRateButtons.forEach { rrRow.addView(it) }
        c1.addView(rrRow); lay.addView(c1)

        val c2 = card(cBg)
        c2.addView(txt("LIVE FPS METRE OVERLAY", 12f, Color.GRAY).apply { setPadding(0, 0, 0, 10) })
        val sFps = Switch(this).apply {
            text = "Show Temperature on Pill"; setTextColor(tPri); setPadding(0,0,0,20); styleControl(this)
            isChecked = sharedPrefs.getBoolean("showTempInFpsOverlay", false)
            setOnCheckedChangeListener { v, c -> haptic(v); sharedPrefs.edit().putBoolean("showTempInFpsOverlay", c).apply() }
        }
        c2.addView(sFps)
        c2.addView(txt("Tap pill to toggle Recording (🔴). Long-press to exit.", 13f, tPri).apply { setPadding(0, 0, 0, 20) })
        c2.addView(createBtn("LAUNCH FPS OVERLAY", Color.parseColor("#2196F3")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            } else { startService(Intent(this@MainActivity, FpsOverlayService::class.java)) }
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2) })
        lay.addView(c2)

        val c3 = card(cBg)
        c3.addView(txt("GAMING FPS & THERMAL TIMELINE", 12f, Color.GRAY).apply { setPadding(0, 0, 0, 15) })
        fpsChartView = FpsChartView(this).apply { isDarkMode = isDark; layoutParams = LinearLayout.LayoutParams(-1, 450) }
        c3.addView(fpsChartView)

        fpsSessionHScroll = HorizontalScrollView(this).apply { 
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 } 
            isScrollContainer = false
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        fpsSessionButtonContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fpsSessionHScroll.addView(fpsSessionButtonContainer); c3.addView(fpsSessionHScroll)

        fpsDetailText = txt("Select a session below to view timeline", 12f, tPri).apply { setPadding(0, 20, 0, 0) }
        fpsChartView.onScrub = { fps, temp -> fpsDetailText.text = "📍 Scrub: $fps FPS  |  $temp°C" }
        c3.addView(fpsDetailText); lay.addView(c3)

        val c4 = card(cBg)
        c4.addView(txt("TODAY'S SCREEN TIME", 12f, Color.GRAY).apply { setPadding(0, 0, 0, 15) })
        screenTimeText = txt("Loading usage stats...", 13f, tPri)
        c4.addView(screenTimeText); lay.addView(c4)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun updateActiveRefreshRateUI() {
        if (!hasRoot) return
        try {
            val activeHz = Settings.System.getInt(contentResolver, "user_refresh_rate", 120)
            uiHandler.post {
                refreshRateButtons.forEachIndexed { i, btn ->
                    val targetHz = listOf(60, 90, 120)[i]
                    (btn.background as GradientDrawable).setColor(if (targetHz == activeHz) Color.parseColor("#2196F3") else Color.parseColor("#333333"))
                }
            }
        } catch (e: Exception) {}
        
        thread {
            try {
                val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
                val stats = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis())
                val sorted = stats.filter { it.totalTimeInForeground > 60000L }.sortedByDescending { it.totalTimeInForeground }.take(5)
                
                var totalTime = 0L
                val sb = StringBuilder()
                sorted.forEach { s ->
                    totalTime += s.totalTimeInForeground
                    val mins = (s.totalTimeInForeground / 1000) / 60
                    val friendlyName = AppLabelHelper.getAppName(this, s.packageName)
                    sb.append("• $friendlyName: ${mins/60}h ${mins%60}m\n")
                }
                
                val totalMins = (totalTime / 1000) / 60
                uiHandler.post { screenTimeText.text = "Total Active: ${totalMins/60}h ${totalMins%60}m\n\n${sb.toString()}" }
            } catch (e: Exception) {}
        }
    }

    private fun refreshLiveHardware() {
        try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val isPlugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val lvl = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
            val pct = if (scale > 0) (lvl * 100 / scale.toFloat()).toInt() else 0

            val stats = PowerHardwareHelper.readPowerStats(this, isPlugged)
            currentLevelText.text = "$pct%"
            batteryGraphic.update(pct, stats.isCharging)
            livePowerText.text = if (stats.isCharging) "Charging AC\n⚡ ${stats.wattage}W (+${stats.currentMa} mA)" else "Discharging\n🔋 -${stats.wattage}W (${stats.currentMa} mA)"

            if (!stats.isCharging && stats.currentMa > 0) {
                val capacity = BatteryHealthHelper.getHealthData(this).actualCapacityMah
                val hoursLeft = (capacity * (pct / 100f)) / stats.currentMa
                val hrs = hoursLeft.toInt(); val mins = ((hoursLeft - hrs) * 60).toInt()
                batteryEstText.text = "~$hrs hrs $mins mins remaining"
                batteryEstText.setTextColor(if (hoursLeft > 3) Color.parseColor("#00E676") else Color.parseColor("#FF9800"))
            } else if (stats.isCharging) { batteryEstText.text = "Charging in progress..."; batteryEstText.setTextColor(Color.parseColor("#2196F3")) }
        } catch (e: Exception) {}
    }

    private fun refreshDashboardData() {
        refreshLiveHardware()
        try {
            val h = BatteryHealthHelper.getHealthData(this)
            healthPercentText.text = "${h.healthPercent}%"
            actualCapacityText.text = "Actual: ${h.actualCapacityMah} mAh\nDesign: ${h.designCapacityMah} mAh\nCycles: ${h.cycleCount}"
        } catch(e: Exception) {}
        
        try { 
            val allRecords = dbHelper.getAllRecords()
            val cutoff = System.currentTimeMillis() - thermalTimeRangeMs
            chartView.setData(allRecords.filter { it.timestamp >= cutoff }) 
        } catch(e: Exception) {}

        try {
            val sessions = dbHelper.getFpsSessions()
            fpsSessionButtonContainer.removeAllViews()
            if (sessions.isNotEmpty()) {
                val latest = sessions.first()
                fpsChartView.setSession(latest); displaySessionDetails(latest)
                
                sessions.forEachIndexed { idx, s ->
                    val btn = createBtn("${s.appName} (${s.avgFps})", if (idx==0) Color.parseColor("#00E676") else Color.parseColor("#333333")) {
                        fpsChartView.setSession(s); displaySessionDetails(s)
                        for (j in 0 until fpsSessionButtonContainer.childCount) { ((fpsSessionButtonContainer.getChildAt(j) as? LinearLayout)?.getChildAt(0) as? Button)?.background?.setTint(Color.parseColor("#333333")) }
                    }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(6,0,6,0) } }
                    
                    btn.setOnLongClickListener { v ->
                        haptic(v)
                        AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Delete Session")
                            .setMessage("Permanently remove ${s.appName} recording?")
                            .setPositiveButton("DELETE") { _, _ -> 
                                dbHelper.deleteFpsSession(s.id)
                                AppLogger.log("Deleted FPS Session: ${s.appName}")
                                refreshDashboardData() 
                            }
                            .setNegativeButton("CANCEL", null).show()
                        true 
                    }
                    
                    fpsSessionButtonContainer.addView(LinearLayout(this).apply{addView(btn)})
                }
            } else { fpsChartView.setSession(null); fpsDetailText.text = "No gaming FPS recordings yet." }
        } catch (e: Exception) {}
    }

    private fun displaySessionDetails(s: FpsSession) {
        val date = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(s.timestamp))
        val minStr = if (s.durationSec >= 60) "${s.durationSec / 60}m ${s.durationSec % 60}s" else "${s.durationSec}s"
        fpsDetailText.text = "🎮 ${s.appName} • $date\n⏱️ Duration: $minStr  |  🌡️ Avg Temp: ${s.avgTemp}°C\n📊 Avg: ${s.avgFps} FPS  •  📉 Min: ${s.minFps} FPS  •  📈 Max: ${s.maxFps} FPS"
    }

    private fun startLiveCpuUpdates() {
        thread {
            while (isCpuTabActive) {
                val ram = HardwareThermalControl.getRamUsage(this@MainActivity)
                val gpu = HardwareThermalControl.getGpuUsage()
                val freqs = HardwareThermalControl.getCoreFrequencies()
                val procs = HardwareThermalControl.getKernelProcessSnapshot(this@MainActivity)

                uiHandler.post {
                    if (!isCpuTabActive) return@post
                    ramUsageText.text = ram; gpuText.text = "GPU: $gpu"
                    for (i in 0..7) {
                        val blk = coreBlocks[i]
                        if (i < freqs.size) {
                            val fStr = freqs[i]; val mhz = fStr.replace(" MHz", "").toIntOrNull() ?: 0
                            if (fStr == "Offline" || (i == 7 && mhz <= 844 && mhz > 0)) { (blk.background as GradientDrawable).setColor(Color.parseColor("#333333")); blk.text = "C$i\n${if(fStr == "Offline") "OFF" else "THROTTLED"}" } 
                            else { (blk.background as GradientDrawable).setColor(when { mhz < 1000 -> Color.parseColor("#4CAF50"); mhz < 2000 -> Color.parseColor("#FF9800"); else -> Color.parseColor("#F44336") }); blk.text = "C$i\n$fStr" }
                        }
                    }
                    processListContainer.removeAllViews()
                    if (procs.isEmpty()) { processListContainer.addView(txt(if(hasRoot)"Waiting for kernel data..." else "Requires Root", 13f, Color.GRAY)); return@post }
                    procs.forEach { p ->
                        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 15, 0, 15); gravity = Gravity.CENTER_VERTICAL }
                        row.addView(txt("${p.name} — ${p.cpu}%", 12f, if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) Color.WHITE else Color.BLACK).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 15 }; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                        row.addView(createBtn("RESTRICT", Color.parseColor("#FF9800")) { HardwareThermalControl.pinProcessToEfficiencyCores(p.pid); Toast.makeText(this@MainActivity, "Pinned to Cores 0-3", Toast.LENGTH_SHORT).show() }.apply { layoutParams = LinearLayout.LayoutParams(-2, 80).apply { rightMargin = 15 }; if(!hasRoot) isEnabled=false })
                        row.addView(createBtn("KILL", Color.parseColor("#D32F2F")) { AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Terminate Process?").setMessage("Kill ${p.name}?").setPositiveButton("KILL") { _, _ -> HardwareThermalControl.killProcess(p.pid); Toast.makeText(this@MainActivity, "Signal sent", Toast.LENGTH_SHORT).show() }.setNegativeButton("Cancel", null).show() }.apply { layoutParams = LinearLayout.LayoutParams(-2, 80); if(!hasRoot) isEnabled=false })
                        processListContainer.addView(row)
                    }
                }
                Thread.sleep(2000)
            }
        }
    }
}
