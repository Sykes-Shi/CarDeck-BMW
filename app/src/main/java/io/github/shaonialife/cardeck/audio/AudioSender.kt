package io.github.shaonialife.cardeck.audio

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import io.github.shaonialife.cardeck.airplay.AirPlayCrypto
import io.github.shaonialife.cardeck.airplay.TimingServer
import io.github.shaonialife.cardeck.util.Bg
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CarPlay 主音频发送端（手机 → 车机，RTP over UDP）。
 *
 * 线格式（对照 DiPlay `AudioStream.kt` 解密路径与 catplay `rtp_cipher_chacha.rs`）：
 * ```
 * [RTP 头 12B][ChaCha20-Poly1305 密封的 PCM 负载 + 16B tag][8B LE nonce]
 * ```
 *  - 加密：ChaCha20-Poly1305；AAD = 头 4..12 字节（timestamp+ssrc 的 BE 表示，
 *    catplay 的 "modern AAD"；DiPlay 解密即取同一范围）
 *  - nonce（12B）= 4 字节零 + 尾 8 字节（小端计数，从 0 逐包递增）→ [AirPlayCrypto.nonce64]
 *  - RTP 字段：version=2、PT=流类型（100=PCM 通路 / 102=AAC-LC 通路；
 *    对照 catplay `payload_type = stream_type`）、
 *    SSRC=0（catplay 同款）、timestamp = 累计样本帧数、seq 逐包递增
 *  - **首个音频包置 Marker 位**（规范：RECORD/FLUSH 之后的首包要打标记，接收端据此
 *    切分播放边界；catplay 恒 false，但我们的会话是「RECORD 之后才加入音频流」，
 *    首包标记能帮接收端重新对齐）
 *
 * 旁路：
 *  - **同步包**：[AudioSyncPacket] 每 1 秒发往该流的 `controlPort`，建立 RTP↔NTP 锚点。
 *    （缺了它接收端只能「按到达时刻猜」，严格的实现干脆不排播——2026-10-20 实测）
 *
 * 采集：经 Shizuku 管道接收系统混合播放声音；MediaProjection 控制本次同意与撤销。
 * 每包 [SAMPLES_PER_PACKET] 样本帧
 * （48000Hz 立体声 16bit = 2048B 负载，总包 ~2084B，不超接收端 4096B 收包缓冲）。
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /** 仅用于恢复旧版本遗留的媒体静音；正式采集保持用户当前媒体音量。 */
    private val context: android.content.Context? = null,
    /**
     * 采集采样率（Hz）：必须与 SETUP 协商出的格式一致（48k 主选，车机只支持
     * 44.1k 时由 AirPlayClient 协商下发）。默认 48k。
     */
    private val sampleRate: Int = 48_000,
    /** 负载编码：PCM（type=100 通路）或 AAC-LC（type=102 通路）。 */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** RTP 流类型，仅用于日志（PT 与 SETUP 的 type 保持一致）。 */
    private val streamType: Long = 100L,
    /** AAC 帧是否补 ADTS 头（见 [AudioTuning.ADTS]，排查车机解码链用）。 */
    private val adtsFrames: Boolean = false,
    /**
     * 该音频流的控制端口（车机在 SETUP 应答里下发）。>0 时每 [SYNC_INTERVAL_MILLIS]
     * 发一个 [AudioSyncPacket] 建立 RTP↔NTP 锚点；0 = 车机没给（旧车机），只发音频。
     */
    private val controlPort: Int = 0,
    /** Null while BMW is handling its other phone. Equal epochs identify uninterrupted media. */
    private val mediaEpoch: () -> Long? = { 0L },
) {
    private val captureMode = AudioCaptureMode

    companion object {
        const val CHANNELS = 2

        /**
         * 每包样本帧数 = AirPlay 标准 SPF 352（catplay `MAX_FRAMES_PER_PACKET_PCM`，
         * 真车验证）。48000/352 ≈ 136 包/秒（7.33ms/包）。
         * 2026-10-17 实测：此前用 512 帧（10.67ms/包），奥迪接收端无声音——
         * 与真 iPhone 的包长不一致时接收端可能按固定 SPF 缓冲而丢弃。
         */
        const val SAMPLES_PER_PACKET = 352

        /** 一包负载字节数（16bit 立体声）。 */
        const val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        /** 同步包发送间隔（规范：每秒一个）。 */
        const val SYNC_INTERVAL_MILLIS = 1_000L

        private const val RTP_HEADER_LEN = 12
        private const val TAG_SIZE = 16
        private const val NONCE_SIZE = 8

        /** 静音前媒体音量的持久化键（进程被杀也不丢恢复目标）。 */
        private const val KEY_SAVED_VOLUME = "audio_saved_media_volume"
        private val diagnosticSenderIds = AtomicLong()
        private val legacyVolumeLock = Any()

        /** 升级恢复只运行一次；失败保留备份，用户已调过音量时不覆盖。应在后台调用。 */
        fun recoverLegacyMediaVolume(context: android.content.Context, log: (String) -> Unit) = synchronized(legacyVolumeLock) {
            val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_SAVED_VOLUME)) return@synchronized
            runCatching {
                val manager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
                val current = manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val maximum = manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                when (val decision = LegacyMediaVolumeRecovery.decide(saved, current, maximum)) {
                    LegacyMediaVolumeRecovery.Decision.PreserveCurrent -> {
                        prefs.edit().remove(KEY_SAVED_VOLUME).commit()
                        runCatching { log("AUDIO: 已处理旧静音记录，保留当前手机媒体音量") }
                    }
                    LegacyMediaVolumeRecovery.Decision.Retry -> {
                        runCatching { log("AUDIO: 旧静音记录暂不能恢复，保留备份") }
                    }
                    is LegacyMediaVolumeRecovery.Decision.Restore -> {
                        // 系统采集不再写音量；这里只还原旧版曾主动设为零的遗留值。
                        manager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, decision.volume, 0)
                        if (manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == decision.volume) {
                            prefs.edit().remove(KEY_SAVED_VOLUME).commit()
                            runCatching { log("AUDIO: 已恢复旧版本保存的手机媒体音量（${decision.volume}）") }
                        } else {
                            runCatching { log("AUDIO: 旧手机媒体音量恢复未生效，保留备份") }
                        }
                    }
                }
            }.onFailure { runCatching { log("AUDIO: 旧手机媒体音量恢复失败，保留备份 reason=${it.javaClass.simpleName}") } }
        }

        /** 小端 s16 → 大端 s16（每两字节换序）。CarPlay 线格式要求大端样本。 */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)

    /** stop() 幂等闸门（多条收尾路径都会调 stop）。 */
    private val stopped = AtomicBoolean(false)
    private val startClaimed = AtomicBoolean(false)
    private val captureCancelled = AtomicBoolean(false)
    private val lifecycleLock = Any()
    @Volatile private var systemCapture: SystemAudioCapture? = null
    @Volatile private var projectionRegistration: ProjectionRegistration? = null
    private var socket: DatagramSocket? = null
    private var syncSocket: DatagramSocket? = null
    private var thread: Thread? = null
    private var syncThread: Thread? = null

    /** AAC-LC 编码器（仅 [AudioCodec.AAC_LC] 时创建）。 */
    private var aacEncoder: AacLcEncoder? = null

    /**
     * RTP 游标（原本是采集循环里的局部变量）。提到字段上是因为**同步包线程**要读
     * 「当前/下一包时间戳」，否则锚点里的时间戳永远对不上真实发出的包。
     */
    private val rtp = AudioRtpCursor(samplesPerPacket)
    private var selfChecked = false

    /** 每包/每帧对应的 PCM 字节数（PCM 通路=一包的负载；AAC 通路=一帧的输入）。 */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(CHANNELS) else PACKET_BYTES

    /** 每包推进的样本数（AAC 一帧 1024，PCM 一包 352）。 */
    private val samplesPerPacket: Long
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.FRAME_SAMPLES.toLong() else SAMPLES_PER_PACKET.toLong()

    /**
     * RTP SSRC：固定 0（catplay `prepare_header` 在真车上即用 0；DiPlay 收端不校验）。
     * 2026-10-17 实测：此前用随机值，奥迪接收端无声音——部分车机按 SSRC 关联 SETUP
     * 声明的流（非 0 视为未知流丢弃）。
     */
    private val ssrc = 0

    /** 采集+推流统计（日志限流用）。 */
    private var packetsSent = 0L
    private var bytesSent = 0L
    private var lastLogAt = 0L

    val isRunning: Boolean get() = running.get()

    private val diagnosticSenderId = diagnosticSenderIds.incrementAndGet()
    private val captureStats = PcmCaptureStats()
    private val sendStats = AudioSendStats()

    /** 导出只读当前发送器的统计快照；带代次和时间，不能把上一轮样本当成当前播放。 */
    internal fun captureDiagnosticLines(): List<String> {
        val sampledAt = android.os.SystemClock.elapsedRealtime()
        val stats = captureStats.snapshot(sampledAt)
        val prefix = "sender=$diagnosticSenderId run=${stats.runId} captureMode=${captureMode.diagnosticName}"
        return buildList {
            add(
                "$prefix sampledAtElapsedMs=$sampledAt active=${running.get()} " +
                    "recorderState=unknown_remote recordingState=unknown_remote " +
                    "startedAtElapsedMs=${stats.startedAtElapsedMs ?: "none"} " +
                    "lastReadAgeMs=${stats.lastReadAgeMs ?: "none"} sampleRate=$sampleRate channels=$CHANNELS " +
                    "format=PCM_S16_LE scope=system_output_mix readUnit=assembled_pcm_frame",
            )
            systemCapture?.diagnosticFields()?.let { add("$prefix $it") }
            stats.current?.let { add("$prefix window=current ${it.fields()}") }
            stats.latestComplete?.let { add("$prefix window=latest_complete ${it.fields()}") }
            val sending = sendStats.snapshot(android.os.SystemClock.elapsedRealtimeNanos())
            add("$prefix sendStats ${sending.fields()}")
            sending.current?.let { add("$prefix sendWindow=current ${it.fields()}") }
            sending.latestComplete?.let { add("$prefix sendWindow=latest_complete ${it.fields()}") }
        }
    }

    /** 统计复制后才写日志，不在统计锁中回调外部记录器。 */
    private fun logSendWindow(reason: String, force: Boolean = false) {
        // 诊断记录器失效也不能阻止音频发送或释放原有资源。
        runCatching {
            val now = android.os.SystemClock.elapsedRealtimeNanos()
            sendStats.takeWindow(now, force)?.let { window ->
                log(
                    "AUDIO: 发送时序 sender=$diagnosticSenderId captureMode=${captureMode.diagnosticName} " +
                        "reason=$reason ${window.fields()} ${sendStats.snapshot(now).fields()}",
                )
            }
        }
    }

    /**
     * 启动音频发送。需要 MediaProjection 作为本次采音同意与撤销的生命周期门控。
     * 返回是否成功进入采集循环。
     */
    fun start(
        projection: MediaProjection,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        if (!startClaimed.compareAndSet(false, true)) return running.get()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            log("AUDIO: 启动失败 captureMode=${captureMode.diagnosticName} reason=main_thread")
            return false
        }
        fun current(): Boolean =
            !captureCancelled.get() && !stopped.get() && runCatching { isCurrent() }.getOrDefault(false)
        fun checkCurrent() {
            if (!current()) throw IOException("capture_cancelled")
        }
        captureStats.reset(android.os.SystemClock.elapsedRealtime())
        sendStats.reset(android.os.SystemClock.elapsedRealtimeNanos())
        return try {
            checkCurrent()
            context?.let { recoverLegacyMediaVolume(it, log) }
            checkCurrent()
            val registration = ProjectionRegistration(projection) {
                captureCancelled.set(true)
                running.set(false)
                Bg.run("audio-projection-stopped") { stop() }
            }
            synchronized(lifecycleLock) {
                checkCurrent()
                projectionRegistration = registration
            }
            registration.register()
            checkCurrent()
            val client = DatagramSocket()
            try {
                client.connect(InetAddress.getByName(host), dataPort)
                synchronized(lifecycleLock) {
                    checkCurrent()
                    socket = client
                }
            } catch (error: Exception) {
                client.close()
                throw error
            }

            // AAC 通路先把编码器立起来：编不出来就没必要采集（也避免「有包无声」的假象）
            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    stop()
                    return false
                }
                synchronized(lifecycleLock) {
                    if (!current()) {
                        encoder.stop()
                        throw IOException("capture_cancelled")
                    }
                    aacEncoder = encoder
                }
            }

            checkCurrent()
            val capture = SystemAudioCapture.open(sampleRate, log, ::current)
            if (capture == null) {
                stop()
                return false
            }
            synchronized(lifecycleLock) {
                if (!current()) {
                    capture.close()
                    throw IOException("capture_cancelled")
                }
                systemCapture = capture
                running.set(true)
            }
            checkCurrent()
            log(
                "AUDIO: 已启动 captureMode=${captureMode.diagnosticName}（${sampleRate}Hz 立体声 → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，" +
                    if (codec == AudioCodec.AAC_LC) {
                        "每包 1 个 AAC 帧（${AacLcEncoder.FRAME_SAMPLES} 采样" +
                            (if (adtsFrames) "，ADTS 封装））" else "，裸帧））")
                    } else {
                        "每包 ${SAMPLES_PER_PACKET} 帧 PCM）"
                    },
            )

            // 同步包线程先起：锚点越早建立，接收端越早能排播
            startSyncLoop()

            Thread(
                {
                    try {
                        checkCurrent()
                    } catch (error: Exception) {
                        log("AUDIO: 启动采集失败 - ${error.message}")
                        stop()
                        return@Thread
                    }
                    runCaptureLoop()
                },
                "audio-capture",
            ).apply {
                isDaemon = true
                start()
                thread = this
            }
            if (current()) {
                true
            } else {
                stop()
                false
            }
        } catch (error: Exception) {
            log("AUDIO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /**
     * 停止音频。
     *
     * 收尾路径不止一条（采集线程 finally、会话结束回调、手动断开），会重复调用；
     * 必须幂等；每个实例只关闭自己持有的系统采集管道、投影回调和发送资源。
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        captureCancelled.set(true)
        running.set(false)
        val cleanup = {
            synchronized(lifecycleLock) {
                systemCapture?.let {
                    log("AUDIO: system_output stop ${it.diagnosticFields()}")
                    it.close()
                }
                systemCapture = null
                runCatching { aacEncoder?.stop() }
                aacEncoder = null
                runCatching { socket?.close() }
                socket = null
                runCatching { syncSocket?.close() }
                syncSocket = null
                syncThread = null
                thread = null
                projectionRegistration?.close()
                projectionRegistration = null
            }
            logSendWindow("stop", force = true)
            log("AUDIO: 已停止 captureMode=${captureMode.diagnosticName}（共发 $packetsSent 包 / ${bytesSent / 1024}KB）")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Bg.run("audio-stop", cleanup)
        } else {
            cleanup()
        }
    }

    /** 撤销只停止本发送器；旧投影的迟到回调不影响新会话。 */
    private class ProjectionRegistration(
        private val projection: MediaProjection,
        onRevoked: () -> Unit,
    ) {
        private val closed = AtomicBoolean(false)
        private val registered = AtomicBoolean(false)
        private val callback = object : MediaProjection.Callback() {
            override fun onStop() = onRevoked.invoke()
        }

        fun register() {
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            registered.set(true)
            if (closed.get()) unregister()
        }

        fun close() {
            closed.set(true)
            unregister()
        }

        private fun unregister() {
            if (registered.compareAndSet(true, false)) runCatching { projection.unregisterCallback(callback) }
        }
    }

    // ---- 音频同步包（RTP 时间戳 ↔ NTP，每秒一个） ----

    private fun startSyncLoop() {
        if (controlPort <= 0) {
            log("AUDIO: 车机未下发 controlPort——不发同步包（只能靠音频包自带时间戳）")
            return
        }
        val client =
            try {
                DatagramSocket().apply { connect(InetAddress.getByName(host), controlPort) }
            } catch (error: Exception) {
                log("AUDIO: 同步包套接字创建失败 - ${error.message}")
                return
            }
        synchronized(lifecycleLock) {
            if (stopped.get() || captureCancelled.get() || !running.get()) {
                client.close()
                return
            }
            syncSocket = client
        }
        log("AUDIO: 同步包已启用 → $host:$controlPort（每 ${SYNC_INTERVAL_MILLIS}ms 一个，建立 RTP↔NTP 锚点）")
        val worker =
            Thread(
                {
                    var syncSequence = 0
                    var lastSyncEpoch: Long? = null
                    val cadence = AudioSyncCadence()
                    try {
                        while (running.get() && !client.isClosed) {
                            val epoch = mediaEpoch()
                            if (epoch == null || (epoch != 0L && rtp.lastEpoch != epoch) || !cadence.allow(epoch)) {
                                Thread.sleep(250)
                                continue
                            }
                            val first = lastSyncEpoch != epoch
                            val next = rtp.timestamp
                            val current = (next - samplesPerPacket).coerceAtLeast(0L)
                            val ntp = TimingServer.ntpFromNanos(System.nanoTime())
                            val packet = AudioSyncPacket.build(syncSequence, current, next, ntp, first)
                            try {
                                client.send(DatagramPacket(packet, packet.size))
                            } catch (error: IOException) {
                                val refused = generateSequence<Throwable>(error) { it.cause }.take(5).any {
                                    it is java.net.PortUnreachableException ||
                                        (it is android.system.ErrnoException && it.errno == android.system.OsConstants.ECONNREFUSED) ||
                                        it.message?.contains("ECONNREFUSED") == true
                                }
                                if (!refused) throw error
                                cadence.refused(epoch)
                                log("AUDIO: 同步口拒绝，暂停此段锚点 epoch=$epoch")
                                continue
                            }
                            syncSequence = (syncSequence + 1) and 0xFFFF
                            if (first) {
                                lastSyncEpoch = epoch
                                log("AUDIO: 已发送媒体同步锚点 epoch=$epoch ts=$current→$next")
                            }
                            Thread.sleep(SYNC_INTERVAL_MILLIS)
                        }
                    } catch (error: Exception) {
                        if (running.get()) log("AUDIO: 同步包发送停止 - ${error.message}")
                    } finally {
                        client.close()
                        synchronized(lifecycleLock) {
                            if (syncSocket === client) syncSocket = null
                        }
                    }
                },
                "audio-sync",
            )
        worker.isDaemon = true
        worker.start()
        syncThread = worker
    }

    // ---- 采集循环 → 封包发送 ----

    private fun runCaptureLoop() {
        val system = systemCapture
        if (system == null) {
            stop()
            return
        }
        val frameBytes = framePcmBytes
        val pcm = ByteArray(frameBytes)
        var encoderEpoch: Long? = null
        try {
            while (running.get()) {
                val epoch = mediaEpoch()
                val readStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                val read =
                    try {
                        system.readFrame(pcm)
                    } catch (error: Exception) {
                        sendStats.recordRead(pcm, -1, readStartedNs, android.os.SystemClock.elapsedRealtimeNanos())
                        logSendWindow("read_error", force = true)
                        log("AUDIO: 采集读取异常 - ${error.message}")
                        break
                    }
                val readFinishedNs = android.os.SystemClock.elapsedRealtimeNanos()
                sendStats.recordRead(pcm, read, readStartedNs, readFinishedNs)?.let { observation ->
                    runCatching {
                        log(
                            "AUDIO: PCM混音首次非零 sender=$diagnosticSenderId captureMode=${captureMode.diagnosticName} " +
                                "${observation.fields()} observation=read_completed scope=mixed_capture",
                        )
                    }
                }
                logSendWindow("periodic")
                // 以实际读缓冲和单调时间统计，避免 AAC 一次产出多帧跳过 RTP 序号抽样点。
                captureStats.recordRead(pcm, read, frameBytes, android.os.SystemClock.elapsedRealtime())?.let { window ->
                    log(
                        "AUDIO: PCM混音窗口 sender=$diagnosticSenderId run=${window.runId} " +
                            "captureMode=${captureMode.diagnosticName} " +
                            "readUnit=assembled_pcm_frame ${window.fields()}",
                    )
                }
                if (read < 0) break
                if (read < frameBytes) continue // 残包（缓冲尾部）：丢弃，等下一个整包
                // Keep the live capture drained during the call. A read spanning either edge
                // is discarded too, including an entire short call occurring inside one read.
                if (epoch == null || mediaEpoch() != epoch) continue

                // PCM 通路：采一段 = 发一包；AAC 通路：喂一帧 PCM，编码器可能吐出 0~N 帧
                val payloads: List<ByteArray> =
                    if (codec == AudioCodec.AAC_LC) {
                        val encoder = aacEncoder ?: break
                        if (encoderEpoch != null && encoderEpoch != epoch) {
                            // An AAC encoder can retain delayed frames. Start a new coding
                            // segment before feeding fresh PCM, without resetting the wire key.
                            synchronized(lifecycleLock) {
                                if (running.get() && !stopped.get()) {
                                    encoder.stop()
                                    if (!encoder.start()) throw IOException("aac_call_resume_failed")
                                }
                            }
                            if (!running.get() || stopped.get()) break
                        }
                        encoderEpoch = epoch
                        val encodeStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
                        try {
                            encoder.encode(pcm, frameBytes).also { frames ->
                                sendStats.recordEncode(encodeStartedNs, android.os.SystemClock.elapsedRealtimeNanos(), frames.size)
                            }
                        } catch (error: Throwable) {
                            sendStats.recordEncode(encodeStartedNs, android.os.SystemClock.elapsedRealtimeNanos(), 0, failed = true)
                            logSendWindow("encode_error", force = true)
                            throw error
                        }
                    } else {
                        listOf(pcm)
                    }
                if (payloads.isEmpty()) continue

                // The local decoder only checks the AAC bitstream; it cannot observe the car's output.
                if (codec == AudioCodec.AAC_LC && !selfChecked) {
                    selfChecked = true
                    verifyEncodedFrame(payloads.first())
                }

                var sendFailed = false
                for (payload in payloads) {
                    if (!running.get() || mediaEpoch() != epoch) break
                    if (!sendPayload(payload, epoch)) {
                        sendFailed = true
                        break
                    }
                }
                if (sendFailed) break
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastLogAt >= 30_000L) {
                    lastLogAt = now
                    log(
                        "AUDIO: 推流中（累计 $packetsSent 包，${bytesSent / 1024}KB，" +
                            "seq=${rtp.sequence} ts=${rtp.timestamp}）",
                    )
                }
            }
        } catch (error: Throwable) {
            // 后台线程未捕获异常会杀死整个进程——音频异常绝不允许冒泡
            logSendWindow("capture_error", force = true)
            log("AUDIO: 采集线程异常退出 - ${error.message}")
        } finally {
            // 外部 stop 可能先输出快照；在途 read/encode/send 的尾部在这里再留一段。
            try {
                logSendWindow("capture_end", force = true)
            } finally {
                stop()
            }
        }
    }

    /** 封包 + 发送一包（同步更新 seq/ts/nonce 游标）。返回 false 表示链路已断。 */
    private fun sendPayload(payload: ByteArray, epoch: Long): Boolean {
        val sendStartedNs = android.os.SystemClock.elapsedRealtimeNanos()
        var wireBytes = 0
        var sent = false
        return try {
            val first = rtp.lastEpoch != epoch
            val wire = sealPacket(payload, rtp.sequence, rtp.timestamp, rtp.nonce, first)
            wireBytes = wire.size
            rtp.advance(epoch)
            val client = socket ?: return false
            client.send(DatagramPacket(wire, wire.size))
            if (first && epoch != 0L) log("AUDIO: 已发出通话后首个媒体包 sender=$diagnosticSenderId epoch=$epoch")
            packetsSent++
            bytesSent += wire.size
            sent = true
            true
        } catch (error: IOException) {
            log("AUDIO: UDP 发送失败 - ${error.message}")
            false
        } finally {
            sendStats.recordSend(
                sendStartedNs, android.os.SystemClock.elapsedRealtimeNanos(), payload.size, wireBytes,
                samplesPerPacket, sent,
            )
            if (!sent) logSendWindow("send_error", force = true)
        }
    }

    /** 用平台解码器把首帧解回 PCM，记录峰值（后台线程，不阻塞推流）。 */
    private fun verifyEncodedFrame(frame: ByteArray) {
        val payload = frame.copyOf()
        Thread(
            {
                try {
                    val result = AacLcEncoder.decodePeak(payload, sampleRate, CHANNELS, adtsFrames)
                    if (result == null) {
                        log("AUDIO: AAC 自检 sender=$diagnosticSenderId decoded=false bytes=${payload.size}")
                        return@Thread
                    }
                    val (peak, samples) = result
                    log("AUDIO: AAC 自检 sender=$diagnosticSenderId decoded=true samples=$samples peak=$peak")
                } catch (error: Exception) {
                    log("AUDIO: 自检解码异常 - ${error.message}")
                }
            },
            "audio-selfcheck",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** 组一个 RTP 包：明文 PCM → ChaCha20 密封 → [头][密文+tag][nonce8]。（internal：测试直测） */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        // AAD = header 的 timestamp+ssrc 段（字节 4..12）——catplay "modern AAD" / DiPlay 解密同款
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        // 关键：CarPlay 线格式的 LPCM 是**大端** s16 样本——接收端对收到的负载做
        // byteSwapS16 转回小端再喂 AudioTrack（DiPlay AndroidMediaSink.kt:1687）。
        // Android 采集是原生小端，因此这里必须换序；漏掉会得到「沙沙声」（2026-10-10 实测）。
        //
        // AAC 通路不做换序：AAC 比特流是字节流，不是 16bit 样本序列，换序会直接把
        // 比特流搅烂（车机解码失败 → 有包无声）。
        val wirePayload = if (codec == AudioCodec.AAC_LC) pcm else bigEndianS16(pcm)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePayload, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // 尾部 8 字节小端 nonce（与 AirPlayCrypto.nonce64 的低 8 字节一致）
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }

    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // version=2
        // PT 跟随流类型（catplay `payload_type = stream_type`）：100=PCM 通路、102=AAC 通路
        header[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7F)).toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
