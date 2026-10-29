package app.ultimate

import app.ultimate.send.SelfSigned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class SelfSignedTest {
    @Test fun certificateParsesVerifiesAndNamesTheAddress() {
        val (keys, der) = SelfSigned.create("192.168.1.42")
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        cert.verify(keys.public)
        cert.checkValidity()
        assertEquals(3, cert.version)
        assertTrue(cert.subjectX500Principal.name.contains("Ultimate on your phone"))
        val sans = cert.subjectAlternativeNames.map { it[0] as Int to it[1].toString() }
        assertTrue(7 to "192.168.1.42" in sans)
        assertTrue(2 to "ultimate.local" in sans)
    }

    @Test fun longLengthsEncode() {
        assertEquals(listOf(0x04, 0x82, 0x01, 0x00), SelfSigned.der(0x04, ByteArray(256)).take(4).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x04, 0x81, 0x80), SelfSigned.der(0x04, ByteArray(128)).take(3).map { it.toInt() and 0xFF })
    }
}

class SecureSocketTest {
    @org.junit.Test fun browsersCanTalkTlsToIt() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val secure = app.ultimate.send.SecureSocket(dir, "pw".toCharArray())
        val server = secure.open("127.0.0.1", 0)
        val t = Thread { server.accept().use { s -> s.getInputStream().read(); s.getOutputStream().write("ok".toByteArray()) } }.apply { start() }
        // Trust exactly this certificate, as a browser does after "proceed anyway".
        val cert = java.security.KeyStore.getInstance("PKCS12").apply { java.io.File(dir, "secure.p12").inputStream().use { load(it, "pw".toCharArray()) } }.getCertificate("ultimate")
        val trust = java.security.KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("c", cert) }
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
        ctx.socketFactory.createSocket("127.0.0.1", server.localPort).use { c ->
            c.getOutputStream().write(1)
            org.junit.Assert.assertEquals("ok", String(c.getInputStream().readNBytes(2)))
        }
        t.join(3000); server.close()
        org.junit.Assert.assertTrue(secure.fingerprint()!!.count { it == ':' } == 7)
        // Same address next time: same certificate, so an accepted browser keeps working.
        val before = java.io.File(dir, "secure.p12").readBytes().contentHashCode()
        secure.open("127.0.0.1", 0).close()
        org.junit.Assert.assertEquals(before, java.io.File(dir, "secure.p12").readBytes().contentHashCode())
    }
}
