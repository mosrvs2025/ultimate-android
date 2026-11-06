package app.ultimate.watch

import org.junit.Assert.assertNotNull
import org.junit.Test

/** The pairing library looks these up by name; if they're missing it falls back to the phone's copy, which failed on Samsung. */
class ConscryptBundledTest {
    @Test fun theBundledConscryptHasWhatPairingLooksFor() {
        val c = Class.forName("org.conscrypt.Conscrypt")
        assertNotNull(c.getMethod("exportKeyingMaterial", javax.net.ssl.SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType))
        assertNotNull(Class.forName("org.conscrypt.OpenSSLProvider"))
    }
}
