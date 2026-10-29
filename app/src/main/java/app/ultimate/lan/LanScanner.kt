package app.ultimate.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class NetInfo(val ip: String, val gateway: String?, val dns: List<String>, val prefix: Int, val wifi: Boolean)

/**
 * Finds devices on the local network the way any app may: knocking on common ports across the /24 (a refused connection also
 * proves a device is there), listening for mDNS/Bonjour and SSDP/UPnP announcements, and reverse DNS. It cannot see other
 * devices' traffic; Android does not allow that without root.
 */
class LanScanner(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** ACCESS_NETWORK_STATE is declared only in editions that ship this tool (Ultimate+); R8 removes it from the others. */
    @android.annotation.SuppressLint("MissingPermission")
    fun netInfo(): NetInfo? {
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val wifi = cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        return NetInfo(v4.address.hostAddress!!, gw, lp.dnsServers.mapNotNull { it.hostAddress }, v4.prefixLength, wifi)
    }

    /** Milliseconds to open a TCP connection, or null. A refusal still answers, so it counts. */
    suspend fun knock(ip: String, port: Int, timeoutMs: Int = 300): Long? = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
            (System.nanoTime() - start) / 1_000_000
        } catch (e: ConnectException) {
            if (e.message?.contains("refused", true) == true) (System.nanoTime() - start) / 1_000_000 else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun isOpen(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), 400) }; true }.getOrDefault(false)
    }

    /** Sweeps the subnet, then looks closer at each device found. [found] is called as soon as anything new is learned. */
    suspend fun scan(info: NetInfo, found: (Device) -> Unit, progress: (Int, Int) -> Unit) = coroutineScope {
        val lock = context.applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("ultimate-lan")?.apply { setReferenceCounted(false); acquire() }
        try {
            found(Device(info.ip, name = "This phone", isThisPhone = true))
            launch { mdns(found) }
            launch { ssdp(found) }
            val hosts = Lan.hosts(info.ip)
            val gate = Semaphore(48)
            var done = 0
            val quick = listOf(80, 443, 22, 445, 62078, 8080, 554, 7000, 8008, 9100, 53, 139)
            hosts.map { ip ->
                async {
                    gate.withPermit {
                        // Knock on a few common doors; the first answer (open or refused) means someone is home.
                        val alive = quick.firstNotNullOfOrNull { knock(ip, it, 200) } ?: runCatching {
                            withContext(Dispatchers.IO) { InetAddress.getByName(ip).isReachable(250) }
                        }.getOrDefault(false).let { if (it) 0L else null }
                        synchronized(this@coroutineScope) { done++ }
                        progress(done, hosts.size)
                        if (alive != null) {
                            found(Device(ip, latencyMs = alive.takeIf { it > 0 }, isGateway = ip == info.gateway))
                            val open = Lan.PORTS.filter { isOpen(ip, it) }
                            val name = withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull() }?.takeIf { it != ip }
                            found(Device(ip, name = name, openPorts = open, isGateway = ip == info.gateway))
                        }
                    }
                }
            }.awaitAll()
        } finally {
            lock?.release()
        }
    }

    private val mdnsTypes = listOf(
        "_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_ipp._tcp", "_printer._tcp", "_http._tcp", "_smb._tcp", "_afpovertcp._tcp",
        "_hap._tcp", "_matter._tcp", "_spotify-connect._tcp", "_companion-link._tcp", "_device-info._tcp", "_rtsp._tcp", "_sonos._tcp",
    )

    /** Listens for Bonjour/mDNS announcements for a few seconds and resolves each to a name and address. */
    private suspend fun mdns(found: (Device) -> Unit) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val resolveLock = Mutex()
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()
        val seen = mutableListOf<NsdServiceInfo>()
        mdnsTypes.forEach { type ->
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) { synchronized(seen) { seen += info } }
                override fun onDiscoveryStarted(t: String) = Unit
                override fun onDiscoveryStopped(t: String) = Unit
                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onStartDiscoveryFailed(t: String, code: Int) = Unit
                override fun onStopDiscoveryFailed(t: String, code: Int) = Unit
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners += l }
        }
        delay(6_000)
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        // Older Android resolves one service at a time.
        synchronized(seen) { seen.toList() }.forEach { s ->
            resolveLock.withLock {
                val r = withTimeoutOrNull(2_000) {
                    suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                        @Suppress("DEPRECATION")
                        runCatching {
                            nsd.resolveService(s, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(i: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) }
                                override fun onServiceResolved(i: NsdServiceInfo) { if (cont.isActive) cont.resume(i) }
                            })
                        }.onFailure { if (cont.isActive) cont.resume(null) }
                    }
                }
                @Suppress("DEPRECATION")
                val ip = (r?.host as? Inet4Address)?.hostAddress
                if (ip != null) found(Device(ip, name = s.serviceName, announces = setOf(s.serviceType.trim('.'))))
            }
        }
    }

    /** Asks UPnP devices to identify themselves, then reads each one's friendly name. */
    private suspend fun ssdp(found: (Device) -> Unit) = withContext(Dispatchers.IO) {
        val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n".toByteArray()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                repeat(2) { socket.send(DatagramPacket(search, search.size, InetAddress.getByName("239.255.255.250"), 1900)) }
                val end = System.currentTimeMillis() + 3_500
                val buf = ByteArray(4096)
                val locations = mutableMapOf<String, String>()
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
                    val ip = p.address.hostAddress ?: continue
                    val h = Lan.ssdpHeaders(String(p.data, 0, p.length))
                    found(Device(ip, vendorHint = h["server"], announces = setOfNotNull(h["st"])))
                    h["location"]?.let { locations.putIfAbsent(ip, it) }
                }
                locations.forEach { (ip, loc) ->
                    val xml = runCatching {
                        (URL(loc).openConnection().apply { connectTimeout = 1500; readTimeout = 1500 }).getInputStream().use { it.readBytes().decodeToString() }
                    }.getOrNull()
                    xml?.let(Lan::friendlyName)?.let { found(Device(ip, name = it)) }
                }
            }
        }
    }
}

/** Merges what different probes learned about the same address. */
fun Device.merge(o: Device) = copy(
    name = name ?: o.name,
    vendorHint = vendorHint ?: o.vendorHint,
    openPorts = (openPorts + o.openPorts).distinct().sorted(),
    announces = announces + o.announces,
    latencyMs = latencyMs ?: o.latencyMs,
    isGateway = isGateway || o.isGateway,
    isThisPhone = isThisPhone || o.isThisPhone,
)
