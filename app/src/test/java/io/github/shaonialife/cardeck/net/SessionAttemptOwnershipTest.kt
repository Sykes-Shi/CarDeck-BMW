package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAttemptOwnershipTest {
    private data class Network(val id: Int)

    @Test
    fun lossBetweenRecordAndPublicationCancelsAttempt() {
        val owner = SessionAttemptOwnership<Network>()
        val attempt = owner.begin()
        assertTrue(owner.bind(attempt, Network(397)))
        assertSame(attempt, owner.lose(Network(397)))
        assertFalse(owner.active(attempt))
        assertFalse(owner.matches(attempt, Network(397)))
        assertTrue(owner.owns(attempt)) // 本轮 finally 仍可释放自己占有的启动门闸。
    }

    @Test
    fun oldLossCannotCancelReplacementOnNewNetwork() {
        val owner = SessionAttemptOwnership<Network>()
        val old = owner.begin()
        owner.bind(old, Network(397))
        val next = owner.begin()
        owner.bind(next, Network(398))
        assertNull(owner.lose(Network(397)))
        assertTrue(owner.matches(next, Network(398)))
        assertFalse(owner.owns(old))
    }

    @Test
    fun cancelledWorkerCannotRebindOrReleaseReplacement() {
        val owner = SessionAttemptOwnership<Network>()
        val old = owner.begin()
        owner.bind(old, Network(397))
        assertSame(old, owner.cancel())
        val next = owner.begin()
        owner.bind(next, Network(397))
        assertFalse(owner.bind(old, Network(398)))
        assertFalse(owner.owns(old))
        assertTrue(owner.matches(next, Network(397)))
    }

    @Test
    fun manualCancellationBlocksLateSuccessfulRecord() {
        val owner = SessionAttemptOwnership<Network>()
        val attempt = owner.begin()
        owner.bind(attempt, Network(397))
        owner.cancel()
        assertFalse(owner.matches(attempt, Network(397)))
        assertFalse(owner.bind(attempt, Network(397)))
    }

    @Test
    fun observedMissingOrChangedNetworkBlocksPublication() {
        val owner = SessionAttemptOwnership<Network>()
        val attempt = owner.begin()
        owner.bind(attempt, Network(397))
        assertFalse(owner.matches(attempt, null))
        assertFalse(owner.matches(attempt, Network(398)))
        assertTrue(owner.matches(attempt, Network(397)))
    }

    @Test
    fun duplicateLossIsIdempotent() {
        val owner = SessionAttemptOwnership<Network>()
        val attempt = owner.begin()
        owner.bind(attempt, Network(397))
        assertSame(attempt, owner.lose(Network(397)))
        assertNull(owner.lose(Network(397)))
    }

    @Test
    fun lossBeforeNetworkAssociationDoesNotCancelUnrelatedStartup() {
        val owner = SessionAttemptOwnership<Network>()
        val attempt = owner.begin()
        assertNull(owner.lose(Network(397)))
        assertTrue(owner.active(attempt))
        assertFalse(owner.matches(attempt, Network(397)))
    }

    @Test
    fun recoveryCannotBindReplacementNetworkBeforeDelayedFirstAssociation() {
        val owner = SessionAttemptOwnership<Network>()
        val recovery = owner.begin()
        val invited = Network(397)
        assertNull(owner.lose(invited))
        assertFalse(owner.bind(recovery, Network(398), invited))
        assertFalse(owner.matches(recovery, Network(398)))
        assertTrue(owner.bind(recovery, Network(397), invited))
        assertSame(recovery, owner.lose(invited))
        assertFalse(owner.bind(recovery, invited, invited))
    }

    @Test
    fun emptyDeferredCleanupCannotOwnLaterAttempt() {
        val owner = SessionAttemptOwnership<Network>()
        val retired = owner.cancel()
        assertNull(retired)
        assertTrue(owner.owns(retired))
        val next = owner.begin()
        assertFalse(owner.owns(retired))
        assertTrue(owner.active(next))
    }
}
