package io.github.shaonialife.cardeck.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserServiceBindingStateTest {
    @Test
    fun frequentTouchAndLauncherRequestsLeaveSlowStartupIntact() {
        val state = UserServiceBindingState()
        val token = state.request(1_000)!!
        for (now in 1_050L until 13_000L step 50L) assertNull(state.request(now))
        assertTrue(state.beginVerification(token))
        assertTrue(state.verified(token))
        assertNull(state.request(100_000))
    }

    @Test
    fun timeoutCreatesNewGenerationWithoutMakingOldResultCurrent() {
        val state = UserServiceBindingState()
        val old = state.request(1_000)!!
        val newer = state.request(13_000)!!
        assertNotEquals(old, newer)
        assertFalse(state.beginVerification(old))
        assertFalse(state.failed(old, 13_100))
        assertTrue(state.beginVerification(newer))
        assertTrue(state.verified(newer))
    }

    @Test
    fun lateSuccessfulProbeCannotReplaceNewerBinder() {
        val state = UserServiceBindingState()
        val old = state.request(0)!!
        assertTrue(state.beginVerification(old))
        val newer = state.request(12_000)!!
        assertFalse(state.verified(old))
        assertTrue(state.beginVerification(newer))
        assertTrue(state.verified(newer))
    }

    @Test
    fun oldDeathDoesNotClearCurrentReadyBinder() {
        val state = UserServiceBindingState()
        val old = state.request(0)!!
        val newer = state.request(12_000)!!
        assertTrue(state.beginVerification(newer))
        assertTrue(state.verified(newer))
        assertFalse(state.failed(old, 13_000))
        assertTrue(state.isCurrent(newer))
        assertNull(state.request(50_000))
    }

    @Test
    fun connectedCallbackMustPassVerificationBeforeReady() {
        val state = UserServiceBindingState()
        val token = state.request(0)!!
        assertFalse(state.verified(token))
        assertTrue(state.beginVerification(token))
        assertTrue(state.verified(token))
        assertFalse(state.verified(token))
    }

    @Test
    fun duplicateConnectedCallbackDoesNotStartAnotherProbe() {
        val state = UserServiceBindingState()
        val token = state.request(0)!!
        assertTrue(state.beginVerification(token))
        assertFalse(state.beginVerification(token))
        assertTrue(state.verified(token))
        assertFalse(state.beginVerification(token))
    }

    @Test
    fun failedProbeInvalidatesLateCallbackAndAppliesRetryDelay() {
        val state = UserServiceBindingState()
        val token = state.request(0)!!
        assertTrue(state.beginVerification(token))
        assertTrue(state.failed(token, 500))
        assertFalse(state.verified(token))
        assertNull(state.request(1_999))
        assertNotNull(state.request(2_000))
    }

    @Test
    fun currentDeathClearsReadyAndAllowsDelayedRetry() {
        val state = UserServiceBindingState()
        val token = state.request(0)!!
        state.beginVerification(token)
        state.verified(token)
        assertTrue(state.failed(token, 5_000))
        assertFalse(state.isCurrent(token))
        assertNull(state.request(6_499))
        assertNotNull(state.request(6_500))
    }

    @Test
    fun serverRestartInvalidatesInFlightProbeImmediately() {
        val state = UserServiceBindingState()
        val old = state.request(0)!!
        state.beginVerification(old)
        state.invalidate()
        assertFalse(state.verified(old))
        assertFalse(state.failed(old, 1_000))
        assertNotNull(state.request(1_000))
    }

    @Test
    fun serverRestartClearsReadyWithoutKeepingFailureCooldown() {
        val state = UserServiceBindingState()
        val old = state.request(0)!!
        state.beginVerification(old)
        state.verified(old)
        state.invalidate()
        assertNotNull(state.request(1))
    }

    @Test
    fun backwardClockDoesNotRestartPendingBind() {
        val state = UserServiceBindingState()
        val old = state.request(8_000)!!
        assertNull(state.request(1_000))
        assertTrue(state.isCurrent(old))
        assertNull(state.request(19_999))
        assertNotNull(state.request(20_000))
    }

    @Test
    fun pollingEligibilityDoesNotConsumeAnAttempt() {
        val state = UserServiceBindingState()
        repeat(50) { assertTrue(state.canRequest(0)) }
        assertEquals(1L, state.request(0))
        repeat(50) { assertFalse(state.canRequest(1_000)) }
        assertNotNull(state.request(12_000))
    }

    @Test
    fun staleFailureCannotDelayNewCurrentAttempt() {
        val state = UserServiceBindingState()
        val old = state.request(0)!!
        state.invalidate()
        assertFalse(state.failed(old, 100_000))
        assertNotNull(state.request(1))
    }

    @Test
    fun failedRegistrationCanRetryButOldConnectedCallbackIsRejected() {
        val state = UserServiceBindingState()
        val old = state.request(0)!!
        assertTrue(state.failed(old, 100))
        assertFalse(state.beginVerification(old))
        assertNull(state.request(1_599))
        val newer = state.request(1_600)!!
        assertTrue(state.beginVerification(newer))
    }
}
