package io.github.shaonialife.cardeck.audio

/** SETUP 已成功后的激活边界；调用方串行执行，只有 RECORD 成功才允许静音及起流。 */
internal object AudioActivationSequence {
    fun afterSetup(
        isCurrent: () -> Boolean,
        record: () -> Boolean,
        start: () -> Boolean,
        stop: () -> Unit,
    ): Boolean {
        if (!isCurrent() || !record() || !isCurrent()) return false
        var started = false
        try {
            if (!start() || !isCurrent()) return false
            started = true
            return true
        } finally {
            // start 可能部分创建采集器后失败；退休发生于启动期间时也只清理这次实例。
            if (!started) stop()
        }
    }
}
