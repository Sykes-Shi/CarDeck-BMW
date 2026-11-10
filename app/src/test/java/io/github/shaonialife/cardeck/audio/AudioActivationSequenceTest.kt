package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioActivationSequenceTest {
    @Test
    fun acceptedRecordPrecedesCaptureAndSyncExactlyOnce() {
        val events = mutableListOf("SETUP accepted")
        val activated =
            AudioActivationSequence.afterSetup(
                isCurrent = { true },
                record = {
                    events.add("RECORD accepted")
                    true
                },
                start = {
                    events.add("mute/capture/sync")
                    true
                },
                stop = { events.add("stop") },
            )

        assertTrue(activated)
        assertEquals(listOf("SETUP accepted", "RECORD accepted", "mute/capture/sync"), events)
    }

    @Test
    fun rejectedRecordDoesNotTouchCaptureOrVolumeRestoration() {
        val events = mutableListOf<String>()
        val activated =
            AudioActivationSequence.afterSetup(
                isCurrent = { true },
                record = {
                    events.add("RECORD rejected")
                    false
                },
                start = {
                    events.add("start")
                    true
                },
                stop = { events.add("restore volume") },
            )

        assertFalse(activated)
        assertEquals(listOf("RECORD rejected"), events)
    }

    @Test
    fun failedRecordRequestLeavesUnstartedSenderUntouched() {
        val failure = IllegalStateException("RECORD connection lost")
        val events = mutableListOf<String>()
        var caught: Exception? = null
        try {
            AudioActivationSequence.afterSetup(
                isCurrent = { true },
                record = { throw failure },
                start = {
                    events.add("start")
                    true
                },
                stop = { events.add("restore volume") },
            )
        } catch (error: Exception) {
            caught = error
        }
        assertSame(failure, caught)
        assertTrue(events.isEmpty())
    }

    @Test
    fun retiredSessionDoesNotIssueRecord() {
        val events = mutableListOf<String>()
        assertFalse(
            AudioActivationSequence.afterSetup(
                isCurrent = { false },
                record = {
                    events.add("RECORD")
                    true
                },
                start = {
                    events.add("start")
                    true
                },
                stop = { events.add("stop") },
            ),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun retirementWhileRecordIsPendingDoesNotStartOrStopUnusedSender() {
        var current = true
        val events = mutableListOf<String>()
        assertFalse(
            AudioActivationSequence.afterSetup(
                isCurrent = { current },
                record = {
                    events.add("RECORD")
                    current = false
                    true
                },
                start = {
                    events.add("start")
                    true
                },
                stop = { events.add("stop") },
            ),
        )
        assertEquals(listOf("RECORD"), events)
    }

    @Test
    fun retirementDuringCaptureStartupCleansThisAttempt() {
        var current = true
        val events = mutableListOf<String>()
        assertFalse(
            AudioActivationSequence.afterSetup(
                isCurrent = { current },
                record = {
                    events.add("RECORD")
                    true
                },
                start = {
                    events.add("start")
                    current = false
                    true
                },
                stop = { events.add("stop") },
            ),
        )
        assertEquals(listOf("RECORD", "start", "stop"), events)
    }

    @Test
    fun partialStartupFailureCleansSenderWithoutAnotherRecord() {
        val events = mutableListOf<String>()
        assertFalse(
            AudioActivationSequence.afterSetup(
                isCurrent = { true },
                record = {
                    events.add("RECORD")
                    true
                },
                start = {
                    events.add("start failed")
                    false
                },
                stop = { events.add("stop") },
            ),
        )
        assertEquals(listOf("RECORD", "start failed", "stop"), events)
    }

    @Test
    fun thrownStartupFailureCleansSenderAndPreservesCause() {
        val failure = IllegalStateException("capture failed")
        val events = mutableListOf<String>()
        var caught: Exception? = null
        try {
            AudioActivationSequence.afterSetup(
                isCurrent = { true },
                record = {
                    events.add("RECORD")
                    true
                },
                start = {
                    events.add("start threw")
                    throw failure
                },
                stop = { events.add("stop") },
            )
        } catch (error: Exception) {
            caught = error
        }
        assertSame(failure, caught)
        assertEquals(listOf("RECORD", "start threw", "stop"), events)
    }
}
