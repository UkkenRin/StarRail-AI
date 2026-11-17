package top.m7a.ui

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** An in-process, RAM-only preview. No file writes, model requests or networking. */
internal data class LocalCaptureState(
    val running: Boolean = false,
    val status: String = "未启动本地画面观察",
    val preview: Bitmap? = null,
    val frames: Long = 0,
    val lastFrameElapsedMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
)

internal object LocalCaptureFrames {
    private val mutable = MutableStateFlow(LocalCaptureState())
    val state = mutable.asStateFlow()

    fun starting() {
        mutable.update {
            it.copy(running = true, status = "已获得系统授权，正在等待屏幕画面", frames = 0,
                preview = null, lastFrameElapsedMs = 0, width = 0, height = 0)
        }
    }

    fun showFrame(bitmap: Bitmap, width: Int, height: Int) {
        mutable.update { old ->
            if (!old.running) old
            else old.copy(
                status = "正在采集本机画面；尚未接入 AI",
                preview = bitmap,
                frames = old.frames + 1,
                lastFrameElapsedMs = SystemClock.elapsedRealtime(),
                width = width,
                height = height,
            )
        }
    }

    fun stop(reason: String) {
        // Keep the final frame in memory until user clears it or starts a new session.
        // This lets users stop from the notification while the game is foregrounded.
        mutable.update { it.copy(running = false, status = reason) }
    }

    fun notice(reason: String) {
        mutable.update { if (it.running) it else it.copy(status = reason) }
    }

    fun clearPreview() {
        mutable.update { it.copy(preview = null, frames = 0, lastFrameElapsedMs = 0,
            width = 0, height = 0) }
    }
}
