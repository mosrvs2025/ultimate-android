package app.ultimate.watch

import android.content.Context
import app.ultimate.UltimateApp
import app.ultimate.air.PhoneActions
import app.ultimate.air.WatchMessages
import app.ultimate.core.Signal
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The phone's end of the watch link. Every message is shown in the Watch tool; it only does something when "Watch
 * controls the phone" is on, which starts off.
 */
object WatchLink {
    /** The last thing the watch sent, and when. */
    val last = MutableStateFlow<Pair<WatchMessages.Message, Long>?>(null)

    private fun prefs(c: Context) = c.getSharedPreferences("watch_link", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()
    fun action(c: Context, gesture: String) = prefs(c).getString("g.$gesture", WatchMessages.DEFAULTS[gesture] ?: "none") ?: "none"
    fun setAction(c: Context, gesture: String, action: String) = prefs(c).edit().putString("g.$gesture", action).apply()

    fun receive(app: UltimateApp, text: String) {
        val m = WatchMessages.parse(text) ?: return
        last.value = m to System.currentTimeMillis()
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
