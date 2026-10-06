package com.personal.tempstatusbar

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class AppInfoResult(
    val name: String,
    val isSystem: Boolean,
    val formattedLabel: String
)

object AppLabelHelper {
    private val cache = mutableMapOf<String, AppInfoResult>()

    private val SYSTEM_DAEMONS = setOf(
        "surfaceflinger", "system_server", "audioserver", "cameraserver",
        "mediaserver", "netd", "hvdcp_opti", "init", "logd", "vold", "zygote", "zygote64"
    )

    fun getAppDetails(context: Context, rawName: String, pid: Int = -1): AppInfoResult {
        val cleanPkg = rawName.trim().removePrefix("• ").substringBefore(" — ").trim()
        if (cache.containsKey(cleanPkg)) return cache[cleanPkg]!!

        val pm = context.packageManager
        var resolvedName: String? = null
        var isSystem = false

        val stripped = cleanPkg.replace("+", "")

        // 1. Check for low-level Android/Linux kernel and system daemons
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
            val res = AppInfoResult(daemonName, true, "⚙️ $daemonName [System]")
            cache[cleanPkg] = res
            return res
        }

        // 2. Direct exact package match via PackageManager
        try {
            val appInfo = pm.getApplicationInfo(cleanPkg, 0)
            resolvedName = pm.getApplicationLabel(appInfo).toString()
            isSystem = (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (e: Exception) {
            // 3. Handle kernel-truncated names (e.g. packages ending in '+' from top command)
            try {
                val prefix = cleanPkg.replace("+", "")
                val packages = pm.getInstalledApplications(0)
                for (appInfo in packages) {
                    if (appInfo.packageName.startsWith(prefix) || appInfo.packageName.contains(prefix)) {
                        resolvedName = pm.getApplicationLabel(appInfo).toString()
                        isSystem = (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
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

        // Secondary check for Xiaomi / AOSP package namespaces
        if (!isSystem && (cleanPkg.startsWith("android.") || cleanPkg.startsWith("com.android.") || cleanPkg.startsWith("com.miui.") || cleanPkg.startsWith("com.xiaomi."))) {
            isSystem = true
        }

        val tag = if (isSystem) "⚙️ $finalName [System]" else "👤 $finalName"
        val result = AppInfoResult(finalName, isSystem, tag)
        cache[cleanPkg] = result
        return result
    }

    // Direct helper method used across MainActivity and background monitors
    fun getAppName(context: Context, rawName: String, pid: Int = -1): String {
        return getAppDetails(context, rawName, pid).formattedLabel
    }
}
