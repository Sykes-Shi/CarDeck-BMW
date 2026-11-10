package io.github.shaonialife.cardeck.input

/** One gate per AirPlay session. Prewarm and button-up alone do not represent a voice request. */
internal class SteeringVoiceKey {
    private var downAt: Long? = null
    private var lastLaunchAt: Long? = null

    fun onAction(value: Any?, nowMs: Long): Boolean {
        val action = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Short -> value.toLong()
            is Byte -> value.toLong()
            else -> return false
        }
        if (action == 3L) downAt = null
        if (action != 2L) return false
        // Release may be lost over a broken link. Do not leave a later press latched forever.
        if (downAt?.let { nowMs - it in 0 until 15_000L } == true) return false
        downAt = nowMs
        if (lastLaunchAt?.let { nowMs - it in 0 until 400L } == true) return false
        lastLaunchAt = nowMs
        return true
    }
}
