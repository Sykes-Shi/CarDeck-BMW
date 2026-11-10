package io.github.shaonialife.cardeck.audio

import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.ICarInput
import io.github.shaonialife.cardeck.util.Bg
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 车机声音的 shell 输出采音：每次只持有自己的远端凭据与管道，不改手机媒体音量。 */
internal class SystemAudioCapture private constructor(
    private val service: ICarInput,
    private val sampleRate: Int,
    private val log: (String) -> Unit,
    private val isCurrent: () -> Boolean,
) {
    companion object {
        private const val OPEN_TIMEOUT_MS = 5_000L
        private const val POLL_SLICE_MS = 100
        private val gate = CaptureOwnerGate()

        fun open(
            sampleRate: Int,
            log: (String) -> Unit,
            isCurrent: () -> Boolean = { true },
        ): SystemAudioCapture? {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                log("AUDIO: system_output failure=main_thread")
                return null
            }
            val service = CarInputClient.service
            if (service == null) {
                log("AUDIO: system_output failure=shizuku_not_connected")
                return null
            }
            val capture = SystemAudioCapture(service, sampleRate, log, isCurrent)
            if (!gate.acquire(capture)) {
                log("AUDIO: system_output failure=previous_capture_busy")
                return null
            }
            val deadline = SystemClock.elapsedRealtime() + OPEN_TIMEOUT_MS
            var handedOff = false
            try {
                Bg.run("system-audio-open") { capture.openRemote(deadline) }
            } catch (_: Exception) {
                capture.openWorkerFinished()
                capture.close()
                log("AUDIO: system_output failure=worker_unavailable")
                return null
            }
            try {
                while (capture.isCurrentAndOpen()) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) {
                        log("AUDIO: system_output failure=open_timeout")
                        break
                    }
                    if (capture.openFinished.await(minOf(remaining, 50L), TimeUnit.MILLISECONDS)) {
                        if (capture.ready && capture.isCurrentAndOpen()) {
                            log("AUDIO: system_output ready rate=$sampleRate channels=2 format=PCM_S16_LE")
                            handedOff = true
                            return capture
                        }
                        break
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                log("AUDIO: system_output failure=interrupted")
            } finally {
                if (!handedOff) capture.close()
            }
            capture.close()
            return null
        }
    }

    private val owner = Binder()
    private val remoteBinder = service.asBinder()
    private val closed = AtomicBoolean(false)
    private val openFinished = CountDownLatch(1)
    private val stateLock = Any()
    private var descriptor: ParcelFileDescriptor? = null
    private var workerFinished = false
    private var cleanupJobs = 0
    private var gateReleased = false
    private var deathLinked = false
    @Volatile private var ready = false
    private var frameReader: PcmFrameReader? = null
    private val pipeReadCalls = AtomicLong()
    private val pipeBytes = AtomicLong()
    private val deathRecipient = IBinder.DeathRecipient {
        log("AUDIO: system_output failure=service_died")
        close()
    }

    val isOpen: Boolean get() = ready && isCurrentAndOpen()

    fun diagnosticFields(): String =
        "pipeOpen=$isOpen pipeReadCalls=${pipeReadCalls.get()} pipeBytes=${pipeBytes.get()} pipeScope=pcm_only"

    private fun isCurrentAndOpen(): Boolean =
        !closed.get() && runCatching { isCurrent() }.getOrDefault(false)

    private fun openRemote(deadline: Long) {
        var stage = "link_service"
        try {
            if (!isCurrentAndOpen()) return
            remoteBinder.linkToDeath(deathRecipient, 0)
            synchronized(stateLock) { deathLinked = true }
            if (!isCurrentAndOpen()) return
            // Binder 阻塞不能靠线程取消；迟到调用真正返回前继续占用单飞闸门。
            stage = "open_remote"
            val pipe = service.openSystemAudio(sampleRate, owner)
            if (pipe == null) {
                log("AUDIO: system_output failure=open_rejected")
                return
            }
            val accepted = synchronized(stateLock) {
                if (closed.get()) {
                    false
                } else {
                    descriptor = pipe
                    true
                }
            }
            if (!accepted) {
                runCatching { pipe.close() }
                return
            }
            stage = "prepare_pipe"
            synchronized(stateLock) {
                if (closed.get()) return
                val flags = Os.fcntlInt(pipe.fileDescriptor, OsConstants.F_GETFL, 0)
                Os.fcntlInt(pipe.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            }
            stage = "read_header"
            val header = ByteArray(SystemAudioWire.HEADER_BYTES)
            var offset = 0
            while (offset < header.size && isCurrentAndOpen()) {
                val read = readAvailable(header, offset, header.size - offset, deadline)
                if (read < 0) throw IOException("header_eof")
                offset += read
            }
            if (offset != header.size || !isCurrentAndOpen()) return
            stage = "validate_header"
            if (!SystemAudioWire.validateHeader(header, sampleRate)) throw IOException("header_mismatch")
            ready = true
        } catch (error: Exception) {
            // 只记录固定阶段及异常类，不把远端异常原文写进用户日志。
            val errorType = error.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z0-9]{1,80}")) } ?: "Exception"
            log("AUDIO: system_output failure=$stage:$errorType")
            close()
        } finally {
            if (!ready || !isCurrentAndOpen()) close()
            openWorkerFinished()
        }
    }

    private fun openWorkerFinished() {
        val cleanupReserved = synchronized(stateLock) {
            workerFinished = true
            closed.get().also { if (it) cleanupJobs++ }
        }
        // 首次关闭可能早于远端建立录音；迟到 open 返回后须再次关闭原凭据。
        if (cleanupReserved) scheduleCleanup()
        openFinished.countDown()
        releaseGateIfFinished()
    }

    /** 仅采集线程读取；保留分片，凑齐编码/RTP 所需的完整 PCM 帧后才交给发送器。 */
    fun readFrame(target: ByteArray): Int {
        if (!isOpen) return -1
        val reader = frameReader ?: PcmFrameReader(target.size).also { frameReader = it }
        return try {
            reader.readFrame(
                target,
                { !isCurrentAndOpen() },
                { scratch, length -> readAvailable(scratch, 0, length) },
                { _, count ->
                    pipeReadCalls.incrementAndGet()
                    pipeBytes.addAndGet(count.toLong())
                },
            ).also { if (it < 0) close() }
        } catch (_: Exception) {
            if (!closed.get()) log("AUDIO: system_output failure=pipe_read_failed")
            close()
            -1
        }
    }

    private fun readAvailable(
        target: ByteArray,
        offset: Int,
        length: Int,
        deadline: Long = Long.MAX_VALUE,
    ): Int {
        while (isCurrentAndOpen()) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) throw IOException("pipe_deadline")
            val poll = synchronized(stateLock) {
                descriptor?.let {
                    StructPollfd().apply {
                        fd = it.fileDescriptor
                        events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort()
                    }
                }
            } ?: return -1
            try {
                val result = Os.poll(arrayOf(poll), minOf(remaining, POLL_SLICE_MS.toLong()).toInt())
                if (result == 0) continue
                val read = synchronized(stateLock) {
                    // poll 等待时可能已关闭管道；锁内重查，避免读取被复用的文件描述符。
                    val pipe = descriptor
                    if (closed.get() || pipe == null) return -1
                    Os.read(pipe.fileDescriptor, target, offset, length)
                }
                if (read == 0) {
                    val remoteError = synchronized(stateLock) {
                        try {
                            descriptor?.checkError()
                            "pipe_eof"
                        } catch (error: IOException) {
                            SystemAudioFailureReason.fromPipeError(error.message)
                        }
                    }
                    log("AUDIO: system_output failure=$remoteError")
                    return -1
                }
                return read
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
            }
        }
        return -1
    }

    /** UI 只标记关闭；远端停止事务及文件描述符释放交给后台，避免拖住界面。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        queueCleanup()
    }

    private fun queueCleanup() {
        synchronized(stateLock) { cleanupJobs++ }
        scheduleCleanup()
    }

    private fun scheduleCleanup() {
        Bg.run("system-audio-close") {
            try {
                val pipe = synchronized(stateLock) { descriptor.also { descriptor = null } }
                runCatching { pipe?.close() }
                runCatching { service.closeSystemAudio(owner) }
                val unlink = synchronized(stateLock) {
                    if (workerFinished && deathLinked) {
                        deathLinked = false
                        true
                    } else {
                        false
                    }
                }
                if (unlink) runCatching { remoteBinder.unlinkToDeath(deathRecipient, 0) }
            } finally {
                synchronized(stateLock) { cleanupJobs-- }
                releaseGateIfFinished()
            }
        }
    }

    private fun releaseGateIfFinished() {
        val release = synchronized(stateLock) {
            if (closed.get() && workerFinished && cleanupJobs == 0 && !gateReleased) {
                gateReleased = true
                true
            } else {
                false
            }
        }
        if (release) gate.release(this)
    }
}
