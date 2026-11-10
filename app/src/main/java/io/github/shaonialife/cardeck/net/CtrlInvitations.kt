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

    @Synchronized
    fun take(nowMs: Long, carAddresses: Set<String>, selectedReceiver: Long?): Boolean {
        val receipt = pending ?: return false
        if (nowMs - receipt.timeMs !in 0L..10_000L || receipt.localAddress !in carAddresses ||
            (selectedReceiver != null && selectedReceiver != receipt.receiver)
        ) return false
        pending = null
        return true
    }
}
