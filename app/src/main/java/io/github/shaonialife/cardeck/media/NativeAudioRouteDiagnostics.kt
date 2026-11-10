package io.github.shaonialife.cardeck.media

import android.content.pm.PackageManager
import android.os.Looper
import android.os.ParcelFileDescriptor
import io.github.shaonialife.cardeck.util.Bg
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** 仅用户导出时读取目标 UID 的 AAudio 原生端点；不改变音频策略或播放器。 */
internal object NativeAudioRouteDiagnostics {
    private const val TIMEOUT_MS = 4_500L
    // Binder 异常时上一轮可能仍在收尾；后续导出跳过，避免累积阻塞任务。
    private val inFlight = AtomicBoolean(false)

    fun collect(targetUids: Set<Int>): List<String> {
        val begin = android.os.SystemClock.elapsedRealtime()
        // 仅 Spotify/网易云两个目标，每个目标最多四条流；输出总行数不超过十二。
        val targets = targetUids.filter { it > 0 }.sorted().take(2).toSet()
        val rows = collectSnapshot(targets)
        val end = android.os.SystemClock.elapsedRealtime()
        return listOf("AUDIO_NATIVE sampledFromElapsedMs=$begin sampledToElapsedMs=$end") + rows
    }

    private fun collectSnapshot(targetUids: Set<Int>): List<String> {
        val startedAt = System.nanoTime()
        if (targetUids.isEmpty()) return listOf("AUDIO_NATIVE unavailable=no_target_uid")
        if (Looper.myLooper() == Looper.getMainLooper()) return unavailable(targetUids, "main_thread")
        if (!inFlight.compareAndSet(false, true)) return unavailable(targetUids, "previous_probe_busy")

        val probe = Probe()
        val future = try {
            Bg.pool.submit(Callable {
                try {
                    readDump(targetUids, probe)
                } catch (_: CancellationException) {
                    unavailable(targetUids, "cancelled")
                } catch (_: Exception) {
                    unavailable(targetUids, "read_failed")
                } finally {
                    probe.workerFinished()
                }
            })
        } catch (_: Exception) {
            inFlight.set(false)
            return unavailable(targetUids, "worker_unavailable")
        }
        return try {
            // 含权限检查和进程创建；Binder 卡住也不能延迟用户的日志导出。
            val remaining = TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS) - (System.nanoTime() - startedAt)
            future.get(remaining.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            unavailable(targetUids, "interrupted")
        } catch (_: TimeoutException) {
            unavailable(targetUids, "timeout")
        } catch (_: Exception) {
            unavailable(targetUids, "read_failed")
        } finally {
            // 不 cancel 排队的任务：它必须执行 finally，且清理真正结束后才能解除单飞。
            probe.cancel()
        }
    }

    private fun readDump(targetUids: Set<Int>, probe: Probe): List<String> {
        probe.checkCancelled()
        val ready = runCatching {
            rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!ready) return unavailable(targetUids, "shizuku_not_ready")
        probe.checkCancelled()
        val binder = rikka.shizuku.Shizuku.getBinder() ?: return unavailable(targetUids, "shizuku_not_ready")
        // 全部为固定字面量；合并错误流避免未消费的管道堵塞，也不另起 stderr 读取任务。
        // exec 让销毁的进程就是 dumpsys；不拼接包名、UID 或任何外部输入。
        val process = IShizukuService.Stub.asInterface(binder)
            .newProcess(arrayOf("sh", "-c", "exec dumpsys -t 3 media.aaudio 2>&1"), null, null)
        probe.attachProcess(process)
        probe.checkCancelled()
        val parser = NativeAudioRouteDumpParser(targetUids)
        var failure: String? = null
        val descriptor = process.inputStream
        probe.attachInput(descriptor)
        val scan = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            // 原始 dump 只经过有界行缓冲，不进文件、普通日志或报告。
            BoundedDiagnosticLines.read(input, 2_097_152, 16_384) { line ->
                probe.checkCancelled()
                failure = failure ?: failureSignal(line)
                parser.accept(line)
            }
        }
        probe.checkCancelled()
        val exited = process.waitForTimeout(300L, TimeUnit.MILLISECONDS.toString())
        val exitCode = if (exited) process.exitValue() else -1
        val complete = scan.complete && exitCode == 0 && failure == null
        val status = failure ?: if (complete) "complete" else "incomplete"
        return listOf("AUDIO_NATIVE source=dumpsys_media_aaudio status=$status bytes=${scan.bytes}") + parser.finish(complete)
    }

    private fun failureSignal(line: String): String? {
        val text = line.trim()
        return when {
            text.contains("DUMP TIMEOUT", ignoreCase = true) -> "service_timeout"
            text.startsWith("Permission Denial:", ignoreCase = true) ||
                text.startsWith("Permission denied", ignoreCase = true) ||
                text.startsWith("java.lang.SecurityException:") -> "permission_denied"
            text.startsWith("Can't find service:", ignoreCase = true) -> "service_unavailable"
            text.startsWith("Error dumping service", ignoreCase = true) -> "dump_failed"
            else -> null
        }
    }

    private fun unavailable(targetUids: Set<Int>, reason: String): List<String> =
        listOf("AUDIO_NATIVE unavailable=$reason") + NativeAudioRouteDumpParser(targetUids).finish(false)

    /**
     * 最多一个读取任务和一个清理任务。远端 Binder 不响应时不靠 interrupt 假装已回收；
     * 两个任务均实际退出才放行下一轮，超时只影响这次附加诊断，不阻塞导出。
     */
    private class Probe {
        private val cancelled = AtomicBoolean(false)
        private val lock = Any()
        private var process: IRemoteProcess? = null
        private var input: ParcelFileDescriptor? = null
        private var workerDone = false
        private var cleanupStarted = false
        private var cleanupDone = false
        private var released = false

        fun checkCancelled() {
            if (cancelled.get()) throw CancellationException()
        }

        fun attachProcess(value: IRemoteProcess) {
            synchronized(lock) { process = value }
            // 创建进程的 Binder 调用可能晚于 4.5 秒返回；返回后仍必须接管并回收它。
            if (cancelled.get()) requestCleanup()
        }

        fun attachInput(value: ParcelFileDescriptor) {
            val closeNow = synchronized(lock) {
                input = value
                cancelled.get() || cleanupStarted
            }
            if (closeNow) {
                runCatching { value.close() }
                throw CancellationException()
            }
        }

        fun cancel() {
            cancelled.set(true)
            requestCleanup()
        }

        fun workerFinished() {
            synchronized(lock) { workerDone = true }
            requestCleanup()
            releaseIfFinished()
        }

        private fun requestCleanup() {
            val owned = synchronized(lock) {
                if (cleanupStarted || (process == null && !workerDone)) return
                cleanupStarted = true
                process to input
            }
            if (owned.first == null && owned.second == null) {
                synchronized(lock) { cleanupDone = true }
                releaseIfFinished()
                return
            }
            try {
                Bg.pool.execute {
                    try {
                        runCatching { owned.second?.close() }
                        runCatching { owned.first?.destroy() }
                    } finally {
                        synchronized(lock) { cleanupDone = true }
                        releaseIfFinished()
                    }
                }
            } catch (_: Exception) {
                // 无清理线程时保留 busy，不能在导出线程执行可能卡住的远端销毁。
            }
        }

        private fun releaseIfFinished() {
            synchronized(lock) {
                if (!released && workerDone && cleanupDone) {
                    released = true
                    inFlight.set(false)
                }
            }
        }
    }
}

