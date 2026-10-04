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

class MainActivity : Activity() {
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var settings: SettingsManager
    private lateinit var contentFrame: FrameLayout
    private lateinit var tab1Thermal: View
    private lateinit var tab2Battery: View
    private lateinit var tab3CPU: View
    private lateinit var tabButtons: List<TextView>
    
    private lateinit var chartView: TemperatureChartView
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
        HardwareThermalControl.init(this)
        dbHelper = DatabaseHelper(this)
        settings = SettingsManager(this)
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
        
        contentFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(contentFrame)

        tab1Thermal = buildTab1(isDark); tab2Battery = buildTab2(isDark); tab3CPU = buildTab3(isDark)
        contentFrame.addView(tab1Thermal); contentFrame.addView(tab2Battery); contentFrame.addView(tab3CPU)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, 180); setPadding(20, 10, 20, 10)
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                    val idx = (event.x / (v.width / 3)).toInt().coerceIn(0, 2)
                    if (currentTabIndex != idx) { haptic(v); switchTab(idx) }
                }
                true
            }
        }
        tabButtons = listOf("Thermal", "Battery", "CPU Core").mapIndexed { i, t ->
            txt(t, 14f, Color.GRAY, true).apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setOnClickListener { haptic(this); switchTab(i) } 
            }
        }
        tabButtons.forEach { nav.addView(it) }
        root.addView(nav)
        
        setContentView(root)
        tab1Thermal.visibility = View.VISIBLE; tab2Battery.visibility = View.GONE; tab3CPU.visibility = View.GONE
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
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                if (abs(dx) > 150 && abs(dx) > abs(dy) * 2) {
                    if (dx > 0 && currentTabIndex > 0) { haptic(contentFrame); switchTab(currentTabIndex - 1) }
                    else if (dx < 0 && currentTabIndex < 2) { haptic(contentFrame); switchTab(currentTabIndex + 1) }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun switchTab(newIdx: Int) {
        if (newIdx == currentTabIndex) return
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val movingLeft = newIdx > currentTabIndex
        val oldTab = if (currentTabIndex == 0) tab1Thermal else if (currentTabIndex == 1) tab2Battery else tab3CPU
        val newTab = if (newIdx == 0) tab1Thermal else if (newIdx == 1) tab2Battery else tab3CPU
        
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
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        val c1 = card(cBg) {
            try {
                val validRecords = dbHelper.getAllRecords().filter { !it.appDetails.contains("TempRecord(") }
                val records = validRecords.takeLast(50).joinToString("\n\n") { "[$it.chargeType] ${it.temp}°C\n${it.appDetails}" }
                showModal("Raw Thermal Database", if (records.isEmpty()) "No logs yet." else records)
            } catch (e: Exception) { showModal("Error", "Could not read database") }
        }
        c1.addView(txt("TAP CHART TO VIEW RAW LOGS", 11f, Color.GRAY).apply{ gravity=Gravity.CENTER; setPadding(0,0,0,15) })
        chartView = TemperatureChartView(this).apply { isDarkMode = isDark; layoutParams = LinearLayout.LayoutParams(-1, 500) }
        c1.addView(chartView); lay.addView(c1)

        val c2 = card(cBg)
        detailTimeText = txt("Select a point on the chart", 13f, Color.GRAY); c2.addView(detailTimeText)
        detailTempText = txt("--°C", 32f, Color.parseColor("#FF5722"), true); c2.addView(detailTempText)
        detailAppContent = txt("", 13f, if(isDark) Color.LTGRAY else Color.DKGRAY).apply { typeface = Typeface.MONOSPACE; setPadding(0,15,0,0) }
        c2.addView(detailAppContent); lay.addView(c2)

        chartView.onRecordSelected = { r ->
            val rootStr = if (r.isRoot) "🛡️ Root Trace" else "📱 Non-Root Trace"
            val screenStr = if (r.screenOn) "Screen ON" else "Screen OFF"
            detailTimeText.text = "${SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(r.timestamp))} • $screenStr\n[$rootStr | ${r.chargeType}]"
            detailTempText.text = "${r.temp}°C"
            detailAppContent.text = r.appDetails
        }

        val c3 = card(cBg) { showModal("PMIC Safety Engine", "Cut Off (🛑): Instantly breaks the circuit from the charger to the battery when this temp is hit.\nResume (🔄): Restores the circuit once the battery has cooled down.") }
        c3.addView(txt("HARDWARE THERMAL PROTECTION (TAP FOR INFO)", 12f, Color.GRAY).apply { setPadding(0,0,0,25) })
        
        warnLabel = txt("⚠️️ Warning Sound Alert: ${settings.warningTemp}°C", 14f, tPri); c3.addView(warnLabel)
        warnS = SeekBar(this).apply { max = 13; progress = settings.warningTemp - 35; setPadding(0,10,0,20); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { val v=35+p; settings.warningTemp=v; warnLabel.text="⚠️ Warning Sound Alert: $v°C" }
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
        c4.addView(txt("NOTIFICATION PREFERENCES", 12f, Color.GRAY).apply { setPadding(0,0,0,20) })
        c4.addView(Switch(this).apply { text = "Show Status Bar Notification"; setTextColor(tPri); setPadding(0,0,0,15); isChecked = settings.showNotification; setOnCheckedChangeListener { _, c -> haptic(this); settings.showNotification = c; startMonitorService() } })
        c4.addView(Switch(this).apply { text = "Show Real-Time Wattage / Drain"; setTextColor(tPri); isChecked = settings.showPowerMetrics; setOnCheckedChangeListener { _, c -> haptic(this); settings.showPowerMetrics = c; startMonitorService() } })
        lay.addView(c4)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun buildTab2(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        // The properly restored Battery and Bypass card
        val c1 = card(cBg)
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        batteryGraphic = BatteryGraphicView(this).apply { layoutParams = LinearLayout.LayoutParams(220, 120).apply{ rightMargin = 40 } }
        r1.addView(batteryGraphic)
        
        val pInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        currentLevelText = txt("--%", 28f, tPri, true); pInfo.addView(currentLevelText)
        livePowerText = txt("Calculating...", 13f, Color.GRAY); pInfo.addView(livePowerText)
        r1.addView(pInfo)
        c1.addView(r1)
        
        c1.addView(Switch(this).apply { 
            text = "Hardware Bypass Charging"; setTextColor(tPri); setPadding(0,30,0,0)
            isChecked = HardwareThermalControl.isManualBypassActive
            setOnCheckedChangeListener { _, c -> 
                haptic(this)
                HardwareThermalControl.setChargingEnabled(!c, isManualToggle = true)
                Toast.makeText(context, if(c) "Bypass Enabled: Battery Isolated" else "Bypass Disabled: Charging Restored", Toast.LENGTH_SHORT).show() 
            }
    
