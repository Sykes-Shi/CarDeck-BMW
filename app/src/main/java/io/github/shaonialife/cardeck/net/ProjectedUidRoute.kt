package io.github.shaonialife.cardeck.net

import android.os.Looper
import io.github.shaonialife.cardeck.shizuku.CarInputClient

/** Android's assigned route, independent of the CarDeck process binding. */
internal object ProjectedUidRoute {
    enum class Kind { UNKNOWN, NONE, BLOCKED, CAR_WIFI, CELLULAR, WIFI, VPN, OTHER, UNVALIDATED }

    fun observe(uid: Int, carHandle: Long): Kind {
        if (Looper.myLooper() == Looper.getMainLooper() || uid !in 10_000..19_999 || carHandle <= 0) return Kind.UNKNOWN
        return runCatching {
            CarInputClient.service?.projectedUidRoute(uid, carHandle)?.let(Kind::valueOf) ?: Kind.UNKNOWN
        }.getOrDefault(Kind.UNKNOWN)
    }

    fun classify(isCar: Boolean, vpn: Boolean, internet: Boolean, validated: Boolean, cell: Boolean, wifi: Boolean): Kind =
        when {
            vpn -> Kind.VPN
            isCar -> Kind.CAR_WIFI
            !internet || !validated -> Kind.UNVALIDATED
            cell -> Kind.CELLULAR
            wifi -> Kind.WIFI
            else -> Kind.OTHER
        }
}
