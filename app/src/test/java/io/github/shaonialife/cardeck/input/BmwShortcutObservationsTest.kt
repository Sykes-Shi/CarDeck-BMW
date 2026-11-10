package io.github.shaonialife.cardeck.input

import io.github.shaonialife.cardeck.launcher.CarShortcut
import org.junit.Assert.*
import org.junit.Test

class BmwShortcutObservationsTest {
    @Test fun differentScreenRequestsCanBeComparedWithoutRevealingTextOrAssigningAPage() {
        val labels = BmwShortcutObservations()
        assertEquals("mode_reason_1", labels.modeReason("one reason"))
        assertEquals("mode_reason_2", labels.modeReason("two reason"))
        assertEquals("mode_reason_1", labels.modeReason("one reason"))
        assertEquals("ui_target_1", labels.uiTarget("MAPS:"))
        assertEquals("ui_target_1", labels.uiTarget("maps:"))
        assertNull(labels.uiTarget("maps:/somewhere?destination=private"))
        assertNull(labels.modeReason("bad\ntext"))
    }

    @Test fun labelSpaceIsBoundedAndResetsWithNewConnection() {
        val first = BmwShortcutObservations()
        (1..16).forEach { assertEquals("mode_reason_$it", first.modeReason("test $it")) }
        assertNull(first.modeReason("extra"))
        assertEquals("mode_reason_4", first.modeReason("test 4"))
        assertEquals("mode_reason_1", BmwShortcutObservations().modeReason("extra"))
    }

    @Test fun nativeScreenAndRotaryCanBeCorrelatedWithAPressButNeverBound() {
        val observed = mutableListOf<String>()
        val samples = BmwShortcutObservations()
        val borrow = mapOf("type" to "changeModes", "params" to mapOf("reasonStr" to "native camera",
            "resources" to listOf(mapOf("resourceID" to 1, "transferType" to 3))))
        samples.record(borrow, false, observed::add)
        samples.record(mapOf("type" to "hidSendReport"), true, observed::add)
        assertEquals(listOf("changemodes", "mode_reason_1", "screen_borrow", "hid_rotary"), observed)
        assertNull(BmwShortcutTarget.from(borrow))
    }

    @Test fun aNativeScreenTakeLikeTheEightFieldSamplesIsDiagnosticOnly() {
        val p = ShortcutCalibration(emptyMap(), emptySet())
        val labels = BmwShortcutObservations()
        val take = mapOf("type" to "changeModes", "params" to mapOf("reasonStr" to "same private mode reason",
            "resources" to listOf(mapOf("resourceID" to 1, "transferType" to 1, "transferPriority" to 500,
                "takeConstraint" to 500, "borrowConstraint" to 100))))
        for (key in listOf(CarShortcut.MAP, CarShortcut.NAV)) {
            p.start(key, 0)
            labels.record(take, false) { p.observe(it, 1_000) }
            p.confirm(8_100)
            labels.record(take, false) { p.observe(it, 9_000) }
            p.tick(16_200)
            assertEquals(setOf("changemodes", "mode_reason_1", "screen_take"), p.state?.observed)
            assertEquals(ShortcutCalibration.Phase.DIAGNOSTIC, p.state?.phase)
            assertNull(p.mapping(key))
        }
        assertNull(BmwShortcutTarget.from(take))
    }
}
