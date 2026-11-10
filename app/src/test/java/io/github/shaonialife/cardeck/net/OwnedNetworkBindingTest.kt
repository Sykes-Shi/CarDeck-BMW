package io.github.shaonialife.cardeck.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedNetworkBindingTest {
    private data class NetworkId(val id: Int)

    private class Fixture {
        var processNetwork: NetworkId? = null
        var bindSucceeds = true
        val liveIds = mutableSetOf<Int>()
        val operations = mutableListOf<String>()
        val state = OwnedNetworkBinding<Any, NetworkId>(
            bind = { candidate ->
                operations += "bind:${candidate.id}"
                if (bindSucceeds) processNetwork = candidate
                bindSucceeds
            },
            unbind = { previous ->
                // 与 Android 接口层保持相同的绑定归属约定。
                if (processNetwork == previous) {
                    operations += "unbind:${previous.id}"
                    processNetwork = null
                }
            },
            isLive = { it.id in liveIds && processNetwork == it },
        )

        fun connect(owner: Any, id: Int): NetworkId {
            val network = NetworkId(id)
            liveIds += id
            state.replace(owner)
            assertEquals(OwnedNetworkBinding.BindResult.BOUND, state.available(owner, network))
            return network
        }
    }

    @Test
    fun androidLossWithEqualButDifferentNetworkObjectUnbinds() {
        val f = Fixture()
        val owner = Any()
        val original = f.connect(owner, 355)
        val callbackNetwork = original.copy()
        assertNotSame(original, callbackNetwork)
        assertNotNull(f.state.recordLoss(owner, callbackNetwork))
        assertNull(f.state.current())
        assertNull(f.processNetwork)
        assertEquals(listOf("bind:355", "unbind:355"), f.operations)
    }

    @Test
    fun lateAvailabilityFromCancelledRequestCannotRebindProcess() {
        val f = Fixture()
        val old = Any()
        f.connect(old, 355)
        f.state.release(old)
        val before = f.operations.toList()
        assertEquals(OwnedNetworkBinding.BindResult.STALE, f.state.available(old, NetworkId(355)))
        assertEquals(before, f.operations)
        assertNull(f.state.current())
    }

    @Test
    fun oldCallbacksAndOldWaiterCannotAffectReplacementWithSameNetworkId() {
        val f = Fixture()
        val old = Any()
        val next = Any()
        val oldNetwork = f.connect(old, 355)
        val nextNetwork = f.connect(next, 355)
        val before = f.operations.toList()
        assertNull(f.state.recordLoss(old, oldNetwork.copy()))
        assertEquals(OwnedNetworkBinding.BindResult.STALE, f.state.available(old, NetworkId(400)))
        assertNull(f.state.release(old))
        assertNull(f.state.current(old))
        assertNull(f.state.validatedCurrent(old))
        assertEquals(before, f.operations)
        assertSame(nextNetwork, f.state.current(next))
    }

    @Test
    fun equalOwnerValuesStillRepresentDistinctRequests() {
        data class Owner(val name: String)
        val f = Fixture()
        val owner = Owner("request")
        val impostor = owner.copy()
        val network = f.connect(owner, 355)
        assertEquals(owner, impostor)
        assertFalse(f.state.isCurrent(impostor))
        assertNull(f.state.recordLoss(impostor, network))
        assertNull(f.state.release(impostor))
        assertSame(network, f.state.current())
    }

    @Test
    fun failedPlatformBindingNeverPublishesAConnection() {
        val f = Fixture()
        val owner = Any()
        f.bindSucceeds = false
        f.state.replace(owner)
        assertEquals(OwnedNetworkBinding.BindResult.FAILED, f.state.available(owner, NetworkId(355)))
        assertNull(f.state.current())
        assertNull(f.processNetwork)
    }

    @Test
    fun failedReplacementBindingClearsPreviousNetworkForSameRequest() {
        val f = Fixture()
        val owner = Any()
        f.connect(owner, 355)
        f.bindSucceeds = false
        assertEquals(OwnedNetworkBinding.BindResult.FAILED, f.state.available(owner, NetworkId(362)))
        assertNull(f.state.current())
        assertNull(f.processNetwork)
        assertEquals(listOf("bind:355", "bind:362", "unbind:355"), f.operations)
    }

    @Test
    fun missingNetworkClearsCacheEvenWhenLostCallbackNeverArrived() {
        val f = Fixture()
        val owner = Any()
        f.connect(owner, 355)
        f.liveIds.clear()
        assertNull(f.state.validatedCurrent())
        assertNull(f.processNetwork)
        assertNull(f.state.current())
        assertTrue(f.state.isCurrent(owner))
        assertEquals(listOf("bind:355", "unbind:355"), f.operations)
    }

    @Test
    fun validationOrReleaseDoesNotUnbindAnotherComponentsNetwork() {
        for (validateFirst in listOf(true, false)) {
            val f = Fixture()
            f.connect(Any(), 355)
            val external = NetworkId(999)
            f.processNetwork = external
            if (validateFirst) assertNull(f.state.validatedCurrent())
            f.state.release()
            assertSame(external, f.processNetwork)
            assertEquals(listOf("bind:355"), f.operations)
        }
    }

    @Test
    fun lateLossForPreviousNetworkOfSameRequestKeepsCurrentConnection() {
        val f = Fixture()
        val owner = Any()
        val oldNetwork = f.connect(owner, 355)
        val replacement = NetworkId(362)
        f.liveIds += 362
        assertEquals(OwnedNetworkBinding.BindResult.BOUND, f.state.available(owner, replacement))
        assertNull(f.state.recordLoss(owner, oldNetwork.copy()))
        assertSame(replacement, f.state.validatedCurrent())
        assertSame(replacement, f.processNetwork)
    }

    @Test
    fun healthyLocalNetworkIsReusableWithoutInternetValidation() {
        val f = Fixture()
        val expected = f.connect(Any(), 355)
        assertSame(expected, f.state.validatedCurrent())
        assertEquals(listOf("bind:355"), f.operations)
    }

    @Test
    fun queuedLossIsRejectedAfterAnotherRequestReconnectsToSameNetwork() {
        val f = Fixture()
        val old = Any()
        val oldNetwork = f.connect(old, 355)
        val loss = f.state.recordLoss(old, oldNetwork.copy())!!
        assertTrue(f.state.isCurrentLoss(old, loss))
        val next = Any()
        f.connect(next, 355)
        assertFalse(f.state.isCurrentLoss(old, loss))
        assertTrue(f.state.isCurrent(next))
    }

    @Test
    fun queuedLossIsRejectedAfterSameRequestRecoversAndLosesAgain() {
        val f = Fixture()
        val owner = Any()
        val original = f.connect(owner, 355)
        val firstLoss = f.state.recordLoss(owner, original.copy())!!
        assertEquals(OwnedNetworkBinding.BindResult.BOUND, f.state.available(owner, original.copy()))
        assertFalse(f.state.isCurrentLoss(owner, firstLoss))
        val nextLoss = f.state.recordLoss(owner, original.copy())!!
        assertTrue(nextLoss > firstLoss)
        assertFalse(f.state.isCurrentLoss(owner, firstLoss))
        assertTrue(f.state.isCurrentLoss(owner, nextLoss))
    }

    @Test
    fun releasingLostRequestInvalidatesAlreadyQueuedLossNotification() {
        val f = Fixture()
        val owner = Any()
        val network = f.connect(owner, 355)
        val loss = f.state.recordLoss(owner, network.copy())!!
        f.state.release(owner)
        assertFalse(f.state.isCurrentLoss(owner, loss))
        assertNull(f.state.current())
    }

    @Test
    fun bindingInFlightCannotCompleteAfterReleaseAndResurrectNetwork() {
        val enteredBind = CountDownLatch(1)
        val finishBind = CountDownLatch(1)
        val releaseStarted = CountDownLatch(1)
        val process = AtomicReference<NetworkId?>()
        val failure = AtomicReference<Throwable?>()
        val state = OwnedNetworkBinding<Any, NetworkId>(
            bind = {
                enteredBind.countDown()
                check(finishBind.await(5, TimeUnit.SECONDS))
                process.set(it)
                true
            },
            unbind = { process.compareAndSet(it, null) },
            isLive = { true },
        )
        val owner = Any()
        state.replace(owner)
        val publisher = Thread {
            try {
                state.available(owner, NetworkId(355))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        val releaser = Thread {
            releaseStarted.countDown()
            state.release(owner)
        }
        try {
            publisher.start()
            assertTrue(enteredBind.await(5, TimeUnit.SECONDS))
            releaser.start()
            assertTrue(releaseStarted.await(5, TimeUnit.SECONDS))
        } finally {
            finishBind.countDown()
            publisher.join(5_000)
            if (releaser.state != Thread.State.NEW) releaser.join(5_000)
        }
        assertFalse(publisher.isAlive)
        assertFalse(releaser.isAlive)
        assertNull(failure.get())
        assertNull(state.current())
        assertNull(process.get())
        assertFalse(state.isCurrent(owner))
    }

    @Test
    fun staleValidationCannotClearNetworkPublishedByReplacementRequest() {
        val enteredValidation = CountDownLatch(1)
        val finishValidation = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val process = AtomicReference<NetworkId?>()
        val failure = AtomicReference<Throwable?>()
        val state = OwnedNetworkBinding<Any, NetworkId>(
            bind = {
                process.set(it)
                true
            },
            unbind = { process.compareAndSet(it, null) },
            isLive = {
                enteredValidation.countDown()
                check(finishValidation.await(5, TimeUnit.SECONDS))
                false
            },
        )
        val old = Any()
        val next = Any()
        val nextNetwork = NetworkId(362)
        state.replace(old)
        state.available(old, NetworkId(355))
        val validator = Thread {
            try {
                state.validatedCurrent(old)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        val replacer = Thread {
            replacementStarted.countDown()
            state.replace(next)
            state.available(next, nextNetwork)
        }
        try {
            validator.start()
            assertTrue(enteredValidation.await(5, TimeUnit.SECONDS))
            replacer.start()
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS))
        } finally {
            finishValidation.countDown()
            validator.join(5_000)
            if (replacer.state != Thread.State.NEW) replacer.join(5_000)
        }
        assertFalse(validator.isAlive)
        assertFalse(replacer.isAlive)
        assertNull(failure.get())
        assertSame(nextNetwork, state.current(next))
        assertSame(nextNetwork, process.get())
    }
}
