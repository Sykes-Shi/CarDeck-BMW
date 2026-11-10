package io.github.shaonialife.cardeck.net

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** Discovers the receiver's AirPlay service on the currently bound car network. */
class CarPlayServiceBrowser(
    context: Context,
    private val log: (String) -> Unit,
    private val accept: (NsdServiceInfo) -> Boolean = { true },
    private val onEndpoint: (NsdServiceInfo) -> Unit = {},
) {
    companion object {
        const val AIRPLAY_TYPE = "_airplay._tcp"
        const val CARPLAY_CTRL_TYPE = "_carplay-ctrl._tcp"
        private const val TAG = "NSD"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val cache = ScopedServiceCache<NsdServiceInfo>()
    private var activeListener: NsdManager.DiscoveryListener? = null
    private var discoveryGeneration = -1L

    /** Same-network reconnects keep the discovered address; changed/unknown scopes start fresh. */
    @Synchronized
    fun ensureForNetwork(network: Network?, ssid: String, targetAddress: String?) {
        val scope = if (network != null && ssid.isNotBlank() && !targetAddress.isNullOrBlank()) {
            ScopedServiceCache.Scope(network.networkHandle, ssid, targetAddress.uppercase())
        } else {
            null
        }
        val generation = cache.selectScope(scope)
        if (activeListener != null && discoveryGeneration == generation) {
            log("$TAG: 同一车机网络，保留已有发现结果并继续扫描")
            return
        }
        stopDiscovery()
        discoveryGeneration = generation
        val listener = createDiscoveryListener(generation)
        activeListener = listener
        log("$TAG: 开始发现 $AIRPLAY_TYPE（新的网络/车辆范围）")
        try {
            nsdManager.discoverServices(AIRPLAY_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: RuntimeException) {
            activeListener = null
            cache.clear()
            log("$TAG: 发现启动失败 ${error.message}")
        }
    }

    fun awaitEndpoint(timeoutMillis: Long): NsdServiceInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            cache.get()?.let { return it }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return cache.get()
            }
        }
        return cache.get()
    }

    /** User stop and network loss invalidate in-flight resolver callbacks as well as cached data. */
    @Synchronized
    fun stop() {
        cache.clear()
        stopDiscovery()
    }

    private fun stopDiscovery() {
        val listener = activeListener ?: return
        activeListener = null
        runCatching { nsdManager.stopServiceDiscovery(listener) }
        log("$TAG: 停止发现")
    }

    private fun serviceKey(info: NsdServiceInfo): String = "${info.serviceName}|${info.serviceType}"

    private fun createDiscoveryListener(generation: Long): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(this@CarPlayServiceBrowser) {
                    if (!cache.isCurrent(generation)) return
                    activeListener = null
                    cache.clear()
                }
                log("$TAG: 发现启动失败 code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (cache.isCurrent(generation)) log("$TAG: 停止失败 code=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val token = cache.beginResolve(generation, serviceKey(serviceInfo)) ?: return
                log("$TAG: 发现服务 ${serviceInfo.serviceName} (${serviceInfo.serviceType})，解析中…")
                resolveWithRetry(serviceInfo, attempts = 3, token = token)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                cache.serviceLost(generation, serviceKey(serviceInfo))
                if (cache.isCurrent(generation)) log("$TAG: 服务消失 ${serviceInfo.serviceName}，清除对应缓存")
            }
        }

    private fun resolveWithRetry(
        serviceInfo: NsdServiceInfo,
        attempts: Int,
        token: ScopedServiceCache.Token,
    ) {
        if (!cache.isCurrent(token)) return
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                if (!cache.isCurrent(token)) return
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempts > 1) {
                    Thread {
                        Thread.sleep(200)
                        resolveWithRetry(info, attempts - 1, token)
                    }.start()
                } else {
                    log("$TAG: 解析失败 ${info.serviceName} code=$errorCode")
                }
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                if (!cache.isCurrent(token)) return
                val accepted = accept(info)
                if (accepted && !cache.publish(token, info)) return
                val host = info.host?.hostAddress ?: "no-host"
                log("$TAG: OK ${info.serviceName} -> $host:${info.port}${if (accepted) "" else "（非车机，忽略）"}")
                if (accepted) onEndpoint(info)
            }
        }
        nsdManager.resolveService(serviceInfo, listener)
    }
}