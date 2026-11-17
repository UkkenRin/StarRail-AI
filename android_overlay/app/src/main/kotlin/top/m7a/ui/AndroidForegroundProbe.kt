package top.m7a.ui

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process

/**
 * Optional READ-ONLY usage-access hint. User must grant "Usage access" in Settings.
 * It is NOT a trusted foreground window handle and cannot permit game inputs.
 */
internal class AndroidForegroundProbe(context: Context) {
    private val app = context.applicationContext

    fun hasUsageAccess(): Boolean = runCatching {
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            app.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun sample(nowMs: Long = System.currentTimeMillis()): ForegroundHint {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!power.isInteractive || keyguard.isKeyguardLocked) return ForegroundHint.SCREEN_LOCKED
        if (!hasUsageAccess()) return ForegroundHint.PERMISSION_REQUIRED

        return runCatching {
            val usage = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            // Only recent events, queried at most once per captured preview frame.
            val events = usage.queryEvents(nowMs - 60_000L, nowMs)
                ?: return@runCatching ForegroundHint.UNKNOWN
            val transitions = ArrayList<ForegroundTransition>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED ->
                        transitions.add(ForegroundTransition(event.packageName.orEmpty(), true, event.timeStamp))
                    UsageEvents.Event.ACTIVITY_PAUSED ->
                        transitions.add(ForegroundTransition(event.packageName.orEmpty(), false, event.timeStamp))
                }
            }
            ForegroundHintRules.evaluate(transitions, nowMs)
        }.getOrDefault(ForegroundHint.UNKNOWN)
    }
}
