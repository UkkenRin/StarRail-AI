package top.m7a.ui

import android.content.Context
import java.util.UUID

/**
 * Small diagnostic breadcrumbs for capture lifecycle. Never stores pixel data,
 * game/account details, authorization Intents, credentials, or stack traces.
 * Its only purpose is to distinguish process restart from a normal stop on
 * devices that may reclaim our process while a game is foreground.
 */
internal object CaptureSessionHealth {
    private const val PREFS = "starrail_capture_session_health_v1"
    private const val STATE = "state"
    private const val OWNER = "owner"
    private const val FRAMES = "frames"
    private const val EVENT_TIME = "event_time"
    private const val LAST_REASON = "reason"
    private const val CAPTURING = "capturing"
    private const val FINISHED = "finished"
    private val processToken: String = UUID.randomUUID().toString()

    fun started(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(STATE, CAPTURING)
            .putString(OWNER, processToken)
            .putLong(FRAMES, 0L)
            .putLong(EVENT_TIME, System.currentTimeMillis())
            .remove(LAST_REASON)
            .apply()
    }

    fun frame(context: Context, count: Long) {
        if (count <= 0L || (count > 2L && count % 10L != 0L)) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(FRAMES, count)
            .putLong(EVENT_TIME, System.currentTimeMillis())
            .apply()
    }

    fun finished(context: Context, reason: String, count: Long) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(STATE, FINISHED)
            .putString(LAST_REASON, reason.take(100))
            .putLong(FRAMES, count)
            .putLong(EVENT_TIME, System.currentTimeMillis())
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = prefs.getString(STATE, "").orEmpty()
        val owner = prefs.getString(OWNER, "").orEmpty()
        val reason = prefs.getString(LAST_REASON, "").orEmpty()
        val frames = prefs.getLong(FRAMES, 0L)
        return explain(state, owner == processToken, reason, frames)
    }

    /** Pure function kept separate from Android for JVM regression tests. */
    internal fun explain(
        state: String, sameProcess: Boolean, reason: String, frames: Long,
    ): String = when {
        state == CAPTURING && !sameProcess ->
            "上次采集约 ${frames} 帧后未正常结束，App 进程曾重新创建；可能被系统回收或发生崩溃，不能仅凭这一条判断原因"
        state == CAPTURING ->
            "当前进程仍记录有采集会话（约 ${frames} 帧）；如已停止但没有回调，请检查系统共享状态"
        state == FINISHED ->
            "上次会话：${reason.ifBlank { "已结束" }}；共约 ${frames} 帧"
        else -> "没有上次会话记录"
    }
}
