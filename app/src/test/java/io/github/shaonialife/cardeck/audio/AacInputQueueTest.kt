package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer

class AacInputQueueTest {
    private data class Queued(val index: Int, val bytes: ByteArray, val presentationUs: Long)

    private class FakePort : AacInputPort {
        var now = 0L
        var buffer: ByteBuffer? = ByteBuffer.allocate(8)
        var onDequeue: (Long) -> Int = { 3 }
        var onDrain: (Long) -> Unit = {}
        var onQueue: () -> Unit = {}
        val waits = mutableListOf<Long>()
        val drainDeadlines = mutableListOf<Long>()
        val queued = mutableListOf<Queued>()
        val events = mutableListOf<String>()

        override fun dequeueInput(timeoutUs: Long): Int {
            waits += timeoutUs
            events += "dequeue"
            return onDequeue(timeoutUs)
        }

        override fun inputBuffer(index: Int): ByteBuffer? = buffer

        override fun queueInput(index: Int, size: Int, presentationUs: Long) {
            onQueue()
            val bytes = ByteArray(size)
            requireNotNull(buffer).duplicate().apply { flip() }.get(bytes)
            queued += Queued(index, bytes, presentationUs)
            events += "queue"
        }

        override fun drainOutput(deadlineNanos: Long) {
            drainDeadlines += deadlineNanos
            events += "drain"
            onDrain(deadlineNanos)
        }
    }

    private fun submit(
        port: FakePort,
        pcm: ByteArray = byteArrayOf(1, 2, 3, 4),
        size: Int = pcm.size,
        presentationUs: Long = 21_333L,
        isInterrupted: () -> Boolean = { false },
    ) = AacInputQueue.submit(port, pcm, size, presentationUs, { port.now }, isInterrupted)

    private fun failure(block: () -> Unit): IOException {
        try {
            block()
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("Expected IOException")
    }

    @Test
    fun availableInputQueuesExactlyTheRequestedBytesOnce() {
        val port = FakePort()
        submit(port, byteArrayOf(1, 2, 3, 4, 99), size = 4)
        assertEquals(1, port.queued.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), port.queued.single().bytes)
        assertEquals(21_333L, port.queued.single().presentationUs)
        assertEquals(3, port.queued.single().index)
        assertTrue(port.drainDeadlines.isEmpty())
    }

    @Test
    fun transientTimeoutRetriesTheSameFrameAndTimestamp() {
        val port = FakePort()
        var attempt = 0
        port.onDequeue = {
            port.now += 10_000_000L
            if (attempt++ == 0) -1 else 3
        }
        submit(port)
        assertEquals(listOf("dequeue", "drain", "dequeue", "queue"), port.events)
        assertEquals(1, port.queued.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), port.queued.single().bytes)
        assertEquals(21_333L, port.queued.single().presentationUs)
    }

    @Test
    fun drainingOldOutputUnlocksInputAndPreservesOutputOrder() {
        val port = FakePort()
        var inputReady = false
        val output = mutableListOf<Int>()
        port.onDequeue = { if (inputReady) 4 else -1 }
        port.onDrain = {
            output += 1
            inputReady = true
        }
        port.onQueue = { output += 2 }
        submit(port)
        assertEquals(listOf(1, 2), output)
        assertEquals(1, port.queued.size)
        assertEquals(4, port.queued.single().index)
        assertEquals(listOf(AacInputQueue.MAX_WAIT_NANOS), port.drainDeadlines)
    }

    @Test
    fun permanentlyUnavailableInputHasAnAttemptBoundEvenWithoutClockProgress() {
        val port = FakePort()
        port.onDequeue = { -1 }
        assertEquals("aac_input_timeout", failure { submit(port) }.message)
        assertEquals(AacInputQueue.MAX_ATTEMPTS, port.waits.size)
        assertTrue(port.waits.all { it == AacInputQueue.DEQUEUE_TIMEOUT_US })
        assertTrue(port.queued.isEmpty())
    }

    @Test
    fun timeSpentDrainingCountsAgainstTheSameDeadline() {
        val port = FakePort()
        port.onDequeue = { -1 }
        port.onDrain = { port.now = it }
        assertEquals("aac_input_timeout", failure { submit(port) }.message)
        assertEquals(1, port.waits.size)
        assertEquals(1, port.drainDeadlines.size)
        assertTrue(port.queued.isEmpty())
    }

    @Test
    fun lastDequeueIsLimitedToTheRemainingBudget() {
        val port = FakePort()
        var attempt = 0
        port.onDequeue = {
            if (attempt++ == 0) {
                port.now = 95_000_000L
                -1
            } else {
                port.now += 1_000_000L
                2
            }
        }
        submit(port)
        assertEquals(listOf(10_000L, 5_000L), port.waits)
        assertEquals(1, port.queued.size)
    }

    @Test
    fun inputReturnedAtDeadlineIsNotQueued() {
        val port = FakePort()
        port.onDequeue = {
            port.now = AacInputQueue.MAX_WAIT_NANOS
            3
        }
        assertEquals("aac_input_timeout", failure { submit(port) }.message)
        assertTrue(port.queued.isEmpty())
    }

    @Test
    fun missingInputBufferFailsWithoutQueuingAnEmptyFrame() {
        val port = FakePort()
        port.buffer = null
        assertEquals("aac_input_buffer_missing", failure { submit(port) }.message)
        assertTrue(port.queued.isEmpty())
    }

    @Test
    fun zeroCapacityBufferFailsBeforeQueue() {
        val port = FakePort()
        port.buffer = ByteBuffer.allocate(0)
        assertEquals("aac_input_buffer_too_small_or_read_only", failure { submit(port) }.message)
        assertTrue(port.queued.isEmpty())
    }

    @Test
    fun tooSmallBufferIsNotPartiallyWrittenOrQueued() {
        val port = FakePort()
        val original = byteArrayOf(7, 8, 9)
        port.buffer = ByteBuffer.wrap(original)
        assertEquals("aac_input_buffer_too_small_or_read_only", failure { submit(port) }.message)
        assertArrayEquals(byteArrayOf(7, 8, 9), original)
        assertTrue(port.queued.isEmpty())
    }

    @Test
    fun readOnlyBufferFailsWithoutQueue() {
        val port = FakePort()
        port.buffer = ByteBuffer.allocate(8).asReadOnlyBuffer()
        assertEquals("aac_input_buffer_too_small_or_read_only", failure { submit(port) }.message)
        assertTrue(port.queued.isEmpty())
    }

    @Test
    fun invalidSourceLengthDoesNotTouchCodec() {
        for (size in listOf(-1, 0, 5)) {
            val port = FakePort()
            assertEquals("aac_invalid_pcm_size", failure { submit(port, size = size) }.message)
            assertTrue(port.events.isEmpty())
        }
    }

    @Test
    fun unexpectedNegativeInputStatusFailsWithoutRetry() {
        val port = FakePort()
        port.onDequeue = { -2 }
        assertEquals("aac_unexpected_input_status", failure { submit(port) }.message)
        assertEquals(listOf("dequeue"), port.events)
    }

    @Test
    fun consecutiveFramesRetainTheirByteAndPresentationOrder() {
        val port = FakePort()
        var attempt = 0
        port.onDequeue = { if (attempt++ % 2 == 0) -1 else 3 }
        submit(port, byteArrayOf(1, 2, 3, 4), presentationUs = 0L)
        submit(port, byteArrayOf(5, 6, 7, 8), presentationUs = 21_333L)
        assertEquals(2, port.queued.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), port.queued[0].bytes)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), port.queued[1].bytes)
        assertEquals(listOf(0L, 21_333L), port.queued.map { it.presentationUs })
    }

    @Test
    fun cancellationBeforeDequeueDoesNotTouchCodec() {
        val port = FakePort()
        assertTrue(failure { submit(port, isInterrupted = { true }) } is InterruptedIOException)
        assertTrue(port.events.isEmpty())
    }

    @Test
    fun cancellationDuringDequeueDoesNotQueueLateInput() {
        val port = FakePort()
        var interrupted = false
        port.onDequeue = {
            interrupted = true
            3
        }
        assertTrue(failure { submit(port, isInterrupted = { interrupted }) } is InterruptedIOException)
        assertTrue(port.queued.isEmpty())
    }

    @Test
    fun queueFailureIsNotRetriedWithTheSameInputSlot() {
        val port = FakePort()
        val expected = IOException("fake_queue_failure")
        port.onQueue = { throw expected }
        assertSame(expected, failure { submit(port) })
        assertEquals(1, port.waits.size)
        assertTrue(port.queued.isEmpty())
    }

    @Test
    fun drainFailureStopsRetriesAndIsPreserved() {
        val port = FakePort()
        val expected = IOException("fake_drain_failure")
        port.onDequeue = { -1 }
        port.onDrain = { throw expected }
        assertSame(expected, failure { submit(port) })
        assertEquals(1, port.waits.size)
        assertTrue(port.queued.isEmpty())
    }
}
