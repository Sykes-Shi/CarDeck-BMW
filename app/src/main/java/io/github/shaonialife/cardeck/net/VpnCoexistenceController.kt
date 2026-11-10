package io.github.shaonialife.cardeck.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * 投屏期间保持蜂窝请求，供安卓维持上网出口。VPN 共存选项控制额外诊断和专用入网策略。
 * 这不会替 VPN 选择出口；能力日志也仅描述当前 app 可见状态。
 */
class VpnCoexistenceController internal constructor(
    private val backend: VpnCoexistenceBackend,
    private val log: (String) -> Unit,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    constructor(context: Context, log: (String) -> Unit) : this(
        AndroidVpnCoexistenceBackend(context.applicationContext),
        log,
    )

    private val lock = Any()
    private var generation = 0L
    private var running = false
    private var verbose = true
    private val blockedNetworks = mutableSetOf<Long>()
    private val registrations = mutableListOf<VpnCoexistenceBackend.Registration>()
    private val previousEvents = LinkedHashMap<String, CoexistenceNetworkEvent>()
    private var windowStartedAt = 0L
    private var emittedInWindow = 0
    private var limitReported = false

    fun isBlocked(handle: Long): Boolean = synchronized(lock) { handle in blockedNetworks }

    fun start(detailedDiagnostics: Boolean = true) {
        val token = synchronized(lock) {
            if (running) return
            running = true
            verbose = detailedDiagnostics
            generation++
            previousEvents.clear()
            windowStartedAt = monotonicMillis()
            emittedInWindow = 0
            limitReported = false
            generation
        }
        try {
            if (!register(token) { backend.observeNetworks { onEvent(token, "visibleApp", it) } }) return
            if (!register(token) { backend.observeDefault { onEvent(token, "defaultApp", it) } }) return
            if (!register(token) { backend.requestCellular { onEvent(token, "cellularRequest", it) } }) return
            synchronized(lock) {
                if (isCurrent(token)) {
                    writeLog("联网: 已申请投屏期间保持移动数据可用，VPN 与各应用继续遵循系统分流")
                }
            }
        } catch (error: Exception) {
            stopGeneration(token, "启动失败（${error.javaClass.simpleName}），已撤销本轮请求；未保持蜂窝网络")
        }
    }

    fun stop() {
        stopGeneration(null, "已释放蜂窝保持请求和网络观察")
    }

    /** 注册期间 stop 也能生效；晚返回的句柄由此处立即释放，不写回新一轮。 */
    private fun register(token: Long, factory: () -> VpnCoexistenceBackend.Registration): Boolean {
        synchronized(lock) { if (!isCurrent(token)) return false }
        val registration = factory()
        val accepted = synchronized(lock) {
            if (isCurrent(token)) {
                registrations.add(registration)
                true
            } else {
                false
            }
        }
        if (!accepted) closeSafely(registration)
        return accepted
    }

    private fun onEvent(token: Long, source: String, event: CoexistenceNetworkEvent) {
        if (source == "cellularRequest" && event.kind == "unavailable") {
            stopGeneration(token, "蜂窝请求不可用，已释放本轮请求；可重新启动此模式重试")
            return
        }
        synchronized(lock) {
            if (!isCurrent(token)) return
            // The all-network listener sees blocked state for this UID, including on cellular.
            // Track it even with diagnostics disabled or the logging budget exhausted.
            if (source == "visibleApp" && event.networkId != null) {
                if (event.blocked == true) blockedNetworks.add(event.networkId)
                if (event.blocked == false || event.kind == "lost") blockedNetworks.remove(event.networkId)
            }
            if (!verbose) return
            // 能力使用回调参数，无同步 ConnectivityManager 查询；不输出地址/DNS/SSID。
            val key = "$source:${event.networkId}:${event.kind}"
            if (previousEvents[key] == event) return
            val now = monotonicMillis()
            if (now - windowStartedAt >= 60_000L || now < windowStartedAt) {
                windowStartedAt = now
                emittedInWindow = 0
                limitReported = false
            }
            // 滚动时段预算不会耗尽整个会话额度，下一时段的 VPN 开关仍可被观察。
            if (emittedInWindow >= 40) {
                if (!limitReported) {
                    limitReported = true
                    writeLog("VPN共存: 网络变化较频繁，本时段诊断已限量；后续时段继续记录")
                }
                return
            }
            previousEvents[key] = event
            while (previousEvents.size > 128) previousEvents.remove(previousEvents.keys.first())
            if (event.kind == "available") previousEvents.remove("$source:${event.networkId}:lost")
            if (event.kind == "lost") {
                previousEvents.keys.removeAll { it.startsWith("$source:${event.networkId}:") && it != key }
            }
            emittedInWindow++
            writeLog("VPN共存: 当前app视角 $source ${event.summary}")
        }
    }

    private fun stopGeneration(expected: Long?, reason: String) {
        val closing = synchronized(lock) {
            if (!running || (expected != null && generation != expected)) return
            running = false
            blockedNetworks.clear()
            generation++
            previousEvents.clear()
            registrations.toList().also { registrations.clear() }
        }
        closing.forEach(::closeSafely)
        writeLog("VPN共存: $reason")
    }

    private fun isCurrent(token: Long): Boolean = running && generation == token

    private fun closeSafely(registration: VpnCoexistenceBackend.Registration) {
        runCatching { registration.close() }.onFailure {
            writeLog("VPN共存: 注销回调异常（${it.javaClass.simpleName}）")
        }
    }

    private fun writeLog(message: String) {
        runCatching { log(message) }
    }
}

internal data class CoexistenceNetworkEvent(
    val kind: String,
    val networkId: Long? = null,
    val summary: String = kind,
    val blocked: Boolean? = null,
)

/** Android 适配边界，生命周期测试无需真实网络或 Android 服务。 */
internal interface VpnCoexistenceBackend {
    fun interface Registration {
        fun close()
    }

    fun requestCellular(listener: (CoexistenceNetworkEvent) -> Unit): Registration

    fun observeNetworks(listener: (CoexistenceNetworkEvent) -> Unit): Registration

    fun observeDefault(listener: (CoexistenceNetworkEvent) -> Unit): Registration
}

private class AndroidVpnCoexistenceBackend(context: Context) : VpnCoexistenceBackend {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun requestCellular(listener: (CoexistenceNetworkEvent) -> Unit): VpnCoexistenceBackend.Registration =
        register(listener) { callback ->
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity.requestNetwork(request, callback)
        }

    override fun observeNetworks(listener: (CoexistenceNetworkEvent) -> Unit): VpnCoexistenceBackend.Registration =
        register(listener) { callback ->
            // 清除默认 NOT_VPN 等限制，才能观察 VPN 和不提供互联网的车机 WiFi。
            val request = NetworkRequest.Builder().clearCapabilities().build()
            connectivity.registerNetworkCallback(request, callback)
        }

    override fun observeDefault(listener: (CoexistenceNetworkEvent) -> Unit): VpnCoexistenceBackend.Registration =
        register(listener) { callback -> connectivity.registerDefaultNetworkCallback(callback) }

    private fun register(
        listener: (CoexistenceNetworkEvent) -> Unit,
        action: (ConnectivityManager.NetworkCallback) -> Unit,
    ): VpnCoexistenceBackend.Registration {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                listener(CoexistenceNetworkEvent("available", network.networkHandle, "network=${network.networkHandle} available"))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val transports = listOf(
                    NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
                    NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
                    NetworkCapabilities.TRANSPORT_VPN to "VPN",
                    NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET",
                    NetworkCapabilities.TRANSPORT_USB to "USB",
                ).filter { capabilities.hasTransport(it.first) }.joinToString("+") { it.second }.ifEmpty { "OTHER" }
                val summary = "network=${network.networkHandle} transport=$transports " +
                    "INTERNET=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                    "VALIDATED=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                    "NOT_VPN=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}"
                listener(CoexistenceNetworkEvent("capabilities", network.networkHandle, summary))
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                listener(CoexistenceNetworkEvent("blocked", network.networkHandle, "network=${network.networkHandle} blocked=$blocked", blocked))
            }

            override fun onLost(network: Network) {
                listener(CoexistenceNetworkEvent("lost", network.networkHandle, "network=${network.networkHandle} lost"))
            }

            override fun onUnavailable() {
                listener(CoexistenceNetworkEvent("unavailable"))
            }
        }
        try {
            action(callback)
        } catch (error: Exception) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            throw error
        }
        return VpnCoexistenceBackend.Registration { connectivity.unregisterNetworkCallback(callback) }
    }
}
