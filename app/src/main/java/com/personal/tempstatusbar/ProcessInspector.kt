package com.personal.tempstatusbar

import android.app.usage.UsageStatsManager
import android.content.Context

object ProcessInspector {

    fun captureNonRootActiveApps(context: Context): String {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return "Usage Access service unavailable"

        val endTime = System.currentTimeMillis()
        val startTime = endTime - (1000 * 60 * 10) // 10-minute window
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, endTime)

        if (stats.isNullOrEmpty()) {
            return "Active app tracking requires 'Usage Access' permission"
        }

        val sorted = stats.filter { it.lastTimeUsed > 0 }.sortedByDescending { it.lastTimeUsed }.take(4)
        val pm = context.packageManager
        val sb = StringBuilder()

        for (item in sorted) {
            val name = try {
                pm.getApplicationLabel(pm.getApplicationInfo(item.packageName, 0)).toString()
            } catch (e: Exception) {
                item.packageName
            }
            sb.append("• ").append(name).append("\n")
        }
        return sb.toString().trim()
    }
}
