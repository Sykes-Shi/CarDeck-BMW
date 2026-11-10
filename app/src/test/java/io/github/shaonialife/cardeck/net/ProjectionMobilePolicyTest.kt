package io.github.shaonialife.cardeck.net

import io.github.shaonialife.cardeck.net.ProjectedUidRoute.Kind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionMobilePolicyTest {
    private fun prefer(route: Kind, vpn: Boolean = false, own: Boolean = false, direct: Boolean? = null,
        otherWifi: Boolean = false, defaultCar: Boolean = true, wifiUnderlay: Boolean = true): Boolean =
        ProjectionMobilePolicy.needsPreference(route, own, defaultCar, wifiUnderlay, otherWifi, vpn) { direct }

    @Test fun actualReceiverWifiIsRecoveredEvenIfTheOtherVpnCannotBeInspected() {
        assertTrue(prefer(Kind.CAR_WIFI))
        assertTrue(prefer(Kind.CAR_WIFI, vpn = true))
        assertTrue(prefer(Kind.CAR_WIFI, vpn = true, direct = true))
        assertFalse(prefer(Kind.CAR_WIFI, vpn = true, direct = false))
    }

    @Test fun assignedVpnBlockedOrHealthyNetworkNeverGetsANewLease() {
        for (route in listOf(Kind.VPN, Kind.BLOCKED, Kind.WIFI, Kind.OTHER, Kind.CELLULAR)) {
            assertFalse(prefer(route))
            assertFalse(prefer(route, vpn = true, direct = true))
        }
    }

    @Test fun releaseOnVpnOrHealthyWifiButDoNotFlipFlopWhenOwnLeaseStartsWorking() {
        assertTrue(prefer(Kind.CELLULAR, own = true))
        assertFalse(prefer(Kind.VPN, own = true))
        assertFalse(prefer(Kind.WIFI, own = true))
        assertFalse(prefer(Kind.CELLULAR, own = true, vpn = true, direct = false))
    }

    @Test fun unavailableUidProbeRequiresCorroboratingRiskAndKnownSplitIfVpnIsVisible() {
        assertTrue(prefer(Kind.UNKNOWN))
        assertFalse(prefer(Kind.UNKNOWN, otherWifi = true))
        assertFalse(prefer(Kind.UNKNOWN, defaultCar = false))
        assertFalse(prefer(Kind.UNKNOWN, vpn = true))
        assertTrue(prefer(Kind.UNKNOWN, vpn = true, direct = true, defaultCar = false))
        assertFalse(prefer(Kind.UNKNOWN, vpn = true, direct = true, defaultCar = false, wifiUnderlay = false))
    }

    @Test fun phoneBixbyRequiresPositiveDirectEvidenceForANewOrContinuingCellularPreference() {
        for (route in Kind.values()) {
            for (owned in listOf(false, true)) {
                val eligible = route == Kind.CAR_WIFI || owned && route == Kind.CELLULAR
                assertFalse(BixbyNetworkPolicy.needsPreference(route, owned) { null })
                assertFalse(BixbyNetworkPolicy.needsPreference(route, owned) { false })
                org.junit.Assert.assertEquals(eligible, BixbyNetworkPolicy.needsPreference(route, owned) { true })
            }
        }
    }
}
