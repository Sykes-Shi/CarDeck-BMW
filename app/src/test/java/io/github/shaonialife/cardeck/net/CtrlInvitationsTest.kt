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
    fun linkLocalAddressMatchesWithoutScopeWhenNoDeviceWasPreselected() {
        val invitations = CtrlInvitations()
        invitations.acknowledge(600, "fe80::1%wlan0", null)
        assertTrue(invitations.take(750, setOf("fe80::1"), null))
    }
}
