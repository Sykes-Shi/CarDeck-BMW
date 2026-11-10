package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiJoinOwnershipTest {
    @Test
    fun waitForANewInvitationOnlyOncePerLostHotspotInTheSameConnection() {
        val owner = WifiJoinOwnership()
        val first = owner.activate()
        assertFalse(owner.takeLostRequest(first, "BMW"))
        owner.noteLostRequest("BMW")
        assertFalse(owner.takeLostRequest(first, "Another car"))
        assertTrue(owner.takeLostRequest(first, "BMW"))
        assertFalse(owner.takeLostRequest(first, "BMW"))
        owner.noteLostRequest("BMW")
        owner.cancel()
        val second = owner.activate()
        assertFalse(owner.takeLostRequest(second, "BMW"))
        owner.noteLostRequest("BMW")
        assertFalse(owner.takeLostRequest(first, "BMW"))
        assertTrue(owner.takeLostRequest(second, "BMW"))
    }

    @Test
    fun credentialAndEndpointShareOneCompleteJoinFlow() {
        val owner = WifiJoinOwnership()
        val epoch = owner.activate()
        val credentials = owner.claim(epoch, "BMW")!!
        val endpoint = owner.claim(epoch, "BMW")!!
        assertTrue(credentials.leader)
        assertFalse(endpoint.leader)
        assertSame(credentials.flight, endpoint.flight)
        assertFalse(endpoint.flight.await(0))
        owner.complete(credentials.flight, true)
        assertTrue(endpoint.flight.await(0))
        assertTrue(endpoint.flight.succeeded)
    }

    @Test
    fun failureAlsoWakesFollowerWithoutStartingAnotherPrompt() {
        val owner = WifiJoinOwnership()
        val epoch = owner.activate()
        val leader = owner.claim(epoch, "BMW")!!.flight
        val follower = owner.claim(epoch, "BMW")!!
        owner.complete(leader, false)
        assertTrue(follower.flight.await(0))
        assertFalse(follower.flight.succeeded)
        assertFalse(owner.isCurrent(leader))
    }

    @Test
    fun manualWaitRetainsOwnershipAcrossOtherProtocolCallbacks() {
        val owner = WifiJoinOwnership()
        val epoch = owner.activate()
        val manual = owner.claim(epoch, "BMW")!!.flight
        repeat(5) {
            assertFalse(owner.claim(epoch, "BMW")!!.leader)
            assertTrue(owner.isCurrent(manual))
        }
    }

    @Test
    fun cancellationInvalidatesPendingDialogAndWakesWaiters() {
        val owner = WifiJoinOwnership()
        val epoch = owner.activate()
        val flight = owner.claim(epoch, "BMW")!!.flight
        owner.cancel()
        assertFalse(owner.isCurrent(epoch))
        assertFalse(owner.isCurrent(flight))
        assertTrue(flight.await(0))
        assertFalse(flight.succeeded)
        assertNull(owner.currentEpoch())
    }

    @Test
    fun rapidReconnectCannotReviveOldWorkerOrOldDialogChoice() {
        val owner = WifiJoinOwnership()
        val oldEpoch = owner.activate()
        val old = owner.claim(oldEpoch, "BMW")!!.flight
        owner.cancel()
        val nextEpoch = owner.activate()
        val next = owner.claim(nextEpoch, "BMW")!!.flight
        assertNotSame(oldEpoch, nextEpoch)
        assertNull(owner.claim(oldEpoch, "BMW"))
        owner.complete(old, true)
        assertFalse(old.succeeded)
        assertTrue(owner.isCurrent(next))
        assertFalse(next.await(0))
    }

    @Test
    fun lateCompletionCannotReleaseAnotherFlightInSameEpoch() {
        val owner = WifiJoinOwnership()
        val epoch = owner.activate()
        val first = owner.claim(epoch, "BMW")!!.flight
        owner.complete(first, false)
        val retry = owner.claim(epoch, "BMW")!!.flight
        owner.complete(first, true)
        assertTrue(owner.isCurrent(retry))
        assertFalse(first.succeeded)
    }

    @Test
    fun idempotentStartKeepsCurrentJoinOwner() {
        val owner = WifiJoinOwnership()
        val epoch = owner.activate()
        val flight = owner.claim(epoch, "BMW")!!.flight
        assertSame(epoch, owner.activate())
        assertTrue(owner.isCurrent(flight))
    }

    @Test
    fun vpnCoexistencePolicyIsFrozenThroughRetriesAndRefreshesAfterDisconnect() {
        val owner = WifiJoinOwnership()
        assertTrue(owner.activate(vpnCoexistence = true).vpnCoexistence)
        assertTrue(owner.activate(vpnCoexistence = false).vpnCoexistence)
        owner.cancel()
        assertFalse(owner.activate(vpnCoexistence = false).vpnCoexistence)
    }

    @Test
    fun differentTargetCannotReplaceAnActiveRequest() {
        val owner = WifiJoinOwnership()
        val epoch = owner.activate()
        val first = owner.claim(epoch, "BMW")!!.flight
        assertNull(owner.claim(epoch, "Other car"))
        assertTrue(owner.isCurrent(first))
    }

    @Test
    fun successfulJoinEndsPromptOwnershipButKeepsNetworkEpochValid() {
        val owner = WifiJoinOwnership()
        val epoch = owner.activate()
        val flight = owner.claim(epoch, "BMW")!!.flight
        owner.complete(flight, true)
        assertFalse(owner.isCurrent(flight))
        assertTrue(owner.isCurrent(epoch))
    }

    @Test
    fun confirmedSystemHotspotCanBeReusedOnNextHandshakeOnlyForSameSsidAndConnection() {
        val owner = WifiJoinOwnership()
        val connection = owner.activate(vpnCoexistence = true)
        val first = owner.claim(connection, "BMW")!!.flight
        assertNull(owner.routeChoice(first))
        assertTrue(owner.rememberRoute(first, WifiJoinOwnership.RouteChoice.SYSTEM_WIFI))
        owner.complete(first, true)
        val other = owner.claim(connection, "Office")!!.flight
        assertNull(owner.routeChoice(other))
        owner.complete(other, false)
        val again = owner.claim(connection, "BMW")!!.flight
        assertEquals(WifiJoinOwnership.RouteChoice.SYSTEM_WIFI, owner.routeChoice(again))
        owner.cancel()
        val next = owner.claim(owner.activate(vpnCoexistence = true), "BMW")!!.flight
        assertFalse(owner.rememberRoute(again, WifiJoinOwnership.RouteChoice.SYSTEM_WIFI))
        assertNull(owner.routeChoice(next))
    }

    @Test
    fun dedicatedAttemptNeverAuthorizesSystemWifiAndCanOfferChoiceAgainAfterLoss() {
        val owner = WifiJoinOwnership()
        val epoch = owner.activate(vpnCoexistence = true)
        val first = owner.claim(epoch, "BMW")!!.flight
        assertTrue(owner.rememberRoute(first, WifiJoinOwnership.RouteChoice.DEDICATED))
        assertEquals(WifiJoinOwnership.RouteChoice.DEDICATED, owner.routeChoice(first))
        owner.complete(first, true)
        val retry = owner.claim(epoch, "BMW")!!.flight
        assertNull(owner.routeChoice(retry))
        assertFalse(owner.rememberRoute(first, WifiJoinOwnership.RouteChoice.SYSTEM_WIFI))
    }
}
