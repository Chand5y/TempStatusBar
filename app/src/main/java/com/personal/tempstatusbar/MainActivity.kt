package com.personal.tempstatusbar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private lateinit var chartView: TemperatureChartView
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var settings: SettingsManager
    private lateinit var pulseView: SubtlePulseView

    // References
    private lateinit var livePowerText: TextView
    private lateinit var healthPercentText: TextView
    private lateinit var healthStatusText: TextView
    private lateinit var designCapacityText: TextView
    private lateinit var actualCapacityText: TextView
    private lateinit var cyclesText: TextView

    private lateinit var warnLabel: TextView
    private lateinit var cutoffLabel: TextView
    private lateinit var resumeLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = DatabaseHelper(this)
        settings = SettingsManager(this)

        buildHyperOsInterface()
        checkPermissionsAndStartService()
    }

    override fun onResume() {
        super.onResume()
        pulseView.startAnimation()
        refreshDashboard()
    }

    override fun onPause() {
        super.onPause()
        pulseView.stopAnimation() // 0% CPU footprint when minimized
    }

    private fun buildHyperOsInterface() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bgCanvas = if (isDark) Color.BLACK else Color.parseColor("#F4F4F6")
        val cardBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val textPrimary = if (isDark) Color.WHITE else Color.parseColor("#111111")
        val textSecondary = Color.parseColor("#8E8E93")

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCanvas)
            setPadding(40, 60, 40, 60)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header Title
        val header = TextView(this).apply {
            text = "Battery & Thermal Shield"
            textSize = 26f
            setTextColor(textPrimary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(10, 0, 0, 30)
        }
        rootLayout.addView(header)

        // 1. CARD: Live Status with Subtle Animation Indicator
        val statusCard = createCard(cardBg)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
        }

        pulseView = SubtlePulseView(this).apply {
            layoutParams = LinearLayout.LayoutParams(60, 60)
        }
        statusRow.addView(pulseView)

        livePowerText = TextView(this).apply {
            textSize = 16f
            setTextColor(textPrimary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(30, 8, 0, 0)
            text = "Reading hardware metrics..."
        }
        statusRow.addView(livePowerText)
        statusCard.addView(statusRow)
        rootLayout.addView(statusCard)

        // 2. CARD: Battery Health Card
        val healthCard = createCard(cardBg)
        val hTitle = TextView(this).apply {
            text = "BATTERY HEALTH & CAPACITY"
            textSize = 12f
            setTextColor(textSecondary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }
        healthCard.addView(hTitle)

        val healthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 20)
        }

        healthPercentText = TextView(this).apply {
            text = "--%"
            textSize = 40f
            setTextColor(textPrimary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        healthRow.addView(healthPercentText)

        healthStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(25, 28, 0, 0)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        healthRow.addView(healthStatusText)
        healthCard.addView(healthRow)

        val specRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        designCapacityText = createSpecCol("Design", "--", textPrimary, textSecondary, specRow)
        actualCapacityText = createSpecCol("Actual", "--", textPrimary, textSecondary, specRow)
        cyclesText = createSpecCol("Cycles", "--", textPrimary, textSecondary, specRow)
        healthCard.addView(specRow)
        rootLayout.addView(healthCard)

        // 3. CARD: Sliders & Thermal Threshold Protection
        val controlCard = createCard(cardBg)
        val cTitle = TextView(this).apply {
            text = "HARDWARE THERMAL PROTECTION"
            textSize = 12f
            setTextColor(textSecondary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }
        controlCard.addView(cTitle)

        // Warning Alert Slider (35 - 48°C)
        warnLabel = TextView(this).apply { setTextColor(textPrimary); textSize = 14f }
        controlCard.addView(warnLabel)
        val warnSeek = SeekBar(this).apply {
            max = 13 // 35 + 13 = 48
            progress = settings.warningTemp - 35
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, b: Boolean) {
                    val v = 35 + p
                    settings.warningTemp = v
                    warnLabel.text = "⚠️ Warning Sound Alert: $v°C"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        warnLabel.text = "⚠️ Warning Sound Alert: ${settings.warningTemp}°C"
        controlCard.addView(warnSeek)

        // PMIC Cutoff Slider (38 - 50°C)
        cutoffLabel = TextView(this).apply { setTextColor(textPrimary); textSize = 14f; setPadding(0, 20, 0, 0) }
        controlCard.addView(cutoffLabel)
        val cutoffSeek = SeekBar(this)

        // PMIC Resume Slider (32 - 45°C)
        resumeLabel = TextView(this).apply { setTextColor(textPrimary); textSize = 14f; setPadding(0, 20, 0, 0) }
        val resumeSeek = SeekBar(this)

        cutoffSeek.apply {
            max = 12 // 38 + 12 = 50
            progress = settings.cutoffTemp - 38
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, b: Boolean) {
                    val v = 38 + p
                    settings.cutoffTemp = v
                    cutoffLabel.text = "🛑 Cut Off Charging (PMIC): $v°C"

                    // Auto-clamp resume slider to maintain 2°C hysteresis
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
            max = 13 // 32 + 13 = 45
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
        rootLayout.addView(controlCard)

        // 4. CARD: Notification Toggles
        val toggleCard = createCard(cardBg)
        val tTitle = TextView(this).apply {
            text = "NOTIFICATION PREFERENCES"
            textSize = 12f
            setTextColor(textSecondary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }
        toggleCard.addView(tTitle)

        val switchNotif = Switch(this).apply {
            text = "Show Status Bar Notification"
            setTextColor(textPrimary)
            isChecked = settings.showNotification
            setOnCheckedChangeListener { _, checked ->
                settings.showNotification = checked
                startService()
            }
        }
        toggleCard.addView(switchNotif)

        val switchPower = Switch(this).apply {
            text = "Show Real-Time Wattage / Drain"
            setTextColor(textPrimary)
            isChecked = settings.showPowerMetrics
            setOnCheckedChangeListener { _, checked ->
                settings.showPowerMetrics = checked
                startService()
            }
        }
        toggleCard.addView(switchPower)
        rootLayout.addView(toggleCard)

        // 5. CARD: Thermal Timeline Chart Card
        val chartCard = createCard(cardBg)
        chartView = TemperatureChartView(this).apply {
            this.isDarkMode = isDark
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 460)
        }
        chartCard.addView(chartView)
        rootLayout.addView(chartCard)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgCanvas)
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(rootLayout)
        }
        setContentView(scroll)
    }

    private fun createCard(bgColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 48f
                setColor(bgColor)
            }
            background = shape
            setPadding(40, 36, 40, 36)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 28
            layoutParams = lp
        }
    }

    private fun createSpecCol(title: String, value: String, primaryColor: Int, secondaryColor: Int, parent: LinearLayout): TextView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tView = TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(secondaryColor)
        }
        val vView = TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(primaryColor)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, 4, 0, 0)
        }
        col.addView(tView)
        col.addView(vView)
        parent.addView(col)
        return vView
    }

    private fun refreshDashboard() {
        val health = BatteryHealthHelper.getHealthData(this)
        healthPercentText.text = "${health.healthPercent}%"
        healthStatusText.text = "• ${health.statusText}"
        designCapacityText.text = "${health.designCapacityMah} mAh"
        actualCapacityText.text = "${health.actualCapacityMah} mAh"
        cyclesText.text = "${health.cycleCount}"

        val stats = PowerHardwareHelper.readPowerStats(false)
        pulseView.setMode(stats.isCharging)
        livePowerText.text = if (stats.isCharging) {
            "⚡ Charging: ${stats.wattage}W (+${stats.currentMa} mA)"
        } else {
            "🔋 Discharging: ${stats.currentMa} mA (-${stats.wattage}W)"
        }

        chartView.setData(dbHelper.getAllRecords())
    }

    private fun startService() {
        val intent = Intent(this, TempMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        startService()
    }
}
