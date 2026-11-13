package io.github.shaonialife.cardeck

import android.content.Context
import android.view.KeyEvent
import io.github.shaonialife.cardeck.airplay.AirPlayClient
import io.github.shaonialife.cardeck.airplay.BmwControlIapCompatibility
import io.github.shaonialife.cardeck.airplay.BmwRequestUiRecoveryPolicy
import io.github.shaonialife.cardeck.airplay.PairVerifyClient
import io.github.shaonialife.cardeck.audio.AudioCompatibilityPolicy
import io.github.shaonialife.cardeck.audio.AudioProfile
import io.github.shaonialife.cardeck.audio.AudioProfileMemory
import io.github.shaonialife.cardeck.audio.AudioSender
import io.github.shaonialife.cardeck.audio.AudioTuning
import io.github.shaonialife.cardeck.bt.BtProfiles
import io.github.shaonialife.cardeck.bt.BtSessionGate
import io.github.shaonialife.cardeck.bt.Iap2BluetoothClient
import io.github.shaonialife.cardeck.bt.Iap2BluetoothServer
import io.github.shaonialife.cardeck.bt.TargetDevicePrefs
import io.github.shaonialife.cardeck.capture.HiddenDisplaySource
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import io.github.shaonialife.cardeck.iap2.ReceiverProfile
import io.github.shaonialife.cardeck.input.ConsumerKeys
import io.github.shaonialife.cardeck.input.BmwRotaryHid
import io.github.shaonialife.cardeck.input.BmwShortcutTarget
import io.github.shaonialife.cardeck.input.BmwShortcutObservations
import io.github.shaonialife.cardeck.input.PhoneBixby
import io.github.shaonialife.cardeck.input.SteeringDialProbe
import io.github.shaonialife.cardeck.input.SteeringVoiceKey
import io.github.shaonialife.cardeck.input.CarShortcutLearning
import io.github.shaonialife.cardeck.launcher.CarShortcut
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.input.CarControllerRouter
import io.github.shaonialife.cardeck.input.TouchReports
import io.github.shaonialife.cardeck.input.TouchRouter
import io.github.shaonialife.cardeck.launcher.settings.AppOrientationCompat
import io.github.shaonialife.cardeck.launcher.settings.DisplayPrefs
import io.github.shaonialife.cardeck.net.AirPlayFeatures
import io.github.shaonialife.cardeck.net.CarPlayCtrlAdvertiser
import io.github.shaonialife.cardeck.net.CarPlayServiceBrowser
import io.github.shaonialife.cardeck.net.EndpointCallbackQueue
import io.github.shaonialife.cardeck.net.EndpointHosts
import io.github.shaonialife.cardeck.net.SessionAttemptOwnership
import io.github.shaonialife.cardeck.net.VpnCoexistenceController
import io.github.shaonialife.cardeck.net.VpnCoexistencePrefs
import io.github.shaonialife.cardeck.net.WifiCarPlayConnector
import io.github.shaonialife.cardeck.net.WifiJoinOwnership
import io.github.shaonialife.cardeck.net.WifiJoinRecovery
import io.github.shaonialife.cardeck.net.WifiSavedNetworks
import io.github.shaonialife.cardeck.service.CarPlayService
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.shizuku.ShizukuGate
import io.github.shaonialife.cardeck.ui.ScreenDimmer
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import io.github.shaonialife.cardeck.video.VideoSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 进程级会话中枢：**日志缓冲**与**会话管线所有权**的唯一持有者。
 *
 * 背景（2026-10-11 用户实测发现三个连带问题）：投屏管线运行在 MainActivity 派生线程里，
 * 划掉界面（Activity 销毁）后进程被前台服务保活、管线继续跑；但重进应用创建的是**新
 * Activity 实例**——
 *  1. 老线程的日志写进老实例（已销毁）的 UI → 新界面"日志断联"（文件日志实际未断）
 *  2. 「断开连接」操作新实例的新对象 → 老管线无人持有，断不掉
 *  3. 重开连接会建第二套对象 → 双会话风险
 *
 * 本对象把这些状态提升为进程级单例：Activity 只是"挂屏"（订阅日志、转发按钮），
 * 销毁/重建对其无影响。会话逻辑线程通过 [appContext] 运行，不依赖任何 Activity。
 */
object SessionController {
    internal val steeringDialProbe = SteeringDialProbe()
    // ---- 日志缓冲（进程级；Activity 订阅渲染）----

    private val logLock = Any()
    private val logBuilder = StringBuilder()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 当前挂屏 Activity 的渲染回调（收到**本条新增日志行**，含时间戳前缀）；Activity onDestroy 时清空。 */
    @Volatile
    var logListener: ((String) -> Unit)? = null

    /** 「监听日志」开关（由 Activity 从偏好读取后同步；关闭时不采集不渲染）。 */
    @Volatile
    var logEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /** WiFi 等待用户操作的窗口（打开 WLAN 开关 / 手动加入热点的轮询上限）。 */
    private const val WLAN_WAIT_TIMEOUT_MS = 120_000L

    /** 加入确认弹窗的应答等待上限（无人应答按中止处理）。 */
    private const val JOIN_DIALOG_TIMEOUT_S = 90L

    /**
     * 等车机 ctrl 邀请的上限（事件驱动，收到即走）。
     * catplay 以邀请为连接触发点；实测邀请在 ctrl 重注册后 ~1.0-1.3s 到达。
     * 车机不发邀请时（旧机型）由「已发现 _airplay._tcp」提前放行，最坏也只等这么久。
     */
    private const val CTRL_INVITE_WAIT_MS = 4_000L
    // After repeated WLAN losses the MGU sent its fresh invitation ~45 s after rebinding.
    private const val BMW_RECOVERY_INVITE_WAIT_MS = 50_000L

    /** 端点不完整时等 mDNS 缓存命中：绑网时已开始发现，通常几百毫秒内命中。 */
    private const val ENDPOINT_RESOLVE_WAIT_MS = 3_000L

    /**
     * 明文 SETUP 被拒后「加密升级」补配对的上限（catplay handle_setup_result 路径）。
     * 只在实际被拒时才走到这里，正常车机零成本；不支持配对的接收端会在
     * pair-setup 收到 404/405/455 后立刻放弃，不会等满这个窗口。
     */
    private const val PAIRING_UPGRADE_WINDOW_MS = 30_000L

    /**
     * 明文会话初始 SETUP 的重试窗口。pre-HomeKit 车机的 AirPlay 端口常在
     * 「用户点车机上的手机图标」后才开放；端口晚开 1~2 秒不该让整轮失败。
     */
    private const val INITIAL_SETUP_WINDOW_MS = 30_000L

    /**
     * `requestNetwork(WIFI)` 等 onAvailable 的上限（秒）。
     * 用户正在系统设置里手动加入热点时，回调要等关联+DHCP 完成才来，实测可达 10s+。
     * 旧值 5s 会在成功前 116ms 误判失败并放弃整轮会话（见 bindCarWifiAndRegister）。
     */
    private const val WIFI_BIND_WAIT_S = 15L

    /**
     * 等用户确认「连接车机热点」系统弹窗的上限（应用专属网络）。
     * 系统确认框可能停留很久；用户取消连接时 [connectCancelled] 会即时退出。
     */
    private const val SPECIFIER_JOIN_TIMEOUT_MS = 120_000L
    private const val SPECIFIER_WITH_EXISTING_WLAN_MS = 20_000L

    /** Activity onCreate 时调用：登记 applicationContext 并创建会话对象（幂等）。 */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        ensureSessionObjects(context.applicationContext)
        // 横屏兼容写入失败的原因（user build 限制 / Shizuku 故障）只有这里能落盘：
        // 车机侧设置面板的 toast 只给用户看，排查需要日志。
        AppOrientationCompat.diagSink = ::appendLog
        // 主线程调用 shell 的告警（历史上两次真机 ANR 都源于此；HKS 下 logcat 不可读）
        Shell.violationSink = ::appendLog
    }

    /** 追加一行日志：logcat + 文件 + 进程级缓冲 + 挂屏渲染（增量行直送）。任意线程可调。 */
    fun appendLog(message: String) {
        // NUL 会写进日志文件：文本编辑器/文件管理器打不开（Read 工具也会判为二进制），
        // 用户反馈日志时等于白导。0x4301 的定长地址字段曾原样打印出多个 NUL
        // （2026-10-18 实测 216 个），这里替换成可见占位符——顺带让「字符串里有脏字节」
        // 在日志上直接可见，而不是隐形成 InetSocketAddress 的解析失败。
        val clean = if (message.indexOf('\u0000') >= 0) message.replace("\u0000", "␀") else message
        android.util.Log.i("CarPlaySender", clean)
        appContext?.let {
            DebugFileLog
                .append(it, clean)
        }
        if (!logEnabled) return
        val line =
            synchronized(logLock) {
                val stamped = '[' + timestamp.format(Date()) + "] " + clean + '\n'
                logBuilder.append(stamped)
                if (logBuilder.length > 40_000) logBuilder.delete(0, logBuilder.length - 20_000)
                stamped
            }
        // 增量行直送 UI：此前每行 toString 整个缓冲再 setText 全文，
        // 高频日志（触摸/帧）下是 UI 线程与 GC 的双重放大器
        logListener?.invoke(line)
    }

    fun logText(): String = synchronized(logLock) { logBuilder.toString() }

    /**
     * 派发系统媒体键（AudioManager 全局路由给**当前活跃媒体会话**，无需权限）。
     * 桌面音乐卡的按钮与车机/方向盘媒体键（Consumer HID）共用这一路径。
     */
    fun dispatchMediaKey(keyCode: Int) {
        val ctx = appContext ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /**
     * 清空进程级日志缓冲（UI 刷新是调用方的事：显式 `renderConsole(logText())`）。
     * 历史上这里靠 `logListener?.invoke("")` 通知 UI——那是「每行全量 setText」时代的
     * 语义；改为增量 appendConsole 后空串等于追加空行，UI 原地不动（「清除按钮点了
     * 没反应」根因，2026-10-16）。不要恢复该调用：空串与真实日志行无法区分。
     */
    fun clearLog() {
        synchronized(logLock) { logBuilder.setLength(0) }
    }

    // ---- 会话对象（进程级所有权；Activity 重建不影响）----

    @Volatile private var wifiConnector: WifiCarPlayConnector? = null
    @Volatile private var vpnCoexistenceController: VpnCoexistenceController? = null

    internal fun projectionWifi(): android.net.Network? = wifiConnector?.currentNetwork
    internal fun projectionNetworkBlocked(handle: Long): Boolean = vpnCoexistenceController?.isBlocked(handle) == true

    /**
     * 当前已绑定到哪个车机热点（bindCarWifiAndRegister 成功后写入）。
     * 端点交接时若已绑定到同一 SSID，跳过二次 bindCurrentWifi——它内部先
     * release() 解绑，会打断刚注册好的 ctrl mDNS 广播并白等系统重连。
     */
    @Volatile
    private var boundCarSsid: String? = null

    // 同一次连接的重试不重复弹窗；用户明确保留的配置跨连接继续保留。
    private val savedPromptEpoch = java.util.concurrent.ConcurrentHashMap<String, WifiJoinOwnership.Epoch>()
    private val keptSavedNetworks = java.util.concurrent.ConcurrentHashMap<String, List<Int>>()

    /** WiFi 绑定互斥：auto-join 与端点交接两条路径可能同时绑定同一热点。 */
    private val wifiBindLock = Any()
    private val wifiJoins = WifiJoinOwnership()
    private val sessionAttempts = SessionAttemptOwnership<android.net.Network>()
    // 端点处理可能等待入网锁；必须离开 BT reader，保活泵才能继续接收控制消息。
    private val endpointCallbacks = EndpointCallbackQueue(
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "carplay-endpoint-dispatch").apply { isDaemon = true }
        },
    )

    /** DCL 双检锁的可发布字段（无 volatile 的双检锁是坏发布——虽然当前仅主线程调用，别留隐患）。 */
    @Volatile
    private var bluetoothServer: Iap2BluetoothServer? = null
    private var bluetoothClient: Iap2BluetoothClient? = null
    private var ctrlAdvertiser: CarPlayCtrlAdvertiser? = null

    /**
     * 常驻 `_airplay._tcp` 发现器（对照 catplay 的常驻 Bonjour cache）。
     * 加入热点即开始后台发现，交接端点时地址多半已就绪，不再现查现等。
     */
    private var endpointBrowser: CarPlayServiceBrowser? = null

    @Volatile
    var videoSender: VideoSender? = null

    // ---- 「显示」运行时生效值（会话建立时快照；英雄卡展示运行时值而非偏好值）----

    /** 本会话流分辨率缩放百分比（按车机分辨率的百分比生成；100=原尺寸）。 */
    @Volatile
    var activeScalePercent: Int = 100

    /** 本会话目标帧率（合成节奏上限；「显示」设置运行中切换可实时生效）。 */
    @Volatile
    var activeTargetFps: Int = 30

    @Volatile
    var audioSender: AudioSender? = null

    @Volatile
    var currentClient: AirPlayClient? = null

    private val userScreenRefreshBusy = AtomicBoolean(false)
    @Volatile private var reconnectBorrowedScreen: ((AirPlayClient, Long) -> Unit)? = null

    fun canRefreshBmwScreen(): Boolean = currentClient?.let {
        !it.cancelled && !connectCancelled && videoSender?.isRunning == true && BmwControlIapCompatibility.matches(it.receiverIdentity())
    } == true

    fun requestBmwScreenRefresh() {
        val client = currentClient ?: return
        val sender = videoSender ?: return
        val revision = client.screenRevisionAtTap() ?: return
        if (!canRefreshBmwScreen() || !userScreenRefreshBusy.compareAndSet(false, true)) return
        Bg.run("bmw-user-screen-return") {
            try {
                val reason = refreshBmwScreen(client, sender, revision, userRequested = true)
                if (currentClient === client && !client.cancelled && !connectCancelled) {
                    if (reason in setOf("awaiting_screen_return", "loan_refresh_failed") &&
                        offerBmwScreenReconnect(client, sender, revision)
                    ) return@run
                    val message = when (reason) {
                        "frame_requested" -> "已请求刷新投屏画面"
                        "receiver_owns_screen", "native_activity_active" ->
                            "宝马仍在使用屏幕。请在电话或倒车结束后，在 iDrive 点「Apple CarPlay」"
                        else -> "车机画面尚未恢复，请在 iDrive 点「Apple CarPlay」"
                    }
                    uiHintSink?.invoke(message)
                }
            } finally {
                userScreenRefreshBusy.set(false)
            }
        }
    }

    private fun offerBmwScreenReconnect(client: AirPlayClient, sender: VideoSender, revision: Long): Boolean {
        val reconnect = reconnectBorrowedScreen ?: return false
        val show = dialogSink ?: return false
        fun current() = currentClient === client && !client.cancelled && !connectCancelled &&
            videoSender === sender && sender.isRunning && client.retryableScreenLoan == revision
        if (!current()) return false
        show(DialogSpec(
            title = "重新连接投屏？",
            message = "宝马仍在使用主屏。若已退出倒车、通话等原车界面，可保留当前 Wi-Fi 并重新连接投屏。",
            positive = "重新连接投屏", negative = "继续等待", isCurrent = ::current,
        )) { answer ->
            if (answer == true && current()) Bg.run("bmw-user-screen-reconnect") { reconnect(client, revision) }
        }
        return true
    }

    private fun dispatchReturnedMedia(client: AirPlayClient, sender: VideoSender, revision: Long): Boolean {
        val ctx = appContext ?: return false
        val generation = VirtualLauncher.sessionGeneration()
        fun live() = currentClient === client && !client.cancelled && !connectCancelled && videoSender === sender &&
            sender.isRunning && client.controllerInputRevision == revision && VirtualLauncher.isCurrentSession(generation)
        if (!live()) return false
        val token = client.takeReturnedMedia(revision) ?: return false
        if (!live() || CarShortcutLearning.isCalibrating(generation)) return false
        val page = CarShortcutLearning.offer(ctx, generation, token, CarShortcut.MEDIA) ?: return false
        val queued = VirtualLauncher.submitShortcut(page, ::live)
        appendLog("BMW-KEY: 已归还屏幕，恢复之前的 MEDIA queued=$queued")
        return queued
    }

    private fun refreshBmwScreen(client: AirPlayClient, sender: VideoSender, revision: Long, userRequested: Boolean): String {
        fun live() = currentClient === client && !client.cancelled && !connectCancelled && videoSender === sender && sender.isRunning
        if (!live()) return "session_changed"
        val result = client.refreshReleasedScreen(revision, userRequested)
        val granted = result.controllerRevision
        val refreshed = result.accepted && granted != null && live() && client.controllerInputRevision == granted && sender.requestKeyFrame()
        if (result.accepted && granted != null && live()) dispatchReturnedMedia(client, sender, granted)
        appendLog("BMW-SCREEN: refresh user=$userRequested revision=$revision reason=${result.reason} keyFrame=$refreshed")
        return if (refreshed) "frame_requested" else result.reason
    }

    internal fun shortcutCalibrationGeneration(): Long? {
        val client = currentClient ?: return null
        if (client.cancelled || connectCancelled || client.controllerInputRevision == null ||
            videoSender?.isRunning != true || VirtualLauncher.appContext() == null ||
            !BmwControlIapCompatibility.matches(client.receiverIdentity())
        ) return null
        return VirtualLauncher.sessionGeneration()
    }

    @Volatile
    var currentHiddenSource: HiddenDisplaySource? = null

    /** 当前会话的共享密钥与接收端地址：投影授权晚于连接时，动态补启音频用。 */
    @Volatile
    var currentSharedSecret: ByteArray? = null

    @Volatile
    var currentAudioHost: String? = null

    /** 本次会话是否在车机侧播放声音（由「启动连接」弹窗选择）。 */
    @Volatile
    var audioEnabledForSession: Boolean = false

    /**
     * 内部音频档位排序：启用时把非 media 档（compatibility 等）排前面，与采音方式无关。
     * 2026-10-19 Honda HFT 实测——media 档只声明 44.1k，该车机音频通路只吃得下 48k，
     * 走默认档会「上线几秒后整车机拆链」。
     */
    @Volatile
    var audioPreferCompat: Boolean = false

    /** 本轮真正用上的音频档位（拆链归因与记忆回写用；null = 本次没有音频）。 */
    @Volatile
    private var activeAudioProfile: AudioProfile? = null

    /** 音频档位记忆的键（车机身份文本，见 [AirPlayClient.receiverIdentity]）。 */
    @Volatile
    private var activeAudioReceiverKey: String = ""

    /** 音频通道真正开始推流的时刻（uptimeMillis）；0 = 未开始。 */
    @Volatile
    private var activeAudioStartedAt: Long = 0L

    /**
     * 音频「存活窗口」（ms）：超过它才认为该档位可用。
     * 依据 2026-10-19 实测：拆链发生在音频起流后 6.5s；取 20s 留足车机音频通路初始化
     * 与首个欠载周期的余量。
     */
    private const val AUDIO_PROFILE_PROBE_MS = 20_000L

    /** 会话建立互斥（端点交接 → 配对 → 推流全程只允许一条）。 */
    val sessionStarting = AtomicBoolean(false)

    /**
     * 交接流程已在跑、但 AirPlayClient 尚未创建时收到的晚到端点（0x4301）。
     * 配对线程起步时取用一次后清空——此刻没有 client 可更新，丢了就白等到下一轮。
     */
    @Volatile
    private var pendingEndpointUpdate: Iap2SenderSession.CarPlayEndpoint? = null

    /** 用户取消连接：配对线程在关键检查点看到后中止（mDNS/配对等待无法中途打断，粒度秒级）。 */
    @Volatile
    var connectCancelled: Boolean = false

    /**
     * 当前连接阶段的一句话提示（英雄卡副标题用，详细过程看日志）。
     * 由各阶段显式设置；null 时 UI 显示默认文案。
     */
    @Volatile
    var phaseHint: String? = null

    /** 取消「连接中」：停链路 + 置取消标志，卡在错误网络/等待回连时用户可主动脱身。 */
    fun cancelConnecting(context: Context) {
        connectCancelled = true
        appendLog("SESSION: 用户取消连接——正在停止蓝牙/WiFi 链路")
        disconnect(context)
        // 强制释放互斥：配对流程线程可能阻塞在不可中断的连接/mDNS 等待上（如手机
        // 不在热点网段时连热点 IP），finally 遥遥无期——用户显式取消即强制解锁，
        // 否则英雄卡永久「正在连接」（旧流程产出已被 retireSession 清理，无冲突）
        sessionStarting.set(false)
        phaseHint = "已取消连接"
    }

    /**
     * 最近一次 0x5703 的热点密码（仅内存，不落盘）。
     * 「车机热点 → 去连接」按钮复制到剪贴板用（手动加入兜底场景）。
     */
    @Volatile
    var lastWifiPsk: String? = null

    /** 挂屏 UI 钩子：SSID 变化时通知当前 Activity 回填输入框（可空）。 */
    @Volatile
    var ssidSink: ((String) -> Unit)? = null

    /** 挂屏 UI 钩子：重要连接提示（WiFi 未开启/热点加入限制等）弹给用户看。 */
    @Volatile
    var uiHintSink: ((String) -> Unit)? = null

    /** 弹窗规格（加入热点确认等两按钮询问）。 */
    data class DialogSpec(
        val title: String,
        val message: String,
        val positive: String,
        val negative: String,
        val isCurrent: () -> Boolean = { true },
    )

    /**
     * 挂屏 UI 钩子：两按钮确认弹窗。
     * onChoice：true=positive；false=negative；null=无人应答/被关闭（调用方兜底）。
     */
    @Volatile
    var dialogSink: ((DialogSpec, onChoice: (Boolean?) -> Unit) -> Unit)? = null

    private fun ensureSessionObjects(context: Context) {
        if (bluetoothServer != null) return
        synchronized(this) {
            if (bluetoothServer != null) return
            vpnCoexistenceController = VpnCoexistenceController(context.applicationContext, ::appendLog)
            wifiConnector =
                WifiCarPlayConnector(context) { line -> appendLog(line) }.also { connector ->
                    // 应用专属网络被系统断开（车机熄火/离开范围）：明确告知用户，
                    // 而不是让英雄卡在「已连接」与失败之间静默摇摆
                    connector.onNetworkLost = { loss ->
                        // Connector 已在后台投递；与新网络发布共锁，旧失联通知不能清掉新发现器。
                        synchronized(wifiBindLock) {
                            if (connectCancelled || !connector.isCurrentLoss(loss)) return@synchronized
                            // 失联后 RECORD 的迟到成功不能再发布编码器/虚拟屏；只退役该网络的会话。
                            if (sessionAttempts.lose(loss.network) != null) {
                                appendLog("AIRPLAY: 车机网络失联——取消所属配对和推流，等待下一轮连接")
                                retireSessionResources()
                            }
                            val lostSsid = loss.ssid.ifBlank { boundCarSsid.orEmpty() }
                            wifiJoins.noteLostRequest(lostSsid)
                            val ssid = lostSsid.ifBlank { "未知" }
                            endpointBrowser?.stop()
                            boundCarSsid = null
                            phaseHint = "等待车机重新连接（$ssid）"
                            uiHintSink?.invoke("车机 Wi-Fi 请求已失效，正在等待下一轮连接")
                            appendLog(">>> 车机热点 \"$ssid\" 的请求已失效，下一轮握手会重新核对热点")
                        }
                    }
                }
            val wifiCredentialHook: (Iap2SenderSession.WifiConfiguration) -> Unit =
                { wifi -> onWifiCredentials(wifi) }
            // 目标车机动态读取：选择后下一轮连接尝试/下一次连入即生效，无需重启链路
            val targetProvider: () -> TargetDevicePrefs.Target? = {
                appContext?.let { TargetDevicePrefs.get(it) }
            }
            bluetoothServer =
                Iap2BluetoothServer(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> dispatchEndpoint(endpoint, "车机回连", late = false) },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> dispatchEndpoint(endpoint, "车机回连", late = true) },
                    targetProvider = targetProvider,
                )
            bluetoothClient =
                Iap2BluetoothClient(
                    context,
                    log = { line -> appendLog(line) },
                    onEndpoint = { endpoint -> dispatchEndpoint(endpoint, "主动连车机", late = false) },
                    onWifiCredentials = wifiCredentialHook,
                    onEndpointUpdate = { endpoint -> dispatchEndpoint(endpoint, "主动连车机", late = true) },
                    targetProvider = targetProvider,
                )
            ctrlAdvertiser = CarPlayCtrlAdvertiser(context) { line -> appendLog(line) }
            endpointBrowser =
                CarPlayServiceBrowser(
                    context,
                    log = { line -> appendLog(line) },
                    accept = { info ->
                        // 手机双归属（连车机热点 + 自己开热点共享给电脑）时 mDNS 会跨网
                        // 发现电脑的 AirPlay 接收器（macOS 自带）——2026-10-11 实测把
                        // MacBook 当成车机连了 120s。非本网段一律不算车机。
                        // 地址判定统一走 EndpointHosts：IPv4 按 /24 前缀，链路本地 IPv6
                        // 天然只在本链路，全局 IPv6 按 /64 比对。旧实现只认 IPv4，会把
                        // 只广播 fe80:: 的奥迪 4xxx 直接丢掉（2026-10-19）。
                        val ip = info.host?.hostAddress ?: return@CarPlayServiceBrowser false
                        EndpointHosts.isSameNetwork(
                            ip,
                            wifiConnector?.localIpv4Address(),
                            wifiConnector?.localIpv6Addresses().orEmpty(),
                        )
                    },
                )
        }
    }

    /** 状态行文本（Activity 渲染 statusText 用）。 */
    fun statusLine(): String =
        "wifi=${wifiConnector?.currentNetwork != null} " +
            "bt=${bluetoothServer?.isRunning == true} ctrl=${(ctrlAdvertiser?.port ?: -1) > 0}"

    /** 会话是否活跃（推出流进程或 BT 任一在跑）。 */
    fun isSessionRunning(): Boolean =
        videoSender?.isRunning == true ||
            bluetoothServer?.isRunning == true ||
            bluetoothClient?.isRunning == true

    /**
     * 「启动连接」主体（权限检查由 Activity 完成后调用）：前台保活服务 + 双向蓝牙。
     * 幂等：已有会话时 BT 组件的 start() 自行短路，不会双开。
     */
    fun startConnection(context: Context) {
        if (ShizukuGate.state(context) != ShizukuGate.State.READY || !CarInputClient.isReady()) {
            appendLog("SHIZUKU: 触控服务未接通，已阻止启动；请在手机端重试准备")
            return
        }
        val connectionEpoch = wifiJoins.activate(VpnCoexistencePrefs.isEnabled(context))
        connectCancelled = false
        endpointCallbacks.activate()
        phaseHint = "蓝牙就绪，等待车机回连…"
        when (
            ShizukuGate
                .state(context)
        ) {
            ShizukuGate.State.READY ->
                appendLog("SHIZUKU: 权限就绪，触控服务已接通")

            ShizukuGate.State.UNAUTHORIZED -> {
                appendLog("SHIZUKU: 未授权本应用——已发起授权请求，请在弹窗点允许后重连")
                ShizukuGate
                    .requestPermission()
            }

            ShizukuGate.State.NOT_RUNNING ->
                appendLog("SHIZUKU: 服务未运行——打开 Shizuku App 点「启动」")

            ShizukuGate.State.NOT_INSTALLED ->
                appendLog("SHIZUKU: 未安装（隐形屏模式需要）")
        }
        CarPlayService
            .start(context)
        vpnCoexistenceController?.start(detailedDiagnostics = connectionEpoch.vpnCoexistence)
        // 能力发布先于连接（catplay 的做法：CarPlayCtrlServer 在会话一开始就常驻广播
        // `_carplay-ctrl._tcp`，之后每 1s force_announce，收到车机邀请才建 AirPlay）。
        // 我们此前要等 0x5703 才注册，导致「车机发现手机」这一步总落在热点绑定之后，
        // 每轮多花 1~3 秒；现在启动连接即发布，换网时 ensureForNetwork 会自动重注册。
        ctrlAdvertiser?.resetInviteCount()
        // 后台发布：start() 首次要向 Shizuku 解析真实蓝牙 MAC（shell），不能在主线程做
        Bg.run("ctrl-publish") { ctrlAdvertiser?.start() }
        // 真车路径：手机主动连车机（SDP …caff 配件服务）
        // 测试台路径：监听手机侧 iPhone UUID，等 DiPlay/xcertplay 回连
        bluetoothClient?.start()
        bluetoothServer?.start()
        // 诊断（奥迪实测：手机与车机「蓝牙已连接」时车机不开放 CarPlay）：
        // 连之前把目标设备的蓝牙配置文件状态打出来，失败时一眼看出是不是这个原因。
        Bg.run("bt-profile-diag") {
            val target = appContext?.let { TargetDevicePrefs.get(it) } ?: return@run
            val connected = BtProfiles.connectedNames(context.applicationContext, target.address)
            appendLog(
                "BT-PROFILE: 连接前「${target.name}」的蓝牙配置文件状态=" +
                    (connected.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "未连接"),
            )
        }
    }

    /** 「断开连接」（可在任意 Activity 实例上调用——操作的是真管线对象）。 */
    fun disconnect(context: Context) {
        // 先淘汰队列和等锁中的旧端点；迅速重连把 connectCancelled 复位也不能复活它们。
        connectCancelled = true
        wifiJoins.cancel()
        endpointCallbacks.invalidate()
        stopVpnCoexistence()
        val hadProjection = CarPlayService.mediaProjection != null
        pendingEndpointUpdate = null
        retireSession()
        // BT 控制会话只在这里（用户终态）退役，socket close 让保活读循环立刻退出：
        // 否则它阻塞到对端超时（实测 ~24s）才结束，用户取消后英雄卡持续转圈。
        // 配对中绝不能断——DiPlay 在隧道 iAP2 就绪前看到 RFCOMM 断开即判
        // bring-up failed 并拆整轮无线栈（含推流中的 AirPlay 会话，2026-10-16 实测）。
        detachActiveBtSessions()
        phaseHint = null
        CarPlayService
            .stop(context)
        bluetoothServer?.stop()
        bluetoothClient?.stop()
        ctrlAdvertiser?.stop()
        endpointBrowser?.stop()
        wifiConnector?.release()
        boundCarSsid = null
        if (hadProjection) {
            // Android 14+：MediaProjection 与保活服务（mediaProjection FGS 类型）绑定，
            // 停服务会连带失效授权——下次连接需重新点「申请投屏权限」
            appendLog("已手动断开并停止保活服务（常亮已恢复）；投屏授权已随服务停止失效，下次连接如需声音请重新申请")
        } else {
            appendLog("已手动断开并停止保活服务（常亮已恢复）")
        }
    }

    /** 仅释放本应用临时持有的网络请求；取消连接及保活服务销毁时均可重复调用。 */
    fun stopVpnCoexistence() {
        vpnCoexistenceController?.stop()
    }

    /** 会话结束/新周期开始前彻底退役上一代会话（旧管线泄漏会累积编码器/虚拟显示器，最终 dequeueBuffer -19）。 */
    fun retireSession() {
        val owner = sessionAttempts.cancel()
        val cleanup = {
            synchronized(wifiBindLock) {
                if (sessionAttempts.owns(owner)) retireSessionResources()
            }
        }
        // UI 取消须先即时使旧任务失效；清理不可等待入网锁或虚拟屏释放。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Bg.run("retire-session") { cleanup() }
        } else {
            cleanup()
        }
    }

    /** 调用方持有 wifiBindLock；不得在旧任务收尾时清理另一代已发布的资源。 */
    private fun retireSessionResources() {
        reconnectBorrowedScreen = null
        // 先使后台音频启动失效，再清理发送器，避免旧任务在清理后发布新发送器。
        currentClient?.cancelled = true
        CarShortcutLearning.onProjectionEnded()
        videoSender?.stop()
        videoSender = null
        // 暗幕/常亮随会话收掉（本方法在每轮配对起步也会执行，新一轮推流起来后会重新置位）
        ScreenDimmer.setSessionActive(false)
        audioSender?.stop()
        audioSender = null
        currentSharedSecret = null
        currentAudioHost = null
        currentHiddenSource?.release()
        currentHiddenSource = null
        TouchRouter
            .reset()
        currentClient?.cancelled = true
        currentClient?.close()
        currentClient = null
        // 注意：这里绝不能 detach BT 控制会话。本方法在每轮配对起步时也会执行，
        // 而 DiPlay 在 WiFi 隧道就绪前看到 RFCOMM 断开即判 bring-up failed、
        // closeWirelessStack 拆掉整轮无线栈（Bonjour/热点/AirPlay 服务），把已建立
        // 甚至正在推流的 AirPlay 会话一起拆掉——「连上约 3 秒即断」循环的根因
        // （2026-10-16 实测）。BT 退役只在用户终态做，见 disconnect()。
        // 解除会话静默：出站探测恢复（下次会话建立时会再次置位）
        BtSessionGate.sessionActive = false
        // 「强制应用横屏」覆盖随会话结束统一复原（应用回到手机侧正常竖屏形态）。
        // 异步：每应用两次 am compat shell 往返，6 个应用实测 ~1.6s；此前同步跑在
        // 配对前，等于每次连接白等 1.6s（2026-10-18 实测）。执行器串行保证
        // 「先复原上一会话、后写入本会话」的顺序。
        appContext?.let { ctx -> AppOrientationCompat.resetAllAsync(ctx, ::appendLog) }
    }

    /** 会话已建立且投影就绪、但音频尚未启动时，动态补启（投影授权晚于连接的场景）。 */
    @Synchronized
    fun maybeStartAudioNow(context: Context) {
        val client =
            currentClient ?: run {
                appendLog("MODE: 投屏已就绪（当前无会话——下次连接时自动启用音频）")
                return
            }
        val secret = currentSharedSecret
        if (secret == null) {
            appendLog("MODE: 明文会话（未配对）无共享密钥——不补启音频")
            return
        }
        if (client.cancelled || connectCancelled || currentClient !== client || videoSender?.isRunning != true) return
        if (audioSender?.isRunning == true) return
        appendLog("MODE: 会话进行中，动态补启音频通道…")
        // 与初次连接共用 SETUP → RECORD → 捕获/同步，避免动态补启遗漏 RECORD。
        startAudioChannel(context, client, secret, currentAudioHost)
    }

    /**
     * 0x5703 到达（拿到车机热点凭据）立即执行——真车 0x4301 的前置条件：
     * 车机在 WiFi 网络上发现手机的 `_carplay-ctrl._tcp`（TXT id=真实蓝牙 MAC）后
     * 主动发 `GET /ctrl-int/1/connect` 邀请，邀请被接受才发 0x4301。
     * 因此这里必须：绑车机 WiFi → 广播 ctrl；晚于 0x4300 则双方互等死锁（Audi 实测）。
     */
    fun onWifiCredentials(wifi: Iap2SenderSession.WifiConfiguration) {
        val epoch = wifiJoins.currentEpoch() ?: return
        if (connectCancelled) return
        // 会话推流中收到的 0x5703 是冗余凭据（DiPlay 控制器周期重连会重发一轮
        // Phase 1，2026-10-15 实测「连上后自动断开」循环的推手之一）：此时再走绑定
        // 流程会 release+重绑进程网络（扰动正在推流的 socket）、force 重注册 ctrl
        // mDNS（DiPlay 视为手机重新上线、再拉一轮 RFCOMM）。只刷新密码缓存。
        if (videoSender?.isRunning == true) {
            lastWifiPsk = wifi.passphrase
            appendLog("0x5703 冗余凭据（会话推流中）——保持现有 WiFi 绑定与 ctrl 广播不变")
            return
        }
        ssidSink?.invoke(wifi.ssid)
        lastWifiPsk = wifi.passphrase
        phaseHint = "拿到车机热点凭据，绑定 WiFi…"
        appendLog(">>> 拿到车机热点凭据（0x5703）——立即绑定 WiFi + 广播 _carplay-ctrl")
        // ctrl 广播已在 startConnection 时发布（catplay：连接前保持发布能力在线）。
        // 这里登记 SSID 并让注册对齐一次（手机若已手动连在车机热点上、Shizuku 不可用
        // 时这是唯一一次重注册机会）；加入热点后 bindCarWifiAndRegister 还会 force
        // 重注册，把组播落到车机所在网络（真车实测必需）。
        ctrlAdvertiser?.ensureForNetwork(wifi.ssid)
        appendLog("CTRL: 广播已在启动连接时发布，此处对齐到热点 ${wifi.ssid}")
        // 凭据与端点入口共用整段入网流程；复用系统 WiFi 前精确核对 SSID。
        Bg.run("wifi-auto-join") { ensureCarWifi(wifi, epoch) }
    }

    /** 两个协议入口共享完整入网流程，包含重试弹窗及手动等待。 */
    private fun ensureCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        epoch: WifiJoinOwnership.Epoch,
    ): Boolean {
        val claim = wifiJoins.claim(epoch, wifi.ssid) ?: return false
        val flight = claim.flight
        if (!claim.leader) {
            try {
                while (!connectCancelled && wifiJoins.isCurrent(epoch)) {
                    if (flight.await(200)) return flight.succeeded && wifiJoins.isCurrent(epoch)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return false
        }
        var success = false
        try {
            if (!isWifiJoinCurrent(flight)) return false
            val shellReady = appContext?.let { ShizukuGate.state(it) == ShizukuGate.State.READY } == true
            if (shellReady && shellWifiDisabled() && !waitForWifiEnabled(flight)) return false
            if (!isWifiJoinCurrent(flight)) return false
            when (joinCarWifiSpecifier(wifi, shellReady, flight)) {
                null -> return false
                is WifiCarPlayConnector.JoinResult.Joined -> Unit
                WifiCarPlayConnector.JoinResult.Unsupported -> legacyJoinCarWifi(wifi, shellReady, flight)
                WifiCarPlayConnector.JoinResult.Unavailable -> promptSpecifierFailed(wifi, shellReady, flight)
            }
            success = isWifiJoinCurrent(flight) && boundCarSsid == wifi.ssid &&
                wifiConnector?.validatedCurrentNetwork() != null
            return success
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (error: Exception) {
            if (isWifiJoinCurrent(flight)) appendLog("WIFI: 入网流程结束（${error.javaClass.simpleName}）——请重试连接")
            return false
        } finally {
            wifiJoins.complete(flight, success)
        }
    }

    private fun isWifiJoinCurrent(flight: WifiJoinOwnership.Flight): Boolean =
        !connectCancelled && wifiJoins.isCurrent(flight)

    private fun waitWifiJoin(flight: WifiJoinOwnership.Flight, millis: Long): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + millis
        while (isWifiJoinCurrent(flight)) {
            val remaining = deadline - android.os.SystemClock.uptimeMillis()
            if (remaining <= 0) return true
            Thread.sleep(minOf(remaining, 200L))
        }
        return false
    }

    /** 端点跟随凭据时复用本会话的绑定，不弹窗、不重新广播。 */
    private fun currentCarWifi(ssid: String, flight: WifiJoinOwnership.Flight): WifiCarPlayConnector.JoinResult.Joined? =
        synchronized(wifiBindLock) {
            if (!isWifiJoinCurrent(flight) || boundCarSsid != ssid) return@synchronized null
            wifiConnector?.validatedCurrentNetwork()?.let { WifiCarPlayConnector.JoinResult.Joined(it) }
        }

    /** Prefer an existing local projection network. A system WLAN needs a separate uplink or an explicit choice. */
    private fun reuseConnectedCarWifi(
        ssid: String,
        shellReady: Boolean,
        flight: WifiJoinOwnership.Flight,
        beforeDedicatedRequest: Boolean = false,
        invitedAddress: String? = null,
    ): WifiCarPlayConnector.JoinResult.Joined? {
        if (!isWifiJoinCurrent(flight)) return null
        val connector = wifiConnector ?: return null
        currentCarWifi(ssid, flight)?.let { return it }
        val systemSsid = if (shellReady) shellWifiStatus().second else null
        if (!isWifiJoinCurrent(flight)) return null
        val local = connector.connectedNetwork(ssid, systemSsid, allowSystemWifi = false)
        val candidate = local ?: connector.connectedNetwork(ssid, systemSsid, allowSystemWifi = true) ?: return null
        if (invitedAddress != null && !connector.hasLocalAddress(candidate, invitedAddress)) return null
        if (local == null && !wifiJoins.mayReuseSystemWifi(flight) { connector.hasIndependentInternet(candidate, beforeDedicatedRequest) }) return null
        if (!bindCarWifiAndRegister(ssid, flight, candidate, allowSystemWifi = local == null)) return null
        return connector.currentNetwork?.let { WifiCarPlayConnector.JoinResult.Joined(it) }
    }

    private fun joinCarWifiSpecifier(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
        flight: WifiJoinOwnership.Flight,
    ): WifiCarPlayConnector.JoinResult? {
        val connector = wifiConnector ?: return WifiCarPlayConnector.JoinResult.Unavailable
        reuseConnectedCarWifi(wifi.ssid, shellReady, flight, beforeDedicatedRequest = true)?.let { return it }
        if (!isWifiJoinCurrent(flight)) return null
        val hasFallback = alreadyAssociated(wifi.ssid, shellReady)
        val receiver = selectedCtrlReceiver()
        phaseHint = "正在加入车机热点 ${wifi.ssid}…（用于车机投屏）"
        appendLog(">>> 以应用专属网络加入 \"${wifi.ssid}\"（投屏绑定本进程，其它应用联网由系统及 VPN 决定）")
        val result = synchronized(wifiBindLock) {
            if (!isWifiJoinCurrent(flight)) return@synchronized WifiCarPlayConnector.JoinResult.Unavailable
            val joined = connector.joinCarPlayNetwork(
                ssid = wifi.ssid,
                passphrase = wifi.passphrase,
                securityType = wifi.securityType,
                requireLocalOnly = wifiJoins.routeChoice(flight) != WifiJoinOwnership.RouteChoice.SYSTEM_WIFI,
                timeoutMillis = if (hasFallback) SPECIFIER_WITH_EXISTING_WLAN_MS else SPECIFIER_JOIN_TIMEOUT_MS,
                isCancelled = { connectCancelled || !wifiJoins.isCurrent(flight.epoch) },
                reuseInvitedWifi = invited@{
                    if (receiver == null || receiver != selectedCtrlReceiver()) return@invited null
                    val address = ctrlAdvertiser?.invitations?.localAddress(android.os.SystemClock.elapsedRealtime(), receiver)
                        ?: return@invited null
                    reuseConnectedCarWifi(wifi.ssid, shellReady, flight, invitedAddress = address)?.also {
                        appendLog("CTRL: 已核对本车邀请所在的 Wi-Fi，提前完成热点交接")
                    }
                },
            )
            if (joined is WifiCarPlayConnector.JoinResult.Joined && isWifiJoinCurrent(flight) &&
                connector.currentNetwork == joined.network
            ) {
                boundCarSsid = wifi.ssid
                phaseHint = "已加入车机热点"
                ctrlAdvertiser?.ensureForNetwork(wifi.ssid, force = true, network = joined.network)
                appendLog(">>> WiFi 已加入车机热点，ctrl 广播已重新注册，等待车机 ctrl 邀请")
                joined
            } else if (joined is WifiCarPlayConnector.JoinResult.Unsupported) {
                joined
            } else WifiCarPlayConnector.JoinResult.Unavailable
        }
        if (result is WifiCarPlayConnector.JoinResult.Unavailable && isWifiJoinCurrent(flight)) {
            // onUnavailable 可能与系统自动连接交错；不要马上弹重试框或重建请求。
            reuseConnectedCarWifi(wifi.ssid, shellReady, flight)?.let { return it }
            if (isWifiJoinCurrent(flight)) appendLog("WIFI: 应用专属网络加入未成功（Unavailable）")
        }
        return result
    }

    private fun alreadyAssociated(ssid: String, shellReady: Boolean): Boolean {
        val systemSsid = if (shellReady) shellWifiStatus().second else null
        return wifiConnector?.connectedNetwork(ssid, systemSsid, allowSystemWifi = true) != null
    }

    /**
     * 系统里已保存同名热点时，询问用户是否清除（**不擅自删除**）。
     *
     * 为什么要问而不是直接删：保留也有正当用途——用户可能就想让系统自动连车机、
     * 或车机热点确实带互联网（部分车型提供流量）。清除当前系统热点可能导致它先断开，
     * 随后再申请仅供投屏的网络。保留系统配置也先尝试本地申请。
     *
     * 阻塞直到用户选择（[JOIN_DIALOG_TIMEOUT_S] 超时按「保留」处理——不做破坏性动作）。
     */
    private fun promptForgetSavedNetworkIfAny(ssid: String, flight: WifiJoinOwnership.Flight) {
        if (!isWifiJoinCurrent(flight) || savedPromptEpoch[ssid] === flight.epoch) return
        val ids = savedNetworkIds(ssid)
        if (!isWifiJoinCurrent(flight) || ids.isEmpty() || keptSavedNetworks[ssid] == ids) return
        savedPromptEpoch[ssid] = flight.epoch
        appendLog(
            "WIFI: 系统里已保存热点 \"$ssid\"（netId=${ids.joinToString(",")}）" +
                "——询问用户是否清除（保留也能正常连接）",
        )
        val forget =
            askWifiJoinChoice(
                DialogSpec(
                    title = "已保存该车机热点",
                    message =
                        "系统里保存过 \"$ssid\"。保留会让手机在进入范围时自动连接它，" +
                            "若该热点没有互联网，可能导致手机无法使用移动数据。\n\n" +
                            "选「清除保存」会移除这个 WLAN 配置，手机可能先断开车机 Wi-Fi，" +
                            "再由 CarDeck 申请投屏连接。选择保留也会重试专用连接。",
                    positive = "清除保存",
                    negative = "保留并重试专用连接",
                ),
                flight,
            )
        if (!isWifiJoinCurrent(flight)) return
        when (forget) {
            true -> {
                if (!wifiJoins.rememberRoute(flight, WifiJoinOwnership.RouteChoice.DEDICATED)) return
                ids.forEach { id ->
                    if (!isWifiJoinCurrent(flight)) return
                    // 用户思考时系统可能更新保存配置。仅删除仍对应同名热点的原 ID。
                    if (id !in savedNetworkIds(ssid)) return@forEach
                    if (!isWifiJoinCurrent(flight)) return
                    val result = Shell.exec("cmd wifi forget-network $id", 5_000L)
                    if (!isWifiJoinCurrent(flight)) return
                    appendLog(
                        "WIFI: 已按用户选择清除系统级保存的热点 \"$ssid\"（netId=$id ok=${result.ok}）",
                    )
                }
            }

            false -> {
                keptSavedNetworks[ssid] = ids
                appendLog("WIFI: 用户保留系统保存的 \"$ssid\"，继续重试专用连接")
            }

            // 无人应答（UI 不在前台）：按「保留」处理，绝不在用户未确认时删网络
            null -> appendLog("WIFI: 无人应答是否清除保存的网络——按保留处理（不改用户配置）")
        }
    }

    /** 查系统已保存的、SSID 与目标完全一致的网络 id（`cmd wifi list-networks`）。 */
    private fun savedNetworkIds(ssid: String): List<Int> {
        val listing = Shell.exec("cmd wifi list-networks", 5_000L)
        if (!listing.ok) {
            appendLog("WIFI: 无法读取系统已保存网络（exit=${listing.exitCode}）——跳过检查")
            return emptyList()
        }
        return WifiSavedNetworks.idsOf(listing.output, ssid)
    }

    /** WiFi 未开启时的引导与等待；true = 已开启可继续。 */
    private fun waitForWifiEnabled(flight: WifiJoinOwnership.Flight): Boolean {
        val open =
            askWifiJoinChoice(
                DialogSpec(
                    title = "WiFi 未开启",
                    message = "连接车机热点需要打开手机 WLAN。是否打开 WLAN 设置？",
                    positive = "打开 WLAN 设置",
                    negative = "取消",
                ),
                flight,
            )
        if (!isWifiJoinCurrent(flight)) return false
        when (open) {
            true -> {
                openWlanSettings(flight)
                uiHintSink?.invoke("请在 WLAN 设置中打开 WiFi 开关")
            }

            false -> {
                phaseHint = "已取消——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 用户取消——自动加入中止（WiFi 未开启）")
                return false
            }

            null -> {
                phaseHint = "WiFi 未开启——连接车机热点需要开启 WLAN"
                appendLog("WIFI: 弹窗无人应答——自动加入中止（WiFi 未开启）")
                return false
            }
        }
        var waited = 0
        while (shellWifiDisabled() && waited < WLAN_WAIT_TIMEOUT_MS && isWifiJoinCurrent(flight)) {
            if (waited == 0) {
                phaseHint = "等待打开 WLAN（最长 120 秒，取消连接可中止）"
                appendLog("WIFI: 等待用户打开 WLAN（最长 120 秒，取消连接可中止）")
            }
            if (!waitWifiJoin(flight, 2_000)) return false
            waited += 2_000
        }
        if (!isWifiJoinCurrent(flight)) return false
        val stillDisabled = shellWifiDisabled()
        if (!isWifiJoinCurrent(flight)) return false
        if (stillDisabled) {
            phaseHint = "WiFi 未开启——无法连接车机热点"
            appendLog("WIFI: 等待超时 WiFi 仍未开启——自动加入中止，请打开 WLAN 后重新连接")
            return false
        }
        return true
    }

    /** 应用专属网络被系统拒绝/超时：让用户重试或转手动连接。 */
    private fun promptSpecifierFailed(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
        flight: WifiJoinOwnership.Flight,
    ) {
        fun choose(spec: DialogSpec): Boolean? {
            if (!isWifiJoinCurrent(flight) || reuseConnectedCarWifi(wifi.ssid, shellReady, flight) != null) return null
            val answer = askWifiJoinChoice(
                spec,
                flight,
                whileWaiting = { reuseConnectedCarWifi(wifi.ssid, shellReady, flight) != null },
            )
            if (!isWifiJoinCurrent(flight)) return null
            if (answer == null && reuseConnectedCarWifi(wifi.ssid, shellReady, flight) == null) {
                phaseHint = "本次热点连接已结束，可重新连接"
            }
            return answer
        }
        WifiJoinRecovery.run(
            chooseRetryOrManual = {
                val associated = isWifiJoinCurrent(flight) && alreadyAssociated(wifi.ssid, shellReady)
                if (associated) choose(DialogSpec(
                    title = "使用已连接的车机热点",
                    message = "手机已连上宝马热点。使用 Spotify 等需要 VPN 的应用，可重试专用连接，让手机保留移动数据上网。选择使用当前热点时，VPN 可能随手机切到车机 Wi-Fi。",
                    positive = "使用当前热点",
                    negative = "重试专用连接",
                ))?.not() else choose(DialogSpec(
                    title = "未能加入车机热点",
                    message = "尚未取得 \"${wifi.ssid}\" 的投屏专用连接。「重试」会再次申请，" +
                        "「手动连接」可复用当前热点或打开 WLAN 设置加入。使用系统热点可能影响手机和 VPN 上网。",
                    positive = "重试",
                    negative = "手动连接",
                ))
            },
            finishRetry = retry@{
                if (!wifiJoins.rememberRoute(flight, WifiJoinOwnership.RouteChoice.DEDICATED)) return@retry true
                if (shellReady) promptForgetSavedNetworkIfAny(wifi.ssid, flight)
                when (joinCarWifiSpecifier(wifi, shellReady, flight)) {
                    null, is WifiCarPlayConnector.JoinResult.Joined -> true
                    WifiCarPlayConnector.JoinResult.Unsupported -> {
                        legacyJoinCarWifi(wifi, shellReady, flight)
                        true
                    }
                    WifiCarPlayConnector.JoinResult.Unavailable -> false
                }
            },
            confirmManual = {
                val manual = choose(DialogSpec(
                    title = "专用连接重试未成功",
                    message = "可直接使用当前车机热点或前往 WLAN 设置加入。连接后投屏会继续，使用系统热点可能影响手机和 VPN 上网。",
                    positive = "手动连接",
                    negative = "结束本次尝试",
                ))
                if (manual == false && isWifiJoinCurrent(flight)) phaseHint = "本次热点连接已结束，可重新连接"
                manual == true
            },
            joinManually = { manualJoinCarWifi(wifi, flight) },
        )
    }

    /** 手动兜底：密码进剪贴板 + 跳 WLAN 设置，轮询用户是否连上。 */
    private fun manualJoinCarWifi(wifi: Iap2SenderSession.WifiConfiguration, flight: WifiJoinOwnership.Flight) {
        if (!isWifiJoinCurrent(flight) || !wifiJoins.rememberRoute(flight, WifiJoinOwnership.RouteChoice.SYSTEM_WIFI)) return
        if (reuseConnectedCarWifi(wifi.ssid, true, flight) != null) return
        if (!isWifiJoinCurrent(flight)) return
        copyHotspotPsk(wifi.passphrase, flight)
        openWlanSettings(flight)
        phaseHint = "请在 WLAN 设置中连接 ${wifi.ssid}…"
        appendLog(
            "WIFI: 用户选择手动连接——等待 WLAN 连上 \"${wifi.ssid}\"（最长 120 秒）；" +
                "注意：手动连接会把该热点保存为系统网络，若它没有互联网可能影响移动数据",
        )
        val manualDeadline = android.os.SystemClock.uptimeMillis() + WLAN_WAIT_TIMEOUT_MS
        while (android.os.SystemClock.uptimeMillis() < manualDeadline) {
            if (!isWifiJoinCurrent(flight)) return
            if (!waitWifiJoin(flight, 1_000)) return
            if (reuseConnectedCarWifi(wifi.ssid, true, flight) != null) return
        }
        if (!isWifiJoinCurrent(flight)) return
        phaseHint = "尚未连接 ${wifi.ssid}——自动加入已中止"
        appendLog("WIFI: 手动连接等待超时——自动加入中止")
    }

    /**
     * 旧路径（WEP 等 specifier 无法表达的安全类型）：`cmd wifi connect-network`
     * 系统级保存并切换。注意这会保存热点为系统网络——无互联网时可能影响移动数据，
     * 只作为兼容兜底。
     */
    private fun legacyJoinCarWifi(
        wifi: Iap2SenderSession.WifiConfiguration,
        shellReady: Boolean,
        flight: WifiJoinOwnership.Flight,
    ) {
        if (!isWifiJoinCurrent(flight)) return
        if (!shellReady) {
            phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(
                ">>> 手机不在车机热点 \"${wifi.ssid}\" 且 Shizuku 未就绪（无法自动加入）——" +
                    "请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        if (reuseConnectedCarWifi(wifi.ssid, shellReady, flight) != null) return
        val choice =
            askWifiJoinChoice(
                DialogSpec(
                    title = "连接车机热点",
                    message =
                        "此热点无法使用投屏专用连接。继续将使用系统 WLAN 连接 \"${wifi.ssid}\"，" +
                            "如果热点不提供互联网，可能影响手机和 VPN 上网。",
                    positive = "使用系统热点",
                    negative = "打开 WLAN 设置",
                ),
                flight,
            )
        if (!isWifiJoinCurrent(flight)) return
        if (choice == false) {
            manualJoinCarWifi(wifi, flight)
            return
        }
        if (choice == null) {
            phaseHint = "未能加入车机热点——请重新连接"
            appendLog("WIFI: 弹窗无人应答——自动加入中止")
            return
        }
        if (!wifiJoins.rememberRoute(flight, WifiJoinOwnership.RouteChoice.SYSTEM_WIFI)) return
        if (reuseConnectedCarWifi(wifi.ssid, shellReady, flight) != null) return
        phaseHint = "正在自动加入车机热点 ${wifi.ssid}…"
        appendLog(">>> 按用户选择，经 Shizuku 连接系统热点 \"${wifi.ssid}\"")
        if (!isWifiJoinCurrent(flight)) return
        val security =
            if (wifi.passphrase.isEmpty()) "open" else "wpa2 ${shellQuote(wifi.passphrase)}"
        val result =
            Shell
                .exec("cmd wifi connect-network ${shellQuote(wifi.ssid)} $security", 10_000L)
        if (!isWifiJoinCurrent(flight)) return
        if (!result.ok) {
            phaseHint = "自动加入热点失败——请到 WLAN 设置加入 ${wifi.ssid}"
            appendLog(
                "WIFI: connect-network 失败（${result.output.take(80).ifEmpty { "exit=${result.exitCode}" }}）" +
                    "——请到 系统设置>WLAN 手动加入（密码可点「去连接」复制）",
            )
            return
        }
        phaseHint = "正在加入车机热点 ${wifi.ssid}…若被切回原 WiFi，请在 WLAN 中手动连接"
        appendLog("WIFI: connect-network 已发起（自动保存网络，等待切换…）")
        // 系统切换是异步的（断开当前网/扫描/关联/DHCP，2-10 秒）：轮询 SSID 确认。
        repeat(20) {
            if (!isWifiJoinCurrent(flight)) {
                appendLog("WIFI: 自动加入已取消（用户断开）")
                return
            }
            val ssid = shellWifiStatus().second
            if (!isWifiJoinCurrent(flight)) return
            if (ssid == wifi.ssid) {
                appendLog("WIFI: 已切到车机热点 ${wifi.ssid}")
                bindCarWifiAndRegister(wifi.ssid, flight, allowSystemWifi = true)
                return
            }
            if (!waitWifiJoin(flight, 800)) return
        }
        if (!isWifiJoinCurrent(flight)) return
        phaseHint = "未能在热点驻留——请到 WLAN 设置手动连接 ${wifi.ssid}"
        uiHintSink?.invoke("未能在热点驻留——请到 WLAN 设置中主动连接 ${wifi.ssid}")
        appendLog(
            ">>> WiFi 自动加入超时（可能被系统切回原 WiFi）——" +
                "请到 系统设置>WLAN 手动连接 \"${wifi.ssid}\"（手动连接不会被自动切回）",
        )
    }

    /** 仅绑定已核对 SSID 的具体网络；回调到其它 Wi-Fi 一律不接收。 */
    private fun bindCarWifiAndRegister(
        ssid: String,
        flight: WifiJoinOwnership.Flight,
        selectedNetwork: android.net.Network? = null,
        allowSystemWifi: Boolean,
    ): Boolean = synchronized(wifiBindLock) {
        if (!isWifiJoinCurrent(flight)) return@synchronized false
        val connector = wifiConnector ?: return@synchronized false
        if (boundCarSsid == ssid && connector.validatedCurrentNetwork() != null) return@synchronized true
        val expected = selectedNetwork ?: connector.connectedNetwork(ssid, shellWifiStatus().second, allowSystemWifi)
            ?: return@synchronized false
        if (!isWifiJoinCurrent(flight)) return@synchronized false
        val latch = java.util.concurrent.CountDownLatch(1)
        val outcome = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        var bound = false
        try {
            connector.bindCurrentWifi(
                expectedNetwork = expected,
                allowSystemWifi = allowSystemWifi,
                isCancelled = { connectCancelled || !wifiJoins.isCurrent(flight.epoch) },
            ) { ok ->
                outcome.compareAndSet(null, ok)
                latch.countDown()
            }
            val deadline = android.os.SystemClock.uptimeMillis() + WIFI_BIND_WAIT_S * 1_000
            while (isWifiJoinCurrent(flight) && android.os.SystemClock.uptimeMillis() < deadline) {
                if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) break
            }
            if (!isWifiJoinCurrent(flight) || outcome.get() != true) return@synchronized false
            // 等待过程中系统可能切网，仍须重新核对目标和同一 Network。
            val actual = connector.connectedNetwork(ssid, shellWifiStatus().second, allowSystemWifi)
            bound = isWifiJoinCurrent(flight) && actual == expected && connector.validatedCurrentNetwork() == expected &&
                (!allowSystemWifi || wifiJoins.mayReuseSystemWifi(flight) { connector.hasIndependentInternet(expected) })
            if (bound) {
                boundCarSsid = ssid
                phaseHint = "已加入车机热点"
                ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = expected)
                appendLog(if (allowSystemWifi) ">>> 已复用车机 WLAN（允许系统热点），等待车机 ctrl 邀请"
                    else ">>> 已复用仅供本地连接的车机热点，等待车机 ctrl 邀请")
            }
            bound
        } finally {
            // 持有绑定锁，新流程不能在本轮等待与清理之间发布另一个请求。
            if (!bound) connector.release()
        }
    }

    /**
     * 弹窗询问用户（阻塞调用线程直到选择/关闭/90 秒超时）：
     * true=positive；false=negative；null=无人应答（调用方决定兜底）。
     */
    private fun askWifiJoinChoice(
        spec: DialogSpec,
        flight: WifiJoinOwnership.Flight,
        whileWaiting: () -> Boolean = { false },
    ): Boolean? {
        if (!isWifiJoinCurrent(flight)) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        val waiting = AtomicBoolean(true)
        var choice: Boolean? = null
        val current = { waiting.get() && isWifiJoinCurrent(flight) }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!current()) return@post
            val sink = dialogSink
            if (sink == null) {
                latch.countDown()
                return@post
            }
            sink(spec.copy(isCurrent = current)) { result ->
                if (current()) {
                    choice = result
                    latch.countDown()
                }
            }
        }
        try {
            val deadline = android.os.SystemClock.uptimeMillis() + JOIN_DIALOG_TIMEOUT_S * 1_000
            var nextCheck = android.os.SystemClock.uptimeMillis() + 1_000
            while (current() && android.os.SystemClock.uptimeMillis() < deadline) {
                if (latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) return choice
                if (android.os.SystemClock.uptimeMillis() >= nextCheck) {
                    if (!current() || whileWaiting()) return null
                    nextCheck = android.os.SystemClock.uptimeMillis() + 1_000
                }
            }
            return null
        } finally {
            waiting.set(false)
        }
    }

    private fun openWlanSettings(flight: WifiJoinOwnership.Flight) {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!isWifiJoinCurrent(flight)) return@post
                runCatching {
                    ctx.startActivity(
                        android.content
                            .Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    /** 热点密码复制到剪贴板（手动到 WLAN 页连接时粘贴），并提示用户。 */
    private fun copyHotspotPsk(passphrase: String, flight: WifiJoinOwnership.Flight) {
        appContext?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!isWifiJoinCurrent(flight)) return@post
                runCatching {
                    val cm =
                        ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("carplay-wifi", passphrase),
                    )
                    uiHintSink?.invoke("热点密码已复制（${passphrase.length} 位）——粘贴到密码框即可")
                }
            }
        }
    }

    /**
     * WiFi 开关与当前连接的 SSID（一次 `cmd wifi status` 查询）。
     * 输出形如：`Wifi is enabled` + `Wifi is connected to "Redmi K40"`（未连接无此行）。
     */
    private fun shellWifiStatus(): Pair<Boolean, String?> {
        val result =
            Shell
                .exec("cmd wifi status", 5_000L)
        if (!result.ok) return true to null
        val enabled = !result.output.contains("Wifi is disabled")
        val ssid = Regex("connected to \"(.*)\"").find(result.output)?.groupValues?.get(1)
        return enabled to ssid
    }

    private fun shellWifiDisabled(): Boolean = !shellWifiStatus().first

    /** 单引号包裹的 POSIX shell 字面量（SSID/密码可能含空格与特殊字符）。 */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 复现「BT 控制已结束」状态：断开当前任一路径的 iAP2 控制会话（DiPlay 只在此状态应答 RTSP）。 */
    private fun detachActiveBtSessions() {
        bluetoothServer?.detachCurrentSession()
        bluetoothClient?.detachCurrentSession()
    }

    private fun selectedCtrlReceiver(): Long? =
        appContext?.let { TargetDevicePrefs.get(it)?.address }?.replace(":", "")?.toLongOrNull(16)

    private class BmwRecoveryInvite(val network: android.net.Network, val take: () -> Boolean)

    /** Called under wifiBindLock for an established MGU stream. The old TCP socket may still
     * look locally open after a reset. Release the media resources while retaining the WLAN
     * and the Bluetooth listener. Only a NEW invitation on that WLAN can start this handoff. */
    private fun endBmwStream(
        attempt: SessionAttemptOwnership.Attempt<android.net.Network>,
        epoch: WifiJoinOwnership.Epoch,
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        receiver: Long?,
    ) {
        val connector = wifiConnector
        val network = connector?.currentNetwork?.takeIf { sessionAttempts.matches(attempt, it) }
        val advertiser = ctrlAdvertiser
        val invitations = advertiser?.invitations
        val countAtEnd = invitations?.count ?: 0
        sessionAttempts.cancel()
        retireSessionResources()
        phaseHint = "投屏已结束，等待宝马重新连接"
        if (connector == null || network == null || advertiser == null || invitations == null || receiver == null) return
        Bg.run("bmw-stream-reconnect") {
            fun connectionCurrent() = !connectCancelled && wifiJoins.isCurrent(epoch) &&
                wifiConnector === connector && connector.currentNetwork == network && boundCarSsid == endpoint.ssid &&
                ctrlAdvertiser === advertiser && advertiser.invitations === invitations && selectedCtrlReceiver() == receiver
            fun stillWaiting() = connectionCurrent() && sessionAttempts.owns(attempt)
            synchronized(wifiBindLock) {
                if (!stillWaiting()) return@run
                advertiser.ensureForNetwork(endpoint.ssid, force = true, network = network)
                appendLog("CTRL: 已清理中断会话，等待本车在当前 Wi-Fi 发出新的连接邀请")
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + BMW_RECOVERY_INVITE_WAIT_MS
            while (android.os.SystemClock.elapsedRealtime() < deadline && stillWaiting()) {
                val address = invitations.localAddress(android.os.SystemClock.elapsedRealtime(), receiver, countAtEnd)
                if (address != null && connector.hasLocalAddress(network, address)) {
                    synchronized(wifiBindLock) {
                        if (!stillWaiting()) return@run
                        if (!sessionStarting.get() && connector.validatedCurrentNetwork() == network) {
                            // The worker consumes an unexpired invitation at pairing time. A receipt
                            // observed on this handle cannot authorize a new association with the same SSID.
                            val invite = BmwRecoveryInvite(network) {
                                connector.hasLocalAddress(network, address) &&
                                    invitations.take(android.os.SystemClock.elapsedRealtime(), setOf(address), receiver, countAtEnd)
                            }
                            endpointBrowser?.stop()
                            val fresh = endpoint.copy(ipAddresses = emptyList(), airPlayPort = 0, preferCtrlInvitation = true)
                            onCarPlayEndpoint(fresh, "宝马重新邀请", ::connectionCurrent, invite)
                            return@run
                        }
                    }
                }
                Thread.sleep(200)
            }
        }
    }

    /**
     * 等车机 ctrl 邀请（`GET /ctrl-int/1/connect`）——catplay 的连接触发点。
     *
     * 邀请意味着「车机已在 WiFi 上发现我们，且已把 AirPlay 端口打开」，此时连
     * AirPlay 才不会被 Connection refused。旧实现用固定 sleep 1.6s 赌邀请已到：
     * 车机快时白等，慢时又不够。这里事件驱动，并且若 mDNS 已发现 `_airplay._tcp`
     * （端口确定可连）也立即放行，避免旧机型不发邀请时白等满窗口。
     *
     * @return true = 收到邀请 / 已发现端点；false = 等待窗口耗尽
     */
    private fun awaitCtrlInvite(
        ssid: String,
        preferCtrlInvitation: Boolean,
        recoveringBmw: Boolean,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        val advertiser = ctrlAdvertiser
        val invitations = advertiser?.invitations
        // Accept an invitation that arrived just before the Bluetooth credentials, on this car Wi-Fi.
        val carAddresses = if (preferCtrlInvitation) {
            (listOfNotNull(wifiConnector?.localIpv4Address()) + wifiConnector?.localIpv6Addresses().orEmpty())
                .map { it.substringBefore('%') }.toSet()
        } else emptySet()
        val selectedReceiver = if (preferCtrlInvitation) selectedCtrlReceiver() else null
        val timeoutMillis = if (recoveringBmw) BMW_RECOVERY_INVITE_WAIT_MS else CTRL_INVITE_WAIT_MS
        if (recoveringBmw) {
            phaseHint = "车机 Wi-Fi 已恢复，等待宝马重新邀请…"
            appendLog("CTRL: MGU 热点请求恢复，最多等 ${timeoutMillis / 1000} 秒接收本轮邀请")
        }
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        val startedAt = android.os.SystemClock.uptimeMillis()
        var lastLogAt = startedAt
        var reannounced = false
        var reportedDiscovery = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (connectCancelled || !isCurrent()) return false
            val late = pendingEndpointUpdate
            if (recoveringBmw && late?.ssid == ssid && late.airPlayPort > 0 && EndpointHosts.hasUsable(late.ipAddresses)) {
                appendLog("CTRL: 宝马已通过 0x4301 提供完整端点，结束恢复等待")
                return true
            }
            val invites = advertiser?.inviteCount ?: 0
            val invited = if (preferCtrlInvitation) {
                invitations?.take(android.os.SystemClock.elapsedRealtime(), carAddresses, selectedReceiver) == true
            } else invites > 0
            if (invited) {
                appendLog(
                    "CTRL: ✓ 收到车机邀请（${android.os.SystemClock.uptimeMillis() - startedAt}ms，第 $invites 次）" +
                        "——立即连接 AirPlay",
                )
                return true
            }
            val discovered = endpointBrowser?.awaitEndpoint(0) != null
            if (discovered && !preferCtrlInvitation) {
                appendLog(
                    "CTRL: 已发现 _airplay._tcp（未收到邀请，旧机型行为）——直接连接 AirPlay",
                )
                return true
            }
            if (discovered && !reportedDiscovery) {
                reportedDiscovery = true
                appendLog("CTRL: 已发现 BMW 端点，等待本轮车机连接邀请后再开始配对")
            }
            val now = android.os.SystemClock.uptimeMillis()
            // catplay 实测：iPhone 的 Bonjour 公告常被车机丢弃——它在每次邀请前都
            // force_announce。这里在等待中途补发一次，覆盖「绑网瞬间公告丢失」的情况。
            val elapsed = now - startedAt
            if (!reannounced && (!discovered && elapsed >= 2_000 || recoveringBmw && elapsed >= 6_000)) {
                synchronized(wifiBindLock) {
                    if (connectCancelled || !isCurrent()) return false
                    reannounced = true
                    appendLog("CTRL: ${elapsed}ms 未收到邀请——重新公告一次")
                    ctrlAdvertiser?.ensureForNetwork(ssid, force = true, network = wifiConnector?.currentNetwork)
                }
            }
            if (now - lastLogAt > 1_200) {
                appendLog("CTRL: 等待车机 ctrl 邀请…（已等 ${now - startedAt}ms）")
                lastLogAt = now
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                return false
            }
        }
        appendLog("CTRL: ${timeoutMillis}ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）")
        return false
    }

    // ---- 以下方法体自 MainActivity 原样迁入（会话逻辑不依赖 Activity）----

    /** 只记录请求的结构和仲裁数值；URL、应用字符串等内容不写入日志、更不执行。 */
    private fun summarizeModeCommand(command: Map<*, *>): String {
        val numericFields = setOf(
            "resourceID", "entity", "permanentEntity", "transferType", "transferPriority",
            "takeConstraint", "borrowConstraint", "unborrowConstraint", "appStateID", "speechMode", "state",
        )
        fun safeKey(key: Any?): String =
            key?.toString()?.filter { it.isLetterOrDigit() || it == '_' }?.take(32).orEmpty()
        fun shape(value: Any?, depth: Int, field: String = ""): String =
            when (value) {
                null -> "null"
                is Boolean -> value.toString()
                is Number -> if (field in numericFields) value.toString() else "number"
                is String -> "str(${value.length})"
                is ByteArray -> "data(${value.size})"
                is Map<*, *> -> if (depth >= 3) "dict(${value.size})" else
                    value.entries.take(10).joinToString(prefix = "{", postfix = "}") {
                        val key = safeKey(it.key)
                        "$key=${shape(it.value, depth + 1, key)}"
                    }
                is List<*> -> if (depth >= 3) "list(${value.size})" else
                    value.take(6).joinToString(prefix = "[", postfix = "]") { shape(it, depth + 1) }
                else -> "other"
            }
        val fields = command.keys.take(10).joinToString { safeKey(it) }
        return "fields=[$fields] params=${shape(command["params"], 0)}".take(800)
    }

    /**
     * 音频通道（手机 → 车机，RTP over UDP）：协商音频流 → RECORD → 播放捕获/同步/推流。
     *
     * 两种采音都要求 MediaProjection 作为本次授权及撤销门控，且需用户在「启动连接」
     * 弹窗中选择「开启声音」——音视频同时推流显著增加负载（实测会卡顿/断连）。
     *
     * @return 音频发送器是否启动；本方法完成激活，调用方不得再补发 RECORD。
     */
    @Synchronized
    fun startAudioChannel(
        context: Context,
        client: AirPlayClient,
        sharedSecret: ByteArray?,
        host: String?,
    ): Boolean {
        fun isLiveSession(): Boolean =
            currentClient === client && !client.cancelled && !connectCancelled && videoSender?.isRunning == true

        if (!isLiveSession()) {
            appendLog("AUDIO: 会话已变化或视频尚未启动，跳过本次音频启动")
            return false
        }
        if (!audioEnabledForSession) {
            appendLog("AUDIO: 本次连接未开启车机侧声音（「启动连接」弹窗选择）——仅推视频")
            return false
        }
        // 初次连接与授权回调可能同时到达；共用锁及此检查避免重复 SETUP/RECORD/发送器。
        if (audioSender?.isRunning == true) return true
        if (sharedSecret == null) {
            // 明文会话（et=NONE）没有共享密钥可派生 DataStream 输出密钥：
            // 推出去的 RTP 车机无法解密。宁可不推，也不要制造「有包无声」的假象。
            appendLog("AUDIO: 明文会话（未配对）无共享密钥——跳过音频通道")
            return false
        }
        if (host == null) {
            appendLog("AUDIO: 无接收端可用地址，跳过音频通道")
            return false
        }
        val projection = CarPlayService.mediaProjection
        if (projection == null) {
            appendLog(
                "AUDIO: 缺少本次投屏授权——音频通道未启动。" +
                    "现在点「申请投屏权限」也可即时补启（无需重连）；注意「断开连接」会停服务并连带失效投屏授权",
            )
            return false
        }
        fun isCurrentActivation(): Boolean =
            isLiveSession() && audioEnabledForSession && CarPlayService.mediaProjection === projection

        var pendingSender: AudioSender? = null
        var metadataPublished = false
        fun discardAttempt() {
            val sender = pendingSender ?: return
            sender.stop()
            if (audioSender === sender) audioSender = null
            // 启动入口串行；只有本次发布过的归因信息才可清理，不覆盖其他调用的状态。
            if (metadataPublished) {
                activeAudioProfile = null
                activeAudioReceiverKey = ""
                activeAudioStartedAt = 0L
                metadataPublished = false
            }
        }
        return try {
            // 车机身份 → 只用于「本车机哪些音频档位踩过雷」的持久化记忆。
            val identity = client.receiverIdentity()
            // 音频封装参数已定案（见 AudioTuning），不再提供切换
            val tuning = AudioTuning.RAW_SPF
            val remembered = AudioProfileMemory.load(context.applicationContext, identity)
            val entry = AudioCompatibilityPolicy.entryForSession(identity, remembered)
            if (!AudioCompatibilityPolicy.allowDurationAttribution(identity) && !remembered.isEmpty) {
                appendLog("AUDIO: BMW 旧音频兼容记录不参与本次排序——按车机当前声明协商，保留原记录")
            }
            if (entry.disabled) {
                // 以前「全部档位都导致拆链」会自动退成只推视频，并要用户去弹窗里手点
                // 「清除记忆」。现在音频通路已换成 AAC-LC 且实测能稳定跑满 147s，
                // 旧结论不再可信：用户既然主动选了「开启声音」，就直接清记忆重试。
                AudioProfileMemory.clearAll(context.applicationContext)
                appendLog("AUDIO: 本车机（$identity）有「全部档位拆链」的旧记忆，已自动清除并重试")
            }
            val candidates = client.audioCandidates(preferCompat = audioPreferCompat)
            val ordered = AudioProfileMemory.order(candidates, entry)
            if (ordered.isEmpty()) {
                appendLog("AUDIO: 没有可用音频档位（候选=${candidates.map { it.label }}）——仅推视频")
                return false
            }
            appendLog(
                "AUDIO: 音频档位候选=${ordered.map { it.label }}" +
                    "（本车机记忆：${AudioProfileMemory.describe(entry)}；" +
                    "封装=${tuning.detail}）",
            )
            // SETUP 失败不会扰动会话（车机只是不接这条流的请求），所以本会话内就把候选
            // 试完，不必等下一次连接。这一点对当前场景很关键：102/AAC-LC 与 100/PCM 是
            // 两条完全不同的通路，前者不被接受时应当立刻退到后者，而不是让用户白连一次。
            var chosen: AudioProfile? = null
            var streamInfo: AirPlayClient.AudioStreamInfo? = null
            for (candidate in ordered) {
                if (!isCurrentActivation()) return false
                val result = client.runAudioStream(sharedSecret, candidate, tuning)
                if (!isCurrentActivation()) return false
                if (result != null) {
                    chosen = candidate
                    streamInfo = result
                    break
                }
                // SETUP 被拒不等于「车机不兼容」：可能只是资源仲裁/网络抖动，
                // 不能据此把整台车机的音频判死（置 disabled 只认「拆链」证据）。
                if (AudioCompatibilityPolicy.allowDurationAttribution(identity)) {
                    AudioProfileMemory.recordRejected(
                        context.applicationContext,
                        identity,
                        candidate.key,
                        ordered.map { it.key }.toSet(),
                        markExhausted = false,
                    )
                }
                appendLog("AUDIO: 档位 ${candidate.label} 协商失败——本会话继续试下一档")
            }
            val profile = chosen
            val audio = streamInfo
            if (profile == null || audio == null) {
                appendLog("AUDIO: 全部候选档位协商失败（共 ${ordered.size} 档）——本次仅推视频")
                return false
            }
            val sender =
                AudioSender(
                    host = host,
                    dataPort = audio.dataPort,
                    streamKey = audio.streamKey,
                    log = { line -> appendLog(line) },
                    context = context.applicationContext,
                    sampleRate = audio.sampleRate,
                    codec = profile.codec,
                    streamType = profile.streamType,
                    adtsFrames = tuning.adts,
                    // 同步包目标端口：车机在 SETUP 应答里下发。缺了它就没有 RTP↔NTP 锚点，
                    // 接收端只能「按到达时刻猜」，严格的实现干脆不排播（2026-10-20 实测）。
                    controlPort = audio.controlPort,
                    mediaEpoch = client::currentMediaEpoch,
                )
            val activated =
                io.github.shaonialife.cardeck.audio.AudioActivationSequence.afterSetup(
                    isCurrent = ::isCurrentActivation,
                    record = {
                        appendLog("AUDIO: 音频 SETUP 已接受——先 RECORD 激活，再启动捕获与同步")
                        client.record().also { accepted ->
                            if (!accepted) appendLog("AUDIO: RECORD 未成功，本次不启动捕获或静音；保留视频")
                        }
                    },
                    start = {
                        // RECORD 失败时发送器尚未触及音量/捕获，不得对未启动实例调用 stop。
                        pendingSender = sender
                        sender.start(projection, ::isCurrentActivation)
                    },
                    stop = { sender.stop() },
                )
            if (!activated) {
                discardAttempt()
                false
            } else {
                // 发送器在本地完成启动后才发布，退休不会与其半初始化过程并发 stop。
                activeAudioProfile = profile
                activeAudioReceiverKey = identity
                activeAudioStartedAt = android.os.SystemClock.uptimeMillis()
                metadataPublished = true
                audioSender = sender
                if (!isCurrentActivation()) {
                    discardAttempt()
                    false
                } else {
                    true
                }
            }
        } catch (error: Exception) {
            appendLog("AUDIO: 通道启动异常 - ${error.message}")
            discardAttempt()
            false
        }
    }

    /**
     * 会话结束时的**音频拆链归因**（2026-10-19 Honda HFT / Alpine 实测新增）。
     *
     * 现象：选了「视频+音频」后会话能建立、视频也正常，但音频起流约 6s 后车机把整条
     * AirPlay 会话拆掉（`EVENT: 读取结束 - Connection reset`），只推视频则稳定。
     * 车机侧不会告诉我们「哪一档音频不兼容」，只能用存活时间反推：
     *  - 音频起流后**没跑满** [AUDIO_PROFILE_PROBE_MS] 就整链被拆 → 判该档位不兼容，
     *    记入本车机名单（全部档位都被判死则置 disabled，下次直接只推视频）；
     *  - 跑满了 → 记为该档位可用，下次连接直接首选。
     *
     * 注意只在「确实起过音频」时归因；纯视频会话结束不做任何记忆改写。
     */
    private fun attributeAudioTeardown() {
        val profile = activeAudioProfile ?: return
        val startedAt = activeAudioStartedAt
        val identity = activeAudioReceiverKey
        val lived = if (startedAt > 0) android.os.SystemClock.uptimeMillis() - startedAt else -1L
        // 本轮记忆很快失效：下次连接重新按最新记忆选档
        activeAudioProfile = null
        activeAudioStartedAt = 0L
        if (startedAt == 0L || identity.isBlank()) return
        // BMW 14:30 实测未完成控制握手却被按 19s 误记为音频不兼容；下一轮
        // 传输超过一分钟但采样全零。存活时长不再用于改写该车的兼容记录。
        if (!AudioCompatibilityPolicy.allowDurationAttribution(identity)) {
            appendLog("AUDIO: BMW 音频会话结束，持续 ${lived / 1000}s——保留原兼容记录；实际声音待实测确认")
            return
        }
        val ctx = appContext?.applicationContext
        if (lived >= AUDIO_PROFILE_PROBE_MS) {
            AudioProfileMemory.recordWorking(ctx, identity, profile.key)
            appendLog("AUDIO: 档位 ${profile.label} 存活 ${lived / 1000}s——记为本车机可用档位")
            return
        }
        val allKeys = audioCandidateKeys(identity)
        AudioProfileMemory.recordRejected(ctx, identity, profile.key, allKeys)
        val after = AudioProfileMemory.load(ctx, identity)
        appendLog(
            "AUDIO: ⚠ 档位 ${profile.label} 起流 ${lived / 1000}s 后整条会话被车机拆掉——" +
                "判定为本车机不兼容档位（已记入名单）。" +
                if (after.disabled) {
                    "全部档位均不兼容：下次连接将自动只推视频（车机侧无声音）。"
                } else {
                    "下次点「启动连接」会自动换用下一档。"
                },
        )
        uiHintSink?.invoke(
            if (after.disabled) {
                "本车机音频不兼容，下次连接将自动只推视频"
            } else {
                "该音频档位导致断开，已记录；重连会自动换档"
            },
        )
    }

    /** 该车机上「所有可能用到的档位键」——用于判断是否已全部试穿。 */
    private fun audioCandidateKeys(identity: String): Set<String> =
        runCatching {
            val client = currentClient ?: return@runCatching emptySet()
            val preferred = client.audioCandidates(preferCompat = audioPreferCompat).map { it.key }
            val alternate = client.audioCandidates(preferCompat = !audioPreferCompat).map { it.key }
            (preferred + alternate).toSet()
        }.getOrDefault(emptySet())

    /**
     * 端点补全：优先吸收 mDNS 解析出的地址，再决定是否等待/兜底。
     *
     * 关键（2026-10-19 奥迪 4851 实车日志）：0x4301 只给了
     * `fe80::3ca8:64ff:fe7c:293e`（IPv6 链路本地），而 `_airplay._tcp` 解析出的是
     * `10.174.189.1:7000`——车机实际用 IPv4 通的。**不能因为 0x4301 带了 IPv6 就不看
     * mDNS**：那样会把唯一验证过的 IPv4 路径丢掉。这里先做一次「零等待」缓存合并，
     * 拿到 IPv4 就按 EndpointHosts 的优先级（IPv4 → IPv6）使用；缓存没命中才按需等。
     */
    private fun resolveEndpointHost(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint {
        var effective = endpoint
        val cached = endpointBrowser?.awaitEndpoint(0)
        if (cached != null) {
            effective = mergeBrowserEndpoint(effective, cached, source, needPort = effective.airPlayPort <= 0)
        }
        val needHost = !EndpointHosts.hasUsable(effective.ipAddresses)
        val needPort = effective.airPlayPort <= 0
        if (!needHost && !needPort) return effective

        phaseHint = "mDNS 解析车机地址…"
        appendLog(">>> [$source] 0x4301 端点不完整（port=${effective.airPlayPort} ips=${effective.ipAddresses}），取 mDNS 车机 _airplay._tcp…")
        // 用常驻发现器的缓存（绑网时已开始后台发现，通常此时已命中）。
        // 此前现起一次发现并硬重试 3×6s：车机 mDNS 稳定时白等，不稳定时又拖满 18s。
        // 网段过滤（防误连同网 macOS 接收器）由 browser 的 accept 统一做。
        val info =
            endpointBrowser?.awaitEndpoint(ENDPOINT_RESOLVE_WAIT_MS) ?: run {
                // 兜底：热点场景车机 IP = 网关（Audi 实测 .1，但取网关更通用）。
                // mDNS 在部分网络/机型上不稳定（2026-10-11 实测车机 mDNS 时有时无）；
                // 网关地址 + 默认端口 7000 直接连，AirPlay 端口是固定约定。
                val gateways = wifiConnector?.gatewayAddresses().orEmpty()
                if (gateways.isNotEmpty()) {
                    appendLog(">>> [$source] mDNS 未发现，使用当前车机网络的网关候选 $gateways:7000（IPv4 优先，保留 IPv6）")
                    return effective.copy(
                        ipAddresses = EndpointHosts.candidates(gateways + effective.ipAddresses),
                        airPlayPort = if (needPort) 7000 else effective.airPlayPort,
                    )
                }
                appendLog(">>> [$source] mDNS 未发现车机 AirPlay 服务（车机可能尚未启动无线会话）")
                return effective
            }
        return mergeBrowserEndpoint(effective, info, source, needPort)
    }

    /**
     * 把一次 mDNS 解析结果并入端点：地址**追加到最前**（EndpointHosts 会按
     * IPv4 → 全局 IPv6 → 链路本地 IPv6 重排，顺序只影响同类型内部）。
     * 其余字段只在缺失时补（0x4301 的权威值不覆盖）。
     */
    private fun mergeBrowserEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        info: android.net.nsd.NsdServiceInfo,
        source: String,
        needPort: Boolean,
    ): Iap2SenderSession.CarPlayEndpoint {
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
            appendLog(">>> [$source] mDNS 解析到车机地址 $address:${info.port}")
        }
        if (needPort && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        // TXT 公钥兜底（pair-verify 校验用；0x4301 缺失时只能从这里拿）
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        return result
    }

    private fun dispatchEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
        late: Boolean,
    ) {
        endpointCallbacks.dispatch { isCurrent ->
            try {
                if (late) onLateEndpoint(endpoint, source, isCurrent)
                else onCarPlayEndpoint(endpoint, source, isCurrent)
            } catch (error: Throwable) {
                appendLog("AIRPLAY: 端点交接异常 - ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /**
     * Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时的热更新入口。
     *
     * 三种归属：
     *  - 正在推流：冗余端点，忽略（与 [onCarPlayEndpoint] 同一防护，避免拆掉健康会话）；
     *  - 交接/配对进行中：热更新当前 AirPlayClient 的端点，配对循环下一轮即用新地址；
     *    若 client 尚未创建（还在绑网/等邀请）先暂存，配对线程起步时并入；
     *  - 上一轮已失败（无会话在跑）：按新端点重走一轮完整交接——Audi MMI 9310
     *    实测 0x4301 在 120s 配对窗口耗尽后 28s 才到，旧实现把它当日志垃圾丢掉，
     *    车机等 15s 无人连入即拆链，表现为「连上后自动断开」。
     */
    private fun onLateEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
        isCurrent: () -> Boolean,
    ) {
        synchronized(wifiBindLock) {
            if (connectCancelled || !isCurrent()) {
                appendLog(">>> [$source] 0x4301 晚到（已取消连接）——忽略")
                return
            }
            if (videoSender?.isRunning == true) {
                appendLog(">>> [$source] 0x4301 晚到（推流中）——忽略冗余端点")
                return
            }
            val client = currentClient
            if (sessionStarting.get()) {
                if (client != null && client.pairingInProgress) {
                    // 合并而非替换：0x4301 可能不带 IPv4（Audi MMI 9310 实测端点解析依赖
                    // mDNS/网关），直接替换会把已解析到的地址抹掉，配对循环反而连不上。
                    val merged = preferEndpoint(endpoint, client.currentEndpoint)
                    val changed = client.updateEndpoint(merged, "0x4301 晚到")
                    appendLog(
                        if (changed) {
                            ">>> [$source] 0x4301 晚到（配对进行中）——端点已热更新，下一轮重试用新地址"
                        } else {
                            ">>> [$source] 0x4301 晚到（配对进行中）——端点无变化，忽略"
                        },
                    )
                } else {
                    pendingEndpointUpdate = endpoint
                    appendLog(">>> [$source] 0x4301 晚到（交接尚未创建配对客户端）——暂存，配对起步时采用")
                }
                return
            }
            // 用户已断开（BT 组件已停）时不得用晚到消息复活会话
            if (bluetoothClient?.isRunning != true && bluetoothServer?.isRunning != true) {
                appendLog(">>> [$source] 0x4301 晚到（蓝牙链路已退役）——忽略")
                return
            }
            appendLog(">>> [$source] 0x4301 晚到（上一轮已结束）——按新端点重走一轮连接")
            onCarPlayEndpoint(endpoint, "$source/0x4301 晚到", isCurrent)
        }
    }

    /** 把交接期间暂存的晚到端点并入新建的客户端（建 client 与收到 0x4301 的竞态窗口）。 */
    private fun drainPendingEndpoint(client: AirPlayClient) {
        val late = pendingEndpointUpdate ?: return
        pendingEndpointUpdate = null
        if (client.updateEndpoint(preferEndpoint(late, client.currentEndpoint), "交接期间晚到 0x4301")) {
            appendLog("AIRPLAY: 交界窗口的 0x4301 端点已并入配对客户端")
        }
    }

    /**
     * 配对循环的端点刷新器（非阻塞）：只读浏览器缓存的 `_airplay._tcp`，
     * 有则并入 IP/端口/公钥等字段。返回 null = 无新信息（配对循环继续用当前端点）。
     */
    private fun enrichEndpointFromBrowser(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
    ): Iap2SenderSession.CarPlayEndpoint? {
        val info = endpointBrowser?.awaitEndpoint(0) ?: return null
        var result = endpoint
        val address = info.host?.hostAddress
        if (address != null &&
            EndpointHosts.candidates(listOf(address)).isNotEmpty() &&
            result.ipAddresses.none { EndpointHosts.stripScope(it) == EndpointHosts.stripScope(address) }
        ) {
            result = result.copy(ipAddresses = listOf(address) + result.ipAddresses)
        }
        if (result.airPlayPort <= 0 && info.port > 0) {
            result = result.copy(airPlayPort = info.port)
        }
        if (result.publicKey == null) {
            txtValue(info, "pk")?.let { result = result.copy(publicKey = it) }
        }
        if (result.deviceIdentifier == null) {
            txtValue(info, "deviceid")?.let { result = result.copy(deviceIdentifier = it) }
        }
        if (result.sourceVersion == null) {
            txtValue(info, "srcvers")?.let { result = result.copy(sourceVersion = it) }
        }
        if (result.advertisedFeatures == null) {
            AirPlayFeatures.parse(txtValue(info, "features"))?.let {
                result = result.copy(advertisedFeatures = it)
            }
        }
        if (result == endpoint) return null
        appendLog(
            ">>> [$source] 配对期间 mDNS 刷新端点 → " +
                "${result.ipAddresses.firstOrNull()}:${result.airPlayPort}",
        )
        return result
    }

    private fun txtValue(
        info: android.net.nsd.NsdServiceInfo,
        key: String,
    ): String? =
        info.attributes[key]
            ?.toString(Charsets.UTF_8)
            ?.trimEnd { it.code == 0 }
            ?.takeIf { it.isNotEmpty() }

    /**
     * 晚到端点优先：车机明确告知的端口/公钥优先于 mDNS 合成值，
     * 但 IP 列表取并集（0x4301 可能不带 IPv4，Audi MMI 9310 实测即如此）。
     */
    private fun preferEndpoint(
        late: Iap2SenderSession.CarPlayEndpoint,
        fallback: Iap2SenderSession.CarPlayEndpoint,
    ): Iap2SenderSession.CarPlayEndpoint =
        late.copy(
            airPlayPort = if (late.airPlayPort > 0) late.airPlayPort else fallback.airPlayPort,
            ipAddresses = (late.ipAddresses + fallback.ipAddresses).distinct(),
            publicKey = late.publicKey ?: fallback.publicKey,
            deviceIdentifier = late.deviceIdentifier ?: fallback.deviceIdentifier,
            sourceVersion = late.sourceVersion ?: fallback.sourceVersion,
            advertisedFeatures = late.advertisedFeatures ?: fallback.advertisedFeatures,
        )

    /**
     * 初始 SETUP（带重连重试）。
     *
     * 已配对路径：配对循环刚连通过，SETUP 一次即可（失败就是真失败，交给降级/收尾）。
     * 明文会话路径：车机的 AirPlay 端口可能要到「用户点车机图标」后才开，
     * catplay 同样是「收到邀请才连」；这里在 [INITIAL_SETUP_WINDOW_MS] 内每 2s
     * 重连一次并重试 SETUP，避免端口晚开 1~2 秒就整轮失败。
     */
    private fun runInitialSetup(
        client: AirPlayClient,
        plainSession: Boolean,
        noPairing: Boolean,
    ): AirPlayClient.SetupInfo? {
        if (client.cancelled || connectCancelled) return null
        val first = client.runSetup()
        if (first != null || !plainSession) return first
        val deadline = android.os.SystemClock.uptimeMillis() + INITIAL_SETUP_WINDOW_MS
        var attempt = 1
        while (android.os.SystemClock.uptimeMillis() < deadline && !connectCancelled && !client.cancelled) {
            attempt++
            appendLog("AIRPLAY: 明文 SETUP 第 ${attempt - 1} 次未通过——2s 后重连重试（车机 AirPlay 端口可能尚未开放）")
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return null
            }
            if (connectCancelled || client.cancelled) return null
            client.close()
            if (!client.reconnect(2_000)) continue
            val retried = client.runSetup()
            if (retried != null) {
                appendLog("AIRPLAY: ✓ 明文 SETUP 在第 $attempt 次尝试成功")
                return retried
            }
            // 端口开了但 SETUP 仍被拒：TXT 判断可能错了，交给调用方的加密升级路径
            if (!noPairing) return null
        }
        return null
    }

    /** BT 会话交接点：拿到 0x5703/0x4301 后两条路径流程一致——绑 WiFi → AirPlay 配对 → SETUP → 推流。 */
    private fun onCarPlayEndpoint(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        source: String,
        isCurrent: () -> Boolean,
        recovery: BmwRecoveryInvite? = null,
    ) {
        val context = appContext ?: return
        val wifiEpoch = wifiJoins.currentEpoch() ?: return
        if (connectCancelled || !isCurrent()) return
        val receiverAtStart = selectedCtrlReceiver()
        // 冗余端点防护：接收端控制器（DiPlay 实测）在我们配对推流后才完成既有的
        // RFCOMM 重连并重发 0x4301。此时重启配对会 retireSession 掉健康会话
        // （编码器 stop 竞态 = 「连上后自动断开」循环的根因）。推流中的端点一律忽略；
        // 真断连时推流自行终止（isRunning=false），下一个端点自然接续重连。
        if (videoSender?.isRunning == true) {
            appendLog(">>> [$source] 会话推流中——忽略冗余端点（接收端周期重连的重复 0x4301）")
            return
        }
        val attempt = synchronized(wifiBindLock) {
            if (!connectCancelled && isCurrent() && videoSender?.isRunning != true && sessionStarting.compareAndSet(false, true)) {
                pendingEndpointUpdate = null
                sessionAttempts.begin()
            } else null
        }
        if (attempt == null) {
            appendLog(">>> [$source] 已有会话在处理中，忽略本次端点（等待上一轮结束）")
            return
        }
        fun finishStart() = synchronized(wifiBindLock) {
            if (sessionAttempts.owns(attempt)) sessionStarting.set(false)
        }
        fun attemptActive() = synchronized(wifiBindLock) {
            !connectCancelled && isCurrent() && sessionAttempts.active(attempt)
        }
        fun requireAttempt() {
            check(attemptActive() && sessionAttempts.matches(attempt, wifiConnector?.currentNetwork)) {
                "本轮车机网络已失效或配对已被替换"
            }
        }
        appendLog(
            ">>> [$source] 拿到接收端热点：SSID=${endpoint.ssid}；" +
                "密码=${if (endpoint.passphrase.isEmpty()) "(空)" else "已获取"}",
        )
        phaseHint = "蓝牙已连上车机，解析热点地址…"
        ssidSink?.invoke(endpoint.ssid)
        // 诊断（老车机排查必看）：地址是 IPv4 还是只有 IPv6、TXT features 是否声明配对能力。
        // 奥迪 4xxx 两种「连不上」都源于这里——旧版日志里完全没有这两项。
        val addresses = endpoint.ipAddresses
        val hasIpv4 = addresses.any { EndpointHosts.isIpv4(it) }
        val pairingFlag = AirPlayFeatures.supportsPairing(endpoint.advertisedFeatures)
        appendLog(
            ">>> [$source] 端点诊断：地址=${addresses.ifEmpty { listOf("(无)") }}" +
                "（${if (hasIpv4) {
                    "IPv4"
                } else if (addresses.isEmpty()) {
                    "无地址"
                } else {
                    "仅 IPv6"
                }}）" +
                " features=" + (endpoint.advertisedFeatures?.let { "0x${it.toString(16)}" } ?: "未知") +
                " HomeKit配对=" +
                when (pairingFlag) {
                    true -> "声明支持"
                    false -> "未声明（按 pre-HomeKit 明文会话处理）"
                    null -> "未知（按需配对）"
                },
        )
        // 车机画像（信号收口点）：mDNS 侧信号到达——era 判定 + 依据一行可解释
        appendLog(
            ReceiverProfile.describe(
                ReceiverProfile.classify(
                    ReceiverProfile.Signals(
                        features = endpoint.advertisedFeatures,
                        srcvers = endpoint.sourceVersion,
                    ),
                ),
            ),
        )
        // 先绑网络再解析端点：mDNS 组播跟随 bindProcessToNetwork 的网络，解析放前面
        // 只会拿到旧网络的结果（此前靠 resolveEndpointHost 内部重试掩盖，白等数秒）。
        // 绑定完成后再并行做三件事：ctrl 广播针对新网络重注册、后台发现 _airplay._tcp、
        // 等车机 ctrl 邀请（车机已发现我们的权威信号）。
        val continueWithNetwork: (Boolean) -> Unit = proceed@{ bound ->
            if (!attemptActive()) {
                finishStart()
                return@proceed
            }
            // 网络发布与延期失联通知共锁；广播与发现器不能分别读到不同代网络。
            val ready = synchronized(wifiBindLock) {
                val network = if (bound && attemptActive()) wifiConnector?.validatedCurrentNetwork() else null
                if (network == null || !sessionAttempts.bind(attempt, network, recovery?.network)) {
                    false
                } else {
                    ctrlAdvertiser?.ensureForNetwork(endpoint.ssid, force = true, network = network)
                    endpointBrowser?.ensureForNetwork(
                        network,
                        endpoint.ssid,
                        appContext?.let { TargetDevicePrefs.get(it)?.address },
                    )
                    true
                }
            }
            if (!ready) {
                phaseHint = "手机不在车机热点——请到 WLAN 设置加入 ${endpoint.ssid}"
                appendLog(">>> [$source] 手机当前不在该热点——请到 设置>WLAN 加入 \"${endpoint.ssid}\" 后断开重连")
                finishStart()
                return@proceed
            }
            // 0x4301 自带完整地址+端口 = 接收端已明确告知「连这里」，无需等邀请
            // （DiPlay 测试台路径：本轮直接配对，省掉固定等待）。
            // IPv6 也算地址：奥迪 4xxx 的 0x4301 常只给 fe80::（旧判据只看 IPv4，
            // 会把这类端点当成「不完整」，白等 ctrl 邀请再退回网关兜底）。
            val endpointSelfContained =
                endpoint.airPlayPort > 0 && EndpointHosts.hasUsable(endpoint.ipAddresses)
            Bg.run("airplay-pairing") {
                var ownedClient: AirPlayClient? = null
                var sessionVideoSender: VideoSender? = null
                try {
                    requireAttempt()
                    if (!attemptActive()) {
                        appendLog("AIRPLAY: 本轮连接已取消或失效——中止配对")
                        finishStart()
                        return@run
                    }
                    if (recovery != null) {
                        synchronized(wifiBindLock) {
                            requireAttempt()
                            check(recovery.take()) { "宝马重连邀请已失效，等待车机再次发起连接" }
                        }
                    } else if (endpointSelfContained) {
                        appendLog("AIRPLAY: 0x4301 端点完整（${endpoint.ipAddresses.firstOrNull()}:${endpoint.airPlayPort}）——直接配对，不等 ctrl 邀请")
                    } else {
                        // 等 ctrl 邀请（catplay CarPlayCtrlServer：邀请=车机已发现手机且
                        // AirPlay 窗口已开，收到即刻连接）。此前是「固定睡 1.6s 赌它已到」，
                        // 快的时候白等、慢的时候不够；改为事件驱动。
                        val recoveringBmw = endpoint.preferCtrlInvitation && wifiJoins.takeLostRequest(wifiEpoch, endpoint.ssid)
                        awaitCtrlInvite(endpoint.ssid, endpoint.preferCtrlInvitation, recoveringBmw) {
                            attemptActive() && sessionAttempts.matches(attempt, wifiConnector?.currentNetwork)
                        }
                        requireAttempt()
                    }
                    // 端点解析放在邀请之后：车机的 `_airplay._tcp` 常在受邀后才上线，
                    // 且这一步最长 3s——必须在后台线程做，不能占住网络回调线程。
                    var effectiveEndpoint = resolveEndpointHost(endpoint, source)
                    // 交接流程起步期间车机补发的 0x4301（比 mDNS 合成更权威）在此并入。
                    synchronized(wifiBindLock) {
                        requireAttempt()
                        pendingEndpointUpdate?.let { late ->
                            pendingEndpointUpdate = null
                            effectiveEndpoint = preferEndpoint(late, effectiveEndpoint)
                            appendLog(
                                "AIRPLAY: 并入交接期间晚到的 0x4301 端点 → " +
                                    "${effectiveEndpoint.ipAddresses.firstOrNull()}:${effectiveEndpoint.airPlayPort}",
                            )
                        }
                    }
                    if (!attemptActive()) {
                        appendLog("AIRPLAY: 本轮连接已取消或失效——中止配对")
                        finishStart()
                        return@run
                    }

                    fun newClient() =
                        AirPlayClient(
                            effectiveEndpoint,
                            log = { line -> appendLog(line) },
                            context = context.applicationContext,
                            endpointRefresher = { current ->
                                if (attemptActive()) enrichEndpointFromBrowser(current, source) else null
                            },
                            currentCtrlInviteCount = { ctrlAdvertiser?.inviteCount ?: 0 },
                            createControlSocket = {
                                if (recovery == null) java.net.Socket() else {
                                    check(isCurrent() && sessionAttempts.matches(attempt, recovery.network)) {
                                        "宝马重连网络或目标已变化"
                                    }
                                    recovery.network.socketFactory.createSocket()
                                }
                            },
                            retryTick = { attempt ->
                                synchronized(wifiBindLock) {
                                    requireAttempt()
                                    // 配对重试期间车机仍可能没探测到 ctrl（Bonjour 漏掉首次公告）：
                                    // 周期性强制重播一次，并把 mDNS 现状写进日志——否则排查时
                                    // 只看得到「端口未开」的重试刷屏，无法判断是车机没邀请还是
                                    // 地址不对（2026-10-19 奥迪 0.1.7 日志就缺这两条信息）。
                                    ctrlAdvertiser?.ensureForNetwork(
                                        endpoint.ssid,
                                        force = true,
                                        network = wifiConnector?.currentNetwork,
                                    )
                                    val invites = ctrlAdvertiser?.inviteCount ?: 0
                                    val found = endpointBrowser?.awaitEndpoint(0)
                                    appendLog(
                                        "AIRPLAY: 重试 #$attempt —— 已重新公告 ctrl；" +
                                            "车机邀请=$invites 次，mDNS _airplay._tcp=" +
                                            (found?.let { "${it.host?.hostAddress}:${it.port}" } ?: "未发现"),
                                    )
                                }
                            },
                        ).also { created ->
                            val endSnapshotTaken = java.util.concurrent.atomic.AtomicBoolean(false)
                            created.onControlReadEnded = ended@{
                                if (currentClient !== created || !sessionAttempts.owns(attempt)) return@ended
                                val liveVideo = videoSender ?: return@ended
                                val liveAudio = audioSender
                                // 不在读线程等待生命周期锁；取引用后复核，避免重连中误读新 sender。
                                if (currentClient !== created || !sessionAttempts.owns(attempt) ||
                                    videoSender !== liveVideo || audioSender !== liveAudio
                                ) return@ended
                                if (!liveVideo.isRunning || !endSnapshotTaken.compareAndSet(false, true)) return@ended
                                // 在后续拆流清空统计之前取快照；不改系统设置或启动重连。
                                appendLog("SESSION-DIAG: first_control_end ${liveVideo.transportDiagnosticLine()}")
                                liveAudio?.captureDiagnosticLines()?.forEach { line ->
                                    appendLog("SESSION-DIAG: first_control_end $line")
                                }
                                if (!connectCancelled && !created.cancelled && currentClient === created &&
                                    videoSender === liveVideo && sessionAttempts.active(attempt)
                                ) liveVideo.onControlConnectionLost()
                            }
                            // 车机媒体键（iAP2 0x6802 通路）：方向盘/面板的上一曲、下一曲。
                            // 放在这里而不是调用点，保证重连重建 client 时也挂得上。
                            created.onMediaKey = { keyCode ->
                                if (currentClient === created && !created.cancelled && !connectCancelled) {
                                    CarShortcutLearning.observe(VirtualLauncher.sessionGeneration(), "iap_media_key")
                                }
                                dispatchMediaKey(keyCode)
                                appendLog("EVENT: 车机媒体键（iAP2 0x6802）→ keyCode=$keyCode 已注入")
                            }
                        }
                    var client = newClient().also { ownedClient = it }
                    synchronized(wifiBindLock) {
                        requireAttempt()
                        retireSessionResources()
                        // 只有仍持有本轮网络的任务才能发布 client；旧任务不得退役下一轮。
                        currentClient = client
                        BtSessionGate.sessionActive = true
                        phaseHint = "AirPlay 配对中…"
                    }
                    drainPendingEndpoint(client)
                    // pre-HomeKit 老车机（CarPlay 210.81 时代，奥迪 4xxx 固件）在
                    // `_airplay._tcp` 里**不声明** HK_PAIRING_AND_ENCRYPT：它们只接受
                    // 明文 SETUP（et=NONE）+ AES-CTR 屏幕流，走 pair-setup 必然失败。
                    // catplay 的接收端分类 1 即此形态；旧实现会在这里空转 120s 配对窗口，
                    // 用户看到的就是「连不上」。
                    val noPairing =
                        AirPlayFeatures.supportsPairing(client.currentEndpoint.advertisedFeatures) == false
                    var keys: PairVerifyClient.ControlKeys? =
                        if (noPairing) {
                            client.skipPairing("TXT features 未声明 HK_PAIRING_AND_ENCRYPT")
                            client.reconnect()
                            null
                        } else {
                            client.runPairing()
                        }
                    if (!attemptActive()) {
                        appendLog("AIRPLAY: 本轮连接已取消或失效——中止配对")
                        finishStart()
                        return@run
                    }
                    requireAttempt()
                    // 配对失败后的兜底路径（纯函数决策，真机抓包场景由 PairingFallbackTest 钉死）
                    val fallbackAfterFirstRound =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                        )
                    if (fallbackAfterFirstRound.useBtRetireHack) {
                        // 第二阶段（仅测试台 DiPlay）：复现「BT 控制已结束」状态再试一次。
                        // 真车（Audi）绝不能断 BT——真无线 CarPlay 的 BT 控制通道全程保活，
                        // 断开会让车机拆掉整轮 AirPlay 窗口（实车实测）。
                        // pairingForbidden（任意段 403，含 verify/认证段——2026-10-21 真机
                        // sysdiagnose 定谳：首连被 403 是车机固有状态，它 18~20s 后自动
                        // 重邀、第二次即成）时同样禁用：断 BT 会让车机整栈重建、恰好错过
                        // 即将到来的重邀窗口。
                        // 门槛：仅当本轮真的连上过接收端 RTSP（attemptedPairing）才用此 hack；
                        // 端点未解析（无 IP）/端口未开时绝不值得断 BT（2026-10-11 踩过：
                        // mDNS 失败也触发 detach，白白拆掉车机窗口）。
                        synchronized(wifiBindLock) {
                            requireAttempt()
                            detachActiveBtSessions()
                            client.cancelled = true
                            client.close()
                            client = newClient().also { ownedClient = it }
                            currentClient = client
                        }
                        drainPendingEndpoint(client)
                        keys = client.runPairing()
                        if (!attemptActive()) {
                            appendLog("AIRPLAY: 本轮连接已取消或失效——中止配对")
                            finishStart()
                            return@run
                        }
                        requireAttempt()
                    }
                    // keys == null 时仍有合法路径：**catplay 的 et=NONE 明文会话**。
                    //  - 接收端明确不支持配对（pre-HomeKit / TXT 无 HK_PAIRING_AND_ENCRYPT）；
                    //  - 配对彻底失败但 RTSP 确实连上过（有些车只认明文会话，
                    //    pair-setup/pair-verify 全拒——catplay 正是「先试明文、失败才配对」，
                    //    我们反过来：优先配对（保住 DiPlay/已知车机），失败后降级明文兜底）。
                    //  - **403 证据（pairingForbidden）除外**（真机抓包 2026-10-21 定谳，决策
                    //    见 pairingFallback）：车机在等自身就绪、~20s 后重邀即成——明文必被
                    //    拒且消耗重邀窗口，不降级。
                    // hack 段可能已重跑拿到 keys：决策以重跑后的状态为准。
                    val plainSession =
                        pairingFallback(
                            keysEstablished = keys != null,
                            noPairing = noPairing,
                            pairSetupRejected = client.pairSetupRejected,
                            pairingForbidden = client.pairingForbidden,
                            attemptedPairing = client.attemptedPairing,
                            pairingUnsupported = client.pairingUnsupported,
                        ).usePlainSession
                    if (plainSession) {
                        phaseHint = "建立明文会话…"
                        appendLog(
                            "AIRPLAY: 配对不可用（${if (client.pairingUnsupported) "接收端不支持" else "配对失败"}）" +
                                "——改用 et=NONE 明文会话 + AES-CTR 屏幕流（catplay 接收端分类 1/2）",
                        )
                        // 配对循环结束时 socket 已关/状态机不可用：SETUP 前必须重连一次。
                        // 失败不直接判死：车机 AirPlay 端口可能还没开，下面会按窗口重试。
                        if (!client.reconnect()) {
                            appendLog("AIRPLAY: 明文会话首次重连未成功——继续按窗口重试 SETUP")
                        }
                    }
                    // keys 是 lambda 内的可变捕获变量：Kotlin 无法智能转换，先固化成不可变值。
                    val pairedKeys = keys
                    if (pairedKeys != null || plainSession) {
                        phaseHint = if (pairedKeys != null) "配对完成，建立 CarPlay 会话…" else "建立明文会话…"
                        if (pairedKeys != null) {
                            appendLog(
                                "AIRPLAY: ✓ 配对完成 writeKey=" +
                                    pairedKeys.phoneWriteKey.joinToString("") { "%02x".format(it) }.take(16) + "… " +
                                    "readKey=" + pairedKeys.phoneReadKey.joinToString("") { "%02x".format(it) }.take(16) + "…",
                            )
                        }
                        // 已配对 → 共享密钥；明文会话 → null（流加密降级为 AES-CTR 零密钥）
                        var sessionKey = pairedKeys?.sharedSecret
                        var setup = runInitialSetup(client, plainSession, noPairing)
                        if (setup == null && plainSession && noPairing) {
                            // catplay 的「加密升级」路径：只有在**我们主动跳过配对**（TXT 未声明
                            // HK_PAIRING_AND_ENCRYPT）且明文 SETUP 仍失败时才补做。
                            // 反过来（配对先试过且失败）再试一次没有意义，只会多等 30s。
                            val upgraded = client.retryWithPairing(PAIRING_UPGRADE_WINDOW_MS)
                            if (upgraded != null) {
                                sessionKey = upgraded.sharedSecret
                                setup = client.runSetup()
                            }
                        }
                        requireAttempt()
                        if (setup != null) {
                            // 记录会话凭据：投影授权晚于连接时用于动态补启音频。
                            // 用 client.currentEndpoint 而非 effectiveEndpoint：配对期间
                            // mDNS 刷新过端点时，真正连上的是 client 里的地址（2026-10-18：
                            // 0x4301 的定长 IP 字段带 NUL，mDNS 给的是干净地址，取旧值
                            // 会让视频端口/keepAlive 用脏地址 → 一律解析失败）。
                            synchronized(wifiBindLock) {
                                requireAttempt()
                                currentSharedSecret = sessionKey
                                currentAudioHost =
                                    client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                            }
                            val uiRecoveryClient = client
                            val uiRecoveryPolicy = BmwRequestUiRecoveryPolicy(uiRecoveryClient.receiverIdentity())
                            val isBmw = BmwControlIapCompatibility.matches(uiRecoveryClient.receiverIdentity())
                            val shortcutSequence = AtomicLong()
                            val shortcutScreenChanges = AtomicLong()
                            val observations = BmwShortcutObservations()
                            val voiceKey = SteeringVoiceKey()
                            val rotaryDevices = setup.hidDescriptors.mapNotNull { (id, desc) ->
                                BmwRotaryHid.fromDescriptor(desc)?.let { id to it }
                            }.toMap()
                            var rotaryEvents = 0
                            client.connectEventChannel(sessionKey, setup) { command ->
                                val generation = VirtualLauncher.sessionGeneration()
                                val measuringDialVoice = isBmw && currentClient === uiRecoveryClient && !uiRecoveryClient.cancelled &&
                                    !connectCancelled && steeringDialProbe.record(
                                        generation, android.os.SystemClock.elapsedRealtime(), command, setup.hidDescriptors, rotaryDevices.keys,
                                    )
                                if (isBmw && currentClient === uiRecoveryClient && !uiRecoveryClient.cancelled &&
                                    !connectCancelled && CarShortcutLearning.isObserving(generation)
                                ) {
                                    observations.record(command, command["uuid"]?.toString() in rotaryDevices) { kind ->
                                        CarShortcutLearning.observe(generation, kind)
                                    }
                                }
                                when (command["type"]) {
                                    "setNightMode" -> {
                                        if (currentClient === uiRecoveryClient && !uiRecoveryClient.cancelled &&
                                            uiRecoveryClient.acceptNightMode(command["params"] as? Map<*, *>)
                                        ) {
                                            val action = io.github.shaonialife.cardeck.launcher.CarAppearance.VEHICLE_MODE_CHANGED
                                            context.sendBroadcast(android.content.Intent(action).setPackage(context.packageName))
                                            appendLog("车机配色：收到夜间模式更新 night=${uiRecoveryClient.receiverNightMode()}")
                                        }
                                    }
                                    "requestSiri" -> {
                                        val action = (command["params"] as? Map<*, *>)?.get("siriAction")
                                        val press = voiceKey.onAction(action, android.os.SystemClock.elapsedRealtime())
                                        val revision = uiRecoveryClient.controllerInputRevision
                                        val epoch = shortcutScreenChanges.get()
                                        fun active() = isBmw && currentClient === uiRecoveryClient && !uiRecoveryClient.cancelled &&
                                            !connectCancelled && videoSender?.isRunning == true && VirtualLauncher.isCurrentSession(generation) &&
                                            epoch == shortcutScreenChanges.get() && revision != null && uiRecoveryClient.controllerInputRevision == revision
                                        if (isBmw) appendLog("BMW-VOICE: action=${(action as? Number)?.toLong()} press=$press dialProbe=$measuringDialVoice")
                                        if (press && !measuringDialVoice && active()) {
                                            Bg.run("bmw-bixby") {
                                                val result = PhoneBixby.open(context.applicationContext, generation = generation, live = ::active)
                                                appendLog("BMW-VOICE: Bixby result=${result.name} display=0")
                                                if (result != PhoneBixby.Result.EXPIRED && active()) uiHintSink?.invoke(result.hint)
                                            }
                                        }
                                    }
                                    "requestUI" -> {
                                        val liveSession = currentClient === uiRecoveryClient &&
                                            !uiRecoveryClient.cancelled && !connectCancelled
                                        val sequence = shortcutSequence.incrementAndGet()
                                        val target = if (liveSession && isBmw) BmwShortcutTarget.from(command) else null
                                        val sender = videoSender
                                        val inputRevision = uiRecoveryClient.controllerInputRevision
                                        val requestParams = command["params"] as? Map<*, *>
                                        val bareReturn = requestParams?.size == 1 && requestParams["url"] == ""
                                        if (isBmw && liveSession && !bareReturn) {
                                            val media = target?.takeIf { inputRevision == null && it.defaultPage == CarShortcut.MEDIA &&
                                                !CarShortcutLearning.isCalibrating(generation) }
                                            uiRecoveryClient.rememberBorrowedMedia(media?.token)
                                        }
                                        val receivedScreen = shortcutScreenChanges.get()
                                        fun ownsShortcut(revision: Long): Boolean =
                                            receivedScreen == shortcutScreenChanges.get() && currentClient === uiRecoveryClient &&
                                                !uiRecoveryClient.cancelled && !connectCancelled && videoSender === sender &&
                                                sender?.isRunning == true && VirtualLauncher.isCurrentSession(generation) &&
                                                uiRecoveryClient.controllerInputRevision == revision
                                        fun dispatch(page: CarShortcut?, revision: Long?) {
                                            if (page == null || revision == null || sequence != shortcutSequence.get() || !ownsShortcut(revision)) return
                                            val queued = VirtualLauncher.submitShortcut(page) { ownsShortcut(revision) }
                                            appendLog("BMW-KEY: page=${page.name} queued=$queued")
                                        }
                                        fun resolveTarget(revision: Long?): CarShortcut? {
                                            if (target == null || revision == null || !ownsShortcut(revision)) return null
                                            return CarShortcutLearning.offer(context, generation, target.token, target.defaultPage)
                                        }
                                        // Learn when the event arrives. A cooldown for restoring video must not
                                        // swallow a second, distinct key while CarDeck still owns the screen.
                                        dispatch(resolveTarget(inputRevision), inputRevision)
                                        if (isBmw) {
                                            val kind = if (target == null) "other" else target.defaultPage?.name ?: "calibration_candidate"
                                            appendLog("BMW-KEY: requestUI target=$kind")
                                        }
                                        val paramsSummary = summarizeModeCommand(command)
                                        val decision = uiRecoveryPolicy.decide("requestUI", liveSession)
                                        if (decision.allowed) {
                                            // 排队期间若又发生倒车借屏或模式转换，旧请求不能恢复新状态。
                                            val modeRevision = uiRecoveryClient.captureUiRestoreRevision()
                                            // Control 请求可能等待响应，不能阻塞 EVENT 的后续读取和 ACK。
                                            Bg.run("bmw-request-ui") {
                                                try {
                                                    if (currentClient !== uiRecoveryClient ||
                                                        uiRecoveryClient.cancelled || connectCancelled
                                                    ) return@run
                                                    val expectedVideoSender = videoSender
                                                    val recovery = uiRecoveryClient.restoreRequestedUi(modeRevision, refreshLoan = bareReturn || target != null)
                                                    val stillLive = currentClient === uiRecoveryClient &&
                                                        !uiRecoveryClient.cancelled && !connectCancelled
                                                    val keyFrameRequested = stillLive && videoSender === expectedVideoSender &&
                                                        recovery.accepted &&
                                                        expectedVideoSender?.requestKeyFrame() == true
                                                    val mediaQueued = recovery.accepted && stillLive && expectedVideoSender != null && recovery.controllerRevision != null &&
                                                        dispatchReturnedMedia(uiRecoveryClient, expectedVideoSender, recovery.controllerRevision)
                                                    if (recovery.accepted && inputRevision == null && recovery.reason == "bmw_take_then_request_ui" &&
                                                        !mediaQueued && sequence == shortcutSequence.get()
                                                    ) {
                                                        val restoredRevision = recovery.controllerRevision
                                                        dispatch(resolveTarget(restoredRevision), restoredRevision)
                                                    }
                                                    appendLog(
                                                        "EXPERIMENT v13: BMW requestUI——滑动窗口恢复" +
                                                            " accepted=${recovery.accepted} reason=${recovery.reason}" +
                                                            " keyFrameRequested=$keyFrameRequested attemptsInWindow=${decision.attemptsInWindow}" +
                                                            " live=$stillLive $paramsSummary",
                                                    )
                                                } finally {
                                                    uiRecoveryPolicy.finishRecovery()
                                                }
                                            }
                                        } else {
                                            appendLog(
                                                "EVENT: requestUI 已应答；恢复暂缓 reason=${decision.reason}" +
                                                    " retryAfterMs=${decision.retryAfterMs ?: "none"}" +
                                                    " attemptsInWindow=${decision.attemptsInWindow} $paramsSummary",
                                            )
                                        }
                                    }

                                    "hidSendReport" -> {
                                        val uuid = command["uuid"]?.toString()
                                        val report = command["hidReport"] as? ByteArray
                                        val rotary = rotaryDevices[uuid]
                                        if (rotary != null && report != null) {
                                            val inputs = rotary.decode(report)
                                            val revision = uiRecoveryClient.controllerInputRevision
                                            if (inputs != null && revision != null) {
                                                inputs.forEach { input ->
                                                    CarControllerRouter.submit(input) {
                                                        currentClient === uiRecoveryClient && !uiRecoveryClient.cancelled &&
                                                            !connectCancelled && videoSender?.isRunning == true &&
                                                            uiRecoveryClient.controllerInputRevision == revision
                                                    }
                                                }
                                            }
                                            if (inputs == null || inputs.isNotEmpty()) {
                                                rotaryEvents++
                                                if (rotaryEvents <= 24 || rotaryEvents % 100 == 0) {
                                                    appendLog("ROTARY: report=${inputs ?: "invalid"} screenRevision=$revision")
                                                }
                                            }
                                            return@connectEventChannel
                                        }
                                        // 车机媒体键（Consumer Control）走 hidSendReport 通路，
                                        // 判定**以设备描述符为准**（不按 uuid、不按车机型号写死）：
                                        // 描述符声明了 Consumer 页(0x0C)的设备才当媒体键，报告字节是
                                        // Usage 数组下标（HID Array 语义）。触摸/旋钮/电话设备的描述符
                                        // 分别是 Digitizer/GenericDesktop/Telephony 页，一律不走这里——
                                        // DiPlay 的 knob(4B) 与 telephony(1B) 报告长度与媒体键重叠，
                                        // 只靠长度会误判（如 telephony usage 0xB3 会误发「快进」）。
                                        val descriptor = uuid?.let { setup.hidDescriptors[it] }
                                        val keyMap =
                                            descriptor?.let(ConsumerKeys::parseDescriptor)
                                        val rawValue =
                                            report
                                                ?.takeIf { it.isNotEmpty() }
                                                ?.let { it[0].toInt() and 0xFF }
                                        val consumerUsage =
                                            when {
                                                // 有描述符：只有 Consumer 设备才解析（非 Consumer → null）
                                                descriptor != null -> keyMap?.let { rawValue?.let(it::usageFor) }
                                                // 描述符缺失（未知车机）：保守回退——仅 1 字节报告，
                                                // 值当 Usage ID 直读
                                                report != null && report.size == 1 -> rawValue?.takeIf { it != 0 }
                                                else -> null
                                            }
                                        val contacts =
                                            if (consumerUsage == null && report != null) {
                                                // 触摸格式按设备描述符判定（Digitizer 页 + 单指/多槽），
                                                // 描述符不可用时按报文长度回退——不依赖车机型号或 uuid
                                                val touchFormat =
                                                    descriptor?.let(TouchReports::formatFromDescriptor)
                                                TouchReports.parse(report, touchFormat)
                                            } else {
                                                null
                                            }
                                        if (consumerUsage != null) {
                                            val keyCode = ConsumerKeys.keyCodeFor(consumerUsage)
                                            if (keyCode != 0) {
                                                dispatchMediaKey(keyCode)
                                                appendLog(
                                                    "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        "（uuid=$uuid usage=0x%02x → keyCode=$keyCode）"
                                                            .format(consumerUsage),
                                                )
                                            } else {
                                                appendLog(
                                                    "EVENT: 车机媒体键 ${ConsumerKeys.nameOf(consumerUsage)}" +
                                                        "（uuid=$uuid usage=0x%02x）无对应 Android 键，忽略"
                                                            .format(consumerUsage),
                                                )
                                            }
                                        } else if (keyMap != null && report != null) {
                                            // Consumer 设备的「无按键」/释放报文（值为 0 或下标越界）：
                                            // 静默计数，避免释放事件刷屏
                                            consumerReleaseCount++
                                        } else if (contacts != null) {
                                            if (touchLogCount <= 30 || touchLogCount % 50 == 0) {
                                                appendLog(
                                                    "EVENT: 触摸 " +
                                                        contacts.joinToString {
                                                            "#${it.slot}(${it.xPixels},${it.yPixels})${if (it.down) "↓" else "↑"}"
                                                        },
                                                )
                                            }
                                            touchLogCount++
                                            contacts.forEach {
                                                TouchRouter.onContact(
                                                    context.applicationContext,
                                                    it,
                                                )
                                            }
                                        } else if (report != null) {
                                            appendLog(
                                                "EVENT: 其他 HID 报告 uuid=$uuid bytes=${report.size}" +
                                                    " hex=${report.joinToString(" ") { "%02x".format(it) }}",
                                            )
                                        }
                                    }

                                    "iAPSendMessage" -> {
                                        // 事件通道上的 iAP2 虚拟链路字节（车机先发 marker 开场）。
                                        // 喂给链路引擎：握手 → EA 会话 → 触摸 HID。
                                        val params = command["params"] as? Map<*, *>
                                        val data = params?.get("data") as? ByteArray
                                        if (data == null) {
                                            appendLog("EVENT: iAPSendMessage 无 data（params=$params）")
                                        } else {
                                            iapEventMessageCount++
                                            client.onIapEventBytes(data)
                                        }
                                    }

                                    "forceKeyFrame" -> {
                                        // 车机解不出画面时会要 I 帧。必须立即响应：
                                        // 拖到下一个周期 I 帧（1s）车机可能一直卡在等帧状态。
                                        val forced = videoSender?.requestKeyFrame() == true
                                        appendLog(
                                            "EVENT: 车机请求关键帧（forceKeyFrame）→ " +
                                                if (forced) "已强制编码 I 帧" else "编码器未就绪，等周期 I 帧",
                                        )
                                    }

                                    "changeModes" -> {
                                        val liveSession = currentClient === uiRecoveryClient &&
                                            !uiRecoveryClient.cancelled && !connectCancelled
                                        if (liveSession) {
                                            // Revoke queued page opens as soon as a screen change arrives,
                                            // even if the control channel is still completing an earlier ACK.
                                            val resources = (command["params"] as? Map<*, *>)?.get("resources") as? List<*>
                                            if (resources == null || resources.any { ((it as? Map<*, *>)?.get("resourceID") as? Number)?.toLong() != 2L }) {
                                                shortcutScreenChanges.incrementAndGet()
                                            }
                                            appendLog("EVENT: 车机请求 changeModes → 按当前借用状态响应 ${summarizeModeCommand(command)}")
                                            val expectedVideoSender = videoSender
                                            val update = uiRecoveryClient.respondToModeChange(command)
                                            val stillLive = currentClient === uiRecoveryClient &&
                                                !uiRecoveryClient.cancelled && !connectCancelled
                                            val revision = update.returnedScreenRevision
                                            if (revision != null && stillLive && expectedVideoSender != null && videoSender === expectedVideoSender) {
                                                val forced = update.accepted && uiRecoveryClient.controllerInputRevision == revision &&
                                                    expectedVideoSender.requestKeyFrame()
                                                if (update.accepted) dispatchReturnedMedia(uiRecoveryClient, expectedVideoSender, revision)
                                                appendLog("BMW-SCREEN: 主屏已归还 revision=$revision accepted=${update.accepted} keyFrame=$forced")
                                                // The head unit can still be switching from native UI when the first IDR arrives.
                                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                                    Bg.run("bmw-returned-screen") {
                                                        refreshBmwScreen(uiRecoveryClient, expectedVideoSender, revision, userRequested = false)
                                                    }
                                                }, 600L)
                                            }
                                        }
                                    }

                                    else ->
                                        appendLog(
                                            // 未识别的命令必须带参数：车机的私有命令（如 requestUI）
                                            // 到底要什么，全在 params 里——只打 type 等于没打。
                                            "EVENT: 命令 type=${command["type"]} " +
                                                command.entries
                                                    .filter { it.key?.toString() != "type" }
                                                    .joinToString(" ") { (key, value) ->
                                                        "$key=" +
                                                            when (value) {
                                                                is ByteArray -> value.joinToString("") { "%02x".format(it) }
                                                                else -> value.toString().take(240)
                                                            }
                                                    }.take(600),
                                        )
                                }
                            }
                            // 资源声明（must）：真车对未声明资源的屏幕流 SETUP 直接 400
                            client.assertModes()
                            // HID 输入模式武装：/info hidDevices（奥迪字符串 "0"/"1"，触摸="1"）。
                            // display features=0x08（高保真触摸）已声明，缺这步车机不发 hidSendReport。
                            setup.hidDeviceUuids.forEach { uuid ->
                                client.sendHidSetInputMode(uuid, mode = 0)
                            }
                            val screen = client.runScreenStream(sessionKey, setup.displayUuid)
                            requireAttempt()
                            if (screen != null) {
                                // 地址取 client 真正连通的那个（配对期间可能被 mDNS 刷新过）：
                                // 视频数据端口与 keepAlive 都按它建 socket；IPv6 端点也算
                                // 可用地址（EndpointHosts 统一排序：IPv4 → 全局 IPv6 → 链路本地 IPv6）。
                                val host = client.activeHost ?: EndpointHosts.candidates(client.currentEndpoint.ipAddresses).firstOrNull()
                                // 心跳双保险（缺失任一都会被车机 ~10s 看门狗拆会话，2026-10-11 实测）：
                                //   keepAlive UDP ping（车机 SETUP 返回的 keepAlivePort，10s 周期）
                                //   + RTSP POST /feedback（1s 周期，catplay FEEDBACK_INTERVAL）
                                if (host != null && setup.keepAlivePort != null) {
                                    client.startKeepAlive(host, setup.keepAlivePort!!)
                                }
                                client.startFeedbackLoop()
                                // iAP 隧道（type-130）：车机→手机的 iAP2 EA 数据（触摸 HID 载体）。
                                // 建立失败不阻塞会话（触摸不可用但视频照常）。
                                val tunnel = client.runIapTunnel(sessionKey)
                                tunnel?.onIapMessage = { frame ->
                                    // 触摸证据采集：先全量记录，格式确认后接 TouchRouter
                                    if (frame.messageId != 0x0000) {
                                        val hex = frame.body.joinToString(" ") { "%02x".format(it) }
                                        appendLog(
                                            "TUNNEL: rx 0x${frame.messageId.toString(16)} " +
                                                "(${Iap2MessageIds.nameOf(
                                                    frame.messageId,
                                                )}) ${frame.body.size}B" +
                                                if (frame.body.isNotEmpty()) " hex=${hex.take(120)}" else "",
                                        )
                                    }
                                }
                                // 关键时序（2026-10-17 奥迪定谳）：屏幕 SETUP 后必须尽快连上
                                // dataPort——车机有 ~2s 看门狗。音频 SETUP 实测要 ~2.1s，
                                // 若排在前面，端口连接迟到即触发车机 reset 控制通道
                                // （日志特征：`音频 SETUP 往返 2091ms` 与 `EVENT 读取结束
                                // - Connection reset` 同一毫秒）。故先建 VideoSender 并
                                // openDataPort()，再协商音频，最后 RECORD + 启动编码器。
                                var pendingVideoW = 0
                                var pendingVideoH = 0
                                var pendingVideoDensity = 0
                                if (host != null) {
                                    // 「显示」偏好：流画布/虚拟屏按车机分辨率百分比生成。
                                    // 只在会话建立时生效——编码器与虚拟屏尺寸此时定型；
                                    // 密度同步缩放，逻辑布局（dp）与 100% 完全一致。
                                    val scalePct =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .scalePercent(it)
                                            } ?: 100
                                    val scale = scalePct / 100f
                                    // 缩放后向下对齐偶数（H.264 要求）：虚拟屏/合成器/编码器
                                    // 三方必须同一尺寸，故在源头对齐（VideoSender 内另有兜底）
                                    val streamW =
                                        ((setup.displayWidth * scale).toInt().coerceAtLeast(320) / 2) * 2
                                    val streamH =
                                        ((setup.displayHeight * scale).toInt().coerceAtLeast(180) / 2) * 2
                                    val fps =
                                        appContext
                                            ?.let {
                                                DisplayPrefs
                                                    .fps(it)
                                            } ?: 30
                                    synchronized(wifiBindLock) {
                                        requireAttempt()
                                        activeScalePercent = scalePct
                                        activeTargetFps = fps
                                        // 车机触摸在通告视图坐标系（如 1160×540），流已按比例
                                        // 缩放（如 1450×674）——注入前需映射，否则点击漂移
                                        TouchRouter
                                            .setCoordinateScale(
                                                setup.displayWidth,
                                                setup.displayHeight,
                                                streamW,
                                                streamH,
                                            )
                                    }
                                    appendLog(
                                        "DISPLAY: 流 ${setup.displayWidth}x${setup.displayHeight} → " +
                                            "${streamW}x$streamH（$scalePct%）@${fps}fps",
                                    )
                                    pendingVideoW = streamW
                                    pendingVideoH = streamH
                                    // dock 屏密度随画布短边等比（设计基准 1160×540@420dpi，
                                    // 见 design/launcher-proto.html：dock 48dp=126px）：高分辨率
                                    // 车机（测试台 2320×1080=2×）下 dock 文字/图标与奥迪同比例
                                    // 放大——此前固定 420dpi，dock 相对画布减半显小（2026-10-17）。
                                    // 上限 4×（4K 级画布）。
                                    pendingVideoDensity =
                                        (420f * minOf(streamW, streamH) / 540f)
                                            .toInt()
                                            .coerceIn(120, 1680)
                                    sessionVideoSender =
                                        VideoSender(
                                            host = host,
                                            dataPort = screen.dataPort,
                                            frameCipher = screen.cipher,
                                            width = streamW,
                                            height = streamH,
                                            targetFps = fps,
                                            log = { line -> appendLog(line) },
                                            keepAliveWithStats = setup.keepAliveSendStatsAsBody,
                                            onEnded = ended@{
                                                synchronized(wifiBindLock) {
                                                    if (connectCancelled || client.cancelled || !sessionAttempts.active(attempt) ||
                                                        currentClient !== client || videoSender !== sessionVideoSender) return@ended
                                                    // 会话真实结束：解除静默降噪（客户端恢复探测/重连）
                                                    appendLog(">>> CarPlay 会话结束（推流终止）——恢复蓝牙探测")
                                                    appContext?.let { io.github.shaonialife.cardeck.net.ProjectionAppMobileData.stop(it, VirtualLauncher.sessionGeneration()) }
                                                    attributeAudioTeardown()
                                                    if (isBmw) {
                                                        endBmwStream(attempt, wifiEpoch, endpoint, receiverAtStart)
                                                        return@ended
                                                    }
                                                    BtSessionGate.sessionActive = false
                                                    // 车机掉线类结束（无用户断开操作）：暗幕必须收掉。
                                                    // 这条路径不经过 retireSession（VirtualLauncher 仍挂着），
                                                    // 漏掉它就会出现「会话早就断了、45s 后手机屏突然变黑」
                                                    // （2026-10-19 用户实测）。
                                                    ScreenDimmer.setSessionActive(false)
                                                    // 同样复原横屏覆盖。这里跑在推流收尾线程，不是连接
                                                    // 关键路径，但统一走异步口以保持「串行、不重入」的语义。
                                                    appContext?.let { ctx ->
                                                        AppOrientationCompat.resetAllAsync(ctx, ::appendLog)
                                                    }
                                                }
                                            },
                                        )
                                    synchronized(wifiBindLock) {
                                        requireAttempt()
                                        videoSender = sessionVideoSender
                                        if (isBmw && receiverAtStart != null) {
                                            reconnectBorrowedScreen = reconnect@{ old, revision ->
                                                synchronized(wifiBindLock) {
                                                    if (old !== uiRecoveryClient || currentClient !== old || connectCancelled ||
                                                        videoSender !== sessionVideoSender || !wifiJoins.isCurrent(wifiEpoch) ||
                                                        selectedCtrlReceiver() != receiverAtStart || ctrlAdvertiser == null ||
                                                        !sessionAttempts.matches(attempt, wifiConnector?.currentNetwork) ||
                                                        !old.cancelForScreenReconnect(revision)
                                                    ) return@reconnect
                                                    appendLog("BMW-SCREEN: 用户确认重新连接投屏，保留车机 Wi-Fi，等待新的连接邀请")
                                                    io.github.shaonialife.cardeck.net.ProjectionAppMobileData.stop(context, VirtualLauncher.sessionGeneration())
                                                    endBmwStream(attempt, wifiEpoch, endpoint, receiverAtStart)
                                                    phaseHint = "等待宝马重新邀请，可在 iDrive 点选这部手机连接"
                                                }
                                            }
                                        }
                                    }
                                    // 先连端口（车机看门狗），编码器等 RECORD 后再启动
                                    sessionVideoSender?.openDataPort()
                                }
                                // 先 RECORD 激活会话并起视频，再协商音频（2026-10-17 奥迪定谳）：
                                // 车机在「屏幕流 SETUP 之后」有约 2s 看门狗，等不到 RECORD/数据流即
                                // 拆掉整轮会话。音频 SETUP 实测本身要 ~2.05-2.16s（车机侧音频通路
                                // 初始化），排在 RECORD 之前必然超时——日志里 reset 与音频 SETUP
                                // 返回同一毫秒、且 5 次实测全部复现。故顺序改为：
                                //   屏幕 SETUP → 连数据端口 → RECORD → 起编码器（出帧）→ 音频 SETUP
                                // 音频 SETUP 失败只丢声音，视频会话保持。
                                requireAttempt()
                                if (!client.record()) error("车机未接受 RECORD，本轮不启动画面")
                                requireAttempt()
                                if (host != null) {
                                    val videoW = pendingVideoW
                                    val videoH = pendingVideoH
                                    val videoDensity = pendingVideoDensity
                                    synchronized(wifiBindLock) {
                                        requireAttempt()
                                        sessionVideoSender?.start { surface ->
                                            requireAttempt()
                                            // 唯一模式：隐形屏（双屏启动器：dock + 应用屏）
                                            val source =
                                                HiddenDisplaySource(
                                                    context.applicationContext,
                                                    independentControllerFocus = rotaryDevices.isNotEmpty() && uiRecoveryClient.receiverIdentity().trim().equals("BMW Group MGU-50-P", ignoreCase = true),
                                                    log = { line -> appendLog(line) },
                                                )
                                            currentHiddenSource = source
                                            val ok =
                                                source.attach(
                                                    surface,
                                                    videoW,
                                                    videoH,
                                                    videoDensity,
                                                )
                                            appendLog(
                                                "MODE: 隐形屏 ${if (ok) "已启动（高德车机版优先）display=${source.displayId}" else "启动失败（Shizuku 就绪？）"}",
                                            )
                                            // 暗幕/常亮只在真正开始投屏后生效（见 ScreenDimmer.sessionActive）：
                                            // 应用启动时只挂触摸探针，避免「会话早断了、手机过一会儿还是变暗」。
                                            requireAttempt()
                                            if (ok) {
                                                if (isBmw) CarShortcutLearning.onProjectionStarted(context, VirtualLauncher.sessionGeneration())
                                                ScreenDimmer.setSessionActive(true)
                                            }
                                        }
                                    }
                                }
                                // 音频通道内部统一 SETUP → RECORD → 捕获/同步，失败保留视频。
                                requireAttempt()
                                startAudioChannel(context, client, sessionKey, host)
                            }
                        } else {
                            // 会话级 SETUP 被拒（典型：接收端按设备过滤回 403，见
                            // AirPlayClient.runSetup 的 deviceID 说明）。主动关闭控制通道：
                            // 否则接收端要等约 45s 视频超时才拆 BT 链路，用户只看到
                            // 「连上之后自动断开」，无从判断是会话建立失败。
                            phaseHint = "接收端拒绝了会话建立（SETUP 未通过）"
                            appendLog("AIRPLAY: 会话建立失败——主动关闭控制通道，本轮结束")
                            uiHintSink?.invoke("接收端拒绝了会话建立（SETUP）——请查看日志中的 deviceID 提示")
                            runCatching { client.close() }
                        }
                    }
                } catch (error: Throwable) {
                    // 配对线程任何异常都不能留下「已有会话在处理中」的卡死状态
                    appendLog("AIRPLAY: 配对线程异常 - ${error.message ?: error.javaClass.simpleName}")
                } finally {
                    synchronized(wifiBindLock) {
                        // 旧 worker 的迟到 finally 不得释放新一轮门闸或清理新虚拟屏。
                        if (sessionAttempts.owns(attempt)) {
                            if (!sessionAttempts.matches(attempt, wifiConnector?.currentNetwork) || videoSender?.isRunning != true) {
                                sessionAttempts.cancel()
                                retireSessionResources()
                            }
                            finishStart()
                            // 配对未成功建立推流（fail/异常/start 失败提前退出）时解除静默降噪，
                            // 否则 BT-C 会永久停在门闸外、连真车路径失效。
                            // 推流活跃时保持关闭，由 VideoSender.onEnded（推流终止）负责解除。
                            if (videoSender?.isRunning != true) {
                                BtSessionGate.sessionActive = false
                                // 收尾竞态：晚到 0x4301 若恰好在「配对已失败、互斥尚未释放」的
                                // 窗口内到达，会被暂存但没人消费。此处补投一次——车机等 15s
                                // 无人连入就拆链，错过这一投就是整轮失败（2026-10-18 分析）。
                                val pending = pendingEndpointUpdate
                                if (pending != null && !connectCancelled && recovery == null) {
                                    pendingEndpointUpdate = null
                                    appendLog(">>> 收尾时发现暂存的晚到 0x4301——立即按新端点重走一轮")
                                    Bg.run("late-endpoint-retry") {
                                        onCarPlayEndpoint(pending, "0x4301 晚到（本轮收尾补投）", isCurrent)
                                    }
                                }
                            }
                        }
                        // openDataPort 可在失联清理后才返回：仍须关闭本 worker 的局部对象，
                        // 不能仅靠全局 sender（它可能已被置空或由下一轮替换）。
                        if (!sessionAttempts.active(attempt) || videoSender !== sessionVideoSender) {
                            sessionVideoSender?.stop()
                        }
                        if (!sessionAttempts.active(attempt) || currentClient !== ownedClient) {
                            ownedClient?.cancelled = true
                            ownedClient?.close()
                        }
                    }
                }
            }
        }
        val wifiConfig = Iap2SenderSession.WifiConfiguration(
            endpoint.ssid, endpoint.passphrase, endpoint.channel, endpoint.securityType,
        )
        Bg.run("wifi-join-on-endpoint") {
            if (!attemptActive()) {
                finishStart()
                return@run
            }
            val bound = if (recovery == null) ensureCarWifi(wifiConfig, wifiEpoch)
                else wifiConnector?.validatedCurrentNetwork() == recovery.network
            if (attemptActive()) continueWithNetwork(bound) else finishStart()
        }
    }

    /** 触摸/隧道事件低频计数（迁移自 MainActivity 字段，保持日志节流行为一致）。 */
    @Volatile
    private var touchLogCount = 0

    /** 车机媒体键「释放」报文计数（值为 0 的 1 字节报告，静默丢弃，仅计数备查）。 */
    @Volatile
    private var consumerReleaseCount = 0

    @Volatile
    private var iapEventMessageCount = 0
}

/** 配对失败后的兜底路径决策（纯函数，真机抓包场景由 PairingFallbackTest 钉死）。 */
internal data class PairingFallback(
    /** 「断 BT 复现控制结束再试」二段 hack（仅测试台 DiPlay 形态）。 */
    val useBtRetireHack: Boolean,
    /** et=NONE 明文会话降级（pre-HomeKit 老车机 / 配对全拒但 RTSP 连上过的兜底）。 */
    val usePlainSession: Boolean,
)

/**
 * 决策依据（2026-10-21 真机 sysdiagnose，docs/PROTOCOL-NOTES.md §13a）：
 * 任意阶段 403 = 「iAP2 信任已建立的车机在等自身就绪」——首连被拒是车机固有状态，
 * 它 18~20s 后自动重邀、第二次即成。此状态下断 BT（整栈重建 30~40s，恰好错过重邀
 * 窗口）与明文降级（必被拒且空耗重邀窗口）都是反向操作，两条兜底一律禁用；
 * 配对循环以 5s 退避在窗口内原地重试（见 pairingRetryBackoffMs）。
 */
internal fun pairingFallback(
    keysEstablished: Boolean,
    noPairing: Boolean,
    pairSetupRejected: Boolean,
    pairingForbidden: Boolean,
    attemptedPairing: Boolean,
    pairingUnsupported: Boolean,
): PairingFallback {
    if (keysEstablished) return PairingFallback(useBtRetireHack = false, usePlainSession = false)
    val useBtRetireHack = !noPairing && !pairSetupRejected && !pairingForbidden && attemptedPairing
    val usePlainSession = !pairingForbidden && (pairingUnsupported || attemptedPairing)
    return PairingFallback(useBtRetireHack = useBtRetireHack, usePlainSession = usePlainSession)
}
