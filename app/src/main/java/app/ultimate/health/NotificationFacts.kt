package app.ultimate.health

import java.util.Locale

/**
 * Facts worth knowing about you, read out of your notifications on the phone: money (what you spent, received, bills
 * due, and payments that failed) and health alerts from your watch or health apps (like "HR low (51 bpm)"). Only
 * notifications Ultimate already keeps are read; nothing is sent anywhere. Pure, so it is tested.
 */
object NotificationFacts {
    enum class Kind { Spent, Received, Declined, Bill, Heart, Steps, Sleep }
    data class Fact(val kind: Kind, val amount: Double?, val text: String, val app: String, val at: Long)

    private val moneyApps = listOf("paypal", "venmo", "cash", "squareup", "chase", "bankofamerica", "wellsfargo", "capitalone", "americanexpress", "amex", "discover",
        "citi", "zelle", "usbank", "schwab", "fidelity", "robinhood", "coinbase", "klarna", "affirm", "wallet", "samsung.android.spay", "revolut", "chime", "sofi", "ally", "navyfederal")
    private val healthApps = listOf("shealth", "samsung.health", "fitbit", "google.android.apps.fitness", "garmin", "oura", "whoop", "withings", "polar", "strava")
    private val amount = Regex("(?:US)?\\$\\s?([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{2})?|[0-9]+(?:\\.[0-9]{2})?)")

    /** A payment that did not happen: declined, failed, denied, cancelled or reversed, in the many ways banks put it. */
    private val declined = Regex("declin|insufficient|failed|retry|couldn.t|unable to|not go through|add money|didn.t (?:go|work|process|complete|succeed)|did not (?:go|work|process|complete|succeed)|" +
        "wasn.t (?:approved|processed|completed|successful)|was not (?:approved|processed|completed|successful)|not approved|not processed|denied|unsuccessful|could not|can.t (?:be )?(?:process|complete)|" +
        "rejected|blocked|cancel|voided|reversed|problem with|issue with|action (?:needed|required)|attempt")
    private val alertOnly = Regex("security alert|verify|verification|suspicious|fraud|unusual activity|log.?in|sign.?in|password|one-time|\\botp\\b|code is|your code")
    private val strongSpent = Regex("\\b(purchase|paid|charged|spent|you sent|transaction)\\b")
    private val weakSpent = Regex("\\b(payment|debit|withdraw|order total)\\b")
    private val notAPayment = Regex("\\b(offer|earn|cash ?back|rewards?|save|deal|sale|coupon|promo|limit|available|balance|credit line|statement)\\b")

    fun read(pkg: String, app: String, title: String, text: String, at: Long): Fact? {
        val all = "$title $text"; val t = all.lowercase(Locale.ROOT); val p = pkg.lowercase(Locale.ROOT)
        val line = listOf(title, text).filter { it.isNotBlank() }.joinToString(" · ").take(140)
        // Health first: a watch alert with a number in it.
        Regex("(\\d{2,3})\\s?bpm").find(t)?.let { if (healthApps.any { it in p } || "heart" in t || "hr " in "$t ") return Fact(Kind.Heart, it.groupValues[1].toDouble(), line, app, at) }
        if (healthApps.any { it in p }) {
            Regex("([0-9][0-9,]{2,})\\s?steps").find(t)?.let { return Fact(Kind.Steps, it.groupValues[1].replace(",", "").toDouble(), line, app, at) }
            if ("sleep" in t) return Fact(Kind.Sleep, null, line, app, at)
        }
        val money = amount.find(all)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        val moneyApp = moneyApps.any { it in p }
        if (money == null && !moneyApp) return null
        // Shops advertise prices; only count it when it reads like a transaction that really happened.
        val kind = when {
            declined.containsMatchIn(t) -> Kind.Declined
            // Security and sign-in messages mention money without being a payment.
            alertOnly.containsMatchIn(t) -> return null
            Regex("\\b(received|deposit|refund|paid you|sent you|you got|incoming|credited)\\b").containsMatchIn(t) -> Kind.Received
            Regex("\\b(due|bill|statement|autopay|payment reminder|minimum payment)\\b").containsMatchIn(t) -> Kind.Bill
            strongSpent.containsMatchIn(t) -> Kind.Spent
            // "payment" or "debit" alone also appear in offers and balance notices.
            weakSpent.containsMatchIn(t) && !notAPayment.containsMatchIn(t) -> Kind.Spent
            else -> return null
        }
        if (!moneyApp && kind != Kind.Bill && !Regex("\\b(card|account|bank|balance|transaction|purchase|payment)\\b").containsMatchIn(t)) return null
        return Fact(kind, money, line, app, at)
    }

    /** This week's money: spent, received, and failed payments (newest first). */
    data class Money(val spent: Double, val received: Double, val declined: List<Fact>, val bills: List<Fact>, val recent: List<Fact>)
    /** The same payment is often announced twice (an update to the notification, or two apps); one app and one amount within 15 minutes counts once. */
    fun dedupe(facts: List<Fact>): List<Fact> {
        val kept = ArrayList<Fact>()
        for (f in facts.sortedBy { it.at }) if (kept.none { k -> k.kind == f.kind && k.app == f.app && k.amount == f.amount && f.at - k.at <= 15 * 60_000L }) kept += f
        return kept
    }

    fun money(facts: List<Fact>) = dedupe(facts).filter { it.kind in setOf(Kind.Spent, Kind.Received, Kind.Declined, Kind.Bill) }.sortedByDescending { it.at }.let { m ->
        Money(m.filter { it.kind == Kind.Spent }.sumOf { it.amount ?: 0.0 }, m.filter { it.kind == Kind.Received }.sumOf { it.amount ?: 0.0 },
            m.filter { it.kind == Kind.Declined }, m.filter { it.kind == Kind.Bill }, m.take(5))
    }
}
