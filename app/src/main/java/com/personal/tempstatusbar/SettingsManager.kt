package com.personal.tempstatusbar

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("thermal_config", Context.MODE_PRIVATE)

    var warningTemp: Int
        get() = prefs.getInt("warn_temp", 40)
        set(v) = prefs.edit().putInt("warn_temp", v).apply()

    var cutoffTemp: Int
        get() = prefs.getInt("cutoff_temp", 41)
        set(v) = prefs.edit().putInt("cutoff_temp", v).apply()

    var resumeTemp: Int
        get() = prefs.getInt("resume_temp", 39)
        set(v) = prefs.edit().putInt("resume_temp", v).apply()

    var showNotification: Boolean
        get() = prefs.getBoolean("show_notif", true)
        set(v) = prefs.edit().putBoolean("show_notif", v).apply()

    var showPowerMetrics: Boolean
        get() = prefs.getBoolean("show_power", true)
        set(v) = prefs.edit().putBoolean("show_power", v).apply()
}
