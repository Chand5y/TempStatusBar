package com.personal.tempstatusbar

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var overlaySwitch: Switch
    private lateinit var gamingSwitch: Switch
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Premium Dark Background
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            setPadding(60, 100, 60, 60)
        }

        // Header Title
        val title = TextView(this).apply {
            text = "THERMAL ENGINE"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = 0.05f
            setPadding(0, 0, 0, 80)
        }
        rootLayout.addView(title)

        // Status Card
        val statusCard = createPremiumCard()
        statusText = TextView(this).apply {
            text = "Engine Status: Inactive"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(50, 40, 50, 40)
        }
        statusCard.addView(statusText)
        rootLayout.addView(statusCard)
        
        val space = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 60) }
        rootLayout.addView(space)

        // Toggles Card
        val controlsCard = createPremiumCard()
        val controlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        overlaySwitch = createPremiumSwitch("Live FPS & Temp Overlay")
        gamingSwitch = createPremiumSwitch("Nuclear Gaming Mode")
        
        // Set initial states from preferences
        val prefs = getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)
        overlaySwitch.isChecked = prefs.getBoolean("showTempInFpsOverlay", false)
        gamingSwitch.isChecked = prefs.getBoolean("gamingModeEnabled", false)

        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("showTempInFpsOverlay", isChecked).apply()
            checkOverlayPermissionAndToggle(isChecked)
        }

        gamingSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("gamingModeEnabled", isChecked).apply()
            Toast.makeText(this, if(isChecked) "Nuclear Engine Armed" else "Engine Disarmed", Toast.LENGTH_SHORT).show()
        }

        controlsLayout.addView(overlaySwitch)
        controlsLayout.addView(createDivider())
        controlsLayout.addView(gamingSwitch)
        controlsCard.addView(controlsLayout)
        rootLayout.addView(controlsCard)

        // Action Button
        val space2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 80) }
        rootLayout.addView(space2)

        val startBtn = Button(this).apply {
            text = "START CORE SERVICES"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            letterSpacing = 0.1f
            background = GradientDrawable().apply {
                cornerRadius = 100f
                setColor(Color.parseColor("#D32F2F"))
            }
            setPadding(0, 45, 0, 45)
            setOnClickListener {
                startServices()
            }
        }
        rootLayout.addView(startBtn)

        setContentView(rootLayout)
        updateStatusText()
    }

    private fun createPremiumCard(): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                cornerRadius = 32f
                setColor(Color.parseColor("#171717"))
                setStroke(2, Color.parseColor("#33FFFFFF"))
            }
        }
    }

    private fun createPremiumSwitch(labelText: String): Switch {
        return Switch(this).apply {
            text = labelText
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 40, 0, 40)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 10, 0, 10)
            }
            setBackgroundColor(Color.parseColor("#26FFFFFF"))
        }
    }

    private fun checkOverlayPermissionAndToggle(enable: Boolean) {
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Please grant Overlay Permission first", Toast.LENGTH_LONG).show()
            overlaySwitch.isChecked = false
        } else if (enable) {
            startService(Intent(this, FpsOverlayService::class.java))
        } else {
            stopService(Intent(this, FpsOverlayService::class.java))
        }
    }

    private fun startServices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, TempMonitorService::class.java))
        } else {
            startService(Intent(this, TempMonitorService::class.java))
        }
        
        if (overlaySwitch.isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            startService(Intent(this, FpsOverlayService::class.java))
        }
        
        Toast.makeText(this, "Thermal Engine Running", Toast.LENGTH_SHORT).show()
        updateStatusText()
    }

    private fun updateStatusText() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var isRunning = false
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (TempMonitorService::class.java.name == service.service.className) {
                isRunning = true
            }
        }
        
        if (isRunning) {
            statusText.text = "Engine Status: ACTIVE \uD83D\uDFE2"
            statusText.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            statusText.text = "Engine Status: INACTIVE \uD83D\uDD34"
            statusText.setTextColor(Color.parseColor("#E53935"))
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateStatusText()
    }
}
