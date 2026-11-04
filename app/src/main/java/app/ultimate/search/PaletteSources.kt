package app.ultimate.search

import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.widget.Toast
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.core.ToolLaunch
import app.ultimate.core.copyText
import app.ultimate.home.Commands
import app.ultimate.timeline.Moment
import app.ultimate.timeline.Source
import app.ultimate.rules.Describe
import app.ultimate.timeline.TimelineMath
import app.ultimate.timeline.TimelineReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Searches everything on the phone the palette can reach: apps, Ultimate's tools and actions, contacts, notifications,
 * what you copied, files, settings pages, your records, Echo's readings and rules. Each source runs at once with a
 * time limit, so a slow one never holds up the rest; one that fails (no permission, say) simply finds nothing.
 */
class PaletteSources(private val app: UltimateApp) {
    private data class Launchable(val label: String, val pkg: String)
    @Volatile private var apps: List<Launchable>? = null
    @Volatile private var records: List<Moment>? = null
    @Volatile private var recordsAt = 0L

    val contactsAllowed get() = app.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun search(query: String): List<Hit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        coroutineScope {
            listOf(::apps, ::tools, ::contacts, ::notifications, ::copied, ::files, ::settings, ::records, ::sensed, ::rules)
                .map { source -> async { withTimeoutOrNull(700) { runCatching { source(q) }.getOrDefault(emptyList()) }.orEmpty() } }
                .awaitAll().flatten()
        }
    }

    private fun start(i: Intent) { runCatching { app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    private fun tool(id: String, query: String? = null) = app.tools.firstOrNull { it.id == id }?.let { app.shell.open(it, ToolLaunch(query = query)) }

    private fun apps(q: String): List<Hit> {
        val list = apps ?: app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { Launchable(it.loadLabel(app.packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != app.packageName }.distinctBy { it.pkg }.also { apps = it }
        return list.mapNotNull { a ->
            val s = PaletteMath.score(a.label, q).takeIf { it > 0 } ?: return@mapNotNull null
            Hit(Kind.App, a.label, "Open the app", s + 5, "app:${a.pkg}", iconPackage = a.pkg) {
                app.packageManager.getLaunchIntentForPackage(a.pkg)?.let(::start)
            }
        }
    }

    private fun tools(q: String): List<Hit> {
        val ids = app.tools.map { it.id }.toSet()
        val found = app.tools.mapNotNull { t ->
            val s = PaletteMath.best(q, t.title, t.tagline, t.id).takeIf { it > 0 } ?: return@mapNotNull null
            Hit(Kind.Tool, t.title, t.tagline, s + 8, "tool:${t.id}") { app.shell.open(t) }
        }
        val actions = Commands.available(Commands.search(q), ids, false).take(3).mapNotNull { a ->
            val t = app.tools.firstOrNull { it.id == a.toolId } ?: return@mapNotNull null
            Hit(Kind.Action, a.label, a.detail, 45, "action:${a.id}") { app.shell.open(t) }
        }
        return found + actions
    }

    private fun contacts(q: String): List<Hit> {
        if (!contactsAllowed) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q))
        return app.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext() && size < 6) {
                    val name = c.getString(2).orEmpty()
                    val contact = ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
                    add(Hit(Kind.Contact, name, "Contact", PaletteMath.score(name, q).coerceAtLeast(40) + 6, "contact:${c.getLong(0)}") { start(Intent(Intent.ACTION_VIEW, contact)) })
                }
            }
        }.orEmpty()
    }

    private fun notifications(q: String): List<Hit> {
        if (!BuildConfig.HAS_INBOX) return emptyList()
        return app.notificationLog.search(q, null, false, limit = 6).map { n ->
            val text = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(" · ")
            Hit(Kind.Message, "${n.app}: ${n.title.ifBlank { n.text }}".take(80), "${text.take(90)} · ${ago(n.at)}", PaletteMath.best(q, n.title, n.text, n.app).coerceAtLeast(30), "note:${n.id}", iconPackage = n.pkg) {
                tool("inbox", q)
            }
        }
    }

    private fun copied(q: String): List<Hit> {
        if (!BuildConfig.HAS_CLIPS) return emptyList()
        return app.clips.clips.value.asSequence().filter { it.text.contains(q, ignoreCase = true) }.take(4).map { c ->
            Hit(Kind.Copied, c.text.lineSequence().first().take(80), "Copied ${ago(c.at)} · tap to copy again", PaletteMath.score(c.text, q).coerceAtLeast(30), "clip:${c.id}") {
                copyText(app, "Copied", c.text); Toast.makeText(app, "Copied", Toast.LENGTH_SHORT).show()
            }
        }.toList()
    }

    private fun files(q: String): List<Hit> {
        if (q.length < 2) return emptyList()
        val uri = MediaStore.Files.getContentUri("external")
        return app.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.RELATIVE_PATH),
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            buildList {
                while (c.moveToNext() && size < 6) {
                    val name = c.getString(1).orEmpty(); val mime = c.getString(2) ?: "*/*"
                    val item = ContentUris.withAppendedId(uri, c.getLong(0))
                    add(Hit(Kind.File, name, c.getString(3).orEmpty().trimEnd('/').ifBlank { "File" }, PaletteMath.score(name, q).coerceAtLeast(25), "file:${c.getLong(0)}") {
                        start(Intent(Intent.ACTION_VIEW).setDataAndType(item, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    })
                }
            }
        }.orEmpty()
    }

    private fun settings(q: String): List<Hit> = PaletteMath.SETTINGS.mapNotNull { (name, action, words) ->
        val s = PaletteMath.best(q, name, *words.toTypedArray()).takeIf { it > 0 } ?: return@mapNotNull null
        Hit(Kind.Setting, name, "Android settings", s, "setting:$action") { start(Intent(action)) }
    }

    /** What you watched and played, photos, installs and the like, from the last 30 days; read once a minute at most. */
    private fun records(q: String): List<Hit> {
        if (q.length < 3) return emptyList()
        val now = System.currentTimeMillis()
        val all = records?.takeIf { now - recordsAt < 60_000 } ?: TimelineReader.read(app, now - 30L * 86_400_000).also { records = it; recordsAt = now }
        return TimelineMath.search(all, q).filter { it.source != Source.Notification && it.source != Source.Clip }.take(4).map { m ->
            Hit(Kind.Record, m.title.ifBlank { m.source.label }, "${m.source.label} · ${m.app} · ${ago(m.at)}", 30, "rec:${m.uid}", iconPackage = m.pkg) { tool("timeline", q) }
        }
    }

    private fun sensed(q: String): List<Hit> = app.senses.recent.value.filter { PaletteMath.best(q, it.label, it.source, it.kind) > 0 }.take(3).map { s ->
        Hit(Kind.Sensed, s.label, "${s.source.replaceFirstChar { it.uppercase() }} · ${ago(s.at)}", 35, "sensed:${s.at}:${s.kind}") { tool(s.source) }
    }

    private fun rules(q: String): List<Hit> {
        if (!BuildConfig.HAS_RULES) return emptyList()
        return app.rules.rules.value.mapNotNull { r ->
            val desc = Describe.trigger(r.trigger)
            val s = PaletteMath.best(q, r.name, desc).takeIf { it > 0 } ?: return@mapNotNull null
            Hit(Kind.Rule, r.name, "When $desc" + if (r.enabled) "" else " · off", s, "rule:${r.id}") { tool("rules") }
        }
    }

    private fun ago(at: Long): String {
        val m = (System.currentTimeMillis() - at) / 60_000
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
    }
}
