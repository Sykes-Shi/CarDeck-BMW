package io.github.shaonialife.cardeck.input

import java.security.MessageDigest
import java.util.Locale

/** Per-connection anonymous labels for comparing manual test rounds. Never used for routing. */
internal class BmwShortcutObservations {
    private val uiTargets = mutableMapOf<String, Int>()
    private val modeReasons = mutableMapOf<String, Int>()
    private val bareScheme = Regex("[A-Za-z][A-Za-z0-9.+-]{0,46}:")

    fun record(command: Map<*, *>, rotary: Boolean, note: (String) -> Unit) {
        val params = command["params"] as? Map<*, *>
        when (command["type"]) {
            "requestUI" -> {
                val url = params?.get("url")
                note(if (url == null || url == "") "requestui_empty" else "requestui_target")
                uiTarget(url)?.let(note)
            }
            "changeModes" -> {
                note("changemodes")
                modeReason(params?.get("reasonStr"))?.let(note)
                val resources = params?.get("resources") as? List<*> ?: return
                for (entry in resources.take(32)) {
                    val row = entry as? Map<*, *> ?: continue
                    if ((row["resourceID"] as? Number)?.toLong() != 1L) continue
                    when ((row["transferType"] as? Number)?.toLong()) {
                        1L -> note("screen_take")
                        3L -> note("screen_borrow")
                        4L -> note("screen_return")
                    }
                }
            }
            "hidSendReport" -> note(if (rotary) "hid_rotary" else "hid")
        }
    }

    fun uiTarget(value: Any?): String? {
        if (value !is String || value.length > 48 || !bareScheme.matches(value)) return null
        return label(uiTargets, "ui_target", value.lowercase(Locale.ROOT))
    }

    fun modeReason(value: Any?): String? {
        if (value !is String || value.isBlank() || value.length > 160 || value.any(Char::isISOControl)) return null
        return label(modeReasons, "mode_reason", value)
    }

    @Synchronized
    private fun label(labels: MutableMap<String, Int>, prefix: String, value: String): String? {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }
        val id = labels[digest] ?: run {
            if (labels.size >= 16) return null
            (labels.size + 1).also { labels[digest] = it }
        }
        return "${prefix}_$id"
    }

}
