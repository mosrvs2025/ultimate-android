package app.ultimate.send

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import app.ultimate.MainActivity
import app.ultimate.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Things the computer can ask the phone to do: show its screen, open a link, ring. */
class PhoneActions(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    // ---- The computer's screen or camera, frame by frame ----
    data class Screen(val frame: ByteArray, val from: String, val at: Long)
    private val _screen = MutableStateFlow<Screen?>(null)
    val screen: StateFlow<Screen?> = _screen.asStateFlow()
    private var announced = false

    fun frame(bytes: ByteArray, from: String) {
        _screen.value = Screen(bytes, from, System.currentTimeMillis())
        if (!announced) { announced = true; notifyScreen(from) }
        // No frame for 4 seconds means the computer stopped sharing.
        main.removeCallbacks(expire); main.postDelayed(expire, 4_000)
    }
    fun stopScreen() { main.removeCallbacks(expire); expire.run() }
    private val expire = Runnable { _screen.value = null; announced = false; nm.cancel(41) }

    private fun channel() = nm.createNotificationChannel(NotificationChannel(CHANNEL, "From your computer", NotificationManager.IMPORTANCE_HIGH))

    private fun notifyScreen(from: String) {
        channel()
        val open = PendingIntent.getActivity(context, 41, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "send").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(41, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("$from is sharing its screen")
            .setContentText("Tap to watch on your phone").setContentIntent(open).setAutoCancel(true).build())
    }

    // ---- Links ----
    /** Android stops apps opening screens from the background, so a link arrives as a notification that opens it. */
    fun openLink(url: String) {
        val uri = Uri.parse(if (url.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*"))) url else "https://$url")
        channel()
        val pi = PendingIntent.getActivity(context, url.hashCode(), Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(42, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Open from your computer")
            .setContentText(url).setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build())
    }

    // ---- Ring ----
    private var player: MediaPlayer? = null
    private var savedVolume = -1

    /** Rings loudly on the alarm stream, so it sounds even on silent, for up to 30 seconds. */
    fun ring() {
        main.post {
            if (player != null) return@post
            val am = context.getSystemService(AudioManager::class.java)
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(context, tone); isLooping = true; prepare(); start()
                }
            }.getOrNull()
            channel()
            val stop = PendingIntent.getActivity(context, 43, Intent(context, MainActivity::class.java).putExtra(EXTRA_STOP_RING, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(43, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle("Found it? Tap to stop ringing")
                .setContentIntent(stop).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MAX).build())
            main.postDelayed(::stopRing, 30_000)
        }
    }

    fun stopRing() {
        main.post {
            player?.runCatching { stop(); release() }; player = null
            if (savedVolume >= 0) runCatching { context.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = -1
            nm.cancel(43)
        }
    }
    val ringing get() = player != null

    companion object {
        const val CHANNEL = "from-computer"
        const val EXTRA_STOP_RING = "app.ultimate.STOP_RING"
    }
}
