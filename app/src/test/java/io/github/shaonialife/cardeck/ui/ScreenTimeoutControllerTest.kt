package io.github.shaonialife.cardeck.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class ScreenTimeoutControllerTest {
    private class Backend : ScreenTimeoutController.Backend {
        // 模拟旧进程遗留状态；本控制器没有创建备份或开启常亮的接口。
        var value = Int.MAX_VALUE
        var backup: Int? = 60_000
        var readFails = false
        var writeFails = false
        var clearFails = false
        var afterWrite: ((Int) -> Unit)? = null
        val events = mutableListOf<String>()
        val writes = mutableListOf<Int>()

        override fun readTimeout(): Int? {
            events += "read:$value"
            return if (readFails) null else value
        }

        override fun writeTimeout(value: Int): Boolean {
            events += "write:$value"
            writes += value
            if (writeFails) return false
            this.value = value
            afterWrite?.invoke(value)
            return true
        }

        override fun readBackup(): Int? = backup

        override fun clearBackup(): Boolean {
            events += "clear"
            if (clearFails) return false
            backup = null
            return true
        }
    }

    private class Harness {
        val backend = Backend()
        val pending = ArrayDeque<() -> Unit>()
        val controller = ScreenTimeoutController(backend, { pending.addLast(it) })

        fun drain() {
            var count = 0
            while (pending.isNotEmpty()) {
                check(++count < 100) { "Unexpected repeated restoration" }
                pending.removeFirst().invoke()
            }
        }

        fun restore() {
            controller.requestRestore()
            drain()
        }
    }

    @Test
    fun noBackupDoesNotReadOrWriteSystemTimeout() {
        for (timeout in listOf(30_000, Int.MAX_VALUE)) {
            val h = Harness()
            h.backend.backup = null
            h.backend.value = timeout
            h.restore()
            assertEquals(timeout, h.backend.value)
            assertTrue(h.backend.events.isEmpty())
        }
    }

    @Test
    fun restorationIsVerifiedBeforeClearingBackup() {
        val h = Harness()
        h.restore()
        assertEquals(60_000, h.backend.value)
        assertNull(h.backend.backup)
        assertTrue(h.backend.events.indexOf("read:60000") < h.backend.events.indexOf("clear"))
    }

    @Test
    fun repeatedRecoveryCallsRestoreAtMostOnce() {
        val h = Harness()
        repeat(3) { h.controller.requestRestore() }
        h.drain()
        h.restore()
        assertEquals(listOf(60_000), h.backend.writes)
        assertNull(h.backend.backup)
    }

    @Test
    fun failedRestoreRetainsBackupAndCanBeRetried() {
        val h = Harness()
        h.backend.writeFails = true
        h.restore()
        assertEquals(Int.MAX_VALUE, h.backend.value)
        assertEquals(60_000, h.backend.backup)
        h.backend.writeFails = false
        h.restore()
        assertEquals(60_000, h.backend.value)
        assertNull(h.backend.backup)
    }

    @Test
    fun failedVerificationRetainsBackupUntilAReadConfirmsRestoration() {
        val h = Harness()
        h.backend.afterWrite = { h.backend.readFails = true }
        h.restore()
        assertEquals(60_000, h.backend.backup)
        assertFalse(h.backend.events.contains("clear"))
        h.backend.readFails = false
        h.restore()
        assertEquals(60_000, h.backend.value)
        assertNull(h.backend.backup)
        assertEquals(listOf(60_000), h.backend.writes)
    }

    @Test
    fun unavailableShellDoesNotDiscardRecoveryValue() {
        val h = Harness()
        h.backend.readFails = true
        h.restore()
        assertEquals(60_000, h.backend.backup)
        assertTrue(h.backend.writes.isEmpty())
        h.backend.readFails = false
        h.restore()
        assertNull(h.backend.backup)
    }

    @Test
    fun userChangedTimeoutIsPreserved() {
        val h = Harness()
        h.backend.value = 30_000
        h.restore()
        assertEquals(30_000, h.backend.value)
        assertNull(h.backend.backup)
        assertTrue(h.backend.writes.isEmpty())
    }

    @Test
    fun invalidBackupDoesNotChangeSystemSetting() {
        for (backup in listOf(-1, 0, Int.MAX_VALUE)) {
            val h = Harness()
            h.backend.backup = backup
            h.restore()
            assertEquals(backup, h.backend.backup)
            assertEquals(Int.MAX_VALUE, h.backend.value)
            assertTrue(h.backend.events.isEmpty())
        }
    }

    @Test
    fun failedBackupClearIsRetriedWithoutOverwritingCurrentSetting() {
        val h = Harness()
        h.backend.clearFails = true
        h.restore()
        assertEquals(60_000, h.backend.value)
        assertNotNull(h.backend.backup)
        h.backend.clearFails = false
        h.backend.writes.clear()
        h.restore()
        assertTrue(h.backend.writes.isEmpty())
        assertNull(h.backend.backup)
    }

    @Test
    fun queuedRecoveryRechecksExternalCurrentValue() {
        val h = Harness()
        h.controller.requestRestore()
        h.backend.value = 120_000
        h.drain()
        assertEquals(120_000, h.backend.value)
        assertTrue(h.backend.writes.isEmpty())
        assertNull(h.backend.backup)
    }

    @Test
    fun reentrantRecoveryDuringWriteDoesNotReapplyGlobalSetting() {
        val h = Harness()
        h.backend.afterWrite = { h.controller.requestRestore() }
        h.restore()
        assertEquals(listOf(60_000), h.backend.writes)
        assertNull(h.backend.backup)
    }

    @Test
    fun externalChangeAfterRestoreWriteIsPreservedOnRetry() {
        val h = Harness()
        h.backend.afterWrite = { h.backend.value = 120_000 }
        h.restore()
        assertEquals(60_000, h.backend.backup)
        h.restore()
        assertEquals(120_000, h.backend.value)
        assertEquals(listOf(60_000), h.backend.writes)
        assertNull(h.backend.backup)
    }
}
