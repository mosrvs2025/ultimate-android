package app.ultimate.social

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import app.ultimate.MainActivity
import app.ultimate.R
import app.ultimate.UltimateApp
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * The friends feature at work: hears what the watch says about walking, asks [WalkAnnouncer] whether it is time, and posts to the
 * owner's chosen circles. Sending is a short HTTPS call made right on the thread that heard the watch (a few seconds at most), so it
 * is done before Android puts the process back to sleep.
 */
object FriendsHub {
    private var announcer: WalkAnnouncer? = null
    private var boutAnnounced = 0L // when this walk was announced, so its finish can be too

    private fun announcer(c: Context, store: FriendsStore) = (announcer ?: WalkAnnouncer(store.settings).also { announcer = it }).also { it.configure(store.settings); it.restore(store.told) }

    /** Called with each walking report from the watch ("walking", "running" or "still"). */
    fun hear(c: Context, kind: String) {
        val app = c.applicationContext
        val store = FriendsStore(app)
        if (!store.settings.enabled) return
        val now = System.currentTimeMillis()
        val a = announcer(app, store)
        val minute = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        if (kind == "still" && boutAnnounced != 0L) { finish(app, store, now); boutAnnounced = 0L }
        when (val d = a.hear(kind, now, minute)) {
            is Announce.Now -> { boutAnnounced = now; tell(app, store, d.friends, d.state) }
            is Announce.Ask -> ask(app, d)
            null -> Unit
        }
    }

    /** Posts "<name> is going for a walk" to each circle in [circleIds]; remembers when. */
    fun tell(c: Context, store: FriendsStore, circleIds: Set<String>, state: String) {
        val now = System.currentTimeMillis()
        val msg = Delivery.text(store.name, state, store.templateStart)
        val mine = store.circles().filter { it.id in circleIds }
        val told = HashMap(store.told)
        for (circle in mine) if (deliver(store, circle, msg, "walk").ok) told[circle.id] = now
        store.told = told
        if (mine.isNotEmpty()) boutAnnounced = now
    }

    private fun finish(c: Context, store: FriendsStore, now: Long) {
        if (!store.summary) return
        val s = store.settings
        val minutes = ((now - boutAnnounced) / 60_000L).toInt()
        if (minutes < s.steadyMinutes) return
        val msg = Delivery.summary(store.name, "walking", minutes, null, store.templateFinish)
        store.circles().filter { it.id in s.notify }.forEach { deliver(store, it, msg, "finish") }
    }

    /** Sends [message] to one chat and writes what happened in the activity log, which the dashboard shows. Blocking. */
    fun deliver(store: FriendsStore, circle: Circle, message: String, what: String): SendResult {
        val r = send(circle.target, message)
        store.record(LogEntry(System.currentTimeMillis(), circle.target.label, what, r.ok, r.error.orEmpty()))
        return r
    }

    /** One message to one target, retrying once if told to slow down. Blocking: call off the main thread. */
    fun send(t: Target, message: String): SendResult {
        Delivery.problem(t)?.let { return SendResult(false, it) }
        repeat(2) { attempt ->
            val r = Delivery.build(t, message)
            val conn = runCatching { URL(r.url).openConnection() as HttpURLConnection }.getOrNull() ?: return SendResult(false, "Could not open the connection.")
            try {
                conn.requestMethod = "POST"; conn.connectTimeout = 6_000; conn.readTimeout = 6_000; conn.doOutput = true
                conn.setRequestProperty("Content-Type", r.contentType); conn.setRequestProperty("User-Agent", "UltimateAndroid/2")
                r.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(r.body.toByteArray()) }
                val code = conn.responseCode
                val body = runCatching { (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")
                if (code in 200..299) return SendResult(true, null)
                Delivery.retryAfterMs(code, body, conn.getHeaderField("Retry-After"))?.let { if (attempt == 0) { Thread.sleep(it.coerceAtMost(8_000L)); return@repeat } }
                return SendResult(false, when (code) { 401, 403, 404 -> "The chat refused it (the link may have been deleted or revoked)."; 429 -> "Slow down: the chat is rate limiting. Try again in a moment."; else -> "The chat answered $code." })
            } catch (e: Exception) { return SendResult(false, "No connection: ${e.message ?: e.javaClass.simpleName}") } finally { conn.disconnect() }
        }
        return SendResult(false, "Try again in a moment.")
    }

    private fun ask(c: Context, d: Announce.Ask) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Walking with friends", NotificationManager.IMPORTANCE_DEFAULT))
        fun act(a: String, code: Int) = PendingIntent.getBroadcast(c, code, Intent(c, FriendsReceiver::class.java).setAction(a).putExtra("circles", d.friends.joinToString(",")).putExtra("state", d.state), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(c, 71, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "friends").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(ASK_ID, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (d.state == "running") "Out for a run? Tell your friends?" else "Out for a walk? Tell your friends?")
            .setContentText("They'll get a message in the chat you chose.").setContentIntent(open).setAutoCancel(true).setTimeoutAfter(15 * 60_000L)
            .addAction(0, "Tell them", act(FriendsReceiver.TELL, 72)).addAction(0, "Not this time", act(FriendsReceiver.SKIP, 73)).build())
    }

    const val CHANNEL = "friends-walks"
    const val ASK_ID = 74
}

/** The two buttons on the "Tell your friends?" notification. */
class FriendsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.getSystemService(NotificationManager::class.java)?.cancel(FriendsHub.ASK_ID)
        if (i.action != TELL) return
        val ids = (i.getStringExtra("circles") ?: return).split(',').filter { it.isNotBlank() }.toSet()
        val state = i.getStringExtra("state") ?: "walking"
        val pending = goAsync()
        Thread { try { FriendsHub.tell(c, FriendsStore(c), ids, state) } finally { pending.finish() } }.start()
    }
    companion object { const val TELL = "app.ultimate.social.TELL"; const val SKIP = "app.ultimate.social.SKIP" }
}
