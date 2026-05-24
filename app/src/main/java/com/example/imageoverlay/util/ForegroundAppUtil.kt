package com.example.imageoverlay.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

object ForegroundAppUtil {

    /** 最近切到前台的应用包名（排除本应用） */
    fun getRecentForegroundPackage(context: Context, lookbackMs: Long = 5000L): String? {
        return try {
            val usageStatsManager =
                context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - lookbackMs
            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    val pkg = event.packageName
                    if (pkg != context.packageName) {
                        last = pkg
                    }
                }
            }
            last
        } catch (_: Exception) {
            null
        }
    }
}
