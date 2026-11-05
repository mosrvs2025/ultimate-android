package app.ultimate.sshclient

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallKeyTest {
    @Test fun buildsASafeIdempotentCommand() {
        val c = InstallKey.command("ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBB phone")!!
        assertTrue(c.contains("grep -qxF 'ecdsa-sha2-nistp256 AAAAE2VjZHNh"))
        assertTrue(c.contains("chmod 600 ~/.ssh/authorized_keys"))
        assertTrue(c.endsWith("echo ${InstallKey.DONE}"))
    }

    @Test fun refusesAnythingThatCouldEscapeTheQuotes() {
        listOf("", "  ", "a'b", "a\nb", "a\$(reboot)", "a`id`", "a\\b").forEach { assertNull(it, InstallKey.command(it)) }
        assertNotNull(InstallKey.command("ssh-ed25519 AAAAC3Nza+/= me-laptop"))
    }
}
