package top.m7a.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamerProtocolTest {
    private fun envelope(body: String) =
        "<|action_start|>$body<|action_end|>"

    @Test fun gameScalingPublicExampleParsesReadOnly() {
        val result = GamerProtocol.parse(
            "<think></think>" + envelope("0 0 0 ; W ; W ; W ; W ; W ; W"),
        )
        assertTrue(result.valid)
        val chunk = result.chunk!!
        assertEquals(6, chunk.groups.size)
        assertEquals(200, GamerProtocol.CHUNK_MS)
        assertEquals(listOf("W"), chunk.groups.first())
        assertEquals(envelope("0 0 0 ; W ; W ; W ; W ; W ; W"), chunk.toWire())
        assertFalse(chunk.isNoop)
    }

    @Test fun reasoningQuotedCommandsAreIgnored() {
        val pretend = envelope("0 0 0 ; LB ; LB ; LB ; LB ; LB ; LB")
        val noop = envelope("0 0 0 ; ; ; ; ; ;")
        val result = GamerProtocol.parse("<think>The template says $pretend</think>$noop")
        assertTrue(result.valid)
        assertTrue(result.chunk!!.isNoop)
        assertEquals(noop, result.chunk!!.toWire())
    }

    @Test fun strictGrammarRejectsMalformedInputs() {
        val cases = mapOf(
            "<think>unfinished " + envelope("0 0 0 ; W ; W ; W ; W ; W ; W") to
                "incomplete_or_multiple_reasoning_blocks",
            "0 0 0 ; W ; W ; W ; W ; W ; W" to
                "missing_or_multiple_action_envelopes",
            envelope("0 0 0 ; W ; W ; W ; W ; W") to
                "expected_six_action_steps",
            envelope("0 0 0 ; W ; W ; W ; W ; W ; W ; W") to
                "expected_six_action_steps",
            envelope("1001 0 0 ; ; ; ; ; ;") to
                "mouse_or_scroll_out_of_range",
            envelope("0 -1001 0 ; ; ; ; ; ;") to
                "mouse_or_scroll_out_of_range",
            envelope("0 0 6 ; ; ; ; ; ;") to
                "mouse_or_scroll_out_of_range",
            envelope("0 0 0 ; W W ; ; ; ; ;") to
                "duplicate_key_in_step",
            envelope("0 0 0 ; W A S D Space ; ; ; ; ;") to
                "too_many_keys_in_step",
            envelope("0 0 0 ; sudo ; ; ; ; ;") to
                "unknown_or_case_mismatched_key",
            envelope("0 0 0 ; w ; ; ; ; ;") to
                "unknown_or_case_mismatched_key",
            envelope("0.5 0 0 ; ; ; ; ; ;") to
                "invalid_mouse_or_scroll_numbers",
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, GamerProtocol.parse(input).reason)
        }
    }

    @Test fun rejectsExtraInputAndOverlongResponses() {
        assertFalse(GamerProtocol.parse("x".repeat(GamerProtocol.MAX_MESSAGE_CHARS + 1)).valid)
        assertFalse(GamerProtocol.parse(envelope("0 0 0 ; ; ; ; ; ;") + "extra").valid)
        assertFalse(GamerProtocol.parse(
            envelope("0 0 0 ; ; ; ; ; ;") + envelope("0 0 0 ; ; ; ; ; ;"),
        ).valid)
        assertFalse(GamerProtocol.parse("<think>a</think><think>b</think>" +
            envelope("0 0 0 ; ; ; ; ; ;")).valid)
    }

    @Test fun boundariesAndCaseSensitivity() {
        val result = GamerProtocol.parse(envelope("1000 -1000 5 ; one ; One ; ; ; ;"))
        assertTrue(result.valid)
        assertNotNull(result.chunk)
        assertEquals(listOf("one"), result.chunk!!.groups[0])
        assertEquals(listOf("One"), result.chunk!!.groups[1])
        assertTrue(GamerProtocol.parse(envelope("0 0 0 ; ; ; ; ; ;")).chunk!!.isNoop)
    }
}
