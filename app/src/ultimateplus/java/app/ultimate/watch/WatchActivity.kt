package app.ultimate.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import app.ultimate.MainActivity
import app.ultimate.R
import app.ultimate.UltimateApp
import app.ultimate.core.Signal
import app.ultimate.movement.MovementRecorder
import app.ultimate.movement.MovementService
import app.ultimate.social.FriendsHub
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the watch says about walking. The watch's own step counter decides when you start and stop walking or running (see the watch
 * app's StepActivity) and tells the phone at once; while you move it sends its step count every half minute.
 *
 * On the phone that becomes: a signal for Rules ("when my watch says I started walking, ..."), an offer to record with Movement
 * (a notification with Record, because Android does not let an app start recording GPS on its own from the background; one tap
 * does), and, while Movement is recording, the watch's step count written into the recording as an independent check.
 *
 * Samsung Health's own automatic workout detection is not visible to other apps while it runs; its finished workouts reach Ultimate
 * later through Health Connect. This link is Ultimate's own, so it works the moment you start moving.
 */
object WatchActivity {
    data class Now(val state: String, val spm: Int, val at: Long)
    val now = MutableStateFlow<Now?>(null)

    private fun prefs(c: Context) = c.getSharedPreferences("movement", Context.MODE_PRIVATE)
    /** Whether to offer a recording when the watch says you started walking or running. On unless turned off. */
    fun offers(c: Context) = prefs(c).getBoolean("offer_from_watch", true)
    fun setOffers(c: Context, on: Boolean) = prefs(c).edit().putBoolean("offer_from_watch", on).apply()

    /** Handles an "activity:" or "steps:" message from the watch; false if it was something else. */
    fun receive(app: UltimateApp, text: String): Boolean {
        val t = System.currentTimeMillis()
        when {
            text.startsWith("activity:") -> {
                val p = text.removePrefix("activity:").split(':')
                val state = p.getOrNull(0) ?: return true
                val spm = p.getOrNull(1)?.toIntOrNull() ?: 0
                now.value = Now(state, spm, t)
                val label = when (state) { "walking" -> "Your watch says you started walking"; "running" -> "Your watch says you started running"; else -> "Your watch says you stopped" }
                app.senses.publish(Signal("watch", state, label, mapOf("spm" to spm.toString())))
                runCatching { FriendsHub.hear(app, state) } // tells the owner's chosen friends, if they asked it to
                val rec = MovementRecorder.get(app)
                if ((state == "walking" || state == "running") && !rec.live.value.recording && offers(app)) offer(app, state, spm)
                if (state == "still") app.getSystemService(NotificationManager::class.java)?.cancel(OFFER_ID)
            }
            text.startsWith("steps:") -> {
                val p = text.removePrefix("steps:").split(':')
                val count = p.getOrNull(0)?.toLongOrNull() ?: return true
                val spm = p.getOrNull(1)?.toIntOrNull() ?: 0
                now.value = Now(now.value?.state ?: if (spm >= 60) "walking" else "still", spm, t)
                if (spm >= 60) runCatching { FriendsHub.hear(app, now.value?.state?.takeIf { it == "running" } ?: "walking") }
                MovementRecorder.get(app).watchSteps(count)
            }
            else -> return false
        }
        return true
    }

    private fun offer(app: UltimateApp, state: String, spm: Int) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Walking from your watch", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 61, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "movement").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val canRecord = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val b = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (state == "running") "Running? Record it with Movement" else "Walking? Record it with Movement")
            .setContentText("Your watch noticed $spm steps a minute. Tap Record to measure distance, pace and laps.")
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(10 * 60_000L)
        if (canRecord) b.addAction(0, "Record", PendingIntent.getForegroundService(app, 62, Intent(app, MovementService::class.java).setAction(MovementService.ACTION_START), PendingIntent.FLAG_IMMUTABLE))
        nm.notify(OFFER_ID, b.build())
    }

    private const val CHANNEL = "watch-walking"
    const val OFFER_ID = 63
}
