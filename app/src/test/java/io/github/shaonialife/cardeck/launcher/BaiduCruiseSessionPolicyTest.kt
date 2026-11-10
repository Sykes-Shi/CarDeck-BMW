package io.github.shaonialife.cardeck.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduCruiseSessionPolicyTest {
    private fun claim(
        policy: BaiduCruiseSessionPolicy,
        enabled: Boolean = true,
        defaultPackage: String? = BaiduCruise.PACKAGE,
        queryOk: Boolean = true,
        tasks: Int = 0,
    ): Boolean = policy.tryClaimAutoStart(enabled, defaultPackage, queryOk, tasks)

    @Test
    fun disabledDoesNotConsumeFutureEnabledAttempt() {
        val policy = BaiduCruiseSessionPolicy()
        assertFalse(claim(policy, enabled = false))
        assertTrue(claim(policy))
    }

    @Test
    fun otherDefaultMapDoesNotLaunchBaiduOrConsumeAttempt() {
        val policy = BaiduCruiseSessionPolicy()
        assertFalse(claim(policy, defaultPackage = "com.autonavi.minimap"))
        assertTrue(claim(policy))
    }

    @Test
    fun missingDefaultMapDoesNotLaunchBaidu() {
        assertFalse(claim(BaiduCruiseSessionPolicy(), defaultPackage = null))
    }

    @Test
    fun failedTaskQueryIsNotTreatedAsAnEmptyTaskList() {
        val policy = BaiduCruiseSessionPolicy()
        assertFalse(claim(policy, queryOk = false))
        assertTrue(claim(policy))
    }

    @Test
    fun existingMapOnAnyDisplayPreservesItsPage() {
        val policy = BaiduCruiseSessionPolicy()
        assertFalse(claim(policy, tasks = 1))
        assertFalse(claim(policy, tasks = 3))
        assertTrue(claim(policy))
    }

    @Test
    fun unknownNegativeTaskCountIsRejected() {
        assertFalse(claim(BaiduCruiseSessionPolicy(), tasks = -1))
    }

    @Test
    fun claimedAttemptCannotRepeatEvenIfLaunchFailsOrPreferenceToggles() {
        val policy = BaiduCruiseSessionPolicy()
        assertTrue(claim(policy))
        assertFalse(claim(policy))
        assertFalse(claim(policy, enabled = false))
        assertFalse(claim(policy))
    }

    @Test
    fun newSessionCanClaimWithoutResettingAnOldSession() {
        val old = BaiduCruiseSessionPolicy()
        assertTrue(claim(old))
        assertTrue(claim(BaiduCruiseSessionPolicy()))
        assertFalse(claim(old))
    }

    @Test
    fun concurrentDashboardEntriesClaimOnlyOnce() {
        val policy = BaiduCruiseSessionPolicy()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val attempts =
                (1..8).map {
                    executor.submit<Boolean> {
                        start.await(2, TimeUnit.SECONDS)
                        claim(policy)
                    }
                }
            start.countDown()
            assertEquals(1, attempts.count { it.get(3, TimeUnit.SECONDS) })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}
