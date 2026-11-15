package app.ultimate.web

import java.util.UUID

/**
 * Screens that joined by opening a page: a laptop, an iPhone, a TV or the Quest, with nothing installed. Each one that opens
 * the phone's /join page gets an id and an inbox; the page asks the phone "anything for me?" and waits up to [WAIT_MS] for
 * an answer (long polling, which every browser can do). The phone drops things into the inbox: a link to open, text,
 * a file to download, an app to get, a chime. A screen that has not asked for [GONE_MS] has gone (tab closed, sleeping).
 *
 * Plain JVM, no Android, so the waiting and expiry are tested directly. [clock] is replaceable for tests.
 */
class WebNodes(private val clock: () -> Long = System::currentTimeMillis) {
    data class Node(val id: String, val name: String, val kind: String, val width: Int, val height: Int, val joinedAt: Long, val lastSeen: Long)

    private val lock = Object()
    private val nodes = LinkedHashMap<String, Node>()
    private val inbox = HashMap<String, ArrayDeque<String>>()
    private val blobs = LinkedHashMap<String, Blob>()

    data class Blob(val name: String, val mime: String, val bytes: ByteArray, val at: Long)

    /** A screen says hello; it keeps its id across reloads if it sends the one it had. */
    fun hello(id: String?, name: String, userAgent: String, width: Int, height: Int): Node = synchronized(lock) {
        val now = clock()
        val use = id?.takeIf { it.matches(Regex("[a-f0-9]{8,32}")) } ?: UUID.randomUUID().toString().replace("-", "").take(16)
        val n = Node(use, clean(name).ifBlank { guessName(userAgent) }, kind(userAgent), width.coerceIn(0, 20_000), height.coerceIn(0, 20_000), nodes[use]?.joinedAt ?: now, now)
        nodes[use] = n; inbox.getOrPut(use) { ArrayDeque() }
        n
    }

    fun list(): List<Node> = synchronized(lock) { expire(); nodes.values.toList() }

    /** Queues [json] for [id]; false if that screen is not here. */
    fun send(id: String, json: String): Boolean = synchronized(lock) {
        expire()
        val q = inbox[id] ?: return false
        q.addLast(json); while (q.size > 50) q.removeFirst()
        lock.notifyAll(); true
    }

    /** What is waiting for [id], waiting up to [waitMs] for something to arrive; null if the screen is unknown (it should say hello again). */
    fun poll(id: String, waitMs: Long = WAIT_MS): List<String>? = synchronized(lock) {
        val n = nodes[id] ?: return null
        nodes[id] = n.copy(lastSeen = clock())
        val q = inbox.getValue(id)
        val until = System.currentTimeMillis() + waitMs
        while (q.isEmpty()) { val left = until - System.currentTimeMillis(); if (left <= 0) break; lock.wait(left) }
        val out = q.toList(); q.clear()
        nodes[id]?.let { nodes[id] = it.copy(lastSeen = clock()) }
        out
    }

    /** Keeps a file for screens to download for [BLOB_MS]; returns its token. */
    fun keep(name: String, mime: String, bytes: ByteArray): String = synchronized(lock) {
        expire()
        val t = UUID.randomUUID().toString().replace("-", "")
        blobs[t] = Blob(name, mime, bytes, clock()); while (blobs.size > 8) blobs.remove(blobs.keys.first())
        t
    }

    fun blob(token: String): Blob? = synchronized(lock) { expire(); blobs[token] }

    private fun expire() {
        val now = clock()
        nodes.values.filter { now - it.lastSeen > GONE_MS }.forEach { nodes.remove(it.id); inbox.remove(it.id) }
        blobs.entries.removeAll { now - it.value.at > BLOB_MS }
    }

    companion object {
        const val WAIT_MS = 20_000L
        const val GONE_MS = 45_000L
        const val BLOB_MS = 10 * 60_000L

        /** What a joined screen is sent: each opens on that screen only when the person there taps it. */
        fun link(url: String, from: String) = org.json.JSONObject().put("type", "link").put("url", url).put("from", from).toString()
        fun text(text: String, from: String) = org.json.JSONObject().put("type", "text").put("text", text.take(4_000)).put("from", from).toString()
        fun app(pkg: String, label: String, from: String) = org.json.JSONObject().put("type", "app").put("label", label)
            .put("url", "https://play.google.com/store/apps/details?id=$pkg").put("from", from).toString()
        fun file(token: String, name: String, mime: String, size: Int, from: String) = org.json.JSONObject().put("type", "file").put("url", "node/blob/$token")
            .put("name", name).put("mime", mime).put("size", size).put("from", from).toString()
        fun chime(from: String) = org.json.JSONObject().put("type", "chime").put("from", from).toString()

        private fun clean(s: String) = s.filter { it >= ' ' }.trim().take(40)

        /** A friendly name from the browser's description of itself, when the person has not typed one. */
        fun guessName(ua: String): String = when {
            "OculusBrowser" in ua || "Quest" in ua -> "Quest"
            "iPhone" in ua -> "iPhone"; "iPad" in ua -> "iPad"
            "Android" in ua && "Mobile" in ua -> "Android phone"; "Android" in ua -> "Android tablet"
            "CrOS" in ua -> "Chromebook"; "Macintosh" in ua -> "Mac"; "Windows" in ua -> "Windows PC"
            "SmartTV" in ua || "Tizen" in ua || "Web0S" in ua || "AFT" in ua -> "TV"
            "Linux" in ua -> "Linux computer"
            else -> "Browser"
        }

        /** Which drop shape to draw: phone, tablet, computer, headset or TV. */
        fun kind(ua: String): String = when {
            "OculusBrowser" in ua || "Quest" in ua -> "headset"
            "iPhone" in ua || ("Android" in ua && "Mobile" in ua) -> "phone"
            "iPad" in ua || "Android" in ua -> "tab"
            "SmartTV" in ua || "Tizen" in ua || "Web0S" in ua || "AFT" in ua -> "tv"
            else -> "pc"
        }
    }
}
