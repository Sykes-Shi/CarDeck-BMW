package io.github.shaonialife.cardeck.airplay

import java.util.ArrayDeque

/**
 * BMW MGU-50-P 的有界 requestUI 诊断门控，每个 AirPlay client 单独持有一份。
 *
 * 实车曾在切回宝马界面后持续请求恢复；每会话永久额度会使后续操作一直失效。
 * 仅由真实请求触发，冷却 5 秒且任意 60 秒最多尝试 6 次，兼顾长会话恢复与突发限流。
 * allowed 只表示预留一次尝试，不表示已显示画面；调用方须在 finally 中释放在途标记。
 * 不接收命令参数，尤其不解析或打开接收端给出的 URL。
 */
internal class BmwRequestUiRecoveryPolicy(
    receiverIdentity: String,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val eligibleReceiver =
        receiverIdentity.trim().split(Regex("\\s+")).joinToString(" ")
            .equals("BMW Group MGU-50-P", ignoreCase = true)
    data class Decision(
        val allowed: Boolean,
        val reason: String,
        /** 时间门控的最早重试等待量；其他条件没有固定等待时间，用 null 表示。 */
        val retryAfterMs: Long?,
        /** 包含本次已预留的尝试；窗口为 (now - 60s, now]。 */
        val attemptsInWindow: Int,
    )

    private val attemptTimes = ArrayDeque<Long>()
    private var inFlight = false
    private var lastObservedMillis: Long? = null

    /** 调用方仍须在执行副作用前确认 client 未被取消或替换；失败也消耗额度及冷却期。 */
    @Synchronized
    fun decide(commandType: String?, liveSession: Boolean): Decision {
        val now = monotonicMillis()
        val previous = lastObservedMillis
        val clockRolledBack = previous != null && now < previous
        if (!clockRolledBack) {
            lastObservedMillis = now
            while (attemptTimes.isNotEmpty() && elapsedSince(now, attemptTimes.first) >= WINDOW_MILLIS) {
                attemptTimes.removeFirst()
            }
        }
        fun denied(reason: String, retryAfterMs: Long? = null) =
            Decision(false, reason, retryAfterMs, attemptTimes.size)

        when {
            !eligibleReceiver -> return denied("receiver_not_supported")
            !liveSession -> return denied("session_not_live")
            commandType != "requestUI" -> return denied("unsupported_command")
            clockRolledBack -> return denied("clock_rollback")
            inFlight -> return denied("in_flight")
        }
        val cooldownRemaining = attemptTimes.peekLast()
            ?.let { (COOLDOWN_MILLIS - elapsedSince(now, it)).coerceAtLeast(0L) } ?: 0L
        val windowRemaining = if (attemptTimes.size >= MAX_ATTEMPTS_IN_WINDOW) {
            WINDOW_MILLIS - elapsedSince(now, attemptTimes.first)
        } else {
            0L
        }
        // 同时受窗口与冷却约束时，报告真正可重试的时间；拒绝请求不移动尝试时间。
        if (windowRemaining > 0L) return denied("window_limit", maxOf(windowRemaining, cooldownRemaining))
        if (cooldownRemaining > 0L) return denied("cooldown", cooldownRemaining)
        attemptTimes.addLast(now)
        inFlight = true
        return Decision(true, "allowed", 0L, attemptTimes.size)
    }

    /** 兼容布尔调用；需要决策详情时只调用 decide，不能两者都调用而重复预留。 */
    fun shouldRecover(commandType: String?, liveSession: Boolean): Boolean = decide(commandType, liveSession).allowed

    /** 仅释放当前调用的在途标记；不清空窗口记录或替换后的 client 的策略。 */
    @Synchronized
    fun finishRecovery() {
        inFlight = false
    }

    // 调用点已确认时钟没有回退；极大正向跳变溢出时视为旧记录已过期。
    private fun elapsedSince(now: Long, earlier: Long): Long = (now - earlier).takeIf { it >= 0L } ?: Long.MAX_VALUE

    private companion object {
        const val COOLDOWN_MILLIS = 5_000L
        const val WINDOW_MILLIS = 60_000L
        const val MAX_ATTEMPTS_IN_WINDOW = 6
    }
}
