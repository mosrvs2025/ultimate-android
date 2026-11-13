package app.ultimate.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.ArrayDeque

/**
 * Finds other mesh devices through Android's network-service discovery (mDNS), a second route besides the UDP broadcast
 * that many routers swallow. The service name carries the pairing key's public fingerprint, so a phone only bothers to
 * resolve devices with the same code; the signed heartbeat afterwards is still what proves a device may join.
 */
@Suppress("DEPRECATION") // the callback-based resolve is the only form available on Android 10-13
class MeshNsd(context: Context, private val fingerprint: String, private val selfId: String, private val port: Int, private val log: (String) -> Unit, private val onFound: (host: String, port: Int) -> Unit) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val name = "om-$fingerprint-${selfId.take(8)}"
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var registered = false
    private var discovering = false

    private val register = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(i: NsdServiceInfo) { registered = true }
        override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { registered = false; log("network-name announce failed ($code)") }
        override fun onServiceUnregistered(i: NsdServiceInfo) { registered = false }
        override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Unit
    }

    private val discover = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(t: String) { discovering = true }
        override fun onStartDiscoveryFailed(t: String, code: Int) { discovering = false; log("network-name search failed ($code)") }
        override fun onStopDiscoveryFailed(t: String, code: Int) = Unit
        override fun onDiscoveryStopped(t: String) { discovering = false }
        override fun onServiceLost(i: NsdServiceInfo) = Unit
        override fun onServiceFound(i: NsdServiceInfo) {
            if (i.serviceName == name || !i.serviceName.startsWith("om-$fingerprint-")) return
            synchronized(queue) { queue.add(i) }
            next()
        }
    }

    @Synchronized private fun next() {
        if (resolving) return
        val i = synchronized(queue) { queue.poll() } ?: return
        resolving = true
        runCatching {
            nsd?.resolveService(i, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, code: Int) { done() }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    s.host?.hostAddress?.let { onFound(it, s.port) }
                    done()
                }
            })
        }.onFailure { done() }
    }

    @Synchronized private fun done() { resolving = false; next() }

    fun start() {
        val m = nsd ?: return
        runCatching {
            m.registerService(NsdServiceInfo().apply { serviceName = name; serviceType = TYPE; port = this@MeshNsd.port }, NsdManager.PROTOCOL_DNS_SD, register)
            m.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discover)
        }.onFailure { log("network-name discovery unavailable: ${it.message}") }
    }

    fun stop() {
        val m = nsd ?: return
        if (registered) runCatching { m.unregisterService(register) }
        if (discovering) runCatching { m.stopServiceDiscovery(discover) }
        synchronized(queue) { queue.clear() }
    }

    private companion object { const val TYPE = "_ultimate-mesh._tcp." }
}
