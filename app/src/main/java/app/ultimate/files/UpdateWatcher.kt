package app.ultimate.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ultimate.R
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/** A newer build of this app found in Downloads. */
data class FoundUpdate(val file: File, val versionName: String, val versionCode: Long)

/** Pure decision: is this APK an update to install? Same app, same signer, higher version. */
object UpdateRules {
    fun isUpdate(apkPackage: String?, apkCode: Long, apkSigners: Set<String>, myPackage: String, myCode: Long, mySigners: Set<String>): Boolean =
        apkPackage == myPackage && apkCode > myCode && apkSigners.isNotEmpty() && apkSigners == mySigners
}

/**
 * Notices a newer Ultimate APK landing in Downloads (from the Claude app, a browser or GitHub) and offers it in a
 * notification, so updating is one tap. Only APKs signed with the same key as the installed app are offered.
 */
object UpdateWatcher {
    private const val WORK = "update-watch"
    private const val CHANNEL = "updates"

    /** Re-armed at every app start: a content trigger fires once. Needs all files access to see Downloads. */
    fun arm(context: Context) {
        if (!hasAllFiles(context)) return
        val req = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(
            Constraints.Builder()
                .addContentUriTrigger(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
                .setTriggerContentUpdateDelay(3, TimeUnit.SECONDS).build(),
        ).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /** The newest offered update in Downloads, or null. */
    fun find(context: Context): FoundUpdate? {
        if (!hasAllFiles(context)) return null
        val pm = context.packageManager
        val me = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val myCode = if (Build.VERSION.SDK_INT >= 28) me.longVersionCode else @Suppress("DEPRECATION") me.versionCode.toLong()
        val mySigners = signers(me)
        val roots = listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        val since = System.currentTimeMillis() - 14 * 86_400_000L
        return roots.flatMap { r -> r.walkTopDown().maxDepth(3).onFail { _, _ -> }.filter { it.isFile && it.extension.equals("apk", true) && it.lastModified() > since }.toList() }
            .sortedByDescending { it.lastModified() }.take(20)
            .mapNotNull { f ->
                val info = runCatching { pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull() ?: return@mapNotNull null
                val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
                if (UpdateRules.isUpdate(info.packageName, code, signers(info), context.packageName, myCode, mySigners)) FoundUpdate(f, info.versionName ?: "?", code) else null
            }.maxByOrNull { it.versionCode }
    }

    private fun signers(info: android.content.pm.PackageInfo): Set<String> = runCatching {
        val s = info.signingInfo ?: return emptySet()
        val certs = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        certs.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }.getOrDefault(emptySet())

    fun notify(context: Context, u: FoundUpdate) {
        val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getLong("offered", 0) == u.versionCode && prefs.getString("path", "") == u.file.path) return
        prefs.edit().putLong("offered", u.versionCode).putString("path", u.file.path).apply()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH))
        val install = PendingIntent.getActivity(context, 21, Intent(context, UpdateInstallActivity::class.java).putExtra("path", u.file.path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat).setColor(0xFF6750A4.toInt())
            .setContentTitle("Update ready: ${context.getString(R.string.app_name)} ${u.versionName}")
            .setContentText("Found in Downloads. Tap to install; your data stays.")
            .setContentIntent(install).addAction(0, "Install", install).setAutoCancel(true).build()
        runCatching { nm.notify(31, n) }
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { UpdateWatcher.find(applicationContext)?.let { UpdateWatcher.notify(applicationContext, it) } }
        UpdateWatcher.arm(applicationContext)
        return Result.success()
    }
}

/** Opened from the update notification: hands the APK to Android's installer, which asks to confirm. */
class UpdateInstallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")?.let(::File)
        val installer = ApkInstaller(this)
        if (path == null || !path.exists()) { android.widget.Toast.makeText(this, "The update file is gone from Downloads", android.widget.Toast.LENGTH_LONG).show(); finish(); return }
        installer.permissionIntent()?.let { startActivity(it); android.widget.Toast.makeText(this, "Allow Ultimate to install apps, then tap the update notification again.", android.widget.Toast.LENGTH_LONG).show(); finish(); return }
        lifecycleScope.launch {
            runCatching { installer.install(android.net.Uri.fromFile(path), path.name) }
                .onFailure { android.widget.Toast.makeText(this@UpdateInstallActivity, "Couldn't start the update: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
            finish()
        }
    }
}
