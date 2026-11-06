package app.ultimate.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable

/**
 * Sends watch gestures and button presses to Ultimate+ on the phone through Wear OS's own link: Bluetooth when close,
 * Wi-Fi or the internet when the phone is further away. Only the matching app (same package, same signing key) on the
 * phone receives them.
 */
class PhoneLink(context: Context) {
    private val messages = Wearable.getMessageClient(context)
    private val nodes = Wearable.getNodeClient(context)

    /** Sends [message] (like "gesture:flick_left") to every connected phone; [done] says whether any received it. */
    fun send(message: String, done: (Boolean) -> Unit = {}) {
        nodes.connectedNodes.addOnSuccessListener { list ->
            if (list.isEmpty()) { done(false); return@addOnSuccessListener }
            var ok = false; var left = list.size
            list.forEach { node ->
                messages.sendMessage(node.id, PATH, message.toByteArray())
                    .addOnSuccessListener { ok = true }
                    .addOnCompleteListener { if (--left == 0) done(ok) }
            }
        }.addOnFailureListener { done(false) }
    }

    @Volatile private var cached: List<String> = emptyList()
    @Volatile private var cachedAt = 0L

    /** Sends [message] to the phone without waiting for an answer, for the live sensor stream (sent ~10 times a second). */
    fun stream(message: String) {
        val now = System.currentTimeMillis()
        if (now - cachedAt > 5_000) { cachedAt = now; nodes.connectedNodes.addOnSuccessListener { l -> cached = l.map { it.id } } }
        cached.forEach { id -> messages.sendMessage(id, PATH, message.toByteArray()) }
    }

    /** Listens for the phone's messages ("stream:on", "config:..."), until the returned function is called. */
    fun listen(onMessage: (String) -> Unit): () -> Unit {
        val l = com.google.android.gms.wearable.MessageClient.OnMessageReceivedListener { e -> if (e.path == FROM_PHONE) onMessage(String(e.data)) }
        messages.addListener(l)
        return { messages.removeListener(l) }
    }

    /** The name of a connected phone, or null when none is in reach. */
    fun phoneName(out: (String?) -> Unit) {
        nodes.connectedNodes.addOnSuccessListener { out(it.firstOrNull()?.displayName) }.addOnFailureListener { out(null) }
    }

    companion object {
        /** Must match the phone's WatchLink.PATH. */
        const val PATH = "/ultimate/watch"
        /** What the phone sends to the watch on; must match the phone's WatchLink.TO_WATCH. */
        const val FROM_PHONE = "/ultimate/phone"
    }
}
