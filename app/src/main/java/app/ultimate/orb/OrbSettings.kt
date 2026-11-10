package app.ultimate.orb

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.provider.Settings

/** The orb's own choices, kept on the phone: where it rests after a drag, and whether it appears by itself when you leave the app. */
object OrbSettings {
    private fun prefs(c: Context) = c.getSharedPreferences("orb", Context.MODE_PRIVATE)

    fun place(c: Context): OrbMath.Place = runCatching { OrbMath.Place.valueOf(prefs(c).getString("place", "Edge")!!) }.getOrDefault(OrbMath.Place.Edge)
    fun setPlace(c: Context, p: OrbMath.Place) { prefs(c).edit().putString("place", p.name).apply() }

    /** True when the orb should show itself whenever Ultimate is left (home, recents, another app) without having to be switched on first. */
    fun auto(c: Context): Boolean = prefs(c).getBoolean("auto", false)
    fun setAuto(c: Context, on: Boolean) { prefs(c).edit().putBoolean("auto", on).apply() }

    /** Called when Ultimate leaves the screen. Starts the orb for the user if they asked for that, and it is allowed to show. */
    fun onLeftApp(c: Context) {
        if (!auto(c) || !Settings.canDrawOverlays(c)) return
        if (!OrbService.on(c)) OrbService.set(c, true) else OrbService.resume(c)
    }
}

/** What is playing now, from the notification access the app may already have. Null when there is none or no access. */
object OrbMedia {
    fun nowPlaying(c: Context): String? = runCatching {
        val msm = c.getSystemService(MediaSessionManager::class.java)
        val s = msm.getActiveSessions(ComponentName(c, app.ultimate.inbox.NotificationCatcher::class.java)).firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } ?: msm.getActiveSessions(ComponentName(c, app.ultimate.inbox.NotificationCatcher::class.java)).firstOrNull() ?: return null
        val m = s.metadata ?: return null
        listOfNotNull(m.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), m.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)).joinToString(" · ").ifBlank { null }
    }.getOrNull()
}
