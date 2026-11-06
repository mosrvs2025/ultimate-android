package app.ultimate.wear

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

/**
 * Updates from the phone: Ultimate+ on the phone sends the new watch app over the watch link (Bluetooth or Wi-Fi), and
 * the watch installs it with Android's own installer, which asks for one tap. No wireless debugging, no computer.
 * The first time, Android asks to allow Ultimate+ to install apps.
 */
class WatchUpdateService : WearableListenerService() {
    /** The phone asks which version is installed, so it can offer an update; answered even when the app isn't open. */
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path == PhoneLink.FROM_PHONE && String(event.data) == "version?") report(this, hello(this))
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != UPDATE_PATH) return
        val client = Wearable.getChannelClient(this)
        val file = File(cacheDir, "update.apk").apply { delete() }
        report(this, "update:receiving")
        client.receiveFile(channel, android.net.Uri.fromFile(file), false)
        client.registerChannelCallback(channel, object : ChannelClient.ChannelCallback() {
            override fun onInputClosed(c: ChannelClient.Channel, closeReason: Int, appErrorCode: Int) {
                client.unregisterChannelCallback(this)
                if (closeReason != CLOSE_REASON_NORMAL || !file.exists() || file.length() == 0L) { report(this@WatchUpdateService, "update:failed:the transfer stopped, try again"); return }
                install(this@WatchUpdateService, file)
            }
        })
    }

    companion object {
        const val UPDATE_PATH = "/ultimate/update"

        fun report(c: Context, text: String) = PhoneLink(c).send(text)

        /** "hello:<versionCode>:<versionName>", the watch app's version for the phone. */
        fun hello(c: Context): String {
            val info = c.packageManager.getPackageInfo(c.packageName, 0)
            @Suppress("DEPRECATION") val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            return "hello:$code:${info.versionName}"
        }

        /** Hands [apk] to Android's installer; Android shows its own confirmation on the watch. */
        fun install(c: Context, apk: File) {
            val pm = c.packageManager
            if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
                // Ask once for "install unknown apps" for Ultimate+; the update can be sent again after allowing it.
                val ok = runCatching {
                    c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                report(c, if (ok) "update:needs-permission" else "update:unsupported:this watch doesn't let apps install updates; use wireless debugging")
                return
            }
            runCatching {
                val installer = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(c.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("ultimate-watch", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                    val done = PendingIntent.getBroadcast(c, id, Intent(c, UpdateResult::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    s.commit(done.intentSender)
                }
                report(c, "update:installing")
            }.onFailure { e -> report(c, "update:failed:${e.message ?: e.javaClass.simpleName}") }
        }
    }
}

/** Android's answer about the install: show its confirmation when it needs one, and tell the phone how it went. */
class UpdateResult : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                WatchUpdateService.report(c, "update:confirm")
            }
            PackageInstaller.STATUS_SUCCESS -> WatchUpdateService.report(c, "update:installed")
            else -> WatchUpdateService.report(c, "update:failed:${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "the watch didn't install it"}")
        }
    }
}
