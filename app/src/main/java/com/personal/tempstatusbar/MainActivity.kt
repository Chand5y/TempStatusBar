package com.personal.tempstatusbar

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
    private lateinit var detailTimeText: TextView
    private lateinit var detailTempText: TextView
    private lateinit var detailPowerText: TextView
    private lateinit var detailSourceBadge: TextView
    private lateinit var detailAppContent: TextView
    private lateinit var dbHelper: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = DatabaseHelper(this)

        checkPermissionsAndStartService()
        buildUserInterface()
    }

    override fun onResume() {
        super.onResume()
        refreshChart()
    }

    private fun buildUserInterface() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(40, 60, 40, 40)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header Title
        val titleText = TextView(this).apply {
            text = "Thermal Timeline"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        rootLayout.addView(titleText)

        val subTitle = TextView(this).apply {
            text = "Touch graph curve to inspect spikes & apps"
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, 8, 0, 30)
        }
        rootLayout.addView(subTitle)

        // Custom Canvas Chart
        chartView = TemperatureChartView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 550)
        }
        rootLayout.addView(chartView)

        // Inspection Details Card
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(35, 35, 35, 35)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = 40
            layoutParams = lp
        }

        detailTimeText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#8E8E93"))
        }
        cardLayout.addView(detailTimeText)

        detailTempText = TextView(this).apply {
            textSize = 34f
            setTextColor(Color.parseColor("#FF5722"))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, 10, 0, 10)
        }
        cardLayout.addView(detailTempText)

        detailPowerText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#E0E0E0"))
        }
        cardLayout.addView(detailPowerText)

        detailSourceBadge = TextView(this).apply {
            textSize = 12f
            setPadding(0, 10, 0, 15)
        }
        cardLayout.addView(detailSourceBadge)

        val processHeading = TextView(this).apply {
            text = "ACTIVE APPS & BACKGROUND PROCESSES:"
            textSize = 12f
            setTextColor(Color.parseColor("#8E8E93"))
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setPadding(0, 15, 0, 10)
        }
        cardLayout.addView(processHeading)

        detailAppContent = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#B0BEC5"))
            typeface = Typeface.MONOSPACE
        }
        cardLayout.addView(detailAppContent)

        rootLayout.addView(cardLayout)

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(rootLayout)
        }
        setContentView(scroll)

        // Connect touch interaction
        chartView.onRecordSelected = { record ->
            val sdf = SimpleDateFormat("MMM dd, hh:mm:ss a", Locale.getDefault())
            detailTimeText.text = sdf.format(Date(record.timestamp))
            detailTempText.text = "${record.temp}°C"

            val powerPrefix = if (record.isCharging) "⚡ Charging:" else "🔋 Discharging:"
            detailPowerText.text = "$powerPrefix ${record.chargeType}"

            if (record.isRoot) {
                detailSourceBadge.text = "🛡️ Kernel Deep Trace (Root Access)"
                detailSourceBadge.setTextColor(Color.parseColor("#00E676"))
            } else {
                detailSourceBadge.text = "📱 System Usage Log (Non-Root)"
                detailSourceBadge.setTextColor(Color.parseColor("#29B6F6"))
            }

            detailAppContent.text = record.appDetails
        }
    }

    private fun refreshChart() {
        val records = dbHelper.getAllRecords()
        chartView.setData(records)
    }

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Check Usage Access (For non-root tracking)
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) {
            Toast.makeText(this, "Enable 'Usage Access' for non-root app tracking", Toast.LENGTH_LONG).show()
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
