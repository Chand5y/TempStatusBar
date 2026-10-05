package com.personal.tempstatusbar

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private var logFile: File? = null

    fun init(cacheDir: File) {
        logFile = File(cacheDir, "global_action_logs.txt")
        log("=================================")
        log("APP LAUNCHED / TRACKER INITIALIZED")
    }

    fun log(message: String) {
        try {
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "[$time] $message\n"
            logFile?.appendText(entry)
        } catch (e: Exception) {}
    }

    fun getLogs(): String {
        return try { logFile?.readText() ?: "No logs found." } catch (e: Exception) { "Error reading logs." }
    }

    fun clearLogs() {
        try { logFile?.writeText("") } catch (e: Exception) {}
        log("LOGS CLEARED BY USER")
    }

    fun exportLogsToDownloads(fileName: String, content: String): Boolean {
        return try {
            val dir = "/storage/emulated/0/Download/TempMonitorLog"
            // Use root to forcefully bypass Android 13+ Scoped Storage restrictions
            Runtime.getRuntime().exec(arrayOf("su", "-c", "mkdir -p $dir")).waitFor()
            
            val tempFile = File.createTempFile("log_dump", ".txt")
            tempFile.writeText(content)
            
            Runtime.getRuntime().exec(arrayOf("su", "-c", "cp ${tempFile.absolutePath} $dir/$fileName")).waitFor()
            Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 777 $dir/$fileName")).waitFor()
            
            tempFile.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun handleFatalCrash(throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val errorStr = sw.toString()
        
        log("FATAL CRASH DETECTED:\n$errorStr")
        
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        exportLogsToDownloads("CRASH_$timeStamp.txt", getLogs())
    }
}
