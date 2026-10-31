package app.ultimate.sshclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostModelTest {
    @Test fun readsEveryWayOfWritingAnAddress() {
        assertEquals(Target("pi", "192.168.2.178", 22), HostRules.parseTarget("pi@192.168.2.178:22"))
        assertEquals(Target(null, "example.com", null), HostRules.parseTarget("example.com"))
        assertEquals(Target("me", "box.local", 2222), HostRules.parseTarget("ssh me@box.local:2222"))
        assertEquals(Target("root", "::1", 2200), HostRules.parseTarget("root@[::1]:2200"))
        assertEquals(Target(null, "fe80::1", null), HostRules.parseTarget("fe80::1"))
    }

    @Test fun refusesWhatIsNotAnAddress() {
        listOf("", "   ", "two words", "user@", "host:99999", "host:abc", "[::1", "a/b").forEach { assertNull(it, HostRules.parseTarget(it)) }
    }

    @Test fun showsTheConnectionTheWayYouWouldTypeIt() {
        val h = Host("1", "n", "10.0.0.5", 2222, "dev")
        assertEquals("dev@10.0.0.5:2222", HostRules.label(h))
        assertEquals("ssh dev@10.0.0.5 -p 2222", HostRules.command(h))
        assertEquals("ssh dev@10.0.0.5", HostRules.command(h.copy(port = 22)))
    }

    @Test fun guessesAPictureFromTheName() {
        assertEquals(HostKind.Pi, HostRules.guessKind("naspi"))
        assertEquals(HostKind.Mac, HostRules.guessKind("sams-macbook"))
        assertEquals(HostKind.Linux, HostRules.guessKind("acme-api-dev-us-west"))
        assertEquals(HostKind.Router, HostRules.guessKind("openwrt"))
        assertEquals(HostKind.Other, HostRules.guessKind("zzz"))
    }

    private val hosts = listOf(
        Host("1", "acme-api-dev", "10.0.0.1", user = "stan", group = "AWS", tags = listOf("api", "ubuntu"), lastConnected = 5),
        Host("2", "acme-db-dev", "10.0.0.2", user = "stan", group = "AWS", tags = listOf("db"), lastConnected = 9),
        Host("3", "naspi", "192.168.2.178", user = "pi", kind = HostKind.Pi),
        Host("4", "momcorp", "10.1.1.1", user = "root", group = "Momcorp"),
    )

    @Test fun everyWordHasToMatch() {
        assertEquals(listOf("1", "2"), HostRules.search(hosts, "stan aws").map { it.id })
        assertEquals(listOf("1"), HostRules.search(hosts, "api ubuntu").map { it.id })
        assertEquals(listOf("3"), HostRules.search(hosts, "pi").map { it.id }.filter { it == "3" })
        assertEquals(4, HostRules.search(hosts, "  ").size)
        assertTrue(HostRules.search(hosts, "nothing like this").isEmpty())
    }

    @Test fun groupsInOrderWithRecentFirstAndUngroupedLast() {
        val g = HostRules.grouped(hosts)
        assertEquals(listOf("AWS", "Momcorp", ""), g.map { it.first })
        assertEquals(listOf("2", "1"), g.first().second.map { it.id })
    }

    @Test fun anUnknownServerIsNewAndAChangedKeyIsFlagged() {
        val known = mapOf(KnownHosts.key("Box.local", 22) to "SHA256:abc")
        assertEquals(KnownHosts.Verdict.Match, KnownHosts.check(known, "box.local", 22, "SHA256:abc"))
        assertEquals(KnownHosts.Verdict.Changed, KnownHosts.check(known, "box.local", 22, "SHA256:other"))
        assertEquals(KnownHosts.Verdict.New, KnownHosts.check(known, "box.local", 2222, "SHA256:abc"))
    }

    @Test fun snippetsFillOnlyTheirOwnPlaceholders() {
        assertEquals("echo dev@box; echo \$HOME 100%", Snippets.fill("echo {{user}}@{{host}}; echo \$HOME 100%", mapOf("user" to "dev", "host" to "box")))
        assertEquals("{{clip}}", Snippets.fill("{{clip}}", mapOf("host" to "x")))
    }

    @Test fun savingKeepsEverythingAndSkipsBrokenEntries() {
        val h = hosts[0].copy(auth = Auth.Key, identityId = "k1", kind = HostKind.Linux, notes = "n", startup = "cd /srv")
        assertEquals(h, VaultJson.host(VaultJson.host(h)))
        assertNull(VaultJson.host(org.json.JSONObject("""{"id":"x"}""")))
        val i = Identity("k1", "phone", "ecdsa-sha2-nistp256 AAAA", "SHA256:zz", 7)
        assertEquals(i, VaultJson.identity(VaultJson.identity(i)))
        val s = Snippets.starters[0]
        assertEquals(s, VaultJson.snippet(VaultJson.snippet(s)))
    }
}
