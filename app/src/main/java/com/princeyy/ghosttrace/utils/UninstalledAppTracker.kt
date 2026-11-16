package com.princeyy.ghosttrace.utils

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.princeyy.ghosttrace.model.AppRecord
import com.princeyy.ghosttrace.model.AppSource
import java.text.SimpleDateFormat
import java.util.*

object UninstalledAppTracker {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * 1. Query all UsageEvents (ACTIVITY_RESUMED + ACTIVITY_PAUSED)
     * 2. Get all currently installed packages via PackageManager
     * 3. Package in events but NOT installed = uninstalled app
     * 4. Calculate firstSeen, lastActive, fgTime, sessionCount
     */
    fun getUninstalledApps(context: Context, windowDays: Int = 10): List<AppRecord> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as UsageStatsManager
        val pm = context.packageManager

        val endTime   = System.currentTimeMillis()
        val startTime = endTime - windowDays.toLong() * 24L * 60L * 60L * 1000L

        // --- Step 1: Query all events ---
        val firstSeen     = mutableMapOf<String, Long>()
        val lastSeen      = mutableMapOf<String, Long>()
        val resumeStart   = mutableMapOf<String, Long>()
        val fgTotal       = mutableMapOf<String, Long>()
        val sessionCount  = mutableMapOf<String, Int>()
        val dailySessions = mutableMapOf<String, MutableMap<String, Int>>()

        val events = usm.queryEvents(startTime, endTime)
        val event  = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val ts  = event.timeStamp

            if (!firstSeen.containsKey(pkg)) firstSeen[pkg] = ts
            if ((lastSeen[pkg] ?: 0L) < ts) lastSeen[pkg] = ts

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    resumeStart[pkg] = ts
                    sessionCount[pkg] = (sessionCount[pkg] ?: 0) + 1
                    val day = dayFmt.format(Date(ts))
                    dailySessions.getOrPut(pkg) { mutableMapOf() }
                        .let { it[day] = (it[day] ?: 0) + 1 }
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    resumeStart.remove(pkg)?.let { start ->
                        fgTotal[pkg] = (fgTotal[pkg] ?: 0L) + (ts - start)
                    }
                }
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (!resumeStart.containsKey(pkg)) {
                        resumeStart[pkg] = ts
                        sessionCount[pkg] = (sessionCount[pkg] ?: 0) + 1
                        val day = dayFmt.format(Date(ts))
                        dailySessions.getOrPut(pkg) { mutableMapOf() }
                            .let { it[day] = (it[day] ?: 0) + 1 }
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    resumeStart.remove(pkg)?.let { start ->
                        fgTotal[pkg] = (fgTotal[pkg] ?: 0L) + (ts - start)
                    }
                }
            }
        }

        // --- Step 2: Get ALL installed packages ---
        val installedPkgs = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { it.packageName }
            .toSet()

        // --- Step 3: Filter uninstalled ---
        return firstSeen.keys
            .filter { it !in installedPkgs }
            .map { pkg ->
                AppRecord(
                    packageName   = pkg,
                    appLabel      = resolveLabel(context, pkg),
                    firstSeen     = firstSeen[pkg] ?: 0L,
                    lastSeen      = lastSeen[pkg] ?: 0L,
                    fgMillis      = fgTotal[pkg] ?: 0L,
                    sessions      = sessionCount[pkg] ?: 0,
                    isGame        = isLikelyGame(pkg),
                    isSystem      = isLikelySystem(pkg),
                    dailySessions = dailySessions[pkg] ?: emptyMap(),
                    source        = AppSource.GHOST
                )
            }
            .sortedByDescending { it.lastSeen }
    }

    private fun resolveLabel(context: Context, pkg: String): String {
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
        }
    }

    private fun isLikelyGame(pkg: String): Boolean {
        val keywords = listOf("game", "ffi", "garena", "pubg", "cod", "clash",
            "arena", "legends", "mobile", "play", "gaming", "freefir")
        return keywords.any { pkg.lowercase().contains(it) }
    }

    private fun isLikelySystem(pkg: String): Boolean {
        val prefixes = listOf("com.android.", "com.google.", "moe.shizuku",
            "com.miui.", "com.oppo.", "com.realme.", "com.oneplus.",
            "android.", "com.qualcomm.", "com.mediatek.",
            "com.coloros.", "com.heytap.")
        return prefixes.any { pkg.startsWith(it) }
    }
}

    // DEBUG — call this to see exactly what event types Android logged
    fun dumpRawEvents(context: Context, windowDays: Int = 10): String {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as UsageStatsManager
        val endTime   = System.currentTimeMillis()
        val startTime = endTime - windowDays.toLong() * 24L * 60L * 60L * 1000L

        val events = usm.queryEvents(startTime, endTime)
        val event  = UsageEvents.Event()
        val sb     = StringBuilder()
        val seen   = mutableSetOf<Int>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            seen.add(event.eventType)
            sb.appendLine("pkg=${event.packageName} type=${event.eventType}")
        }

        sb.appendLine("\n--- EVENT TYPES SEEN: $seen ---")
        sb.appendLine("1=MOVE_TO_FG, 2=MOVE_TO_BG, 19=ACTIVITY_RESUMED, 23=ACTIVITY_PAUSED")
        return sb.toString()
    }
