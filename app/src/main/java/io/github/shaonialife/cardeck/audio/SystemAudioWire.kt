package io.github.shaonialife.cardeck.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 有界协议头后仅接 PCM S16_LE；头只在服务端录音成功启动后发送。 */
internal object SystemAudioWire {
    const val HEADER_BYTES = 16
    const val MAGIC = 0x43444131
    const val CHANNELS = 2
    const val ENCODING = 2

    fun supportedRate(sampleRate: Int): Boolean = sampleRate == 44_100 || sampleRate == 48_000

    fun header(sampleRate: Int): ByteArray {
        require(supportedRate(sampleRate)) { "unsupported_sample_rate" }
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).putInt(sampleRate).putInt(CHANNELS).putInt(ENCODING).array()
    }

    fun validateHeader(bytes: ByteArray, sampleRate: Int): Boolean {
        if (bytes.size != HEADER_BYTES || !supportedRate(sampleRate)) return false
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return buffer.int == MAGIC && buffer.int == sampleRate &&
            buffer.int == CHANNELS && buffer.int == ENCODING
    }
}
