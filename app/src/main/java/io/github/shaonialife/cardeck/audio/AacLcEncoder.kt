package io.github.shaonialife.cardeck.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.IOException
import java.nio.ByteBuffer

/**
 * PCM → AAC-LC 编码器（type=102 MainHighAudio 通路用）。
 *
 * 为什么需要它（2026-10-20 Honda HFT / Alpine 实测）：该车机 `/info` 里
 * `type=102`（MainHighAudio）的 `media` 档只声明 `0x400000` = **AAC-LC 44.1k 立体声**，
 * 没有任何 PCM 位；而 `type=100 media` 只声明 PCM 44.1k，往那里推 PCM 时车机
 * 收包却不消费（`/feedback` 里 `sampleTime` 恒 0），约 6.5s 后拆会话。
 * 真 iPhone 的媒体音频走的就是 102 + AAC-LC，所以必须自己编 AAC。
 *
 * 线格式（对照 catplay `aac_encoder.rs` + `rtp_recorder.rs`）：编码器输出的是
 * **裸 AAC 帧** —— 没有 ADTS 头、没有 RFC3640 的 AU header，RTP 负载就是该帧本身，
 * 一包一帧。Android `MediaCodec` 的 AAC 编码器同样输出裸帧，可直接使用。
 *
 * 帧长固定 1024 采样（AAC-LC 规格），因此 RTP 时间戳每包步进 1024。
 */
class AacLcEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitRate: Int = 192_000,
    /** 是否给每帧补 7 字节 ADTS 头（见 [AudioTuning.ADTS]）。 */
    private val wrapAdts: Boolean = false,
    private val log: (String) -> Unit,
) {
    companion object {
        /** AAC-LC 一帧固定 1024 个采样（每声道）。 */
        const val FRAME_SAMPLES = 1024

        /** 一帧 PCM 的字节数（16bit）。 */
        fun pcmBytes(channels: Int): Int = FRAME_SAMPLES * channels * 2

        /** ADTS 头长度（无 CRC）。 */
        private const val ADTS_HEADER_SIZE = 7

        /** ADTS 里的 audioObjectType：AAC-LC = 2（存的是 AOT-1）。 */
        private const val AAC_PROFILE_LC = 2

        private const val MIME = "audio/mp4a-latm"

        /**
         * 组 7 字节 ADTS 头（无 CRC）：MPEG-4 / AAC-LC / 对应采样率与声道数。
         *
         * 线上报文（RTP 负载）按 Apple 的约定是**裸 AAC 帧**，但个别车机的解码链是
         * 按 ADTS 组织的（Android `audio/aac-adts`），裸帧喂进去会被判为非帧。
         * 因此留一条可切换的封装路径（[AudioTuning.ADTS]）。
         */
        fun adtsHeader(
            payloadSize: Int,
            sampleRate: Int,
            channels: Int,
        ): ByteArray {
            val frameLength = payloadSize + ADTS_HEADER_SIZE
            val freqIndex = frequencyIndex(sampleRate)
            val header = ByteArray(ADTS_HEADER_SIZE)
            header[0] = 0xFF.toByte()
            // syncword 剩余 4 位 + MPEG-4(0) + layer(00) + protection_absent(1)
            header[1] = 0xF1.toByte()
            // profile=LC(2) 存的是 AOT-1；采样率索引 <<2；声道配置高 2 位
            header[2] =
                (
                    ((AAC_PROFILE_LC - 1) shl 6) or
                        ((freqIndex and 0x0F) shl 2) or
                        ((channels shr 2) and 0x03)
                ).toByte()
            // 声道配置低 2 位 + 帧长高 2 位
            header[3] = (((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
            header[4] = ((frameLength shr 3) and 0xFF).toByte()
            // 帧长低 3 位 + buffer_fullness=0x7FF（VBR 惯例）+ 单帧（低 2 位 0）
            header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
            header[6] = 0xFC.toByte()
            return header
        }

        /** 采样率 → ADTS 的 samplingFrequencyIndex。 */
        fun frequencyIndex(rate: Int): Int =
            when (rate) {
                96_000 -> 0
                88_200 -> 1
                64_000 -> 2
                48_000 -> 3
                44_100 -> 4
                32_000 -> 5
                24_000 -> 6
                22_050 -> 7
                16_000 -> 8
                12_000 -> 9
                11_025 -> 10
                8_000 -> 11
                7_350 -> 12
                else -> 4
            }

        /** MPEG-4 AudioSpecificConfig for the negotiated AAC-LC format (5/4/4 bits + GASpecificConfig flags). */
        internal fun decoderConfig(sampleRate: Int, channels: Int): ByteArray {
            val index = frequencyIndex(sampleRate)
            return byteArrayOf(((AAC_PROFILE_LC shl 3) or (index ushr 1)).toByte(),
                (((index and 1) shl 7) or (channels shl 3)).toByte())
        }

        /** Inspect a single encoded frame locally. Startup silence is a valid decoded result. */
        fun decodePeak(
            frame: ByteArray,
            sampleRate: Int,
            channels: Int,
            adts: Boolean = false,
        ): Pair<Int, Int>? {
            var created: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(decoderConfig(sampleRate, channels)))
                    if (adts) setInteger(MediaFormat.KEY_IS_ADTS, 1)
                }
                val codec = MediaCodec.createDecoderByType(MIME)
                created = codec
                codec.configure(format, null, null, 0)
                codec.start()
                val inputIndex = codec.dequeueInputBuffer(200_000L)
                if (inputIndex < 0) return null
                val input = codec.getInputBuffer(inputIndex) ?: return null
                input.clear()
                input.put(frame)
                codec.queueInputBuffer(inputIndex, 0, frame.size, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val info = MediaCodec.BufferInfo()
                val deadline = System.nanoTime() + 600_000_000L
                while (System.nanoTime() < deadline) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 60_000L)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER ||
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
                    ) {
                        continue
                    }
                    if (outputIndex < 0) continue
                    val output = codec.getOutputBuffer(outputIndex)
                    var peak = 0
                    var samples = 0
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        output.order(java.nio.ByteOrder.nativeOrder())
                        while (output.remaining() >= 2) {
                            val value = output.short.toInt()
                            val magnitude = if (value < 0) -value else value
                            if (magnitude > peak) peak = magnitude
                            samples++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (samples > 0) return peak to samples
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { created?.stop() }
                runCatching { created?.release() }
            }
        }
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    /** ADTS 剥离只提示一次，避免刷屏。 */
    private var adtsStripped = false

    /** ADTS 封装提示只打一次。 */
    private var adtsWrapped = false

    /** Log packet shape once, without retaining AAC bytes. */
    private var firstFrameLogged = false

    /** 每帧 PCM 输入字节数。 */
    val inputFrameBytes: Int = pcmBytes(channels)

    var started: Boolean = false
        private set

    fun start(): Boolean {
        if (started) return true
        var created: MediaCodec? = null
        return try {
            val format =
                MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputFrameBytes)
                }
            created = MediaCodec.createEncoderByType(MIME)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            codec = created
            presentationUs = 0L
            started = true
            log(
                "AUDIO: AAC-LC 编码器就绪（${sampleRate}Hz ${channels}ch " +
                    "${bitRate / 1000}kbps，帧=${FRAME_SAMPLES}采样/${inputFrameBytes}B）",
            )
            true
        } catch (error: Exception) {
            runCatching { created?.release() }
            codec = null
            log("AUDIO: AAC-LC 编码器启动失败 - ${error.message}（本档位放弃）")
            started = false
            false
        }
    }

    /**
     * 喂一帧 PCM（[inputFrameBytes] 字节），返回本次编出的全部 AAC 帧。
     *
     * 编码器内部有延迟（首帧通常要喂 1~2 帧才吐第一包），所以返回值可能为空，
     * 调用方按「拿到的帧逐个发包」处理即可。
     */
    fun encode(
        pcm: ByteArray,
        size: Int,
    ): List<ByteArray> {
        val encoder = codec ?: throw IOException("aac_encoder_not_started")
        val output = mutableListOf<ByteArray>()
        try {
            if (size != inputFrameBytes) throw IOException("aac_incomplete_pcm_frame")
            AacInputQueue.submit(
                port =
                    object : AacInputPort {
                        override fun dequeueInput(timeoutUs: Long): Int = encoder.dequeueInputBuffer(timeoutUs)

                        override fun inputBuffer(index: Int): ByteBuffer? = encoder.getInputBuffer(index)

                        override fun queueInput(index: Int, size: Int, presentationUs: Long) {
                            encoder.queueInputBuffer(index, 0, size, presentationUs, 0)
                        }

                        override fun drainOutput(deadlineNanos: Long) {
                            drain(encoder, output, deadlineNanos)
                        }
                    },
                pcm = pcm,
                size = size,
                presentationUs = presentationUs,
            )
            presentationUs += FRAME_SAMPLES * 1_000_000L / sampleRate
            drain(encoder, output)
        } catch (error: Exception) {
            val failure = error as? IOException ?: IOException("aac_codec_operation_failed", error)
            log("AUDIO: AAC 编码停止 - ${failure.message}")
            // AudioSender 的现有 catch/finally 负责停止采集和恢复音量；不能吞错后跳过本帧。
            throw failure
        }
        return output
    }

    private fun drain(
        encoder: MediaCodec,
        output: MutableList<ByteArray>,
        deadlineNanos: Long? = null,
    ) {
        while (true) {
            if (deadlineNanos != null && deadlineNanos - System.nanoTime() <= 0) {
                throw IOException("aac_input_timeout")
            }
            val index = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index < 0 -> return
                else -> {
                    val buffer = encoder.getOutputBuffer(index)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && bufferInfo.size > 0 && !isConfig) {
                        val data = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(data)
                        output += finalizeFrame(data)
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /**
     * 去掉 ADTS 头（若存在）。
     *
     * 线上格式要求是**裸 AAC 帧**（catplay `rtp_recorder` 直接把编码器输出当负载）。
     * 多数 MediaCodec 实现输出裸帧，但个别硬件编码器会带 7/9 字节 ADTS 头——带上就是
     * 非法负载，车机解码器会整条流丢弃。这里按同步字识别并剥掉，属于防御性处理。
     */
    private fun stripAdts(frame: ByteArray): ByteArray {
        if (frame.size < 7) return frame
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return frame
        // ADTS 的 protection_absent 位在第二个字节最低位：1=无 CRC（7 字节头），0=9 字节
        val headerSize = if ((b1 and 0x01) == 0x01) 7 else 9
        if (frame.size <= headerSize) return frame
        if (!adtsStripped) {
            adtsStripped = true
            log("AUDIO: 编码器输出带 ADTS 头（${headerSize}B）——已剥离，改发裸 AAC 帧")
        }
        return frame.copyOfRange(headerSize, frame.size)
    }

    /**
     * 按当前方案决定线上负载形态：裸帧（默认）或「ADTS 头 + 裸帧」。
     *
     * 先统一剥掉编码器可能自带的 ADTS（避免重复套头），再按方案决定是否重建。
     */
    private fun finalizeFrame(frame: ByteArray): ByteArray {
        val raw = stripAdts(frame)
        if (!adtsWrapped && wrapAdts) {
            adtsWrapped = true
            log("AUDIO: 已改为 ADTS 封装（每帧补 7 字节头，方案2）")
        }
        val out = if (wrapAdts) buildAdts(raw) else raw
        if (!firstFrameLogged) {
            firstFrameLogged = true
            log(
                "AUDIO: AAC 首帧 bytes=${out.size} rawBytes=${raw.size} adts=$wrapAdts",
            )
        }
        return out
    }

    /** 组 7 字节 ADTS 头（无 CRC）：MPEG-4 / LC / 对应采样率与声道。 */
    private fun buildAdts(raw: ByteArray): ByteArray = adtsHeader(raw.size, sampleRate, channels) + raw

    fun stop() {
        val encoder = codec ?: return
        codec = null
        started = false
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
    }
}
