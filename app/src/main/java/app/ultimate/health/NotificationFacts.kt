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
        // Shops advertise prices; only count it when it reads like a transaction.
        val kind = when {
            Regex("declin|insufficient|failed|retry|couldn.t|unable to|not go through|add money").containsMatchIn(t) -> Kind.Declined
            Regex("\\b(received|deposit|refund|paid you|sent you|you got|incoming|credited)\\b").containsMatchIn(t) -> Kind.Received
            Regex("\\b(due|bill|statement|autopay|payment reminder|minimum payment)\\b").containsMatchIn(t) -> Kind.Bill
            Regex("\\b(purchase|paid|payment|charged|spent|you sent|transaction|debit|withdraw|order total)\\b").containsMatchIn(t) -> Kind.Spent
            else -> return null
        }
        if (!moneyApp && kind != Kind.Bill && !Regex("\\b(card|account|bank|balance|transaction|purchase|payment)\\b").containsMatchIn(t)) return null
        return Fact(kind, money, line, app, at)
    }

    /** This week's money: spent, received, and failed payments (newest first). */
    data class Money(val spent: Double, val received: Double, val declined: List<Fact>, val bills: List<Fact>, val recent: List<Fact>)
    fun money(facts: List<Fact>) = facts.filter { it.kind in setOf(Kind.Spent, Kind.Received, Kind.Declined, Kind.Bill) }.sortedByDescending { it.at }.let { m ->
        Money(m.filter { it.kind == Kind.Spent }.sumOf { it.amount ?: 0.0 }, m.filter { it.kind == Kind.Received }.sumOf { it.amount ?: 0.0 },
            m.filter { it.kind == Kind.Declined }, m.filter { it.kind == Kind.Bill }, m.take(5))
    }
}
