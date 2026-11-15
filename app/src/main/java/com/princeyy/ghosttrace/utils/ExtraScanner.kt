package com.princeyy.ghosttrace.utils

import android.accounts.AccountManager
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import java.io.File

object ExtraScanner {

    // Collect all extra package traces without root/Shizuku
    fun scan(context: Context): Set<String> {
        val pm = context.packageManager
        val found = mutableSetOf<String>()

        found += scanRecentTasks(context, pm)
        found += scanExternalStorageTraces(pm)
        found += scanAccountTraces(context, pm)
        found += scanCacheTraces(context, pm)
        found += scanAppOpsReadable(pm)

        return found.filter { it.contains(".") && it.length > 4 }.toSet()
    }

    // 1. ActivityManager recent tasks — shows recently used apps incl. uninstalled
    private fun scanRecentTasks(context: Context, pm: PackageManager): Set<String> {
        val found = mutableSetOf<String>()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            @Suppress("DEPRECATION")
            val tasks = am.getRecentTasks(200, ActivityManager.RECENT_WITH_EXCLUDED)
            tasks?.forEach { task ->
                val pkg = task.baseIntent?.component?.packageName
                    ?: task.baseIntent?.`package`
                    ?: return@forEach
                if (!isInstalled(pkg, pm)) found.add(pkg)
            }
        } catch (_: Exception) {}
        return found
    }

    // 2. External storage — apps often leave folders in /sdcard/Android/data/
    private fun scanExternalStorageTraces(pm: PackageManager): Set<String> {
        val found = mutableSetOf<String>()
        try {
            val androidDataDir = File(
                Environment.getExternalStorageDirectory(),
                "Android/data"
            )
            androidDataDir.listFiles()?.forEach { dir ->
                val pkg = dir.name
                if (pkg.contains(".") && !isInstalled(pkg, pm)) {
                    found.add(pkg)
                }
            }
        } catch (_: Exception) {}

        // Also check Android/obb
        try {
            val obbDir = File(
                Environment.getExternalStorageDirectory(),
                "Android/obb"
            )
            obbDir.listFiles()?.forEach { dir ->
                val pkg = dir.name
                if (pkg.contains(".") && !isInstalled(pkg, pm)) {
                    found.add(pkg)
                }
            }
        } catch (_: Exception) {}

        return found
    }

    // 3. AccountManager — apps that registered accounts survive uninstall traces
    private fun scanAccountTraces(context: Context, pm: PackageManager): Set<String> {
        val found = mutableSetOf<String>()
        try {
            val am = AccountManager.get(context)
            val authenticators = am.authenticatorTypes
            authenticators?.forEach { auth ->
                val pkg = auth.packageName
                if (!isInstalled(pkg, pm)) found.add(pkg)
            }
        } catch (_: Exception) {}
        return found
    }

    // 4. App-specific cache dirs that survived uninstall (some OEMs don't clean)
    private fun scanCacheTraces(context: Context, pm: PackageManager): Set<String> {
        val found = mutableSetOf<String>()
        try {
            // Check /sdcard/Android/media/ for leftover media folders
            val mediaDir = File(
                Environment.getExternalStorageDirectory(),
                "Android/media"
            )
            mediaDir.listFiles()?.forEach { dir ->
                val pkg = dir.name
                if (pkg.contains(".") && !isInstalled(pkg, pm)) {
                    found.add(pkg)
                }
            }
        } catch (_: Exception) {}
        return found
    }

    // 5. /proc/net/tcp + /proc/net/udp — packages with open sockets (running now)
    private fun scanAppOpsReadable(pm: PackageManager): Set<String> {
        val found = mutableSetOf<String>()
        try {
            // Read /proc/net/unix for socket names that contain package names
            val unixSockets = File("/proc/net/unix").readLines()
            unixSockets.forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                val socketName = parts.lastOrNull() ?: return@forEach
                // Socket paths sometimes contain package names
                if (socketName.contains(".") &&
                    socketName.startsWith("/") &&
                    !socketName.startsWith("/dev") &&
                    !socketName.startsWith("/sys")
                ) {
                    val candidate = socketName
                        .split("/")
                        .firstOrNull { it.contains(".") && it.count { c -> c == '.' } >= 2 }
                    if (candidate != null && !isInstalled(candidate, pm)) {
                        found.add(candidate)
                    }
                }
            }
        } catch (_: Exception) {}
        return found
    }

    private fun isInstalled(pkg: String, pm: PackageManager): Boolean {
        return try { pm.getPackageInfo(pkg, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false }
    }
}
