package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmCaptureStatsTest {
    @Test
    fun noReadsRemainUnknownInsteadOfBeingReportedAsSilence() {
        val stats = PcmCaptureStats()
        assertNull(stats.snapshot(0).current)
        stats.reset(100)
        val snapshot = stats.snapshot(6_100)
        assertNull(snapshot.lastReadAgeMs)
        assertEquals(0L, snapshot.current!!.readCalls)
        assertTrue(snapshot.current.fields().contains("nonZeroPercent=unknown"))
        assertNull(snapshot.latestComplete)
    }

    @Test
    fun silenceCountsActualSamplesAcrossEveryRead() {
        val stats = PcmCaptureStats()
        stats.reset(100)
        assertNull(stats.recordRead(ByteArray(8), 8, 8, 120))
        val complete = stats.recordRead(ByteArray(8), 8, 8, 5_100)!!
        assertEquals(2L, complete.readCalls)
        assertEquals(2L, complete.pcmReads)
        assertEquals(16L, complete.pcmBytes)
        assertEquals(8L, complete.sampleValues)
        assertEquals(0L, complete.nonZeroValues)
        assertEquals(0, complete.peak)
        assertTrue(complete.fields().contains("nonZeroPercent=0"))
    }

    @Test
    fun signedSixteenBitPeakIncludesNegativeFullScale() {
        val stats = PcmCaptureStats(1)
        stats.reset(0)
        val pcm = byteArrayOf(0, 0, 1, 0, -1, -1, 0, -128)
        val complete = stats.recordRead(pcm, 8, 8, 1)!!
        assertEquals(4L, complete.sampleValues)
        assertEquals(3L, complete.nonZeroValues)
        assertEquals(32768, complete.peak)
        assertTrue(complete.fields().contains("nonZeroPercent=75"))
    }

    @Test
    fun shortReadCountsOnlyReturnedBytesAndCompleteSampleValues() {
        val stats = PcmCaptureStats()
        stats.reset(0)
        val reusedBuffer = byteArrayOf(1, 0, 127, 127, 127, 127, 127, 127)
        stats.recordRead(reusedBuffer, 3, 8, 10)
        val window = stats.snapshot(11).current!!
        assertEquals(3L, window.pcmBytes)
        assertEquals(1L, window.sampleValues)
        assertEquals(1L, window.nonZeroValues)
        assertEquals(1, window.peak)
        assertEquals(1L, window.shortReads)
    }

    @Test
    fun encodedPacketNumbersAreIrrelevantToWallTimeWindows() {
        val stats = PcmCaptureStats()
        stats.reset(100)
        assertNull(stats.recordRead(byteArrayOf(1, 0), 2, 2, 5_099))
        val complete = stats.recordRead(byteArrayOf(2, 0), 2, 2, 5_101)!!
        assertEquals(100L, complete.startElapsedMs)
        assertEquals(5_101L, complete.endElapsedMs)
        assertEquals(2L, complete.pcmReads)
        assertEquals(2, complete.peak)
        val current = stats.snapshot(5_102).current!!
        assertEquals(5_101L, current.startElapsedMs)
        assertEquals(0L, current.pcmReads)
    }

    @Test
    fun emptyAndFailedReadsNeverBecomeSilentPcmSamples() {
        val stats = PcmCaptureStats(10)
        stats.reset(0)
        stats.recordRead(ByteArray(4), -3, 4, 1)
        val complete = stats.recordRead(ByteArray(4), 0, 4, 10)!!
        assertEquals(2L, complete.readCalls)
        assertEquals(1L, complete.errorReads)
        assertEquals(1L, complete.emptyReads)
        assertEquals(0L, complete.pcmReads)
        assertEquals(0L, complete.sampleValues)
        assertTrue(complete.fields().contains("nonZeroPercent=unknown"))
        assertNull(stats.snapshot(20).lastReadAgeMs)
    }

    @Test
    fun exportSeesCurrentPartialAndLatestCompletedWindowWithAge() {
        val stats = PcmCaptureStats(10)
        stats.reset(0)
        val completed = stats.recordRead(byteArrayOf(5, 0), 2, 2, 10)!!
        stats.recordRead(byteArrayOf(0, 0), 2, 2, 15)
        val snapshot = stats.snapshot(200)
        assertEquals(completed, snapshot.latestComplete)
        assertEquals(185L, snapshot.lastReadAgeMs)
        assertEquals(1L, snapshot.current!!.pcmReads)
        assertEquals(0, snapshot.current.peak)
        assertEquals(15L, snapshot.current.lastReadElapsedMs)
        assertEquals(200L, snapshot.current.endElapsedMs)
    }

    @Test
    fun aNewCaptureRunDiscardsPreviousStatisticsAndAdvancesIdentity() {
        val stats = PcmCaptureStats(10)
        stats.reset(0)
        stats.recordRead(byteArrayOf(7, 0), 2, 2, 10)
        val old = stats.snapshot(10)
        stats.reset(100)
        val fresh = stats.snapshot(110)
        assertEquals(old.runId + 1, fresh.runId)
        assertEquals(100L, fresh.startedAtElapsedMs)
        assertNull(fresh.latestComplete)
        assertNull(fresh.lastReadAgeMs)
        assertEquals(0L, fresh.current!!.pcmBytes)
        assertEquals(7, old.latestComplete!!.peak)
    }

    @Test
    fun reusedBufferAndSubsequentReadsCannotChangeExportedSnapshot() {
        val stats = PcmCaptureStats()
        stats.reset(0)
        val pcm = byteArrayOf(3, 0)
        stats.recordRead(pcm, 2, 2, 1)
        val first = stats.snapshot(2)
        pcm.fill(0)
        stats.recordRead(pcm, 2, 2, 3)
        assertEquals(1L, first.current!!.pcmReads)
        assertEquals(1L, first.current.nonZeroValues)
        assertEquals(3, first.current.peak)
        assertEquals(2L, stats.snapshot(4).current!!.pcmReads)
    }

    @Test
    fun simultaneousReadStatisticsAndSnapshotsKeepWholeCounters() {
        val stats = PcmCaptureStats()
        stats.reset(0)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val workers = List(2) {
            Thread {
                try {
                    repeat(100) {
                        stats.recordRead(byteArrayOf(1, 0, 0, 0), 4, 4, 1)
                        val window = stats.snapshot(2).current!!
                        assertEquals(window.pcmReads * 4, window.pcmBytes)
                        assertEquals(window.pcmReads * 2, window.sampleValues)
                        assertEquals(window.pcmReads, window.nonZeroValues)
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(5_000) }
        workers.forEach { assertFalse(it.isAlive) }
        failure.get()?.let { throw AssertionError("Concurrent snapshot failed", it) }
        assertEquals(200L, stats.snapshot(3).current!!.pcmReads)
    }
}
