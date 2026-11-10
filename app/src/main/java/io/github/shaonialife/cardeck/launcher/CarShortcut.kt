package io.github.shaonialife.cardeck.launcher

enum class CarShortcut { MEDIA, MAP, OPTION, NAV }

/** One accepted edge owns its launch. A different action, session or subsequent press supersedes it. */
internal class CarShortcutGate {
    class Ticket(val action: CarShortcut, val session: Long, val whenMs: Long)
    private var latest: Ticket? = null
    private var pending = false

    @Synchronized
    fun claim(action: CarShortcut, session: Long, nowMs: Long): Ticket? {
        val old = latest
        if (old != null && old.session == session && old.action == action &&
            (pending || nowMs - old.whenMs in 0 until 550L)
        ) return null
        return Ticket(action, session, nowMs).also { latest = it; pending = true }
    }

    @Synchronized fun owns(ticket: Ticket): Boolean = latest === ticket

    @Synchronized fun finished(ticket: Ticket) {
        if (latest === ticket) pending = false
    }

    @Synchronized fun reset() {
        latest = null
        pending = false
    }
}

/** The dedicated music key should not select a video or short-video session. */
internal object ShortcutMusic {
    val known = listOf("com.netease.cloudmusic", "com.spotify.music", "com.tencent.qqmusic",
        "com.kugou.android", "cn.kuwo.player", "com.apple.android.music",
        "com.google.android.apps.youtube.music", "com.sec.android.app.music")

    fun choose(current: String?, playing: Set<String>, availableSessions: Set<String>, lastUsed: String?, installed: (String) -> Boolean): String? {
        val playable = known.filter(installed)
        return current?.takeIf { it in playable && it in playing }
            ?: playable.firstOrNull { it in playing }
            ?: current?.takeIf { it in playable && it in availableSessions }
            ?: lastUsed?.takeIf { it in playable && it in availableSessions }
            ?: playable.firstOrNull { it in availableSessions }
            ?: lastUsed?.takeIf { it in playable }
            ?: playable.firstOrNull()
    }
}
