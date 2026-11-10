package io.github.shaonialife.cardeck.input

import io.github.shaonialife.cardeck.launcher.CarShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutCalibrationTest {
    private val a = "ui:" + "a".repeat(64)
    private val b = "hid:" + "b".repeat(64)
    private fun policy() = ShortcutCalibration(emptyMap(), emptySet())

    @Test fun mapsContextCanOpenNavSearchOnlyAfterTwoExplicitObservations() {
        val p = policy()
        assertEquals(CarShortcut.MAP, p.offer(a, 0, CarShortcut.MAP))
        p.start(CarShortcut.NAV, 1_000)
        assertNull(p.offer(a, 1_100, CarShortcut.MAP))
        p.confirm(9_001)
        assertNull(p.offer(a, 9_100, CarShortcut.MAP))
        p.tick(17_100)
        assertEquals(ShortcutCalibration.Phase.SAVED, p.state?.phase)
        val nextConnection = ShortcutCalibration(mapOf(CarShortcut.NAV to a), p.conflicts())
        assertEquals(CarShortcut.NAV, nextConnection.offer(a, 18_000, CarShortcut.MAP))
        assertEquals(CarShortcut.MEDIA, nextConnection.offer(b, 18_100, CarShortcut.MEDIA))
    }

    @Test fun identicalLearnedMapAndNavSignalsAreStillBlockedInEitherOrder() {
        for ((previous, next) in listOf(CarShortcut.NAV to CarShortcut.MAP, CarShortcut.MAP to CarShortcut.NAV)) {
            val p = ShortcutCalibration(mapOf(previous to a, CarShortcut.MEDIA to b), emptySet())
            p.start(next, 0)
            p.offer(a, 1, CarShortcut.MAP)
            p.confirm(8_000)
            p.offer(a, 8_001, CarShortcut.MAP)
            p.tick(16_000)
            assertEquals(ShortcutCalibration.Phase.SHARED, p.state?.phase)
            assertNull(p.offer(a, 18_000, CarShortcut.MAP))
            assertEquals(CarShortcut.MEDIA, p.offer(b, 19_000, CarShortcut.MEDIA))
        }
    }

    @Test fun twoIndependentlyArmedIdenticalWindowsAreNeededBeforeRouting() {
        val p = policy()
        p.start(CarShortcut.MEDIA, 0)
        assertNull(p.offer(a, 1))
        assertNull(p.offer(a, 200))
        p.tick(8_000)
        assertEquals(ShortcutCalibration.Phase.CONFIRM, p.state?.phase)
        assertNull(p.offer(a, 8_500))
        assertNull(p.mapping(CarShortcut.MEDIA))
        p.confirm(9_000)
        assertNull(p.offer(a, 10_000))
        p.tick(17_000)
        assertEquals(ShortcutCalibration.Phase.SAVED, p.state?.phase)
        assertEquals(CarShortcut.MEDIA, p.offer(a, 19_000))
    }

    @Test fun unsupportedPayloadsAndAnExpiredConfirmationCannotTeachAnything() {
        val p = policy()
        p.start(CarShortcut.MAP, 0)
        for (s in listOf("", "requestUI", "changeModes", "https://foo.example/a", "hid:01", a.uppercase())) assertNull(p.offer(s, 1, CarShortcut.MEDIA))
        p.tick(8_000)
        assertEquals(ShortcutCalibration.Phase.TIMEOUT, p.state?.phase)
        p.start(CarShortcut.MAP, 10_000)
        p.offer(a, 11_000)
        p.confirm(49_000)
        assertEquals(ShortcutCalibration.Phase.TIMEOUT, p.state?.phase)
        assertNull(p.mapping(CarShortcut.MAP))
    }

    @Test fun mixedSignalsDifferentSignalsAndCancelledAttemptsNeverBind() {
        val p = policy()
        p.start(CarShortcut.OPTION, 0)
        p.offer(a, 500)
        p.offer(b, 1_000)
        p.tick(8_000)
        assertEquals(ShortcutCalibration.Phase.AMBIGUOUS, p.state?.phase)
        p.start(CarShortcut.OPTION, 9_000)
        p.offer(a, 10_000)
        p.confirm(18_000)
        p.offer(b, 18_500)
        p.tick(26_000)
        assertEquals(ShortcutCalibration.Phase.DIFFERENT, p.state?.phase)
        p.start(CarShortcut.OPTION, 27_000)
        p.offer(a, 27_001)
        p.cancel()
        assertNull(p.offer(a, 40_000))
        assertNull(p.mapping(CarShortcut.OPTION))
    }

    @Test fun secondKeySharingAnExistingSignalDisablesItsOldAndDefaultMappings() {
        val p = ShortcutCalibration(mapOf(CarShortcut.MEDIA to a), emptySet())
        p.start(CarShortcut.MAP, 0)
        assertNull(p.offer(a, 1, CarShortcut.MEDIA))
        p.confirm(9_000)
        assertNull(p.offer(a, 10_000, CarShortcut.MEDIA))
        p.tick(17_000)
        assertEquals(ShortcutCalibration.Phase.SHARED, p.state?.phase)
        assertTrue(a in p.conflicts())
        assertNull(p.mapping(CarShortcut.MEDIA))
        assertNull(p.offer(a, 18_000, CarShortcut.MEDIA))
        val restored = ShortcutCalibration(emptyMap(), p.conflicts())
        assertNull(restored.offer(a, 0, CarShortcut.MEDIA))
        restored.reset()
        assertEquals(CarShortcut.MEDIA, restored.offer(a, 1, CarShortcut.MEDIA))
    }

    @Test fun persistedMappingConflictingWithNewDefaultCannotHijackOrTriggerEitherAction() {
        val p = ShortcutCalibration(mapOf(CarShortcut.MAP to a), emptySet())
        assertNull(p.offer(a, 0, CarShortcut.OPTION))
        assertFalse(p.conflicts().isEmpty())
        assertNull(p.mapping(CarShortcut.MAP))
        assertEquals(CarShortcut.OPTION, p.offer(b, 1, CarShortcut.OPTION))
    }

    @Test fun diagnosticObservationsArePrivateBoundedAndNeverBind() {
        val logs = mutableListOf<String>()
        val p = ShortcutCalibration(emptyMap(), emptySet(), logs::add)
        p.start(CarShortcut.MAP, 0)
        repeat(100) { p.observe("requestUI", 200) }
        p.observe("mode_reason_16", 250)
        p.observe("https://private.example/key", 300)
        p.observe("ui_target_17", 301)
        p.observe("screen_borrow", 400)
        assertTrue(p.observing(7_999))
        assertFalse(p.observing(8_000))
        p.observe("screen_return", 8_010)
        p.tick(8_020)
        assertEquals(4, logs.count { "event=observed" in it })
        assertEquals(ShortcutCalibration.Phase.CONFIRM, p.state?.phase)
        assertTrue(p.state?.diagnosticOnly == true)
        p.confirm(9_000)
        p.observe("changemodes", 9_100)
        p.observe("mode_reason_16", 9_101)
        p.tick(17_000)
        assertEquals(ShortcutCalibration.Phase.DIAGNOSTIC, p.state?.phase)
        assertTrue(logs.any { "key=MAP round=1 event=result status=CONFIRM candidate=false observations=4" in it })
        assertTrue(logs.any { "key=MAP round=2 event=result status=DIAGNOSTIC observations=2" in it })
        assertFalse(logs.any { "private" in it || "ui_target_17" in it || "screen_return" in it })
        assertNull(p.mapping(CarShortcut.MAP))
    }

    @Test fun eachArmedRoundCarriesItsOwnKeyAndFingerprintPrefixAndLogsDisconnectOnce() {
        val logs = mutableListOf<String>()
        val p = ShortcutCalibration(emptyMap(), emptySet(), logs::add)
        p.start(CarShortcut.OPTION, 0)
        p.observe("ui_target_2", 50)
        repeat(9) { p.offer(a, 60) }
        p.confirm(8_500)
        p.observe("ui_target_2", 8_501)
        p.offer(b, 8_550)
        p.cancel(disconnected = true)
        p.cancel()
        assertEquals(2, logs.count { "event=candidate" in it })
        assertTrue(logs.any { "key=OPTION round=2 event=observed kind=ui_target_2" in it })
        assertTrue(logs.any { "key=OPTION round=2 event=disconnected" in it })
        assertFalse(logs.any { a in it || b in it })
    }
}
