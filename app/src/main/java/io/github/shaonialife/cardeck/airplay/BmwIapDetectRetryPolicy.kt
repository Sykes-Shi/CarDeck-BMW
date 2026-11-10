package io.github.shaonialife.cardeck.airplay

/** 只补偿「DETECT 先于本轮车机邀请」；已有链路进展时不再发送探测，以免打断协商。 */
internal class BmwIapDetectRetryPolicy {
    private var inviteBaseline: Int? = null
    private var receivedBytes = false
    private var retried = false

    fun arm(currentInviteCount: Int) {
        if (inviteBaseline == null) inviteBaseline = currentInviteCount
    }

    fun noteIncomingBytes() {
        receivedBytes = true
    }

    fun shouldRetry(
        enabled: Boolean,
        currentInviteCount: Int,
        linkNegotiating: Boolean,
        alive: Boolean,
    ): Boolean {
        val baseline = inviteBaseline ?: return false
        if (!enabled || !alive || receivedBytes || !linkNegotiating || retried || currentInviteCount <= baseline) return false
        retried = true
        return true
    }
}
