package io.github.shaonialife.cardeck.audio

/** 可靠管道报错只允许服务端约定的固定标签进入导出日志，不保留任意错误正文。 */
internal object SystemAudioFailureReason {
    private val reasons = setOf(
        "open_failed", "cancelled", "unsupported_uid", "invalid_shell_attribution", "unsupported_buffer",
        "invalid_shell_package", "invalid_shell_package_uid",
        "unsupported_record_format", "record_not_started", "record_read_failed", "record_stalled",
        "partial_pcm_frame", "consumer_stalled", "consumer_closed", "owner_gone",
    )
    private val stages = setOf(
        "shell_context", "query_buffer", "build_record", "verify_record", "prepare_pipe", "start_record",
        "write_header", "check_reader", "read_pcm", "write_pcm",
    )

    fun fromPipeError(message: String?): String {
        if (message == null || message.length > 128) return "unknown_remote_error"
        val value = message.removePrefix("Remote error: ")
        if (value in reasons) return value
        val separator = value.indexOf(':')
        if (separator <= 0 || value.substring(0, separator) !in stages) return "unknown_remote_error"
        val type = value.substring(separator + 1)
        return if (type.matches(Regex("[A-Za-z0-9]{1,80}"))) value else "unknown_remote_error"
    }
}
