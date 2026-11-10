package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverAudioClockTest {
    @Test fun refusedSyncPortGetsOnlyOneNewAnchorPerResumedSegment() {
        val cadence = AudioSyncCadence()
        assertEquals(true, cadence.allow(0L))
        assertEquals(true, cadence.allow(0L))
        cadence.refused(0L)
        assertEquals(false, cadence.allow(0L))
        assertEquals(true, cadence.allow(2L))
        assertEquals(false, cadence.allow(2L))
        assertEquals(true, cadence.allow(4L))
        cadence.refused(4L)
        assertEquals(false, cadence.allow(4L))
    }
    @Test fun observesTheLongBmwCallButRejectsAnUnexplainedGapAndExtremeCounters() {
        val clock = ReceiverAudioClock()
        val start = 504_856_427_000L
        val end = 571_209_398_000L
        fun replay(call: Boolean): ReceiverAudioClock.Difference? {
            clock.start(7, 48_000)
            clock.observe(7, start, 5_197_920)
            if (call) clock.noteBorrow(true)
            clock.observe(7, start, 5_197_920)
            if (call) clock.noteBorrow(false)
            return clock.observe(7, end, 5_325_488)
        }
        assertNull(replay(false))
        val gap = replay(true)!!
        assertEquals(true, gap.borrowedInWindow)
        assertEquals(63_695L, gap.deficitMs)
        clock.noteBorrow(true)
        assertNull(clock.observe(7, Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun identifiesTheTwoObservedBmwInterruptionsWithoutMixingStreams() {
        val clock = ReceiverAudioClock().apply { start(7, 48_000) }
        fun feedback(raw: Long, samples: Long) = clock.observe(7, raw, samples)
        assertNull(feedback(264_442_883_000, 7_026_784))
        clock.noteBorrow(true)
        assertNull(feedback(264_442_883_000, 7_026_784))
        clock.noteBorrow(false)
        assertNull(clock.observe(8, 270_000_000_000, 100))
        val first = feedback(271_730_398_000, 7_220_912)!!
        assertEquals(true, first.borrowedInWindow)
        assertEquals(3_243, first.deficitMs.toInt())
        assertNull(feedback(276_743_371_000, 7_461_552))
        // Use a fresh baseline for the field extract 45 s later.
        assertNull(feedback(315_848_396_000, 9_338_544))
        clock.noteBorrow(true)
        clock.noteBorrow(false)
        val second = feedback(322_270_608_000, 9_467_568)!!
        assertEquals(true, second.borrowedInWindow)
        assertEquals(3_734, second.deficitMs.toInt())
    }

    @Test fun distinguishesNormalPlaybackACounterResetAndAPauseWithoutBorrow() {
        val clock = ReceiverAudioClock().apply { start(8, 44_100) }
        assertNull(clock.observe(8, 0, 0))
        assertNull(clock.observe(8, 1_000_000_000, 44_100))
        val gap = clock.observe(8, 2_000_000_000, 44_100)!!
        assertEquals(false, gap.borrowedInWindow)
        assertEquals(1_000, gap.deficitMs.toInt())
        assertNull(clock.observe(8, 5, 0))
        assertNull(clock.observe(8, 1_000_000_005, 44_100))
    }
}
