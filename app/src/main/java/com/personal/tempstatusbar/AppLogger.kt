package com.personal.tempstatusbar

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {
    private const val MAX_LINES = 1500
    private val memoryLogs = CopyOnWriteArrayList<String>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile == null) {
            logFile = File(context.filesDir, "live_diagnostic_log.txt")
            try {
                if (logFile!!.exists()) {
                    val existing = logFile!!.readLines().takeLast(400)
                    if (memoryLogs.isEmpty()) memoryLogs.addAll(existing)
                }
            } catch (e: Exception) {}
        }
    }

    fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        val line = "[$timestamp] $message"
        memoryLogs.add(line)
        while (memoryLogs.size > MAX_LINES) {
            memoryLogs.removeAt(0)
        }
        try {
            logFile?.appendText("$line\n")
        } catch (e: Exception) {}
    }

    fun logUi(component: String, detail: String) {
        log("👆 [UI ACTION] $component -> $detail")
    }

    fun getLogs(): String {
        if (memoryLogs.isEmpty()) {
            try {
                if (logFile?.exists() == true) {
                    val disk = logFile!!.readText()
                    if (disk.isNotBlank()) return disk
                }
            } catch (e: Exception) {}
            return "[SYSTEM] Logger ready. Interact with any control to see live kernel verification."
        }
        return memoryLogs.joinToString("\n")
    }

    fun clear() {
        memoryLogs.clear()
        try {
            logFile?.writeText("")
        } catch (e: Exception) {}
        log("==========================================")
        log("LOGS CLEARED BY USER — LIVE TRACKING ACTIVE")
    }
}
