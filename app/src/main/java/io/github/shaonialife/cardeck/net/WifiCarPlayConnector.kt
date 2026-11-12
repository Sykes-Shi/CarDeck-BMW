package io.github.shaonialife.cardeck.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiInfo
import android.os.SystemClock
import io.github.shaonialife.cardeck.util.Bg
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用车机热点承载 CarPlay 流量。
 *
 * **两种入网方式，默认应用专属网络**：
 *  - [joinCarPlayNetwork]：`WifiNetworkSpecifier` 为本应用请求车机 WiFi，
 *    然后只绑定 CarDeck 进程。其它应用是否通过蜂窝网络或 VPN 联网，
 *    仍由系统和各应用选择，不能由本次请求成功推断。
 *  - [bindCurrentWifi]：已核对 SSID 后绑定指定的系统 Wi-Fi，拒绝其它网络回调。
 *
 * 关键点：
 *  - 请求不带 `NET_CAPABILITY_INTERNET`，系统不会因「无互联网」拆连接；
 *  - 应用专属网络必须 `bindProcessToNetwork` 后再建 socket / 注册 mDNS；
 *  - 首次连接会弹系统确认框；SSID 必须精确匹配（热点名可能带空格/中文）。
 */
class WifiCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** 应用专属网络的连接结果。 */
    sealed interface JoinResult {
        /** 已连接并绑定到本进程；[network] 可用于 NSD/端点判断。 */
        data class Joined(
            val network: Network,
        ) : JoinResult

        /** 系统未允许（用户拒绝/热点不在范围/超时）。可重试。 */
        data object Unavailable : JoinResult

        /** 该安全类型无法用 WifiNetworkSpecifier 表达（WEP）——调用方走旧路径。 */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // 注册与释放也须串行，否则取消可能先注销、随后 requestNetwork 才注册，遗留请求。
    // 本锁内只提交/注销请求，绝不等待网络回调。
    private val requestLock = Any()
    private val binding = OwnedNetworkBinding<ConnectivityManager.NetworkCallback, Network>(
        bind = { network -> runCatching { connectivityManager.bindProcessToNetwork(network) }.getOrDefault(false) },
        unbind = { network ->
            // 只解除本组件持有的绑定，包括已经失效的 Network；不解除其它组件的新绑定。
            runCatching {
                if (connectivityManager.boundNetworkForProcess == network) {
                    connectivityManager.bindProcessToNetwork(null)
                }
            }
            Unit
        },
        isLive = { network ->
            runCatching {
                connectivityManager.boundNetworkForProcess == network &&
                    connectivityManager.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(false)
        },
    )

    /** 廉价快照；准备复用连接的调用方须在后台校验网络存活。 */
    val currentNetwork: Network? get() = binding.current()

    /** 不要求 INTERNET/VALIDATED：车机网络本来就可以没有互联网。 */
    fun validatedCurrentNetwork(): Network? {
        val previous = binding.current()
        val current = binding.validatedCurrent()
        if (previous != null && current == null) {
            log("WiFi: 缓存的车机网络已失效——清理旧绑定，等待重新加入")
        }
        return current
    }

    class NetworkLoss internal constructor(
        val ssid: String,
        val network: Network,
        internal val owner: ConnectivityManager.NetworkCallback,
        internal val revision: Long,
    )

    /**
     * 在后台投递失联事件，不阻塞 Android 网络回调。会话层必须在发布新网络的同一把
     * 锁内再次调用 [isCurrentLoss]，通过后才清理会话状态；不得在网络回调线程等待该锁。
     */
    @Volatile
    var onNetworkLost: ((NetworkLoss) -> Unit)? = null

    fun isCurrentLoss(loss: NetworkLoss): Boolean = binding.isCurrentLoss(loss.owner, loss.revision)

    private fun notifyNetworkLost(owner: ConnectivityManager.NetworkCallback, network: Network, ssid: String) {
        val revision = binding.recordLoss(owner, network) ?: return
        log("WiFi: 当前请求已失去车机网络（$network），重新核验连接；不据此判断物理 WiFi 是否断开")
        val loss = NetworkLoss(ssid, network, owner, revision)
        Bg.run("wifi-network-lost") {
            if (isCurrentLoss(loss)) onNetworkLost?.invoke(loss)
        }
    }

    /** 后台读取；不记录 SSID/地址。系统隐藏 SSID 且可见多条 Wi-Fi 时不猜测。 */
    fun connectedNetwork(expectedSsid: String, confirmedSystemSsid: String?, allowSystemWifi: Boolean): Network? = runCatching {
        @Suppress("DEPRECATION")
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) return@mapNotNull null
            val raw = (capabilities.transportInfo as? WifiInfo)?.ssid
            val ssid = raw?.removeSurrounding("\"")?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
            ConnectedWifiSelection.Candidate(network, ssid, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        }
        ConnectedWifiSelection.select(expectedSsid, confirmedSystemSsid, candidates, allowSystemWifi)
    }.getOrNull()

    /** A shared association is a usable fast path when the phone already has a separate uplink.
     * If the platform hides the VPN underlay, ambiguous Wi-Fi still goes through the local-only request. */
    @Suppress("DEPRECATION")
    fun hasIndependentInternet(car: Network, beforeDedicatedRequest: Boolean = false): Boolean = runCatching {
        val cm = connectivityManager
        val active = cm.activeNetwork ?: return false
        if (active != car && cm.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true &&
            ConnectedWifiSelection.sameLink(cm.getLinkProperties(car), cm.getLinkProperties(active))) return false
        fun route(network: Network): ConnectedWifiSelection.Upstream<Network>? {
            val c = cm.getNetworkCapabilities(network) ?: return null
            val vpn = c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            return ConnectedWifiSelection.Upstream(network,
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                vpn, c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
        val visible = cm.allNetworks.mapNotNull(::route)
        ConnectedWifiSelection.independentInternet(car, visible.firstOrNull { it.network == active }, visible, beforeDedicatedRequest) &&
            cm.activeNetwork == active
    }.getOrDefault(false)

    /**
     * 当前绑定网络的网关地址（车机热点场景 = 车机 IP）。
     * mDNS 发现失败时的兜底路径。
     */
    fun gatewayAddresses(): List<String> {
        val network = currentNetwork ?: return emptyList()
        return try {
            EndpointHosts.gatewayCandidates(
                connectivityManager.getLinkProperties(network)?.routes
                    ?.filter { it.isDefaultRoute }
                    ?.mapNotNull { it.gateway?.hostAddress }
                    .orEmpty(),
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 本机在当前绑定网络上的 IPv4 地址（如 10.174.189.57）。
     * 用于 mDNS 结果过滤：手机双归属（连车机热点 + 自己开热点共享给电脑）时，
     * mDNS 会跨网发现电脑的 AirPlay 接收器——非本网段地址一律不是车机。
     */
    fun localIpv4Address(): String? {
        val network = currentNetwork ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.firstOrNull { it.contains('.') && !it.contains(':') }
                ?.substringBefore('/')
        } catch (_: Exception) {
            null
        }
    }

    /** Correlate the incoming ctrl socket with a specific Android Network before binding it. */
    fun hasLocalAddress(network: Network, address: String): Boolean = runCatching {
        connectivityManager.getLinkProperties(network)?.linkAddresses?.any {
            it.address.hostAddress?.substringBefore('%') == address
        } == true
    }.getOrDefault(false)

    /**
     * 本机在当前绑定网络上的全部 IPv6 地址。
     *
     * mDNS 结果的网段验收用：奥迪 4xxx 的 `_airplay._tcp` 常只发链路本地 IPv6，
     * 旧过滤只按 IPv4 前缀判定，会把车机本身也当成「非本网段」丢掉（2026-10-19）。
     */
    fun localIpv6Addresses(): List<String> {
        val network = currentNetwork ?: return emptyList()
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.filter { it.contains(':') }
                ?.map { it.substringBefore('%').substringBefore('/') }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 用应用专属网络（WifiNetworkSpecifier）加入车机热点。
     *
     * 阻塞直到连接成功/系统拒绝/超时（内部 200ms 分片，[isCancelled] 可即时退出）。
     * 成功后进程网络已绑定，调用方可直接注册 mDNS / 建 socket。
     *
     * 安全类型映射按 0x5703（xcertplay Iap2WirelessSecurity）：
     * 0=NONE 1=WEP 2=WPA/WPA2 3=WPA3 过渡 4=WPA3 仅。
     * WEP 无对应 API（返回 [JoinResult.Unsupported]，调用方走旧路径）。
     */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        requireLocalOnly: Boolean,
        timeoutMillis: Long = 120_000L,
        isCancelled: () -> Boolean = { false },
        reuseInvitedWifi: () -> JoinResult.Joined? = { null },
    ): JoinResult {
        val specifier =
            buildSpecifier(ssid, passphrase, securityType)
                ?: return JoinResult.Unsupported
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
        val latch = CountDownLatch(1)
        val unavailable = AtomicBoolean(false)
        val sharedWifiReported = AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                // Android 10+ 保证首次 onAvailable 后投递能力。等它确认目标是本地 Wi-Fi，
                // 不以移除请求的 INTERNET 能力推断返回网络一定没有 INTERNET。
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (isCancelled() || !binding.isCurrent(this) || unavailable.get()) return
                    val systemWifi = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val previous = binding.current(this)
                    if (previous != null) {
                        if (previous != network) {
                            // 已建的媒体 socket 和 mDNS 都指向旧 handle，替换要重新握手。
                            unavailable.set(true)
                            notifyNetworkLost(this, previous, ssid)
                            latch.countDown()
                            return
                        }
                        if (systemWifi && sharedWifiReported.compareAndSet(false, true)) {
                            log("WiFi: 已连接的车机热点现被系统列为上网网络；若其它应用无网，请检查热点的自动连接")
                        }
                        return
                    }
                    if ((systemWifi && requireLocalOnly) || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    ) {
                        log("WiFi: 系统尚未提供独立的本地投屏网络；可重试或手动选择使用系统热点")
                        unavailable.set(true)
                        latch.countDown()
                        return
                    }
                    when (acceptNetwork(this, network, isCancelled)) {
                        OwnedNetworkBinding.BindResult.STALE -> return
                        OwnedNetworkBinding.BindResult.FAILED -> {
                            unavailable.set(true)
                            log("WiFi: 应用专属网络绑定失败——本轮未连接成功")
                        }
                        OwnedNetworkBinding.BindResult.BOUND -> {
                            val route = if (systemWifi) "系统车机热点" else "应用专属网络"
                            log("WiFi: 已加入$route \"$ssid\"（$network bindProcessToNetwork=true）")
                            logNetworkSnapshot(this, network, capabilities)
                        }
                    }
                    latch.countDown()
                }

                override fun onUnavailable() {
                    if (isCancelled() || !binding.isCurrent(this)) return
                    unavailable.set(true)
                    log("WiFi: \"$ssid\" 的本地网络请求未获满足")
                    latch.countDown()
                }

                override fun onLost(network: Network) {
                    if (binding.isBound(this, network)) unavailable.set(true)
                    notifyNetworkLost(this, network, ssid)
                }
            }
        val accepted = registerRequest(newCallback, isCancelled) { connectivityManager.requestNetwork(request, newCallback) }
        if (!accepted) {
            return JoinResult.Unavailable
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var nextInvitationCheck = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled() || !binding.isCurrent(newCallback)) {
                release(newCallback)
                return JoinResult.Unavailable
            }
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
                if (binding.current(newCallback) == null && SystemClock.uptimeMillis() >= nextInvitationCheck) {
                    // The MGU may have reached us on a separate system WLAN for which this
                    // specifier never receives onAvailable. Its owner is replaced on a valid handoff.
                    reuseInvitedWifi()?.let { return it }
                    nextInvitationCheck = SystemClock.uptimeMillis() + 750L
                }
            } catch (_: InterruptedException) {
                release(newCallback)
                return JoinResult.Unavailable
            }
        }
        // 使用首个能力回调已核验并绑定、且仍由本次请求持有的网络。
        val network = if (isCancelled() || unavailable.get()) null else binding.current(newCallback)
        if (network == null) {
            log(
                "WiFi: 未能完成加入 \"$ssid\"" +
                    if (unavailable.get()) "——未取得可用的本地投屏网络"
                    else if (SystemClock.uptimeMillis() >= deadline) "——等待超时（${timeoutMillis / 1000}s）"
                    else "——网络已丢失或请求已取消",
            )
            release(newCallback)
            return JoinResult.Unavailable
        }
        return JoinResult.Joined(network)
    }

    /** 构造 specifier；WEP 或参数不合法返回 null（走旧路径）。 */
    private fun buildSpecifier(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): WifiNetworkSpecifier? {
        return try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            when (securityType) {
                // 开放热点：无密码
                0 -> Unit
                // WEP：WifiNetworkSpecifier 无对应 API（现代车机不再使用）
                1 -> return null
                // WPA/WPA2 与 WPA3 过渡模式：过渡模式 AP 同时接受 WPA2-PSK
                2, 3 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
                // WPA3 仅
                4 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa3Passphrase(passphrase)
                }
                // 未知类型：按有密码的 WPA2 尝试（比直接放弃更可能成功）
                else -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
            }
            builder.build()
        } catch (error: Exception) {
            log("WiFi: 构造应用专属网络参数失败（${error.message}）")
            null
        }
    }

    /**
     * 绑定已核对 SSID 的指定 Wi-Fi（系统已自动或手动加入热点后的复用路径）。
     * 热点无互联网时系统可能不把它设为默认网络，必须显式 bindProcessToNetwork，
     * 我们的 socket 才会走 WiFi 而不是蜂窝。
     * 被动跟踪已识别的目标，不发起另一个通用 Wi-Fi 请求来影响系统选网。
     */
    fun bindCurrentWifi(
        expectedNetwork: Network,
        allowSystemWifi: Boolean,
        isCancelled: () -> Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        val request =
            NetworkRequest
                .Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        // 网络回调线程与 Bg 看门狗线程并发读写——原子标志保证可见性与单次投递
        val reported =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (network != expectedNetwork || reported.get() || isCancelled() || !binding.isCurrent(this)) return
                    if (!allowSystemWifi && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        log("WiFi: 目标已变为系统上网热点，继续申请本地投屏网络")
                        if (release(this) && reported.compareAndSet(false, true)) onResult(false)
                        return
                    }
                    val bound = when (acceptNetwork(this, network, isCancelled)) {
                        OwnedNetworkBinding.BindResult.STALE -> return
                        OwnedNetworkBinding.BindResult.FAILED -> false
                        OwnedNetworkBinding.BindResult.BOUND -> true
                    }
                    log(if (bound) "WiFi: 已绑定当前WiFi网络 $network bindProcessToNetwork=true" else "WiFi: 当前 WiFi 绑定失败——本轮未连接成功")
                    if (bound) logNetworkSnapshot(this, network, capabilities)
                    val stillOwned = if (bound) binding.isCurrent(this) else release(this)
                    if (stillOwned && !isCancelled() && reported.compareAndSet(false, true)) onResult(bound)
                }

                override fun onUnavailable() {
                    if (isCancelled()) return
                    if (!release(this)) return
                    log("WiFi: 没有可用的WiFi连接——请先在系统设置里加入接收端热点")
                    if (reported.compareAndSet(false, true)) onResult(false)
                }

                override fun onLost(network: Network) {
                    notifyNetworkLost(this, network, "")
                }
            }
        if (!registerRequest(newCallback, isCancelled) { connectivityManager.registerNetworkCallback(request, newCallback) }) {
            if (reported.compareAndSet(false, true)) onResult(false)
            return
        }
        // 5 秒后给等待提示，终止等待由调用方的 WIFI_BIND_WAIT_S 负责。
        Bg.run("wifi-bind-watchdog") {
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@run
            }
            if (!isCancelled() && binding.isCurrent(newCallback) && !reported.get()) {
                log("WiFi: 5 秒尚未绑定（手机可能还没连上热点）——请到 设置>WLAN 加入接收端热点，加入后本轮绑定会自动完成")
            }
        }
    }

    /**
     * 每次入网请求只记录一次。绑定网络的能力来自回调，其余查询移出 NetworkCallback；
     * activeNetwork 和可见网络仅是当前 app 视角，不能据此判定其它 app 的实际路由。
     * 不读地址、DNS、SSID 或完整 NetworkCapabilities，避免诊断日志夹带网络凭据。
     */
    private fun logNetworkSnapshot(
        owner: ConnectivityManager.NetworkCallback,
        expectedNetwork: Network,
        boundCapabilities: NetworkCapabilities,
    ) {
        // 提交失败或读取权限不足都只影响诊断，不影响已建立的连接。
        runCatching {
            val initialCapabilities = NetworkCapabilities(boundCapabilities)
            Bg.run("wifi-network-snapshot") {
                if (!binding.isBound(owner, expectedNetwork)) return@run
                val summary =
                    runCatching {
                        val active = connectivityManager.activeNetwork
                        val processBound = connectivityManager.boundNetworkForProcess
                        @Suppress("DEPRECATION")
                        val visible = connectivityManager.allNetworks.toList()
                        val networks = (visible + listOfNotNull(active, processBound, expectedNetwork)).distinct()
                        val capabilities =
                            networks.associateWith { network ->
                                if (network == expectedNetwork) initialCapabilities
                                else connectivityManager.getNetworkCapabilities(network)
                            }
                        val vpnObserved =
                            when {
                                capabilities.values.any { it?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } -> "yes"
                                capabilities.values.any { it == null } -> "unknown"
                                else -> "not_observed"
                            }
                        fun id(network: Network?): String = network?.networkHandle?.toString() ?: "none"
                        val details =
                            networks.joinToString("; ") { network ->
                                "${id(network)}:${networkCapabilitySummary(capabilities[network])}"
                            }
                        "WiFi: 网络快照（仅当前app视角）activeApp=${id(active)} " +
                            "boundProcess=${id(processBound)} requested=${id(expectedNetwork)} " +
                            "visibleVpn=$vpnObserved networks=[$details]"
                    }.getOrElse { error ->
                        "WiFi: 网络快照不可用（${error.javaClass.simpleName}）；连接流程不变"
                    }
                if (binding.isBound(owner, expectedNetwork)) log(summary)
            }
        }
    }

    private fun networkCapabilitySummary(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "capabilities=unavailable"
        val transports =
            listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
                NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
                NetworkCapabilities.TRANSPORT_VPN to "VPN",
                NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET",
                NetworkCapabilities.TRANSPORT_BLUETOOTH to "BLUETOOTH",
                NetworkCapabilities.TRANSPORT_WIFI_AWARE to "WIFI_AWARE",
                NetworkCapabilities.TRANSPORT_LOWPAN to "LOWPAN",
                NetworkCapabilities.TRANSPORT_USB to "USB",
            ).filter { capabilities.hasTransport(it.first) }.joinToString("+") { it.second }
        return "transport=${transports.ifEmpty { "OTHER" }} " +
            "INTERNET=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "VALIDATED=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
            "NOT_VPN=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)}"
    }

    fun release() {
        release(null)
    }

    private fun release(owner: ConnectivityManager.NetworkCallback?): Boolean =
        synchronized(requestLock) {
            val previous = binding.release(owner) ?: return@synchronized false
            runCatching { connectivityManager.unregisterNetworkCallback(previous) }
            true
        }

    private fun acceptNetwork(
        owner: ConnectivityManager.NetworkCallback,
        network: Network,
        isCancelled: () -> Boolean,
    ): OwnedNetworkBinding.BindResult = synchronized(requestLock) {
        if (isCancelled()) OwnedNetworkBinding.BindResult.STALE else binding.available(owner, network)
    }

    private fun registerRequest(
        owner: ConnectivityManager.NetworkCallback,
        isCancelled: () -> Boolean,
        register: () -> Unit,
    ): Boolean =
        synchronized(requestLock) {
            if (isCancelled()) return@synchronized false
            binding.replace(owner)?.let {
                runCatching { connectivityManager.unregisterNetworkCallback(it) }
            }
            try {
                register()
                if (isCancelled()) {
                    release(owner)
                    false
                } else true
            } catch (error: Exception) {
                binding.release(owner)
                runCatching { connectivityManager.unregisterNetworkCallback(owner) }
                log("WiFi: 发起网络请求失败（${error.javaClass.simpleName}）——请检查权限后重试")
                false
            }
        }
}
