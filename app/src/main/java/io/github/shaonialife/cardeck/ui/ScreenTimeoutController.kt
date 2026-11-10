package io.github.shaonialife.cardeck.ui

/** 仅恢复旧版本保存的屏幕超时；新会话和暗幕不再改写全局超时。 */
internal class ScreenTimeoutController(
    private val backend: Backend,
    private val execute: (() -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
) {
    interface Backend {
        fun readTimeout(): Int?
        fun writeTimeout(value: Int): Boolean
        fun readBackup(): Int?
        fun clearBackup(): Boolean
    }

    companion object {
        const val LEGACY_KEEP_AWAKE_TIMEOUT = Int.MAX_VALUE
    }

    private val workerLock = Any()

    fun requestRestore() {
        execute { synchronized(workerLock) { restore() } }
    }

    private fun restore() {
        val backup = backend.readBackup() ?: return
        if (backup <= 0 || backup >= LEGACY_KEEP_AWAKE_TIMEOUT) {
            log("timeout_restore_invalid_backup_pending")
            return
        }
        val current = backend.readTimeout() ?: run {
            log("timeout_restore_read_failed")
            return
        }
        if (current != LEGACY_KEEP_AWAKE_TIMEOUT) {
            if (backend.clearBackup()) log("timeout_external_value_preserved")
            return
        }
        if (!backend.writeTimeout(backup) || backend.readTimeout() != backup) {
            log("timeout_restore_failed_pending")
            return
        }
        if (backend.clearBackup()) log("timeout_restored") else log("timeout_backup_clear_failed")
    }
}
