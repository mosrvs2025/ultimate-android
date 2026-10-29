package app.ultimate.send

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * The https side of Send to computer. The certificate is made once and kept in the app's private files; it is remade only
 * when the phone's Wi-Fi address changes, so a browser that accepted it keeps accepting it.
 */
class SecureSocket(private val dir: File, private val password: CharArray) {
    private val file = File(dir, "secure.p12")

    fun context(ip: String?): SSLContext {
        val store = load()?.takeIf { s -> ip == null || covers(s, ip) } ?: create(ip)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    fun open(ip: String?, port: Int): SSLServerSocket = context(ip).serverSocketFactory.createServerSocket(port) as SSLServerSocket

    /** SHA-256 fingerprint of the certificate, shown on the phone so the browser warning can be checked against it. */
    fun fingerprint(): String? = runCatching {
        val c = load()?.getCertificate("ultimate") ?: return null
        java.security.MessageDigest.getInstance("SHA-256").digest(c.encoded).take(8).joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    private fun load(): KeyStore? = runCatching { KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } } }.getOrNull()

    private fun covers(store: KeyStore, ip: String): Boolean = runCatching {
        val c = store.getCertificate("ultimate") as X509Certificate
        c.checkValidity()
        c.subjectAlternativeNames.orEmpty().any { it[0] == 7 && it[1].toString() == ip }
    }.getOrDefault(false)

    private fun create(ip: String?): KeyStore {
        val (keys, der) = SelfSigned.create(ip)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null); setKeyEntry("ultimate", keys.private, password, arrayOf(cert)) }
        dir.mkdirs()
        file.outputStream().use { store.store(it, password) }
        return store
    }
}
