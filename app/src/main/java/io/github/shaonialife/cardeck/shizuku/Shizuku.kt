package io.github.shaonialife.cardeck.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.shaonialife.cardeck.util.Bg
import moe.shizuku.server.IShizukuService
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku 网关 + shell 执行 + CarInput UserService 绑定。
 * 激活（无线调试，Android 11+，免 root）：开发者选项 → 无线调试 → 配对 → Shizuku App 点启动。
 * （移植自参考项目 android-auto）
 */
object ShizukuGate {
    enum class State { NOT_INSTALLED, NOT_RUNNING, UNAUTHORIZED, READY }

    /** Shizuku 授权请求码（授权结果回调按它过滤）。 */
    const val PERMISSION_REQUEST_CODE = 101

    /** Shizuku App 包名（打开/检测共用）。 */
    const val PACKAGE_NAME = "moe.shizuku.privileged.api"

    fun state(context: Context): State {
        return try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return State.NOT_INSTALLED
        }.let {
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) return State.NOT_RUNNING
                if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    State.READY
                } else {
                    State.UNAUTHORIZED
                }
            } catch (_: Throwable) {
                State.NOT_RUNNING
            }
        }
    }

    fun requestPermission() {
        try {
            rikka.shizuku.Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /**
     * 未就绪时的引导文案（纯函数，可单测）。
     *
     * 三种未就绪状态的**处置方式完全不同**（装/启动/授权），混成一句
     * 「Shizuku 未就绪」用户无从下手——这是共享给朋友后最容易被问住的地方。
     */
    internal fun guidanceOf(state: State): ShizukuGuidance =
        when (state) {
            State.NOT_INSTALLED ->
                ShizukuGuidance(
                    title = "需要先安装 Shizuku",
                    message =
                        "连接车机依赖 Shizuku 提供的 shell 权限：创建虚拟屏、注入触摸、" +
                            "读取窗口状态都要经过它。\n\n" +
                            "请先安装 Shizuku App 并按官方说明启动服务（无线调试激活，免 root），" +
                            "完成后回到这里点「重试检查」。",
                    primaryLabel = null,
                )

            State.NOT_RUNNING ->
                ShizukuGuidance(
                    title = "CarDeck 尚未连接到 Shizuku",
                    message =
                        "当前 CarDeck 尚未连接到 Shizuku，此时连接车机会失败。\n\n" +
                            "请打开 Shizuku，确认服务正在运行，并在「授权应用」中允许 CarDeck BMW诊断版；" +
                            "然后返回这里点「重试检查」。若仍未接通，再尝试重新启动 Shizuku。",
                    primaryLabel = "打开 Shizuku",
                )

            State.UNAUTHORIZED ->
                ShizukuGuidance(
                    title = "未授权本应用使用 Shizuku",
                    message =
                        "Shizuku 服务已在运行，但本应用还没有获得授权，此时连接会失败" +
                            "（车机没有画面、触摸也不可用）。\n\n" +
                            "点「请求授权」并在弹出的窗口中点「允许」；若之前拒绝过，" +
                            "请到 Shizuku App →「授权应用」里手动允许本应用。",
                    primaryLabel = "请求授权",
                )

            State.READY -> ShizukuGuidance("Shizuku 就绪", "", null)
        }
}

/** Shizuku 未就绪时的引导内容（见 [ShizukuGate.guidanceOf]）。 */
internal data class ShizukuGuidance(
    val title: String,
    val message: String,
    /** null = 本机没有可执行的主操作（未安装时无处可跳）。 */
    val primaryLabel: String?,
)

/** 以 shell 身份执行命令（等同 adb shell）。Shizuku api 13 需经 IShizukuService binder 调 newProcess。 */
object Shell {
    class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    /** 主线程调用 `Shell.exec` 的累计次数（诊断用；见 [exec] 里的守卫）。 */
    @Volatile
    var mainThreadViolations: Int = 0
        private set

    /**
     * 在主线程调用 shell 的告警出口（SessionController.appendLog）。
     * 单条 shell 通常 100ms 量级、超时可达数秒，串几条就够 ANR——
     * 这类问题只在真机上暴露（HKS 下 logcat 不可读），必须进文件日志。
     */
    @Volatile
    var violationSink: ((String) -> Unit)? = null

    fun exec(
        cmd: String,
        timeoutMs: Long = 6000L,
    ): Result {
        // **UI 线程禁止 shell**：每次 exec 都是 Shizuku newProcess + 进程等待。
        // 这里不强行拦截（会让原本可用的功能失效），但记录并告警——历史上
        // 已有两次真机 ANR 源于此（detach 清理、横屏勾选框）。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            mainThreadViolations++
            violationSink?.invoke(
                "SHELL: ⚠ 主线程调用 shell（第 $mainThreadViolations 次）cmd=${cmd.take(60)}" +
                    "（$timeoutMs ms 上限）——应改用 Bg.run / 后台线程",
            )
        }
        val binder =
            rikka.shizuku.Shizuku.getBinder()
                ?: return Result(-1, "Shizuku 未连接")
        return try {
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)

            Bg.run("shell-stderr") {
                try {
                    process.errorStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes()
                    }
                } catch (_: Exception) {
                }
            }

            val output =
                try {
                    process.inputStream.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).readBytes().decodeToString().trim()
                    }
                } catch (_: Exception) {
                    ""
                }

            if (!process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.toString())) {
                process.destroy()
            }
            val exit =
                try {
                    process.exitValue()
                } catch (_: Exception) {
                    -1
                }
            Result(exit, output)
        } catch (e: Exception) {
            Result(-1, "命令执行失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** CarInput UserService 的绑定管理：绑定一次后所有触摸注入直连常驻 shell 进程（毫秒级）。 */
object CarInputClient {
    @Volatile
    var service: ICarInput? = null
        private set

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var userServiceArgs: rikka.shizuku.Shizuku.UserServiceArgs? = null

    @Volatile
    var logSink: ((String) -> Unit)? = null

    private val stateLock = Any()
    private val state = UserServiceBindingState()
    private val ensureQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val verificationBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var binderListenerAdded = false
    private var attempt: BindingAttempt? = null
    private var serverBinder: IBinder? = null
    private var diagAt = 0L

    private class BindingAttempt(val token: Long) {
        lateinit var connection: ServiceConnection

        @Volatile
        var binder: IBinder? = null

        @Volatile
        var deathRecipient: IBinder.DeathRecipient? = null
    }

    private fun diag(message: String, force: Boolean = false) {
        Log.i(TAG, message)
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(stateLock) {
            if (!force && now - diagAt < 1_000L) return
            diagAt = now
        }
        logSink?.invoke("SHIZUKU: $message")
    }

    /** 只读已验证状态；不在 UI 或触摸事件线程发 Binder ping。 */
    fun isReady(): Boolean = service != null

    /** 等待预算只约束调用方，不宣称能取消 SDK 注册或已经发出的 Binder 调用。 */
    fun awaitReady(
        context: Context,
        timeoutMs: Long = 12_000L,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            diag("awaitReady 拒绝主线程等待", force = true)
            return false
        }
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs.coerceIn(0L, 12_000L)
        ensure(context)
        while (!isCancelled() && !Thread.currentThread().isInterrupted) {
            if (isReady()) return true
            if (android.os.SystemClock.uptimeMillis() >= deadline) break
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            ensure(context)
        }
        return false
    }

    /** 所有调用方仅排队；权限探测、ping、shell 均在后台，SDK 注册独占主线程队列。 */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        if (isReady() || !synchronized(stateLock) { state.canRequest(android.os.SystemClock.uptimeMillis()) }) return
        if (!ensureQueued.compareAndSet(false, true)) return
        Bg.run("car-input-ensure") {
            var posted = false
            try {
                val app = appContext ?: return@run
                if (ShizukuGate.state(app) != ShizukuGate.State.READY) return@run
                // Shizuku API 的回调 HashSet 在主线程遍历，注册/注销也必须在该队列，
                // 且 post 到回调迭代之后。这里只允许 SDK 注册 IPC；不执行 ping/等待/shell。
                posted = mainHandler.post {
                    try {
                        registerListeners()
                        bindOnMain(app)
                    } finally {
                        ensureQueued.set(false)
                    }
                }
            } finally {
                if (!posted) ensureQueued.set(false)
            }
        }
    }

    private fun registerListeners() {
        if (binderListenerAdded) return
        binderListenerAdded = true
        rikka.shizuku.Shizuku.addBinderReceivedListener {
            // 始终跳出 SDK 自身的监听器迭代，避免重入修改其注册表。
            mainHandler.post {
                val next = rikka.shizuku.Shizuku.getBinder()
                val changed = synchronized(stateLock) {
                    if (serverBinder === next) false else {
                        serverBinder = next
                        state.invalidate()
                        service = null
                        true
                    }
                }
                if (changed) diag("server binder changed; input service will rebind", force = true)
                appContext?.let { ensure(it) }
            }
        }
        rikka.shizuku.Shizuku.addBinderDeadListener {
            mainHandler.post {
                // 若死回调滞后到新服务到达之后，保留新代次。
                if (rikka.shizuku.Shizuku.getBinder() != null) return@post
                synchronized(stateLock) {
                    serverBinder = null
                    state.invalidate()
                    service = null
                }
                diag("server binder dead; input service unavailable", force = true)
            }
        }
    }

    private fun bindOnMain(app: Context) {
        val next: BindingAttempt
        val previous: BindingAttempt?
        synchronized(stateLock) {
            if (service != null) return
            val token = state.request(android.os.SystemClock.uptimeMillis()) ?: return
            next = BindingAttempt(token)
            previous = attempt
            attempt = next
            serverBinder = rikka.shizuku.Shizuku.getBinder()
        }
        next.connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                // 仅捕获候选 Binder，验证和发布在后台；旧回调不能接管新绑定。
                if (binder == null) {
                    fail(next, "null binder")
                    return
                }
                val validate = synchronized(stateLock) {
                    if (attempt !== next || !state.beginVerification(next.token)) false else {
                        next.binder = binder
                        true
                    }
                }
                if (validate) verify(next, binder)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                fail(next, "input service disconnected")
            }
        }
        try {
            val args = userServiceArgs ?: rikka.shizuku.Shizuku.UserServiceArgs(
                ComponentName(app.packageName, CarInputServiceImpl::class.java.name),
            ).processNameSuffix("car_input")
                .debuggable(true)
                .version(serviceVersion())
                .also { userServiceArgs = it }
            // false 只解绑回调，不移除/销毁持有虚拟屏的服务。首次慢启动给足12秒，
            // 不再每1.5秒 remove=true；版本号仍负责 APK 更新后的服务代次。
            if (previous != null) {
                previous.deathRecipient?.let { death ->
                    runCatching { previous.binder?.unlinkToDeath(death, 0) }
                }
                rikka.shizuku.Shizuku.unbindUserService(args, previous.connection, false)
            }
            val registeredAt = android.os.SystemClock.uptimeMillis()
            rikka.shizuku.Shizuku.bindUserService(args, next.connection)
            diag("bindUserService requested generation=${next.token} registrationMs=${android.os.SystemClock.uptimeMillis() - registeredAt}", force = true)
        } catch (t: Throwable) {
            fail(next, "bind registration failed ${t.javaClass.simpleName}")
        }
    }

    private fun verify(candidate: BindingAttempt, binder: IBinder) {
        // 一个无响应 Binder 最多占用一个验证线程，避免重试堆积线程。
        if (!verificationBusy.compareAndSet(false, true)) {
            diag("input probe still pending; retaining startup deadline", force = true)
            return
        }
        Bg.run("car-input-verify") {
            var linkedDeath: IBinder.DeathRecipient? = null
            var published = false
            try {
                val input = ICarInput.Stub.asInterface(binder)
                val death = IBinder.DeathRecipient { fail(candidate, "input binder died") }
                candidate.deathRecipient = death
                binder.linkToDeath(death, 0)
                linkedDeath = death
                val ok = input?.ping() == true && binder.isBinderAlive
                published = synchronized(stateLock) {
                    if (ok && binder.isBinderAlive && attempt === candidate && state.verified(candidate.token)) {
                        service = input
                        true
                    } else false
                }
                if (published) {
                    diag("connected ping=true generation=${candidate.token}; touch ready", force = true)
                    appContext?.let { io.github.shaonialife.cardeck.net.ProjectionAppMobileData.recover(it) }
                } else {
                    fail(candidate, if (ok) "stale input probe discarded" else "input ping=false")
                }
            } catch (t: Throwable) {
                fail(candidate, "input probe failed ${t.javaClass.simpleName}")
            } finally {
                if (!published) linkedDeath?.let { death -> runCatching { binder.unlinkToDeath(death, 0) } }
                verificationBusy.set(false)
            }
        }
    }

    private fun fail(candidate: BindingAttempt, reason: String) {
        val current = synchronized(stateLock) {
            if (attempt !== candidate || !state.failed(candidate.token, android.os.SystemClock.uptimeMillis())) false else {
                service = null
                true
            }
        }
        if (current) diag("$reason generation=${candidate.token}", force = true)
    }

    /**
     * 回收旧版本残留的 UserService 进程，返回实际 kill 的数量（不足条件时 0）。
     *
     * Shizuku 按「组件+进程名+版本(lastUpdateTime)」复用常驻 shell 进程——重装 APK 后
     * 旧版本进程既不受 force-stop 影响、Shizuku 也不回收；而虚拟屏只有创建它的进程能
     * 释放（releaseDisplay 只查本进程的 map），表现为「重启后检测到残留屏，点清理却
     * 释放 0/N」。这里枚举 :car_input 进程，杀掉除当前绑定进程外的全部——旧进程只持有
     * 残留屏，进程回收即屏随之释放（不打断触摸注入，当前会话屏属于绑定进程）。
     * 配合 UserService 侧的过期自清（CarInputServiceImpl 的 stale self-reap）双保险。
     *
     * 阻塞调用（含 shell ps/kill），仅用于显式残留屏清理；自动重绑不调用此方法。
     */
    fun reapStaleUserServices(): Int {
        val bound = service ?: return 0
        val keepPid =
            try {
                bound.pid()
            } catch (t: Throwable) {
                // 旧 dex 无 pid() 事务（正常不会发生：Shizuku 按版本复用，绑定的必是当前版本）
                diag("reap 跳过：pid() 不可用（${t.javaClass.simpleName}）")
                return 0
            }
        val procName = (appContext?.packageName ?: return 0) + ":car_input"
        val ps = Shell.exec("ps -A -o PID,ARGS", 5_000L)
        if (!ps.ok) {
            diag("reap 跳过：ps 失败（${ps.output.take(40)}）")
            return 0
        }
        val stale =
            ps.output
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 2)
                    val pid = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    if (parts.getOrNull(1) == procName && pid != keepPid) pid else null
                }.toList()
        if (stale.isEmpty()) return 0
        val killed = stale.count { pid -> Shell.exec("kill -9 $pid", 3_000L).ok }
        // force：紧随 bind 的 1s 节流窗口会吞掉这条关键结果（排查时必查）
        diag("已回收 $killed/${stale.size} 个旧版本 UserService 进程（其残留虚拟屏随之释放）", force = true)
        return killed
    }

    /**
     * UserService 版本 = APK lastUpdateTime（秒）。Shizuku 按「组件+进程名+版本」复用
     * 常驻服务进程：硬编码版本号时重装 APK 不会换进程——复用的旧进程跑的还是旧 dex，
     * 新代码静默不生效（2026-10-15 实测：受信屏加 SUPPORTS_IME 后 flags 仍是旧值
     * 0x448 的根因；且 force-stop 杀不掉这些 shell 进程，会越积越多）。
     * lastUpdateTime 重装必变 → 必起新进程；APK 不变时恒定 → 正常复用常驻进程。
     */
    private fun serviceVersion(): Int =
        try {
            val pm = appContext?.packageManager ?: return 15

            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(appContext!!.packageName, 0)
            (info.lastUpdateTime / 1000L).toInt()
        } catch (_: Throwable) {
            15 // 兜底：与历史手写版本号序列衔接，保证不低于 14
        }

    private const val TAG = "CarInputClient"
}
