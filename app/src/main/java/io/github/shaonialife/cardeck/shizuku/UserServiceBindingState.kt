package io.github.shaonialife.cardeck.shizuku

/** 绑定生命周期的纯状态机；调用方用同一把锁保护它与已发布的服务。 */
internal class UserServiceBindingState(
    private val startupTimeoutMs: Long = 12_000L,
    private val retryDelayMs: Long = 1_500L,
) {
    private enum class Phase { IDLE, BINDING, VERIFYING, READY }

    private var phase = Phase.IDLE
    private var generation = 0L
    private var startedAt = 0L
    private var retryAfter = Long.MIN_VALUE

    fun canRequest(now: Long): Boolean =
        phase != Phase.READY && now >= retryAfter &&
            (phase == Phase.IDLE || (now >= startedAt && now - startedAt >= startupTimeoutMs))

    fun request(now: Long): Long? {
        if (!canRequest(now)) return null
        generation++
        startedAt = now
        phase = Phase.BINDING
        return generation
    }

    fun isCurrent(token: Long): Boolean = generation == token && phase != Phase.IDLE

    fun beginVerification(token: Long): Boolean {
        if (!isCurrent(token) || phase != Phase.BINDING) return false
        phase = Phase.VERIFYING
        return true
    }

    fun verified(token: Long): Boolean {
        if (!isCurrent(token) || phase != Phase.VERIFYING) return false
        phase = Phase.READY
        return true
    }

    fun failed(token: Long, now: Long): Boolean {
        if (!isCurrent(token)) return false
        phase = Phase.IDLE
        generation++
        retryAfter = now + retryDelayMs
        return true
    }

    fun invalidate() {
        generation++
        phase = Phase.IDLE
        retryAfter = Long.MIN_VALUE
    }
}
