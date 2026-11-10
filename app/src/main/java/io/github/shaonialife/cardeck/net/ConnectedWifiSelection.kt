package io.github.shaonialife.cardeck.net

/** 精确 SSID 才能复用。SSID 被平台隐藏时，shell 只可佐证唯一可见 Wi-Fi；多网时不猜。 */
internal object ConnectedWifiSelection {
    data class Candidate<N>(val network: N, val ssid: String?, val advertisesInternet: Boolean)

    data class Upstream<N>(
        val network: N, val validatedInternet: Boolean, val vpn: Boolean,
        val wifi: Boolean, val cellularOrEthernet: Boolean,
    )

    /** More than one Android Network can describe the very same WLAN association. */
    fun sameLink(a: android.net.LinkProperties?, b: android.net.LinkProperties?): Boolean =
        a != null && b != null && !a.interfaceName.isNullOrEmpty() && a.interfaceName == b.interfaceName &&
            a.linkAddresses.any { first -> first.address is java.net.Inet4Address &&
                b.linkAddresses.any { second -> second.address == first.address } }

    /** A cellular default for this UID alone does not establish that a visible split VPN has
     * a usable uplink. Never count the validated flag of a VPN riding the car hotspot as proof. */
    fun <N> independentInternet(
        car: N, default: Upstream<N>?, visible: List<Upstream<N>>,
        beforeDedicatedRequest: Boolean = false,
    ): Boolean {
        if (default == null || default.network == car || !default.validatedInternet) return false
        // While Android is associating, the VPN can still report its old cellular bearer.
        // Give the private projection request one chance before adopting an unvalidated BMW WLAN.
        if (beforeDedicatedRequest && visible.any { it.vpn } &&
            visible.none { it.network == car && it.validatedInternet }) return false
        fun apart(route: Upstream<N>): Boolean = !route.vpn || route.cellularOrEthernet && !route.wifi
        return apart(default) && visible.all { !it.vpn || it.validatedInternet && apart(it) }
    }

    fun <N> select(
        expectedSsid: String,
        confirmedSystemSsid: String?,
        candidates: List<Candidate<N>>,
        allowSystemWifi: Boolean = false,
    ): N? {
        if (expectedSsid.isBlank()) return null
        val exact = candidates.filter { it.ssid == expectedSsid }
        if (exact.isNotEmpty()) {
            val eligible = exact.filter { allowSystemWifi || !it.advertisesInternet }
            return eligible.singleOrNull()?.network
        }
        // 先判定身份，再过滤 INTERNET。否则隐藏 SSID 的多网会被错误缩成“唯一目标”。
        val only = candidates.singleOrNull() ?: return null
        if (only.ssid != null || confirmedSystemSsid != expectedSsid) return null
        return only.network.takeIf { allowSystemWifi || !only.advertisesInternet }
    }
}
