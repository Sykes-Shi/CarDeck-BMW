package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BmwRequestUiRecoveryPolicyTest {
    @Test
    fun completedAttemptNeedsAnotherRequestAndFiveSecondCooldown() {
        var now = 0L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        assertTrue(policy.shouldRecover("requestUI", liveSession = true))
        policy.finishRecovery()

        now = 4_999L
        assertFalse(policy.shouldRecover("requestUI", liveSession = true))
        now = 5_000L
        assertTrue(policy.shouldRecover("requestUI", liveSession = true))
    }

    @Test
    fun attemptStillInFlightBlocksRequestsAfterCooldown() {
        var now = 0L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        assertTrue(policy.shouldRecover("requestUI", liveSession = true))
        now = 10_000L
        assertFalse(policy.shouldRecover("requestUI", liveSession = true))
        policy.finishRecovery()
        assertTrue(policy.shouldRecover("requestUI", liveSession = true))
    }

    @Test
    fun failedAttemptsConsumeWindowBudgetUntilTheExactExpiryBoundary() {
        var now = 0L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        // 失败与成功同样释放在途状态；不能因声明失败而绕过滑动窗口额度。
        repeat(6) {
            assertTrue(policy.shouldRecover("requestUI", liveSession = true))
            policy.finishRecovery()
            now += 5_000L
        }
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "window_limit", 30_000L, 6),
            policy.decide("requestUI", liveSession = true),
        )
        now = 59_999L
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "window_limit", 1L, 6),
            policy.decide("requestUI", liveSession = true),
        )
        now = 60_000L
        // 恰好过期的是第一条；新尝试补入后仍为 6 条，而不是清空整个窗口。
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(true, "allowed", 0L, 6),
            policy.decide("requestUI", liveSession = true),
        )
    }

    @Test
    fun rejectedRequestsDoNotPostponeNextAllowedAttempt() {
        var now = 0L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        assertTrue(policy.shouldRecover("requestUI", liveSession = true))
        policy.finishRecovery()
        now = 4_999L
        repeat(10) { assertFalse(policy.shouldRecover("requestUI", liveSession = true)) }
        now = 5_000L
        assertFalse(policy.shouldRecover("requestUI", liveSession = false))
        assertFalse(policy.shouldRecover("changeModes", liveSession = true))
        assertTrue(policy.shouldRecover("requestUI", liveSession = true))
    }

    @Test
    fun backwardClockDoesNotBypassCooldown() {
        var now = 10_000L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        assertTrue(policy.shouldRecover("requestUI", liveSession = true))
        policy.finishRecovery()
        now = 9_000L
        assertFalse(policy.shouldRecover("requestUI", liveSession = true))
        now = 15_000L
        assertTrue(policy.shouldRecover("requestUI", liveSession = true))
    }

    @Test
    fun otherReceiversAndUnknownIdentityNeverEnterExperiment() {
        listOf("unknown", "", "BMW Group MGU-118", "Other MGU-50-P", "BMW Group MGU-50-P-extra")
            .forEach { identity ->
                assertFalse(identity, BmwRequestUiRecoveryPolicy(identity).shouldRecover("requestUI", true))
            }
    }

    @Test
    fun identityFormattingDoesNotChangeTheExactModelGate() {
        val policy = BmwRequestUiRecoveryPolicy("  bmw  GROUP\tMGU-50-P  ")

        assertTrue(policy.shouldRecover("requestUI", liveSession = true))
    }

    @Test
    fun otherCommandsAndUrlTextDoNotConsumeTheRequestUiAllowance() {
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P")

        // 门控仅接收 type；params/url 根本没有进入执行路径的入口。
        listOf(null, "changeModes", "forceKeyFrame", "requestUI?url=https://example.invalid", "https://example.invalid")
            .forEach { command -> assertFalse(policy.shouldRecover(command, liveSession = true)) }
        assertTrue(policy.shouldRecover("requestUI", liveSession = true))
    }

    @Test
    fun inactiveClientCannotClaimAnAttemptAndNewClientHasItsOwnAllowance() {
        var now = 0L
        val oldPolicy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        assertFalse(oldPolicy.shouldRecover("requestUI", liveSession = false))
        assertTrue(oldPolicy.shouldRecover("requestUI", liveSession = true))
        assertFalse(oldPolicy.shouldRecover("requestUI", liveSession = false))

        val newPolicy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        assertTrue(newPolicy.shouldRecover("requestUI", liveSession = true))
        oldPolicy.finishRecovery()
        now = 5_000L
        // 旧任务完成只能释放旧策略，不能解锁新 client 的在途任务。
        assertFalse(newPolicy.shouldRecover("requestUI", liveSession = true))
        assertFalse(oldPolicy.shouldRecover("requestUI", liveSession = false))
    }

    @Test
    fun concurrentRequestsStillAllowOnlyOneAttempt() {
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P")
        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = (1..16).map {
                workers.submit(Callable {
                    check(start.await(2, TimeUnit.SECONDS))
                    policy.shouldRecover("requestUI", liveSession = true)
                })
            }
            start.countDown()
            assertEquals(1, results.count { it.get(2, TimeUnit.SECONDS) })
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun structuredDecisionReportsEligibilityWithoutConsumingAttempts() {
        val other = BmwRequestUiRecoveryPolicy("BMW Group MGU-118") { 0L }
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "receiver_not_supported", null, 0),
            other.decide("requestUI", liveSession = true),
        )
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { 0L }
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "session_not_live", null, 0),
            policy.decide("requestUI", liveSession = false),
        )
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "unsupported_command", null, 0),
            policy.decide("changeModes", liveSession = true),
        )
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(true, "allowed", 0L, 1),
            policy.decide("requestUI", liveSession = true),
        )
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "in_flight", null, 1),
            policy.decide("requestUI", liveSession = true),
        )
    }

    @Test
    fun cooldownDecisionIsNotExtendedByRepeatedRejections() {
        var now = 100L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        assertTrue(policy.decide("requestUI", liveSession = true).allowed)
        policy.finishRecovery()
        now = 5_099L
        repeat(10) {
            assertEquals(
                BmwRequestUiRecoveryPolicy.Decision(false, "cooldown", 1L, 1),
                policy.decide("requestUI", liveSession = true),
            )
        }
        now = 5_100L
        assertTrue(policy.decide("requestUI", liveSession = true).allowed)
    }

    @Test
    fun sixthAttemptReportsWindowWaitEvenWhenCooldownAlsoApplies() {
        var now = 0L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        repeat(6) {
            now = it * 5_000L
            assertTrue(policy.decide("requestUI", liveSession = true).allowed)
            policy.finishRecovery()
        }
        now = 26_000L
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "window_limit", 34_000L, 6),
            policy.decide("requestUI", liveSession = true),
        )
    }

    @Test
    fun windowSlidesWithAttemptsInsteadOfResettingAtMinuteBoundaries() {
        var now = 50_000L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        repeat(6) {
            assertTrue(policy.decide("requestUI", liveSession = true).allowed)
            policy.finishRecovery()
            now += 5_000L
        }
        now = 109_999L
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "window_limit", 1L, 6),
            policy.decide("requestUI", liveSession = true),
        )
        now = 110_000L
        assertEquals(6, policy.decide("requestUI", liveSession = true).attemptsInWindow)
        policy.finishRecovery()
        now = 115_000L
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(true, "allowed", 0L, 6),
            policy.decide("requestUI", liveSession = true),
        )
    }

    @Test
    fun fifteenMinuteSessionCanRecoverAfterEarlierBudgetWasExhausted() {
        var now = 0L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        repeat(6) {
            assertTrue(policy.decide("requestUI", liveSession = true).allowed)
            policy.finishRecovery()
            now += 5_000L
        }
        assertEquals("window_limit", policy.decide("requestUI", liveSession = true).reason)
        now = 900_000L
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(true, "allowed", 0L, 1),
            policy.decide("requestUI", liveSession = true),
        )
    }

    @Test
    fun expiredWindowDoesNotReleaseAnInFlightAttempt() {
        var now = 0L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        assertTrue(policy.decide("requestUI", liveSession = true).allowed)
        now = 900_000L
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "in_flight", null, 0),
            policy.decide("requestUI", liveSession = true),
        )
        policy.finishRecovery()
        assertTrue(policy.decide("requestUI", liveSession = true).allowed)
    }

    @Test
    fun clockRollbackBehindRejectedObservationIsConservativelyBlocked() {
        var now = 10_000L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        assertTrue(policy.decide("requestUI", liveSession = true).allowed)
        policy.finishRecovery()
        now = 14_000L
        assertEquals("cooldown", policy.decide("requestUI", liveSession = true).reason)
        now = 13_000L
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "clock_rollback", null, 1),
            policy.decide("requestUI", liveSession = true),
        )
        now = 15_000L
        assertTrue(policy.decide("requestUI", liveSession = true).allowed)
    }

    @Test
    fun backwardClockCannotExpireExhaustedWindowOrRefillAllowance() {
        var now = 100_000L
        val policy = BmwRequestUiRecoveryPolicy("BMW Group MGU-50-P") { now }
        repeat(6) {
            assertTrue(policy.decide("requestUI", liveSession = true).allowed)
            policy.finishRecovery()
            now += 5_000L
        }
        now = 99_000L
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "clock_rollback", null, 6),
            policy.decide("requestUI", liveSession = true),
        )
        now = 130_000L
        assertEquals(
            BmwRequestUiRecoveryPolicy.Decision(false, "window_limit", 30_000L, 6),
            policy.decide("requestUI", liveSession = true),
        )
        now = 160_000L
        assertTrue(policy.decide("requestUI", liveSession = true).allowed)
    }
}
