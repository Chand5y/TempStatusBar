package com.personal.tempstatusbar

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.InputStreamReader

object ProcessInspector {

    fun captureProcesses(context: Context): Pair<Boolean, String> {
        // 1. Attempt Root Snapshot First
        if (isDeviceRooted()) {
            val rootData = captureRootCpuTop()
            if (rootData.isNotEmpty()) {
                return Pair(true, rootData)
            }
        }

        // 2. Non-Root Fallback (UsageStats)
        return Pair(false, captureNonRootActiveApps(context))
    }

    private fun isDeviceRooted(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val line = reader.readLine()
            p.destroy()
            line != null && line.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    private fun captureRootCpuTop(): String {
        return try {
            // One-shot instant snapshot of top 5 CPU processes
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "top -b -n 1 -m 5"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var line: String?
            var count = 0
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.isNotEmpty() && !l.startsWith("Tasks:") && !l.startsWith("Mem:")) {
                    sb.append(l).append("\n")
                    count++
                    if (count >= 6) break
                }
            }
            p.destroy()
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun captureNonRootActiveApps(context: Context): String {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return "Usage access service not available"

        val endTime = System.currentTimeMillis()
        val startTime = endTime - (1000 * 60 * 15) // Past 15 minutes
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)

        if (stats.isNullOrEmpty()) {
            return "Active app tracking requires 'Usage Access' permission (Tap Settings)."
        }

        val sorted = stats.filter { it.lastTimeUsed > 0 }.sortedByDescending { it.lastTimeUsed }.take(4)
        val pm = context.packageManager
        val sb = StringBuilder()

        for (item in sorted) {
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(item.packageName, 0)).toString()
            } catch (e: Exception) {
                item.packageName
            }
            sb.append("• ").append(appName).append("\n")
        }
        return sb.toString().trim()
    }
}
