package com.princeyy.ghosttrace.utils

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.princeyy.ghosttrace.model.AppRecord
import com.princeyy.ghosttrace.model.AppSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object UsageScanner {

    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // -------------------------------------------------------------------------
    // Public entry point — runs all three detection passes and merges results
    // -------------------------------------------------------------------------
    fun scan(context: Context, windowDays: Int = 30): ScanResult {
        val pm = context.packageManager

        val ghosts      = scanUsageGhosts(context, windowDays, pm)
        val background  = scanBackgroundServices(context, pm)
        val procGhosts  = scanProcFilesystem(pm)

        // Merge: a package already in ghosts shouldn't appear as procGhost too
        val ghostPkgs = ghosts.map { it.packageName }.toSet()
        val bgPkgs    = background.map { it.packageName }.toSet()

        val uniqueProcGhosts = procGhosts.filter { it.packageName !in ghostPkgs && it.packageName !in bgPkgs }
        val uniqueBg         = background.filter { it.packageName !in ghostPkgs }

        return ScanResult(
            ghosts     = ghosts,
            background = uniqueBg,
            procGhosts = uniqueProcGhosts
        )
    }

    // -------------------------------------------------------------------------
    // PASS 1 — UsageEvents ghosts (uninstalled apps seen in the usage log)
    // -------------------------------------------------------------------------
    private fun scanUsageGhosts(
        context: Context,
        windowDays: Int,
        pm: PackageManager
    ): List<AppRecord> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val endTime   = System.currentTimeMillis()
        val startTime = endTime - windowDays.toLong() * 24L * 60 * 60 * 1000

        val firstSeen     = mutableMapOf<String, Long>()
        val lastSeen      = mutableMapOf<String, Long>()
        val fgStart       = mutableMapOf<String, Long>()
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
            lastSeen[pkg] = ts

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    fgStart[pkg] = ts
                    sessionCount[pkg] = (sessionCount[pkg] ?: 0) + 1
                    val day = dayFmt.format(Date(ts))
                    dailySessions.getOrPut(pkg) { mutableMapOf() }
                        .let { it[day] = (it[day] ?: 0) + 1 }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    fgStart.remove(pkg)?.let { start ->
                        fgTotal[pkg] = (fgTotal[pkg] ?: 0L) + (ts - start)
                    }
                }
            }
        }

        // Filter: package seen in log but NOT currently installed
        return firstSeen.keys
            .filter { pkg -> !isInstalled(pkg, pm) }
            .map { pkg ->
                AppRecord(
                    packageName  = pkg,
                    appLabel     = resolveLabel(context, pkg),
                    firstSeen    = firstSeen[pkg] ?: 0L,
                    lastSeen     = lastSeen[pkg] ?: 0L,
                    fgMillis     = fgTotal[pkg] ?: 0L,
                    sessions     = sessionCount[pkg] ?: 0,
                    isGame       = isLikelyGame(pkg),
                    isSystem     = isLikelySystem(pkg),
                    dailySessions = dailySessions[pkg] ?: emptyMap(),
                    source       = AppSource.GHOST
                )
            }
            .sortedByDescending { it.lastSeen }
    }

    // -------------------------------------------------------------------------
    // PASS 2 — ActivityManager background service scan
    // Finds packages currently running services that are NOT installed
    // (e.g. leftover service processes after a force-delete, or sideloaded
    //  tools that run as a service with no launcher icon)
    // -------------------------------------------------------------------------
    private fun scanBackgroundServices(
        context: Context,
        pm: PackageManager
    ): List<AppRecord> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        // getRunningServices is deprecated in API 26+ but still works for our
        // own process detection; for installed apps it may be restricted.
        @Suppress("DEPRECATION")
        val running = try {
            am.getRunningServices(Int.MAX_VALUE) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // Group by package name, count services
        val pkgServiceCount = mutableMapOf<String, Int>()
        running.forEach { svc ->
            val pkg = svc.service.packageName
            pkgServiceCount[pkg] = (pkgServiceCount[pkg] ?: 0) + 1
        }

        // Also check running app processes
        @Suppress("DEPRECATION")
        val processes = try {
            am.runningAppProcesses ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val runningPkgs = mutableSetOf<String>()
        processes.forEach { proc ->
            proc.pkgList?.forEach { runningPkgs.add(it) }
        }

        // Find packages that are running NOW but not installed
        val allRunning = (pkgServiceCount.keys + runningPkgs).toSet()
        return allRunning
            .filter { pkg -> !isInstalled(pkg, pm) }
            .filter { pkg -> pkg.contains(".") } // skip bare process names
            .map { pkg ->
                AppRecord(
                    packageName  = pkg,
                    appLabel     = resolveLabel(context, pkg),
                    firstSeen    = System.currentTimeMillis(),
                    lastSeen     = System.currentTimeMillis(),
                    fgMillis     = 0L,
                    sessions     = 0,
                    isGame       = isLikelyGame(pkg),
                    isSystem     = isLikelySystem(pkg),
                    source       = AppSource.BACKGROUND,
                    isRunningNow = true,
                    serviceCount = pkgServiceCount[pkg] ?: 0
                )
            }
    }

    // -------------------------------------------------------------------------
    // PASS 3 — /proc filesystem scan
    // Reads /proc/<pid>/cmdline for every running PID. This catches processes
    // that don't appear in ActivityManager (native daemons, Zygote children,
    // root services). Works without root on most Android builds.
    // -------------------------------------------------------------------------
    private fun scanProcFilesystem(pm: PackageManager): List<AppRecord> {
        val found = mutableSetOf<String>()

        try {
            val procDir = File("/proc")
            if (!procDir.exists() || !procDir.isDirectory) return emptyList()

            procDir.listFiles()
                ?.filter { it.name.all { c -> c.isDigit() } } // only PID dirs
                ?.forEach { pidDir ->
                    try {
                        val cmdline = File(pidDir, "cmdline").readText()
                            .trimEnd('\u0000') // null-terminated
                            .split("\u0000").first() // first arg only
                            .trim()

                        // A valid package name: has dots, no slashes, looks like com.x.y
                        if (cmdline.contains(".") &&
                            !cmdline.contains("/") &&
                            !cmdline.startsWith("-") &&
                            cmdline.length > 4
                        ) {
                            // Strip :service or :remote suffixes (e.g. com.pkg:remote)
                            val pkg = cmdline.substringBefore(":")
                            if (pkg.contains(".")) found.add(pkg)
                        }
                    } catch (_: Exception) { /* unreadable PID, skip */ }
                }
        } catch (_: Exception) { /* /proc not accessible */ }

        // Filter to uninstalled only
        return found
            .filter { pkg -> !isInstalled(pkg, pm) }
            .map { pkg ->
                AppRecord(
                    packageName  = pkg,
                    appLabel     = pkg.split(".").lastOrNull()
                                        ?.replaceFirstChar { it.uppercase() } ?: pkg,
                    firstSeen    = System.currentTimeMillis(),
                    lastSeen     = System.currentTimeMillis(),
                    fgMillis     = 0L,
                    sessions     = 0,
                    isGame       = isLikelyGame(pkg),
                    isSystem     = isLikelySystem(pkg),
                    source       = AppSource.PROC_GHOST,
                    isRunningNow = true
                )
            }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private fun isInstalled(pkg: String, pm: PackageManager): Boolean {
        return try {
            pm.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun resolveLabel(context: Context, pkg: String): String {
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: pkg
        }
    }

    private fun isLikelyGame(pkg: String): Boolean {
        val keywords = listOf("game", "ffi", "garena", "pubg", "cod", "clash",
            "arena", "legends", "mobile", "play", "gaming")
        return keywords.any { pkg.lowercase().contains(it) }
    }

    private fun isLikelySystem(pkg: String): Boolean {
        val prefixes = listOf("com.android.", "com.google.", "moe.shizuku",
            "com.miui.", "com.oppo.", "com.realme.", "com.oneplus.",
            "android.", "com.qualcomm.", "com.mediatek.")
        return prefixes.any { pkg.startsWith(it) }
    }
}

// Result container holding all three categories
data class ScanResult(
    val ghosts:     List<AppRecord>,  // uninstalled, seen in usage log
    val background: List<AppRecord>,  // running now, not installed
    val procGhosts: List<AppRecord>   // found in /proc, not installed
) {
    val all: List<AppRecord> get() = ghosts + background + procGhosts
    val totalCount: Int get() = all.size
}
