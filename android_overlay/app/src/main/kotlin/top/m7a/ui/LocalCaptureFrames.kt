package top.m7a.ui

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-process, RAM-only preview and observation metadata; never a grant to click or upload. */
internal data class LocalCaptureState(
    val running: Boolean = false,
    val status: String = "未启动本地画面观察",
    val preview: Bitmap? = null,
    val frames: Long = 0,
    val lastFrameElapsedMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val sessionId: Long = 0,
    val projectionVisible: Boolean? = null,
    val foregroundHint: ForegroundHint = ForegroundHint.UNKNOWN,
    val changeEvents: Long = 0,
    val lastFrameChanged: Boolean = false,
)

/** Old frame callbacks cannot write into the next user-authorized capture session. */
internal object LocalCaptureFrames {
    private val epochs = AtomicLong(0)
    private val mutable = MutableStateFlow(LocalCaptureState())
    val state = mutable.asStateFlow()

    fun starting(): Long {
        val id = epochs.incrementAndGet()
        mutable.update {
            LocalCaptureState(
                running = true,
                status = "已获得系统授权，正在等待屏幕画面",
                sessionId = id,
            )
        }
        return id
    }

    fun showFrame(
        sessionId: Long,
        bitmap: Bitmap,
        width: Int,
        height: Int,
        changed: Boolean,
        foreground: ForegroundHint,
    ) {
        mutable.update { old ->
            if (!old.running || old.sessionId != sessionId) old
            else old.copy(
                status = "本机已获取画面；尚未向 AI 发送",
                preview = bitmap,
                frames = old.frames + 1,
                lastFrameElapsedMs = SystemClock.elapsedRealtime(),
                width = width,
                height = height,
                foregroundHint = foreground,
                changeEvents = old.changeEvents + if (changed) 1 else 0,
                lastFrameChanged = changed,
            )
        }
    }

    fun updateVisibility(sessionId: Long, visible: Boolean) {
        mutable.update { old ->
            if (!old.running || old.sessionId != sessionId) old
            else old.copy(projectionVisible = visible)
        }
    }

    fun stop(sessionId: Long, reason: String) {
        // Retain final preview only in memory, not as trusted current game evidence.
        mutable.update { old ->
            if (old.sessionId != sessionId) old
            else old.copy(running = false, status = reason)
        }
    }

    fun notice(reason: String) {
        mutable.update { if (it.running) it else it.copy(status = reason) }
    }

    fun clearPreview() {
        mutable.update {
            it.copy(preview = null, frames = 0, lastFrameElapsedMs = 0,
                width = 0, height = 0, changeEvents = 0, lastFrameChanged = false)
        }
    }
}
