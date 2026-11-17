package top.m7a.ui

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedGameDeviceTest {
    private fun status(
        now: Long = 100_000,
        taskAt: Long = 99_400,
        mainActivity: String = TrustedGameDevice.MAIN_ACTIVITY,
        targetDisplay: Int = 0,
        taskDisplay: Int = 0,
        visible: Boolean = true,
        top: String = TrustedGameDevice.GAME_PACKAGE + "/" + TrustedGameDevice.MAIN_ACTIVITY,
        interactive: Boolean = true,
        locked: Boolean = false,
    ): JSONObject {
        val task = JSONObject()
            .put("display_id", taskDisplay)
            .put("visible", visible)
            .put("top_activity", top)
        val watchdog = JSONObject()
            .put("enabled", true)
            .put("package", TrustedGameDevice.GAME_PACKAGE)
            .put("main_activity", mainActivity)
            .put("target_display_id", targetDisplay)
            .put("task_checked_ms", taskAt)
            .put("interactive", interactive)
            .put("keyguard_locked", locked)
            .put("task", task)
        return JSONObject()
            .put("watchdog", watchdog)
            .put("power", JSONObject()
                .put("interactive", interactive)
                .put("keyguard_locked", locked))
    }

    @Test fun acceptsFreshVisibleGameTaskOnlyAsCandidateNotPermission() {
        val result = GameDeviceInspection.fromDeviceStatus(status(), 100_000)
        assertTrue(result.candidate)
        assertTrue(result.hasGameTask)
        assertTrue(result.taskOnDefaultDisplay)
        assertTrue(result.mainActivityOnTop)
        assertTrue(result.status.contains("仍需真实窗口焦点核验"))
    }

    @Test fun rejectsAllInsufficientLocalEvidence() {
        val bad = listOf(
            status(taskAt = 97_000),
            status(taskAt = 100_050),
            status(mainActivity = "com.example.OverlayActivity"),
            status(targetDisplay = 14),
            status(taskDisplay = 14),
            status(visible = false),
            status(top = "com.android.settings/.Settings"),
            status(interactive = false),
            status(locked = true),
            JSONObject().put("watchdog", JSONObject().put("enabled", false)),
        )
        for (raw in bad) {
            val result = GameDeviceInspection.fromDeviceStatus(raw, 100_000)
            assertFalse("Unexpected usable candidate: ${result.status}", result.candidate)
        }
    }

    @Test fun missingPermissionsAndFailedLeaseNeverBecomeCandidates() {
        assertFalse(GameDeviceInspection.denied("Shizuku 不可用").candidate)
        assertFalse(GameDeviceInspection.fromDeviceStatus(JSONObject(), 300_000).candidate)
    }
}
