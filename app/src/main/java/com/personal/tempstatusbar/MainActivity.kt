package com.personal.tempstatusbar

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs

class BatteryGraphicView(context: Context) : View(context) {
    var level = 0
    var isCharging = false
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    
    fun update(newLevel: Int, charging: Boolean) {
        level = newLevel; isCharging = charging
        fillPaint.color = when {
            isCharging -> Color.parseColor("#00E676")
            level > 50 -> Color.parseColor("#4CAF50")
            level > 30 -> Color.parseColor("#FFD600")
            level > 20 -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#F44336")
        }
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 10f; val bw = width - 30f; val bh = height - 20f
        canvas.drawRoundRect(pad, pad, bw, pad + bh, 15f, 15f, outlinePaint)
        canvas.drawRoundRect(bw, pad + (bh/3f), bw + 15f, pad + (bh*2f/3f), 5f, 5f, fillPaint)
        val fillWidth = (bw - pad - 10f) * (level / 100f)
        if (fillWidth > 0) canvas.drawRoundRect(pad + 5f, pad + 5f, pad + 5f + fillWidth, pad + bh - 5f, 10f, 10f, fillPaint)
        if (isCharging) {
            val bolt = Path(); val cx = bw / 2f; val cy = height / 2f
            bolt.moveTo(cx + 10f, cy - 20f); bolt.lineTo(cx - 10f, cy + 5f)
            bolt.lineTo(cx + 5f, cy + 5f); bolt.lineTo(cx - 10f, cy + 25f)
            bolt.lineTo(cx + 15f, cy); bolt.lineTo(cx, cy); bolt.close()
            canvas.drawPath(bolt, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL })
        }
    }
}

class FpsChartView(context: Context) : View(context) {
    private var sessions: List<FpsSession> = emptyList()
    var onSessionSelected: ((FpsSession) -> Unit)? = null
    var isDarkMode = true
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }

    fun setData(data: List<FpsSession>) {
        sessions = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sessions.isEmpty()) {
            textPaint.color = if (isDarkMode) Color.parseColor("#8E8E93") else Color.parseColor("#98989D")
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("No FPS data recorded yet.", width / 2f, height / 2f, textPaint)
            return
        }
        val padX = 40f; val padY = 40f
        val w = width - padX * 2; val h = height - padY * 2
        val stepX = w / sessions.size.toFloat()
        val maxFpsVal = (sessions.maxOf { it.maxFps } + 10).coerceAtLeast(60).toFloat()

        for (i in sessions.indices) {
            val s = sessions[i]
            val left = padX + (i * stepX) + 4f
            val right = padX + ((i + 1) * stepX) - 4f
            val top = padY + h - ((s.avgFps.toFloat() / maxFpsVal) * h)
            val bottom = padY + h

            barPaint.color = when {
                s.avgFps >= 55 -> Color.parseColor("#4CAF50")
                s.avgFps >= 30 -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#F44336")
            }
            canvas.drawRoundRect(left, top, right, bottom, 8f, 8f, barPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sessions.isEmpty()) return false
        if (event.action == MotionEvent.ACTION_DOWN) {
            val padX = 40f
            val stepX = (width - padX * 2) / sessions.size.toFloat()
            val idx = ((event.x - padX) / stepX).toInt().coerceIn(0, sessions.size - 1)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onSessionSelected?.invoke(sessions[idx])
            return true
        }
        return super.onTouchEvent(event)
    }
}

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
    private lateinit var fpsChartView: FpsChartView
    private lateinit var fpsDetailText: TextView
    private lateinit var warnS: SeekBar
    private lateinit var cutS: SeekBar
    private lateinit var resS: SeekBar
    
    private lateinit var detailTimeText: TextView
    private lateinit var detailTempText: TextView
    private lateinit var detailAppContent: TextView
    private lateinit var warnLabel: TextView
    private lateinit var cutoffLabel: TextView
    private lateinit var resumeLabel: TextView
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

    private val liveHardwarePoller = object : Runnable {
        override fun run() {
            if (!isPaused) { refreshLiveHardware(); uiHandler.postDelayed(this, 1000L) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            HardwareThermalControl.init(this)
            dbHelper = DatabaseHelper(this)
            settings = SettingsManager(this)
            sharedPrefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)
            buildBaseLayout()
            
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
            
            if (!HardwareThermalControl.isRootAvailable()) {
                val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
                if (mode != AppOpsManager.MODE_ALLOWED) {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    Toast.makeText(this, "Please enable Usage Access for Non-Root tracking.", Toast.LENGTH_LONG).show()
                }
            }
            
            startMonitorService()
        } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        isPaused = false
        uiHandler.post(liveHardwarePoller)
        refreshDashboardData()
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
        sv.addView(txt(content, 12f, Color.GRAY).apply { typeface = Typeface.MONOSPACE })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(sv).setPositiveButton("Close", null).show()
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
                    row.addView(Button(this@MainActivity).apply { text="KILL"; textSize=10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#D32F2F")); setPadding(10,0,10,0); layoutParams=LinearLayout.LayoutParams(-2, -2)
                        setOnClickListener { haptic(this); HardwareThermalControl.killProcess(p.pid); list.removeView(row); Toast.makeText(this@MainActivity, "Killed ${p.name}", Toast.LENGTH_SHORT).show() }
                    })
                    list.addView(row)
                }
            }
        }
    }

    private fun txt(t: String, sz: Float, col: Int, bold: Boolean = false): TextView = TextView(this).apply { text = t; textSize = sz; setTextColor(col); if (bold) typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    
    private fun card(bg: Int, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(45, 40, 45, 40); background = GradientDrawable().apply { cornerRadius = 40f; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 30 }
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { haptic(this); onClick() } }
    }

    private fun buildBaseLayout() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (isDark) Color.BLACK else Color.parseColor("#F4F4F6")
        
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        
        // WhatsApp Style Header
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, 160)
            setPadding(50, 0, 50, 0)
            gravity = Gravity.CENTER_VERTICAL
            elevation = 8f
        }
        topBar.addView(txt("Temp Monitor", 22f, if (isDark) Color.WHITE else Color.parseColor("#000000"), true).apply { 
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f) 
        })
        root.addView(topBar)
        
        contentFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(contentFrame)

        tab1Thermal = buildTab1(isDark); tab2Battery = buildTab2(isDark); tab3CPU = buildTab3(isDark); tab4Display = buildTab4(isDark)
        contentFrame.addView(tab1Thermal); contentFrame.addView(tab2Battery); contentFrame.addView(tab3CPU); contentFrame.addView(tab4Display)

        // Bottom Navigation Bar
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, 180); setPadding(20, 10, 20, 10)
            elevation = 16f
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
                setOnClickListener { haptic(this); switchTab(i) } 
            }
        }
        tabButtons.forEach { nav.addView(it) }
        root.addView(nav) // Nav added at the bottom
        
        setContentView(root)
        tab1Thermal.visibility = View.VISIBLE; tab2Battery.visibility = View.GONE; tab3CPU.visibility = View.GONE; tab4Display.visibility = View.GONE
        tabButtons[0].setTextColor(Color.parseColor("#00E5FF"))
    }

    private fun isTouchInside(ev: MotionEvent, view: View): Boolean {
        if (view.visibility != View.VISIBLE || !view.isShown) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + view.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + view.height
    }

    private var downX = 0f; private var downY = 0f
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (currentTabIndex == 0 && (isTouchInside(ev, chartView) || isTouchInside(ev, warnS) || isTouchInside(ev, cutS) || isTouchInside(ev, resS))) {
            return super.dispatchTouchEvent(ev)
        }
        if (currentTabIndex == 3 && isTouchInside(ev, fpsChartView)) {
            return super.dispatchTouchEvent(ev)
        }
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
        
        newTab.translationX = if (movingLeft) sw else -sw
        newTab.alpha = 0f
        newTab.visibility = View.VISIBLE
        newTab.animate().translationX(0f).alpha(1f).setDuration(250).start()

        tabButtons.forEachIndexed { i, b -> b.setTextColor(if (i == newIdx) Color.parseColor("#00E5FF") else (if (isDark) Color.GRAY else Color.DKGRAY)) }
        currentTabIndex = newIdx
        isCpuTabActive = (newIdx == 2)
        if (isCpuTabActive) startLiveCpuUpdates()
    }

    private fun buildTab1(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        val c1 = card(cBg)
        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
        headerRow.addView(txt("📈 THERMAL HISTORY", 12f, Color.GRAY).apply { layoutParams = LinearLayout.LayoutParams(0,-2,1f); setPadding(0,0,0,15) })
        headerRow.addView(txt("📋 VIEW LOGS", 12f, Color.parseColor("#00E5FF"), true).apply { 
            layoutParams = LinearLayout.LayoutParams(0,-2,1f); gravity = Gravity.END; setPadding(0,0,0,15)
            setOnClickListener {
                haptic(this)
                try {
                    val validRecords = dbHelper.getAllRecords().filter { it.appDetails.isNotEmpty() && !it.appDetails.contains("TempRecord(") }
                    val records = validRecords.takeLast(50).joinToString("\n\n") { "[$it.chargeType] ${it.temp}°C\n${it.appDetails}" }
                    showModal("Raw Thermal Database", if (records.isEmpty()) "No logs yet." else records)
                } catch (e: Exception) { showModal("Error", "Could not read database") }
            }
        })
        c1.addView(headerRow)
        
        chartView = TemperatureChartView(this).apply { isDarkMode = isDark; layoutParams = LinearLayout.LayoutParams(-1, 450) }
        c1.addView(chartView)

        val detailRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,25,0,0); gravity = Gravity.CENTER_VERTICAL }
        detailTempText = txt("--°C", 38f, Color.parseColor("#FF3B30"), true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        detailRow.addView(detailTempText)
        
        val infoCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 2f) }
        detailTimeText = txt("Tap chart to inspect", 13f, tPri, true); infoCol.addView(detailTimeText)
        detailAppContent = txt("Awaiting selection...", 12f, if(isDark) Color.LTGRAY else Color.DKGRAY).apply { typeface = Typeface.MONOSPACE }
        infoCol.addView(detailAppContent)
        detailRow.addView(infoCol)
        c1.addView(detailRow)
        lay.addView(c1)

        chartView.onRecordSelected = { r ->
            val rootStr = if (r.isRoot) "🛡️ Root Trace" else "📱 Non-Root Trace"
            val screenStr = if (r.screenOn) "Screen ON" else "Screen OFF"
            detailTimeText.text = "${SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(r.timestamp))} • $screenStr"
            detailTempText.text = "${r.temp}°C"
            detailAppContent.text = "[$rootStr | ${r.chargeType}]\n${r.appDetails}"
        }

        val c3 = card(cBg) { showModal("PMIC Safety Engine", "Cut Off (🛑): Instantly breaks the circuit from the charger to the battery when this temp is hit.\nResume (🔄): Restores the circuit once the battery has cooled down.") }
        c3.addView(txt("🛡️ HARDWARE THERMAL PROTECTION", 12f, Color.GRAY).apply { setPadding(0,0,0,25) })
        
        warnLabel = txt("🔔 Warning Sound Alert: ${settings.warningTemp}°C", 14f, tPri); c3.addView(warnLabel)
        warnS = SeekBar(this).apply { max = 13; progress = settings.warningTemp - 35; setPadding(0,10,0,20); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { val v=35+p; settings.warningTemp=v; warnLabel.text="🔔 Warning Sound Alert: $v°C" }
            override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) { haptic(this@apply) }
        })}
        c3.addView(warnS)

        cutoffLabel = txt("🛑 Cut Off Charging (PMIC): ${settings.cutoffTemp}°C", 14f, tPri).apply { setPadding(0,10,0,0) }; c3.addView(cutoffLabel)
        cutS = SeekBar(this).apply { max = 12; progress = settings.cutoffTemp - 38; setPadding(0,10,0,20); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { val v=38+p; settings.cutoffTemp=v; cutoffLabel.text="🛑 Cut Off Charging (PMIC): $v°C"
                if (settings.resumeTemp >= v-1) { settings.resumeTemp = v-2; resS.progress = (v-2)-32; resumeLabel.text="🔄 Resume Charging: ${v-2}°C" } }
            override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) { haptic(this@apply) }
        })}
        c3.addView(cutS)

        resumeLabel = txt("🔄 Resume Charging: ${settings.resumeTemp}°C", 14f, tPri).apply { setPadding(0,10,0,0) }; c3.addView(resumeLabel)
        resS = SeekBar(this).apply { max = 13; progress = settings.resumeTemp - 32; setPadding(0,10,0,10); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { var v=32+p; if(v>settings.cutoffTemp-2){v=settings.cutoffTemp-2; progress=v-32}; settings.resumeTemp=v; resumeLabel.text="🔄 Resume Charging: $v°C" }
            override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) { haptic(this@apply) }
        })}
        c3.addView(resS); lay.addView(c3)

        val c4 = card(cBg)
        c4.addView(txt("⚙️ NOTIFICATION PREFERENCES", 12f, Color.GRAY).apply { setPadding(0,0,0,20) })
        c4.addView(Switch(this).apply { text = "📱 Show Status Bar Notification"; setTextColor(tPri); setPadding(0,0,0,15); isChecked = settings.showNotification; setOnCheckedChangeListener { _, c -> haptic(this); settings.showNotification = c; startMonitorService() } })
        c4.addView(Switch(this).apply { text = "⚡ Show Real-Time Wattage View"; setTextColor(tPri); isChecked = settings.showPowerMetrics; setOnCheckedChangeListener { _, c -> haptic(this); settings.showPowerMetrics = c; startMonitorService() } })
        lay.addView(c4)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun buildTab2(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
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

        val c3 = card(cBg) {
            try {
                val h = BatteryHealthHelper.getHealthData(this@MainActivity)
                showModal("Battery Diagnostic", "Design Capacity: ${h.designCapacityMah} mAh\nActual Capacity: ${h.actualCapacityMah} mAh\nTotal Charge Cycles: ${h.cycleCount}\n\nWear Level: ${100 - h.healthPercent}%\nStatus: ${h.statusText}")
            } catch (e: Exception) { showModal("Error", "Could not parse health data.") }
        }
        c3.addView(txt("BATTERY DEGRADATION HEALTH (TAP FOR DETAILS)", 12f, Color.GRAY))
        healthPercentText = txt("--%", 32f, tPri, true); c3.addView(healthPercentText)
        actualCapacityText = txt("Loading metrics...", 15f, tPri).apply { setPadding(0,10,0,0) }; c3.addView(actualCapacityText)
        lay.addView(c3)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun buildTab3(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        val rCard = card(cBg) { showRamDetailsModal() }
        val hdRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hdRow.addView(txt("SoC: ${HardwareThermalControl.getHardwareInfo()}", 14f, tPri, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        gpuText = txt("GPU: --%", 14f, Color.parseColor("#00E5FF"), true); hdRow.addView(gpuText)
        rCard.addView(hdRow)
        rCard.addView(txt("LIVE MEMORY (RAM) - TAP FOR APP DETAILS", 12f, Color.GRAY).apply{setPadding(0,25,0,15)})
        
        val ramRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ramUsageText = txt("Loading...", 15f, tPri, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        ramRow.addView(ramUsageText)
        ramRow.addView(Button(this).apply {
            text = "CLEAN RAM"; textSize = 11f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2196F3")); layoutParams = LinearLayout.LayoutParams(-2, -2); setPadding(20, 10, 20, 10)
            setOnClickListener { haptic(this); HardwareThermalControl.clearRamCaches(); Toast.makeText(this@MainActivity, "Kernel RAM caches dropped.", Toast.LENGTH_SHORT).show() }
        })
        rCard.addView(ramRow); lay.addView(rCard)

        val sgCard = card(cBg) { showModal("Smart Thermal Governor", "When your phone hits the Warning Temperature limit (set in Tab 1), this Engine will automatically:\n1. Throttle the Prime Core to minimum frequency.\n2. Force heavy background processes to the efficiency cores to save heat.\nIt releases the locks once the phone cools down.") }
        sgCard.addView(Switch(this).apply { 
            text = "Auto-Thermal Smart Governor"; setTextColor(tPri)
            isChecked = isSmartGovernorEnabled
            setOnCheckedChangeListener { _, c -> 
                haptic(this); isSmartGovernorEnabled = c
                sendBroadcast(Intent("ACTION_TOGGLE_SMART_GOVERNOR").apply { putExtra("state", c); setPackage(packageName) })
                Toast.makeText(context, if(c) "Smart Governor Engine Armed" else "Smart Governor Disabled", Toast.LENGTH_SHORT).show()
            }
        })
        lay.addView(sgCard)

        val cCard = card(cBg) { showModal("Android Core Hotplugging", "Long-Press any Silver or Gold core to completely force it Offline/Online.\n\nBecause the Snapdragon 870 kernel crashes if the Prime Core (C7) is offlined, Long-Pressing C7 will Throttle/Restore its frequency instead.") }
        cCard.addView(txt("CPU ARCHITECTURE MAP (LONG-PRESS TO TOGGLE)", 11f, Color.GRAY).apply{setPadding(0,0,0,20)})
        
        cpuArchitectureGrid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; layoutParams = LinearLayout.LayoutParams(-1, 380) }
        coreBlocks = Array(8) { TextView(this) }
        
        fun coreBox(idx: Int): TextView = txt("C$idx", 12f, Color.WHITE, true).apply { 
            gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(6,6,6,6) }
            background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.DKGRAY) }
            setOnLongClickListener { 
                haptic(this)
                if (idx == 7) {
                    val throttled = (this.text.toString().contains("THROTTLED"))
                    HardwareThermalControl.throttlePrimeCore(!throttled)
                    Toast.makeText(context, if(!throttled) "Core 7 Throttled" else "Core 7 Restored", Toast.LENGTH_SHORT).show()
                } else {
                    val isOff = (this.text.toString().contains("OFF"))
                    HardwareThermalControl.setCoreOnline(idx, isOff)
                    if (isOff) {
                        (this.background as GradientDrawable).setColor(Color.parseColor("#4CAF50"))
                        this.text = "C$idx\nONLINE"
                        Toast.makeText(context, "Core $idx Online", Toast.LENGTH_SHORT).show()
                    } else {
                        (this.background as GradientDrawable).setColor(Color.parseColor("#333333"))
                        this.text = "C$idx\nOFF"
                        Toast.makeText(context, "Core $idx Offline", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
        }

        val silCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-1,1f) }
        for(i in 0..3) { val b = coreBox(i); coreBlocks[i] = b; silCol.addView(b) }
        val gldCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-1,1f) }
        for(i in 4..6) { val b = coreBox(i); coreBlocks[i] = b; gldCol.addView(b) }
        val prmCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0,-1,1f) }
        val c7 = coreBox(7); coreBlocks[7] = c7; prmCol.addView(c7)

        cpuArchitectureGrid.addView(silCol); cpuArchitectureGrid.addView(gldCol); cpuArchitectureGrid.addView(prmCol)
        cCard.addView(cpuArchitectureGrid)
        lay.addView(cCard)

        val pCard = card(cBg)
        pCard.addView(txt("LIVE CPU STRESS & TERMINATION", 12f, Color.GRAY).apply{setPadding(0,0,0,20)})
        processListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutTransition = LayoutTransition() }
        pCard.addView(processListContainer); lay.addView(pCard)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun buildTab4(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        val c1 = card(cBg)
        c1.addView(txt("DISPLAY REFRESH RATE", 12f, Color.GRAY).apply { setPadding(0, 0, 0, 20) })
        val rrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        listOf(60, 90, 120).forEach { r ->
            rrRow.addView(Button(this).apply {
                text = "${r}Hz"
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(10, 0, 10, 0) }
                setBackgroundColor(Color.parseColor("#333333"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    haptic(this)
                    thread { 
                        val cmd = "settings put system peak_refresh_rate $r; settings put system min_refresh_rate $r; settings put system user_refresh_rate $r; settings put secure miui_refresh_rate $r; settings put system miui_refresh_rate $r"
                        Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)) 
                    }
                    Toast.makeText(this@MainActivity, "Forced ${r}Hz (Lock & Unlock screen to apply)", Toast.LENGTH_LONG).show()
                }
            })
        }
        c1.addView(rrRow); lay.addView(c1)

        val c2 = card(cBg)
        c2.addView(txt("LIVE FPS METRE OVERLAY", 12f, Color.GRAY).apply { setPadding(0, 0, 0, 10) })
        
        c2.addView(Switch(this).apply { 
            text = "Show Temperature on Pill"; setTextColor(tPri); setPadding(0,0,0,20)
            isChecked = sharedPrefs.getBoolean("showTempInFpsOverlay", false)
            setOnCheckedChangeListener { _, c -> haptic(this); sharedPrefs.edit().putBoolean("showTempInFpsOverlay", c).apply() }
        })

        c2.addView(txt("Tap the pill to start/pause counting. Long-press to save session & close.", 13f, tPri).apply { setPadding(0, 0, 0, 20) })
        c2.addView(Button(this).apply {
            text = "LAUNCH FPS OVERLAY"
            setBackgroundColor(Color.parseColor("#2196F3"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                haptic(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                    Toast.makeText(this@MainActivity, "Please grant Draw Overlays permission first", Toast.LENGTH_LONG).show()
                } else {
                    startService(Intent(this@MainActivity, FpsOverlayService::class.java))
                }
            }
        })
        lay.addView(c2)

        val c3 = card(cBg)
        c3.addView(txt("GAMING FPS HISTORY", 12f, Color.GRAY).apply { setPadding(0, 0, 0, 20) })
        fpsDetailText = txt("Tap a bar to view session details", 13f, tPri).apply { setPadding(0, 20, 0, 0) }
        fpsChartView = FpsChartView(this).apply {
            isDarkMode = isDark
            layoutParams = LinearLayout.LayoutParams(-1, 400)
            onSessionSelected = { s ->
                val date = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(s.timestamp))
                fpsDetailText.text = "Session: $date\nApp: ${s.appName}\nDuration: ${s.durationSec}s\nAverage: ${s.avgFps} FPS  |  Min: ${s.minFps}  |  Max: ${s.maxFps}\nAvg Temp: ${s.avgTemp}°C"
            }
        }
        c3.addView(fpsChartView); c3.addView(fpsDetailText); lay.addView(c3)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
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
        } catch (e: Exception) {
            try { livePowerText.text = "Hardware parsing error" } catch (ex: Exception) {}
        }
    }

    private fun refreshDashboardData() {
        refreshLiveHardware()
        try {
            val h = BatteryHealthHelper.getHealthData(this)
            healthPercentText.text = "${h.healthPercent}%"
            actualCapacityText.text = "Actual: ${h.actualCapacityMah} mAh\nDesign: ${h.designCapacityMah} mAh\nCycles: ${h.cycleCount}"
        } catch(e: Exception) { healthPercentText.text = "--%"; actualCapacityText.text = "Error reading health" }
        
        try { chartView.setData(dbHelper.getAllRecords()) } catch(e: Exception) {}
        try { fpsChartView.setData(dbHelper.getFpsSessions().reversed()) } catch (e: Exception) {}
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
                    ramUsageText.text = ram
                    gpuText.text = "GPU: $gpu"
                    
                    for (i in 0..7) {
                        val blk = coreBlocks[i]
                        if (i < freqs.size) {
                            val fStr = freqs[i]
                            val mhz = fStr.replace(" MHz", "").toIntOrNull() ?: 0
                            
                            if (fStr == "Offline" || (i == 7 && mhz <= 844 && mhz > 0)) {
                                (blk.background as GradientDrawable).setColor(Color.parseColor("#333333"))
                                blk.text = "C$i\n${if(fStr == "Offline") "OFF" else "THROTTLED"}"
                            } else {
                                val col = when {
                                    mhz < 1000 -> Color.parseColor("#4CAF50")
                                    mhz < 2000 -> Color.parseColor("#FF9800")
                                    else -> Color.parseColor("#F44336")
                                }
                                (blk.background as GradientDrawable).setColor(col)
                                blk.text = "C$i\n$fStr"
                            }
                        }
                    }
                    processListContainer.removeAllViews()
                    if (procs.isEmpty()) { processListContainer.addView(txt("Waiting for kernel data...", 13f, Color.GRAY)); return@post }
                    procs.forEach { p ->
                        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 15, 0, 15); gravity = Gravity.CENTER_VERTICAL }
                        val procTxt = txt("${p.name} — ${p.cpu}%", 12f, if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) Color.WHITE else Color.BLACK).apply { 
                            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = 15 }; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        }
                        row.addView(procTxt)
                        row.addView(Button(this@MainActivity).apply { text="RESTRICT"; textSize=10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#FF9800")); setPadding(10,0,10,0); layoutParams=LinearLayout.LayoutParams(-2, 80).apply{rightMargin=15}; setOnClickListener { haptic(this); HardwareThermalControl.pinProcessToEfficiencyCores(p.pid); Toast.makeText(this@MainActivity, "Pinned to Cores 0-3", Toast.LENGTH_SHORT).show() } })
                        row.addView(Button(this@MainActivity).apply { text="KILL"; textSize=10f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#D32F2F")); setPadding(10,0,10,0); layoutParams=LinearLayout.LayoutParams(-2, 80); setOnClickListener { haptic(this); showKillConfirmDialog(p.pid, p.name) } })
                        processListContainer.addView(row)
                    }
                }
                Thread.sleep(2000)
            }
        }
    }

    private fun showKillConfirmDialog(pid: Int, name: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Terminate Process?").setMessage("Kill $name?").setPositiveButton("KILL") { _, _ -> HardwareThermalControl.killProcess(pid); Toast.makeText(this, "Signal sent to $name", Toast.LENGTH_SHORT).show() }.setNegativeButton("Cancel", null).show()
    }
}
