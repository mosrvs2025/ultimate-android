package app.ultimate.sshclient

import org.json.JSONArray
import org.json.JSONObject

enum class Auth(val label: String) { Key("Key"), Password("Password"), Ask("Ask each time") }

/** Only used to pick a picture for the host; guessed from the name, changeable by the person. */
enum class HostKind(val label: String) { Linux("Linux"), Mac("Mac"), Windows("Windows"), Pi("Raspberry Pi"), Router("Router"), Other("Other") }

data class Host(
    val id: String,
    val name: String,
    val address: String,
    val port: Int = 22,
    val user: String,
    val group: String = "",
    val tags: List<String> = emptyList(),
    val auth: Auth = Auth.Ask,
    val identityId: String = "",
    val kind: HostKind = HostKind.Other,
    val notes: String = "",
    val lastConnected: Long = 0,
    /** Typed into the shell right after connecting, e.g. "cd ~/app; clear". */
    val startup: String = "",
)

/** A key pair's public side; the private key lives encrypted elsewhere and is never shown. */
data class Identity(val id: String, val name: String, val publicKey: String, val fingerprint: String, val createdAt: Long)

data class Snippet(val id: String, val name: String, val command: String, val folder: String = "")

/** Where "user@host:port" points. */
data class Target(val user: String?, val host: String, val port: Int?)

object HostRules {
    /** Reads "host", "user@host", "host:2222", "user@host:2222" and "user@[::1]:2222"; null when it is not an address. */
    fun parseTarget(input: String): Target? {
        val s = input.trim().removePrefix("ssh ").trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val user = s.substringBefore('@', "").takeIf { s.contains('@') && it.isNotEmpty() }
        val rest = if (user != null) s.substringAfter('@') else s.removePrefix("@")
        val host: String
        var port: Int? = null
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']'); if (close < 0) return null
            host = rest.substring(1, close)
            val tail = rest.substring(close + 1)
            if (tail.isNotEmpty()) port = tail.removePrefix(":").toIntOrNull()?.takeIf { tail.startsWith(":") } ?: return null
        } else if (rest.count { it == ':' } == 1) {
            host = rest.substringBefore(':'); port = rest.substringAfter(':').toIntOrNull() ?: return null
        } else host = rest
        if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it in ".-_:%" }) return null
        if (port != null && port !in 1..65535) return null
        return Target(user, host, port)
    }

    fun label(h: Host) = "${h.user}@${h.address}" + if (h.port != 22) ":${h.port}" else ""

    /** The same connection as a command you could type on a computer. */
    fun command(h: Host) = "ssh ${h.user}@${h.address}" + if (h.port != 22) " -p ${h.port}" else ""

    fun guessKind(name: String, address: String = ""): HostKind {
        val s = "$name $address".lowercase()
        return when {
            Regex("\\b(pi|raspberry|raspi|naspi)\\b|raspberry").containsMatchIn(s) -> HostKind.Pi
            Regex("mac|imac|macbook|osx|darwin").containsMatchIn(s) -> HostKind.Mac
            Regex("win|windows|desktop-").containsMatchIn(s) -> HostKind.Windows
            Regex("router|gateway|openwrt|pfsense|unifi|mikrotik").containsMatchIn(s) -> HostKind.Router
            Regex("ubuntu|debian|linux|arch|fedora|server|vps|cloud|aws|api|db|web|prod|dev").containsMatchIn(s) -> HostKind.Linux
            else -> HostKind.Other
        }
    }

    /** Every word typed must match something: name, address, user, group or a tag. */
    fun search(hosts: List<Host>, query: String): List<Host> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return hosts
        return hosts.filter { h ->
            val hay = (listOf(h.name, h.address, h.user, h.group, h.kind.label) + h.tags).joinToString(" ").lowercase()
            words.all { it in hay }
        }
    }

    /** Groups in name order, each with its hosts newest-connected first; hosts without a group come last. */
    fun grouped(hosts: List<Host>): List<Pair<String, List<Host>>> {
        val by = hosts.groupBy { it.group.trim() }
        val named = by.filterKeys { it.isNotEmpty() }.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (g, l) -> g to l.sortedWith(recent) }
        return named + listOfNotNull(by[""]?.let { "" to it.sortedWith(recent) })
    }

    private val recent = compareByDescending<Host> { it.lastConnected }.thenBy { it.name.lowercase() }

    /** The host a name-less quick connect should become. */
    fun fromTarget(t: Target, id: String, defaultUser: String = "root"): Host =
        Host(id, t.host, t.host, t.port ?: 22, t.user ?: defaultUser, kind = guessKind(t.host))
}

/** Remembers each server's key the first time, and warns when it changes, like ssh's known_hosts. */
object KnownHosts {
    enum class Verdict { New, Match, Changed }

    fun key(address: String, port: Int) = "${address.lowercase()}:$port"

    fun check(known: Map<String, String>, address: String, port: Int, fingerprint: String): Verdict = when (known[key(address, port)]) {
        null -> Verdict.New
        fingerprint -> Verdict.Match
        else -> Verdict.Changed
    }
}

object Snippets {
    /** %-free placeholders so shell syntax like $HOME and %d is left alone. */
    fun fill(command: String, vars: Map<String, String>): String {
        var out = command
        vars.forEach { (k, v) -> out = out.replace("{{$k}}", v) }
        return out
    }

    val starters = listOf(
        Snippet("s-disk", "Disk usage", "df -h | grep -E '^/dev/'", "System"),
        Snippet("s-mem", "Memory", "free -h", "System"),
        Snippet("s-cpu", "Busiest processes", "ps aux --sort=-%cpu | head -15", "System"),
        Snippet("s-up", "Uptime and load", "uptime", "System"),
        Snippet("s-ports", "Listening ports", "ss -tulpn 2>/dev/null || netstat -tulpn", "Network"),
        Snippet("s-ip", "My addresses", "ip -brief addr 2>/dev/null || ifconfig", "Network"),
        Snippet("s-big", "Biggest files here", "ls -lSrh | tail -15", "Files"),
        Snippet("s-logs", "Recent system log", "journalctl -n 50 --no-pager 2>/dev/null || tail -50 /var/log/syslog", "System"),
        Snippet("s-upd", "Update packages (apt)", "sudo apt update && sudo apt upgrade", "Maintenance"),
    )
}

object VaultJson {
    fun host(h: Host): JSONObject = JSONObject().put("id", h.id).put("name", h.name).put("addr", h.address).put("port", h.port).put("user", h.user).put("group", h.group)
        .put("tags", JSONArray(h.tags)).put("auth", h.auth.name).put("key", h.identityId).put("kind", h.kind.name).put("notes", h.notes).put("last", h.lastConnected).put("startup", h.startup)

    fun host(o: JSONObject): Host? = runCatching {
        val t = o.optJSONArray("tags")
        Host(o.getString("id"), o.getString("name"), o.getString("addr"), o.optInt("port", 22), o.getString("user"), o.optString("group"),
            if (t == null) emptyList() else List(t.length()) { t.getString(it) }, runCatching { Auth.valueOf(o.optString("auth")) }.getOrDefault(Auth.Ask),
            o.optString("key"), runCatching { HostKind.valueOf(o.optString("kind")) }.getOrDefault(HostKind.Other), o.optString("notes"), o.optLong("last"), o.optString("startup"))
    }.getOrNull()

    fun identity(i: Identity): JSONObject = JSONObject().put("id", i.id).put("name", i.name).put("pub", i.publicKey).put("fp", i.fingerprint).put("at", i.createdAt)
    fun identity(o: JSONObject): Identity? = runCatching { Identity(o.getString("id"), o.getString("name"), o.getString("pub"), o.getString("fp"), o.optLong("at")) }.getOrNull()

    fun snippet(s: Snippet): JSONObject = JSONObject().put("id", s.id).put("name", s.name).put("cmd", s.command).put("folder", s.folder)
    fun snippet(o: JSONObject): Snippet? = runCatching { Snippet(o.getString("id"), o.getString("name"), o.getString("cmd"), o.optString("folder")) }.getOrNull()
}
