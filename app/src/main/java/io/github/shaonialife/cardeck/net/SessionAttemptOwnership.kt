package io.github.shaonialife.cardeck.net

/** 配对、已建立会话与失联通知共用归属；旧线程的 finally 不能释放下一轮的启动门闸。 */
internal class SessionAttemptOwnership<N : Any> {
    class Attempt<N : Any> internal constructor() {
        internal var network: N? = null
        internal var cancelled = false
    }

    private var current: Attempt<N>? = null

    @Synchronized
    fun begin(): Attempt<N> {
        current?.cancelled = true
        return Attempt<N>().also { current = it }
    }

    @Synchronized
    fun owns(attempt: Attempt<N>?): Boolean = current === attempt

    @Synchronized
    fun active(attempt: Attempt<N>): Boolean = current === attempt && !attempt.cancelled

    @Synchronized
    fun bind(attempt: Attempt<N>, network: N): Boolean {
        if (!active(attempt)) return false
        attempt.network = network
        return true
    }

    @Synchronized
    fun matches(attempt: Attempt<N>, network: N?): Boolean =
        active(attempt) && network != null && attempt.network == network

    @Synchronized
    fun lose(network: N): Attempt<N>? {
        val attempt = current ?: return null
        if (attempt.cancelled || attempt.network != network) return null
        attempt.cancelled = true
        return attempt
    }

    @Synchronized
    fun cancel(): Attempt<N>? {
        current?.cancelled = true
        return current
    }
}
