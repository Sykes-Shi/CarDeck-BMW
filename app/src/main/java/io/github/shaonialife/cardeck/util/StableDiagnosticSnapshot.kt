package io.github.shaonialife.cardeck.util

/** An export must not associate a former session's data with a newly selected client. */
internal object StableDiagnosticSnapshot {
    fun <T : Any> read(current: () -> T?, collect: (T) -> List<String>): List<String> {
        val owner = current() ?: return listOf("unavailable=no_current_client")
        val result = runCatching { collect(owner) }.getOrNull()
        return when {
            current() !== owner -> listOf("unavailable=client_changed")
            result == null -> listOf("unavailable=snapshot_failed")
            else -> result
        }
    }
}
