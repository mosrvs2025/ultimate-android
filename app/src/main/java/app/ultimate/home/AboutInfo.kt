package app.ultimate.home

import app.ultimate.insights.Crash

/** The words on the About screen that can be worked out without Android, so they are tested. */
object AboutInfo {
    fun editionName(flavor: String): String = when (flavor) {
        "ultimate" -> "Ultimate"; "ultimateplus" -> "Ultimate+"; "studio" -> "Studio"; "traffic" -> "Traffic"
        "squeeze" -> "Squeeze"; "audio" -> "Audio"; "pluck" -> "Pluck"
        else -> flavor.replaceFirstChar { it.uppercase() }
    }

    fun versionLine(name: String, code: Long) = "$name (build $code)"

    /** One short line for a crash: when, what and where, so a list of them can be read at a glance. */
    fun crashLine(c: Crash, whenText: String): String =
        "$whenText · ${c.type.substringAfterLast('.')}" + (c.message?.takeIf { it.isNotBlank() }?.let { ": " + it.lines().first().take(90) } ?: "") + (c.frames.firstOrNull()?.let { " @ $it" } ?: "")

    /** A block to paste into a message: everything needed to know which build this is and what it is running on. */
    fun details(
        edition: String, versionName: String, versionCode: Long, buildType: String, androidRelease: String, sdk: Int, device: String,
        tools: List<String>, internet: Boolean, crashes: List<String>,
    ): String = buildString {
        appendLine("$edition $versionName (build $versionCode, $buildType)")
        appendLine("Android $androidRelease (API $sdk) on $device")
        appendLine("Internet permission: ${if (internet) "yes" else "no"}")
        appendLine("Tools (${tools.size}): ${tools.joinToString(", ")}")
        if (crashes.isEmpty()) appendLine("Recent crashes: none") else { appendLine("Recent crashes:"); crashes.forEach { appendLine("  $it") } }
    }.trimEnd()
}

/** What changed, newest first. Add a line here with every release so the About screen can say so. */
object Changelog {
    data class Entry(val version: String, val notes: List<String>)

    val entries = listOf(
        Entry("1.44.0", listOf(
            "New Focus tool, with ideas from Bluey: tell a little blueberry what you're working on and for how long. Its eyes follow your finger; it smiles while you're on task and looks worried when you drift.",
            "Every 30 seconds it notes which app is in front (on the phone, no screenshots). Drift has to last before it says anything, it waits two minutes between nudges, and a nudge is a gentle line and a soft chirp.",
            "The session shows as a coloured timeline you can tap through, with a big countdown, pause and end. Checks stop when paused or the screen is off.",
        )),
        Entry("1.43.0", listOf(
            "Search everything you watched and listened to, with a dashboard that follows your search: total time, a day-by-day line, a ring by app (tap to filter) and the artists you spend most time with (tap to search). Open, look up or forget any single item.",
            "The floating orb fans its actions out like a hand of cards, spaced so they never melt into one block.",
        )),
        Entry("1.42.0", listOf(
            "The bottom bar's highlight is a drop of liquid: switch tabs and it stretches across, two small drops trail it on necks and get pulled in, and it lands with a squish as the new icon hops up into it.",
            "The splash on every tap is gone.",
        )),
        Entry("1.41.0", listOf(
            "The gooey motion is easy to see now: tab pills land with a squish and drag drops behind them, switch thumbs swell and pull a tail, progress bars grow a bulb that sheds droplets, and the orb's petals bud on thicker necks.",
        )),
        Entry("1.40.0", listOf(
            "Notification history has an Insights tab: which apps are worth it and which are noise (by how often you actually open them), who reaches you and how fast you open them, when your phone pings, adverts dressed up as notifications, bursts, repeats, night-time pings and sign-in codes.",
            "Tap a noisy app to quiet it in Android. From now on the history also notes whether you opened, swiped or ignored each notification.",
        )),
        Entry("1.39.0", listOf(
            "Gooey motion across the app: switches whose thumb stretches like a drop, tab and option pickers with a liquid pill, progress bars that flow and bud droplets, and a success check made of drops that melt together.",
            "The floating orb's actions bud out of it on liquid necks. Home's running-work banner shows the work flowing.",
            "Turn animations off in Android's settings and everything holds still. Phones older than Android 12 get the same shapes without the melting.",
        )),
        Entry("1.38.0", listOf("Footprint is interactive: a sliding section bar to jump around, tap an app for what it can see and a button to change it, tap the exposure gauge to see which apps add most, tap any square of the week map, and tap a stat to jump to its section.")),
        Entry("1.37.0", listOf("Gooey effect in Footprint: drops of colour that merge and split in the header, and places that are close on your photo map melt into one blob (Android 12 and newer).")),
        Entry("1.36.0", listOf("Footprint: an exposure gauge for how much your apps can see, a header that drifts away as you scroll, shimmer while loading, haptic taps, a pulsing home dot on the places map, and a live equalizer on watch history.")),
        Entry("1.35.0", listOf("Footprint is livelier: a drifting-gradient header, your day as a tappable clock, a ring chart of who can see what, smooth line charts for steps, and bars that grow in.")),
        Entry("1.34.0", listOf("Footprint now also shows what you watch and listen to (opt in), where your photos were taken, and your steps, sleep and heart rate from Health Connect.")),
        Entry("1.33.0", listOf("New Footprint tool: what your phone knows about you, read from the phone itself: when you unlock, what you open, which apps can see what, data use, steps, and facts any app can read.")),
        Entry("1.32.0", listOf("Storage: open a folder and see its files as tiles, with a zoom from the tile you tap and a short glow on the biggest one.", "Clearer header and sizes (158 GB instead of 158.00 GB).")),
        Entry("1.31.1", listOf("Screenshots and a release workflow for GitHub.")),
        Entry("1.31.0", listOf("New About and settings screen: version, what's new, setup checklist, crashes and a copy-details button.")),
        Entry("1.30.1", listOf("Fixed the Terminal (and SSH tabs) crashing when opened.")),
        Entry("1.30.0", listOf("SSH client: saved hosts, keys, snippets, and connecting from the terminal.")),
        Entry("1.29.0", listOf("Rules: when something happens, do something, and undo it afterwards.")),
        Entry("1.28.1", listOf("Roomier top bars and a calmer Home.")),
        Entry("1.28.0", listOf("Home shows your most-used tools first; everything else is small and foldable.")),
        Entry("1.27.1", listOf("Fixed the floating orb crash loop.", "Clean up and Storage now load in seconds.")),
        Entry("1.27.0", listOf("Terminal with phone commands, and the floating orb.")),
        Entry("1.26.0", listOf("Storage map: what fills your phone, by kind, folder, file and app.")),
    )

    /** The entries to show: [count] most recent ones, always including the one for [current] if it is known. */
    fun recent(current: String, count: Int = 4): List<Entry> {
        val i = entries.indexOfFirst { it.version == current }
        return (if (i >= 0) entries.drop(i) else entries).take(count)
    }
}
