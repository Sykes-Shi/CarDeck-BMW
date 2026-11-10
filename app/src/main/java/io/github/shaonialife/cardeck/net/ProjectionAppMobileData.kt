package io.github.shaonialife.cardeck.net

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.shaonialife.cardeck.SessionController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Platform-managed, lower-priority physical defaults for the selected projection apps. The
 * normal path (local-only receiver, healthy per-app Internet or assigned VPN) needs no writes. */
internal object ProjectionAppMobileData {
    private const val SPOTIFY = "com.spotify.music"
    private val worker = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "projection-app-network").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private val lock = Any()
    @Volatile private var current: Session? = null
    private var lastGeneration = Long.MIN_VALUE

    private class Session(val ctx: Context, val generation: Long) {
        val apps = linkedSetOf<String>()
        val suppressed = mutableSetOf<String>() // worker-owned
        val unregister = mutableListOf<() -> Unit>() // worker-owned
        @Volatile var paused = false
        var pending: ScheduledFuture<*>? = null // guarded by lock
        var force = true
        var last: Pair<Profile, Set<String>>? = null // worker-owned
        var needsRecovery = true
        var lastCheck = 0L
        var previousLeases = emptySet<Int>()
        var bixbyFollowUp: ScheduledFuture<*>? = null // worker-owned
        var spotifyProfile: Profile? = null // worker-owned
    }

    private data class Profile(
        val car: Long, val sharedAliases: Set<Long>, val shared: Boolean, val vpns: Map<Long, String>, val cellular: Long?,
        val defaultId: Long?, val defaultUsesWifi: Boolean, val otherWifi: Boolean,
    ) { val vpn: Boolean get() = vpns.isNotEmpty() }

    private fun supported() = Build.VERSION.SDK_INT >= 36 && Build.MANUFACTURER.equals("samsung", true)

    fun start(context: Context, generation: Long) {
        if (!supported()) return
        synchronized(lock) {
            if (generation <= lastGeneration) return
            lastGeneration = generation
            val old = current
            val s = Session(context.applicationContext, generation)
            current = s
            worker.execute {
                old?.let(::unregister)
                if (current === s) { register(s); schedule(s, true) }
            }
        }
    }

    fun note(context: Context, generation: Long, packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName || packageName == BixbyNetworkPolicy.PACKAGE) return
        val s = current ?: return
        synchronized(lock) {
            if (current !== s || s.generation != generation || s.paused) return
            s.apps.add(packageName)
            schedule(s, false)
        }
    }

    /** Called on the phone-launch worker after a real voice request. Give the phone assistant a
     * short chance to obtain its Internet default before the first utterance. Never block the
     * receiver event loop or wait indefinitely for shell, the carrier, or VPN. */
    fun preparePhoneBixby(generation: Long) {
        val s = current?.takeIf { it.generation == generation } ?: return
        val ready = CountDownLatch(1)
        synchronized(lock) {
            if (current !== s) return
            if (!s.paused) s.apps.add(BixbyNetworkPolicy.PACKAGE)
            worker.execute {
                try {
                    if (current !== s) return@execute
                    val p = profile(s.ctx)
                    val route = reportBixbyRoute(s, p, "before")
                    if (!s.paused && route == ProjectedUidRoute.Kind.CAR_WIFI && p?.cellular != null) {
                        reconcile(s, synchronized(lock) { s.apps.toSet() }, true)
                    }
                } catch (e: Exception) {
                    if (current === s) SessionController.appendLog("BIXBY-NET: preflight unavailable (${e.javaClass.simpleName})")
                } finally {
                    ready.countDown()
                }
                schedule(s, false)
                s.bixbyFollowUp?.cancel(false)
                s.bixbyFollowUp = worker.schedule({
                    if (current === s) runCatching {
                        // A background-blocked UID can gain a different default once Bixby is visible.
                        if (reportBixbyRoute(s, profile(s.ctx), "followup") == ProjectedUidRoute.Kind.CAR_WIFI) schedule(s, true)
                    }
                }, 2, TimeUnit.SECONDS)
            }
        }
        try {
            if (!ready.await(1500, TimeUnit.MILLISECONDS)) SessionController.appendLog("BIXBY-NET: preflight continuing after launch deadline")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @Suppress("DEPRECATION")
    private fun reportBixbyRoute(s: Session, p: Profile?, stage: String): ProjectedUidRoute.Kind {
        val app = runCatching { s.ctx.packageManager.getApplicationInfo(BixbyNetworkPolicy.PACKAGE, 0) }.getOrNull()
        val uid = app?.uid
        val systemApp = app != null && app.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val route = if (p != null && uid != null) observeRoute(uid, p) else ProjectedUidRoute.Kind.UNKNOWN
        SessionController.appendLog("BIXBY-NET: stage=$stage uid=$uid default=$route vpn=${p?.vpn} " +
            "validatedCell=${p?.cellular != null} sharedCar=${p?.shared} systemApp=$systemApp paused=${s.paused}")
        return route
    }

    fun stop(context: Context, generation: Long) {
        synchronized(lock) {
            val s = current?.takeIf { it.generation == generation } ?: return
            current = null
            s.pending?.cancel(false)
            worker.execute { unregister(s); if (!restore(s.ctx)) recover(context.applicationContext) }
        }
    }

    fun recover(context: Context) {
        val ctx = context.applicationContext
        if (current != null || !MobileDataRouting.hasLeases(ctx)) return
        worker.execute { if (current == null && !restore(ctx)) retryRestore(ctx) }
    }

    // A user-initiated bulk change is serialized by MobileDataRouting and takes precedence
    // for the rest of this projection. A future session can again help newly opened apps.
    internal fun pauseForManualChange() {
        synchronized(lock) { current?.let { it.paused = true; it.pending?.cancel(false) } }
    }

    private var recoveryPending = false // worker-owned
    private fun retryRestore(ctx: Context) {
        if (recoveryPending || !MobileDataRouting.hasLeases(ctx)) return
        recoveryPending = true
        worker.schedule({
            recoveryPending = false
            if (current == null && !restore(ctx)) retryRestore(ctx)
        }, 30L, TimeUnit.SECONDS)
    }

    private fun restore(ctx: Context): Boolean {
        if (!MobileDataRouting.hasLeases(ctx)) return true
        return runCatching { MobileDataRouting.releaseProjection(ctx) }.onSuccess {
            SessionController.appendLog("APP-NET: 上一轮投屏的临时流量设置已撤回")
        }.onFailure { SessionController.appendLog("APP-NET: 临时设置待 Shizuku 就绪后恢复") }.isSuccess
    }

    private fun register(s: Session) {
        val cm = s.ctx.getSystemService(ConnectivityManager::class.java) ?: return
        fun callback() = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = schedule(s, false)
            override fun onLost(network: Network) = schedule(s, false)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = schedule(s, false)
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = schedule(s, false)
        }
        fun listen(register: (ConnectivityManager.NetworkCallback) -> Unit) {
            val cb = callback()
            runCatching { register(cb) }.onSuccess { s.unregister.add { cm.unregisterNetworkCallback(cb) } }
        }
        listen(cm::registerDefaultNetworkCallback)
        listen { cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), it) }
        val observer = object : ContentObserver(null) { override fun onChange(selfChange: Boolean) = schedule(s, true) }
        runCatching { s.ctx.contentResolver.registerContentObserver(Settings.Secure.getUriFor("mobile_data_preferred_uids"), false, observer) }
            .onSuccess { s.unregister.add { s.ctx.contentResolver.unregisterContentObserver(observer) } }
    }

    private fun unregister(s: Session) {
        s.bixbyFollowUp?.cancel(false)
        s.unregister.forEach { runCatching(it) }; s.unregister.clear()
    }

    private fun schedule(s: Session, force: Boolean) {
        synchronized(lock) {
            if (current !== s || s.paused) return
            s.force = s.force || force
            if (s.pending != null) return
            s.pending = worker.schedule({
                val (apps, forceCheck) = synchronized(lock) {
                    s.pending = null
                    (s.apps.toSet() to s.force).also { s.force = false }
                }
                if (current === s && !s.paused) runCatching { reconcile(s, apps, forceCheck) }.onFailure {
                    s.last = null
                    SessionController.appendLog("APP-NET: 自动流量设置未完成（${it.javaClass.simpleName}）")
                    retry(s)
                }
            }, 400L, TimeUnit.MILLISECONDS)
        }
    }

    @Suppress("DEPRECATION")
    private fun profile(ctx: Context): Profile? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val car = SessionController.projectionWifi() ?: return null
        val caps = cm.getNetworkCapabilities(car) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        val all = cm.allNetworks.associateWith { cm.getNetworkCapabilities(it) }
        val boundLink = cm.getLinkProperties(car)
        // Samsung can publish a local-only handle and a shared Internet-capable handle for the
        // same WLAN association. Equal interface AND IPv4 link address distinguish it from STA+STA.
        val aliases = all.entries.filter { (n,c) ->
            n != car && c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                ConnectedWifiSelection.sameLink(boundLink, cm.getLinkProperties(n))
        }.mapTo(mutableSetOf()) { it.key.networkHandle }
        fun internet(c: NetworkCapabilities?) = c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val cellular = all.entries.firstOrNull { (net, c) -> internet(c) && c != null &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !SessionController.projectionNetworkBlocked(net.networkHandle) }?.key
        val otherWifi = all.any { (n, c) -> n != car && n.networkHandle !in aliases && internet(c) && c != null &&
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
        return Profile(car.networkHandle, aliases, active == car || aliases.isNotEmpty() || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            all.mapNotNull { (net, c) -> if (c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                val bearer = when {
                    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "WIFI_AND_CELLULAR"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    else -> "UNKNOWN"
                }
                net.networkHandle to bearer
            } else null }.toMap(),
            cellular?.networkHandle, active?.networkHandle, activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true, otherWifi)
    }

    private fun reconcile(s: Session, apps: Set<String>, force: Boolean) {
        if (s.needsRecovery) {
            // Restore a crashed or previous session before accepting ownership for this generation.
            if (!restore(s.ctx)) { retry(s); return }
            s.needsRecovery = false
        }
        val p = profile(s.ctx)
        // A dedicated projection WLAN needs no policy write, but a VPN-assigned player can
        // still be offline. Sample its own default even when routes are already configured.
        if (p != null && SPOTIFY in apps && s.spotifyProfile != p) {
            val uid = runCatching { s.ctx.packageManager.getApplicationInfo(SPOTIFY, 0).uid }.getOrNull()
            val route = uid?.let { observeRoute(it, p) } ?: ProjectedUidRoute.Kind.UNKNOWN
            if (current !== s || s.paused) return
            s.spotifyProfile = p
            val bearers = p.vpns.values.distinct().sorted().joinToString(",").ifEmpty { "NONE_VISIBLE" }
            SessionController.appendLog("SPOTIFY-NET: uidDefault=$route visibleVpnBearer=$bearers " +
                "sharedCar=${p.shared} validatedCell=${p.cellular != null}")
        } else if (p == null) s.spotifyProfile = null
        if (p == null || !p.shared || p.cellular == null || apps.isEmpty()) {
            s.last = null
            if (!restore(s.ctx)) retry(s) else s.previousLeases = emptySet()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && s.last == (p to apps) && now - s.lastCheck < 30_000L) return
        val previous = s.last?.first
        if (previous != null && p.otherWifi && (!previous.otherWifi || previous.defaultId != p.defaultId) && MobileDataRouting.hasLeases(s.ctx)) {
            // Give a newly available regular Internet Wi-Fi the first chance. Reobserve the actual
            // UID default after Android has rematched it, before considering another cellular lease.
            if (restore(s.ctx)) s.last = null
            retry(s)
            return
        }
        val split: Set<String>? by lazy {
            val list = apps.mapNotNull { pkg ->
                val uid = runCatching { s.ctx.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: return@mapNotNull null
                DirectAppCatalog.App(pkg, pkg, uid)
            }
            DirectAppCatalog.fromLiveClash(s.ctx, list, acceptConfirmedInactive = !p.vpn && BixbyNetworkPolicy.PACKAGE in apps)
        }
        fun live() = current === s && !s.paused && profile(s.ctx) == p
        val leases = MobileDataRouting.syncProjection(s.ctx, apps, s.suppressed, { app, owned ->
            val route = observeRoute(app.uid, p)
            val direct = { split?.contains(app.pkg) }
            val decision = if (app.pkg == BixbyNetworkPolicy.PACKAGE) BixbyNetworkPolicy.needsPreference(route, owned, direct)
                else ProjectionMobilePolicy.needsPreference(route, owned, p.defaultId == p.car || p.defaultId in p.sharedAliases,
                    p.defaultUsesWifi, p.otherWifi, p.vpn, direct)
            SessionController.appendLog("APP-NET: pkg=${app.pkg} uid=${app.uid} default=$route cellularPreference=$decision")
            decision
        }, ::live)
        if (live()) {
            s.last = p to apps
            s.lastCheck = now
            if ((leases - s.previousLeases).isNotEmpty()) {
                worker.schedule({ schedule(s, true) }, 1L, TimeUnit.SECONDS)
            }
            s.previousLeases = leases
            SessionController.appendLog("APP-NET: 本次投屏临时移动数据设置 ${leases.size} 项")
        }
    }

    private fun observeRoute(uid: Int, p: Profile): ProjectedUidRoute.Kind {
        val initial = ProjectedUidRoute.observe(uid, p.car)
        if ((initial == ProjectedUidRoute.Kind.WIFI || initial == ProjectedUidRoute.Kind.UNVALIDATED) &&
            p.sharedAliases.any { ProjectedUidRoute.observe(uid, it) == ProjectedUidRoute.Kind.CAR_WIFI }) {
            return ProjectedUidRoute.Kind.CAR_WIFI
        }
        return initial
    }

    private fun retry(s: Session) {
        synchronized(lock) {
            if (current !== s || s.paused || s.pending != null) return
            s.pending = worker.schedule({
                synchronized(lock) { s.pending = null }
                schedule(s, true)
            }, 15L, TimeUnit.SECONDS)
        }
    }
}

internal object ProjectionMobilePolicy {
    /** A default VPN has precedence over cellular preferences on Android. With a visible VPN,
     * also require known split exclusion for an unknown route. A different validated Wi-Fi is useful. */
    fun needsPreference(route: ProjectedUidRoute.Kind, owned: Boolean, defaultIsCar: Boolean,
        defaultUsesWifi: Boolean, otherWifi: Boolean, vpn: Boolean, direct: () -> Boolean?): Boolean {
        return when (route) {
            ProjectedUidRoute.Kind.VPN, ProjectedUidRoute.Kind.WIFI, ProjectedUidRoute.Kind.OTHER, ProjectedUidRoute.Kind.BLOCKED -> false
            ProjectedUidRoute.Kind.CELLULAR -> owned && (!vpn || direct() != false)
            ProjectedUidRoute.Kind.CAR_WIFI -> !vpn || direct() != false
            ProjectedUidRoute.Kind.NONE, ProjectedUidRoute.Kind.UNVALIDATED, ProjectedUidRoute.Kind.UNKNOWN ->
                !otherWifi && (defaultIsCar || vpn && defaultUsesWifi) && (!vpn || direct() == true)
        }
    }
}
