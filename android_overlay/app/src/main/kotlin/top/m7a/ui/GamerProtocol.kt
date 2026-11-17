package top.m7a.ui

/**
 * Read-only, STRICT parser for the public Gamer / GameScaling wire protocol.
 * Six keyboard/mouse groups describe 200 ms of intent, NOT Android touch targets.
 *
 * This file has no input, screenshot, model, network, or permission API.
 * A successful parse MUST NEVER be interpreted as authorization to tap.
 */
internal data class GamerActionChunk(
    val mouseXPermille: Int,
    val mouseYPermille: Int,
    val scrollNotches: Int,
    val groups: List<List<String>>,
) {
    val isNoop: Boolean
        get() = mouseXPermille == 0 && mouseYPermille == 0 &&
            scrollNotches == 0 && groups.all { it.isEmpty() }

    fun toWire(): String =
        "<|action_start|>$mouseXPermille $mouseYPermille $scrollNotches " +
            groups.joinToString(" ") { group ->
                if (group.isEmpty()) ";" else "; " + group.joinToString(" ")
            } + "<|action_end|>"
}

internal data class GamerParseResult(
    val chunk: GamerActionChunk? = null,
    val reason: String,
) {
    val valid: Boolean get() = chunk != null
}

internal object GamerProtocol {
    const val CHUNK_MS = 200
    const val STEP_COUNT = 6
    const val MAX_MESSAGE_CHARS = 4096

    private const val BEGIN = "<|action_start|>"
    private const val END = "<|action_end|>"
    private const val THINK = "<think>"
    private const val CLOSE_THINK = "</think>"
    private val integer = Regex("-?(?:0|[1-9][0-9]*)")
    private val knownKeys: Set<String> = (
        ('A'..'Z').map { it.toString() } +
        "zero one two three four five six seven eight nine".split(' ') +
        "One Two Three Four Five Six Seven Eight Nine Ten Eleven Twelve".split(' ') +
        "LB RB MB Up Down Left Right".split(' ') +
        "Shift Ctrl Alt Tab Caps Esc Space Enter Back Delete Insert Home End Pause".split(' ') +
        "Equal Minus Period Slash Quote".split(' ')
    ).toSet()

    private fun occurrences(text: String, sub: String): Int =
        text.windowed(sub.length).count { it == sub }

    private fun fail(reason: String) = GamerParseResult(reason = reason)

    fun parse(message: String): GamerParseResult {
        if (message.isBlank() || message.length > MAX_MESSAGE_CHARS)
            return fail("empty_or_oversized_message")

        var action = message.trim()
        if (action.startsWith(THINK)) {
            if (occurrences(action, THINK) != 1 || occurrences(action, CLOSE_THINK) != 1)
                return fail("incomplete_or_multiple_reasoning_blocks")
            val endAt = action.lastIndexOf(CLOSE_THINK)
            if (endAt < THINK.length) return fail("invalid_reasoning")
            action = action.substring(endAt + CLOSE_THINK.length).trim()
        } else if (THINK in action || CLOSE_THINK in action) {
            return fail("invalid_reasoning")
        }

        if (!action.startsWith(BEGIN) || !action.endsWith(END) ||
            occurrences(action, BEGIN) != 1 || occurrences(action, END) != 1)
            return fail("missing_or_multiple_action_envelopes")

        val body = action.substring(BEGIN.length, action.length - END.length)
        if (body.any { it == '\n' || it == '\r' || it == '<' || it == '>' })
            return fail("action_contains_unexpected_text")

        val parts = body.split(';').map { it.trim() }
        if (parts.size != STEP_COUNT + 1) return fail("expected_six_action_steps")

        val numbers = parts[0].split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (numbers.size != 3 || numbers.any { !integer.matches(it) })
            return fail("invalid_mouse_or_scroll_numbers")
        val values = numbers.map { it.toIntOrNull() }
        if (values.any { it == null }) return fail("invalid_mouse_or_scroll_numbers")
        val x = values[0]!!
        val y = values[1]!!
        val z = values[2]!!
        if (x !in -1000..1000 || y !in -1000..1000 || z !in -5..5)
            return fail("mouse_or_scroll_out_of_range")

        val steps = ArrayList<List<String>>(STEP_COUNT)
        for (part in parts.drop(1)) {
            val keys = part.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (keys.size > 4) return fail("too_many_keys_in_step")
            if (keys.any { it !in knownKeys }) return fail("unknown_or_case_mismatched_key")
            if (keys.size != keys.toSet().size) return fail("duplicate_key_in_step")
            steps.add(keys)
        }
        return GamerParseResult(GamerActionChunk(x, y, z, steps), "parsed_read_only")
    }
}
