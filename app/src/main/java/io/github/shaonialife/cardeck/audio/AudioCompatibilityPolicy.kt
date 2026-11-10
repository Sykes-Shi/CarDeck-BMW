package io.github.shaonialife.cardeck.audio

import io.github.shaonialife.cardeck.airplay.BmwControlIapCompatibility

/**
 * BMW MGU-50-P 的音频兼容记录边界。
 *
 * v6 实测中，未完成 iAP 握手的会话在音频起流 19 秒后断开，被旧逻辑误记为音频
 * 不兼容；另一轮音频采集全零却持续超过 20 秒。存活时长不能作为本车机的有声结论。
 * 只忽略本次选择所用的历史记录，不清除持久化内容，也不影响其他接收端。
 */
internal object AudioCompatibilityPolicy {
    fun entryForSession(
        identity: String,
        remembered: AudioProfileMemory.Entry,
    ): AudioProfileMemory.Entry =
        if (BmwControlIapCompatibility.matches(identity)) AudioProfileMemory.Entry() else remembered

    /** 不接收时长参数：BMW 的短时拆链与长时存活都不参与自动兼容判定。 */
    fun allowDurationAttribution(identity: String): Boolean = !BmwControlIapCompatibility.matches(identity)
}
