package app.ultimate.inbox

import java.util.TimeZone

/** What became of a notification: how it left the shade. Stored as a number in the history. */
enum class Fate(val code: Int) {
    Waiting(0), Tapped(1), Swiped(2), ClearedAll(3), Withdrawn(4), Other(5);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: Waiting

        /** Android's removal reasons (NotificationListenerService.REASON_*) mapped to what they mean to a person. */
        fun fromReason(reason: Int): Fate = when (reason) {
            1 -> Tapped        // REASON_CLICK
            2 -> Swiped        // REASON_CANCEL
            3 -> ClearedAll    // REASON_CANCEL_ALL
            8 -> Withdrawn     // REASON_APP_CANCEL
            else -> Other
        }
    }
}

/** One kept notification, with what the person did with it. */
data class Rec(
    val pkg: String, val app: String, val at: Long, val title: String, val text: String,
    val who: String? = null, val category: String? = null, val fate: Fate = Fate.Waiting, val gone: Long = 0, val pulledBack: Boolean = false,
)

/** How one app's notifications are treated. */
data class AppSignal(val pkg: String, val app: String, val count: Int, val tapped: Int, val dismissed: Int, val decided: Int) {
    /** Of the ones you did something with, the share you opened. Null until there are enough to say. */
    val tapRate: Float? get() = if (decided >= 5) tapped.toFloat() / decided else null
    val verdict: Verdict get() = NotifyMath.verdict(this)
}

enum class Verdict(val label: String) { Worth("Worth it"), Mixed("Mixed"), Noise("Noise"), Unknown("Learning") }

/** A person or chat that reaches you, and how quickly you open them. */
data class Person(val name: String, val count: Int, val opened: Int, val medianOpenMs: Long?, val apps: Set<String>)

/** A burst: one app pinging again and again in a short time. */
data class Storm(val pkg: String, val app: String, val start: Long, val end: Long, val count: Int)

/** The same words sent over and over. */
data class Repeat(val pkg: String, val app: String, val title: String, val text: String, val count: Int)

/** A notification that is really an advert. */
data class Promo(val pkg: String, val app: String, val count: Int, val example: String)

/** Everything the Insights tab shows, worked out in one pass. */
data class NotifyReport(
    val total: Int, val days: Int, val perDay: Float,
    val fates: Map<Fate, Int>,
    val apps: List<AppSignal>,
    val people: List<Person>,
    val hours: IntArray,
    val heat: Array<IntArray>,
    val night: Int, val nightApps: List<Pair<String, Int>>,
    val storms: List<Storm>,
    val repeats: List<Repeat>,
    val promos: List<Promo>, val promoTotal: Int,
    val codes: Int, val codeApps: List<String>,
    val pulledBack: Int,
    val sentences: List<String>,
)

object NotifyMath {
    private const val DAY = 86_400_000L

    fun verdict(a: AppSignal): Verdict {
        val r = a.tapRate ?: return Verdict.Unknown
        return when { r >= 0.3f -> Verdict.Worth; r < 0.08f -> Verdict.Noise; else -> Verdict.Mixed }
    }

    // Words shops and games use to sell. Two or more, or a price with a discount word, makes it an ad.
    private val salesWords = listOf(
        "sale", "% off", "discount", "deal", "deals", "offer", "limited time", "last chance", "ends tonight", "ends soon", "don't miss", "dont miss",
        "free shipping", "promo", "coupon", "voucher", "save ", "shop now", "buy now", "exclusive", "flash", "clearance", "black friday", "cyber monday",
        "% cashback", "just dropped", "back in stock", "price drop", "special offer", "treat yourself", "claim your", "reward", "bonus",
    )
    private val price = Regex("""([$€£¥]\s?\d)|(\d+\s?%)""")

    /** Whether a notification is an advert dressed up as news. Chats and calls never are. */
    fun isPromo(title: String, text: String, category: String? = null): Boolean {
        if (category == "msg" || category == "call" || category == "email") return false
        if (category == "promo") return true
        val s = "$title $text".lowercase()
        val hits = salesWords.count { it in s }
        return hits >= 2 || (hits >= 1 && price.containsMatchIn(s))
    }

    private val codeWords = Regex("""(?i)\b(code|otp|passcode|verification|verify|one[- ]time|2fa|security code|pin|login|sign[- ]in)\b""")
    private val codeDigits = Regex("""(?<![\d])(\d{4,8}|\d{3}[- ]\d{3})(?![\d])""")

    /** Whether a notification carries a sign-in or verification code. */
    fun hasCode(title: String, text: String): Boolean {
        val s = "$title $text"
        return codeWords.containsMatchIn(s) && codeDigits.containsMatchIn(s)
    }

    /** Runs of at least [min] notifications from one app with no gap longer than [gapMs] between them. */
    fun storms(recs: List<Rec>, min: Int = 5, gapMs: Long = 90_000): List<Storm> = buildList {
        recs.groupBy { it.pkg }.forEach { (pkg, list) ->
            val sorted = list.sortedBy { it.at }
            var start = 0
            for (i in 1..sorted.size) {
                if (i == sorted.size || sorted[i].at - sorted[i - 1].at > gapMs) {
                    val n = i - start
                    if (n >= min) add(Storm(pkg, sorted[start].app, sorted[start].at, sorted[i - 1].at, n))
                    start = i
                }
            }
        }
    }.sortedByDescending { it.count }

    /** Identical notifications seen at least [min] times. */
    fun repeats(recs: List<Rec>, min: Int = 3): List<Repeat> =
        recs.filter { it.text.isNotBlank() || it.title.isNotBlank() }.groupBy { Triple(it.pkg, it.title.trim(), it.text.trim()) }
            .filter { it.value.size >= min }.map { (k, v) -> Repeat(k.first, v.first().app, k.second, k.third, v.size) }.sortedByDescending { it.count }

    fun median(values: List<Long>): Long? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    private fun hourOf(at: Long, zone: TimeZone) = (Math.floorMod(at + zone.getOffset(at), DAY) / 3_600_000L).toInt()
    private fun dayOf(at: Long, zone: TimeZone) = Math.floorDiv(at + zone.getOffset(at), DAY)

    fun report(recs: List<Rec>, now: Long, zone: TimeZone = TimeZone.getDefault()): NotifyReport {
        val total = recs.size
        val firstDay = recs.minOfOrNull { dayOf(it.at, zone) } ?: dayOf(now, zone)
        val days = (dayOf(now, zone) - firstDay + 1).toInt().coerceAtLeast(1)
        val fates = Fate.entries.associateWith { f -> recs.count { it.fate == f } }

        val apps = recs.groupBy { it.pkg }.map { (pkg, list) ->
            val tapped = list.count { it.fate == Fate.Tapped }
            val dismissed = list.count { it.fate == Fate.Swiped || it.fate == Fate.ClearedAll }
            AppSignal(pkg, list.first().app, list.size, tapped, dismissed, tapped + dismissed)
        }.sortedByDescending { it.count }

        val people = recs.filter { !it.who.isNullOrBlank() }.groupBy { it.who!!.trim() }.map { (name, list) ->
            val opens = list.filter { it.fate == Fate.Tapped && it.gone > it.at }.map { it.gone - it.at }
            Person(name, list.size, opens.size, median(opens), list.map { it.app }.toSet())
        }.sortedByDescending { it.count }

        val hours = IntArray(24); recs.forEach { hours[hourOf(it.at, zone)]++ }
        // Seven rows, oldest day first, by 24 hours.
        val today = dayOf(now, zone)
        val heat = Array(7) { IntArray(24) }
        recs.forEach { r -> val ago = (today - dayOf(r.at, zone)).toInt(); if (ago in 0..6) heat[6 - ago][hourOf(r.at, zone)]++ }

        val nightRecs = recs.filter { hourOf(it.at, zone) < 6 }
        val nightApps = nightRecs.groupingBy { it.app }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

        val storms = storms(recs)
        val repeats = repeats(recs)
        val promoRecs = recs.filter { isPromo(it.title, it.text, it.category) }
        val promos = promoRecs.groupBy { it.pkg }.map { (pkg, list) -> Promo(pkg, list.first().app, list.size, list.maxByOrNull { it.at }!!.let { listOf(it.title, it.text).filter { s -> s.isNotBlank() }.joinToString(": ") }) }.sortedByDescending { it.count }
        val codeRecs = recs.filter { hasCode(it.title, it.text) }

        val report = NotifyReport(
            total, days, total / days.toFloat(), fates, apps, people, hours, heat,
            nightRecs.size, nightApps, storms, repeats, promos, promoRecs.size,
            codeRecs.size, codeRecs.map { it.app }.distinct(), recs.count { it.pulledBack }, emptyList(),
        )
        return report.copy(sentences = sentences(report))
    }

    fun clock(hour: Int) = "${if (hour % 12 == 0) 12 else hour % 12}${if (hour < 12) "am" else "pm"}"

    private fun minutes(ms: Long): String = when {
        ms < 60_000 -> "${(ms / 1000).coerceAtLeast(1)} seconds"
        ms < 3_600_000 -> "${ms / 60_000} min"
        else -> "${ms / 3_600_000}h ${ms / 60_000 % 60}m"
    }

    /** The findings worth saying out loud, most striking first. */
    fun sentences(r: NotifyReport): List<String> = buildList {
        if (r.total == 0) return@buildList
        add("Your phone interrupted you about ${Math.round(r.perDay)} times a day.")
        r.hours.indices.maxByOrNull { r.hours[it] }?.takeIf { r.hours[it] > 0 }?.let { add("It pings most around ${clock(it)}.") }
        val decided = r.fates[Fate.Tapped]!! + r.fates[Fate.Swiped]!! + r.fates[Fate.ClearedAll]!!
        if (decided >= 10) add("You opened ${Math.round(100f * r.fates[Fate.Tapped]!! / decided)}% of the ones you dealt with. The rest you swiped away.")
        r.apps.filter { it.verdict == Verdict.Noise }.maxByOrNull { it.count }?.let { add("${it.app} sent ${it.count} and you opened ${it.tapped}. It is mostly noise.") }
        r.people.filter { it.medianOpenMs != null && it.opened >= 3 }.minByOrNull { it.medianOpenMs!! }?.let { add("You open ${it.name} fastest: usually within ${minutes(it.medianOpenMs!!)}.") }
        if (r.promoTotal > 0) add("${r.promoTotal} were adverts dressed up as notifications.")
        if (r.night > 0) add("${r.night} arrived between midnight and 6am.")
        r.storms.firstOrNull()?.let { add("Biggest burst: ${it.count} from ${it.app} in ${minutes((it.end - it.start).coerceAtLeast(1000))}.") }
        if (r.codes > 0) add("${r.codes} carried sign-in codes. Any app with notification access can read those.")
    }
}
