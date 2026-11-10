package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSendStatsTest {
    private fun ms(value: Long) = value * 1_000_000

    @Test
    fun emptyStatsDoNotInventSilenceOrPacketTiming() {
        val stats = AudioSendStats()
        assertNull(stats.snapshot(0).current)
        stats.reset(0)
        assertNull(stats.takeWindow(ms(6_000)))
        assertNull(stats.snapshot(ms(6_000)).firstNonZero)
        assertNull(stats.snapshot(ms(6_000)).lastSuccessfulSendElapsedNs)
        assertNull(stats.snapshot(ms(6_000)).current!!.sendGapMinNs)
    }

    @Test
    fun firstNonZeroUsesReadCompletionAndNeverRetainsTheBuffer() {
        val stats = AudioSendStats()
        stats.reset(0)
        assertNull(stats.recordRead(ByteArray(4), 4, ms(1), ms(2)))
        val pcm = byteArrayOf(0, 0, 0, -128)
        val found = stats.recordRead(pcm, 4, ms(3), ms(5))!!
        pcm.fill(0)
        assertEquals(ms(3), found.readStartedElapsedNs)
        assertEquals(ms(5), found.observedElapsedNs)
        assertEquals(2L, found.readNumber)
        assertNull(stats.recordRead(byteArrayOf(1, 0), 2, ms(6), ms(7)))
        assertEquals(found, stats.snapshot(ms(8)).firstNonZero)
    }

    @Test
    fun invalidEmptyOddTailAndUnreadBytesCannotBecomeNonZeroEvidence() {
        val stats = AudioSendStats()
        val reused = byteArrayOf(0, 0, 5, 7)
        assertNull(stats.recordRead(reused, -3, 0, ms(1)))
        assertNull(stats.recordRead(reused, 0, ms(1), ms(2)))
        assertNull(stats.recordRead(reused, 3, ms(2), ms(3)))
        val snapshot = stats.snapshot(ms(4))
        assertNull(snapshot.firstNonZero)
        assertEquals(3L, snapshot.current!!.readBytes)
        assertEquals(1L, snapshot.current.readFailures)
    }

    @Test
    fun timingTracksEachStageAndEncodingBatchWithoutAssumingOneOutput() {
        val stats = AudioSendStats()
        stats.reset(0)
        stats.recordRead(ByteArray(4), 4, ms(1), ms(22))
        stats.recordEncode(ms(22), ms(25), 0)
        stats.recordEncode(ms(26), ms(30), 3)
        stats.recordEncode(ms(31), ms(40), 0, failed = true)
        val window = stats.snapshot(ms(41)).current!!
        assertEquals(ms(21), window.readMaxNs)
        assertEquals(ms(9), window.encodeMaxNs)
        assertEquals(3, window.encodeMaxBatch)
        assertEquals(3L, window.encodedFrames)
        assertEquals(1L, window.encodeEmpty)
        assertEquals(1L, window.encodeFailures)
    }

    @Test
    fun onlySuccessfulSendsAdvanceActualPacketsBytesAndSamples() {
        val stats = AudioSendStats()
        stats.reset(0)
        stats.recordSend(ms(1), ms(2), 512, 548, 1024, true)
        stats.recordSend(ms(3), ms(8), 700, 736, 1024, false)
        stats.recordSend(ms(20), ms(22), 500, 536, 1024, true)
        val snapshot = stats.snapshot(ms(25))
        val window = snapshot.current!!
        assertEquals(3L, window.sendCalls)
        assertEquals(1L, window.sendFailures)
        assertEquals(ms(5), window.sendMaxNs)
        assertEquals(2L, window.packetsSent)
        assertEquals(1084L, window.wireBytesSent)
        assertEquals(2048L, window.sampleFramesSent)
        assertEquals(500, window.payloadMinBytes)
        assertEquals(512, window.payloadMaxBytes)
        assertEquals(536, window.wireMinBytes)
        assertEquals(548, window.wireMaxBytes)
        assertEquals(ms(20), window.sendGapMinNs)
        assertEquals(2L, snapshot.totalPacketsSent)
        assertEquals(2048L, snapshot.totalSampleFramesSent)
    }

    @Test
    fun sendGapSpansWindowBoundaryAndIgnoresFailedSendAsAnchor() {
        val stats = AudioSendStats(ms(100))
        stats.recordSend(ms(1), ms(2), 1, 37, 352, true)
        stats.takeWindow(ms(101))!!
        stats.recordSend(ms(110), ms(111), 1, 37, 352, false)
        stats.recordSend(ms(120), ms(122), 1, 37, 352, true)
        val snapshot = stats.snapshot(ms(123))
        assertEquals(ms(120), snapshot.current!!.sendGapMaxNs)
        assertEquals(1L, snapshot.current.packetsSent)
        assertEquals(704L, snapshot.totalSampleFramesSent)
    }

    @Test
    fun periodicWindowWaitsForDeadlineAndKeepsAnImmutableSnapshot() {
        val stats = AudioSendStats()
        stats.recordRead(ByteArray(4), 4, 0, ms(1))
        assertNull(stats.takeWindow(ms(4_999)))
        val window = stats.takeWindow(ms(5_000))!!
        assertEquals(1L, window.readCalls)
        assertEquals(ms(5_000), window.endElapsedNs)
        stats.recordRead(ByteArray(4), 4, ms(5_001), ms(5_002))
        assertEquals(1L, window.readCalls)
        assertEquals(window, stats.snapshot(ms(5_003)).latestComplete)
        assertEquals(ms(5_000), stats.snapshot(ms(5_003)).current!!.startElapsedNs)
    }

    @Test
    fun stopFlushIncludesPartialWindowWithoutDuplicatingIt() {
        val stats = AudioSendStats()
        stats.recordRead(ByteArray(4), 4, 0, ms(10))
        assertEquals(1L, stats.takeWindow(ms(11), force = true)!!.readCalls)
        assertNull(stats.takeWindow(ms(12), force = true))
        // stop 与在途 send 并发时，最后完成的操作仍能被循环 finally 输出。
        stats.recordSend(ms(9), ms(13), 512, 548, 1024, false)
        val tail = stats.takeWindow(ms(14), force = true)!!
        assertEquals(0L, tail.readCalls)
        assertEquals(1L, tail.sendFailures)
    }

    @Test
    fun resetClearsObservationTotalsAndPreviousSessionGap() {
        val stats = AudioSendStats()
        stats.recordRead(byteArrayOf(1, 0), 2, 0, ms(1))
        stats.recordSend(ms(2), ms(3), 1, 37, 352, true)
        stats.takeWindow(ms(4), true)
        stats.reset(ms(100))
        stats.recordSend(ms(101), ms(102), 1, 37, 352, true)
        val snapshot = stats.snapshot(ms(103))
        assertNull(snapshot.firstNonZero)
        assertNull(snapshot.latestComplete)
        assertNull(snapshot.current!!.sendGapMaxNs)
        assertEquals(1L, snapshot.totalPacketsSent)
        assertEquals(352L, snapshot.totalSampleFramesSent)
    }

    @Test
    fun diagnosticTimeDeltasNeverBecomeNegative() {
        val stats = AudioSendStats()
        stats.recordRead(ByteArray(2), 2, 10, 9)
        stats.recordEncode(20, 19, 0)
        stats.recordSend(30, 29, 1, 37, 352, true)
        val window = stats.snapshot(28).current!!
        assertEquals(0L, window.readMaxNs)
        assertEquals(0L, window.encodeMaxNs)
        assertEquals(0L, window.sendMaxNs)
        assertTrue(stats.snapshot(28).fields().contains("lastSendAgeMs=0"))
    }

    @Test
    fun concurrentSnapshotAndObservationsKeepAllCounters() {
        val stats = AudioSendStats()
        stats.reset(0)
        val workers = (0..3).map { worker ->
            Thread {
                repeat(100) { index ->
                    val time = (worker * 100 + index).toLong()
                    stats.recordSend(time, time + 1, 512, 548, 1024, true)
                    stats.snapshot(time + 2)
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }
        val snapshot = stats.snapshot(1_000)
        assertEquals(400L, snapshot.totalPacketsSent)
        assertEquals(409_600L, snapshot.totalSampleFramesSent)
        assertEquals(400L, snapshot.current!!.packetsSent)
    }
}
