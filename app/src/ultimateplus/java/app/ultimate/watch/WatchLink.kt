package app.ultimate.watch

import android.content.Context
import app.ultimate.UltimateApp
import app.ultimate.air.WatchFeed
import app.ultimate.air.PhoneActions
import app.ultimate.air.WatchMessages
import app.ultimate.health.LiveHeart
import app.ultimate.air.WristCalibration
import app.ultimate.core.Signal
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The phone's end of the watch link. Every message is shown in the Watch tool; it only does something when "Watch
 * controls the phone" is on, which starts off.
 */
object WatchLink {
    /** The watch app's version (code, name), once it says hello. */
    val watchVersion = MutableStateFlow<Pair<Long, String>?>(null)
    /** How a phone-sent update is going, as the watch reports it ("receiving", "confirm", "installed", "failed:why"...). */
    val updateStatus = MutableStateFlow<String?>(null)

    /** The watch's live sensors, the last few seconds, while the Watch screen asks for them. */
    val sensors = MutableStateFlow<List<WristCalibration.Sample>>(emptyList())
    /** Gestures the watch recognised, with when, for marking them on the live graph. */
    val marks = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
    private val debounce = app.ultimate.air.FlickDebounce()

    /** Path the phone talks to the watch on (stream on/off, calibration); must match the watch's PhoneLink.FROM_PHONE. */
    const val TO_WATCH = "/ultimate/phone"

    /** Sends [text] to every connected watch. */
    fun sendToWatch(c: Context, text: String) {
        com.google.android.gms.wearable.Wearable.getNodeClient(c).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { com.google.android.gms.wearable.Wearable.getMessageClient(c).sendMessage(it.id, TO_WATCH, text.toByteArray()) }
        }
    }

    /** The last thing the watch sent, and when. */
    val last = MutableStateFlow<Pair<WatchMessages.Message, Long>?>(null)

    private fun prefs(c: Context) = c.getSharedPreferences("watch_link", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()
    fun action(c: Context, gesture: String) = prefs(c).getString("g.$gesture", WatchMessages.DEFAULTS[gesture] ?: "none") ?: "none"
    fun setAction(c: Context, gesture: String, action: String) = prefs(c).edit().putString("g.$gesture", action).apply()

    fun receive(app: UltimateApp, text: String) {
        val now = System.currentTimeMillis()
        if (text.startsWith("hello:")) {
            val p = text.split(':', limit = 3)
            p.getOrNull(1)?.toLongOrNull()?.let { watchVersion.value = it to (p.getOrNull(2) ?: "?") }
            return
        }
        if (text.startsWith("update:")) { updateStatus.value = text.removePrefix("update:"); if (text == "update:installed") watchVersion.value = null; return }
        if (text.startsWith("hr:")) { text.removePrefix("hr:").toIntOrNull()?.takeIf { it in 25..240 }?.let { LiveHeart.bpm.value = it to now }; return }
        if (text.startsWith("sensors:")) {
            val fresh = WristCalibration.parseSensors(text, now)
            // Kept for 30 seconds, so the graph can be paused and scrubbed back.
            sensors.value = (sensors.value + fresh).filter { now - it.t <= 30_000 }
            WatchFeed.samples.value = sensors.value
            return
        }
        val m = WatchMessages.parse(text) ?: return
        // A flick's rebound (the wrist stopping) is part of the same flick.
        if (!m.isButton && !debounce.accept(m.kind, now)) return
        last.value = m to now
        if (!m.isButton) marks.value = (marks.value + (now to m.kind)).filter { now - it.first <= 30_000 }
        if (!enabled(app)) return
        val label = WatchMessages.label(m)
        // Wrist gestures are signals for Rules too ("When I flick my wrist left…"); buttons just do their job.
        if (!m.isButton) app.senses.publish(Signal("watch", m.kind, "You did a ${label.lowercase()} on your watch"))
        val action = if (m.isButton) m.kind else action(app, m.kind)
        android.os.Handler(android.os.Looper.getMainLooper()).post { PhoneActions.perform(app, action) { app.shell.showHome() } }
    }
}

/** Wear OS wakes this when the watch sends a message, even if Ultimate+ isn't open. */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WatchMessages.PATH) WatchLink.receive(application as UltimateApp, String(event.data))
    }
}
