package com.personal.tempstatusbar

import android.content.Context
import java.io.File

object AppLabelHelper {

    private val POPULAR_APPS = mapOf(
        // Games & High Load
        "com.pubg.imobile" to "BGMI",
        "com.tencent.ig" to "PUBG Mobile",
        "com.dts.freefireth" to "Free Fire",
        "com.dts.freefiremax" to "Free Fire MAX",
        "com.miHoYo.GenshinImpact" to "Genshin Impact",
        "com.activision.callofduty.shooter" to "Call of Duty: Mobile",
        "com.supercell.clashofclans" to "Clash of Clans",
        "com.supercell.clashroyale" to "Clash Royale",
        "com.supercell.brawlstars" to "Brawl Stars",
        "com.roblox.client" to "Roblox",
        "com.mojang.minecraftpe" to "Minecraft",
        "com.ea.gp.fifamobile" to "EA SPORTS FC Mobile",
        "com.riotgames.league.wildrift" to "League of Legends: Wild Rift",
        "com.krafton.battleground" to "Battlegrounds",

        // Social & Messaging
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.facebook.orca" to "Messenger",
        "com.facebook.lite" to "Facebook Lite",
        "org.telegram.messenger" to "Telegram",
        "com.snapchat.android" to "Snapchat",
        "com.twitter.android" to "X (Twitter)",
        "com.reddit.frontpage" to "Reddit",
        "com.discord" to "Discord",
        "com.linkedin.android" to "LinkedIn",
        "com.pinterest" to "Pinterest",

        // Google Ecosystem
        "com.google.android.youtube" to "YouTube",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.android.chrome" to "Chrome",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.photos" to "Google Photos",
        "com.google.android.apps.maps" to "Google Maps",
        "com.google.android.googlequicksearchbox" to "Google Search",
        "com.google.android.apps.docs" to "Google Drive",
        "com.google.android.dialer" to "Google Phone",
        "com.google.android.apps.messaging" to "Messages",
        "com.google.android.play.games" to "Google Play Games",
        "com.android.vending" to "Google Play Store",

        // Media & Streaming
        "com.spotify.music" to "Spotify",
        "com.netflix.mediaclient" to "Netflix",
        "in.startv.hotstar" to "Disney+ Hotstar",
        "com.amazon.avod.thirdpartyclient" to "Prime Video",
        "com.jio.media.ondemand" to "JioCinema",
        "com.zee5.angl" to "ZEE5",
        "org.videolan.vlc" to "VLC Media Player",
        "com.mxtech.videoplayer.ad" to "MX Player",

        // Commerce & Finance
        "com.amazon.mShop.android.shopping" to "Amazon",
        "com.flipkart.android" to "Flipkart",
        "net.one97.paytm" to "Paytm",
        "com.phonepe.app" to "PhonePe",
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "in.org.npci.upiapp" to "BHIM UPI",
        "com.dream11.android" to "Dream11",

        // Xiaomi HyperOS System & Daemons
        "com.miui.home" to "System Launcher",
        "com.miui.securitycenter" to "Security Core",
        "com.android.settings" to "Settings",
        "com.android.camera" to "Camera",
        "com.miui.gallery" to "Gallery",
        "com.miui.player" to "Mi Music",
        "com.xiaomi.joyose" to "Joyose Thermal Core",
        "com.miui.powerkeeper" to "Power Keeper",
        "system_server" to "Android System",
        "surfaceflinger" to "Display Compositor",
        "hvdcp_opti" to "Fast Charge Controller",
        "com.personal.tempstatusbar" to "Temp Monitor"
    )

    fun getAppName(context: Context, rawName: String, pid: Int = -1): String {
        var cleanPkg = rawName.trim()

        // 1. Recover un-truncated name from Linux kernel if process ends with '+'
        if (pid > 0 && (cleanPkg.endsWith("+") || !cleanPkg.contains("."))) {
            try {
                val cmdline = File("/proc/$pid/cmdline").readText().replace("\u0000", "").trim()
                if (cmdline.isNotEmpty()) cleanPkg = cmdline
            } catch (e: Exception) {}
        }

        // Strip prefixes/suffixes
        cleanPkg = cleanPkg.removePrefix("• ").substringBefore(" — ").trim()

        // 2. Direct dictionary match
        POPULAR_APPS[cleanPkg]?.let { return it }

        // 3. Fallback dictionary match on partial matches (e.g. reddit, whatsapp)
        for ((pkg, friendlyName) in POPULAR_APPS) {
            if (cleanPkg.startsWith(pkg.take(14)) || cleanPkg.contains(pkg.substringAfterLast("."))) {
                return friendlyName
            }
        }

        // 4. Query Android's system PackageManager
        if (cleanPkg.contains(".")) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(cleanPkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                if (label.isNotEmpty() && !label.contains(".")) return label
            } catch (e: Exception) {}
        }

        // 5. Clean text presentation as final fallback
        val nameCandidate = cleanPkg.substringAfterLast(".").replace("+", "").trim()
        return if (nameCandidate.isNotEmpty()) {
            nameCandidate.replaceFirstChar { it.uppercase() }
        } else {
            "System Process"
        }
    }
}
