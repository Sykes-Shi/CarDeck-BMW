package io.github.shaonialife.cardeck.audio

/** 只统计实际读到的 PCM 数量和幅值，不保留音频；编码器一次吐出多帧也不会跳过窗口。 */
internal class PcmCaptureStats(private val windowMs: Long = 5_000L) {
    data class Window(
        val runId: Long,
        val startElapsedMs: Long,
        val endElapsedMs: Long,
        val lastReadElapsedMs: Long?,
        val readCalls: Long,
        val pcmReads: Long,
        val pcmBytes: Long,
        val sampleValues: Long,
        val nonZeroValues: Long,
        val peak: Int,
        val shortReads: Long,
        val emptyReads: Long,
        val errorReads: Long,
    ) {
        fun fields(): String =
            "startElapsedMs=$startElapsedMs endElapsedMs=$endElapsedMs " +
                "lastReadElapsedMs=${lastReadElapsedMs ?: "none"} readCalls=$readCalls " +
                "pcmReads=$pcmReads pcmBytes=$pcmBytes sampleValues=$sampleValues " +
                "nonZeroValues=$nonZeroValues nonZeroPercent=" +
                (if (sampleValues > 0) nonZeroValues * 100 / sampleValues else "unknown") +
                " peak=$peak shortReads=$shortReads emptyReads=$emptyReads errorReads=$errorReads"
    }

    data class Snapshot(
        val runId: Long,
        val startedAtElapsedMs: Long?,
        val sampledAtElapsedMs: Long,
        val lastReadElapsedMs: Long?,
        val current: Window?,
        val latestComplete: Window?,
    ) {
        val lastReadAgeMs: Long?
            get() = lastReadElapsedMs?.let { (sampledAtElapsedMs - it).coerceAtLeast(0L) }
    }

    private var runId = 0L
    private var startedAt: Long? = null
    private var start = 0L
    private var lastReadAt: Long? = null
    private var windowLastReadAt: Long? = null
    private var readCalls = 0L
    private var pcmReads = 0L
    private var pcmBytes = 0L
    private var sampleValues = 0L
    private var nonZeroValues = 0L
    private var peak = 0
    private var shortReads = 0L
    private var emptyReads = 0L
    private var errorReads = 0L
    private var latestComplete: Window? = null

    init {
        require(windowMs > 0L)
    }

    @Synchronized
    fun reset(nowElapsedMs: Long) {
        runId++
        startedAt = nowElapsedMs
        lastReadAt = null
        latestComplete = null
        clearWindow(nowElapsedMs)
    }

    /** expectedBytes 是编码器需要的整块大小；短读照实统计，原发送路径仍决定是否丢弃。 */
    @Synchronized
    fun recordRead(pcm: ByteArray, bytesRead: Int, expectedBytes: Int, nowElapsedMs: Long): Window? {
        require(expectedBytes > 0)
        require(bytesRead <= pcm.size)
        if (startedAt == null) reset(nowElapsedMs)
        readCalls++
        when {
            bytesRead < 0 -> errorReads++
            bytesRead == 0 -> emptyReads++
            else -> {
                pcmReads++
                pcmBytes += bytesRead
                sampleValues += bytesRead / 2
                if (bytesRead < expectedBytes) shortReads++
                lastReadAt = nowElapsedMs
                windowLastReadAt = nowElapsedMs
                var index = 0
                while (index + 1 < bytesRead) {
                    val value = ((pcm[index + 1].toInt() and 0xFF) shl 8) or (pcm[index].toInt() and 0xFF)
                    val magnitude = if (value > 32767) 65536 - value else value
                    if (magnitude > peak) peak = magnitude
                    if (magnitude != 0) nonZeroValues++
                    index += 2
                }
            }
        }
        if (nowElapsedMs - start < windowMs) return null
        return window(nowElapsedMs).also {
            latestComplete = it
            clearWindow(nowElapsedMs)
        }
    }

    @Synchronized
    fun snapshot(nowElapsedMs: Long): Snapshot =
        Snapshot(runId, startedAt, nowElapsedMs, lastReadAt, startedAt?.let { window(nowElapsedMs) }, latestComplete)

    private fun window(nowElapsedMs: Long): Window =
        Window(
            runId, start, nowElapsedMs, windowLastReadAt, readCalls, pcmReads, pcmBytes, sampleValues,
            nonZeroValues, peak, shortReads, emptyReads, errorReads,
        )

    private fun clearWindow(nowElapsedMs: Long) {
        start = nowElapsedMs
        windowLastReadAt = null
        readCalls = 0L
        pcmReads = 0L
        pcmBytes = 0L
        sampleValues = 0L
        nonZeroValues = 0L
        peak = 0
        shortReads = 0L
        emptyReads = 0L
        errorReads = 0L
    }
}
