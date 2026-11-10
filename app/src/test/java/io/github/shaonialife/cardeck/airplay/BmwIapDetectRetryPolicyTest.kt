package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwIapDetectRetryPolicyTest {
    @Test
    fun onlyInvitationAfterArmAllowsOneRetry() {
        val policy = BmwIapDetectRetryPolicy()
        policy.arm(8)
        assertFalse(policy.shouldRetry(true, 0, true, true))
        assertFalse(policy.shouldRetry(true, 7, true, true))
        assertFalse(policy.shouldRetry(true, 8, true, true))
        assertTrue(policy.shouldRetry(true, 9, true, true))
        assertFalse(policy.shouldRetry(true, 9, true, true))
        assertFalse(policy.shouldRetry(true, 10, true, true))
        policy.arm(10)
        assertFalse(policy.shouldRetry(true, 11, true, true))
    }

    @Test
    fun anyIncomingBytesPreventRetryEvenBeforeArm() {
        for (beforeArm in listOf(true, false)) {
            val policy = BmwIapDetectRetryPolicy()
            if (beforeArm) policy.noteIncomingBytes()
            policy.arm(0)
            if (!beforeArm) policy.noteIncomingBytes()
            assertFalse(policy.shouldRetry(true, 1, true, true))
        }
    }

    @Test
    fun nonNegotiatingClosedDisabledOrUnarmedLinkNeverRetries() {
        val unarmed = BmwIapDetectRetryPolicy()
        assertFalse(unarmed.shouldRetry(true, 1, true, true))
        val policy = BmwIapDetectRetryPolicy().also { it.arm(0) }
        assertFalse(policy.shouldRetry(false, 1, true, true))
        assertFalse(policy.shouldRetry(true, 1, false, true))
        assertFalse(policy.shouldRetry(true, 1, true, false))
        assertTrue(policy.shouldRetry(true, 1, true, true))
    }

    @Test
    fun repeatedArmDoesNotEraseInitialInvitationBaseline() {
        val policy = BmwIapDetectRetryPolicy()
        policy.arm(3)
        policy.arm(4)
        assertTrue(policy.shouldRetry(true, 4, true, true))
    }
}
