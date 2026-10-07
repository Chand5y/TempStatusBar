package com.personal.tempstatusbar

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("TempMonitorPrefs", Context.MODE_PRIVATE)

    var warningTemp: Int
        get() = prefs.getInt("warningTemp", 40)
        set(value) = prefs.edit().putInt("warningTemp", value).apply()

    var cutoffTemp: Int
        get() = prefs.getInt("cutoffTemp", 41)
        set(value) = prefs.edit().putInt("cutoffTemp", value).apply()

    var resumeTemp: Int
        get() = prefs.getInt("resumeTemp", 39)
        set(value) = prefs.edit().putInt("resumeTemp", value).apply()

    var showNotification: Boolean
        get() = prefs.getBoolean("showNotification", true)
        set(value) = prefs.edit().putBoolean("showNotification", value).apply()

    var showPowerMetrics: Boolean
        get() = prefs.getBoolean("showPowerMetrics", true)
        set(value) = prefs.edit().putBoolean("showPowerMetrics", value).apply()

    var chargeLimitEnabled: Boolean
        get() = prefs.getBoolean("chargeLimitEnabled", false)
        set(value) = prefs.edit().putBoolean("chargeLimitEnabled", value).apply()

    var chargeLimitMax: Int
        get() = prefs.getInt("chargeLimitMax", 80)
        set(value) = prefs.edit().putInt("chargeLimitMax", value).apply()

    var chargeLimitResume: Int
        get() = prefs.getInt("chargeLimitResume", 75)
        set(value) = prefs.edit().putInt("chargeLimitResume", value).apply()
}
