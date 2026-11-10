package io.github.shaonialife.cardeck.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Looper
import android.os.SystemClock
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.util.DiagnosticTextReport
import io.github.shaonialife.cardeck.util.StableDiagnosticSnapshot

/** 用户导出日志时才执行的声音快照；不改播放、音量、网络或采集策略。 */
object AudioDiagnostics {
    private val targetPackages = listOf("com.spotify.music", "com.netease.cloudmusic")
    private const val SAMPLE_INTERVAL_MS = 2_000L

    /** 包资源读取和两轮采样必须在后台完成；失败只影响当前字段，不阻断日志导出。 */
    fun collect(context: Context): String {
        val report = DiagnosticTextReport()
        try {
            collectInto(context, report)
        } catch (error: Exception) {
            report.line("AUDIO_DIAG unavailable=${error.javaClass.simpleName}")
        }
        return report.toString()
    }

    @Suppress("DEPRECATION")
    private fun collectInto(
        context: Context,
        report: DiagnosticTextReport,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            report.line("AUDIO_DIAG unavailable=main_thread")
            return
        }
        report.line("AUDIO_DIAG trigger=user_log_export samples=2 minimumIntervalMs=$SAMPLE_INTERVAL_MS")
        report.line("说明：声明采集策略不是实际生效策略；LOCAL 不是实际输出设备；原始进度不变不表示停播。")
        report.line("运行时策略来自系统报告的播放器缓存；PCM 统计来自既有捕获混音，不额外录音或保存音频，不改采集许可、播放或音量。")
        val appContext = context.applicationContext
        val targetUids = mutableSetOf<Int>()
        targetPackages.forEach { pkg ->
            val info = query { appContext.packageManager.getPackageInfo(pkg, 0) }
            val application = query { appContext.packageManager.getApplicationInfo(pkg, 0) }
            val declaration = application?.let { query { PackageCaptureDeclaration.read(it) } } ?: "unknown"
            application?.uid?.takeIf { it >= 0 }?.let(targetUids::add)
            report.line(
                "AUDIO_DIAG package=$pkg version=${safeText(info?.versionName)} " +
                    "versionCode=${query { info?.longVersionCode } ?: "unknown"} " +
                    "targetSdk=${application?.targetSdkVersion ?: "unknown"} uid=${application?.uid ?: "unknown"} " +
                    "packageDeclaredCaptureAllowed=$declaration",
            )
        }
        val manager = query { appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager }
        val audio = query { appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
        // 仅在这次导出内用 token 区分会话；报告只写临时编号，绝不输出 token 内容。
        val tokens = mutableMapOf<MediaSession.Token, Int>()
        snapshot(1, appContext, manager, audio, tokens, report)
        runtimeSnapshot(1, targetUids, report)
        try {
            Thread.sleep(SAMPLE_INTERVAL_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            report.line("AUDIO_DIAG sample=2 unavailable=interrupted")
            return
        }
        snapshot(2, appContext, manager, audio, tokens, report)
        runtimeSnapshot(2, targetUids, report)
        // 原生端点只读一次；各数据源分别标时，不能把不同时间的状态当作原子快照。
        val nativeLines = runCatching { NativeAudioRouteDiagnostics.collect(targetUids) }
            .getOrElse { listOf("AUDIO_NATIVE unavailable=probe_failed") }
        nativeLines.forEach(report::line)
    }

    private fun runtimeSnapshot(sample: Int, targetUids: Set<Int>, report: DiagnosticTextReport) {
        report.line("AUDIO_RUNTIME sample=$sample sampledAtElapsedMs=${SystemClock.elapsedRealtime()}")
        val lines = runCatching { RuntimeAudioDiagnostics.collect(targetUids) }
            .getOrElse { listOf("AUDIO_RUNTIME unavailable=probe_failed") }
        lines.forEach { report.line(it.replaceFirst("AUDIO_RUNTIME ", "AUDIO_RUNTIME sample=$sample ")) }
    }

    private fun captureSnapshot(sample: Int, report: DiagnosticTextReport) {
        val sender = SessionController.audioSender
        if (sender == null) {
            report.line("AUDIO_CAPTURE sample=$sample sampledAtElapsedMs=${SystemClock.elapsedRealtime()} unavailable=no_current_sender")
            return
        }
        val lines = query { sender.captureDiagnosticLines() }
        // 读快照时若重连，舍弃整组旧统计，不跨发送器拼接。
        if (SessionController.audioSender !== sender) {
            report.line("AUDIO_CAPTURE sample=$sample unavailable=sender_changed")
        } else if (lines == null) {
            report.line("AUDIO_CAPTURE sample=$sample unavailable=snapshot_failed")
        } else {
            lines.forEach { report.line("AUDIO_CAPTURE sample=$sample $it") }
        }
    }

    private fun snapshot(
        sample: Int,
        context: Context,
        manager: MediaSessionManager?,
        audio: AudioManager?,
        tokens: MutableMap<MediaSession.Token, Int>,
        report: DiagnosticTextReport,
    ) {
        val sampledAt = SystemClock.elapsedRealtime()
        report.line(
            "AUDIO_DIAG sample=$sample sampledAtElapsedMs=$sampledAt " +
                "musicStreamVolume=${query { audio?.getStreamVolume(AudioManager.STREAM_MUSIC) } ?: "unknown"} " +
                "musicStreamMuted=${query { audio?.isStreamMute(AudioManager.STREAM_MUSIC) } ?: "unknown"}",
        )
        captureSnapshot(sample, report)
        StableDiagnosticSnapshot.read({ SessionController.currentClient }) { it.peerDiagnosticLines() }
            .forEach { report.line("AUDIO_PEER sample=$sample $it") }
        val controllers = query { manager?.getActiveSessions(MediaListenerService.componentName(context)) }
        if (controllers == null) {
            report.line("AUDIO_DIAG sample=$sample sessions=unavailable")
            return
        }
        // 只读取固定目标包的播放状态；不用 metadata，也不枚举或输出其他应用的内容。
        val selected = controllers.take(64).mapNotNull { controller ->
            val pkg = query { controller.packageName }
            if (pkg in targetPackages) pkg!! to controller else null
        }
        targetPackages.forEach { pkg ->
            val matches = selected.filter { it.first == pkg }.take(2)
            if (matches.isEmpty()) report.line("AUDIO_DIAG sample=$sample package=$pkg sessions=none_observed")
            matches.forEach { (_, controller) ->
                val token = query { controller.sessionToken }
                val sessionId = token?.let { tokens.getOrPut(it) { tokens.size + 1 } }?.toString() ?: "unknown"
                val prefix = "AUDIO_DIAG sample=$sample package=$pkg session=$sessionId"
                val state = query { controller.playbackState }
                val info = query { controller.playbackInfo }
                val attributes = query { info?.audioAttributes }
                report.line(
                    "$prefix state=${query { state?.state } ?: "unknown"} " +
                        "statePositionMs=${query { state?.position } ?: "unknown"} " +
                        "speed=${query { state?.playbackSpeed } ?: "unknown"} " +
                        "stateUpdatedAtMs=${query { state?.lastPositionUpdateTime } ?: "unknown"}",
                )
                report.line(
                    "$prefix playbackType=${playbackType(query { info?.playbackType })} " +
                        "usage=${query { attributes?.usage } ?: "unknown"} " +
                        "sessionDeclaredCapturePolicy=${capturePolicy(query { attributes?.allowedCapturePolicy })} " +
                        "sessionVolume=${query { info?.currentVolume } ?: "unknown"}/" +
                        "${query { info?.maxVolume } ?: "unknown"}",
                )
            }
        }
        if (controllers.size > 64) report.line("AUDIO_DIAG sample=$sample sessionScan=limited_to_64")
    }

    private fun playbackType(value: Int?): String =
        when (value) {
            MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL -> "LOCAL"
            MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE -> "REMOTE"
            null -> "unknown"
            else -> "unknown($value)"
        }

    private fun capturePolicy(value: Int?): String =
        when (value) {
            AudioAttributes.ALLOW_CAPTURE_BY_ALL -> "ALL"
            AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM -> "SYSTEM"
            AudioAttributes.ALLOW_CAPTURE_BY_NONE -> "NONE"
            null -> "unknown"
            else -> "unknown($value)"
        }

    private fun safeText(value: String?): String =
        value
            ?.take(80)
            ?.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }
            ?.joinToString("")
            ?.ifBlank { "unknown" }
            ?: "unknown"

    private fun <T> query(block: () -> T): T? = runCatching(block).getOrNull()

}
