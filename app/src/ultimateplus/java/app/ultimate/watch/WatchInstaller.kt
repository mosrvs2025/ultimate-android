package app.ultimate.watch

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * "Install on my watch": Ultimate+ pairs with the watch over Wi-Fi (the watch's Wireless debugging, the same thing a
 * computer would use), finds it on the network by itself, and installs the watch app it carries. No computer, no other
 * app. Pairing happens once; the watch remembers this phone.
 */
class WatchInstaller(private val context: Context) {
    /** The phone's identity for the watch: an RSA key and a self-signed certificate, made once and kept in app storage. */
    private inner class Manager : AbsAdbConnectionManager() {
        private val dir = File(context.filesDir, "watch_adb").apply { mkdirs() }
        private val keyFile = File(dir, "key.pk8")
        private val certFile = File(dir, "cert.der")
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(20, TimeUnit.SECONDS)
            if (!keyFile.exists() || !certFile.exists()) {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                keyFile.writeBytes(pair.private.encoded)
                certFile.writeBytes(selfSigned(pair.public.encoded, pair.private))
            }
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes()))
        }

        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "Ultimate+ on ${Build.MODEL}"
    }

    /** What happened, in words for the screen. */
    sealed interface Outcome {
        data class Done(val message: String) : Outcome
        data class Failed(val why: String) : Outcome
    }

    /**
     * Finds the watch's "Pair new device" service on this Wi-Fi (not this phone's own), or null if none shows within
     * [timeoutMs]. The watch shows that screen only while "Pair new device" is open.
     */
    suspend fun findPairing(timeoutMs: Long = 20_000): Pair<String, Int>? = discover(AdbMdns.SERVICE_TYPE_TLS_PAIRING, null, timeoutMs)

    /** Pairs with the 6-digit [code] the watch shows. */
    suspend fun pair(host: String, port: Int, code: String): Outcome = withContext(Dispatchers.IO) {
        runCatching { Manager().use { m -> if (m.pair(host, port, code.trim())) Outcome.Done("Paired with your watch.") else Outcome.Failed("The watch didn't accept the code. Check it and try again.") } }
            .getOrElse { Outcome.Failed(reason(it)) }
    }

    /** Connects to the paired watch at [host] and installs the watch app bundled in Ultimate+, reporting [progress] (0..1). */
    suspend fun install(host: String, knownPort: Int? = null, progress: (Float) -> Unit = {}): Outcome = withContext(Dispatchers.IO) {
        val apk = runCatching { context.assets.open(BUNDLED).use { it.readBytes() } }.getOrNull()
            ?: return@withContext Outcome.Failed("This build of Ultimate+ doesn't include the watch app. Install it from the release page instead.")
        // The port typed from the watch's Wireless debugging screen, else whatever the watch announces on the network.
        val (h, port) = knownPort?.let { host to it } ?: discover(AdbMdns.SERVICE_TYPE_TLS_CONNECT, host, 8_000)
            ?: return@withContext Outcome.Failed(NEED_PORT)
        runCatching {
            Manager().use { m ->
                if (!m.connect(h, port)) return@use Outcome.Failed("The watch refused the connection. Pair again.")
                // The package manager reads the APK from the stream and answers "Success" or the reason it failed.
                m.openStream("exec:cmd package install -r -S ${apk.size}").use { s ->
                    s.openOutputStream().use { out ->
                        var sent = 0
                        while (sent < apk.size) { val n = minOf(64 * 1024, apk.size - sent); out.write(apk, sent, n); sent += n; progress(sent / apk.size.toFloat()) }
                        out.flush()
                    }
                    val answer = s.openInputStream().bufferedReader().readText().trim()
                    if (answer.contains("Success")) Outcome.Done("Installed. Open Ultimate+ on your watch.") else Outcome.Failed("The watch said: ${answer.ifBlank { "nothing" }}")
                }
            }
        }.getOrElse { Outcome.Failed(reason(it)) }
    }

    /** Listens for an ADB service of [type] on the local network, skipping this phone's own addresses. */
    private suspend fun discover(type: String, host: String?, timeoutMs: Long): Pair<String, Int>? = withTimeoutOrNull(timeoutMs) {
        val mine = ownAddresses()
        suspendCancellableCoroutine { cont ->
            var done = false
            val mdns = AdbMdns(context, type) { address: InetAddress?, port: Int ->
                val a = address?.hostAddress ?: return@AdbMdns
                if (done || port <= 0 || a in mine || (host != null && a != host)) return@AdbMdns
                done = true
                cont.resume(a to port)
            }
            mdns.start()
            cont.invokeOnCancellation { mdns.stop() }
        }
    }

    private fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.toSet()
    }.getOrDefault(emptySet())

    private fun reason(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return when {
            root is java.net.ConnectException || root is java.net.SocketTimeoutException -> "Couldn't reach the watch. Is Wireless debugging still on, on the same Wi-Fi?"
            root.javaClass.simpleName.contains("Ssl") || root.message?.contains("TLS", true) == true -> "The secure connection failed: ${root.message}"
            else -> "${root.javaClass.simpleName}: ${root.message ?: "no details"}"
        }
    }

    companion object {
        const val BUNDLED = "watch/Ultimate-Plus-Watch.apk"
        /** Shown when the watch's connection port isn't announced on the network, so the screen asks for it. */
        const val NEED_PORT = "need-port"

        /** Reads "192.168.1.239:37581" (as the watch shows it, spaces or a trailing dot allowed) into address and port. */
        fun parseAddress(text: String): Pair<String, Int>? {
            val m = Regex("""^\s*(\d{1,3}(?:\.\d{1,3}){3})\s*:\s*(\d{2,5})\s*\.?\s*$""").find(text) ?: return null
            val ip = m.groupValues[1]; val port = m.groupValues[2].toInt()
            if (ip.split('.').any { it.toInt() > 255 } || port !in 1..65535) return null
            return ip to port
        }

        /** A self-signed X.509 certificate for [publicKey], signed with [key] (SHA-256 with RSA). */
        internal fun selfSigned(publicKey: ByteArray, key: PrivateKey): ByteArray {
            val alg = AlgorithmIdentifier(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.sha256WithRSAEncryption, org.bouncycastle.asn1.DERNull.INSTANCE)
            val now = System.currentTimeMillis()
            val name = X500Name("CN=Ultimate Plus")  // "+" separates parts of a name in X.500, so it is spelled out
            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger.valueOf(now)))
                setIssuer(name); setSubject(name)
                setStartDate(Time(Date(now - 86_400_000L))); setEndDate(Time(Date(now + 30L * 365 * 86_400_000L)))
                setSignature(alg)
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(publicKey))
            }.generateTBSCertificate()
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update(tbs.encoded) }.sign()
            return DERSequence(ASN1EncodableVector().apply { add(tbs); add(alg); add(DERBitString(sig)) }).encoded
        }
    }
}
