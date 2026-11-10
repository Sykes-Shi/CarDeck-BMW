package io.github.shaonialife.cardeck.input

import io.github.shaonialife.cardeck.launcher.CarShortcut
import java.security.MessageDigest
import java.util.Locale

/** The receiver supplies a UI context, not an Android intent or a physical button label. */
internal class BmwShortcutTarget private constructor(val token: String, val defaultPage: CarShortcut?) {
    companion object {
        // Bare, separately identifiable receiver controls may be learned. Exclude native UI,
        // calls, messages, Siri and general-purpose URLs, even when they contain no payload.
        private val learnable = Regex(
            """(?:(?:bmw|idrive|carplay)[.-])?(?:options?|settings|media|maps?|nav|navigation|navsearch|nowplaying|(?:hard)?key[.-]?[0-9]{1,3}):""",
        )

        fun from(command: Map<*, *>): BmwShortcutTarget? {
            if (command["type"] != "requestUI") return null
            val params = command["params"] as? Map<*, *> ?: return null
            val url = params["url"] as? String ?: return null
            // A qualified request may be for another display or context; leave it to UI recovery.
            if (params.size != 1 || url.length !in 2..48 || url.any { it.code > 127 }) return null
            val value = url.lowercase(Locale.ROOT)
            if (!learnable.matches(value)) return null
            val page = when (value) {
                "nowplaying:" -> CarShortcut.MEDIA
                "maps:" -> CarShortcut.MAP
                else -> null
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(("requestUI\u0000" + value).toByteArray(Charsets.US_ASCII))
            val token = "ui:" + digest.joinToString("") { "%02x".format(Locale.ROOT, it) }
            return BmwShortcutTarget(token, page)
        }
    }
}
