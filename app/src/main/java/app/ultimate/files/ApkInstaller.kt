package app.ultimate.files

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Which APKs inside an archive to install: every base and split APK, skipping icons, manifests and OBB data. */
object ApkBundle {
    fun isApkEntry(name: String): Boolean {
        val n = name.substringAfterLast('/').lowercase()
        return n.endsWith(".apk") && !name.startsWith("__MACOSX")
    }

    fun isArchive(name: String): Boolean = name.lowercase().let { it.endsWith(".apks") || it.endsWith(".xapk") || it.endsWith(".apkm") || it.endsWith(".zip") }
}

/**
 * Installs an APK, or a split app saved as .apks/.xapk/.zip (like the ones Pluck saves), through Android's own installer.
 * Android always asks the user to confirm; Ultimate never installs silently.
 */
class ApkInstaller(private val context: Context) {
    /** Android needs the user to allow Ultimate to install apps once; null when it already may. */
    fun permissionIntent(): Intent? =
        if (context.packageManager.canRequestPackageInstalls()) null
        else Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Streams the APK(s) into an install session and hands it to Android, which shows its confirmation. Returns how many APKs. */
    suspend fun install(uri: Uri, name: String): Int = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        var count = 0
        try {
            val resolver = context.contentResolver
            if (ApkBundle.isArchive(name)) {
                resolver.openInputStream(uri)!!.use { raw ->
                    ZipInputStream(raw).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            if (!e.isDirectory && ApkBundle.isApkEntry(e.name)) { write(session, "part$count.apk", zip, e.size); count++ }
                        }
                    }
                }
                check(count > 0) { "No APKs inside $name" }
            } else {
                resolver.openInputStream(uri)!!.use { write(session, "base.apk", it, -1) }
                count = 1
            }
            val status = Intent(context, InstallStatusActivity::class.java).setAction(InstallStatusActivity.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getActivity(context, id, status, flags).intentSender)
            session.close()
            count
        } catch (e: Exception) {
            runCatching { session.abandon() }
            throw e
        }
    }

    private fun write(session: PackageInstaller.Session, name: String, input: InputStream, size: Long) {
        session.openWrite(name, 0, size).use { out -> input.copyTo(out, 256 * 1024); session.fsync(out) }
    }
}

/**
 * Receives the installer's answer. When Android needs the user to confirm, it opens Android's own screen for that; otherwise
 * it says how it went and closes.
 */
class InstallStatusActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent); finish()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent); finish() }

    @Suppress("DEPRECATION")
    private fun handle(intent: Intent?) {
        if (intent?.action != ACTION) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> toast("Installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val text = InstallMessages.explain(status, msg)
                toast(text)
                (applicationContext as? app.ultimate.UltimateApp)?.insights?.problem("Install", text)
            }
        }
    }

    private fun toast(text: String) = android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_LONG).show()

    companion object { const val ACTION = "app.ultimate.INSTALL_STATUS" }
}

object InstallMessages {
    /** The installer's status codes in plain words. */
    fun explain(status: Int, message: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked this install"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "An installed version conflicts with it (often a different signature). Uninstall the old one first."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This app doesn't support this phone"
        PackageInstaller.STATUS_FAILURE_INVALID -> "The file isn't a valid app, or a split part is missing"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space to install"
        else -> "Install failed" + (message?.let { ": $it" } ?: "")
    }
}
