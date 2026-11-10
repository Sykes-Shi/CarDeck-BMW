package io.github.shaonialife.cardeck.net

/**
 * 将请求归属与进程绑定操作串行化。请求按引用区分，网络按值比较：
 * Android 可能为同一网络交付不同的 Network 实例。
 * 传入的平台操作不得在本锁内调用上层会话回调。
 */
internal class OwnedNetworkBinding<O : Any, N : Any>(
    private val bind: (N) -> Boolean,
    private val unbind: (N) -> Unit,
    private val isLive: (N) -> Boolean,
) {
    enum class BindResult {
        BOUND,
        FAILED,
        STALE,
    }

    private var owner: O? = null
    private var network: N? = null
    private var revision = 0L

    @Synchronized
    fun replace(next: O): O? {
        val previous = owner
        clearNetwork()
        owner = next
        return previous
    }

    @Synchronized
    fun isCurrent(expected: O): Boolean = owner === expected

    @Synchronized
    fun isBound(expected: O, candidate: N): Boolean = owner === expected && network == candidate

    @Synchronized
    fun available(expected: O, candidate: N): BindResult {
        if (owner !== expected) return BindResult.STALE
        if (!bind(candidate)) {
            // 同一请求切网失败时也清除旧绑定，避免残留网络被当成新连接成功。
            clearNetwork()
            return BindResult.FAILED
        }
        revision++
        network = candidate
        return BindResult.BOUND
    }

    @Synchronized
    fun recordLoss(expected: O, candidate: N): Long? {
        if (owner !== expected || network != candidate) return null
        clearNetwork()
        return revision
    }

    /** 失联通知排队期间发生重连、换网、释放或新请求后，旧通知必须失效。 */
    @Synchronized
    fun isCurrentLoss(expected: O, expectedRevision: Long): Boolean =
        owner === expected && network == null && revision == expectedRevision

    /** UI 与 NetworkCallback 使用的廉价快照，不查询 Android。 */
    @Synchronized
    fun current(expected: O? = null): N? = if (expected == null || owner === expected) network else null

    /** 复用缓存前在后台线程调用，不在 NetworkCallback 中同步查询平台网络。 */
    @Synchronized
    fun validatedCurrent(expected: O? = null): N? {
        if (expected != null && owner !== expected) return null
        val existing = network ?: return null
        if (!isLive(existing)) {
            clearNetwork()
            return null
        }
        return existing
    }

    /** 已取消或超时的旧等待线程只能释放自己的请求。 */
    @Synchronized
    fun release(expected: O? = null): O? {
        if (expected != null && owner !== expected) return null
        val previous = owner
        owner = null
        clearNetwork()
        return previous
    }

    private fun clearNetwork() {
        revision++
        val previous = network ?: return
        network = null
        unbind(previous)
    }
}
