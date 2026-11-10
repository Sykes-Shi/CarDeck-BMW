package io.github.shaonialife.cardeck.net

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/** 使用串行 executor 保留端点顺序；断开后，排队及等待锁中的旧回调不能跨轮次生效。 */
internal class EndpointCallbackQueue(private val executor: Executor) {
    private val generation = AtomicReference<Any?>(null)

    fun activate() {
        generation.compareAndSet(null, Any())
    }

    fun invalidate() {
        generation.set(null)
    }

    fun dispatch(action: (isCurrent: () -> Boolean) -> Unit) {
        val expected = generation.get() ?: return
        executor.execute {
            val isCurrent = { generation.get() === expected }
            if (isCurrent()) action(isCurrent)
        }
    }
}
