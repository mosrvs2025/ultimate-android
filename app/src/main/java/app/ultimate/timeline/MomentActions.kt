package app.ultimate.timeline

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.clips.ClipSmart
import app.ultimate.core.ToolLaunch
import app.ultimate.core.copyText
import app.ultimate.inbox.LiveNotifications

/** One thing you can do with a record, for its long-press menu or its quick chips. */
data class MomentAction(val label: String, val run: () -> Unit)

/**
 * What tapping a record does, and what its menu offers. Every record leads somewhere: a notification opens the way it
 * would from the shade (or its app), something you played opens its app, a copy is copied again, a photo opens.
 */
class MomentActions(private val app: UltimateApp, private val context: Context) {
    private fun tool(id: String, query: String? = null) = app.tools.firstOrNull { it.id == id }?.let { app.shell.open(it, ToolLaunch(query = query)) }
    private fun hasTool(id: String) = app.tools.any { it.id == id }

    private fun openApp(pkg: String?): Boolean {
        val i = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) } ?: return false
        return runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private fun copy(label: String, text: String) { copyText(context, label, text); Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show() }

    /** The one obvious thing to do with [m]. Returns false when there is nothing better than showing its details. */
    fun primary(m: Moment): Boolean = when (m.source) {
        Source.Notification -> {
            val pi = LiveNotifications.intent(m.key)
            if (pi != null && runCatching { pi.send(); true }.getOrDefault(false)) true else openApp(m.pkg)
        }
        Source.Watched -> openApp(m.pkg)
        Source.Clip -> { copy("Copied again", m.detail.ifBlank { m.title }); true }
        Source.Photo, Source.Screenshot -> m.uri?.let { u -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).setType("image/*").setDataAndType(Uri.parse(u), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false) } ?: false
        Source.Install -> openApp(m.pkg)
        Source.Focus -> { tool("focus"); true }
        Source.Charge -> false
    }

    /** Quick one-tap chips for copied text: prettify JSON, open a link, run a command, copy a colour. */
    fun smart(m: Moment): List<MomentAction> {
        if (m.source != Source.Clip) return emptyList()
        val text = m.detail.ifBlank { m.title }
        return when (ClipSmart.kind(text)) {
            ClipSmart.Kind.Json -> listOf(MomentAction("Copy as pretty JSON") { ClipSmart.pretty(text)?.let { copy("JSON", it) } })
            ClipSmart.Kind.Url -> listOf(MomentAction("Open link") { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (text.trim().startsWith("www.")) "https://${text.trim()}" else text.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
            ClipSmart.Kind.Command -> if (BuildConfig.HAS_TERMINAL && hasTool("terminal")) listOf(MomentAction("Run in Terminal") { app.terminal.type(ClipSmart.command(text), run = false); tool("terminal") }) else emptyList()
            ClipSmart.Kind.Path -> if (hasTool("files")) listOf(MomentAction("Show in Files") { tool("files") }) else emptyList()
            ClipSmart.Kind.Color -> listOf(MomentAction("Copy colour") { copy("Colour", "#" + text.trim().removePrefix("#").uppercase()) })
            ClipSmart.Kind.Plain -> emptyList()
        }
    }

    /** Everything else, for the long-press menu. */
    fun menu(m: Moment, onDetails: () -> Unit): List<MomentAction> = buildList {
        add(MomentAction("Details") { onDetails() })
        if (m.pkg != null && context.packageManager.getLaunchIntentForPackage(m.pkg) != null) add(MomentAction("Open ${m.app.ifBlank { "app" }}") { openApp(m.pkg) })
        val text = listOf(m.title, m.detail).filter { it.isNotBlank() }.distinct().joinToString("\n")
        if (text.isNotBlank()) add(MomentAction("Copy text") { copy(m.source.label, text) })
        if (m.pkg != null && hasTool("pluck")) add(MomentAction("Inspect in Pluck") { tool("pluck") })
        if (m.pkg != null) add(MomentAction("App settings") { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${m.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        if (hasTool("rules")) when (m.source) {
            Source.Notification -> add(MomentAction("Make a rule from this") { tool("rules", "When I get a notification from ${m.app}, ") })
            Source.Clip -> add(MomentAction("Make a rule from this") { tool("rules", "When I copy something, ") })
            Source.Charge -> add(MomentAction("Make a rule from this") { tool("rules", "When I plug in the charger, ") })
            else -> Unit
        }
        if (m.source == Source.Notification && m.pkg != null && BuildConfig.HAS_INBOX) add(MomentAction("Stop keeping ${m.app}'s notifications") {
            app.notificationLog.excluded = app.notificationLog.excluded + m.pkg; Toast.makeText(context, "${m.app} won't be kept any more", Toast.LENGTH_SHORT).show()
        })
    }
}
