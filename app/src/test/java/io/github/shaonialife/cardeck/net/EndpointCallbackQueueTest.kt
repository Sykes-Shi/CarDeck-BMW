package io.github.shaonialife.cardeck.net

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointCallbackQueueTest {
    private class QueuedExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test
    fun submittingEndpointsDoesNotRunTheirBlockingHandlersOnCaller() {
        val executor = QueuedExecutor()
        val queue = EndpointCallbackQueue(executor)
        queue.activate()
        val seen = mutableListOf<String>()
        queue.dispatch { seen.add("initial") }
        queue.dispatch { seen.add("late") }
        assertTrue(seen.isEmpty())
        executor.drain()
        assertEquals(listOf("initial", "late"), seen)
    }

    @Test
    fun queuedOldEndpointsAreSkippedAfterDisconnectAndImmediateRestart() {
        val executor = QueuedExecutor()
        val queue = EndpointCallbackQueue(executor)
        queue.activate()
        val seen = mutableListOf<String>()
        queue.dispatch { seen.add("old") }
        queue.invalidate()
        queue.activate()
        queue.dispatch { seen.add("new") }
        executor.drain()
        assertEquals(listOf("new"), seen)
    }

    @Test
    fun callbackAlreadyWaitingForSessionLockMustRecheckGeneration() {
        val executor = QueuedExecutor()
        val queue = EndpointCallbackQueue(executor)
        queue.activate()
        var afterLock: (() -> Boolean)? = null
        queue.dispatch { isCurrent -> afterLock = isCurrent }
        executor.drain()
        assertTrue(afterLock!!.invoke())
        queue.invalidate()
        assertFalse(afterLock!!.invoke())
    }

    @Test
    fun repeatedDisconnectCannotReviveAnOldCallback() {
        val executor = QueuedExecutor()
        val queue = EndpointCallbackQueue(executor)
        queue.activate()
        var oldCheck: (() -> Boolean)? = null
        var newCheck: (() -> Boolean)? = null
        queue.dispatch { oldCheck = it }
        executor.drain()
        queue.invalidate()
        queue.invalidate()
        queue.activate()
        queue.dispatch { newCheck = it }
        executor.drain()
        assertFalse(oldCheck!!.invoke())
        assertTrue(newCheck!!.invoke())
    }

    @Test
    fun endpointsArrivingWhileDisconnectedCannotBeQueuedIntoNextConnection() {
        val executor = QueuedExecutor()
        val queue = EndpointCallbackQueue(executor)
        val seen = mutableListOf<String>()
        queue.dispatch { seen.add("before-start") }
        queue.activate()
        queue.invalidate()
        queue.dispatch { seen.add("after-disconnect") }
        queue.activate()
        queue.dispatch { seen.add("new") }
        executor.drain()
        assertEquals(listOf("new"), seen)
    }
}
