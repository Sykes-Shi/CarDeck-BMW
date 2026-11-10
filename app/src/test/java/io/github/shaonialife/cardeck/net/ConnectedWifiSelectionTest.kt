package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedWifiSelectionTest {
    private val directCell = ConnectedWifiSelection.Upstream(2, true, false, false, true)
    private val tunnel = ConnectedWifiSelection.Upstream(3, true, true, false, true)

    @Test
    fun provisionalCellularVpnDoesNotSkipThePrivateWifiRequest() {
        val carWifi = ConnectedWifiSelection.Upstream(1, false, false, true, false)
        val networks = listOf(carWifi, directCell, tunnel)
        assertFalse(ConnectedWifiSelection.independentInternet(1, tunnel, networks, beforeDedicatedRequest = true))
        assertTrue(ConnectedWifiSelection.independentInternet(1, tunnel, networks, beforeDedicatedRequest = false))
        assertFalse(ConnectedWifiSelection.independentInternet(1, tunnel.copy(wifi = true), networks))
        assertTrue(ConnectedWifiSelection.independentInternet(1, directCell, listOf(carWifi, directCell), beforeDedicatedRequest = true))
        val onlineCar = listOf(carWifi.copy(validatedInternet = true), tunnel)
        assertTrue(ConnectedWifiSelection.independentInternet(1, tunnel, onlineCar, beforeDedicatedRequest = true))
    }

    @Test
    fun projectionIsLocalFirstWithEitherCoexistenceSettingAndEitherAndroidVpnState() {
        for (coexist in listOf(false, true)) for (vpn in listOf(false, true)) {
            val owner = WifiJoinOwnership()
            val flight = owner.claim(owner.activate(coexist), "BMW")!!.flight
            val usesCar = if (vpn) tunnel.copy(wifi = true) else directCell.copy(network = 1)
            val separate = if (vpn) tunnel else directCell
            assertFalse(owner.mayReuseSystemWifi(flight) { ConnectedWifiSelection.independentInternet(1, usesCar, listOf(usesCar)) })
            assertTrue(owner.mayReuseSystemWifi(flight) { ConnectedWifiSelection.independentInternet(1, separate, listOf(separate)) })
            assertTrue(owner.rememberRoute(flight, WifiJoinOwnership.RouteChoice.SYSTEM_WIFI))
            assertTrue(owner.mayReuseSystemWifi(flight) { false })
            owner.cancel()
            assertFalse(owner.mayReuseSystemWifi(flight) { true })
        }
    }

    @Test
    fun aSplitVpnUsingTheCarCannotBeIgnoredJustBecauseCarDeckHasCellularAsDefault() {
        val viaCar = tunnel.copy(wifi = true, cellularOrEthernet = false)
        assertFalse(ConnectedWifiSelection.independentInternet(1, directCell, listOf(directCell, viaCar)))
        assertFalse(ConnectedWifiSelection.independentInternet(1, directCell, listOf(tunnel.copy(wifi = true))))
        assertTrue(ConnectedWifiSelection.independentInternet(1, directCell, listOf(tunnel)))
        assertFalse(ConnectedWifiSelection.independentInternet(1, directCell, listOf(tunnel.copy(cellularOrEthernet = false))))
        assertFalse(ConnectedWifiSelection.independentInternet(1, directCell, listOf(tunnel.copy(validatedInternet = false))))
        assertFalse(ConnectedWifiSelection.independentInternet(1, directCell.copy(validatedInternet = false), listOf(directCell)))
    }
    private fun candidate(id: Int, ssid: String?, internet: Boolean = false) =
        ConnectedWifiSelection.Candidate(id, ssid, internet)

    @Test
    fun exactPerNetworkSsidSelectsCorrectNetworkAmongOtherWifi() {
        assertEquals(2, ConnectedWifiSelection.select("BMW", null, listOf(candidate(1, "Office"), candidate(2, "BMW"))))
    }

    @Test
    fun redactedSingleWifiNeedsExactShellConfirmation() {
        assertEquals(2, ConnectedWifiSelection.select("BMW", "BMW", listOf(candidate(2, null))))
        assertNull(ConnectedWifiSelection.select("BMW", null, listOf(candidate(2, null))))
        assertNull(ConnectedWifiSelection.select("BMW", "Office", listOf(candidate(2, null))))
    }

    @Test
    fun wrongPerNetworkSsidCannotBeOverriddenByOlderShellSnapshot() {
        assertNull(ConnectedWifiSelection.select("BMW", "BMW", listOf(candidate(2, "Office"))))
    }

    @Test
    fun multipleRedactedWifiNetworksAreAmbiguousEvenWithShellConfirmation() {
        assertNull(ConnectedWifiSelection.select("BMW", "BMW", listOf(candidate(1, null), candidate(2, null))))
    }

    @Test
    fun repeatedSsidOnMultipleNetworksIsNotGuessed() {
        assertNull(ConnectedWifiSelection.select("BMW", "BMW", listOf(candidate(1, "BMW"), candidate(2, "BMW"))))
    }

    @Test
    fun ssidMatchingDoesNotUsePrefixOrTrim() {
        assertNull(ConnectedWifiSelection.select("BMW", "BMW", listOf(candidate(1, "BMW 12345"))))
        assertNull(ConnectedWifiSelection.select("BMW", "BMW", listOf(candidate(1, " BMW"))))
    }

    @Test
    fun emptyTargetOrNoWifiCannotBeSelected() {
        assertNull(ConnectedWifiSelection.select("", "", listOf(candidate(1, ""))))
        assertNull(ConnectedWifiSelection.select<Int>("BMW", "BMW", emptyList()))
    }

    @Test
    fun internetAdvertisingCarHotspotRequiresExplicitManualChoice() {
        val system = listOf(candidate(577, "DIRECT-BMW", internet = true))
        assertNull(ConnectedWifiSelection.select("DIRECT-BMW", "DIRECT-BMW", system))
        assertEquals(577, ConnectedWifiSelection.select("DIRECT-BMW", "DIRECT-BMW", system, allowSystemWifi = true))
    }

    @Test
    fun distinguishLocalConnectionFromSystemConnectionWithSameVisibleSsid() {
        val both = listOf(candidate(577, "BMW", internet = true), candidate(578, "BMW"))
        assertEquals(578, ConnectedWifiSelection.select("BMW", "BMW", both))
        assertNull(ConnectedWifiSelection.select("BMW", "BMW", both, allowSystemWifi = true))
    }

    @Test
    fun redactedSystemNetworkDoesNotPassAutomaticShellSsidCheck() {
        val system = listOf(candidate(577, null, internet = true))
        assertNull(ConnectedWifiSelection.select("BMW", "BMW", system))
        assertEquals(577, ConnectedWifiSelection.select("BMW", "BMW", system, allowSystemWifi = true))
        assertNull(ConnectedWifiSelection.select("BMW", "Office", system, allowSystemWifi = true))
    }

    @Test
    fun excludingInternetCandidateCannotCreateFalseSingleRedactedMatch() {
        val multiple = listOf(candidate(577, null, internet = true), candidate(578, null))
        assertNull(ConnectedWifiSelection.select("BMW", "BMW", multiple))
    }
}
