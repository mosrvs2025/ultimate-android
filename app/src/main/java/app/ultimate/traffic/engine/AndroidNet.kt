package app.ultimate.traffic.engine

import android.net.ConnectivityManager
import android.net.VpnService
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/** The engine's network access on Android: every socket is protected, so it leaves by the real network instead of looping back into the tunnel. */
class AndroidNet(private val vpn: VpnService, private val connectivity: ConnectivityManager) : Net {
    override fun connect(ip: Int, port: Int, timeoutMs: Int): Socket {
        val s = Socket()
        try {
            check(vpn.protect(s)) { "Could not protect the socket from the tunnel" }
            s.connect(InetSocketAddress(ip.toInetAddress(), port), timeoutMs)
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    override fun datagram(): DatagramSocket = DatagramSocket().also { check(vpn.protect(it)) { "Could not protect the socket from the tunnel" } }

    /** Only the app that runs the VPN may ask this, which is exactly the case here. */
    override fun owner(protocol: Int, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int): Int =
        runCatching { connectivity.getConnectionOwnerUid(protocol, InetSocketAddress(srcIp.toInetAddress(), srcPort), InetSocketAddress(dstIp.toInetAddress(), dstPort)) }.getOrDefault(-1)
}
