package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CtrlInvitationsTest {
    private val carAddresses = setOf("192.0.2.7")

    @Test
    fun earlyInvitationCanBeUsedOnceAcrossBonjourReregistration() {
        val invitations = CtrlInvitations()
        invitations.acknowledge(1_000, "192.0.2.7", "123")
        assertTrue(invitations.take(3_100, carAddresses, 123))
        assertFalse(invitations.take(3_101, carAddresses, 123))
        invitations.acknowledge(4_000, "192.0.2.7", "123")
        assertTrue(invitations.take(4_100, carAddresses, 123))
    }

    @Test
    fun staleOrUnrelatedInvitationsCannotReleaseAnotherAttempt() {
        val invitations = CtrlInvitations()
        invitations.acknowledge(1_000, "192.0.2.7", "123")
        assertFalse(invitations.take(1_500, setOf("192.0.2.2"), 123))
        assertFalse(invitations.take(1_500, carAddresses, 456))
        assertFalse(invitations.take(21_000, carAddresses, 123))
    }

    @Test
    fun matchingInviteOnAnUnboundWlanSurvivesTheObservedHandoffDelay() {
        val invitations = CtrlInvitations()
        invitations.acknowledge(0, "192.0.2.7", "123")
        assertTrue(invitations.localAddress(11_497, 123) in carAddresses)
        assertTrue(invitations.localAddress(11_497, 321) == null)
        assertFalse(invitations.take(11_498, setOf("192.0.2.5"), 123))
        assertTrue(invitations.take(11_499, carAddresses, 123))
        assertTrue(invitations.localAddress(11_500, 123) == null)
    }

    @Test
    fun endedStreamNeedsANewMatchedInvitationThatHasNotExpired() {
        val invitations = CtrlInvitations()
        val atEnd = invitations.acknowledge(500, "192.0.2.7", "123")
        assertTrue(invitations.localAddress(700, 123, atEnd) == null)
        invitations.acknowledge(710, "192.0.2.7", "456")
        assertTrue(invitations.localAddress(720, 123, atEnd) == null)
        invitations.acknowledge(730, "192.0.2.7", "123")
        assertTrue(invitations.localAddress(731, 123, atEnd) in carAddresses)
        assertTrue(invitations.localAddress(15_731, 123, atEnd) == null)
    }

    @Test
    fun recoveryRechecksFreshnessReceiverAndAddressWhenPairingStarts() {
        val invites = CtrlInvitations()
        val atEnd = invites.acknowledge(1_000, "192.0.2.7", "123")
        assertFalse(invites.take(1_001, carAddresses, 123, atEnd))
        invites.acknowledge(1_100, "192.0.2.7", "123")
        assertTrue(invites.localAddress(1_101, 123, atEnd) in carAddresses)
        assertFalse(invites.take(16_101, carAddresses, 123, atEnd))
        invites.acknowledge(16_200, "192.0.2.7", "456")
        assertFalse(invites.take(16_201, carAddresses, 123, atEnd))
        invites.acknowledge(16_300, "192.0.2.8", "123")
        assertFalse(invites.take(16_301, carAddresses, 123, atEnd))
        invites.acknowledge(16_400, "192.0.2.7", "123")
        assertTrue(invites.take(16_401, carAddresses, 123, atEnd))
        assertFalse(invites.take(16_402, carAddresses, 123, atEnd))
    }

    @Test
    fun linkLocalAddressMatchesWithoutScopeWhenNoDeviceWasPreselected() {
        val invitations = CtrlInvitations()
        invitations.acknowledge(600, "fe80::1%wlan0", null)
        assertTrue(invitations.take(750, setOf("fe80::1"), null))
    }
}
