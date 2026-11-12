package io.github.shaonialife.cardeck.net

/** One connection lifetime. An acknowledged BMW request can open only one pairing attempt. */
internal class CtrlInvitations {
    private data class Receipt(val timeMs: Long, val localAddress: String?, val receiver: Long?)

    @Volatile var count = 0
        private set
    private var pending: Receipt? = null

    @Synchronized
    fun acknowledge(nowMs: Long, localAddress: String?, receiverId: String?): Int {
        pending = Receipt(nowMs, localAddress?.substringBefore('%'), receiverId?.toLongOrNull())
        return ++count
    }

    /** Peek without consuming. A Wi-Fi handoff must still check the socket's local address
     * against the target network. A reconnect also requires an invitation after the stream ended. */
    @Synchronized
    fun localAddress(nowMs: Long, selectedReceiver: Long, afterCount: Int = 0): String? =
        if (count > afterCount) recentAddress(nowMs, selectedReceiver) else null

    private fun recentAddress(nowMs: Long, selectedReceiver: Long?): String? {
        val receipt = pending ?: return null
        // Bluetooth credentials and the WLAN callback arrived 11.3s after the same MGU's
        // invitation. Use the longer handoff window only for an explicitly selected receiver.
        val maxAgeMs = if (selectedReceiver != null) 15_000L else 10_000L
        if (nowMs - receipt.timeMs !in 0L..maxAgeMs ||
            selectedReceiver != null && receipt.receiver != selectedReceiver
        ) return null
        return receipt.localAddress
    }

    @Synchronized
    fun take(nowMs: Long, carAddresses: Set<String>, selectedReceiver: Long?, afterCount: Int = 0): Boolean {
        if (count <= afterCount || recentAddress(nowMs, selectedReceiver) !in carAddresses) return false
        pending = null
        return true
    }
}
