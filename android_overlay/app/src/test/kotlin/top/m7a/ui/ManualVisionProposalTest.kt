package top.m7a.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ManualVisionProposalTest {
    @Test fun parsesReadOnlySceneAndEmptyAction() {
        val body = JSONObject()
            .put("scene", "角色界面")
            .put("observations", "画面中显示角色")
            .put("next_step", "确认角色信息")
            .put("gamer_action", "")
        val result = parseManualVisionProposal(body.toString())
        assertEquals("角色界面", result.scene)
        assertEquals("画面中显示角色", result.observations)
        assertNull(result.gameActionParse)
    }

    @Test fun gamerChunkIsOnlyParsedNeverExecuted() {
        val action = "<|action_start|>0 0 0 ; W ; W ; W ; W ; W ; W<|action_end|>"
        val body = JSONObject()
            .put("scene", "探索")
            .put("observations", "画面中存在道路")
            .put("next_step", "保持观察")
            .put("gamer_action", action)
        val result = parseManualVisionProposal(body.toString())
        assertTrue(result.gameActionParse!!.valid)
        assertEquals(action, result.gamerAction)
    }

    @Test fun unrequestedGamerActionIsStrippedEvenWhenModelIgnoresPrompt() {
        val action = "<|action_start|>0 0 0 ; W ; W ; W ; W ; W ; W<|action_end|>"
        val proposed = parseManualVisionProposal(JSONObject()
            .put("scene", "探索").put("observations", "道路")
            .put("next_step", "继续观察").put("gamer_action", action).toString())
        val denied = restrictGamerSuggestion(proposed, allowGamerSuggestion = false)
        assertTrue(denied.gamerAction.isEmpty())
        assertNull(denied.gameActionParse)
        val permittedPreview = restrictGamerSuggestion(proposed, allowGamerSuggestion = true)
        assertEquals(action, permittedPreview.gamerAction)
        assertTrue(permittedPreview.gameActionParse!!.valid)
    }

    @Test fun malformedResponseRefused() {
        assertTrue(runCatching { parseManualVisionProposal("not JSON") }.isFailure)
        assertTrue(runCatching { parseManualVisionProposal("{\"scene\":\"X\"}") }.isFailure)
        assertTrue(runCatching { parseManualVisionProposal(" ") }.isFailure)
        assertTrue(runCatching { parseManualVisionProposal("x".repeat(12_001)) }.isFailure)
    }

    @Test fun gamerActionGenerationNeedsExplicitOptIn() {
        val defaultPrompt = manualVisionSystemPrompt(false)
        assertTrue(defaultPrompt.contains("gamer_action 必须始终为空字符串"))
        assertFalse(defaultPrompt.contains("只有当键鼠映射非常明确"))
        val enabled = manualVisionSystemPrompt(true)
        assertTrue(enabled.contains("200 毫秒"))
        assertTrue(enabled.contains("<|action_start|>"))
        assertTrue(enabled.contains("不清楚按键时必须留空"))
        assertTrue(enabled.contains("不会被发送给 Android"))
    }

    @Test fun rateLimitRejectsRapidCallsAndExhaustion() {
        val quota = ManualVisionQuota(maxRequests = 3, cooldownMs = 10_000L)
        assertNull(quota.consume(10_000L))
        assertEquals("cooldown", quota.consume(10_001L))
        assertEquals(1, quota.requestsUsed())
        assertNull(quota.consume(20_000L))
        assertNull(quota.consume(30_000L))
        assertEquals("request_budget_exhausted", quota.consume(40_000L))
        assertEquals(3, quota.requestsUsed())
    }

    @Test fun rejectsReversedClockAndInvalidConfiguration() {
        val quota = ManualVisionQuota(maxRequests = 2, cooldownMs = 5_000L)
        assertEquals("invalid_monotonic_time", quota.consume(-1))
        assertNull(quota.consume(12_000L))
        assertEquals("clock_reset", quota.consume(11_999L))
        assertTrue(runCatching { ManualVisionQuota(0, 10_000L) }.isFailure)
        assertTrue(runCatching { ManualVisionQuota(3, -1L) }.isFailure)
    }
}
