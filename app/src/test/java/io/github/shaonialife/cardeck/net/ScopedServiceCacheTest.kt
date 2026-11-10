package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopedServiceCacheTest {
    private val scope = ScopedServiceCache.Scope(239L, "DIRECT-Car", "TARGET-A")
    private val service = "CarPlay|_airplay._tcp."

    @Test
    fun sameNetworkAutomaticReconnectKeepsDiscoveredIpv4() {
        val cache = ScopedServiceCache<String>()
        val generation = cache.selectScope(scope)
        assertTrue(cache.publish(cache.beginResolve(generation, service)!!, "172.16.222.97:7000"))
        assertEquals(generation, cache.selectScope(scope.copy()))
        assertEquals("172.16.222.97:7000", cache.get())
    }

    @Test
    fun changedNetworkWithSameSsidRejectsPreviousResolver() {
        val cache = ScopedServiceCache<String>()
        val generation = cache.selectScope(scope)
        val pending = cache.beginResolve(generation, service)!!
        cache.publish(pending, "old-car")
        assertNotEquals(generation, cache.selectScope(scope.copy(networkHandle = 240L)))
        assertNull(cache.get())
        assertFalse(cache.publish(pending, "late-old-car"))
        assertNull(cache.beginResolve(generation, service))
    }

    @Test
    fun changedVehicleOrSsidNeverReusesEarlierAddress() {
        for (changed in listOf(scope.copy(targetAddress = "TARGET-B"), scope.copy(ssid = "OTHER-Car"))) {
            val cache = ScopedServiceCache<String>()
            val generation = cache.selectScope(scope)
            val pending = cache.beginResolve(generation, service)!!
            cache.publish(pending, "old-car")
            cache.selectScope(changed)
            assertNull(cache.get())
            assertFalse(cache.publish(pending, "late-old-car"))
        }
    }

    @Test
    fun userStopOrNetworkLossInvalidatesPendingResolution() {
        val cache = ScopedServiceCache<String>()
        val generation = cache.selectScope(scope)
        val pending = cache.beginResolve(generation, service)!!
        cache.publish(pending, "old-car")
        cache.clear()
        assertNull(cache.get())
        assertFalse(cache.publish(pending, "late-old-car"))
        val nextGeneration = cache.selectScope(scope)
        assertNotEquals(generation, nextGeneration)
        assertNull(cache.get())
    }

    @Test
    fun serviceLossDropsEndpointAndRejectsAlreadyPendingResolution() {
        val cache = ScopedServiceCache<String>()
        val generation = cache.selectScope(scope)
        val old = cache.beginResolve(generation, service)!!
        cache.publish(old, "old-endpoint")
        cache.serviceLost(generation, service)
        assertNull(cache.get())
        assertFalse(cache.publish(old, "late-old-endpoint"))
        val rediscovered = cache.beginResolve(generation, service)!!
        assertTrue(cache.publish(rediscovered, "new-endpoint"))
        assertEquals("new-endpoint", cache.get())
    }

    @Test
    fun serviceLossFromOldNetworkCannotEraseNewNetworkResult() {
        val cache = ScopedServiceCache<String>()
        val oldGeneration = cache.selectScope(scope)
        val newGeneration = cache.selectScope(scope.copy(networkHandle = 240L))
        cache.publish(cache.beginResolve(newGeneration, service)!!, "new-car")
        cache.serviceLost(oldGeneration, service)
        assertEquals("new-car", cache.get())
    }

    @Test
    fun unrelatedServiceLossKeepsSelectedEndpoint() {
        val cache = ScopedServiceCache<String>()
        val generation = cache.selectScope(scope)
        cache.publish(cache.beginResolve(generation, service)!!, "car")
        cache.serviceLost(generation, "Another|_airplay._tcp.")
        assertEquals("car", cache.get())
    }

    @Test
    fun unidentifiedScopeIsNeverReusedAcrossHandoffs() {
        val cache = ScopedServiceCache<String>()
        val generation = cache.selectScope(null)
        cache.publish(cache.beginResolve(generation, service)!!, "unidentified-car")
        assertNotEquals(generation, cache.selectScope(null))
        assertNull(cache.get())
    }

    @Test
    fun ipv6DefaultRouteFirstStillTriesIpv4AndKeepsIpv6Fallback() {
        assertEquals(
            listOf("172.16.222.97", "fe80::1%wlan0"),
            EndpointHosts.gatewayCandidates(listOf("fe80::1%wlan0", "172.16.222.97", "0.0.0.0", "::")),
        )
    }

    @Test
    fun allUnspecifiedIpv6SpellingsAreExcludedWithoutDroppingRealGateways() {
        assertEquals(
            listOf("172.16.222.97", "2001:db8::1", "fe80::1%wlan0"),
            EndpointHosts.gatewayCandidates(
                listOf(
                    "::", "::0", "0::", "0000::0000", "0:0:0:0:0:0:0:0",
                    "0000:0000:0000:0000:0000:0000:0000:0000%wlan0",
                    "::0%3", "0.0.0.0", "fe80::1%wlan0", "172.16.222.97", "2001:db8::1",
                ),
            ),
        )
    }

    @Test
    fun ipv6OnlyGatewayRemainsAvailable() {
        assertEquals(listOf("fe80::1%wlan0"), EndpointHosts.gatewayCandidates(listOf("fe80::1%wlan0")))
        assertTrue(EndpointHosts.gatewayCandidates(listOf("0.0.0.0", "::", "")).isEmpty())
    }
}