package app.ultimate.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ultimate.MainActivity
import app.ultimate.R
import app.ultimate.UltimateApp
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What a tool is doing in the background, as the notification should describe it. */
data class WorkSnapshot(
    val active: Boolean = false,
    val title: String = "",
    val detail: String? = null,
    /** 0..100, or null when progress is unknown. */
    val percent: Int? = null,
    /** The run ended without doing what it set out to do. */
    val failed: Boolean = false,
    /** Set when work has just finished; shown as a notification when [active] flips to false. */
    val doneTitle: String? = null,
    val doneText: String? = null,
)

/** Implemented by anything that runs long work and wants it kept alive in the background. */
interface WorkSource {
    val workId: String
    /** Human name for the Activity tab, e.g. "Squeeze". */
    val workTitle: String
    val work: StateFlow<WorkSnapshot>
    /** The current or most recent run's log. */
    val log: JobLog
    fun cancelWork()
    /** Text of the notification button that calls [cancelWork]. */
    val cancelLabel: String get() = "Cancel"
    /** Monochrome status-bar icon, so each tool's notifications are told apart at a glance. */
    val notifyIcon: Int get() = R.drawable.ic_stat
    /** Accent for the notification, as 0xAARRGGBB. */
    val notifyColor: Int get() = 0xFF6750A4.toInt()
}

/** Every tool registers its work here; one foreground service keeps the process alive for all of them. */
class WorkHub(private val app: Context) {
    private val registered = CopyOnWriteArrayList<WorkSource>()
    val sources: List<WorkSource> get() = registered

    fun register(source: WorkSource) {
        registered += source
    }

    fun start() = ContextCompat.startForegroundService(app, Intent(app, WorkService::class.java))
}

class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private val hub get() = (application as UltimateApp).work
    private var previouslyActive: List<Boolean> = emptyList()
    private val startedAt = HashMap<Int, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    @android.annotation.SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_ID)?.takeIf { intent.action == ACTION_CANCEL }?.let { id ->
            hub.sources.firstOrNull { it.workId == id }?.cancelWork()
        }
        createChannels()
        val snapshots = hub.sources.map { it.work.value }
        // foregroundServiceType="dataSync" is declared on this service in the main manifest and is present in every edition's merged
        // manifest. Lint reports it missing only in editions that also declare the VPN service, so that report is a false positive.
        ServiceCompat.startForeground(this, ONGOING_ID, ongoing(snapshots), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watcher == null) {
            watcher = scope.launch {
                combine(hub.sources.map { it.work }) { it.toList() }.collect { onChange(it) }
            }
        }
        return START_NOT_STICKY
    }

    private fun onChange(snaps: List<WorkSnapshot>) {
        val nm = getSystemService(NotificationManager::class.java)
        snaps.forEachIndexed { i, s ->
            if (s.active && previouslyActive.getOrNull(i) != true) startedAt[i] = android.os.SystemClock.elapsedRealtime()
            if (previouslyActive.getOrNull(i) == true && !s.active) hub.sources.getOrNull(i)?.let { src ->
                startedAt.remove(i)?.let { t -> (application as UltimateApp).insights.job(src.workId, android.os.SystemClock.elapsedRealtime() - t, !s.failed) }
                if (s.doneTitle != null) nm.notify(DONE_ID + i, done(s, src))
            }
        }
        previouslyActive = snaps.map { it.active }
        if (snaps.any { it.active }) {
            nm.notify(ONGOING_ID, ongoing(snaps))
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Opens the app, straight into [toolId]'s screen when given. */
    private fun open(toolId: String? = null) = PendingIntent.getActivity(
        this, toolId?.hashCode() ?: 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { toolId?.let { putExtra(MainActivity.EXTRA_TOOL, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(snaps: List<WorkSnapshot>): Notification {
        val index = snaps.indexOfFirst { it.active }.coerceAtLeast(0)
        val s = snaps.getOrNull(index) ?: WorkSnapshot(title = "Working…")
        val more = snaps.count { it.active } - 1
        val source = hub.sources.getOrNull(index)
        val builder = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(source?.notifyIcon ?: R.drawable.ic_stat)
            .setColor(source?.notifyColor ?: 0)
            .setSubText(source?.workTitle)
            .setContentTitle(s.title + if (more > 0) "  (+$more more)" else "")
            .setContentText(s.detail)
            .setProgress(100, s.percent ?: 0, s.percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(source?.workId))
        source?.let { source ->
            val cancel = PendingIntent.getService(
                this, index + 1,
                Intent(this, WorkService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, source.workId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, source.cancelLabel, cancel)
        }
        return builder.build()
    }

    private fun done(s: WorkSnapshot, source: WorkSource): Notification = NotificationCompat.Builder(this, CH_DONE)
        .setSmallIcon(source.notifyIcon)
        .setColor(source.notifyColor)
        .setSubText(source.workTitle)
        .setContentTitle(s.doneTitle)
        .setContentText(s.doneText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(s.doneText))
        .setAutoCancel(true)
        .setContentIntent(open(source.workId))
        .build()

    private companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val ONGOING_ID = 1
        const val DONE_ID = 100
        const val ACTION_CANCEL = "app.ultimate.CANCEL"
        const val EXTRA_ID = "workId"
    }
}
