package io.github.shaonialife.cardeck.input

import org.junit.Assert.*
import org.junit.Test

class SteeringVoiceKeyTest {
    @Test fun fieldSequenceLaunchesOnceDespiteThreeWarmupsAndThreeReleases() {
        val gate = SteeringVoiceKey()
        val recorded = listOf(0 to 1, 747 to 2, 1793 to 3, 2457 to 1, 2458 to 3, 2474 to 1, 2561 to 3)
        assertEquals(listOf(747), recorded.filter { (t, a) -> gate.onAction(a.toLong(), t.toLong()) }.map { it.first })
    }

    @Test fun releaseRearmsAndBounceOrMalformedActionsNeverLaunch() {
        val gate = SteeringVoiceKey()
        assertFalse(gate.onAction(2.5, 0))
        assertFalse(gate.onAction("2", 0))
        assertTrue(gate.onAction(2, 100))
        assertFalse(gate.onAction(2, 800))
        assertFalse(gate.onAction(3, 1000))
        assertTrue(gate.onAction(2, 1100))
        assertFalse(gate.onAction(3, 1150))
        assertFalse(gate.onAction(2, 1250))
        assertTrue(gate.onAction(2, 20_000))
    }

    @Test fun dialProbeLabelsOnlyAnExplicitTelephonePageAndNeverReportsTheNumber() {
        val probe = SteeringDialProbe()
        val sample = probe.start(4, 1000)
        fun ui(url: String) = mapOf("type" to "requestUI", "params" to mapOf("url" to url))
        assertFalse(probe.record(4, 1100, ui("mobilephone:")))
        assertFalse(probe.record(4, 1200, ui("tel:+01234567")))
        assertFalse(probe.record(4, 1300, ui("mobilephone:")))
        assertEquals(listOf(SteeringDialProbe.Event.PHONE, SteeringDialProbe.Event.OTHER_UI), probe.finish(sample))
    }

    @Test fun oldWindowAndOtherSessionsCannotConsumeVoicePress() {
        val probe = SteeringDialProbe()
        val cmd = mapOf("type" to "requestSiri", "params" to mapOf("siriAction" to 2))
        val old = probe.start(4, 0)
        val active = probe.start(5, 1000)
        assertFalse(probe.record(4, 1100, cmd))
        assertTrue(probe.record(5, 1200, cmd))
        assertNull(probe.finish(old))
        assertFalse(probe.record(5, 9000, cmd))
        assertEquals(listOf(SteeringDialProbe.Event.VOICE), probe.finish(active))
        assertFalse(probe.record(5, 1300, cmd))
    }

    @Test fun mixedHidReportsDuringDialSamplingAreAttributedToTheirCurrentDevice() {
        val probe = SteeringDialProbe()
        val touch = ("05 0d 09 04 a1 01 05 0d 09 22 a1 02 05 0d 09 33 15 00 25 01 75 01 95 01 81 02 " +
            "75 07 95 01 81 01 05 01 09 30 15 00 26 80 07 75 10 95 01 81 02 09 31 15 00 26 d0 02 " +
            "75 10 95 01 81 02 c0 c0").split(" ").map { it.toInt(16).toByte() }.toByteArray()
        fun report(id: Int) = mapOf("type" to "hidSendReport", "uuid" to id, "hidReport" to byteArrayOf(1, 13, 7, 26, 2))
        val sample = probe.start(6, 0)
        val descriptors = mapOf("1" to touch, "5" to byteArrayOf(5, 1))
        val knownRotaries = setOf("5")
        // A touch report was observed in the 14:57:26 dial sample. It cannot identify a phone key.
        assertFalse(probe.record(6, 100, report(1), descriptors, knownRotaries))
        assertFalse(probe.record(6, 110, report(1), descriptors, knownRotaries))
        assertFalse(probe.record(6, 120, report(5), descriptors, knownRotaries))
        assertFalse(probe.record(6, 130, report(7), descriptors, knownRotaries))
        assertEquals(listOf(SteeringDialProbe.Event.TOUCH, SteeringDialProbe.Event.ROTARY, SteeringDialProbe.Event.UNKNOWN_HID),
            probe.finish(sample))

        val next = probe.start(7, 1000)
        assertFalse(probe.record(7, 1100, report(1))) // Same UUID and bytes, new setup without a descriptor.
        assertEquals(listOf(SteeringDialProbe.Event.UNKNOWN_HID), probe.finish(next))
    }
}
