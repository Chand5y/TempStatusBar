package com.personal.tempstatusbar

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import kotlin.concurrent.thread
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var settings: SettingsManager
    private lateinit var statusBadge: TextView
    private lateinit var tempValueText: TextView
    private lateinit var powerValueText: TextView
    private lateinit var gpuValueText: TextView
    private lateinit var ramValueText: TextView
    private lateinit var coresValueText: TextView
    private lateinit var overlaySwitch: Switch
    private lateinit var tempInPillSwitch: Switch
    private lateinit var gamingSwitch: Switch
    private lateinit var bypassSwitch: Switch

    private val uiHandler = Handler(Looper.getMainLooper())
    private var isUiActive = false

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            if (!isUiActive) return
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
            val plugged = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val pwrStats = PowerHardwareHelper.readPowerStats(applicationContext, plugged)
            val ramStr = HardwareThermalControl.getRamUsage(applicationContext)

            thread {
                val gpuLoad = HardwareThermalControl.getGpuUsage()
                val gpuFreq = HardwareThermalControl.getGpuFrequency()
                val cores = HardwareThermalControl.getCoreFrequencies()
                val coresFormatted = if (cores.size >= 8) {
                    "C0-C3 (Silver): ${cores[0]}  |  C4-C6 (Gold): ${cores[4]}\nC7 (Prime): ${cores[7]}"
                } else {
                    cores.joinToString(" | ")
                }

                uiHandler.post {
                    if (!isUiActive) return@post
                    tempValueText.text = "${temp}°C"
                    tempValueText.setTextColor(
                        when {
                            temp >= 41 -> Color.parseColor("#FF5252")
                            temp >= 38 -> Color.parseColor("#FFB74D")
                            else -> Color.parseColor("#69F0AE")
                        }
                    )
                    val sign = if (pwrStats.isCharging) "+" else "-"
                    powerValueText.text = "$sign${pwrStats.wattage}W (${abs(pwrStats.currentMa)} mA)"
                    gpuValueText.text = "$gpuLoad @ $gpuFreq"
                    ramValueText.text = ramStr.replace("\n", " ")
                    coresValueText.text = coresFormatted
                    updateServiceBadge()
                }
            }
            uiHandler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager(this)
        HardwareThermalControl.init(this)

        window.statusBarColor = Color.parseColor("#0A0A0A")
        window.navigationBarColor = Color.parseColor("#0A0A0A")

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 80)
        }

        // Header Row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "THERMAL ENGINE"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.05f
        })
        titleCol.addView(TextView(this).apply {
            text = "${HardwareThermalControl.getHardwareInfo()} • HyperOS"
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 12f
            setPadding(0, 6, 0, 0)
        })

        statusBadge = TextView(this).apply {
            text = "CHECKING"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(30, 14, 30, 14)
        }
        headerRow.addView(titleCol)
        headerRow.addView(statusBadge)
        root.addView(headerRow)

        addSpacer(root, 48)

        // Live Telemetry Card
        val telemetryCard = createGlassCard()
        val telemetryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val tempBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tempBox.addView(createLabel("BATTERY TEMP"))
        tempValueText = TextView(this).apply {
            text = "--°C"
            setTextColor(Color.parseColor("#69F0AE"))
            textSize = 32f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        tempBox.addView(tempValueText)

        val pwrBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        pwrBox.addView(createLabel("POWER DRAW"))
        powerValueText = TextView(this).apply {
            text = "-- W"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 18, 0, 0)
        }
        pwrBox.addView(powerValueText)

        telemetryRow.addView(tempBox)
        telemetryRow.addView(pwrBox)
        telemetryCard.addView(telemetryRow)

        telemetryCard.addView(createDivider())
        telemetryCard.addView(createLabel("ADRENO GPU STATUS"))
        gpuValueText = createMetricText("Reading...")
        telemetryCard.addView(gpuValueText)

        telemetryCard.addView(createDivider())
        telemetryCard.addView(createLabel("KRYO CPU CLUSTERS"))
        coresValueText = createMetricText("Reading...")
        telemetryCard.addView(coresValueText)

        telemetryCard.addView(createDivider())
        telemetryCard.addView(createLabel("MEMORY"))
        ramValueText = createMetricText("Reading...")
        telemetryCard.addView(ramValueText)

        root.addView(telemetryCard)
        addSpacer(root, 40)

        // Controls Card
        val controlsCard = createGlassCard()
        val prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)

        gamingSwitch = createSwitchRow("Nuclear Gaming Mode (120 FPS)", settings.gamingModeEnabled) { enabled ->
            settings.gamingModeEnabled = enabled
            prefs.edit().putBoolean("gamingModeEnabled", enabled).apply()
            if (!enabled) HardwareThermalControl.setPeakPerformanceMode(false)
        }

        overlaySwitch = createSwitchRow("Floating FPS Pill", FpsOverlayService.isRunning) { enabled ->
            toggleFpsOverlay(enabled)
        }

        tempInPillSwitch = createSwitchRow("Show Temperature in Pill", prefs.getBoolean("showTempInFpsOverlay", true)) { enabled ->
            prefs.edit().putBoolean("showTempInFpsOverlay", enabled).apply()
        }

        bypassSwitch = createSwitchRow("Isolate Battery (Pause Charging)", HardwareThermalControl.isManualBypassActive) { enabled ->
            HardwareThermalControl.setChargingEnabled(!enabled, isManualToggle = true)
        }

        controlsCard.addView(gamingSwitch)
        controlsCard.addView(createDivider())
        controlsCard.addView(overlaySwitch)
        controlsCard.addView(createDivider())
        controlsCard.addView(tempInPillSwitch)
        controlsCard.addView(createDivider())
        controlsCard.addView(bypassSwitch)
        root.addView(controlsCard)

        addSpacer(root, 40)

        // Quick Actions Card (Refresh Rate + RAM Purge)
        val actionsCard = createGlassCard()
        actionsCard.addView(createLabel("DISPLAY REFRESH OVERRIDE"))
        val hzRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            setPadding(0, 16, 0, 8)
        }
        listOf(60, 90, 120).forEach { hz ->
            val btn = createActionPill("${hz}Hz") {
                HardwareThermalControl.forceRefreshRate(hz)
                Toast.makeText(this, "Forced ${hz}Hz", Toast.LENGTH_SHORT).show()
            }
            btn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 8, 0)
            }
            hzRow.addView(btn)
        }
        actionsCard.addView(hzRow)
        root.addView(actionsCard)

        addSpacer(root, 48)

        // Primary Start/Restart Service Button
        val startBtn = TextView(this).apply {
            text = "START / SYNC ENGINE"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
            setPadding(0, 42, 0, 42)
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#1E88E5"))
            }
            setOnClickListener {
                startCoreServices()
            }
        }
        root.addView(startBtn)

        scrollView.addView(root)
        setContentView(scrollView)

        startCoreServices()
    }

    private fun createGlassCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 40, 44, 40)
            background = GradientDrawable().apply {
                cornerRadius = 36f
                setColor(Color.parseColor("#141414"))
                setStroke(2, Color.parseColor("#24FFFFFF"))
            }
        }
    }

    private fun createLabel(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.parseColor("#73FFFFFF"))
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.08f
        }
    }

    private fun createMetricText(initial: String): TextView {
        return TextView(this).apply {
            text = initial
            setTextColor(Color.parseColor("#E6FFFFFF"))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 8, 0, 4)
        }
    }

    private fun createSwitchRow(title: String, initialValue: Boolean, onToggle: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isChecked = initialValue
            setPadding(0, 28, 0, 28)
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
    }

    private fun createActionPill(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 26, 0, 26)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#222222"))
                setStroke(2, Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 24, 0, 24)
            }
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
        }
    }

    private fun addSpacer(parent: LinearLayout, heightPx: Int) {
        parent.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, heightPx)
        })
    }

    private fun toggleFpsOverlay(enable: Boolean) {
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Grant Overlay Permission first", Toast.LENGTH_LONG).show()
            overlaySwitch.isChecked = false
            return
        }
        if (enable) {
            startService(Intent(this, FpsOverlayService::class.java))
        } else {
            stopService(Intent(this, FpsOverlayService::class.java))
        }
    }

    private fun startCoreServices() {
        val intent = Intent(this, TempMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateServiceBadge()
    }

    private fun updateServiceBadge() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == TempMonitorService::class.java.name
        }
        statusBadge.text = if (running) "ACTIVE" else "STOPPED"
        statusBadge.background = GradientDrawable().apply {
            cornerRadius = 100f
            setColor(if (running) Color.parseColor("#264CAF50") else Color.parseColor("#26E53935"))
            setStroke(2, if (running) Color.parseColor("#804CAF50") else Color.parseColor("#80E53935"))
        }
    }

    override fun onResume() {
        super.onResume()
        isUiActive = true
        overlaySwitch.isChecked = FpsOverlayService.isRunning
        uiHandler.post(telemetryRunnable)
    }

    override fun onPause() {
        super.onPause()
        isUiActive = false
        uiHandler.removeCallbacks(telemetryRunnable)
    }
}
