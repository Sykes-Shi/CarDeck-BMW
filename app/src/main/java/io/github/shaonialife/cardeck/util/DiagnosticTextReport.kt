package io.github.shaonialife.cardeck.util

/** One UTF-8 byte budget shared by the collector and exported file; truncation is always explicit. */
internal class DiagnosticTextReport {
    private val text = StringBuilder()
    private var bytes = 0
    private var truncated = false

    fun line(value: String) {
        if (truncated) return
        // A bounded prefix is enough to reject a single oversized line without a large UTF-8 allocation.
        val content = value.take(MAX_BYTES) + '\n'
        val size = content.toByteArray(Charsets.UTF_8).size
        if (value.length > MAX_BYTES || bytes + size > MAX_BYTES - MARKER_BYTES) {
            text.append(TRUNCATION_MARKER)
            truncated = true
            return
        }
        text.append(content)
        bytes += size
    }

    override fun toString(): String = text.toString()

    companion object {
        const val MAX_BYTES = 16_384
        const val TRUNCATION_MARKER = "AUDIO_DIAG truncated=true\n"
        private val MARKER_BYTES = TRUNCATION_MARKER.toByteArray(Charsets.UTF_8).size

        fun bound(value: String): String {
            if (value.length <= MAX_BYTES && value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return value
            val report = DiagnosticTextReport()
            // Work only on a bounded prefix, preserving complete lines and never splitting a UTF-8 character.
            val prefix = value.take(MAX_BYTES)
            val complete = if (prefix.length < value.length) prefix.substring(0, prefix.lastIndexOf('\n') + 1) else prefix
            complete.lineSequence().filter { it.isNotEmpty() }.forEach(report::line)
            report.truncate()
            return report.toString()
        }
    }

    private fun truncate() {
        if (!truncated) {
            text.append(TRUNCATION_MARKER)
            truncated = true
        }
    }
}
