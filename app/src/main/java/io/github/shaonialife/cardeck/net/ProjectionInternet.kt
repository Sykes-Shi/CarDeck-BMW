package io.github.shaonialife.cardeck.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.shaonialife.cardeck.SessionController
import java.net.Socket

/** Socket-level Internet egress for requests made by CarDeck during projection. Keeps mDNS and
 * the existing media sockets on the car network, without altering the route for another UID. */
object ProjectionInternet {
    @Suppress("DEPRECATION")
    fun select(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork ?: return null
        // This process is bound only by the car connector. During attach/teardown prefer the
        // connector's identity, then the outstanding process binding if publication is in flight.
        val car = SessionController.projectionWifi() ?: cm.boundNetworkForProcess
        val preferred = candidate(cm, active) ?: return null
        val carDefault = if (car != null && car != active && !preferred.vpn &&
            cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            ConnectedWifiSelection.sameLink(cm.getLinkProperties(car), cm.getLinkProperties(active))) active else car
        val chosen = ProjectionInternetRoute.select(preferred, carDefault) {
            cm.allNetworks.mapNotNull { network -> candidate(cm, network) }
        }
        return chosen?.takeIf { cm.activeNetwork == active }
    }

    fun isCurrent(cm: ConnectivityManager, network: Network): Boolean = select(cm) == network

    /** Only for the opted-in public NetEase lyrics retry after a VPN transport failure. The
     * disposable, unconnected socket makes Android enforce bind permission without sending data.
     * Subsequent connections must still use the returned Network and recheck the route. */
    @Suppress("DEPRECATION")
    fun selectDirectLyricsFallback(cm: ConnectivityManager, failedDefault: Network): Network? {
        val original = currentRetryVpn(cm, failedDefault) ?: return null
        val car = SessionController.projectionWifi() ?: return null
        for (network in cm.allNetworks) {
            if (!ProjectionInternetRoute.permitsLyricsRetry(failedDefault, original, car, candidate(cm, network))) continue
            val permitted = runCatching { Socket().use { network.bindSocket(it) } }.isSuccess
            if (permitted && isDirectLyricsFallbackCurrent(cm, failedDefault, network)) return network
        }
        return null
    }

    fun isDirectLyricsFallbackCurrent(cm: ConnectivityManager, failedDefault: Network, chosen: Network): Boolean {
        val original = currentRetryVpn(cm, failedDefault) ?: return false
        return ProjectionInternetRoute.permitsLyricsRetry(failedDefault, original, SessionController.projectionWifi(), candidate(cm, chosen)) &&
            cm.activeNetwork == failedDefault
    }

    private fun currentRetryVpn(cm: ConnectivityManager, failed: Network): ProjectionInternetRoute.Candidate<Network>? {
        if (cm.activeNetwork != failed) return null
        val caps = cm.getNetworkCapabilities(failed) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        return candidate(failed, caps)
    }

    private fun candidate(cm: ConnectivityManager, network: Network): ProjectionInternetRoute.Candidate<Network>? {
        return cm.getNetworkCapabilities(network)?.let { candidate(network, it) }
    }

    private fun candidate(network: Network, caps: NetworkCapabilities): ProjectionInternetRoute.Candidate<Network> {
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !SessionController.projectionNetworkBlocked(network.networkHandle)
        return ProjectionInternetRoute.Candidate(
            network, validated, vpn,
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
        )
    }
}
