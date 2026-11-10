package io.github.shaonialife.cardeck.audio

import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer

/** 只抽出同步 codec 的输入边界，让背压、超时和整帧提交能用真实字节缓冲测试。 */
internal interface AacInputPort {
    fun dequeueInput(timeoutUs: Long): Int

    fun inputBuffer(index: Int): ByteBuffer?

    fun queueInput(index: Int, size: Int, presentationUs: Long)

    /** 必须非阻塞地取现成输出，并遵守同一单调时间截止点。 */
    fun drainOutput(deadlineNanos: Long)
}

internal object AacInputQueue {
    private const val TRY_AGAIN_LATER = -1
    const val MAX_WAIT_NANOS = 100_000_000L
    const val MAX_ATTEMPTS = 10
    const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * 暂无输入槽不等于这一帧可丢弃：先释放已完成输出，再限时重试同一 PCM。
     * 超出预算明确终止本轮音频，不让 RTP 继续却悄悄缩短音频采样时间线。
     */
    fun submit(
        port: AacInputPort,
        pcm: ByteArray,
        size: Int,
        presentationUs: Long,
        clockNanos: () -> Long = System::nanoTime,
        isInterrupted: () -> Boolean = { Thread.currentThread().isInterrupted },
    ) {
        if (size <= 0 || size > pcm.size) throw IOException("aac_invalid_pcm_size")
        val deadlineNanos = clockNanos() + MAX_WAIT_NANOS
        repeat(MAX_ATTEMPTS) {
            if (isInterrupted()) throw InterruptedIOException("aac_input_interrupted")
            val remainingNanos = deadlineNanos - clockNanos()
            if (remainingNanos <= 0) throw IOException("aac_input_timeout")
            val waitUs = minOf(DEQUEUE_TIMEOUT_US, remainingNanos / 1_000L)
            val index = port.dequeueInput(waitUs)
            if (isInterrupted()) throw InterruptedIOException("aac_input_interrupted")
            if (deadlineNanos - clockNanos() <= 0) throw IOException("aac_input_timeout")
            if (index >= 0) {
                val buffer = port.inputBuffer(index) ?: throw IOException("aac_input_buffer_missing")
                if (buffer.isReadOnly || buffer.capacity() < size) {
                    throw IOException("aac_input_buffer_too_small_or_read_only")
                }
                buffer.clear()
                buffer.put(pcm, 0, size)
                port.queueInput(index, size, presentationUs)
                return
            }
            if (index != TRY_AGAIN_LATER) throw IOException("aac_unexpected_input_status")
            port.drainOutput(deadlineNanos)
        }
        throw IOException("aac_input_timeout")
    }
}
