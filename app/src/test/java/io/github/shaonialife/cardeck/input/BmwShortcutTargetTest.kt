package io.github.shaonialife.cardeck.input

import io.github.shaonialife.cardeck.launcher.CarShortcut
import org.junit.Assert.*
import org.junit.Test

class BmwShortcutTargetTest {
    private fun target(url: Any?) = BmwShortcutTarget.from(mapOf("type" to "requestUI", "params" to mapOf("url" to url)))

    @Test fun explicitPlaybackAndMainMapHaveDistinctDefaults() {
        assertEquals(CarShortcut.MEDIA, target("nowplaying:")?.defaultPage)
        assertEquals(CarShortcut.MAP, target("MAPS:")?.defaultPage)
        assertNotEquals(target("nowplaying:")?.token, target("maps:")?.token)
    }

    @Test fun lengthsAreNotIdentitiesAndPhoneRequestsNeverOpenMusic() {
        assertEquals("bmw-option:".length, "nowplaying:".length)
        assertNull(target("bmw-option:")?.defaultPage)
        assertNull(target("mobilephone:"))
    }

    @Test fun onlyOpaqueStableTokensReachCalibration() {
        val option = requireNotNull(target("bmw-option:"))
        assertTrue(Regex("ui:[0-9a-f]{64}").matches(option.token))
        assertEquals(option.token, target("BMW-OPTION:")?.token)
        assertNull(option.defaultPage)
        assertNotEquals(option.token, target("bmw-key17:")?.token)
    }

    @Test fun qualifiedAndGenericRequestsCannotBeLearnedOrDispatched() {
        for (raw in listOf(null, 17, "", "maps:/car/instrumentcluster", "maps:?q=Station", "options:#info",
            "bmw-native:", "carplay:", "https:", "siri:", "messages:", "mailto:", "tel:", "resume:",
            "bmw-key99:\n", "mapſ:", "maps: ", "a".repeat(100))) assertNull(target(raw))
    }

    @Test fun modesOrQualifiedDisplayTargetsDoNotMasqueradeAsFunctionKeys() {
        assertNull(BmwShortcutTarget.from(mapOf("type" to "changeModes", "params" to mapOf("url" to "maps:"))))
        assertNull(BmwShortcutTarget.from(mapOf("type" to "requestUI", "url" to "maps:")))
        assertNull(BmwShortcutTarget.from(mapOf("type" to "requestUI", "params" to mapOf("url" to "maps:", "display" to "cluster"))))
    }

    @Test fun twoParkedSamplesPerButtonResolveOnlyTheirOwnPageAfterReconnecting() {
        val pages = mapOf("nowplaying:" to CarShortcut.MEDIA, "maps:" to CarShortcut.MAP, "bmw-option:" to CarShortcut.OPTION, "bmw-nav:" to CarShortcut.NAV)
        val calibration = ShortcutCalibration(emptyMap(), emptySet())
        var time = 10_000L
        for ((url, button) in pages) {
            val signal = requireNotNull(target(url))
            calibration.start(button, time)
            assertNull(calibration.offer(signal.token, time + 100, signal.defaultPage))
            time += ShortcutCalibration.WINDOW_MS
            calibration.confirm(time)
            assertNull(calibration.offer(signal.token, time + 100, signal.defaultPage))
            time += ShortcutCalibration.WINDOW_MS
            calibration.tick(time)
            assertEquals(ShortcutCalibration.Phase.SAVED, calibration.state?.phase)
        }
        val saved = pages.values.associateWith { requireNotNull(calibration.mapping(it)) }
        val nextSession = ShortcutCalibration(saved, emptySet())
        for ((url, page) in pages) {
            val signal = requireNotNull(target(url))
            assertEquals(page, nextSession.offer(signal.token, time, signal.defaultPage))
        }
    }
}
