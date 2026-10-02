package com.personal.tempstatusbar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var dbHelper: DatabaseHelper
    private lateinit var settings: SettingsManager
    
    // UI Containers
    private lateinit var contentFrame: FrameLayout
    private lateinit var tab1Thermal: View
    private lateinit var tab2Battery: View
    private lateinit var tab3CPU: View
    private lateinit var tabButtons: List<TextView>

    // Tab 1 Elements
    private lateinit var chartView: TemperatureChartView
    private lateinit var detailTimeText: TextView
    private lateinit var detailTempText: TextView
    private lateinit var detailAppContent: TextView

    // Tab 2 Elements
    private lateinit var pulseView: SubtlePulseView
    private lateinit var livePowerText: TextView
    private lateinit var healthPercentText: TextView
    private lateinit var actualCapacityText: TextView

    // Tab 3 Elements
    private lateinit var processListContainer: LinearLayout
    private lateinit var core7StatusText: TextView
    private var isCpuTabActive = false
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = DatabaseHelper(this)
        settings = SettingsManager(this)

        buildBaseLayout()
        checkPermissionsAndStartService()
    }

    override fun onResume() {
        super.onResume()
        pulseView.startAnimation()
        refreshDashboardData()
        if (isCpuTabActive) startLiveCpuUpdates()
    }

    override fun onPause() {
        super.onPause()
        pulseView.stopAnimation()
        isCpuTabActive = false
    }

    private fun buildBaseLayout() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bgCanvas = if (isDark) Color.BLACK else Color.parseColor("#F4F4F6")

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCanvas)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Title Area
        val header = TextView(this).apply {
            text = "Hardware Shield"
            textSize = 22f
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(40, 50, 40, 20)
        }
        rootLayout.addView(header)

        // Content Area (Weight 1)
        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rootLayout.addView(contentFrame)

        // Build the 3 Tabs
        tab1Thermal = buildTab1Thermal(isDark)
        tab2Battery = buildTab2Battery(isDark)
        tab3CPU = buildTab3CPU(isDark)

        contentFrame.addView(tab1Thermal)
        contentFrame.addView(tab2Battery)
        contentFrame.addView(tab3CPU)

        // Bottom Navigation Bar
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160)
            setPadding(20, 10, 20, 10)
        }

        tabButtons = listOf(
            createNavButton("Thermal", 0),
            createNavButton("Battery", 1),
            createNavButton("CPU Core", 2)
        )
        tabButtons.forEach { navBar.addView(it) }
        rootLayout.addView(navBar)

        setContentView(rootLayout)
        switchTab(0) // Default to Thermal
    }

    private fun createNavButton(title: String, index: Int): TextView {
        return TextView(this).apply {
            text = title
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { switchTab(index) }
        }
    }

    private fun switchTab(index: Int) {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        tab1Thermal.visibility = if (index == 0) View.VISIBLE else View.GONE
        tab2Battery.visibility = if (index == 1) View.VISIBLE else View.GONE
        tab3CPU.visibility = if (index == 2) View.VISIBLE else View.GONE

        tabButtons.forEachIndexed { i, btn ->
            btn.setTextColor(if (i == index) Color.parseColor("#00E5FF") else (if (isDark) Color.GRAY else Color.DKGRAY))
        }

        isCpuTabActive = (index == 2)
        if (isCpuTabActive) startLiveCpuUpdates()
    }

    // ==========================================
    // TAB 1: THERMAL & ACTIVITY
    // ==========================================
    private fun buildTab1Thermal(isDark: Boolean): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 20) }
        val cardBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val textPrimary = if (isDark) Color.WHITE else Color.BLACK

        // Chart Card
        val chartCard = createCard(cardBg)
        chartView = TemperatureChartView(this).apply {
            this.isDarkMode = isDark
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 450)
        }
        chartCard.addView(chartView)
        layout.addView(chartCard)

        // Inspector Card
        val detailCard = createCard(cardBg)
        detailTimeText = TextView(this).apply { textSize = 12f; setTextColor(Color.GRAY) }
        detailCard.addView(detailTimeText)

        detailTempText = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.parseColor("#FF5722"))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        detailCard.addView(detailTempText)

        detailAppContent = TextView(this).apply {
            textSize = 12f
            setPadding(0, 10, 0, 0)
            setTextColor(if (isDark) Color.parseColor("#CFD8DC") else Color.DKGRAY)
            typeface = Typeface.MONOSPACE
        }
        detailCard.addView(detailAppContent)
        layout.addView(detailCard)

        chartView.onRecordSelected = { r ->
            val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
            val screenState = if (r.screenOn) "📱 Screen ON" else "💤 Screen OFF"
            detailTimeText.text = "${sdf.format(Date(r.timestamp))} • $screenState"
            detailTempText.text = "${r.temp}°C"
            detailAppContent.text = r.appDetails
        }
        
        return ScrollView(this).apply { addView(layout); isFillViewport = true }
    }

    // ==========================================
    // TAB 2: BATTERY HEALTH & POWER
    // ==========================================
    private fun buildTab2Battery(isDark: Boolean): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 20) }
        val cardBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val textPrimary = if (isDark) Color.WHITE else Color.BLACK

        val statusCard = createCard(cardBg)
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pulseView = SubtlePulseView(this).apply { layoutParams = LinearLayout.LayoutParams(50, 50) }
        statusRow.addView(pulseView)
        livePowerText = TextView(this).apply {
            textSize = 15f
            setTextColor(textPrimary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(25, 4, 0, 0)
        }
        statusRow.addView(livePowerText)
        statusCard.addView(statusRow)
        layout.addView(statusCard)

        val healthCard = createCard(cardBg)
        healthCard.addView(TextView(this).apply { text = "BATTERY HEALTH"; textSize = 11f; setTextColor(Color.GRAY) })
        healthPercentText = TextView(this).apply { textSize = 40f; setTextColor(textPrimary); typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        healthCard.addView(healthPercentText)
        actualCapacityText = TextView(this).apply { textSize = 14f; setTextColor(textPrimary); setPadding(0, 10, 0, 0) }
        healthCard.addView(actualCapacityText)
        layout.addView(healthCard)

        return ScrollView(this).apply { addView(layout); isFillViewport = true }
    }

    // ==========================================
    // TAB 3: CPU CONTROLS & KILL MANAGER
    // ==========================================
    private fun buildTab3CPU(isDark: Boolean): View {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 20) }
        val cardBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val textPrimary = if (isDark) Color.WHITE else Color.BLACK

        // Core Control Card
        val coreCard = createCard(cardBg)
        coreCard.addView(TextView(this).apply { text = "HARDWARE CORE HOTPLUG"; textSize = 11f; setTextColor(Color.GRAY) })
        
        core7StatusText = TextView(this).apply { textSize = 14f; setTextColor(textPrimary); setPadding(0, 10, 0, 15) }
        coreCard.addView(core7StatusText)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "Disable Prime Core 7 (Cooling)"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { HardwareThermalControl.setCoreOnline(7, false); Toast.makeText(context, "Core 7 Disabled", Toast.LENGTH_SHORT).show() }
        })
        btnRow.addView(Button(this).apply {
            text = "Enable Core 7"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { HardwareThermalControl.setCoreOnline(7, true); Toast.makeText(context, "Core 7 Enabled", Toast.LENGTH_SHORT).show() }
        })
        coreCard.addView(btnRow)
        layout.addView(coreCard)

        // Process Manager Card
        val procCard = createCard(cardBg)
        procCard.addView(TextView(this).apply { text = "LIVE CPU STRESS & TERMINATION"; textSize = 11f; setTextColor(Color.GRAY); setPadding(0,0,0,15) })
        processListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        procCard.addView(processListContainer)
        layout.addView(procCard)

        return ScrollView(this).apply { addView(layout); isFillViewport = true }
    }

    // ==========================================
    // LOGIC & DATA REFRESH
    // ==========================================
    private fun createCard(bgColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 40f; setColor(bgColor) }
            setPadding(35, 30, 35, 30)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 25 }
        }
    }

    private fun refreshDashboardData() {
        val health = BatteryHealthHelper.getHealthData(this)
        healthPercentText.text = "${health.healthPercent}%"
        actualCapacityText.text = "Actual: ${health.actualCapacityMah} mAh / Design: ${health.designCapacityMah} mAh\nCycles: ${health.cycleCount}"

        val isPlugged = (registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val stats = PowerHardwareHelper.readPowerStats(this, isPlugged)
        pulseView.setMode(stats.isCharging)
        livePowerText.text = if (stats.isCharging) "⚡ ${stats.wattage}W (+${stats.currentMa} mA)" else "🔋 Discharging (-${stats.wattage}W)"

        chartView.setData(dbHelper.getAllRecords())
    }

    private fun startLiveCpuUpdates() {
        thread {
            while (isCpuTabActive) {
                if (HardwareThermalControl.isRootAvailable()) {
                    val (snapshot, _) = HardwareThermalControl.getKernelProcessSnapshot()
                    uiHandler.post { updateProcessListUI(snapshot) }
                }
                Thread.sleep(2000)
            }
        }
    }

    private fun updateProcessListUI(snapshot: String) {
        if (!isCpuTabActive) return
        processListContainer.removeAllViews()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isDark) Color.WHITE else Color.BLACK

        if (snapshot.isEmpty()) {
            processListContainer.addView(TextView(this).apply { text = "Waiting for kernel data..."; setTextColor(Color.GRAY) })
            return
        }

        val lines = snapshot.split("\n")
        for (line in lines) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 10); gravity = Gravity.CENTER_VERTICAL }
            val txt = TextView(this).apply {
                text = line
                textSize = 12f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(txt)

            val pidMatch = Regex("\\(PID (\\d+)\\)").find(line)
            if (pidMatch != null && !line.contains("system_server") && !line.contains("surfaceflinger")) {
                val pid = pidMatch.groupValues[1].toInt()
                val killBtn = Button(this).apply {
                    text = "KILL"
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#D32F2F"))
                    setPadding(10, 0, 10, 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 80)
                    setOnClickListener { showKillConfirmDialog(pid, line) }
                }
                row.addView(killBtn)
            }
            processListContainer.addView(row)
        }
    }

    private fun showKillConfirmDialog(pid: Int, processDesc: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Terminate Process?")
            .setMessage("Are you sure you want to kill PID $pid?\n\n$processDesc")
            .setPositiveButton("KILL") { _, _ ->
                HardwareThermalControl.killProcess(pid)
                Toast.makeText(this, "Force Stop signal sent to PID $pid", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
        val intent = Intent(this, TempMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }
}
