package com.personal.tempstatusbar

import android.content.Context
import android.content.pm.PackageManager

object AppLabelHelper {
    private val cache = mutableMapOf<String, String>()

    fun getAppName(context: Context, rawName: String, pid: Int = -1): String {
        val cleanPkg = rawName.trim().removePrefix("• ").substringBefore(" — ").trim()
        if (cache.containsKey(cleanPkg)) return cache[cleanPkg]!!

        var label = ""
        val pm = context.packageManager
        
        try {
            // Attempt 1: Direct Exact Package Match
            val appInfo = pm.getApplicationInfo(cleanPkg, PackageManager.GET_META_DATA)
            label = pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            // Attempt 2: If the kernel truncated the package name (e.g., com.reddit.fron+), 
            // scan installed apps for a matching prefix.
            try {
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val cleanPrefix = cleanPkg.replace("+", "")
                for (appInfo in packages) {
                    if (appInfo.packageName.contains(cleanPrefix)) {
                        label = pm.getApplicationLabel(appInfo).toString()
                        break
                    }
                }
            } catch (ex: Exception) {}
        }

        // Attempt 3: Final Fallbacks for low-level system daemon binaries
        if (label.isBlank() || label == cleanPkg) {
            label = when (cleanPkg.replace("+", "")) {
                "surfaceflinger" -> "Display Compositor"
                "system_server" -> "Android System"
                "audioserver" -> "Audio Server"
                "cameraserver" -> "Camera Server"
                "mediaserver" -> "Media Server"
                "netd" -> "Network Daemon"
                "hvdcp_opti" -> "Fast Charge Controller"
                "com.personal.tempstatusbar" -> "Temp Monitor"
                else -> {
                    val nameCandidate = cleanPkg.substringAfterLast(".").replace("+", "").trim()
                    if (nameCandidate.isNotEmpty()) nameCandidate.replaceFirstChar { it.uppercase() } else "System Process"
                }
            }
        }

        cache[cleanPkg] = label
        return label
    }
}
