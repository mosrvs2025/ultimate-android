package app.ultimate.mesh

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import app.ultimate.R
import app.ultimate.UltimateApp
import app.ultimate.core.Signal

/**
 * What this phone does with something another of the owner's devices flings to it. Nothing opens by itself: each thing
 * arrives as a notification the owner taps (an app opens, or its store page if it is not installed here; a link opens in
 * the browser; a file is saved to Downloads/Ultimate and opens from the notification). The owner can refuse all of it with
 * "Accept what my devices send" in Octopus Mesh.
 */
class FlingHost(context: Context, private val accepts: () -> Boolean) {
    private val app = context.applicationContext
    private val inboxes = HashMap<String, Fling.Inbox>()

    fun handle(from: MeshNode.Peer, cmd: String, args: Map<String, String>): Map<String, String> {
        if (!accepts()) throw IllegalStateException("${Build.MODEL} is not accepting things from your devices right now")
        return when (cmd) {
            Cmd.FLING_APP -> {
                val pkg = args["pkg"]?.takeIf { it.matches(Regex("[A-Za-z0-9_.]{1,200}")) } ?: throw IllegalArgumentException("no app named")
                val label = args["label"]?.take(60) ?: pkg
                val launch = app.packageManager.getLaunchIntentForPackage(pkg)
                val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                notify("${from.name} sent you $label", if (launch != null) "Tap to open it here" else "Not installed here: tap to get it", intent)
                publish(from, "app", label)
                mapOf("installed" to if (launch != null) "1" else "0")
            }
            Cmd.FLING_TEXT -> {
                val text = args["text"]?.take(4_000) ?: throw IllegalArgumentException("nothing sent")
                val link = Fling.link(text)
                val intent = if (link != null) Intent(Intent.ACTION_VIEW, Uri.parse(link))
                    else Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).let { Intent.createChooser(it, "Use the text from ${from.name}") }
                notify(if (link != null) "${from.name} sent you a link" else "${from.name} sent you text", link ?: text.take(120), intent, big = text)
                publish(from, if (link != null) "link" else "text", link ?: text.take(40))
                mapOf("ok" to "1")
            }
            Cmd.FLING_BEGIN -> synchronized(inboxes) {
                val id = args.getValue("id")
                inboxes[id] = Fling.Inbox(id, Fling.safeName(args["name"]), args.getValue("size").toLong(), args.getValue("sha"), args["mime"] ?: "application/octet-stream")
                if (inboxes.size > 3) inboxes.keys.firstOrNull { it != id }?.let(inboxes::remove)
                mapOf("ok" to "1")
            }
            Cmd.FLING_CHUNK -> {
                val inbox = synchronized(inboxes) { inboxes[args.getValue("id")] } ?: throw IllegalStateException("that transfer was not started")
                try { inbox.accept(args.getValue("i").toInt(), args.getValue("data")) } catch (e: IllegalArgumentException) { synchronized(inboxes) { inboxes.remove(inbox.id) }; throw e }
                mapOf("next" to inbox.next.toString())
            }
            Cmd.FLING_END -> {
                val inbox = synchronized(inboxes) { inboxes.remove(args.getValue("id")) } ?: throw IllegalStateException("that transfer was not started")
                val uri = save(inbox.name, inbox.mime, inbox.finish())
                val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, inbox.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                notify("${from.name} sent you ${inbox.name}", "Saved in Downloads/Ultimate · tap to open", open)
                publish(from, "file", inbox.name)
                mapOf("saved" to inbox.name)
            }
            else -> throw IllegalStateException("unknown command")
        }
    }

    private fun save(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ultimate"); put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val cr = app.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("could not save the file here")
        try {
            cr.openOutputStream(uri)!!.use { it.write(bytes) }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { cr.delete(uri, null, null); throw IllegalStateException("could not save the file here: ${e.message}") }
        return uri
    }

    private fun publish(from: MeshNode.Peer, what: String, title: String) = runCatching {
        (app as UltimateApp).senses.publish(Signal("mesh", "fling_received", "${from.name} sent you $title", mapOf("device" to from.name, "what" to what)))
    }

    private fun notify(title: String, text: String, intent: Intent, big: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sent from your devices", NotificationManager.IMPORTANCE_HIGH))
        val id = (title + text + System.nanoTime()).hashCode()
        val pi = PendingIntent.getActivity(app, id, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
        if (big != null) b.setStyle(NotificationCompat.BigTextStyle().bigText(big))
        nm.notify(id, b.build())
    }

    companion object { const val CHANNEL = "mesh-fling" }
}
