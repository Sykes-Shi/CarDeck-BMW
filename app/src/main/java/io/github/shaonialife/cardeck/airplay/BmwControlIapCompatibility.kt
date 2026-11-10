package io.github.shaonialife.cardeck.airplay

/** 仅对这次实车 /info 确认的制造商和型号启用控制链路实验，不扩展到 BMW 全系。 */
internal object BmwControlIapCompatibility {
    fun matches(receiverIdentity: String): Boolean =
        receiverIdentity.trim().split(Regex("\\s+")).joinToString(" ")
            .equals("BMW Group MGU-50-P", ignoreCase = true)
}
