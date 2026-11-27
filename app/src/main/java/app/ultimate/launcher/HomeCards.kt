package app.ultimate.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ultimate.health.ActivityLoaded
import app.ultimate.health.ActivityMath
import app.ultimate.health.ActivityRings
import app.ultimate.health.HeartBrief
import app.ultimate.health.HeartGlance
import app.ultimate.health.HeartWords
import app.ultimate.mesh.CamNodes
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Small cards on the home screen that show something from Ultimate: today's activity rings, your heart rate, and the cameras your own
 * devices are sharing. Every one is off until you switch it on (Settings, Home screen), draws in the look you are wearing, and a tap
 * opens the matching page of Ultimate. They only read what Ultimate already may read: the launcher never asks for permission, so a card
 * without it says so and a tap goes where it can be allowed.
 */
enum class HomeCard(val label: String, val sub: String) {
    Activity("Activity", "Your rings, steps and how the day is going against your usual"),
    Heart("Heart", "Your latest heart rate and resting rate, from Health Connect"),
    Cameras("Cameras", "Which of your own devices are sharing a camera right now"),
}

/** What a card has to show, kept in memory only. */
data class CardData(
    val activity: ActivityLoaded? = null,
    val heart: HeartGlance.Result? = null,
    val cameras: List<CamNodes.Node> = emptyList(),
    val at: Long = 0,
)

/** Pure parts of the cards: what each one says, and the setting's storage. Tested without a phone. */
object HomeCards {
    fun encode(cards: Set<HomeCard>): String = HomeCard.entries.filter { it in cards }.joinToString(",") { it.name }
    fun decode(text: String?): Set<HomeCard> = text.orEmpty().split(',').mapNotNull { n -> HomeCard.entries.firstOrNull { it.name == n.trim() } }.toSet()

    /** The tool (and, for the Timeline, the tab) a card's tap opens. */
    fun target(card: HomeCard, needsPermission: Boolean = false): Pair<String, String?> = when (card) {
        HomeCard.Activity -> "timeline" to "activity"
        HomeCard.Heart -> "health" to null
        HomeCard.Cameras -> "mesh" to null
    }

    /** "Kitchen camera" for one, "3 cameras" for more; null with none. */
    fun cameraTitle(nodes: List<CamNodes.Node>): String? = when (nodes.size) {
        0 -> null
        1 -> "${nodes[0].name.ifBlank { "A device" }} camera"
        else -> "${nodes.size} cameras"
    }

    /** The line under it: who is sharing (names for a few, "sharing now" for one) and how many are being watched from this phone. */
    fun cameraDetail(nodes: List<CamNodes.Node>): String {
        val who = if (nodes.size == 1) "sharing now" else nodes.map { it.name.ifBlank { "A device" } }.let { n -> if (n.size <= 3) n.joinToString(", ") else n.take(2).joinToString(", ") + " and ${n.size - 2} more" }
        val w = watching(nodes)
        return if (w > 0) "$who \u00b7 watching ${if (w == nodes.size && nodes.size > 1) "all" else w.toString()}" else who
    }

    /** A short pace line for a small card: "1,200 ahead of usual", "400 behind usual", "on your usual pace". Null with no usual to compare to. */
    fun paceShort(p: ActivityMath.Pace?): String? {
        p ?: return null
        val gap = Math.round(p.ahead / 100.0) * 100
        return when {
            gap == 0L -> "on your usual pace"
            gap > 0 -> "%,d ahead of usual".format(gap)
            else -> "%,d behind usual".format(-gap)
        }
    }

    /** Whether any of the cameras is being watched from this phone right now. */
    fun watching(nodes: List<CamNodes.Node>) = nodes.count { it.viewing }

    fun minuteOfDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Int = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }
}

@Composable
private fun CardShell(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val look = LocalLook.current
    val th = LocalLauncherTheme.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(look.fg.copy(alpha = 0.07f)).border(BorderStroke(1.dp, th.a.copy(alpha = 0.30f)), shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

/** The cards you switched on, one under the other. A card with nothing to say (no cameras sharing) isn't drawn at all. */
@Composable
internal fun HomeCardsBlock(cards: Set<HomeCard>, data: CardData, nowMs: Long, onOpen: (tool: String, source: String?) -> Unit) {
    if (cards.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (HomeCard.Activity in cards) ActivityCard(data.activity, onOpen)
        if (HomeCard.Heart in cards) HeartCard(data.heart, nowMs, onOpen)
        if (HomeCard.Cameras in cards) CamerasCard(data.cameras, onOpen)
    }
}

@Composable
private fun ActivityCard(d: ActivityLoaded?, onOpen: (String, String?) -> Unit) {
    val look = LocalLook.current; val th = LocalLauncherTheme.current
    val (tool, source) = HomeCards.target(HomeCard.Activity)
    val day = d?.day
    if (d == null) return
    if (day == null) {
        CardShell({ onOpen(tool, source) }) {
            Column {
                Text("Activity", color = look.fg, fontSize = 16.sp, fontFamily = look.face)
                Text(if (d.status == ActivityLoaded.Status.NeedsPermission) "Tap to allow steps in Ultimate" else "Health Connect isn't available", color = look.dim, fontSize = 13.sp, fontFamily = look.face)
            }
        }
        return
    }
    val (s, a, k) = day.rings(d.goals)
    CardShell({ onOpen(tool, source) }) {
        ActivityRings(s, a, k, Modifier.size(56.dp), stroke = 6.dp)
        Column(Modifier.weight(1f)) {
            Text("%,d steps".format(day.steps), color = look.fg, fontSize = 17.sp, fontWeight = FontWeight.Medium, fontFamily = look.face, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = day.activeMin?.let { "${ActivityMath.minutesWords(it)} active" } ?: day.activeKcal?.let { "${"%,d".format(it.roundToInt())} Cal" } ?: day.metres?.let { ActivityMath.distance(it, d.miles) }.orEmpty()
            if (line.isNotBlank()) Text(line, color = look.dim, fontSize = 12.sp, fontFamily = look.face, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HomeCards.paceShort(day.pace)?.let { Text(it, color = th.a, fontSize = 12.sp, fontFamily = look.face, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun HeartCard(r: HeartGlance.Result?, nowMs: Long, onOpen: (String, String?) -> Unit) {
    val look = LocalLook.current; val th = LocalLauncherTheme.current
    val (tool, source) = HomeCards.target(HomeCard.Heart)
    if (r == null) return
    val (title, detail) = when (r) {
        is HeartGlance.Result.Ready -> (HeartWords.headline(r.brief, nowMs) ?: "No readings yet") to HeartWords.detail(r.brief, nowMs, range = false)
        HeartGlance.Result.NeedsPermission -> "Heart" to "Tap to allow heart rate in Ultimate"
        HeartGlance.Result.Empty -> "Heart" to "Nothing in Health Connect yet. Check that your watch is syncing"
        HeartGlance.Result.Unavailable -> "Heart" to "Health Connect isn't available"
    }
    CardShell({ onOpen(tool, source) }) {
        Icon(Icons.Rounded.Favorite, null, tint = th.a, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = look.fg, fontSize = 17.sp, fontWeight = FontWeight.Medium, fontFamily = look.face, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = look.dim, fontSize = 12.sp, fontFamily = look.face, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CamerasCard(nodes: List<CamNodes.Node>, onOpen: (String, String?) -> Unit) {
    val look = LocalLook.current; val th = LocalLauncherTheme.current
    val title = HomeCards.cameraTitle(nodes) ?: return
    val (tool, source) = HomeCards.target(HomeCard.Cameras)
    CardShell({ onOpen(tool, source) }) {
        Icon(Icons.Rounded.Videocam, null, tint = th.a, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = look.fg, fontSize = 17.sp, fontWeight = FontWeight.Medium, fontFamily = look.face, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HomeCards.cameraDetail(nodes), color = look.dim, fontSize = 12.sp, fontFamily = look.face, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

