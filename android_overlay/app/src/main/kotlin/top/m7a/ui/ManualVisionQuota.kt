package top.m7a.ui

/**
 * Process-lifetime quota for explicit one-shot manual preview uploads.
 * No automatic screenshots or retry loops. A denied or failed network call
 * still consumes a permit once it starts, to avoid accidental request storms.
 */
internal class ManualVisionQuota(
    private val maxRequests: Int = 3,
    private val cooldownMs: Long = 10_000L,
) {
    init {
        require(maxRequests > 0 && cooldownMs >= 0)
    }

    private var used = 0
    private var lastRequestElapsedMs: Long? = null

    @Synchronized
    fun consume(nowElapsedMs: Long): String? {
        if (nowElapsedMs < 0) return "invalid_monotonic_time"
        if (used >= maxRequests) return "request_budget_exhausted"
        val last = lastRequestElapsedMs
        if (last != null && nowElapsedMs >= last &&
            nowElapsedMs - last < cooldownMs) return "cooldown"
        if (last != null && nowElapsedMs < last) return "clock_reset"
        used += 1
        lastRequestElapsedMs = nowElapsedMs
        return null
    }

    @Synchronized
    fun requestsUsed(): Int = used
}

internal object ManualVisionQuotaSession {
    val quota = ManualVisionQuota()
}
