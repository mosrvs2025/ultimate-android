package app.ultimate.sshclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultTest {
    @get:Rule val temp = TemporaryFolder()

    /** Stands in for the Keystore so the vault's behaviour can be tested on a computer. */
    private class FakeSealer : Sealer {
        override fun seal(data: ByteArray) = "sealed:" + java.util.Base64.getEncoder().encodeToString(data.reversedArray())
        override fun open(sealed: String) = if (sealed.startsWith("sealed:")) java.util.Base64.getDecoder().decode(sealed.removePrefix("sealed:")).reversedArray() else null
    }

    private var n = 0
    private fun vault() = VaultStore(temp.root, FakeSealer(), { 1000L }, { "id${n++}" })

    @Test fun hostsSnippetsAndTrustSurviveARestart() {
        val v = vault()
        v.saveHost(Host("h1", "box", "10.0.0.2", user = "dev", group = "Lab"))
        v.trust("10.0.0.2", 22, "SHA256:abc")
        v.touch("h1")
        val again = vault()
        assertEquals(listOf("box"), again.hosts.value.map { it.name })
        assertEquals(1000L, again.hosts.value[0].lastConnected)
        assertEquals(KnownHosts.Verdict.Match, KnownHosts.check(again.knownFingerprints(), "10.0.0.2", 22, "SHA256:abc"))
        assertEquals(Snippets.starters.size, again.snippets.value.size)
    }

    @Test fun secretsAreNeverWrittenInTheClear() {
        val v = vault()
        v.savePassword("h1", "hunter2-super-secret")
        val id = v.generateIdentity("phone")
        val text = temp.root.resolve("ssh-vault.json").readText()
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("PRIVATE KEY"))
        assertFalse(text.contains(java.util.Base64.getEncoder().encodeToString("hunter2-super-secret".toByteArray())))
        assertEquals("hunter2-super-secret", vault().password("h1"))
        assertNotNull(id)
    }

    @Test fun aGeneratedKeyComesBackAsTheSamePair() {
        val v = vault()
        val id = v.generateIdentity("phone")
        assertTrue(id.publicKey.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(id.fingerprint.startsWith("SHA256:"))
        val pair = vault().keyPair(id.id)!!
        assertEquals(id.fingerprint, Keys.fingerprint(pair))
        assertEquals(id.publicKey, Keys.publicLine(pair, ""))
    }

    @Test fun anImportedKeyKeepsItsPassphrase() {
        val pair = Keys.generate()
        val v = vault()
        val id = v.importIdentity("work", Keys.pem(pair), null)!!
        assertEquals(Keys.fingerprint(pair), id.fingerprint)
        assertNull(v.importIdentity("junk", "this is not a key", null))
    }

    @Test fun removingAKeyOrHostCleansUpAfterItself() {
        val v = vault()
        val k = v.generateIdentity("phone")
        v.saveHost(Host("h1", "box", "x", user = "u", auth = Auth.Key, identityId = k.id))
        v.savePassword("h1", "pw")
        v.deleteIdentity(k.id)
        assertEquals(Auth.Ask, v.hosts.value[0].auth)
        assertNull(v.keyPair(k.id))
        v.deleteHost("h1")
        assertFalse(v.hasPassword("h1"))
        assertTrue(vault().hosts.value.isEmpty())
    }

    @Test fun aDamagedFileStartsFreshInsteadOfCrashing() {
        temp.root.resolve("ssh-vault.json").writeText("{ not json")
        val v = vault()
        assertTrue(v.hosts.value.isEmpty())
        assertEquals(Snippets.starters.size, v.snippets.value.size)
    }
}
