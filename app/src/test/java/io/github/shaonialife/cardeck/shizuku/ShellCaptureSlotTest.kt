package io.github.shaonialife.cardeck.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ShellCaptureSlotTest {
    @Test
    fun idleOnlyAfterActualReleaseAndNotOnCancel() {
        val slot = ShellCaptureSlot<String>()
        assertTrue(slot.isIdle())
        val lease = slot.claim("owner")!!
        assertFalse(slot.isIdle())
        slot.cancel("owner")
        assertFalse(slot.isIdle())
        slot.release(lease)
        assertTrue(slot.isIdle())
    }

    @Test
    fun onlyOnePendingOrRunningWorkerCanBeClaimed() {
        val slot = ShellCaptureSlot<String>()
        assertNotNull(slot.claim("first"))
        assertNull(slot.claim("first"))
        assertNull(slot.claim("second"))
    }

    @Test
    fun cancelledStartupRetainsSlotUntilActualCleanup() {
        val slot = ShellCaptureSlot<String>()
        val lease = slot.claim("first")!!
        slot.cancel("first")
        assertTrue(lease.cancelled)
        assertNull(slot.claim("second"))
        slot.release(lease)
        assertNotNull(slot.claim("second"))
    }

    @Test
    fun otherOwnerCannotCancelCurrentWorker() {
        val slot = ShellCaptureSlot<String>()
        val lease = slot.claim("current")!!
        slot.cancel("old")
        assertFalse(lease.cancelled)
    }

    @Test
    fun lateOldCleanupCannotReleaseNewLease() {
        val slot = ShellCaptureSlot<String>()
        val old = slot.claim("old")!!
        slot.release(old)
        val current = slot.claim("current")!!
        slot.release(old)
        assertFalse(current.cancelled)
        assertNull(slot.claim("third"))
    }

    @Test
    fun lateOldDeathCannotCancelReusedOwnerGeneration() {
        val slot = ShellCaptureSlot<String>()
        val old = slot.claim("owner")!!
        slot.release(old)
        val current = slot.claim("owner")!!
        old.cancel()
        assertFalse(current.cancelled)
    }

    @Test
    fun repeatedCancelAndReleaseAreIdempotent() {
        val slot = ShellCaptureSlot<String>()
        val lease = slot.claim("owner")!!
        repeat(3) { slot.cancel("owner") }
        repeat(3) { slot.release(lease) }
        assertTrue(lease.cancelled)
        assertNotNull(slot.claim("next"))
    }

    @Test
    fun cancellationDuringBlockedStartupIsVisibleOnLateReturn() {
        val slot = ShellCaptureSlot<String>()
        val lease = slot.claim("owner")!!
        val entered = CountDownLatch(1)
        val finishConstruction = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val starts = AtomicInteger()
        val releases = AtomicInteger()
        val worker = Thread {
            try {
                entered.countDown()
                finishConstruction.await(2, TimeUnit.SECONDS)
                if (!lease.cancelled) starts.incrementAndGet()
            } finally {
                releases.incrementAndGet()
                slot.release(lease)
                finished.countDown()
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            slot.cancel("owner")
            assertNull(slot.claim("next"))
        } finally {
            finishConstruction.countDown()
        }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertTrue(starts.get() == 0)
        assertTrue(releases.get() == 1)
        assertNotNull(slot.claim("next"))
    }
}
