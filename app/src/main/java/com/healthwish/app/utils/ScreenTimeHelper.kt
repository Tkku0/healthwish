package com.healthwish.app.utils

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.util.Calendar

object ScreenTimeHelper {

    fun hasUsagePermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageSettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
    }

    // Calculates screen-on time in milliseconds for a specific day offset (0 = today, -1 = yesterday)
    fun getScreenOnTimeMillis(ctx: Context, dayOffset: Int = 0): Long {
        if (!hasUsagePermission(ctx)) return 0L
        val usageManager = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0L

        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startTime = cal.timeInMillis

        val endTime = if (dayOffset == 0) {
            System.currentTimeMillis()
        } else {
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            cal.timeInMillis
        }

        var totalScreenOn = 0L
        var lastScreenOn = 0L

        try {
            val events = usageManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // SCREEN_INTERACTIVE = 15, SCREEN_NON_INTERACTIVE = 16
                when (event.eventType) {
                    15 -> { // Screen turned ON
                        lastScreenOn = event.timeStamp
                    }
                    16 -> { // Screen turned OFF
                        if (lastScreenOn > 0) {
                            totalScreenOn += (event.timeStamp - lastScreenOn)
                            lastScreenOn = 0L
                        }
                    }
                }
            }

            // If screen is currently on right now
            if (lastScreenOn > 0 && dayOffset == 0) {
                totalScreenOn += (endTime - lastScreenOn)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return totalScreenOn
    }

    fun formatMillis(millis: Long): String {
        if (millis <= 0) return "0m"
        val totalMins = millis / (1000 * 60)
        val h = totalMins / 60
        val m = totalMins % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
