package com.personal.tempstatusbar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var warnLabel: TextView
    private lateinit var cutoffLabel: TextView
    private lateinit var resumeLabel: TextView

    // Tab 2 Elements
    private lateinit var pulseView: SubtlePulseView
    private lateinit var livePowerText: TextView
    private lateinit var healthPercentText: TextView
    private lateinit var actualCapacityText: TextView
    private lateinit var batteryDrainList: TextView

    // Tab 3 Elements
    private lateinit var processListContainer: LinearLayout
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
        if (isCpuTabActive) {
            startLiveCpuUpdates()
        }
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
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = TextView(this).apply {
            text = "Hardware Shield"
            textSize = 22f
            setTextColor(if (isDark) Color.WHITE else Color.BLACK)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(40, 50, 40, 20)
        }
        rootLayout.addView(header)

        contentFrame = FrameLayout(this).apply { 
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) 
        }
        rootLayout.addView(contentFrame)

        tab1Thermal = buildTab1Thermal(isDark)
        tab2Battery = buildTab2Battery(isDark)
        tab3CPU = buildTab3CPU(isDark)

        contentFrame.addView(tab1Thermal)
        contentFrame.addView(tab2Battery)
        contentFrame.addView(tab3CPU)

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
        switchTab(0)
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
        if (isCpuTabActive) {
            startLiveCpuUpdates()
        }
    }

    // ==========================================
    // TAB 1: THERMAL & SETTINGS
    // ==========================================
    private fun buildTab1Thermal(isDark: Boolean): View {
        val layout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(40, 10, 40, 20) 
        }
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
        detailTimeText = TextView(this).apply { 
            textSize = 12f
            setTextColor(Color.GRAY) 
        }
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
            val rootBadge = if (r.isRoot) "🛡️ Root Trace" else "📱 Non-Root Trace"
            detailTimeText.text = "${sdf.format(Date(r.timestamp))} • $screenState • $rootBadge"
            detailTempText.text = "${r.temp}°C"
            detailAppContent.text = r.appDetails
        }

        // Sliders Card
        val controlCard = createCard(cardBg)
        val titleText = TextView(this).apply { 
            text = "HARDWARE THERMAL PROTECTION"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 20) 
        }
        controlCard.addView(titleText)

        warnLabel = TextView(this).apply { 
            setTextColor(textPrimary)
            textSize = 13f 
        }
        controlCard.addView(warnLabel)
        
        val warnSeek = SeekBar(this).apply { 
            max = 13
            progress = settings.warningTemp - 35 
        }
        warnSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, b: Boolean) { 
                val v = 35 + p
                settings.warningTemp = v
                warnLabel.text = "⚠️ Warning Sound Alert: $v°C" 
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        warnLabel.text = "⚠️ Warning Sound Alert: ${settings.warningTemp}°C"
        controlCard.addView(warnSeek)

        cutoffLabel = TextView(this).apply { 
            setTextColor(textPrimary)
            textSize = 13f
            setPadding(0, 16, 0, 0) 
        }
        controlCard.addView(cutoffLabel)
        
        val cutoffSeek = SeekBar(this)
        resumeLabel = TextView(this).apply { 
            setTextColor(textPrimary)
            textSize = 13f
            setPadding(0, 16, 0, 0) 
        }
        val resumeSeek = SeekBar(this)

        cutoffSeek.apply {
            max = 12
            progress = settings.cutoffTemp - 38
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, b: Boolean) {
                    val v = 38 + p
                    settings.cutoffTemp = v
                    cutoffLabel.text = "🛑 Cut Off Charging (PMIC): $v°C"
                    if (settings.resumeTemp >= v - 1) { 
                        settings.resumeTemp = v - 2
                        resumeSeek.progress = (v - 2) - 32
                        resumeLabel.text = "🔄 Resume Charging: ${v - 2}°C" 
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        cutoffLabel.text = "🛑 Cut Off Charging (PMIC): ${settings.cutoffTemp}°C"
        controlCard.addView(cutoffSeek)

        resumeSeek.apply {
            max = 13
            progress = settings.resumeTemp - 32
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, b: Boolean) {
                    var v = 32 + p
                    if (v > settings.cutoffTemp - 2) { 
                        v = settings.cutoffTemp - 2
                        progress = v - 32 
                    }
                    settings.resumeTemp = v
                    resumeLabel.text = "🔄 Resume Charging: $v°C"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        resumeLabel.text = "🔄 Resume Charging: ${settings.resumeTemp}°C"
        controlCard.addView(resumeLabel)
        controlCard.addView(resumeSeek)
        layout.addView(controlCard)

        // Toggles Card
        val toggleCard = createCard(cardBg)
        val prefsTitle = TextView(this).apply { 
            text = "NOTIFICATION PREFERENCES"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 15) 
        }
        toggleCard.addView(prefsTitle)
        
        val notifSwitch = Switch(this).apply { 
            text = "Show Status Bar Notification"
            setTextColor(textPrimary)
            isChecked = settings.showNotification
            setOnCheckedChangeListener { _, c -> 
                settings.showNotification = c
                this@MainActivity.startMonitorService() 
            } 
        }
        toggleCard.addView(notifSwitch)
        
        val powerSwitch = Switch(this).apply { 
            text = "Show Real-Time Wattage / Drain"
            setTextColor(textPrimary)
            isChecked = settings.showPowerMetrics
            setOnCheckedChangeListener { _, c -> 
                settings.showPowerMetrics = c
                this@MainActivity.startMonitorService() 
            } 
        }
        toggleCard.addView(powerSwitch)
        
        layout.addView(toggleCard)
        
        return ScrollView(this).apply { 
            addView(layout)
            isFillViewport = true 
        }
    }

    // ==========================================
    // TAB 2: BATTERY HEALTH & POWER
    // ==========================================
    private fun buildTab2Battery(isDark: Boolean): View {
        val layout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(40, 10, 40, 20) 
        }
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
        healthCard.addView(TextView(this).apply { 
            text = "BATTERY HEALTH"
            textSize = 11f
            setTextColor(Color.GRAY) 
        })
        healthPercentText = TextView(this).apply { 
            textSize = 40f
            setTextColor(textPrimary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) 
        }
        healthCard.addView(healthPercentText)
        
        actualCapacityText = TextView(this).apply { 
            textSize = 14f
            setTextColor(textPrimary)
            setPadding(0, 10, 0, 0) 
        }
        healthCard.addView(actualCapacityText)
        layout.addView(healthCard)

        val statsCard = createCard(cardBg)
        statsCard.addView(TextView(this).apply { 
            text = "PER-APP BATTERY DRAIN (SINCE UNPLUGGED)"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0,0,0,15) 
        })
        batteryDrainList = TextView(this).apply { 
            textSize = 12f
            setTextColor(if (isDark) Color.parseColor("#CFD8DC") else Color.DKGRAY)
            typeface = Typeface.MONOSPACE 
        }
        statsCard.addView(batteryDrainList)
        layout.addView(statsCard)

        return ScrollView(this).apply { 
            addView(layout)
            isFillViewport = true 
        }
    }

    // ==========================================
    // TAB 3: CPU CONTROLS & KILL MANAGER
    // ==========================================
    private fun buildTab3CPU(isDark: Boolean): View {
        val layout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(40, 10, 40, 20) 
        }
        val cardBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE

        val coreCard = createCard(cardBg)
        coreCard.addView(TextView(this).apply { 
            text = "HARDWARE CORE HOTPLUG"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0,0,0,10) 
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        
        val disableBtn = Button(this).apply {
            text = "Disable Prime Core 7"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { 
                HardwareThermalControl.setCoreOnline(7, false)
                Toast.makeText(this@MainActivity, "Core 7 Disabled (Cooling Mode)", Toast.LENGTH_SHORT).show() 
            }
        }
        btnRow.addView(disableBtn)
        
        val enableBtn = Button(this).apply {
            text = "Enable Core 7"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { 
                HardwareThermalControl.setCoreOnline(7, true)
                Toast.makeText(this@MainActivity, "Core 7 Enabled (Performance Mode)", Toast.LENGTH_SHORT).show() 
            }
        }
        btnRow.addView(enableBtn)
        coreCard.addView(btnRow)
        layout.addView(coreCard)

        val procCard = createCard(cardBg)
        procCard.addView(TextView(this).apply { 
            text = "LIVE CPU STRESS & TERMINATION"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0,0,0,15) 
        })
        processListContainer = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL 
        }
        procCard.addView(processListContainer)
        layout.addView(procCard)

        return ScrollView(this).apply { 
            addView(layout)
            isFillViewport = true 
        }
    }

    // ==========================================
    // LOGIC & DATA REFRESH
    // ==========================================
    private fun createCard(bgColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { 
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40f
                setColor(bgColor) 
            }
            setPadding(35, 30, 35, 30)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 25 }
        }
    }

    private fun refreshDashboardData() {
        val health = BatteryHealthHelper.getHealthData(this)
        healthPercentText.text = "${health.healthPercent}%"
        actualCapacityText.text = "Actual: ${health.actualCapacityMah} mAh / Design: ${health.designCapacityMah} mAh\nCycles: ${health.cycleCount}"

        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val plugged = registerReceiver(null, intentFilter)?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val isPlugged = plugged != 0
        
        val stats = PowerHardwareHelper.readPowerStats(this, isPlugged)
        pulseView.setMode(stats.isCharging)
        livePowerText.text = if (stats.isCharging) {
            "⚡ ${stats.wattage}W (+${stats.currentMa} mA)"
        } else {
            "🔋 Discharging (-${stats.wattage}W)"
        }

        chartView.setData(dbHelper.getAllRecords())
        
        thread {
            val statsStr = HardwareThermalControl.getAppBatteryDrain()
            uiHandler.post { batteryDrainList.text = statsStr }
        }
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
            pro
