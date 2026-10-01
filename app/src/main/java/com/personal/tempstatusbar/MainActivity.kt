package com.personal.tempstatusbar

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private lateinit var chartView: TemperatureChartView
    private lateinit var dbHelper: DatabaseHelper

    // Card View References
    private lateinit var healthPercentText: TextView
    private lateinit var healthStatusText: TextView
    private lateinit var designCapacityText: TextView
    private lateinit var actualCapacityText: TextView
    private lateinit var cyclesText: TextView

    private lateinit var detailTimeText: TextView
    private lateinit var detailTempText: TextView
    private lateinit var detailPowerText: TextView
    private lateinit var detailSourceBadge: TextView
    private lateinit var detailAppContent: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = DatabaseHelper(this)

        checkPermissionsAndStartService()
        buildHyperOsInterface()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
    }

    private fun buildHyperOsInterface() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bgCanvas = if (isDark) Color.BLACK else Color.parseColor("#F4F4F6")
        val cardBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val textPrimary = if (isDark) Color.WHITE else Color.parseColor("#111111")
        val textSecondary = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#8E8E93")

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgCanvas)
            setPadding(40, 70, 40, 60)
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
            }
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header Title
        val headerTitle = TextView(this).apply {
            text = "Battery & Thermal"
            textSize = 28f
            setTextColor(textPrimary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(10, 0, 0, 35)
        }
        rootLayout.addView(headerTitle)

        // 1. CARD: Apple-Style Battery Health Card
        val healthCard = createHyperOsCard(cardBg)
        
        val healthHeader = TextView(this).apply {
            text = "BATTERY HEALTH & CAPACITY"
            textSize = 12f
            setTextColor(textSecondary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }
        healthCard.addView(healthHeader)

        val healthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 5, 0, 20)
        }

        healthPercentText = TextView(this).apply {
            text = "--%"
            textSize = 42f
            setTextColor(textPrimary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        healthRow.addView(healthPercentText)

        healthStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#00E676"))
            setPadding(25, 30, 0, 0)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        healthRow.addView(healthStatusText)
        healthCard.addView(healthRow)

        // Battery Specs Row
        val specRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }

        designCapacityText = createSpecColumn(this, "Design Capacity", "--", textPrimary, textSecondary, specRow)
        actualCapacityText = createSpecColumn(this, "Actual Capacity", "--", textPrimary, textSecondary, specRow)
        cyclesText = createSpecColumn(this, "Cycles", "--", textPrimary, textSecondary, specRow)

        healthCard.addView(specRow)
        rootLayout.addView(healthCard)

        // 2. CARD: Thermal Timeline Chart Card
        val chartCard = createHyperOsCard(cardBg)
        val chartHeader = TextView(this).apply {
            text = "THERMAL TIMELINE"
            textSize = 12f
            setTextColor(textSecondary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, 0, 0, 15)
        }
        chartCard.addView(chartHeader)

        chartView = TemperatureChartView(this).apply {
            this.isDarkMode = isDark
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 460)
        }
        chartCard.addView(chartView)
        rootLayout.addView(chartCard)

        // 3. CARD: Touch Inspection Breakdown Card
        val detailCard = createHyperOsCard(cardBg)

        detailTimeText = TextView(this).apply {
            textSize = 13f
            setTextColor(textSecondary)
        }
        detailCard.addView(detailTimeText)

        val tempPowerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 5, 0, 10)
        }

        detailTempText = TextView(this).apply {
            textSize = 34f
            setTextColor(Color.parseColor("#FF5722"))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        tempPowerRow.addView(detailTempText)

        detailPowerText = TextView(this).apply {
            textSize = 14f
            setTextColor(textPrimary)
            setPadding(25, 20, 0, 0)
        }
        tempPowerRow.addView(detailPowerText)
        detailCard.addView(tempPowerRow)

        detailSourceBadge = TextView(this).apply {
            textSize = 12f
            setPadding(0, 0, 0, 15)
        }
        detailCard.addView(detailSourceBadge)

        val procTitle = TextView(this).apply {
            text = "ACTIVE APPS & BACKGROUND PROCESSES"
            textSize = 11f
            setTextColor(textSecondary)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, 10, 0, 10)
        }
        detailCard.addView(procTitle)

        detailAppContent = TextView(this).apply {
            textSize = 13f
            setTextColor(if (isDark) Color.parseColor("#CFD8DC") else Color.parseColor("#37474F"))
            typeface = Typeface.MONOSPACE
        }
        detailCard.addView(detailAppContent)
        rootLayout.addView(detailCard)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgCanvas)
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(rootLayout)
        }
        setContentView(scroll)

        chartView.onRecordSelected = { r ->
            val sdf = SimpleDateFormat("MMM dd, hh:mm:ss a", Locale.getDefault())
            detailTimeText.text = sdf.format(Date(r.timestamp))
            detailTempText.text = "${r.temp}°C"

            val pfx = if (r.isCharging) "⚡ Charging:" else "🔋 Discharging:"
            detailPowerText.text = "$pfx ${r.chargeType}"

            if (r.isRoot) {
                detailSourceBadge.text = "🛡️ Kernel Deep Trace (Root)"
                detailSourceBadge.setTextColor(Color.parseColor("#00E676"))
            } else {
                detailSourceBadge.text = "📱 System Usage Log (Non-Root)"
                detailSourceBadge.setTextColor(Color.parseColor("#29B6F6"))
            }
            detailAppContent.text = r.appDetails
        }
    }

    private fun createHyperOsCard(bgColor: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 48f
                setColor(bgColor)
            }
            background = shape
            setPadding(42, 38, 42, 38)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 30
            layoutParams = lp
        }
    }

    private fun createSpecColumn(context: Context, title: String, value: String, primaryColor: Int, secondaryColor: Int, parent: LinearLayout): TextView {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tView = TextView(context).apply {
            text = title
            textSize = 11f
            setTextColor(secondaryColor)
        }
        val vView = TextView(context).apply {
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

        val records = dbHelper.getAllRecords()
        chartView.setData(records)
    }

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {}
        }

        val serviceIntent = Intent(this, TempMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
