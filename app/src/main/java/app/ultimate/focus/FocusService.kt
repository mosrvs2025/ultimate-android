package app.ultimate.focus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.ultimate.R
import app.ultimate.UltimateApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a focus session in the background: a notification counting down, and every 30 seconds a look at which app is
 * in front (from Android's own usage log, with usage access). Nothing else is read. Checks stop while the session is
 * paused or the screen is off, and the service stops itself when the session ends or runs out.
 */
class FocusService : Service() {
    private val store get() = (application as UltimateApp).focus
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private val policy = FocusPolicy()
    private var launchers: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    // Declared with foregroundServiceType="specialUse" in the manifests of the editions that include Focus.
    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = store.current.value
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Focus session", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(NUDGES, "Focus nudges", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(true); vibrationPattern = longArrayOf(0, 60, 80, 60) })
        ServiceCompat.startForeground(this, ONGOING, ongoing(s), if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (s == null) { stopSelf(); return START_NOT_STICKY }
        launchers = runCatching { packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet() }.getOrDefault(emptySet())
        if (loop == null) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        var lastPaused = false
        var lastCheck = 0L
        while (scope.isActive) {
            val s = store.current.value ?: break
            val now = System.currentTimeMillis()
            if (s.left(now) <= 0) { store.end(now); done(s); break }
            if (s.paused != lastPaused) { lastPaused = s.paused; policy.reset(); notifyOngoing() }
            val awake = getSystemService(PowerManager::class.java).isInteractive
            if (!awake) policy.reset()
            if (!s.paused && awake && now - lastCheck >= EVERY) {
                lastCheck = now
                val pkg = withContext(Dispatchers.IO) { frontApp(now) }
                if (pkg != null) {
                    val category = runCatching { packageManager.getApplicationInfo(pkg, 0).category }.getOrNull()
                    val (verdict, strength) = FocusClassifier.classify(pkg, s.allowed, category, packageName, launchers)
                    val c = Check(now, pkg, verdict, strength)
                    store.addCheck(c)
                    if (policy.next(c)) nudge(s, pkg, now)
                } else policy.reset() // no usage access, or nothing known: say nothing
            }
            delay(1_000)
        }
        stopSelf()
    }

    /** The app most recently brought to the front, from Android's usage log. */
    private fun frontApp(now: Long): String? = runCatching {
        val q = getSystemService(UsageStatsManager::class.java).queryEvents(now - 10 * 60_000L, now)
        val e = UsageEvents.Event(); var last: String? = null
        while (q.hasNextEvent()) { q.getNextEvent(e); if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName }
        last
    }.getOrNull()

    private fun label(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun nudge(s: Session, pkg: String, now: Long) {
        store.addNudge(now)
        val text = FocusMath.nudge(s.task, label(pkg), s.nudges.size)
        val open = PendingIntent.getActivity(this, 32, focusIntent(this), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(NUDGE, NotificationCompat.Builder(this, NUDGES).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Focus").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setTimeoutAfter(60_000).build())
        chirp()
    }

    /** A soft two-note chirp, only when the phone is not on silent or vibrate. */
    private fun chirp() = runCatching {
        if (getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL) return@runCatching
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45)
        scope.launch { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 70); delay(110); tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 90); delay(200); tone.release() }
    }

    private fun done(s: Session) {
        val share = FocusMath.onTaskShare(store.history.value.firstOrNull { it.id == s.id } ?: s)
        val text = "Time's up on “${s.task.ifBlank { "your task" }}”." + (share?.let { " On task ${Math.round(it * 100)}% of the time." } ?: "")
        getSystemService(NotificationManager::class.java).notify(NUDGE, NotificationCompat.Builder(this, NUDGES).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Focus session done").setContentText(text).setContentIntent(PendingIntent.getActivity(this, 33, focusIntent(this), PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).build())
        chirp()
    }

    private fun notifyOngoing() = runCatching { getSystemService(NotificationManager::class.java).notify(ONGOING, ongoing(store.current.value)) }

    /** A live countdown in the notification shade; Android ticks it, so the service does not have to. */
    private fun ongoing(s: Session?) = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat).setOngoing(true).setSilent(true)
        .setContentTitle(s?.task?.ifBlank { null } ?: "Focus")
        .setContentText(if (s?.paused == true) "Paused · ${FocusMath.countdown(s.left(System.currentTimeMillis()))} left" else "Focusing · tap to see how it's going")
        .apply { if (s != null && !s.paused) setUsesChronometer(true).setChronometerCountDown(true).setWhen(System.currentTimeMillis() + s.left(System.currentTimeMillis())).setShowWhen(true) }
        .setContentIntent(PendingIntent.getActivity(this, 31, focusIntent(this), PendingIntent.FLAG_IMMUTABLE)).build()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val CHANNEL = "focus"; const val NUDGES = "focus-nudges"; const val ONGOING = 7; const val NUDGE = 8
        const val EVERY = 30_000L
        fun start(context: Context) { runCatching { context.startForegroundService(Intent(context, FocusService::class.java)) } }
    }
}
