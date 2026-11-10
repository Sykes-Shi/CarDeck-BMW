package io.github.shaonialife.cardeck.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 一轮用户连接中的 Wi-Fi 工作单飞，归属覆盖确认框、重试和手动等待。 */
internal class WifiJoinOwnership {
    class Epoch internal constructor(val vpnCoexistence: Boolean)

    enum class RouteChoice { SYSTEM_WIFI, DEDICATED }

    class Flight internal constructor(val epoch: Epoch, val ssid: String) {
        private val done = CountDownLatch(1)
        @Volatile
        var succeeded: Boolean = false
            private set

        internal fun finish(success: Boolean) {
            succeeded = success
            done.countDown()
        }

        fun await(millis: Long): Boolean = done.await(millis, TimeUnit.MILLISECONDS)
    }

    data class Claim(val flight: Flight, val leader: Boolean)

    private var epoch: Epoch? = null
    private var flight: Flight? = null
    private var lostRequestSsid: String? = null
    private val routeChoices = mutableMapOf<String, RouteChoice>()

    /** The receiver may still be restarting CarPlay when this same WLAN next appears. */
    @Synchronized
    fun noteLostRequest(ssid: String) {
        if (epoch != null && ssid.isNotBlank()) lostRequestSsid = ssid
    }

    @Synchronized
    fun takeLostRequest(expected: Epoch, ssid: String): Boolean {
        if (epoch !== expected || lostRequestSsid != ssid) return false
        lostRequestSsid = null
        return true
    }

    @Synchronized
    fun routeChoice(expected: Flight): RouteChoice? =
        if (isCurrent(expected)) routeChoices[expected.ssid] else null

    /** No VPN preference is needed to choose a projection network. A system-WLAN fallback
     * must be explicit or already have a verified independent Internet uplink. */
    fun mayReuseSystemWifi(expected: Flight, independentInternet: () -> Boolean): Boolean =
        isCurrent(expected) && when (routeChoice(expected)) {
            RouteChoice.SYSTEM_WIFI -> true
            RouteChoice.DEDICATED -> false
            null -> independentInternet()
        }

    @Synchronized
    fun rememberRoute(expected: Flight, choice: RouteChoice): Boolean {
        if (!isCurrent(expected)) return false
        routeChoices[expected.ssid] = choice
        return true
    }

    @Synchronized
    fun activate(vpnCoexistence: Boolean = false): Epoch = epoch ?: Epoch(vpnCoexistence).also { epoch = it }

    @Synchronized
    fun currentEpoch(): Epoch? = epoch

    @Synchronized
    fun isCurrent(expected: Epoch): Boolean = epoch === expected

    @Synchronized
    fun isCurrent(expected: Flight): Boolean = epoch === expected.epoch && flight === expected

    @Synchronized
    fun claim(expected: Epoch, ssid: String): Claim? {
        if (epoch !== expected) return null
        flight?.let { return if (it.ssid == ssid) Claim(it, false) else null }
        return Flight(expected, ssid).let { flight = it; Claim(it, true) }
    }

    @Synchronized
    fun complete(expected: Flight, success: Boolean) {
        if (epoch !== expected.epoch || flight !== expected) return
        flight = null
        // 如果专用请求在系统接管热点后失效，下次握手可改用现有 WLAN。
        // 明确允许使用系统热点的选择则贯穿本轮用户连接。
        if (routeChoices[expected.ssid] == RouteChoice.DEDICATED) routeChoices.remove(expected.ssid)
        expected.finish(success)
    }

    @Synchronized
    fun cancel() {
        epoch = null
        lostRequestSsid = null
        routeChoices.clear()
        val previous = flight
        flight = null
        previous?.finish(false)
    }
}
