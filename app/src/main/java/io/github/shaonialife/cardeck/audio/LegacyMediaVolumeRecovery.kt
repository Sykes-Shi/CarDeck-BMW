package io.github.shaonialife.cardeck.audio

/** 只解释旧版本的静音备份，不为新的系统声音采集生成任何音量覆盖。 */
internal object LegacyMediaVolumeRecovery {
    sealed interface Decision {
        data object PreserveCurrent : Decision
        data object Retry : Decision
        data class Restore(val volume: Int) : Decision
    }

    fun decide(saved: Int, current: Int, maximum: Int): Decision = when {
        current < 0 -> Decision.Retry
        current > 0 || saved <= 0 -> Decision.PreserveCurrent
        maximum <= 0 -> Decision.Retry
        else -> Decision.Restore(saved.coerceAtMost(maximum))
    }
}
