package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PcmFrameReaderTest {
    @Test
    fun oddByteFragmentsPreserveSignedSamplesAndPeak() {
        val pcm = byteArrayOf(0, -128, -1, 127, 1, 0, 0, 0)
        val sizes = listOf(1, 3, 1, 3)
        var position = 0
        var calls = 0
        var observedBytes = 0
        val target = ByteArray(8)
        val result = PcmFrameReader(8).readFrame(target, { false }, { buffer, requested ->
            val size = sizes[calls++]
            assertTrue(size <= requested)
            pcm.copyInto(buffer, 0, position, position + size)
            position += size
            size
        }, { _, count -> observedBytes += count })
        assertEquals(8, result)
        assertEquals(4, calls)
        assertEquals(8, observedBytes)
        assertArrayEquals(pcm, target)
        val stats = PcmCaptureStats(5)
        stats.reset(0)
        val window = stats.recordRead(target, result, 8, 10)!!
        assertEquals(32768, window.peak)
        assertEquals(4L, window.sampleValues)
        assertEquals(3L, window.nonZeroValues)
    }

    @Test
    fun consecutiveFramesDoNotMixOrReusePreviousContents() {
        val reader = PcmFrameReader(4)
        var value = 0
        val target = ByteArray(4)
        repeat(2) {
            assertEquals(4, reader.readFrame(target, { false }, { buffer, _ ->
                buffer[0] = (++value).toByte()
                1
            }, { _, _ -> }))
            assertArrayEquals(ByteArray(4) { index -> (it * 4 + index + 1).toByte() }, target)
        }
    }

    @Test
    fun eofAfterPartialFrameCountsBytesButDoesNotEmitFrame() {
        var calls = 0
        var bytes = 0
        val target = ByteArray(8)
        val result = PcmFrameReader(8).readFrame(target, { false }, { buffer, _ ->
            if (calls++ == 0) {
                buffer[0] = 1
                1
            } else {
                -1
            }
        }, { _, count -> bytes += count })
        assertEquals(-1, result)
        assertEquals(1, bytes)
    }

    @Test
    fun cancellationDuringPartialFrameStopsBeforeAnotherRead() {
        var cancelled = false
        var calls = 0
        val result = PcmFrameReader(8).readFrame(ByteArray(8), { cancelled }, { buffer, _ ->
            calls++
            buffer[0] = 1
            1
        }, { _, _ -> cancelled = true })
        assertEquals(-1, result)
        assertEquals(1, calls)
    }

    @Test
    fun emptyReadDoesNotErasePartialFrame() {
        var call = 0
        val target = ByteArray(4)
        val result = PcmFrameReader(4).readFrame(target, { false }, { buffer, requested ->
            when (call++) {
                0 -> {
                    buffer[0] = 7
                    1
                }
                1 -> 0
                else -> {
                    assertEquals(3, requested)
                    buffer.fill(8)
                    3
                }
            }
        }, { _, _ -> })
        assertEquals(4, result)
        assertArrayEquals(byteArrayOf(7, 8, 8, 8), target)
    }

    @Test(expected = IllegalStateException::class)
    fun readBeyondRemainingBytesIsRejected() {
        PcmFrameReader(4).readFrame(ByteArray(4), { false }, { _, _ -> 5 }, { _, _ -> })
    }

    @Test
    fun gateRetainsOriginalOwnerUntilExplicitRelease() {
        val gate = CaptureOwnerGate()
        val first = Any()
        val second = Any()
        assertTrue(gate.acquire(first))
        assertFalse(gate.acquire(second))
        assertFalse(gate.release(second))
        assertFalse(gate.acquire(second))
        assertTrue(gate.release(first))
        assertTrue(gate.acquire(second))
        assertFalse(gate.release(first))
        assertFalse(gate.acquire(first))
        assertTrue(gate.release(second))
    }

    @Test
    fun concurrentOpenHasExactlyOneOwner() {
        val gate = CaptureOwnerGate()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val accepted = AtomicInteger()
        repeat(8) {
            Thread {
                start.await()
                if (gate.acquire(Any())) accepted.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, accepted.get())
    }
}
