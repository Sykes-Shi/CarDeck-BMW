package io.github.shaonialife.cardeck.shizuku

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import io.github.shaonialife.cardeck.audio.ShellAudioIdentity
import io.github.shaonialife.cardeck.audio.SystemAudioWire
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅 shell 的 REMOTE_SUBMIX 实验采集。创建、启动、读取、释放均由单个独立 worker 执行。
 * Binder 的关闭/死亡通知只设置取消标记；构造或启动迟到返回后仍由原 worker 自清。
 * 使用公开 AudioRecord.Builder.setContext（API31）；不改属性/音量、不回退麦克风。
 */
internal class ShellAudioCaptureController(private val context: Context) {
    private val slot = ShellCaptureSlot<IBinder>()

    fun open(sampleRate: Int, owner: IBinder?): ParcelFileDescriptor? {
        if (owner == null || !SystemAudioWire.supportedRate(sampleRate)) {
            log("invalid_request")
            return null
        }
        val lease = slot.claim(owner) ?: run {
            log("busy")
            return null
        }
        var reader: ParcelFileDescriptor? = null
        var writer: ParcelFileDescriptor? = null
        val death = IBinder.DeathRecipient { lease.cancel() }
        var linked = false
        try {
            val pipe = ParcelFileDescriptor.createReliablePipe()
            reader = pipe[0]
            val output = pipe[1]
            writer = output
            owner.linkToDeath(death, 0)
            linked = true
            if (!owner.isBinderAlive || lease.cancelled) throw CaptureFailure("owner_gone")
            // 捕获线程仅持有写端。返回读端由 AIDL 的 WRITE_RETURN_VALUE 移交并关闭本地副本。
            Thread({ capture(lease, output, sampleRate, death) }, "carinput-system-audio").apply {
                isDaemon = true
                start()
            }
            return reader
        } catch (error: Exception) {
            lease.cancel()
            if (linked) runCatching { owner.unlinkToDeath(death, 0) }
            runCatching { reader?.close() }
            runCatching { writer?.closeWithError("open_failed") }
            slot.release(lease)
            log("open_failed", error)
            return null
        }
    }

    /** oneway 入口不得等待 native build/start/release 或管道写入。 */
    fun close(owner: IBinder?) {
        if (owner != null) slot.cancel(owner)
    }

    /** 仅查询本地占位，不调用 AudioRecord 或等待 native 收尾。 */
    fun isIdle(): Boolean = slot.isIdle()

    @SuppressLint("MissingPermission") // 只在已校验 uid 2000 的 Shizuku UserService 中执行。
    private fun capture(
        lease: ShellCaptureSlot.Lease<IBinder>,
        writer: ParcelFileDescriptor,
        sampleRate: Int,
        death: IBinder.DeathRecipient,
    ) {
        val identity = Binder.clearCallingIdentity()
        var recorder: AudioRecord? = null
        var failure: String? = null
        var stage = "shell_context"
        try {
            checkActive(lease)
            val processUid = android.os.Process.myUid()
            if (processUid != ShellAudioIdentity.UID) throw CaptureFailure("unsupported_uid")
            val packageContext = context.createPackageContext(ShellAudioIdentity.PACKAGE, 0)
            ShellAudioIdentity.validationFailure(
                processUid,
                packageContext.packageName,
                packageContext.applicationInfo.uid,
            )?.let { throw CaptureFailure(it) }
            // AOSP ContextImpl.createPackageContextAsUser 仍继承原容器的 opPackageName，
            // AttributionSource 也由该名称生成；v17 因此在构造录音前两次拒绝了合法 shell。
            // 仅给本次 Builder 包装已验证的真实身份，不替换触控服务或应用的 Context。
            // https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/app/ContextImpl.java
            val shellContext = object : ContextWrapper(packageContext) {
                private val audioAttribution =
                    AttributionSource.Builder(processUid).setPackageName(ShellAudioIdentity.PACKAGE).build()

                override fun getPackageName(): String = ShellAudioIdentity.PACKAGE

                override fun getOpPackageName(): String = ShellAudioIdentity.PACKAGE

                override fun getAttributionSource(): AttributionSource = audioAttribution

                // 若音频实现取 applicationContext，仍应保留刚核验的采音归属。
                override fun getApplicationContext(): Context = this
            }
            val attribution = shellContext.attributionSource
            if (shellContext.packageName != ShellAudioIdentity.PACKAGE ||
                shellContext.opPackageName != ShellAudioIdentity.PACKAGE ||
                attribution.uid != processUid ||
                attribution.packageName != ShellAudioIdentity.PACKAGE
            ) throw CaptureFailure("invalid_shell_attribution")
            checkActive(lease)
            stage = "query_buffer"
            val minBytes = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0 || minBytes > MAX_RECORD_BUFFER_BYTES / 4) {
                throw CaptureFailure("unsupported_buffer")
            }
            stage = "build_record"
            // SDK 的普通应用音源枚举未包含此特权源；这里只在上述 uid 2000 校验后使用。
            // https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#REMOTE_SUBMIX
            @SuppressLint("WrongConstant")
            val record = AudioRecord.Builder()
                .setContext(shellContext)
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBytes * 4, PCM_BYTES * 4))
                .build()
            recorder = record
            checkActive(lease)
            stage = "verify_record"
            if (record.state != AudioRecord.STATE_INITIALIZED || record.sampleRate != sampleRate ||
                record.channelCount != SystemAudioWire.CHANNELS || record.audioFormat != AudioFormat.ENCODING_PCM_16BIT
            ) throw CaptureFailure("unsupported_record_format")
            stage = "prepare_pipe"
            val pipe = BoundedPipeWriter(writer.fileDescriptor, lease)
            stage = "start_record"
            record.startRecording()
            checkActive(lease)
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw CaptureFailure("record_not_started")
            }
            stage = "write_header"
            pipe.write(SystemAudioWire.header(sampleRate), SystemAudioWire.HEADER_BYTES)
            log("started")
            val samples = ShortArray(PCM_BYTES / 2)
            val bytes = ByteArray(PCM_BYTES)
            var lastDataNanos = System.nanoTime()
            while (!lease.cancelled) {
                stage = "check_reader"
                pipe.checkReader()
                stage = "read_pcm"
                val count = record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                checkActive(lease)
                if (count < 0) throw CaptureFailure("record_read_failed")
                if (count == 0) {
                    if (System.nanoTime() - lastDataNanos >= NO_DATA_NANOS) throw CaptureFailure("record_stalled")
                    Thread.sleep(10L)
                    continue
                }
                if (count % SystemAudioWire.CHANNELS != 0) throw CaptureFailure("partial_pcm_frame")
                lastDataNanos = System.nanoTime()
                // 显式转小端，协议不依赖设备的 native byte order；音频只经过固定内存缓冲。
                for (index in 0 until count) {
                    val value = samples[index].toInt()
                    bytes[index * 2] = value.toByte()
                    bytes[index * 2 + 1] = (value shr 8).toByte()
                }
                stage = "write_pcm"
                pipe.write(bytes, count * 2)
            }
        } catch (_: CaptureCancelled) {
            failure = "cancelled"
        } catch (error: CaptureFailure) {
            failure = error.reason
            log(error.reason)
        } catch (error: Exception) {
            // 错误管道只传固定阶段和有界类名，便于应用导出；不含异常 message/系统原文。
            failure = "$stage:${errorClass(error)}"
            log("failed_$stage", error)
        } finally {
            lease.cancel()
            // release 自带停止；只有实际释放返回后才允许下一次 open，不叠加卡住的 worker。
            runCatching { recorder?.release() }
            val terminalFailure = failure
            runCatching {
                if (terminalFailure == null) writer.close() else writer.closeWithError(terminalFailure)
            }
            runCatching { lease.owner.unlinkToDeath(death, 0) }
            Binder.restoreCallingIdentity(identity)
            slot.release(lease)
            log("stopped")
        }
    }

    private class BoundedPipeWriter(
        private val descriptor: FileDescriptor,
        private val lease: ShellCaptureSlot.Lease<IBinder>,
    ) {
        private val descriptorPoll = StructPollfd().apply {
            fd = descriptor
            events = OsConstants.POLLOUT.toShort()
        }
        private val polls = arrayOf(descriptorPoll)

        init {
            val flags = Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }

        fun checkReader() = poll(0)

        fun write(bytes: ByteArray, size: Int) {
            var offset = 0
            val deadline = System.nanoTime() + WRITE_DEADLINE_NANOS
            while (offset < size) {
                checkActive(lease)
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw CaptureFailure("consumer_stalled")
                val wait = minOf(250L, maxOf(1L, remaining / 1_000_000L)).toInt()
                if (!poll(wait)) continue
                try {
                    offset += Os.write(descriptor, bytes, offset, size - offset)
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                }
            }
        }

        private fun poll(timeoutMs: Int): Boolean {
            checkActive(lease)
            descriptorPoll.revents = 0
            val ready = try {
                Os.poll(polls, timeoutMs)
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINTR) return false
                throw error
            }
            checkActive(lease)
            val result = descriptorPoll.revents.toInt()
            if (result and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                throw CaptureFailure("consumer_closed")
            }
            return ready > 0 && result and OsConstants.POLLOUT != 0
        }
    }

    private class CaptureFailure(val reason: String) : RuntimeException()
    private class CaptureCancelled : RuntimeException()

    companion object {
        private const val TAG = "CarInputAudio"
        private const val PCM_BYTES = 4096
        private const val MAX_RECORD_BUFFER_BYTES = 1_048_576
        private const val WRITE_DEADLINE_NANOS = 2_000_000_000L
        private const val NO_DATA_NANOS = 5_000_000_000L

        private fun checkActive(lease: ShellCaptureSlot.Lease<IBinder>) {
            if (lease.cancelled) throw CaptureCancelled()
        }

        private fun log(state: String, error: Exception? = null) {
            val suffix = if (error == null) "" else " errorClass=${errorClass(error)}"
            Log.i(TAG, "AUDIO_SYSTEM_SERVER state=$state$suffix")
        }

        private fun errorClass(error: Exception): String = error.javaClass.simpleName
            .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
            .take(80).ifEmpty { "Exception" }
    }
}

/** 纯状态闸门：取消不释放占位；原 worker 实际收尾后才能开下一轮。 */
internal class ShellCaptureSlot<T> {
    class Lease<T>(val owner: T) {
        private val cancellation = AtomicBoolean(false)
        val cancelled: Boolean get() = cancellation.get()
        fun cancel() { cancellation.set(true) }
    }

    private var current: Lease<T>? = null

    @Synchronized
    fun isIdle(): Boolean = current == null

    @Synchronized
    fun claim(owner: T): Lease<T>? {
        if (current != null) return null
        return Lease(owner).also { current = it }
    }

    @Synchronized
    fun cancel(owner: T) {
        current?.takeIf { it.owner == owner }?.cancel()
    }

    @Synchronized
    fun release(lease: Lease<T>) {
        lease.cancel()
        if (current === lease) current = null
    }
}
