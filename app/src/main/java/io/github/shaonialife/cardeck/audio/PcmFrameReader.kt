package io.github.shaonialife.cardeck.audio

/** 管道可能在单个采样的两个字节之间分片；只输出完整的编码/RTP 输入帧。 */
internal class PcmFrameReader(private val frameBytes: Int) {
    private val scratch = ByteArray(frameBytes)

    init {
        require(frameBytes > 0 && frameBytes % 4 == 0)
    }

    fun readFrame(
        target: ByteArray,
        isCancelled: () -> Boolean,
        readChunk: (ByteArray, Int) -> Int,
        onRead: (ByteArray, Int) -> Unit,
    ): Int {
        require(target.size == frameBytes)
        var filled = 0
        while (filled < frameBytes) {
            if (isCancelled()) return -1
            val requested = frameBytes - filled
            val read = readChunk(scratch, requested)
            if (read < 0) return -1
            check(read <= requested) { "PCM read exceeded requested bytes" }
            if (read == 0) continue
            onRead(scratch, read)
            scratch.copyInto(target, filled, 0, read)
            filled += read
        }
        return if (isCancelled()) -1 else filled
    }
}

/** 仅同一持有者可释放；迟到的打开/清理不能清除后续实例已取得的占位。 */
internal class CaptureOwnerGate {
    private var owner: Any? = null

    @Synchronized
    fun acquire(candidate: Any): Boolean {
        if (owner != null) return false
        owner = candidate
        return true
    }

    @Synchronized
    fun release(candidate: Any): Boolean {
        if (owner !== candidate) return false
        owner = null
        return true
    }
}
