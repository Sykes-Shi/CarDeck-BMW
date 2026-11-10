package io.github.shaonialife.cardeck.net

/** 专用 Wi-Fi 请求失败后只重试一次；第二次失败仍可在当前连接内转入手动流程。 */
internal object WifiJoinRecovery {
    fun run(
        chooseRetryOrManual: () -> Boolean?,
        finishRetry: () -> Boolean,
        confirmManual: () -> Boolean,
        joinManually: () -> Unit,
    ) {
        when (chooseRetryOrManual()) {
            false -> joinManually()
            true -> if (!finishRetry() && confirmManual()) joinManually()
            null -> Unit
        }
    }
}
