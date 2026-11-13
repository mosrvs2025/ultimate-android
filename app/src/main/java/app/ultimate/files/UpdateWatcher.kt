package app.ultimate.files

import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.setContent
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
import app.ultimate.insights.track
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
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
        // A safety net: content triggers can be missed (or fire while a big APK is still downloading), so also look
        // every 15 minutes. Finding nothing new costs a directory listing.
        wm.enqueueUniquePeriodicWork("$WORK-periodic", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES).build())
    }

    /** Looks again shortly: used when an APK in Downloads was still being written and couldn't be read yet. */
    fun recheckSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("$WORK-retry", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateWorker>().setInitialDelay(20, TimeUnit.SECONDS).build())
    }

    /** True when a recent APK in Downloads can't be read yet (most likely still downloading). */
    @Volatile var unreadableRecent = false

    /** What was learned about one APK file, kept so it is not read again until the file changes. */
    private class Parsed(val pkg: String, val code: Long, val versionName: String, val signers: Set<String>)
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Parsed?>()

    /**
     * The newest offered update in Downloads, or null. Reading an APK's signature means reading the whole file (these are over
     * 70 MB), and this runs whenever Home, About or the app comes to the front, so each file is parsed once (by path, size and
     * date) and only the six newest recent files are looked at. That was most of the slowness.
     */
    fun find(context: Context): FoundUpdate? {
        if (!hasAllFiles(context)) return null
        val pm = context.packageManager
        val me = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val myCode = if (Build.VERSION.SDK_INT >= 28) me.longVersionCode else @Suppress("DEPRECATION") me.versionCode.toLong()
        val mySigners = signers(me)
        val roots = listOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        val since = System.currentTimeMillis() - 14 * 86_400_000L
        unreadableRecent = false
        return roots.flatMap { r -> r.walkTopDown().maxDepth(3).onFail { _, _ -> }.filter { it.isFile && it.extension.equals("apk", true) && it.lastModified() > since }.toList() }
            .sortedByDescending { it.lastModified() }.take(6)
            .mapNotNull { f ->
                val key = "${f.path}|${f.length()}|${f.lastModified()}"
                val p = if (parsed.containsKey(key)) parsed[key] else {
                    val info = runCatching { pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES) }.getOrNull()
                    val v = info?.let { Parsed(it.packageName, if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong(), it.versionName ?: "?", signers(it)) }
                    // An unreadable file is not remembered: it is most likely still downloading.
                    if (v != null) parsed[key] = v
                    v
                }
                if (p == null) { if (System.currentTimeMillis() - f.lastModified() < 10 * 60_000L) unreadableRecent = true; return@mapNotNull null }
                if (UpdateRules.isUpdate(p.pkg, p.code, p.signers, context.packageName, myCode, mySigners)) FoundUpdate(f, p.versionName, p.code) else null
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
        // A download still in progress can't be read yet: look again shortly instead of waiting for the next change.
        if (UpdateWatcher.unreadableRecent) UpdateWatcher.recheckSoon(applicationContext)
        UpdateWatcher.arm(applicationContext)
        return Result.success()
    }
}

/**
 * Opened from the update notification, Home and About: hands the APK to Android's installer. It stays on screen, showing what
 * it is doing, until Android has answered, and opens Android's confirmation itself. It used to close the moment the file was
 * handed over and leave the answer to arrive in the background, where newer Android versions refuse to open the confirmation
 * and the install ended as "Install cancelled".
 */
class UpdateInstallActivity : ComponentActivity() {
    private val status = androidx.compose.runtime.mutableStateOf("Finding the update…")
    private val progress = androidx.compose.runtime.mutableStateOf<Float?>(null)
    private var receiver: android.content.BroadcastReceiver? = null
    private var finishing = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            androidx.compose.material3.MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().padding(32.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        val p = progress.value
                        if (p == null) androidx.compose.material3.CircularProgressIndicator() else androidx.compose.material3.CircularProgressIndicator(progress = { p })
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(18.dp))
                        androidx.compose.material3.Text(status.value, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        val installer = ApkInstaller(this)
        track("update.open")      // the update flow is counted step by step, so the usage report can say where an update gets stuck
        installer.permissionIntent()?.let { track("update.needs_permission"); startActivity(it); toast("Allow Ultimate to install apps, then tap the update again."); finish(); return }
        lifecycleScope.launch {
            // Notification and button hand over a path; Home hands over nothing and the newest update is looked up here, off the main thread.
            val file = intent.getStringExtra("path")?.let(::File)?.takeIf { it.exists() } ?: withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { UpdateWatcher.find(applicationContext)?.file }.getOrNull() }
            if (file == null) { track("update.file_gone"); toast("The update file is gone from Downloads"); finish(); return@launch }
            status.value = "Preparing ${file.name}…"
            val action = "app.ultimate.UPDATE_RESULT"
            receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (val s = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)) {
                        android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                            track("update.confirm_shown"); status.value = "Confirm the update"; progress.value = null
                            confirm?.let { runCatching { startActivity(it) }.onFailure { e -> toast("Couldn't open Android's installer: ${e.message}"); done() } }
                        }
                        android.content.pm.PackageInstaller.STATUS_SUCCESS -> { track("update.success"); toast("Installed"); done() }
                        else -> {
                            track("update.fail.$s")
                            val text = InstallMessages.explain(s, i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE))
                            toast(text); (applicationContext as? app.ultimate.UltimateApp)?.insights?.problem("Install", text)
                            i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID, -1).takeIf { it >= 0 }?.let { id -> runCatching { packageManager.packageInstaller.abandonSession(id) } }
                            done()
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, android.content.IntentFilter(action), Context.RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, android.content.IntentFilter(action))
            val result = PendingIntent.getBroadcast(this@UpdateInstallActivity, 22, Intent(action).setPackage(packageName), PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            track("update.start")
            runCatching { installer.install(android.net.Uri.fromFile(file), file.name, result) { f -> progress.value = f; status.value = "Copying the update… ${(f * 100).toInt()}%" } }
                .onFailure { track("update.start_failed"); (applicationContext as? app.ultimate.UltimateApp)?.insights?.problem("Install", "Couldn't start the update: ${it.message}".take(150)); toast("Couldn't start the update: ${it.message}"); done() }
        }
    }

    private fun done() { if (!finishing) { finishing = true; finish() } }
    private fun toast(t: String) = android.widget.Toast.makeText(applicationContext, t, android.widget.Toast.LENGTH_LONG).show()
    override fun onDestroy() { receiver?.let { runCatching { unregisterReceiver(it) } }; super.onDestroy() }
}
