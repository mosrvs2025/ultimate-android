package app.ultimate.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class WatchInstallerTest {
    @Test fun thePairingCertificateIsAValidSelfSignedCertificate() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val der = WatchInstaller.selfSigned(pair.public.encoded, pair.private)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(pair.public) // signed by its own key; throws if not
        cert.checkValidity()
        assertEquals(pair.public, cert.publicKey)
        assertTrue(cert.subjectX500Principal.name, cert.subjectX500Principal.name.contains("CN=Ultimate Plus"))
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertTrue(cert.notAfter.time - System.currentTimeMillis() > 20L * 365 * 86_400_000L)
    }
}

class WatchAddressTest {
    @Test fun readsTheAddressAsTheWatchShowsIt() {
        assertEquals("192.168.1.239" to 37581, WatchInstaller.parseAddress("192.168.1.239:37581"))
        assertEquals("192.168.1.239" to 37581, WatchInstaller.parseAddress(" 192.168.1.239 : 37581 "))
        assertEquals("10.0.0.5" to 5555, WatchInstaller.parseAddress("10.0.0.5:5555."))
    }

    @Test fun rejectsWhatIsNotAnAddress() {
        listOf("", "192.168.1.239", "192.168.1:37581", "300.1.1.1:5555", "1.2.3.4:0", "1.2.3.4:99999", "phone:5555").forEach {
            assertEquals(it, null, WatchInstaller.parseAddress(it))
        }
    }
}
