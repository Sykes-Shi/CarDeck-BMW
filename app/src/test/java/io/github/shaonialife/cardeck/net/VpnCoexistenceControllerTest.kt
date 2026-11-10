package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VpnCoexistenceControllerTest {
    @Test fun bothProjectionModesHoldCellularAndRespectBlockedStateUntilDisconnect() {
        for (diagnostics in listOf(false, true)) {
            val backend = FakeBackend()
            val messages = mutableListOf<String>()
            val controller = VpnCoexistenceController(backend, messages::add)
            controller.start(detailedDiagnostics = diagnostics)
            assertEquals(3, backend.calls.size)
            val visible = backend.calls.first().listener
            visible(CoexistenceNetworkEvent("blocked", 17L, "sample", true))
            assertTrue(controller.isBlocked(17L))
            assertEquals(diagnostics, messages.any { it.contains("visibleApp sample") })
            visible(CoexistenceNetworkEvent("lost", 17L))
            assertTrue(!controller.isBlocked(17L))
            visible(CoexistenceNetworkEvent("blocked", 18L, "sample", true))
            controller.stop()
            assertTrue(!controller.isBlocked(18L))
            assertTrue(backend.calls.all { it.closed == 1 })
        }
    }

    @Test
    fun repeatedStartHasOnlyOneRequestAndStopClosesEveryRegistrationOnce() {
        val backend = FakeBackend()
        val controller = VpnCoexistenceController(backend, {})
        repeat(4) { controller.start() }
        assertEquals(listOf("visible", "default", "cellular"), backend.calls.map { it.role })
        repeat(4) { controller.stop() }
        assertEquals(listOf(1, 1, 1), backend.calls.map { it.closed })
    }

    @Test
    fun partialRegistrationFailureRollsBackAndNextStartCanRetry() {
        for (failureAt in 1..3) {
            val backend = FakeBackend().apply { failAt = failureAt }
            val controller = VpnCoexistenceController(backend, {})
            controller.start()
            assertTrue(backend.calls.all { it.closed == 1 })
            val oldCount = backend.calls.size
            backend.failAt = null
            controller.start()
            assertEquals(oldCount + 3, backend.calls.size)
            controller.stop()
            assertTrue(backend.calls.all { it.closed == 1 })
        }
    }

    @Test
    fun stopDuringRegistrationClosesLateHandleAndDoesNotRegisterMore() {
        val backend = FakeBackend()
        lateinit var controller: VpnCoexistenceController
        backend.afterRegister = { if (it.role == "visible") controller.stop() }
        controller = VpnCoexistenceController(backend, {})
        controller.start()
        assertEquals(1, backend.calls.size)
        assertEquals(1, backend.calls.single().closed)
    }

    @Test
    fun unavailableCellularRollsBackAndOldCallbacksCannotStopNewSession() {
        val backend = FakeBackend()
        val messages = mutableListOf<String>()
        val controller = VpnCoexistenceController(backend, messages::add)
        controller.start()
        val oldCalls = backend.calls.toList()
        oldCalls.last().listener(CoexistenceNetworkEvent("unavailable"))
        assertTrue(oldCalls.all { it.closed == 1 })
        controller.start()
        val messageCount = messages.size
        oldCalls.forEach {
            it.listener(CoexistenceNetworkEvent("unavailable"))
            it.listener(CoexistenceNetworkEvent("capabilities", 17L, "old"))
        }
        assertEquals(messageCount, messages.size)
        assertTrue(backend.calls.takeLast(3).all { it.closed == 0 })
        controller.stop()
    }

    @Test
    fun oneUnregisterFailureStillAttemptsAllOtherUnregisters() {
        val backend = FakeBackend()
        val controller = VpnCoexistenceController(backend, {})
        controller.start()
        backend.calls.first().throwOnClose = true
        controller.stop()
        assertTrue(backend.calls.all { it.closed == 1 })
        controller.start()
        assertEquals(6, backend.calls.size)
        controller.stop()
    }

    @Test
    fun concurrentStartAndStopDoNotLeakThePendingRegistration() {
        val backend = FakeBackend()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.afterRegister = {
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
        }
        val controller = VpnCoexistenceController(backend, {})
        val worker = Executors.newSingleThreadExecutor()
        try {
            val task = worker.submit { controller.start() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.start()
            controller.stop()
            release.countDown()
            task.get(2, TimeUnit.SECONDS)
            assertEquals(1, backend.calls.size)
            assertEquals(1, backend.calls.single().closed)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun duplicateCapabilitiesAreQuietButVpnChangesRemainObservableAfterRateWindow() {
        var now = 0L
        val backend = FakeBackend()
        val messages = mutableListOf<String>()
        val controller = VpnCoexistenceController(backend, messages::add) { now }
        controller.start()
        val baseline = messages.size
        val listener = backend.calls.first().listener
        val initial = CoexistenceNetworkEvent("capabilities", 17L, "initial")
        repeat(100) { listener(initial) }
        assertEquals(baseline + 1, messages.size)
        repeat(100) { listener(CoexistenceNetworkEvent("capabilities", 17L, "changed-$it")) }
        val limitedCount = messages.size
        assertTrue(limitedCount <= baseline + 41)
        now = 60_000L
        listener(CoexistenceNetworkEvent("capabilities", 18L, "vpn appeared"))
        assertEquals(limitedCount + 1, messages.size)
        listener(CoexistenceNetworkEvent("lost", 18L))
        assertEquals(limitedCount + 2, messages.size)
        controller.stop()
    }

    @Test
    fun repeatedLossIsObservableAfterTheSameNetworkReturns() {
        val backend = FakeBackend()
        val messages = mutableListOf<String>()
        val controller = VpnCoexistenceController(backend, messages::add)
        controller.start()
        val listener = backend.calls.first().listener
        val baseline = messages.size
        repeat(2) {
            listener(CoexistenceNetworkEvent("available", 17L))
            listener(CoexistenceNetworkEvent("lost", 17L))
        }
        assertEquals(baseline + 4, messages.size)
        controller.stop()
    }

    @Test
    fun diagnosticSinkFailureDoesNotPreventRegistrationOrCleanup() {
        val backend = FakeBackend()
        val controller = VpnCoexistenceController(backend, { throw IllegalStateException("log failed") })
        controller.start()
        backend.calls.first().listener(CoexistenceNetworkEvent("capabilities", 17L))
        controller.stop()
        assertEquals(3, backend.calls.size)
        assertTrue(backend.calls.all { it.closed == 1 })
    }

    private class FakeBackend : VpnCoexistenceBackend {
        class Call(val role: String, val listener: (CoexistenceNetworkEvent) -> Unit) : VpnCoexistenceBackend.Registration {
            var closed = 0
            var throwOnClose = false

            override fun close() {
                closed++
                if (throwOnClose) throw IllegalStateException("unregister failed")
            }
        }

        val calls = mutableListOf<Call>()
        var failAt: Int? = null
        var afterRegister: (Call) -> Unit = {}
        private var attempts = 0

        override fun requestCellular(listener: (CoexistenceNetworkEvent) -> Unit) = register("cellular", listener)

        override fun observeNetworks(listener: (CoexistenceNetworkEvent) -> Unit) = register("visible", listener)

        override fun observeDefault(listener: (CoexistenceNetworkEvent) -> Unit) = register("default", listener)

        private fun register(role: String, listener: (CoexistenceNetworkEvent) -> Unit): Call {
            attempts++
            if (attempts == failAt) throw SecurityException("not permitted")
            return Call(role, listener).also {
                calls.add(it)
                afterRegister(it)
            }
        }
    }
}
