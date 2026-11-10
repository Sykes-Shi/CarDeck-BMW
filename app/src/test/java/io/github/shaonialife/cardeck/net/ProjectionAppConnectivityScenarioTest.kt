package io.github.shaonialife.cardeck.net

import io.github.shaonialife.cardeck.net.ProjectedUidRoute.Kind
import org.junit.Assert.*
import org.junit.Test

class ProjectionAppConnectivityScenarioTest {
    @Test fun fourSwitchCombinationsPreserveAppSplitAndRestoreOnlyCarDeckAdditions() {
        val maps = "com.baidu.BaiduMap"
        val player = "com.spotify.music"
        val installed = mapOf(10101 to setOf(maps), 10102 to setOf(player))
        val before = setOf(10402)
        for (detailedLog in listOf(false, true)) for (vpn in listOf(false, true)) {
            val joins = WifiJoinOwnership()
            val flight = joins.claim(joins.activate(detailedLog), "BMW")!!.flight
            val active = if (vpn) ConnectedWifiSelection.Upstream(2, true, true, true, false)
                else ConnectedWifiSelection.Upstream(1, false, false, true, false)
            assertFalse(joins.mayReuseSystemWifi(flight) { ConnectedWifiSelection.independentInternet(1, active, listOf(active)) })
            // The user accepts a shared-network fallback. Maps has the receiver route while
            // the player is already on its assigned tunnel or its validated physical default.
            assertTrue(joins.rememberRoute(flight, WifiJoinOwnership.RouteChoice.SYSTEM_WIFI))
            val routes = mapOf(maps to Kind.CAR_WIFI, player to if (vpn) Kind.VPN else Kind.CELLULAR)
            val selected = routes.filter { (pkg, route) ->
                ProjectionMobilePolicy.needsPreference(route, false, !vpn, true, false, vpn) { pkg == maps }
            }.keys
            assertEquals(setOf(maps), selected)
            val temporary = MobileDataPreferencePlan.plan(before, emptyMap(), installed, routes.keys, selected, 10150)
            assertEquals(before + 10101, temporary.after)
            val afterManualAddition = temporary.after + 10501
            val stopped = MobileDataPreferencePlan.plan(afterManualAddition, temporary.owners, installed, emptySet(), emptySet(), 10150)
            assertEquals(before + 10501, stopped.after)
            assertTrue(stopped.owners.isEmpty())
        }
    }
}
