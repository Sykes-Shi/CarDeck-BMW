package io.github.shaonialife.cardeck.shizuku

import android.content.Context
import android.content.ContextWrapper
import android.content.AttributionSource
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import io.github.shaonialife.cardeck.net.ProjectedUidRoute
import io.github.shaonialife.cardeck.net.ProjectedUidRoute.Kind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object ProjectedUidRouteReader {
    private const val SHELL = "com.android.shell"
    @Volatile private var shellManager: ConnectivityManager? = null

    @Synchronized
    private fun manager(context: Context): ConnectivityManager? {
        shellManager?.let { return it }
        val base = context.createPackageContext(SHELL, 0)
        if (base.applicationInfo.uid != Process.myUid() ||
            base.checkSelfPermission("android.permission.NETWORK_SETTINGS") != PackageManager.PERMISSION_GRANTED) return null
        // createPackageContext can inherit the resource context's opPackageName. Use the
        // verified shell UID/package pair for the callback's ConnectivityService attribution.
        val value = if (base.opPackageName == SHELL) base.getSystemService(ConnectivityManager::class.java) else {
            val shell = object : ContextWrapper(base) {
                private val source = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build()
                override fun getOpPackageName() = SHELL
                override fun getPackageName() = SHELL
                override fun getAttributionSource(): AttributionSource = source
                override fun getApplicationContext(): Context = this
            }
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, Context.CONNECTIVITY_SERVICE) as? IBinder ?: return null
            val serviceClass = Class.forName("android.net.IConnectivityManager")
            val service = Class.forName("android.net.IConnectivityManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            ConnectivityManager::class.java.getConstructor(Context::class.java, serviceClass).newInstance(shell, service)
        }
        shellManager = value
        return value
    }

    // This callback accepts NETWORK_SETTINGS; getActiveNetworkForUid requires NETWORK_STACK.
    private val register by lazy {
        runCatching {
            ConnectivityManager::class.java.getMethod("registerDefaultNetworkCallbackForUid",
                Int::class.javaPrimitiveType, ConnectivityManager.NetworkCallback::class.java, Handler::class.java)
        }.getOrNull()
    }

    fun read(context: Context, uid: Int, carHandle: Long): Kind {
        if (uid !in 10_000..19_999 || carHandle <= 0L || Process.myUid() != 2000 ||
            Looper.myLooper() == Looper.getMainLooper()) return Kind.UNKNOWN
        val method = register ?: return Kind.UNKNOWN
        val token = Binder.clearCallingIdentity()
        try {
            val manager = manager(context) ?: return Kind.UNKNOWN
            val latch = CountDownLatch(1)
            val result = AtomicReference(Kind.UNKNOWN)
            val cb = object : ConnectivityManager.NetworkCallback() {
                private var observedNetwork: Network? = null
                private var route = Kind.UNKNOWN
                private var blocked: Boolean? = null
                override fun onAvailable(network: Network) {
                    observedNetwork = network
                    route = Kind.UNKNOWN
                    blocked = null
                }
                private fun publish() {
                    val state = blocked ?: return
                    if (!state && route == Kind.UNKNOWN) return
                    result.set(if (state) Kind.BLOCKED else route)
                    latch.countDown()
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (network != observedNetwork) return
                    route = ProjectedUidRoute.classify(network.networkHandle == carHandle, caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                    publish()
                }
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    if (network != observedNetwork) return
                    this.blocked = blocked
                    publish()
                }
                override fun onLost(network: Network) {
                    if (network == observedNetwork) {
                        observedNetwork = null
                        result.set(Kind.UNKNOWN)
                    }
                }
                override fun onUnavailable() { result.set(Kind.NONE); latch.countDown() }
            }
            try {
                method.invoke(manager, uid, cb, Handler(Looper.getMainLooper()))
                return if (latch.await(900, TimeUnit.MILLISECONDS)) result.get() else Kind.UNKNOWN
            } finally {
                runCatching { manager.unregisterNetworkCallback(cb) }
            }
        } catch (_: Exception) {
            return Kind.UNKNOWN
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}
