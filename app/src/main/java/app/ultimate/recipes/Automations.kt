package app.ultimate.recipes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ultimate.MainActivity
import app.ultimate.R
import app.ultimate.UltimateApp
import java.util.concurrent.TimeUnit

/** The things Ultimate can do on its own, each off until the user turns it on. */
class Automations(private val context: Context) {
    private val prefs = context.getSharedPreferences("automations", Context.MODE_PRIVATE)

    var weeklyCheckup: Boolean
        get() = prefs.getBoolean("weekly", false)
        set(v) { prefs.edit().putBoolean("weekly", v).apply(); scheduleWeekly(v) }

    var screenRecordings: Boolean
        get() = prefs.getBoolean("screenrec", false)
        set(v) {
            prefs.edit().putBoolean("screenrec", v).apply()
            if (v) { prefs.edit().putLong("screenrecSince", System.currentTimeMillis() / 1000).apply(); watchScreenRecordings() }
            else WorkManager.getInstance(context).cancelUniqueWork(SCREEN)
        }

    private fun scheduleWeekly(on: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!on) { wm.cancelUniqueWork(WEEKLY); return }
        val req = PeriodicWorkRequestBuilder<WeeklyCheckupWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
        wm.enqueueUniquePeriodicWork(WEEKLY, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Wakes when the phone's video collection changes; the worker re-arms itself after each look. */
    fun watchScreenRecordings() {
        if (!screenRecordings) return
        val req = OneTimeWorkRequestBuilder<ScreenRecordingWorker>().setConstraints(
            Constraints.Builder().addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true).setTriggerContentUpdateDelay(5, TimeUnit.SECONDS).build(),
        ).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SCREEN, ExistingWorkPolicy.REPLACE, req)
    }

    fun takeScreenRecordingsSince(): Long = prefs.getLong("screenrecSince", System.currentTimeMillis() / 1000)
    fun markScreenRecordingsSeen(at: Long) = prefs.edit().putLong("screenrecSince", at).apply()

    companion object {
        const val WEEKLY = "weekly-checkup"
        const val SCREEN = "screen-recordings"
        const val CHANNEL = "automations"

        fun notify(context: Context, id: Int, icon: Int, title: String, text: String, open: Intent) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Automations", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(icon).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi).build()
            runCatching { nm.notify(id, n) }
        }
    }
}

/** Runs a checkup quietly once a week and speaks up only when something changed. */
class WeeklyCheckupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as UltimateApp
        val changes = runCatching { app.checkupManager.runScan() }.getOrElse { return Result.retry() }
        val bad = changes.filter { it.bad }
        if (changes.isNotEmpty()) Automations.notify(
            app, 301, R.drawable.ic_stat_checkup,
            if (bad.isEmpty()) "Weekly checkup: ${changes.size} change${if (changes.size == 1) "" else "s"}" else "Weekly checkup: ${bad.size} thing${if (bad.size == 1) "" else "s"} to look at",
            (bad.ifEmpty { changes }).take(3).joinToString("\n") { it.text },
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "checkup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return Result.success()
    }
}

/** Offers to shrink new screen recordings, which are often huge for what they show. */
class ScreenRecordingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as UltimateApp
        val auto = app.automations
        val since = auto.takeScreenRecordingsSince()
        val found = mutableListOf<Uri>()
        var newest = since
        runCatching {
            app.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.RELATIVE_PATH),
                "${MediaStore.Video.Media.DATE_ADDED} > ?", arrayOf(since.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(2).orEmpty()
                    newest = maxOf(newest, c.getLong(1))
                    if (RecipeRules.isScreenRecording(path)) found += Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                }
            }
        }
        auto.markScreenRecordingsSeen(newest)
        if (found.isNotEmpty()) Automations.notify(
            app, 302, R.drawable.ic_stat_squeeze,
            if (found.size == 1) "New screen recording" else "${found.size} new screen recordings",
            "Tap to make ${if (found.size == 1) "it" else "them"} smaller with Squeeze.",
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "squeeze").putParcelableArrayListExtra(MainActivity.EXTRA_FILES, ArrayList(found))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        auto.watchScreenRecordings()
        return Result.success()
    }
}
