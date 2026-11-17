package top.m7a.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameObservationTest {
    private val now = 100_000L

    @Test fun latestRecentGameResumeIsOnlyCandidate() {
        val game = ForegroundTransition("com.miHoYo.hkrpg", true, now - 1_000)
        assertEquals(ForegroundHint.GAME_CANDIDATE, ForegroundHintRules.evaluate(listOf(game), now))
        assertEquals(ForegroundHint.UNKNOWN, ForegroundHintRules.evaluate(emptyList(), now))
        assertEquals(ForegroundHint.UNKNOWN,
            ForegroundHintRules.evaluate(listOf(game.copy(timeMs = now - 50_000)), now))
    }

    @Test fun switchingAppsOrPausingRemovesGameCandidate() {
        val game = ForegroundTransition("com.miHoYo.hkrpg", true, now - 2_000)
        val browser = ForegroundTransition("com.android.chrome", true, now - 500)
        assertEquals(ForegroundHint.OTHER_APP,
            ForegroundHintRules.evaluate(listOf(game, browser), now))
        assertEquals(ForegroundHint.UNKNOWN,
            ForegroundHintRules.evaluate(listOf(game,
                ForegroundTransition(game.packageName, false, now - 300)), now))
        assertEquals(ForegroundHint.UNKNOWN,
            ForegroundHintRules.evaluate(listOf(game.copy(timeMs = now + 100)), now))
    }

    @Test fun displayNameOrLooseSubstringIsNeverAcceptedAsPackage() {
        assertEquals(ForegroundHint.OTHER_APP, ForegroundHintRules.evaluate(
            listOf(ForegroundTransition("com.miHoYo.hkrpg.fake", true, now - 50)), now))
        assertEquals(ForegroundHint.OTHER_APP, ForegroundHintRules.evaluate(
            listOf(ForegroundTransition("Honkai Star Rail", true, now - 50)), now))
    }

    @Test fun frameReadinessRejectsExpiredOrHiddenFrames() {
        val evaluate = { running: Boolean, seq: Long, frame: Long, current: Long,
            visible: Boolean?, hint: ForegroundHint ->
            FrameReadinessRules.evaluate(running, seq, frame, current, visible, hint)
        }
        assertEquals(FrameReadiness.NOT_CAPTURING,
            evaluate(false, 1, 98_000, now, true, ForegroundHint.GAME_CANDIDATE))
        assertEquals(FrameReadiness.NO_FRAME,
            evaluate(true, 0, 0, now, true, ForegroundHint.GAME_CANDIDATE))
        assertEquals(FrameReadiness.STALE,
            evaluate(true, 3, now - 2_001, now, true, ForegroundHint.GAME_CANDIDATE))
        assertEquals(FrameReadiness.STALE,
            evaluate(true, 3, now + 1, now, true, ForegroundHint.GAME_CANDIDATE))
        assertEquals(FrameReadiness.CAPTURE_HIDDEN,
            evaluate(true, 3, now - 100, now, false, ForegroundHint.GAME_CANDIDATE))
        assertEquals(FrameReadiness.GAME_NOT_CONFIRMED,
            evaluate(true, 3, now - 100, now, true, ForegroundHint.OTHER_APP))
        assertEquals(FrameReadiness.LOCAL_CANDIDATE,
            evaluate(true, 3, now - 100, now, true, ForegroundHint.GAME_CANDIDATE))
        // Even LOCAL_CANDIDATE is explicitly NOT input authority.
    }

    @Test fun lowNoiseIsIgnoredButLargeChangeOrResizeIsNot() {
        val detector = FrameChangeDetector()
        assertTrue(detector.changed(IntArray(64) { 5 }, 640, 360))
        assertFalse(detector.changed(IntArray(64) { 11 }, 640, 360))
        assertTrue(detector.changed(IntArray(64) { 80 }, 640, 360))
        assertTrue(detector.changed(IntArray(64) { 80 }, 360, 640))
        detector.reset()
        assertTrue(detector.changed(IntArray(64) { 80 }, 360, 640))
        assertTrue(runCatching { detector.changed(IntArray(4), 640, 360) }.isFailure)
    }
}
