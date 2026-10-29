package app.ultimate.traffic.engine

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import app.ultimate.UltimateApp
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import kotlin.concurrent.thread

/**
 * Opens the local tunnel for the chosen apps and hands its packets to [TrafficEngine]. There is no VPN server: the tunnel ends in this
 * app, which passes the traffic on to the real internet itself.
 */
class TrafficVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var engine: TrafficEngine? = null
    private val manager get() = (application as UltimateApp).trafficManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(intent.getStringExtra(EXTRA_REASON) ?: "Stopped")
            return START_NOT_STICKY
        }
        val config = manager.pendingConfig
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine != null) return START_NOT_STICKY
        start(config)
        return START_NOT_STICKY
    }

    /**
     * ACCESS_NETWORK_STATE is declared only in the editions that include the traffic inspector (src/ultimateplus and src/traffic). This service is
     * declared there too and nowhere else, and R8 removes it from the offline editions, so lint's MissingPermission is suppressed for them.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun start(config: CaptureConfig) {
        val log = manager.log
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            // Read the real network's DNS server before our tunnel becomes the default network.
            val upstream = cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.firstOrNull { it is Inet4Address }?.toIp4()
            log.info("Upstream DNS: " + (upstream?.ipString() ?: "unknown, using 8.8.8.8"))

            val builder = Builder()
                .setSession("Ultimate traffic inspector")
                .addAddress(TUN_IP.ipString(), 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_IP.ipString())
                .setMtu(1500)
                .setBlocking(true)
            var added = 0
            config.packages.forEach { pkg -> if (runCatching { builder.addAllowedApplication(pkg) }.isSuccess) added++ else log.warn("Could not capture $pkg") }
            if (added == 0) { manager.onEnded("None of the chosen apps could be captured"); stopSelf(); return }

            val fd = builder.establish()
            if (fd == null) { manager.onEnded("Android did not allow the tunnel. Was VPN permission revoked?"); stopSelf(); return }
            tun = fd
            val out = FileOutputStream(fd.fileDescriptor)
            val sink = PacketSink { p -> synchronized(out) { runCatching { out.write(p) } } }
            val e = TrafficEngine(AndroidNet(this, cm), EngineConfig(DNS_IP, upstream ?: ip4(8, 8, 8, 8), config.showNames), sink)
            engine = e
            manager.onEstablished(e)
            log.ok("Tunnel up. Capturing $added app${if (added == 1) "" else "s"}.")
            thread(name = "traffic-tun", isDaemon = true) {
                // However the engine ends, the tunnel must be released: an open tunnel with nobody reading it cuts the chosen apps off.
                try {
                    e.run(FileInputStream(fd.fileDescriptor))
                } catch (t: Throwable) {
                    e.failure = e.failure ?: "The inspector crashed (${t.javaClass.simpleName}) and stopped to keep your apps online"
                } finally {
                    if (engine === e) shutdown(e.failure ?: "The tunnel closed")
                }
            }
        } catch (e: Exception) {
            log.error("Could not start: ${e.message}")
            manager.onEnded(e.message ?: "Could not start the tunnel")
            shutdown(null)
        }
    }

    /** Swiping the app away ends the inspection, so it can never keep running unseen. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown("Stopped because the app was closed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        shutdown("Another VPN or the system turned the inspector off")
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown(null)
        super.onDestroy()
    }

    private fun shutdown(reason: String?) {
        val e = engine
        engine = null
        e?.stop()
        runCatching { tun?.close() }
        tun = null
        if (e != null) manager.onEnded(null.takeIf { reason == "Stopped" } ?: reason)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "app.ultimate.traffic.STOP"
        const val EXTRA_REASON = "reason"
        /** The tunnel's own address, and the pretend DNS server apps are told to use. */
        val TUN_IP = ip4(10, 215, 173, 1)
        val DNS_IP = ip4(10, 215, 173, 2)
    }
}
