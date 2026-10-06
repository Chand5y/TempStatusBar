package com.personal.tempstatusbar

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object AppLabelHelper {
    private val cache = mutableMapOf<String, String>()

    private val SYSTEM_DAEMONS = setOf(
        "surfaceflinger", "system_server", "audioserver", "cameraserver",
        "mediaserver", "netd", "hvdcp_opti", "init", "logd", "vold", "zygote", "zygote64"
    )

    fun getAppName(context: Context, rawName: String, pid: Int = -1): String {
        val cleanPkg = rawName.trim().removePrefix("• ").substringBefore(" — ").trim()
        if (cache.containsKey(cleanPkg)) return cache[cleanPkg]!!

        val pm = context.packageManager
        var resolvedName: String? = null
        val stripped = cleanPkg.replace("+", "")

        if (SYSTEM_DAEMONS.contains(stripped)) {
            val daemonName = when (stripped) {
                "surfaceflinger" -> "Display Compositor"
                "system_server" -> "Android System"
                "audioserver" -> "Audio Server"
                "cameraserver" -> "Camera Server"
                "mediaserver" -> "Media Server"
                "netd" -> "Network Core"
                "hvdcp_opti" -> "Fast Charge Controller"
                else -> stripped.replaceFirstChar { it.uppercase() }
            }
            cache[cleanPkg] = daemonName
            return daemonName
        }

        try {
            val appInfo = pm.getApplicationInfo(cleanPkg, 0)
            resolvedName = pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            try {
                val prefix = cleanPkg.replace("+", "")
                val packages = pm.getInstalledApplications(0)
                for (appInfo in packages) {
                    if (appInfo.packageName.startsWith(prefix) || appInfo.packageName.contains(prefix)) {
                        resolvedName = pm.getApplicationLabel(appInfo).toString()
                        break
                    }
                }
            } catch (ex: Exception) {}
        }

        val finalName = if (!resolvedName.isNullOrBlank() && resolvedName != cleanPkg) {
            resolvedName
        } else {
            val fallback = cleanPkg.substringAfterLast(".").replace("+", "").trim()
            if (fallback.isNotEmpty()) fallback.replaceFirstChar { it.uppercase() } else "Unknown Process"
        }

        cache[cleanPkg] = finalName
        return finalName
    }
}
