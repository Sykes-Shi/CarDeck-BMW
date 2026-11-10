package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionInternetRouteTest {
    private val car = ProjectionInternetRoute.Candidate("car", false, false, false)
    private val cellular = ProjectionInternetRoute.Candidate("cell", true, false, true)
    private val vpn = ProjectionInternetRoute.Candidate("vpn", true, true, false)
    private val wifi = ProjectionInternetRoute.Candidate("homeWifi", true, false, false)

    @Test fun sharedCarHotspotFallsBackToOrdinaryValidatedCellularForThisUid() {
        assertEquals("cell", ProjectionInternetRoute.select(car, "car") { listOf(car, cellular) })
        assertEquals("cell", ProjectionInternetRoute.select(car.copy(validated = true), "car") { listOf(cellular) })
    }

    @Test fun assignedVpnIsKeptEvenWhenItReportsWifiAndCellularIsAvailable() {
        assertEquals("vpn", ProjectionInternetRoute.select(vpn, "car") { error("must not route around the VPN") })
        assertNull(ProjectionInternetRoute.select(vpn.copy(validated = false), "car") { listOf(cellular) })
    }

    @Test fun usableNonCarDefaultWinsIncludingWifiAndAnExcludedUidWithAVisibleVpn() {
        assertEquals("homeWifi", ProjectionInternetRoute.select(wifi, "car") { error("ordinary Wi-Fi has Internet") })
        assertEquals("cell", ProjectionInternetRoute.select(cellular, "car") { listOf(vpn) })
    }

    @Test fun offlineBlockedRestrictedAndImsOnlyRoutesCannotBeUsedAsFallbacks() {
        assertNull(ProjectionInternetRoute.select(null, "car") { listOf(cellular) })
        val unavailable = listOf(cellular.copy(validated = false), cellular.copy(ordinaryCellular = false), vpn, car)
        assertNull(ProjectionInternetRoute.select(car, "car") { unavailable })
    }

    @Test fun exceptionalLyricsRetryNeedsTheSameLiveVpnAndAWritablePublicCellularRoute() {
        fun permits(current: ProjectionInternetRoute.Candidate<String>?, target: ProjectionInternetRoute.Candidate<String>?,
                    carHandle: String? = "car") = ProjectionInternetRoute.permitsLyricsRetry("vpn", current, carHandle, target)
        assertTrue(permits(vpn, cellular))
        assertFalse(permits(vpn.copy(network = "replacement"), cellular))
        assertFalse(permits(null, cellular))
        assertFalse(permits(vpn.copy(validated = false), cellular))
        assertFalse(permits(cellular, cellular))
        assertFalse(permits(vpn, cellular, null))
        assertFalse(permits(vpn, cellular, "cell"))
        assertFalse(permits(vpn, null))
        assertFalse(permits(vpn, cellular.copy(validated = false)))
        assertFalse(permits(vpn, cellular.copy(ordinaryCellular = false)))
        assertFalse(permits(vpn, cellular.copy(vpn = true)))
        assertFalse(permits(vpn, wifi))
        assertFalse(permits(vpn, vpn))
    }
}
