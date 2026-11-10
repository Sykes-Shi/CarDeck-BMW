package io.github.shaonialife.cardeck.launcher.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** Compat writes normally restart a package, including any VPN service it owns. */
internal enum class OrientationPackage {
    REGULAR, VPN, UNKNOWN;

    val mayRestart: Boolean get() = this == REGULAR

    fun compatAction(enable: Boolean): String? = when {
        mayRestart -> if (enable) "enable" else "disable"
        enable -> null
        else -> "disable --no-kill"
    }

    companion object {
        fun inspect(key: String, servicePermissions: (String) -> List<String?>?): OrientationPackage {
            val pkg = key.substringBefore('/')
            if (pkg == "com.github.metacubex.clash.meta") return VPN
            val permissions = servicePermissions(pkg) ?: return UNKNOWN
            return if (Manifest.permission.BIND_VPN_SERVICE in permissions) VPN else REGULAR
        }

        // Read only the target package, including non-exported/disabled services (e.g. Clash TunService).
        // Recheck before each destructive operation so an upgraded package never uses a cached negative.
        @Suppress("DEPRECATION")
        fun inspect(context: Context, key: String): OrientationPackage = inspect(key) { pkg ->
            try {
                context.packageManager.getPackageInfo(
                    pkg, PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS,
                ).services.orEmpty().map { it.permission }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}
