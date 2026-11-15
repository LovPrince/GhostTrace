package com.princeyy.ghosttrace.model

enum class AppSource {
    GHOST,       // uninstalled, was seen in UsageEvents log
    BACKGROUND,  // currently running as a service, not installed in foreground
    PROC_GHOST   // found in /proc filesystem, not currently installed
}

data class AppRecord(
    val packageName: String,
    val appLabel: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val fgMillis: Long,
    val sessions: Int,
    val isGame: Boolean = false,
    val isSystem: Boolean = false,
    val dailySessions: Map<String, Int> = emptyMap(),
    val source: AppSource = AppSource.GHOST,       // how it was detected
    val isRunningNow: Boolean = false,             // currently alive in memory
    val serviceCount: Int = 0                      // number of active services
)
