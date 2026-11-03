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

/** What Ultimate is, in words you can show or send someone. */
object Pitch {
    const val ONE_LINE = "Ultimate turns your phone into one private toolbox that also gives it new senses."

    fun text(edition: String, toolCount: Int): String = """
        $edition is one app that replaces a drawer full of single-purpose apps, and teaches your phone new tricks. Everything runs on the phone itself: no accounts, no ads, nothing uploaded.

        • Make space: shrink videos, clean up photos and duplicates, and see what fills your storage.
        • Make things: edit videos, record and transcribe, convert audio, a teleprompter.
        • Know your phone: what it knows about you, what your apps track, your notifications and clipboard history, screen time.
        • New senses: Echo uses sound too high to hear to feel movement and measure distance, like a bat.
        • Automate it: rules like "when something comes closer to the phone, turn on the flashlight".

        $toolCount tools in one place, with a floating orb to reach them from anywhere.
    """.trimIndent()
}

/** Which changes to show after an update: everything newer than the last version you saw, at most [max] releases. */
object WhatsNew {
    /** Null when there is nothing to show: a fresh install (shown the introduction instead) or no change. */
    fun since(lastSeen: String?, current: String, entries: List<Changelog.Entry> = Changelog.entries, max: Int = 5): List<Changelog.Entry>? {
        if (lastSeen == null || lastSeen == current) return null
        val newer = entries.takeWhile { it.version != lastSeen }
        return newer.take(max).takeIf { it.isNotEmpty() }
    }
}

/** What changed, newest first. Add a line here with every release so the About screen can say so. */
object Changelog {
    data class Entry(val version: String, val notes: List<String>)

    val entries = listOf(
        Entry("1.55.0", listOf(
            "Echo measures your hand properly: your last test read 4 cm whatever you did, because your hand changes how the speaker reaches the mic. Echo now models the speaker's own sound and the room separately, frame by frame, and keeps only what really moved.",
            "Gestures: push your hand toward the phone, pull it away, or hold it still. Rules can act on them, like \"When I push toward the phone, turn on the flashlight\".",
            "Reach: a living, gooey view of your hand above the phone, with how far Echo has detected so far.",
            "What's new appears after each update, and About now says what Ultimate is, with a button to share it.",
            "The Echo test fits the Fold's cover screen: its buttons no longer hide behind the navigation bar.",
        )),
        Entry("1.54.0", listOf(
            "Echo follows your hand, not your arm: it reports the nearest moving thing, which fixes readings that came out 10 cm too far.",
            "The Echo test is now a guided, full-screen walk-through: one big step at a time, a picture of where your hand goes, a countdown, and what Echo reads live. Results show your hand against Echo's reading on a ruler.",
            "Record a session: saves Echo's measurements (numbers only, never audio) with markers you tap, to share for a precise diagnosis.",
            "Senses on Home: Echo sits near the top with its live reading, so you don't have to scroll for it.",
        )),
        Entry("1.53.1", listOf(
            "Echo Distance: fixed constant false readings of a few centimetres. Your bench showed the speaker's own sound wobbling slightly every frame and looking like a hand; each frame is now locked to that sound, and each distance is judged against its own usual noise.",
            "Echo asks for the top and bottom microphones separately in a way Samsung phones allow, for a direction estimate.",
        )),
        Entry("1.53.0", listOf(
            "Echo can now measure distance: Distance mode repeats a chirp too high to hear and times its echoes, like a bat, to show how far away a moving hand is (to a few millimetres in tests).",
            "A live echogram shows what moves at each distance over time, a map shows the space in front of the phone, and a spectrum shows the raw sound. Every view says whether it is measured, estimated or an illustration.",
            "On phones that expose two separate microphones, Echo estimates which side the movement is on. The gyroscope pauses readings while the phone itself moves.",
            "A 30-second bench test measures accuracy, false alarms, speed and battery draw on your phone, with a report to copy.",
        )),
        Entry("1.52.0", listOf(
            "New sense, Echo: the phone plays a tone too high to hear and listens for its echo, so it can feel a hand or a person moving near it, and which way. Nothing is recorded.",
            "Automations can now react to what the phone senses: \"When something comes closer to the phone, turn on the flashlight.\"",
            "Live now on Home lists everything using the phone's senses right now, each with Stop, plus Stop everything.",
        )),
        Entry("1.51.0", listOf(
            "Clean up reads each app waiting under Check first for what else it does: the words in its menus and settings, its widgets, tiles, what it accepts from Share and what it asks permission for. Anything Ultimate may not do yet is listed.",
            "Mark each found feature Need it or Don't need. Your answers are kept.",
            "Copy build brief puts everything still needed, for every app, into one text to paste into a coding chat, so the missing parts can be built and the apps uninstalled.",
        )),
        Entry("1.50.0", listOf(
            "Squeeze: Watch both plays the original and the squeezed copy one above the other, in step, with one play button and one seek bar.",
            "Squeeze: Delete original frees the space once you're happy. It goes to Android's trash (kept 30 days), after Android asks you to confirm.",
            "Clean up now spots apps that Ultimate's own tools stand in for, such as a sound meter, voice recorder, teleprompter, clipboard or video compressor. Ones recognised only by name always wait under Check first.",
            "The orb has its own mark now, and while something runs it shows what: a video being squeezed, a film rendering, a song being cut, with the percentage.",
            "Studio: fixed the crash when trimming clips. The time ruler tried to draw a label where there was no room left at the end.",
            "Studio: trimming shows the frame at the edge you are dragging, live, and the trim handles are easier to catch.",
        )),
        Entry("1.49.1", listOf(
            "Fixed a crash when a panel was measured at a negative size for a moment (the jump panel while the screen resized, and the Squeeze before-and-after view).",
            "Studio: fixed a crash when trimming a clip that has a title very short (the title no longer tries to squeeze into no room).",
            "Crash reports now say where in Ultimate they happened: tap one under Recent crashes to see it, and Copy to send.",
        )),
        Entry("1.49.0", listOf(
            "Fixed a crash in You when two identical notifications arrived in the same moment (upload progress), and Back from You or any tool now returns to Home instead of leaving the app.",
            "Uploads and downloads are one notification entry that updates in place, ending with how they finished.",
            "Every record in You does something when tapped (opens the notification or its app, copies again, opens the photo); hold it for Open app, Copy, Inspect in Pluck, Make a rule, or Stop keeping. Copied JSON, links, commands and colours get one-tap actions.",
            "Findings in You wrap fully and, tapped, show the records behind them. Drag across the day's hour chart to scrub to a stretch of hours.",
            "Clean up's legend wraps instead of squeezing, and every coloured kind opens where you can deal with it. Storage's bar is tappable, and Biggest files has a grid with real thumbnails (photos, videos, app icons, PDF pages) and a Send to computer button.",
            "Jump to has a handle to drag it taller, and holding any tool (on Home or in Jump to) pins it to the top of Home. The privacy gauge no longer overshoots past 100.",
        )),
        Entry("1.48.0", listOf(
            "Footprint and Timeline are now one place, You: Days (everything, searchable, day by day), Patterns (your week, body, viewing, places) and Privacy (who can see what).",
            "For your AI: one tap writes an About me summary from your records (rhythm, apps, who reaches you, what you watch, focus) to copy or share with any AI.",
            "In the orb, tapping a notification opens it in its own app, and the chips open right there: who sent today's notifications, and what you copied (tap to copy again).",
            "The month strip in Days stacks each day by what filled it, in colour, and grows in.",
        )),
        Entry("1.47.0", listOf(
            "The floating orb steps aside while you're inside Ultimate, and comes back when you leave.",
            "Opening the orb shows today's numbers and your latest notifications; its badge counts new ones since you last looked. More opens the same panel as Jump to, with search (the keyboard works over any app) and hold-to-pin.",
            "Home has real cards: who sent the most notifications today, what you played, focus time, photos, and one finding from your records each day.",
            "Timeline now also knows when you take photos and screenshots, which apps you install, and when you charge your phone, with findings for each.",
        )),
        Entry("1.46.0", listOf(
            "New Timeline: search everything the app keeps (notifications, copies, what you watched, focus sessions) in one place, pick any of the last 30 days to see it hour by hour, and read findings that only show when the records are put together.",
            "Jump to is readable in dark mode, stays clear of the navigation bar, shows today's numbers at the top, and its search finds things in your records as well as tools.",
            "The floating orb's actions are now a tidy curved column of pills with their names inside, so they never pile up or overlap labels, whichever edge the orb is on.",
        )),
        Entry("1.45.0", listOf(
            "The floating orb's actions never overlap now, even with the orb on an edge or in a corner: they open in a half circle into the screen and nudge apart until they fit.",
            "The orb has a More button with every tool. Hold any of them to pin it to the orb, up to six.",
            "Jump to, inside the app, is new: the bubble melts into a panel with search, the tools you use most, and every tool by family.",
        )),
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
