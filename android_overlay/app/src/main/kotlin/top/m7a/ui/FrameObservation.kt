package top.m7a.ui

import kotlin.math.abs

/**
 * Observational hints only. Android usage events do not prove that a screen frame
 * belongs to the game, and MUST NOT authorize a model input action by themselves.
 */
internal enum class ForegroundHint {
    PERMISSION_REQUIRED,
    SCREEN_LOCKED,
    GAME_CANDIDATE,
    OTHER_APP,
    UNKNOWN,
}

internal data class ForegroundTransition(
    val packageName: String,
    val resumed: Boolean,
    val timeMs: Long,
)

/** Stateless conservative reducer for independently sampled Android usage events. */
internal object ForegroundHintRules {
    const val GAME_PACKAGE = "com.miHoYo.hkrpg"
    const val MAX_EVENT_AGE_MS = 30_000L

    fun evaluate(
        transitions: List<ForegroundTransition>,
        nowWallMs: Long,
    ): ForegroundHint {
        if (transitions.isEmpty()) return ForegroundHint.UNKNOWN
        val ordered = transitions.sortedBy { it.timeMs }
        var current: String? = null
        var lastResumedAt = 0L
        for (event in ordered) {
            if (event.timeMs > nowWallMs || event.timeMs < nowWallMs - 60_000L) continue
            if (event.resumed) {
                current = event.packageName
                lastResumedAt = event.timeMs
            } else if (event.packageName == current) {
                current = null
            }
        }
        if (current == null || lastResumedAt == 0L) return ForegroundHint.UNKNOWN
        val age = nowWallMs - lastResumedAt
        if (age < 0 || age > MAX_EVENT_AGE_MS) return ForegroundHint.UNKNOWN
        return if (current == GAME_PACKAGE) ForegroundHint.GAME_CANDIDATE
            else ForegroundHint.OTHER_APP
    }
}

/**
 * This is a diagnostic policy for local observation only.
 * CANDIDATE is NOT a verified focus or authorization to upload/click.
 */
internal enum class FrameReadiness {
    NOT_CAPTURING,
    NO_FRAME,
    STALE,
    CAPTURE_HIDDEN,
    GAME_NOT_CONFIRMED,
    LOCAL_CANDIDATE,
}

internal object FrameReadinessRules {
    const val MAX_FRAME_AGE_MS = 2_000L

    fun evaluate(
        running: Boolean,
        sequence: Long,
        frameElapsedMs: Long,
        nowElapsedMs: Long,
        visible: Boolean?,
        foreground: ForegroundHint,
    ): FrameReadiness {
        if (!running) return FrameReadiness.NOT_CAPTURING
        if (sequence <= 0 || frameElapsedMs <= 0) return FrameReadiness.NO_FRAME
        val age = nowElapsedMs - frameElapsedMs
        if (age < 0 || age > MAX_FRAME_AGE_MS) return FrameReadiness.STALE
        if (visible == false) return FrameReadiness.CAPTURE_HIDDEN
        if (foreground != ForegroundHint.GAME_CANDIDATE) return FrameReadiness.GAME_NOT_CONFIRMED
        return FrameReadiness.LOCAL_CANDIDATE
    }
}

/** 8x8 local thumbnail change detector: only a hint, NOT scene classification. */
internal class FrameChangeDetector {
    private var previous: IntArray? = null
    private var previousWidth = 0
    private var previousHeight = 0

    fun changed(samples: IntArray, width: Int, height: Int): Boolean {
        require(samples.size == 64 && width > 0 && height > 0)
        val before = previous
        val change = before == null || width != previousWidth || height != previousHeight ||
            samples.indices.sumOf { abs(samples[it] - before[it]) } / samples.size >= 16
        previous = samples.copyOf()
        previousWidth = width
        previousHeight = height
        return change
    }

    fun reset() {
        previous = null
        previousWidth = 0
        previousHeight = 0
    }
}
