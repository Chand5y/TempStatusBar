package com.personal.tempstatusbar

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var settings: SettingsManager
    private lateinit var contentFrame: FrameLayout
    private lateinit var tab1Thermal: View
    private lateinit var tab2Battery: View
    private lateinit var tab3CPU: View
    private lateinit var tabButtons: List<TextView>
    private lateinit var chartView: TemperatureChartView
    private lateinit var detailTimeText: TextView
    private lateinit var detailTempText: TextView
    private lateinit var detailAppContent: TextView
    private lateinit var warnLabel: TextView
    private lateinit var cutoffLabel: TextView
    private lateinit var resumeLabel: TextView
    private lateinit var pulseView: SubtlePulseView
    private lateinit var livePowerText: TextView
    private lateinit var currentLevelText: TextView
    private lateinit var healthPercentText: TextView
    private lateinit var actualCapacityText: TextView
    private lateinit var batteryDrainList: TextView
    private lateinit var ramUsageText: TextView
    private lateinit var coreGrid: LinearLayout
    private lateinit var coreTexts: Array<TextView>
    private lateinit var processListContainer: LinearLayout
    private var currentTabIndex = 0
    private var isCpuTabActive = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var isPaused = false

    private val livePowerUpdater = object : Runnable {
        override fun run() {
            if (!isPaused) {
                refreshDashboardData(null)
                uiHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = DatabaseHelper(this)
        settings = SettingsManager(this)
        buildBaseLayout()
        
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
        startMonitorService()
    }

    override fun onResume() {
        super.onResume()
        isPaused = false
        pulseView.startAnimation()
        uiHandler.post(livePowerUpdater)
        if (isCpuTabActive) startLiveCpuUpdates()
    }

    override fun onPause() {
        super.onPause()
        isPaused = true
        pulseView.stopAnimation()
        isCpuTabActive = false
        uiHandler.removeCallbacks(livePowerUpdater)
    }

    private fun startMonitorService() {
        val i = Intent(this, TempMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun haptic(v: View) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    private fun showModal(title: String, content: String) {
        val sv = ScrollView(this).apply { setPadding(40, 20, 40, 20) }
        val tv = txt(content, 12f, Color.GRAY).apply { typeface = Typeface.MONOSPACE }
        sv.addView(tv)
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
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,10,0,10); gravity=Gravity.CENTER_VERTICAL }
                    row.addView(txt("${p.name}\n${p.sizeMb} MB (PID ${p.pid})", 11f, Color.WHITE).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                    row.addView(Button(this@MainActivity).apply { text="KILL"; textSize=9f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#D32F2F")); setPadding(5,0,5,0); layoutParams=LinearLayout.LayoutParams(-2, -2)
                        setOnClickListener { haptic(this); HardwareThermalControl.killProcess(p.pid); list.removeView(row); Toast.makeText(this@MainActivity, "Killed ${p.name}", Toast.LENGTH_SHORT).show() }
                    })
                    list.addView(row)
                }
            }
        }
    }

    private fun txt(t: String, sz: Float, col: Int, bold: Boolean = false): TextView = TextView(this).apply { text = t; textSize = sz; setTextColor(col); if (bold) typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
    private fun card(bg: Int, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(35, 30, 35, 30); background = GradientDrawable().apply { cornerRadius = 40f; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 25 }; layoutTransition = LayoutTransition()
        if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { haptic(this); onClick() } }
    }

    private fun buildBaseLayout() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (isDark) Color.BLACK else Color.parseColor("#F4F4F6")
        
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); layoutTransition = LayoutTransition() }
        root.addView(txt("Hardware Shield", 22f, if (isDark) Color.WHITE else Color.BLACK, true).apply { setPadding(40, 50, 40, 20) })
        
        contentFrame = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); layoutTransition = LayoutTransition() }
        root.addView(contentFrame)

        tab1Thermal = buildTab1(isDark)
        tab2Battery = buildTab2(isDark)
        tab3CPU = buildTab3(isDark)
        contentFrame.addView(tab1Thermal); contentFrame.addView(tab2Battery); contentFrame.addView(tab3CPU)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, 160); setPadding(20, 10, 20, 10)
            
            // Continuous sliding touch support for nav bar
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                    val idx = (event.x / (v.width / 3)).toInt().coerceIn(0, 2)
                    if (currentTabIndex != idx) { haptic(v); switchTab(idx) }
                }
                true
            }
        }
        tabButtons = listOf("Thermal", "Battery", "CPU Core").mapIndexed { i, t ->
            txt(t, 14f, Color.GRAY, true).apply { gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, -1, 1f) }
        }
        tabButtons.forEach { nav.addView(it) }
        root.addView(nav)
        
        setContentView(root)
        switchTab(0)
    }

    // Global Swipe support
    private var downX = 0f; private var downY = 0f
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                // Require clear horizontal swipe, but ignore if swiping high up on the thermal chart
                if (abs(dx) > 150 && abs(dx) > abs(dy) * 2 && (currentTabIndex != 0 || ev.y > 800)) {
                    if (dx > 0 && currentTabIndex > 0) { haptic(contentFrame); switchTab(currentTabIndex - 1) }
                    else if (dx < 0 && currentTabIndex < 2) { haptic(contentFrame); switchTab(currentTabIndex + 1) }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun switchTab(idx: Int) {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        currentTabIndex = idx
        tab1Thermal.visibility = if (idx == 0) View.VISIBLE else View.GONE
        tab2Battery.visibility = if (idx == 1) View.VISIBLE else View.GONE
        tab3CPU.visibility = if (idx == 2) View.VISIBLE else View.GONE
        tabButtons.forEachIndexed { i, b -> b.setTextColor(if (i == idx) Color.parseColor("#00E5FF") else (if (isDark) Color.GRAY else Color.DKGRAY)) }
        isCpuTabActive = (idx == 2)
        if (isCpuTabActive) startLiveCpuUpdates()
    }

    private fun buildTab1(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        val c1 = card(cBg) {
            val records = dbHelper.getAllRecords().takeLast(50).joinToString("\n\n") { "[$it.chargeType] ${it.temp}°C\n${it.appDetails}" }
            showModal("Raw Thermal Database", if (records.isEmpty()) "No logs yet." else records)
        }
        c1.addView(txt("TAP CHART TO VIEW RAW LOGS", 10f, Color.GRAY).apply{ gravity=Gravity.CENTER; setPadding(0,0,0,10) })
        chartView = TemperatureChartView(this).apply { isDarkMode = isDark; layoutParams = LinearLayout.LayoutParams(-1, 450) }
        c1.addView(chartView); lay.addView(c1)

        val c2 = card(cBg)
        detailTimeText = txt("Select a point on the chart", 12f, Color.GRAY); c2.addView(detailTimeText)
        detailTempText = txt("--°C", 28f, Color.parseColor("#FF5722"), true); c2.addView(detailTempText)
        detailAppContent = txt("", 12f, if(isDark) Color.LTGRAY else Color.DKGRAY).apply { typeface = Typeface.MONOSPACE; setPadding(0,10,0,0) }
        c2.addView(detailAppContent); lay.addView(c2)

        chartView.onRecordSelected = { r ->
            val s = if (r.screenOn) "📱 Screen ON" else "💤 Screen OFF"
            val b = if (r.isRoot) "🛡️ Root Trace" else "📱 Non-Root Trace"
            detailTimeText.text = "${SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()).format(Date(r.timestamp))} • $s • $b"
            detailTempText.text = "${r.temp}°C"
            detailAppContent.text = r.appDetails
        }

        val c3 = card(cBg) { showModal("PMIC Safety Engine", "These hardware-level sliders control the Qualcomm charging IC directly.\n\nCut Off (🛑): Instantly breaks the circuit from the charger to the battery when this temp is hit.\nResume (🔄): Restores the circuit once the battery has cooled down.") }
        c3.addView(txt("HARDWARE THERMAL PROTECTION (TAP FOR INFO)", 11f, Color.GRAY).apply { setPadding(0,0,0,20) })
        warnLabel = txt("⚠️ Warning Sound Alert: ${settings.warningTemp}°C", 13f, tPri); c3.addView(warnLabel)
        c3.addView(SeekBar(this).apply { max = 13; progress = settings.warningTemp - 35; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { val v=35+p; settings.warningTemp=v; warnLabel.text="⚠️ Warning Sound Alert: $v°C" }
            override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) { haptic(this@apply) }
        })})

        cutoffLabel = txt("🛑 Cut Off Charging (PMIC): ${settings.cutoffTemp}°C", 13f, tPri).apply { setPadding(0,16,0,0) }; c3.addView(cutoffLabel)
        val cutS = SeekBar(this); val resS = SeekBar(this)
        resumeLabel = txt("🔄 Resume Charging: ${settings.resumeTemp}°C", 13f, tPri).apply { setPadding(0,16,0,0) }
        
        cutS.apply { max = 12; progress = settings.cutoffTemp - 38; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { val v=38+p; settings.cutoffTemp=v; cutoffLabel.text="🛑 Cut Off Charging (PMIC): $v°C"
                if (settings.resumeTemp >= v-1) { settings.resumeTemp = v-2; resS.progress = (v-2)-32; resumeLabel.text="🔄 Resume Charging: ${v-2}°C" } }
            override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) { haptic(this@apply) }
        })}; c3.addView(cutS); c3.addView(resumeLabel)

        resS.apply { max = 13; progress = settings.resumeTemp - 32; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) { var v=32+p; if(v>settings.cutoffTemp-2){v=settings.cutoffTemp-2; progress=v-32}; settings.resumeTemp=v; resumeLabel.text="🔄 Resume Charging: $v°C" }
            override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) { haptic(this@apply) }
        })}; c3.addView(resS); lay.addView(c3)

        val c4 = card(cBg)
        c4.addView(txt("NOTIFICATION PREFERENCES", 11f, Color.GRAY).apply { setPadding(0,0,0,15) })
        c4.addView(Switch(this).apply { text = "Show Status Bar Notification"; setTextColor(tPri); isChecked = settings.showNotification; setOnCheckedChangeListener { _, c -> haptic(this); settings.showNotification = c; startMonitorService() } })
        c4.addView(Switch(this).apply { text = "Show Real-Time Wattage / Drain"; setTextColor(tPri); isChecked = settings.showPowerMetrics; setOnCheckedChangeListener { _, c -> haptic(this); settings.showPowerMetrics = c; startMonitorService() } })
        lay.addView(c4)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun buildTab2(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        val c1 = card(cBg)
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pulseView = SubtlePulseView(this).apply { layoutParams = LinearLayout.LayoutParams(50, 50) }; r1.addView(pulseView)
        livePowerText = txt("", 15f, tPri, true).apply { setPadding(25,0,0,0) }; r1.addView(livePowerText)
        c1.addView(r1); lay.addView(c1)

        val c2 = card(cBg)
        c2.addView(txt("CURRENT BATTERY LEVEL", 11f, Color.GRAY))
        currentLevelText = txt("--%", 48f, tPri, true); c2.addView(currentLevelText)
        lay.addView(c2)

        val c3 = card(cBg) {
            val h = BatteryHealthHelper.getHealthData(this@MainActivity)
            showModal("Battery Diagnostic", "Design Capacity: ${h.designCapacityMah} mAh\nActual Capacity: ${h.actualCapacityMah} mAh\nTotal Charge Cycles: ${h.cycleCount}\n\nWear Level: ${100 - h.healthPercent}%\nStatus: ${h.statusText}")
        }
        c3.addView(txt("BATTERY DEGRADATION HEALTH (TAP FOR DETAILS)", 11f, Color.GRAY))
        healthPercentText = txt("", 28f, tPri, true); c3.addView(healthPercentText)
        actualCapacityText = txt("", 14f, tPri).apply { setPadding(0,10,0,0) }; c3.addView(actualCapacityText)
        lay.addView(c3)

        val c4 = card(cBg)
        c4.addView(txt("PER-APP DRAIN (SINCE UNPLUGGED)", 11f, Color.GRAY).apply{setPadding(0,0,0,15)})
        batteryDrainList = txt("", 12f, if(isDark) Color.LTGRAY else Color.DKGRAY).apply { typeface = Typeface.MONOSPACE }; c4.addView(batteryDrainList)
        lay.addView(c4)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun buildTab3(isDark: Boolean): View {
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 20) }
        val cBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val tPri = if (isDark) Color.WHITE else Color.BLACK

        val rCard = card(cBg) { showRamDetailsModal() }
        rCard.addView(txt("LIVE MEMORY (RAM) - TAP FOR APP DETAILS", 11f, Color.GRAY).apply{setPadding(0,0,0,10)})
        
        val ramRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ramUsageText = txt("Loading...", 14f, tPri, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        ramRow.addView(ramUsageText)
        ramRow.addView(Button(this).apply {
            text = "CLEAN RAM"; textSize = 9f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2196F3"))
            layoutParams = LinearLayout.LayoutParams(-2, -2) // Changed to WRAP_CONTENT to fix squishing
            setOnClickListener { haptic(this); HardwareThermalControl.clearRamCaches(); Toast.makeText(this@MainActivity, "Kernel RAM caches dropped.", Toast.LENGTH_SHORT).show() }
        })
        rCard.addView(ramRow); lay.addView(rCard)

        val cCard = card(cBg)
        cCard.addView(txt("8-CORE PROCESSOR FREQUENCIES", 11f, Color.GRAY).apply{setPadding(0,0,0,15)})
        coreGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        coreTexts = Array(8) { TextView(this) }
        val clusterNames = arrayOf("Silver (Efficiency)", "Silver (Efficiency)", "Silver (Efficiency)", "Silver (Efficiency)", "Gold (Performance)", "Gold (Performance)", "Gold (Performance)", "Prime (Extreme)")
        for (i in 0..7) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,8,0,8) }
            r.addView(txt("Core $i [${clusterNames[i]}]: ", 12f, Color.GRAY).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            coreTexts[i] = txt("--- MHz", 12f, tPri, true)
            r.addView(coreTexts[i])
            coreGrid.addView(r)
        }
        cCard.addView(coreGrid)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,15,0,0) }
        btnRow.addView(Button(this).apply { text="Disable Prime (C7)"; textSize=10f; layoutParams=LinearLayout.LayoutParams(0,-2,1f); setOnClickListener { haptic(this); HardwareThermalControl.setCoreOnline(7, false); Toast.makeText(context, "Core 7 Disabled", Toast.LENGTH_SHORT).show() } })
        btnRow.addView(Button(this).apply { text="Enable Prime (C7)"; textSize=10f; layoutParams=LinearLayout.LayoutParams(0,-2,1f); setOnClickListener { haptic(this); HardwareThermalControl.setCoreOnline(7, true); Toast.makeText(context, "Core 7 Enabled", Toast.LENGTH_SHORT).show() } })
        cCard.addView(btnRow); lay.addView(cCard)

        val pCard = card(cBg)
        pCard.addView(txt("LIVE CPU STRESS & TERMINATION", 11f, Color.GRAY).apply{setPadding(0,0,0,15)})
        processListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutTransition = LayoutTransition() }
        pCard.addView(processListContainer); lay.addView(pCard)

        return ScrollView(this).apply { addView(lay); isFillViewport = true }
    }

    private fun refreshDashboardData(intent: Intent?) {
        val curIntent = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (curIntent != null) {
            val level = curIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = curIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) currentLevelText.text = "${(level * 100 / scale.toFloat()).toInt()}%"
        }

        val h = BatteryHealthHelper.getHealthData(this)
        healthPercentText.text = "${h.healthPercent}%"
        actualCapacityText.text = "Actual: ${h.actualCapacityMah} mAh\nDesign: ${h.designCapacityMah} mAh\nCycles: ${h.cycleCount}"

        val plugged = curIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isPlugged = plugged != 0
        val st = PowerHardwareHelper.readPowerStats(this, isPlugged)
        pulseView.setMode(st.isCharging)
        
        val statusText = if (st.isCharging) "Status: Charging AC" else "Status: Discharging (Battery)"
        livePowerText.text = if (st.isCharging) "$statusText\n⚡ ${st.wattage}W (+${st.currentMa} mA)" else "$statusText\n🔋 -${st.wattage}W (${st.currentMa} mA)"

        chartView.setData(dbHelper.getAllRecords())
        thread { val s = HardwareThermalControl.getAppBatteryDrain(); uiHandler.post { batteryDrainList.text = s } }
    }

    private fun startLiveCpuUpdates() {
        thread {
            while (isCpuTabActive) {
                val ram = HardwareThermalControl.getRamUsage(this@MainActivity)
                val freqs = HardwareThermalControl.getCoreFrequencies()
                var snap = ""
                if (HardwareThermalControl.isRootAvailable()) snap = HardwareThermalControl.getKernelProcessSnapshot().first
                
                uiHandler.post {
                    if (!isCpuTabActive) return@post
                    ramUsageText.text = ram
                    for (i in 0..7) { if (i < freqs.size) coreTexts[i].text = freqs[i] }
                    updateProcessListUI(snap)
                }
                Thread.sleep(2000)
            }
        }
    }

    private fun updateProcessListUI(snap: String) {
        processListContainer.removeAllViews()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isDark) Color.WHITE else Color.BLACK

        if (snap.isEmpty()) { 
            processListContainer.addView(txt("Waiting for kernel data... Check root access.", 12f, Color.GRAY))
            return 
        }

        snap.split("\n").forEach { line ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 15, 0, 15); gravity = Gravity.CENTER_VERTICAL }
            row.addView(txt(line, 11f, textColor).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })

            Regex("\\(PID (\\d+)\\)").find(line)?.let { match ->
                val pid = match.groupValues[1].toInt()
                if (pid > 0 && !line.contains("Android Core Engine") && !line.contains("Display Compositor")) {
                    row.addView(Button(this).apply { text="RESTRICT"; textSize=9f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#FF9800")); setPadding(5,0,5,0); layoutParams=LinearLayout.LayoutParams(-2, 70).apply{rightMargin=10}; setOnClickListener { haptic(this); HardwareThermalControl.pinProcessToEfficiencyCores(pid); Toast.makeText(this@MainActivity, "Pinned to Cores 0-3", Toast.LENGTH_SHORT).show() } })
                    row.addView(Button(this).apply { text="KILL"; textSize=9f; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#D32F2F")); setPadding(5,0,5,0); layoutParams=LinearLayout.LayoutParams(-2, 70); setOnClickListener { haptic(this); showKillConfirmDialog(pid, line) } })
                }
            }
            processListContainer.addView(row)
        }
    }

    private fun showKillConfirmDialog(pid: Int, desc: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Terminate Process?")
            .setMessage("Kill PID $pid?\n\n$desc")
            .setPositiveButton("KILL") { _, _ -> HardwareThermalControl.killProcess(pid); Toast.makeText(this, "Signal sent to PID $pid", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Cancel", null).show()
    }
}
