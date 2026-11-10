package io.github.shaonialife.cardeck.audio

/** 只保留混音读取、编码和发送的数字摘要；不保存 PCM、编码内容或播放器身份。 */
internal class AudioSendStats(private val windowNs: Long = 5_000_000_000L) {
    data class FirstNonZero(
        val readStartedElapsedNs: Long,
        val observedElapsedNs: Long,
        val readNumber: Long,
    ) {
        fun fields(): String =
            "firstNonZeroReadStartedElapsedNs=$readStartedElapsedNs " +
                "firstNonZeroObservedElapsedNs=$observedElapsedNs firstNonZeroReadNumber=$readNumber"
    }

    data class Window(
        val startElapsedNs: Long,
        val endElapsedNs: Long,
        val readCalls: Long,
        val readBytes: Long,
        val readFailures: Long,
        val readMaxNs: Long,
        val encodeCalls: Long,
        val encodeFailures: Long,
        val encodeEmpty: Long,
        val encodeMaxNs: Long,
        val encodeMaxBatch: Int,
        val encodedFrames: Long,
        val sendCalls: Long,
        val sendFailures: Long,
        val sendMaxNs: Long,
        val packetsSent: Long,
        val wireBytesSent: Long,
        val sampleFramesSent: Long,
        val payloadMinBytes: Int?,
        val payloadMaxBytes: Int?,
        val wireMinBytes: Int?,
        val wireMaxBytes: Int?,
        val sendGapMinNs: Long?,
        val sendGapMaxNs: Long?,
    ) {
        val hasEvents: Boolean get() = readCalls + encodeCalls + sendCalls > 0

        fun fields(): String =
            "startElapsedNs=$startElapsedNs endElapsedNs=$endElapsedNs " +
                "readCalls=$readCalls readBytes=$readBytes readFailures=$readFailures readMaxUs=${readMaxNs / 1_000} " +
                "encodeCalls=$encodeCalls encodeFailures=$encodeFailures encodeEmpty=$encodeEmpty " +
                "encodeMaxUs=${encodeMaxNs / 1_000} encodeMaxBatch=$encodeMaxBatch encodedFrames=$encodedFrames " +
                "sendCalls=$sendCalls sendFailures=$sendFailures sendMaxUs=${sendMaxNs / 1_000} " +
                "packetsSent=$packetsSent wireBytesSent=$wireBytesSent sampleFramesSent=$sampleFramesSent " +
                "payloadMinBytes=${payloadMinBytes ?: "none"} payloadMaxBytes=${payloadMaxBytes ?: "none"} " +
                "wireMinBytes=${wireMinBytes ?: "none"} wireMaxBytes=${wireMaxBytes ?: "none"} " +
                "sendGapMinUs=${sendGapMinNs?.div(1_000) ?: "none"} sendGapMaxUs=${sendGapMaxNs?.div(1_000) ?: "none"}"
    }

    data class Snapshot(
        val sampledAtElapsedNs: Long,
        val firstNonZero: FirstNonZero?,
        val lastSuccessfulSendElapsedNs: Long?,
        val totalPacketsSent: Long,
        val totalSampleFramesSent: Long,
        val current: Window?,
        val latestComplete: Window?,
    ) {
        fun fields(): String =
            "sampledAtElapsedNs=$sampledAtElapsedNs " +
                "lastSendAgeMs=${lastSuccessfulSendElapsedNs?.let { (sampledAtElapsedNs - it).coerceAtLeast(0) / 1_000_000 } ?: "none"} " +
                "totalPacketsSent=$totalPacketsSent totalSampleFramesSent=$totalSampleFramesSent " +
                (firstNonZero?.fields() ?: "firstNonZeroObservedElapsedNs=none")
    }

    private var started = false
    private var start = 0L
    private var firstNonZero: FirstNonZero? = null
    private var totalReadCalls = 0L
    private var totalPacketsSent = 0L
    private var totalSampleFramesSent = 0L
    private var lastSuccessfulSendAt: Long? = null
    private var latestComplete: Window? = null
    private var readCalls = 0L
    private var readBytes = 0L
    private var readFailures = 0L
    private var readMaxNs = 0L
    private var encodeCalls = 0L
    private var encodeFailures = 0L
    private var encodeEmpty = 0L
    private var encodeMaxNs = 0L
    private var encodeMaxBatch = 0
    private var encodedFrames = 0L
    private var sendCalls = 0L
    private var sendFailures = 0L
    private var sendMaxNs = 0L
    private var packetsSent = 0L
    private var wireBytesSent = 0L
    private var sampleFramesSent = 0L
    private var payloadMinBytes: Int? = null
    private var payloadMaxBytes: Int? = null
    private var wireMinBytes: Int? = null
    private var wireMaxBytes: Int? = null
    private var sendGapMinNs: Long? = null
    private var sendGapMaxNs: Long? = null

    init {
        require(windowNs > 0)
    }

    @Synchronized
    fun reset(nowElapsedNs: Long) {
        started = true
        firstNonZero = null
        totalReadCalls = 0
        totalPacketsSent = 0
        totalSampleFramesSent = 0
        lastSuccessfulSendAt = null
        latestComplete = null
        clearWindow(nowElapsedNs)
    }

    /** 时间是读取返回时观察到非零的时刻；不是歌曲开始播放或车机实际出声的时刻。 */
    @Synchronized
    fun recordRead(pcm: ByteArray, bytesRead: Int, startedNs: Long, endedNs: Long): FirstNonZero? {
        if (!started) reset(startedNs)
        readCalls++
        totalReadCalls++
        readMaxNs = maxOf(readMaxNs, (endedNs - startedNs).coerceAtLeast(0))
        if (bytesRead < 0) {
            readFailures++
            return null
        }
        val valid = bytesRead.coerceAtMost(pcm.size)
        readBytes += valid
        // 不碰缓冲尾部旧数据；只检查完整的 16 位样本，且找到一次后不再扫描。
        if (firstNonZero == null) {
            var index = 0
            while (index + 1 < valid) {
                if (pcm[index] != 0.toByte() || pcm[index + 1] != 0.toByte()) {
                    return FirstNonZero(startedNs, endedNs, totalReadCalls).also { firstNonZero = it }
                }
                index += 2
            }
        }
        return null
    }

    @Synchronized
    fun recordEncode(startedNs: Long, endedNs: Long, outputFrames: Int, failed: Boolean = false) {
        if (!started) reset(startedNs)
        encodeCalls++
        encodeMaxNs = maxOf(encodeMaxNs, (endedNs - startedNs).coerceAtLeast(0))
        if (failed) encodeFailures++ else {
            if (outputFrames == 0) encodeEmpty++
            val count = outputFrames.coerceAtLeast(0)
            encodeMaxBatch = maxOf(encodeMaxBatch, count)
            encodedFrames += count
        }
    }

    /** 发送耗时包括封包与 send 调用；成功包间隔按 send 返回时刻，不冒充线上到达时间。 */
    @Synchronized
    fun recordSend(startedNs: Long, endedNs: Long, payloadBytes: Int, wireBytes: Int, samples: Long, success: Boolean) {
        if (!started) reset(startedNs)
        sendCalls++
        sendMaxNs = maxOf(sendMaxNs, (endedNs - startedNs).coerceAtLeast(0))
        if (!success) {
            sendFailures++
            return
        }
        packetsSent++
        totalPacketsSent++
        wireBytesSent += wireBytes.coerceAtLeast(0)
        sampleFramesSent += samples.coerceAtLeast(0)
        totalSampleFramesSent += samples.coerceAtLeast(0)
        payloadMinBytes = minOf(payloadMinBytes ?: payloadBytes, payloadBytes)
        payloadMaxBytes = maxOf(payloadMaxBytes ?: payloadBytes, payloadBytes)
        wireMinBytes = minOf(wireMinBytes ?: wireBytes, wireBytes)
        wireMaxBytes = maxOf(wireMaxBytes ?: wireBytes, wireBytes)
        lastSuccessfulSendAt?.let { last ->
            val gap = (endedNs - last).coerceAtLeast(0)
            sendGapMinNs = minOf(sendGapMinNs ?: gap, gap)
            sendGapMaxNs = maxOf(sendGapMaxNs ?: gap, gap)
        }
        lastSuccessfulSendAt = endedNs
    }

    /** 调用方在锁外写日志；停止时可取部分窗口，迟到完成的操作另记最后一小段。 */
    @Synchronized
    fun takeWindow(nowElapsedNs: Long, force: Boolean = false): Window? {
        if (!started || (!force && nowElapsedNs - start < windowNs)) return null
        val value = window(nowElapsedNs)
        if (!value.hasEvents) return null
        clearWindow(nowElapsedNs)
        latestComplete = value
        return value
    }

    @Synchronized
    fun snapshot(nowElapsedNs: Long): Snapshot = Snapshot(
        nowElapsedNs, firstNonZero, lastSuccessfulSendAt, totalPacketsSent, totalSampleFramesSent,
        if (started) window(nowElapsedNs) else null, latestComplete,
    )

    private fun window(now: Long): Window = Window(
        start, now, readCalls, readBytes, readFailures, readMaxNs,
        encodeCalls, encodeFailures, encodeEmpty, encodeMaxNs, encodeMaxBatch, encodedFrames,
        sendCalls, sendFailures, sendMaxNs, packetsSent, wireBytesSent, sampleFramesSent,
        payloadMinBytes, payloadMaxBytes, wireMinBytes, wireMaxBytes, sendGapMinNs, sendGapMaxNs,
    )

    private fun clearWindow(now: Long) {
        start = now
        readCalls = 0
        readBytes = 0
        readFailures = 0
        readMaxNs = 0
        encodeCalls = 0
        encodeFailures = 0
        encodeEmpty = 0
        encodeMaxNs = 0
        encodeMaxBatch = 0
        encodedFrames = 0
        sendCalls = 0
        sendFailures = 0
        sendMaxNs = 0
        packetsSent = 0
        wireBytesSent = 0
        sampleFramesSent = 0
        payloadMinBytes = null
        payloadMaxBytes = null
        wireMinBytes = null
        wireMaxBytes = null
        sendGapMinNs = null
        sendGapMaxNs = null
    }
}
