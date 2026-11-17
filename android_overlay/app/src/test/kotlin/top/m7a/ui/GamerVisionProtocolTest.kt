package top.m7a.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamerVisionProtocolTest {
    @Test fun requestBodyContainsOnlyOneImageAndNoCredentials() {
        val obj = GamerVisionProtocol.requestBody("vision-test", "wait or stop", "aGVsbG8=")
        assertEquals("vision-test", obj.getString("model"))
        assertFalse(obj.getBoolean("stream"))
        val messages = obj.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val system = messages.getJSONObject(0).getString("content")
        assertTrue(system.contains("READ-ONLY"))
        assertTrue(system.contains("200ms"))
        val user = messages.getJSONObject(1).getJSONArray("content")
        assertEquals(2, user.length())
        assertEquals("text", user.getJSONObject(0).getString("type"))
        assertEquals("image_url", user.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,aGVsbG8=",
            user.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertFalse(obj.toString().contains("Bearer "))
        assertFalse(obj.toString().contains("account_profile"))
    }

    @Test fun explicitConsentBudgetAndStaleFramesAreFailClosed() {
        val rule = GamerVisionProtocol
        assertNull(rule.blockReason(0, -1L, 10_000, 9_000))
        assertNotNull(rule.blockReason(8, -1L, 10_000, 9_000))
        assertNotNull(rule.blockReason(1, 4_000L, 10_000, 9_000))
        assertNull(rule.blockReason(1, 0L, 11_000, 9_000))
        assertNotNull(rule.blockReason(0, -1L, 200_001, 1))
        assertNotNull(rule.blockReason(0, -1L, 10_000, 10_001))
        assertNotNull(rule.blockReason(0, -1L, 10_000, 0))
    }

    @Test fun noopIsStrictlyParsedButStillNonExecutable() {
        val parsed = GamerProtocol.parse(GamerVisionProtocol.NOOP)
        assertTrue(parsed.valid)
        assertTrue(parsed.chunk!!.isNoop)
        assertEquals(6, parsed.chunk!!.groups.size)
        assertFalse(GamerProtocol.parse("click at 300,500").valid)
    }

    @Test fun rejectUnboundedProviderPayloads() {
        assertTrue(runCatching {
            GamerVisionProtocol.requestBody("", "wait", "AA==")
        }.isFailure)
        assertTrue(runCatching {
            GamerVisionProtocol.requestBody("m", " ".repeat(301), "AA==")
        }.isFailure)
        assertTrue(runCatching {
            GamerVisionProtocol.requestBody("m", "wait", "x".repeat(2_100_001))
        }.isFailure)
    }
}
