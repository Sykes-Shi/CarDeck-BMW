package io.github.shaonialife.cardeck.net

import io.github.shaonialife.cardeck.net.ProjectedUidRoute.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectedUidRouteTest {
    @Test fun vpnWithWifiOrCellularUnderlayKeepsItsVpnClassificationEvenBeforeValidation() {
        for (validated in listOf(false, true)) {
            assertEquals(Kind.VPN, ProjectedUidRoute.classify(false, true, true, validated, false, true))
            assertEquals(Kind.VPN, ProjectedUidRoute.classify(false, true, true, validated, true, false))
        }
    }

    @Test fun carHandleWinsOverInternetBitAndOtherValidatedNetworksRetainTheirTransport() {
        assertEquals(Kind.CAR_WIFI, ProjectedUidRoute.classify(true, false, true, true, false, true))
        assertEquals(Kind.WIFI, ProjectedUidRoute.classify(false, false, true, true, false, true))
        assertEquals(Kind.CELLULAR, ProjectedUidRoute.classify(false, false, true, true, true, false))
        assertEquals(Kind.UNVALIDATED, ProjectedUidRoute.classify(false, false, true, false, false, true))
    }
}
