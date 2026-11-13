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
    /** [tries]: tools this version adds or changes, so What's new and Home can take you straight to them. */
    data class Entry(val version: String, val notes: List<String>, val tries: List<String> = emptyList())

    val entries = listOf(
        Entry("2.11.0", listOf(
            "New: Movement. Record a walk or a run with the phone (motion sensors and GPS, with the screen off if you like) and it tells you the distance, pace, steps, laps, turns and stops, and, importantly, whether its own sensors agree. Three sensors measure the same walk (the camera's tracking when you used World Scan, GPS, and the steps felt by the accelerometer), each with its own stated uncertainty. Where they agree the answer is far more believable; where one disagrees it says which, why that is likely, and leaves it out of the total instead of averaging a wrong number in. Stretches no sensor saw are not counted: the distance is then a minimum, and it says so.",
            "Every number is marked OBSERVED (read from a sensor or entered by you), DERIVED (calculated from observations by a fixed rule, with its uncertainty) or INFERRED (a judgement, like walking vs running). The review screen has a map of your path coloured by which sensor drew each stretch, a timeline you can drag (speed, gait, stops, laps, turns, where a sensor was blind), a chart of how each sensor's distance compares with the fused one, and a list of what went wrong.",
            "Ask about a recording: \"How far did I actually travel?\", \"Which sensor disagreed?\", \"Where did tracking fail?\", \"How accurate is this?\". The answers are put together by fixed code straight from the measurements, not written by a model, so a number is never invented. The same for your mapped places: how well the last scan was tracked, whether walks could be merged and why not, and what a merge added or removed. It says plainly that the app cannot yet tell you how close a map is to the real building.",
            "Teach it from a course you know: enter the true length of a track lap or a measured route and the system learns your stride and the camera's scale. It learns only from distances you entered, refuses anything it estimated itself, and does not claim to be more accurate than the evidence allows. The raw sensor record of every recording is kept, so a better analysis later improves your old recordings too. Motion recorded on another of your devices (Octopus Mesh) is kept here and analysed the same way. World Scan walks appear in Movement as well.",
            "All of this was tested on simulated walks against known courses (distance, laps, turns, the uncertainty being honest, the calibration learning a 12% stride error in four runs). It has not yet been tried on a real phone: how well it counts steps in your pocket, tells running from walking, and keeps time with the GPS is for your first real walks to show. docs/MOVEMENT.md lists what is verified, what is not, and what is not built yet (video analysis, watch capture, drift correction from a confirmed return to the start).",
        ), tries = listOf("movement", "mesh")),
        Entry("2.10.0", listOf(
            "Octopus Mesh now makes your devices more useful together, not just connected. When another phone joins your mesh, your phones tell you (\"New device joined your mesh: Galaxy Z Flip6\"), and each device now says what it can do: motion sensors, microphone, speaker, camera, whether it reaches the high pitch used for distance. They find each other three ways: the signed broadcast on your Wi-Fi as before, Android's network-name discovery (for routers that block broadcasts), and Bluetooth, which also shows a rough band for how near a phone is (\"in the same room\") and tells you when a phone is close but not on a network it can reach.",
            "Lend this phone (new switch, off until you turn it on): lets your other devices use this phone's sensors and speaker. Then from your main phone you can press Record motion on the other one: it records its accelerometer and gyroscope (even with the screen off, from a pocket or an armband), shows live repetitions, sets and intensity on your phone, and on Stop sends the whole recording over to save as a CSV. The repetition count comes from how the phone moved, not from knowing the exercise, and has not been checked against a human count. Every use shows on the lending phone, in its notification, and in Live now with Stop.",
            "Distance by sound: Distance on a connected phone makes both phones play a short chirp near the top of hearing (17-20 kHz) and listen. Each finds both chirps in its own recording, so the two phones need no agreed clock, and the answer is the median of three runs with the spread and how clear the chirps were. It is rated MEASURED, and the screen says its accuracy on real phones is not yet verified: in synthetic tests it recovers the distance to about 2 cm, but only real phones can show how well speakers, microphones and rooms behave.",
            "The mesh keeps running with the app in the background (a notification with Stop, shown the whole time), and picks up again when you reopen the app if you left it on. The pairing code is kept in the phone's secure hardware-backed store so you do not have to retype it; Forget removes it and the list of known devices.",
            "Octopus Mesh is part of Ultimate+ only (it needs the internet permission); the plain Ultimate edition and the single-purpose apps do not contain it.",
            "Also in this version: Storage has a new list, Apps you could drop (under Biggest apps). It shows the apps you have not opened for a month or more, and any app one of Ultimate's tools already does, biggest first, with how long each has sat idle and how much space it takes. Tick the ones to go (a button picks the long-idle ones for you) and Android asks about each in turn; nothing is removed without you confirming it there. Apps that might hold something you cannot get back (an authenticator, a wallet, a money or messaging app) are marked Check first and are never ticked for you. It needs Usage access to know what you open; without it, it says so and offers nothing. The phone's record only reaches back 60 days, so an app with no use in that time says \"at least 60 days\", not a made-up longer number.",
            "Fixed: the Uninstall buttons (Pluck's cleanup, and the new list) could not have worked. From Android 9 the system's uninstall screen refuses an app that has not declared the right to ask, and Ultimate had not. It now does.",
            "The update flow is now counted step by step (tapped, needed permission, file gone, handed to Android, confirmation shown, failed or declined with Android's reason, went through), and the usage report has an Updates section. The Home update nudge was tapped 129 times in 16 days, far more than the number of updates, and the code alone cannot say why; this will.",
            "The usage report's \"Searched for, found nothing\" list is now trustworthy: it used to judge a search from the older command list only and ignore the palette's apps, files, settings and the rest, so a search like \"Open YouTube\" that worked was listed as a miss. It now asks the palette.",
        ), tries = listOf("mesh", "storage")),
        Entry("2.8.1", listOf(
            "The usage report no longer reports things that are already fixed. Crashes are grouped and checked against the version they happened on: the ones that were fixed (the Studio ruler crash in 1.50.0, the notification list crash in 1.89.0) are listed once as fixed, the ones that have not come back on your current version are listed as not seen again, and \"Crashes\" says \"None on 2.8.1\" when there are none. A crash that returns after being fixed is open again.",
            "Things that are not problems stay out of the report: a cleaning job that was cancelled when you closed the sheet (it used to show up as \"The coroutine scope left the composition\"), an install you declined, and searches that Home did answer (a brightness or volume command, a rule sentence), which used to be listed under \"Searched for, found nothing\".",
            "When a Studio export fails, the report now says which Media3 error it was and what was unusual about the project (clip count, size, source codecs, speeds, layers, music), without any names or content, so errors like \"Unexpected runtime error\" can be traced.",
            "When Android refuses a new install session (\"Too many active sessions\"), the message now says how many of Ultimate's sessions were cleared first and how many are still open. That error was recorded after it was meant to be fixed, and this is what is needed to see why.",
        ), tries = emptyList()),
        Entry("2.8.0", listOf(
            "Fixed: turning the phone no longer throws you out of World Scan (it also lost a walk you were in the middle of). The whole app now handles rotation itself instead of being rebuilt, so the camera view, the map and a walk in progress carry on; if the screen is ever rebuilt anyway (a change of language or theme), World Scan remembers where you were, and a walk in progress is saved instead of lost. The map keeps your pan and zoom when you rotate. This also applies to Room scan and every other screen.",
            "World Scan can correct a long walk's drift using GPS. Motion tracking drifts (its heading slowly goes wrong and its steps are a little long or short) and GPS doesn't, so over a long walk the two disagree more and more. Details now says whether the walk drifted, how much correcting would help, and offers a Correct the drift button on a place's first walk. It only offers it when the evidence is strong (at least 150 m, a statistically significant gain, a change of at least 2 m), because a GPS error that grows slowly between tall buildings can look like drift. The raw walk is kept and Undo puts it back. In a simulated 600 m walk the end was 13.5 m off before and 1.9 m after.",
            "Long walks use less memory: finished stretches of the map are written to a file as you go instead of piling up until Stop, the live map keeps showing the whole walk from small pictures of them, and saving no longer holds the walk twice. (About 100 MB a kilometre was my estimate from simulation; this should flatten it. Not measured on a phone.)",
        ), tries = listOf("worldscan")),
        Entry("2.7.1", listOf(
            "World Scan places a whole place from every walk of it. Each added walk's GPS is carried into the place's frame and all of it is fitted together, so GPS errors that differ from day to day average out: the heading and position get tighter with every walk, and a place whose first walk had no GPS is placed by a later one that did. The place card says how many walks it is placed from.",
            "The heading uncertainty it reports is now honest about GPS: a GPS error lasts many seconds, so many fixes are not many independent measurements. The claim is widened by what the walk's own residuals show, and in 40 simulated walks the true heading fell within the claimed 2 sigma 38 times.",
            "While you walk: a panel under the cubes chip (tap it) shows what the depth filters are throwing away, the camera-clock sync, GPS fixes, phone temperature and battery. The coach now speaks from the last five seconds of what was actually rejected (moving too fast, or depth too weak: dark, plain or far) instead of one frame, and says nothing when you simply stand still.",
            "Heat and battery are recorded with every walk (and shown in Details), and a walk stops and is saved if Android reports the phone as severely hot.",
            "Details has a new graph: how far apart the walk and GPS were at each fix over the whole walk. A steady rise is drift; spikes are GPS noise.",
            "The map's 3D view no longer builds a second copy of the whole map in memory.",
            "Sharing a path uses the place's combined placement, so walks added to a place appear in the GeoJSON even when they couldn't be placed on their own. A walk that was refused for merging is drawn on the map as a dashed grey path where GPS says it went, so you can see why it didn't match.",
            "Squeeze, when it finishes: a clear Delete original button on the big card (one Android confirmation for all the originals), and the card now says the truth: the space comes back when the original is deleted, not before (it used to say \"reclaimed\" and \"your originals were not touched\" at once). Videos picked through Android's photo picker used to get no delete option and no explanation; their real gallery entry is now found (and checked by exact size before anything is deleted), and if Android still won't allow it the card says so.",
        ), tries = listOf("worldscan")),
        Entry("2.7.0", listOf(
            "New: World Scan (Ultimate+). Walk down a street, round a building or across a park with the phone held up, and it builds a 3D map of what it sees from the camera's depth and motion tracking, notes where on Earth you were from GPS, and keeps everything on this phone. Start a walk from Home, then watch a live top-down map fill in behind you.",
            "Walk it again to make it better. Add a second walk to a place and the new walk is lined up with the old one, piece by piece (motion tracking drifts, so every 15 metres is fitted on its own), and only added if it truly fits. If the match isn't clearly the best one (a long straight wall can slide along itself) or the walks don't overlap, it is kept as its own walk, with the reason, and the saved map is not changed. Undo the last add any time.",
            "A map you can look at: pan and pinch the top-down map, turn it north-up once the walk is placed on Earth, replay the walk with a scrubber (time, distance, pace, whether tracking was working, GPS accuracy), see GPS fixes against the path, orbit the 3D points, and read the Details tab: heading uncertainty, GPS fit, camera-clock sync and jitter, tracking share, what each added walk changed.",
            "Honest about its limits: a walk is only placed on the map when its own shape lets GPS fix the heading (a 6 m straight walk is refused, a long bendy one is placed to within a couple of degrees in tests), a walk ends and is saved if tracking is lost for more than 4 seconds or comes back somewhere impossible, and it only records while it is on screen.",
            "Share when you choose to: the path as GeoJSON (it shows where you walked) or the map as a PLY point cloud (no location in it) for a computer.",
            "Under the hood: ARCore's frame clock is not defined, so every frame is logged with both clocks and put on the sensors' clock; the room scanner's depth filters, which would have thrown away a normal walking pace, are set for walking; GPS and sensor logs are written to a size-capped, compressed file on a background thread. Fixed in the first draft of this feature: it would have crashed on the first GPS fix on Android 10.",
            "Tried in simulation and by unit tests only; the first real walk is the real test. docs/WORLD_SCAN.md lists what it can't do (no loop closure, no colour, roughly placed on Earth) and a checklist for testing it on a phone.",
        ), tries = listOf("worldscan")),
        Entry("2.6.0", listOf(
            "Room scan can be told where the walls are. Point the dot at a wall and tap Mark wall (a button appears when you look level at a wall; also in the menu). Mark two opposite walls and you have one size, the other two the other. Once the walls are marked, a ceiling height you give (2.4 / 2.7 / 3.0 m, or typed) stays a height above the floor instead of drifting when the floor estimate moves.",
            "The scan and the saved room now show the walls as faint surfaces over the part that was seen, and the scan screen has a Floor / Walls n/4 / Ceiling checklist, so you can see what is still missing and the room reads as a room instead of scattered points.",
            "Adding to a space is more careful. The fit between the two scans is shown, a loose fit (over 9 cm) only adds and never clears, and a very loose one (over 20 cm) is refused with a reason instead of blurring the walls.",
            "Fewer made-up things: a thing needs four sightings, and a small thing (bottle, cup, book) that sits flat on a wall well above the floor, where the depth is just the wall, is dropped.",
            "Storage: tapping a file in the map now shows its picture (a frame of a video) and name before you open anything, and tiny tiles show what kind of file they are.",
            "Phone checkup's last report is now cards: what to look at first (with the apps involved; tap one for its settings), who can reach what, your phone's details, and the app list.",
            "Squeeze opens its live details while it works.",
            "Not in this build: the palm-on-wall gesture (use Mark wall), and a better Battery screen inside the app.",
        ), tries = listOf("roomscan", "checkup", "storage")),
        Entry("2.5.0", listOf(
            "Room scan sees plain walls and ceilings. The diagnostics from your last scan showed three quarters of the depth being thrown away, mostly on plain paint, where the camera's raw depth has holes. Those holes are now filled from ARCore's own filled-in depth, as weak evidence that needs several views to agree and never clears anything; the sharp-edge filter is looser too. In the simulator a nearly blank wall went from 14% covered to fully covered.",
            "A size you have measured wall to wall no longer shrinks or vanishes: a later measurement that sees fewer walls keeps the earlier size, in the live readout, when you add to a space and when you save. (Start over clears it.) The floor under your feet is skipped over a smaller area.",
            "Home cards go where they say. Battery and Storage are now two cards (Battery opens Android's battery usage, Storage the storage map). Played today opens the Timeline on what you played, Photos today opens Clean up when there are screenshots to tidy, and Last copied opens your clipboard history with a copy icon on the card for the quick re-copy.",
            "Phone checkup opens on your last report (score, date, findings) instead of the first-time screen, with Scan again one tap away.",
            "Squeeze, while squeezing: the frame being squeezed with a light sweeping down it, the size falling live (552 MB ➔ 23.3 MB, −95%), one thin progress bar instead of two, and a quieter Cancel squeeze button. When finished: a Send to your computer button next to Play and Share, and the Original / Squeezed labels sit along the bottom of the comparison instead of on top of the video's own status bar.",
            "The share-menu shortcut is now called Strip metadata & share.",
            "Not in this build: putting your palm on a wall to mark it, and drawing a floor where none was seen.",
        ), tries = listOf("roomscan", "squeeze", "checkup")),
        Entry("2.4.0", listOf(
            "Workouts, in the Health screen. Prop the phone up, pick an exercise and tap Count a set with the camera: it finds your joints on the phone (the picture is dropped, never saved), counts each full repetition, tells you what to fix, and keeps the set. It can count squats, push-ups, sit-ups, lunges, jumping jacks, bicep curls, shoulder presses and high knees, and time a plank.",
            "Only full reps count. A half squat is not counted and says \"Go deeper\"; a plank only runs while your body is level and straight, and says whether to lift or lower your hips. Standing still, walking about or being out of position counts nothing.",
            "It can count aloud so you never need to look at the screen, and finishing a set shows how deep your reps went and whether it is a new best.",
            "The Health screen now has a Workouts card: this week's totals, today's sets, your streak, recent sets, and Log a set by hand (which works even in builds without the camera counter).",
            "Where to put the phone: push-ups, sit-ups and planks need it on the floor, side-on to you; squats and lunges 2 to 3 metres away with your whole body in the picture; jumping jacks, presses and high knees facing the phone. Tested on simulated bodies only; I haven't been able to try it on a person yet.",
        ), tries = listOf("health")),
        Entry("2.3.1", listOf(
            "\"Scan again to improve\" now really updates that space. The main button says Update <name> from the moment you start, never asks for a name, and if the scan hasn't lined up with the space yet it does that when you tap (and says so plainly if it can't, instead of making a new space).",
            "Mark ceiling / Mark floor no longer use an old reading. With no distance at the dot, the ceiling used to be set from the last good reading (that is how it came to say 7.47 m high). Now it needs a distance right now, above everywhere the phone has been; otherwise it offers 2.4 m, 2.7 m, 3.0 m or a typed height. A tap on Reset clears a floor or ceiling you marked.",
            "Measuring distances is now a mode you turn on from the menu, with a visible Done button, so touching the screen no longer drops yellow rulers.",
            "Your own legs and feet are no longer mapped when you look down: anything in a column straight below the phone is skipped.",
            "Things are checked against their real size: a big wooden chest of drawers is now a cabinet instead of a suitcase, and a bottle a metre wide or a bed 60 cm long is dropped. In a saved space, tap Things to relabel or remove any of them.",
            "The \"could be my room, but it fits more than one way\" message no longer sticks around: it is re-checked every time, including each time a new thing is recognised, and things the two scans have in common now settle a room that fits two ways round.",
            "Not in this build: putting your palm on a wall to mark it (needs hand tracking running beside the scan).",
        ), tries = listOf("roomscan")),
        Entry("2.3.0", listOf(
            "Adding to a saved space no longer wipes the scan. Before, tapping Add cleared everything on screen and you had to start again; now it keeps scanning and each Add folds in what is new. One visit is one entry in the scan history, and Undo takes the whole visit back.",
            "Merging is tighter. The match against the saved space is polished against the real wall points on every Add (before, the first match was reused, which is why every scan showed the same 75% fit), and a point that lands next to an existing one now confirms it instead of laying a second copy of the wall beside it.",
            "A measured ceiling is kept. The room height from the ceiling fan stays with the space across scans, and a ceiling can now also be estimated from where the walls end (only when you really tilted up at it, and shown as an estimate). If depth can't see the ceiling and there is no distance at the dot, Mark ceiling asks for the height.",
            "Things are tidied: the same thing seen twice is one thing, and one that is not found again in two scans that looked at its place is dropped. Ones not found last time are hidden from the viewer and listed under Things.",
            "New scan screen: one main button (Save, or Add to <space>) and a menu, a small readout that expands when tapped, and a \"Tap to mark the ceiling / floor\" button that appears when you aim up or down at one that isn't measured yet. The camera now fills the screen.",
            "New viewer: the 3D view fills the screen with size, height and things chips across the top, a Scan again button, and Rename, Undo scan, Scan log and Forget tiles. Names only show where there is room, so they no longer pile up.",
        ), tries = listOf("roomscan")),
        Entry("2.2.1", listOf(
            "Room scan: 2.2.0 was too strict. On a real phone it kept about a quarter of the points of the earlier build and found no walls or ceiling. A point now needs three viewpoints instead of four, doubtful depth readings count a little instead of not at all (so plain walls fill in over a few views), fewer readings are thrown away at depth edges, and the step that clears seen-through points no longer eats into walls whose depth is noisy.",
            "The picture reacts at once: readings that aren't confirmed yet are drawn as faint pale dots and turn solid as they're confirmed. Only confirmed points are measured and saved.",
            "You can tell it what it is looking at: aim the dot at the floor or the ceiling and tap Mark floor or Mark ceiling. The measurement then uses your mark and says so. The ceiling can also come from ARCore's own surface detection when depth can't see a plain ceiling.",
            "Things are recognised more readily again (the thresholds were too high), and are placed using lower-confidence depth when that is all there is.",
            "The buttons under the camera wrap onto a second line instead of running off the screen. The 3D viewer turns about the middle of the scan and shows a half-metre floor grid.",
            "Still simulator-tested only; the simulated phone was made noisier to match what the recording showed.",
        ), tries = listOf("roomscan")),
        Entry("2.2.0", listOf(
            "Room scan rebuilt after the first real-phone test. The wrong numbers in that recording (a 5.95 m ceiling, 0.0 m² of floor, a 6.6 × 4.5 m bedroom) came from taking the lowest and highest stray depth reading as floor and ceiling. Floor and ceiling now need a real area of support, must sit below and above everywhere the phone has been, and a size is only given when walls were seen on both sides. What isn't measured says \"not measured\" with a hint on what to scan.",
            "Much less noise: the phone was counting the same depth picture several times, which made noise look solid. Each picture is now counted once, needs four different viewpoints to become solid, is skipped while the phone moves too fast, and readings on depth edges or beyond 4.5 m are dropped. Things later seen through (a hand, a foot) fade out.",
            "Scanning a saved space again improves it: the new scan is lined up with the saved one from walls and tall furniture (no longer dependent on the floor estimate), then folded in. Agreeing points gain confidence, new areas are added, and things that are gone from places you looked at are cleared. Recognition now runs by itself while you scan. Each space keeps a scan history, and the last scan can be undone.",
            "Things keep their identity between scans (the same bed stays one bed), are merged by their real size instead of a fixed 70 cm, and are placed by the depth across the middle of the detection rather than one pixel. Ones not found again where you looked are marked, not silently dropped.",
            "The distance at the dot falls back to ARCore's filled-in depth where the raw depth has holes (plain walls, doors), shown as \"≈ … (estimate)\". The viewer has Plan and 3D views with the measured outline and its sizes drawn in. Tap the readout while scanning for diagnostics. Spaces can be marked as room, vehicle or shelving and grouped by place.",
            "Verified on a simulated phone only (noise, stray readings, a mirror, a hand, drift): see docs/SPATIAL_SCAN.md for what still needs a real-device test.",
        ), tries = listOf("roomscan")),
        Entry("2.1.0", listOf(
            "New in Ultimate+: Room scan (under Senses). Walk slowly round a room with the camera and the phone builds its shape in 3D from ARCore depth and motion tracking, shows the distance to whatever the centre dot is on, and works out the room's length, width, height and floor area. Tap two points to measure between them.",
            "It recognises furniture and household things on the phone (chairs, couch, bed, table, TV, laptop, plants and so on), pins a label on each where it stands in the room, and only keeps one after seeing it several times.",
            "Save a room and it is remembered: open it later in a 3D viewer (drag to turn, pinch to zoom), and when you scan again tap \"Have I been here?\" to have the phone recognise the room from its shape, show the things it remembered there in green, and add the new scan to it.",
            "Private by design: camera pictures are looked at on the phone and dropped; only the room's shape and labels are stored, in the app's own storage. Nothing is uploaded.",
            "Honest limits: needs a phone with ARCore depth (it offers to install Google Play Services for AR). Most phones work out depth from the camera moving, so blank walls, mirrors, glass and dim light scan poorly, and measurements are estimates. This was built without a phone to test on; see docs/SPATIAL_SCAN.md.",
        ), tries = listOf("roomscan")),
        Entry("2.0.0", listOf(
            "Fixed: a clip you just imported into Studio wouldn't play. The preview muted itself while a clip loaded and, when it finished, set \"playing\" from an out-of-date value, so tapping Play during that moment (right after importing) was undone. It now follows what you asked for, and the first clip no longer waits at all.",
            "Version numbers now go 2.0.0 and up, so they stop looking like they go backwards (1.100.0 is higher than 1.99.0 but reads like 1.10).",
        ), tries = listOf("studio")),
        Entry("1.100.0", listOf(
            "Cut pauses: select a clip and tap Cut pauses. Studio listens to its sound on the phone, finds the silent stretches (judged against that recording's own background noise, with a little kept on each side so words aren't clipped) and cuts them out in one step. The status line says how many it cut; Undo puts them back.",
            "Split is always there now, in the main toolbar as well as with a clip selected, and it cuts the clip the playhead is on, so there is nothing to line up first.",
            "Layers: Start here, End here and Match clip set a layer's timing from the playhead or the clip under it, and Duplicate copies it.",
            "A short guide appears the first time you have clips (tap a clip to edit, split at the playhead, pinch to zoom, hold to reorder) and goes away for good when you tap Got it.",
        ), tries = listOf("studio")),
        Entry("1.99.0", listOf(
            "Studio layers: a second track over your film for text and stickers. Layers button (or the new row under the clips): add text or an emoji sticker at the playhead, drag it on the picture to place it, drag its ends on the track (or the range slider) to choose when it shows, and set size, colour and top, middle or bottom. Layers are saved with the project, follow the film if you trim it, and are burned into the export.",
            "Days tab: the summary chips (photos, screenshots, notifications, watched…) are now tappable. Tapping one filters the feed below, scrolls it into view, lights up its heading for a moment (\"Only photos · 12\") and gives a Show everything button. The chip you picked is outlined.",
        ), tries = listOf("studio", "timeline")),
        Entry("1.98.0", listOf(
            "Health says it plainly when your activity is low: \"Your activity is low: you're averaging 3,200 steps a day against a goal of 8,000\", with what a daily walk is worth, even when nothing changed from last week. Before, it said nothing was worth doing.",
            "Tapping a day in the steps goal strip now shows that day's steps big, with its date and how close it came to the goal, and outlines the bar.",
            "Food and water journal on the Health screen: note what you ate or drank (with an optional calorie guess), tap for each glass of water, and see how the week is going. It stays on the phone.",
            "The tongue is no longer always \"out\": it needs an open mouth, learns your normal colour while your mouth is shut, and smooths over single frames.",
            "Playground tabs scroll instead of squashing, and hints are shorter.",
            "Desk piano: the thumb (C) now counts with a shorter tap than the fingers, since it moves less up and down.",
            "AR gestures only listen when you turn them on: hold both hands above your head and they are on for ten seconds (a chip shows the countdown), each swipe keeps them on, and the swipe has to be longer and straighter. Reaching, waving or drinking no longer changes effects or starts the squat counter. The palette is also tappable, and point-and-hold takes half a second.",
        ), tries = listOf("health", "playground")),
        Entry("1.97.1", listOf(
            "Updates install from the app again: tapping the update notification, Home's update suggestion or Install in About now opens a small screen that shows the copy progress and then opens Android's confirmation itself. Before, the app closed the moment it handed the file over and the answer arrived in the background, where newer Android refuses to show the confirmation, so it ended as \"Install cancelled\".",
            "Much less lag from updates: each downloaded APK is now read once (these builds are over 70 MB, and Home, About and every return to the app were re-reading up to 20 of them), only the six newest recent files are checked, and the file is copied in bigger pieces.",
        )),
        Entry("1.97.0", listOf(
            "The orb can appear by itself: Floating orb now has Show it when I leave the app. Press home or switch apps and it shows up, no need to switch it on first. It still hides while you are in Ultimate.",
            "No more square around the orb: the orb's shadow was being cut off by its overlay window, which showed a hard-edged box during taps and the opening animation. It now has a soft halo that stays inside.",
            "Place it where you like: choose Snap to edge (as before), Soft edges (it sticks to a side only if you let go close to it) or Anywhere (it stays exactly where you drop it).",
            "A Media and quick petal in the bloom: play and pause, skip, volume, what is playing (when notification access is on), and shortcuts for the torch, Internet, sound and display, without leaving what you are doing.",
        ), tries = listOf("orb")),
        Entry("1.96.0", listOf(
            "A deeper Health screen: This week vs last cards for steps, sleep, resting heart rate, HRV and active calories, each with the change, an arrow coloured by whether that is the good way, last week's number and a two-week line.",
            "Steps goal: the last seven days as bars against 8,000 steps, green on the days you hit it, with your streak. Tap a day for its count.",
            "Sleep by stage: seven nights as stacked bars (deep, REM, light). Tap a night for its breakdown.",
            "Worth doing: up to four plain observations from your own numbers (short sleep, steps down, resting heart rate up, a streak to keep). They only appear when the numbers clearly support them and are not medical advice.",
        ), tries = listOf("health")),
        Entry("1.95.0", listOf(
            "A floating palette in AR: hold both hands above your head and an arc of choices appears over your shoulders and moves with you. Point at one and keep your hand there; a ring fills and it is chosen. Effects normally, outfits when you are wearing one.",
            "Energy blast has impact: sparks rush inward and rings close faster as you charge, colours pull apart at the edges, and letting go fires a beam with a flash and a shake.",
            "Air swipes are easier and you can see them being read: a sweep at chest height of about two thirds of your shoulder width is enough, a bar at the top fills toward the side you sweep, and a ghost trail follows your hand.",
            "Costumes follow your pose: the crown and shades tilt with your head, each boxing glove turns and sits along its forearm, and each trainer points where the foot points.",
            "Smoother tracking: the body model now reads a small picture while the preview stays sharp, and when tracking falls behind the effects lighten (fewer sparks, no finger tracking) until it catches up.",
        ), tries = listOf("playground")),
        Entry("1.94.0", listOf(
            "Studio opens clean: coming back asks \"Pick up where you left off?\" (Resume or New project), and videos you send to Studio from elsewhere always start a new project instead of joining the last one.",
            "Edit in Studio: any video in the Library (from Squeeze or Studio) now has an Edit in Studio button that opens it straight in the editor.",
            "Name your film: tap the title at the top of Studio (or File name in the Export sheet) to rename it. The name becomes the start of the exported file's name.",
            "Export finished: when a render ends a clear box says so, with the file name, size and length, and Play and Share buttons.",
            "Smaller exports: Studio now picks the video codec and bitrate from your clips instead of always using 8 Mbps H.264. A trimmed Squeeze (HEVC) video stays HEVC at about its own bitrate, so it should no longer come out bigger than the original. The render log says what was chosen.",
            "Fewer export errors: if the encoder hits a runtime error, Studio retries once in a simpler way (no forced audio track, loop or custom fades) and records the reason, before giving up.",
            "Smoother preview: scrubbing jumps to the nearest keyframe while you drag and lands on the exact frame when you stop, and the player is muted while the next clip loads so clips no longer overlap in sound.",
            "Clearer tool descriptions: Studio is \"Edit videos\", Squeeze is \"Make a video smaller\", Video to audio is \"Pull the sound out of a video\".",
        ), tries = listOf("studio")),
        Entry("1.93.0", listOf(
            "Work the AR camera from across the room: raise a hand and sweep it sideways to go to the next or previous effect, and hold both hands above your head to switch the swipe between effects and outfits. A big banner says what you picked.",
            "Energy blast: bring both hands together to charge a ball of energy (the longer, the bigger), then throw them apart to fire it.",
            "Outfits drawn on your body: tee and jeans, hoodie, summer shorts, suit, hero with a cape, ninja, armour and a gi. Swipe in the air to change them.",
            "Neon body now also shows your eyes and mouth, and every finger when a hand is close enough to read.",
            "Legs and feet are kept from further back (they are trusted at a lower score, and a small far-away body is no longer dropped), and the squat counter reacts sooner and shows when you are down.",
        ), tries = listOf("playground")),
        Entry("1.92.0", listOf(
            "New in Playground: AR. The camera now tracks your whole body (on the phone, nothing saved) and draws on it live: a neon skeleton, light trails from your hands and feet, fire from your fingertips, sparks when your feet move, and a costume (crown, shades, boxing gloves, trainers) that follows you.",
            "Coach: shows your elbow and knee angles on your body, counts squats, and tells you if your shoulders are level.",
            "Ten looks for the picture: Vivid, Mono, Noir, Sepia, Warm, Cool, Night, Heat, Negative, or none. Flip between front and back camera with 🔄.",
        ), tries = listOf("playground")),
        Entry("1.91.0", listOf(
            "Suggestions you can turn down: with \"Learn my habits\" on, the suggested tool on Home is now a clear card that says why (\"Because: charging, evening\") with a Not now button. Not now is remembered: the same tool isn't offered again at that time of day for two weeks, and three refusals in a month silence it.",
            "Habits now also notice how the phone is carried (on a table, in your hand, walking), felt for two seconds when Home opens, so a suggestion can depend on it.",
            "The insight card on Home shows \"See ›\" so it is clear it opens the records behind it.",
        )),
        Entry("1.90.2", listOf(
            "Your money is read more carefully: a purchase that \"didn't go through\", was declined, blocked, cancelled or reversed no longer counts as spending, offers, balance notices and security alerts are ignored, and the same payment announced twice counts once. A failed payment shows as failed, not as money spent.",
            "Your body says why a number is missing instead of a bare dash: resting heart rate, sleep and heart rate variability now show the latest from the last few days with how old it is, and when there is none it says whether Health Connect needs your permission or simply has no data. Tap it to fix it. Allowing only some kinds of health data now works too (before, one missing permission hid the whole group).",
            "Tapping the insight on Home (\"32 things you copied…\") now opens the records behind it, not the general You screen.",
            "Home draws instantly from what it showed last time, so the cards no longer pop in one by one a moment after opening.",
            "The Last 30 days charts on Health (steps, sleep, resting heart rate) can be tapped or dragged across to read any day's date and value.",
        )),
        Entry("1.90.1", listOf(
            "Fixed \"Couldn't start the update: Too many active sessions\": every install you cancelled or that failed used to leave an unfinished install session behind, and after enough of them Android refused new ones. Ultimate now clears its own leftover sessions before each install and when one is cancelled or fails.",
        )),
        Entry("1.90.0", listOf(
            "Rename what a tool just made: a Rename button on the finished screen of Video to audio, Squeeze, Studio (Your videos) and Photos, and on every result in the Library for those tools and Recorder. The file itself is renamed, its ending (.mp3, .mp4…) is kept, and the name shows everywhere straight away.",
            "Adaptive Home (off until you turn it on): About and settings has a new \"Learn my habits\" switch. When on, Ultimate remembers which tool you open at what time of day, whether you were charging, had headphones on, and which tool came before, only on this phone. Home then puts the one tool it is sure about after your pinned tools and says why. It says nothing when it is not sure, and turning the switch off or choosing Forget deletes what it learned.",
            "Walking makes the tools you tap bigger: for two seconds each time Home appears the phone feels whether you are walking, and if so your favourite tiles grow taller. Nothing moves or disappears.",
            "Fixed a crash in the Timeline when two records had the same id (for example a notification that updated).",
        ), tries = listOf("audio", "squeeze")),
        Entry("1.89.0", listOf(
            "A real media player for anything you open with Ultimate+: album art (or a colour drawn from the track), title and artist, a big play button, a seek bar with times, skip back and forward 10 seconds, the other songs in the same folder as previous and next, repeat one or all, speed, and a sleep timer.",
            "Music keeps playing when you leave the player or lock the phone, with controls in the notification, on the lock screen and from headphones; it pauses when headphones come out.",
            "Video plays full screen with tap-to-show controls, and pictures zoom. \"Do more with it\" hands any of them to Ultimate's tools.",
        ), tries = listOf("files")),
        Entry("1.88.0", listOf(
            "Open with Ultimate+: Ultimate now shows up when you open any audio, video or picture from another app (a downloaded WAV, a video in Files…). It plays right away with seek, speed and loop, shows pictures with pinch to zoom, and \"Do more with it\" hands the file to Ultimate's tools.",
            "One flick is one flick: the smaller swing back as your wrist stops no longer counts as the opposite flick (fixed on the watch, 1.6, and on the phone), so rules fire once.",
            "Pattern Studio on the Watch screen: do a motion for a few seconds (scratching, fidgeting, a gym rep, brushing teeth), tap 🏷 Teach this and name it. From then on the live graph says when it sees it (\"🔍 Scratching (91% match)\") and counts it for the day.",
            "The live watch graph can be paused and scrubbed back through the last 30 seconds, and Record keeps every reading on the phone (exportable from Air mode's ⓘ) to look for patterns later.",
            "Air control shows your watch's motion as a live strip along the bottom of the camera, so hand and wrist can be read together.",
        ), tries = listOf("watch", "air")),
        Entry("1.87.0", listOf(
            "New: Spatial (in Senses). Your phone and watch sense what's around you, measured, never made up.",
            "Your watch's distance: with the watch's new Proximity beacon on (watch 1.5), the phone hears how close your watch is by Bluetooth and shows it in zones (right by the phone, within reach, away), calibrated for your own phone and watch, with how sure it is. No fake metres.",
            "Bring the watch close to open the Orb, move it away to close it (turn it on in Spatial). It has to hold for a moment, so passing by doesn't trigger it.",
            "Wi-Fi: the strength of the network you're on, every network you can hear, and a signal map you build by walking (placed by your steps and compass, coloured by strength), with dead zones marked.",
            "Places: save where you spend time (\"Desk\", \"Kitchen\") and Ultimate recognises them by their Wi-Fi fingerprint, with a confidence.",
            "Rules can use all of it: \"When I'm at my desk, …\", \"When my watch comes close, …\", \"When my watch moves away, …\". Turn on Keep sensing for rules with the screen off (it shows a notification with Stop).",
            "It checks what your phone really supports (Bluetooth LE, UWB, Wi-Fi ranging, Bluetooth channel sounding) and says so. Everything stays on the phone.",
        ), tries = listOf("spatial")),
        Entry("1.86.0", listOf(
            "Brightness, volume and the flashlight just work from search and voice: \"brightness to 50\", \"make the screen dimmer\", \"max brightness\", \"volume up\", \"mute\", \"flashlight off\". They're read directly, instantly, even without Local AI, and never confused with each other. (Brightness asks once for \"Modify system settings\".)",
            "Local AI's suggestions get a sanity check: it can no longer offer a volume change for a sentence about brightness.",
            "You → Today now reads your notifications for what matters: your money this week (spent and received, bills due, and a warning when a payment didn't go through, like a declined PayPal purchase) and health alerts from your watch (like \"HR low (51 bpm)\"). Shop ads with prices don't count. Nothing leaves the phone.",
            "Watch heart rate sharing is much lighter on battery: it measures for about 25 seconds every 5 minutes, and only follows closely while your heart rate is high (110 or more) until it settles.",
            "Fixed \"up past midnight on 21 of the last 13 days\": late nights are now counted against the days they cover.",
        )),
        Entry("1.85.0", listOf(
            "Watch 1.4: with \"Share heart rate\" on, the watch keeps sending your heart rate when its app is closed and the screen is off, so body rules and the heart rate on Home work all day. It shows a notification on the watch with Stop. Update the watch from Watch in Ultimate+.",
        ), tries = listOf("watch")),
        Entry("1.84.0", listOf(
            "Your body on Home: a pulse strip under the search bar shows your watch's heart rate right now and how you feel today, with a one-tap mood check-in when you haven't given one. Tap it for You → Today.",
            "Body rules: Rules can now react to you. \"When my heart rate stays high, turn on Do Not Disturb\", \"When I'm stressed, …\", \"When my heart rate calms down, …\", \"When I feel low, …\". A high heart rate counts once it has lasted a minute, so standing up or a sneeze doesn't trigger anything.",
        ), tries = listOf("rules", "timeline")),
        Entry("1.83.0", listOf(
            "You has a new Today page: how you're doing (a one-tap mood, and a read on your day from sleep, heart rate variability, resting heart rate, late-night phone use and notifications, with every reason listed), your body (live heart rate from the watch, steps, sleep, HRV), your phone life, and what it's learning about you. Everything stays on the phone.",
            "Search hands on what you asked for: \"YouTube notifications\" opens Notification history already searching for YouTube. And it no longer flashes \"Nothing matches\" while it's still looking.",
            "Camera calibration coaches you live, right on the picture: show your hand, turn it flat, and (the usual reason for \"0 matched readings\") bring it lower over the bottom of the phone where Echo's speaker is, tilting the phone back so the camera still sees it. You feel a tick as readings are matched.",
            "Hand size is now Small, Medium or Large (with − and + to fine-tune) and says what it's for: the camera judges distance from how big your hand looks.",
            "Echo ignores your voice: on phones whose microphone is always processed, talking could look like a hand moving. Echo now notices speech and pauses motion for a moment, and says so.",
            "Wearing your watch? Air control shows your live heart rate right on your wrist in the camera view, while the watch is sending it.",
        ), tries = listOf("timeline", "echo", "echo-camera", "air")),
        Entry("1.82.1", listOf(
            "Fixes 1.82.0, which was built without its hand and face models (\"Gesture tracking couldn't start … gesture_recognizer.task\") and without Local AI's engine. Everything is back, and builds now refuse to ship without them.",
        ), tries = listOf("playground")),
        Entry("1.82.0", listOf(
            "Search understands what you mean: \"Play YouTube\" or \"open the YouTube app\" puts YouTube itself on top, instead of searching for the words.",
            "Air control: all five fingertips light up, and a new Both mode tracks your hand and face together from one camera. A ✨ Playground button sits in the top bar.",
            "Desk piano: Finger pads (new default) puts a note right under where each finger rests, so different finger lengths don't matter: thumb C, index D, middle E, ring F, little G. A note now needs a real tap (lift, then down moving), with a buzz; resting or drifting doesn't play. Keyboard mode is still there.",
            "Draw sticks to your face: with 📌 On my face (the default), what you draw near your face moves, tilts and scales with your head. Switch to 🌐 On the screen to draw on the glass.",
            "Face: paint on your face by pinching in the air as well as with a finger on the screen.",
            "Hands-free calling is easier to drive: a bigger live view of your face with its mesh, and a live meter for each move (up, down, call, cancel) so you see how close you are. Nodding or tilting your head also scrolls, and the eyebrow moves count sooner.",
            "In the dark, the camera screens offer 🌙 Light my face: a warm ring light around the screen edges at full brightness.",
            "Echo's tabs no longer wrap (\"Echogra-m\").",
        ), tries = listOf("playground", "air")),
        Entry("1.81.0", listOf(
            "😛 Tongue out, seen by the camera: the face tracker has no tongue, so Ultimate looks at the picture just below your lower lip for it, against your own skin in the same light. It's a new face gesture you can give an action in Air control, and in hands-free calling sticking your tongue out (held) calls, just like holding your mouth open.",
        ), tries = listOf("playground", "air")),
        Entry("1.80.0", listOf(
            "Record for training (Air mode ⓘ): when you turn it on, Air mode keeps your hand points, pinches, watch motion and what you did (never pictures) so a model can be trained just for you. Export sends it all as one zip; it's capped at 50 MB and deletable any time.",
            "Made for tablets too: on a big screen (like a Galaxy Tab) Home shows your tools three to five to a row instead of stretching two, Air control puts the camera and its controls side by side, and Files lays its categories out in three columns.",
        )),
        Entry("1.79.0", listOf(
            "New: Playground, the camera as a toy, kept apart from Air control so nothing fires by accident.",
            "🎹 Desk piano: point the back camera down at your desk, rest your fingertips and tap Set the desk. A keyboard (C to C) appears along it; lift a finger and bring it down to play the key under it. All five fingertips play on their own, with a piano sound made on the phone.",
            "✏️ Air draw: pinch thumb and index to put the pen down, move to draw glowing lines in the air, open to lift. Pick colours, undo, clear.",
            "📞 Hands-free calling: make the call-me sign 🤙 in Air control (or open Playground's Call). Raise your eyebrows or look up to go up your contacts, lower them or look down to go down, hold your mouth open to call; a 3-second countdown follows, and closing your eyes cancels it. Made for fun, and for anyone who can't easily use their hands.",
            "😎 Face fun: stickers that sit on your face (crown, shades, dog, cat, butterfly…), and paint on your face with your finger on the screen; it stays on your cheek as you move, tilt or come closer.",
        ), tries = listOf("playground")),
        Entry("1.78.0", listOf(
            "Home, rearranged: search sits right under the title, then today's live picture (photos, screen time, what's running, your senses), then your tools in more compact tiles, so the live stuff is the first thing you see.",
            "Air control, tidied: one compact bar (Start Air mode, gestures on or off, and ⓘ for how it works) instead of three stacked cards; the camera stays pinned at the top with Hand / Face and the camera flip floating on it; what each gesture does is a clean list in groups (Navigation, Media, System), each a tap to change.",
            "New finger shapes, read finger by finger: middle finger, rock on, call me, three and four fingers. Find them under Finger shapes in Air control and give each an action.",
            "The practice now shows your hand faintly behind the targets, so you can see the ring rides on your index fingertip.",
            "Pinch to tap lands where you aimed: closing your fingers pulls the fingertip toward your thumb, so the tap now goes where the cursor was just before the pinch started.",
        ), tries = listOf("air")),
        Entry("1.77.0", listOf(
            "Talk to Air mode: an Ask pill sits at the top of the screen. Point at it, pinch, and say what you want: \"open Spotify\", \"go back\", \"scroll down\", or anything Local AI understands (\"turn on the flashlight\"). The pill shows what it heard and did.",
            "Air mode knows when you pick the phone up: while it's in your hand the cursor steps aside so touch works normally, and it comes back by itself a moment after you set the phone down.",
            "Fixes updating: 1.75 and 1.76 were built with the wrong signing key, so Android refused them as updates (\"App not installed\") and Ultimate didn't offer them. This version is signed with the right key again.",
        ), tries = listOf("air")),
        Entry("1.76.0", listOf(
            "Your senses now work together: a new intent engine combines what each one notices, so two half-sure senses that agree make a sure decision, and one that's guessing alone doesn't act.",
            "Air mode with your watch on: the watch feels the tiny tap of thumb meeting finger and confirms the pinch, so clicks land even when the camera can barely see your fingers (far away, at an angle, in dim light). Arm swings and walking don't count; the bar adapts to how much your wrist is moving.",
            "Talk to Ultimate: tap the mic in the search bar and say what you want (\"turn on the flashlight\"). A confident spoken command runs right away, since you said it.",
            "After a command runs, a button appears underneath to undo or flip it (\"Turn off\" after the flashlight, \"Undo\" after a volume change), plus \"Keep\" to leave it on Home as a one-tap control. If you do neither, it fades away after a few seconds. Long-press a kept control to remove it.",
            "Files has a front page: Downloads, Docs, Images, Audio, Videos and Install files one tap each, your recent files as pictures, and how full your storage is, with every folder still one tap away.",
            "Updates found more reliably: if a new version is still downloading when Ultimate notices it, it looks again a few seconds later, and it also checks Downloads every 15 minutes, so the \"Update ready\" notification doesn't get missed.",
        ), tries = listOf("air")),
        Entry("1.75.0", listOf(
            "Air mode teaches you first: a one-minute practice with the real cursor (find your hand, point at a circle, pinch three circles, drag a dot) before the first start, and \"Practise again\" any time.",
            "No more surprise Home or Back: hand-shape shortcuts are off unless you turn them on, and then the shape must be held still for a full second while a ring fills on the cursor, labelled with what it will do. Moving your hand cancels it.",
            "Air mode learns your hand as you use it: every shortcut you hold all the way teaches a small on-phone neural network what you mean, and every hold you abandon (or plain pointing around) teaches it what you don't. Shapes it's sure you mean need less holding, down to a third of a second; shapes it's sure you don't mean never start a ring.",
            "While you get used to it, a tip next to the cursor says what to do next (\"Pinch thumb and finger to tap\"), and goes away after a few taps.",
        ), tries = listOf("air")),
        Entry("1.74.0", listOf(
            "Air mode: control any app with your hand in the air. A cursor follows your index finger; pinch to tap, pinch and move to scroll, hold the pinch to long-press; open palm goes Back, a fist goes Home, a peace sign shows recent apps.",
            "Start it from Air control: turn on \"Ultimate Air mode\" in Accessibility once (it taps and scrolls for you and reads nothing on screen), then Start. It keeps working in other apps until you tap Stop, there or in its notification.",
            "The cursor holds steady while you aim and moves fast when you do: smoothed with the 1€ filter, and the middle of the camera's view covers the whole screen.",
        ), tries = listOf("air")),
        Entry("1.73.0", listOf(
            "Gesture Studio: every sense on one screen, each with a live line: the phone's proximity, light and motion sensors, sound (claps, snaps, knocks), your watch's motion and Echo's distance.",
            "Teach once, every sense learns: name a gesture (or pick one, like \"Wave over phone\" or \"Knock twice\"), do it five times when asked, and all your senses learn it together. You then see how well each sense alone recognises it, and the combined model recognises it live.",
            "Gestures you teach can start Rules (\"When I do …\").",
        ), tries = listOf("gesture-studio")),
        Entry("1.72.0", listOf(
            "Health: your steps, sleep (with stages), heart rate, resting heart rate, heart rate variability, workouts, blood oxygen, breathing rate, weight and body fat in one place, read from Health Connect, where Samsung Health puts what your watch measures. You choose each kind; it's read-only and stays on the phone.",
            "Patterns between your health and your phone use, like less sleep after late-night scrolling or fewer steps on heavy phone days. They're shown only with at least 10 days of data and a clear link, and each says it's a pattern, not proof.",
            "Live heart rate from your watch: turn on \"Share heart rate\" on the watch's status page (watch app 1.3.0, sent from Watch → Setup).",
            "The Watch screens are one scroll again, on the phone and on the watch, with heart rate sharing and the version added to the watch's list; the live graph is smoother (twice the updates, drawn every frame).",
            "\"Update it\" no longer shows a connection error when the watch picked a new port; it just asks for the new port, with no new pairing code.",
        ), tries = listOf("health", "watch")),
        Entry("1.71.0", listOf(
            "Watch updates from the phone: Watch → Setup shows the watch app's version and sends an update over the watch link, where you tap Install on the watch. No wireless debugging, from watch app 1.2.0 on (this one update still goes the old way).",
            "The Watch screen is tidier: the link, the on/off switch and the last gesture stay at the top, with tabs for Gestures, Live, Calibrate and Setup.",
            "The watch app has three pages you swipe between: gestures with a big readout, a remote with big buttons, and status.",
        ), tries = listOf("watch")),
        Entry("1.70.0", listOf(
            "Watch → Live from your watch: see the watch's sensors on the phone as you move, how fast your wrist turns about each axis and how hard it jolts, with every recognised gesture marked.",
            "Calibrate gestures to your wrist: do each flick and the twist three times, and the phone works out which way each one turns on your watch and how hard you flick, then sends it to the watch. This fixes a flick up reading as a flick down, which happens because a flick's rebound can be stronger than the flick itself.",
            "Flicks no longer read as a shake: a shake now needs harder jolts and doesn't count while the wrist is turning. Update the watch app too (Watch → Update it).",
        ), tries = listOf("watch")),
        Entry("1.69.2", listOf(
            "Install on my watch pairs on Samsung phones: their built-in security library lacks a step the pairing needs (\"NoSuchMethodException … exportKeyingMaterial\"), so Ultimate+ now brings its own.",
            "Typing the watch's address is easier: your network's start (like 192.168.1.) is filled in, and if something's missing the box says what, instead of only turning red.",
        ), tries = listOf("watch")),
        Entry("1.69.1", listOf(
            "Install on my watch works when the watch can't be found by itself (some routers block that): type the IP address & port and the code the watch shows. If the watch doesn't announce its connection port either, Ultimate+ asks for the one number from the Wireless debugging screen, and remembers it for next time.",
        ), tries = listOf("watch")),
        Entry("1.69.0", listOf(
            "Install on my watch: Ultimate+ now carries the watch app and installs it itself, over Wi-Fi. Turn on Wireless debugging on the watch, tap \"Pair new device\", and type the 6-digit code into Ultimate+ → Watch. No computer, no other app; it finds the watch by itself. Next time, \"Update it\" skips the code.",
        ), tries = listOf("watch")),
        Entry("1.68.0", listOf(
            "Watch: control the phone from a Galaxy Watch (or any Wear OS watch). Flick your wrist left or right, flick up or down, twist or shake, or tap the watch's big buttons, from across the house: the watch reaches the phone over Bluetooth, or Wi-Fi when it's further away.",
            "Nothing happens until you turn on \"Watch controls the phone\", and you choose what each gesture does. Watch gestures can start Rules too. The watch app is a separate install; the Watch tool shows how, step by step.",
        ), tries = listOf("watch")),
        Entry("1.67.0", listOf(
            "New things find you: What's new has a button to try each new feature, Home shows \"New for you\" with one tap each, and new tools wear a New badge until you open them.",
            "Echo shows \"Teach Echo your gestures\" right at the top, and Teach Echo ends with \"Make a gesture do something\", which takes you to Rules, so each step leads to the next.",
        ), tries = listOf("echo-teach", "echo")),
        Entry("1.66.0", listOf(
            "Echo learns as you use Air control: its sonar listens alongside the camera, and every hand gesture the camera sees becomes another example, so gestures with the camera off keep getting better. You can turn \"Echo learns from this\" off.",
            "A small neural network, trained on the phone from your own examples, takes over from example-matching once there are enough examples and it scores better on examples held back from training. Teach Echo shows both scores.",
            "Echo now also listens to the Doppler shift of your hand, the sound spilling just outside its chirp as you move, which helps it tell swipes apart. Gestures taught in 1.65 need teaching once more, because the examples now hold more.",
        ), tries = listOf("air", "echo-teach")),
        Entry("1.65.0", listOf(
            "Teach Echo: show the camera a few gestures (swipes, push, pull, hold) and Echo learns what each one sounds like, so it recognises them later with the camera off. A guided session takes about a minute, with a dot for every example the camera confirms.",
            "After teaching you see how well Echo tells your gestures apart, scored honestly from your own examples, then try them with the camera off. Learned gestures can start Rules.",
            "Air control links to Teach Echo, and Teach Echo sits with Echo and Air control under Senses on Home.",
        ), tries = listOf("echo-teach")),
        Entry("1.64.0", listOf(
            "Air control opens watching only: you see the tracking and what each gesture would do, and nothing happens on the phone until you turn on \"Gestures do things\". It turns itself off again when you leave.",
            "Air control can watch your face: eyebrows up or down, smile, mouth open, pucker, puffed cheeks, wink and blink, with live bars measured against your own resting face. Each one can be given an action, or used in Rules.",
            "Home is calmer: the big orb section at the top is replaced by compact tiles of your tools (pins first, then what you use around this time).",
        ), tries = listOf("air")),
        Entry("1.63.0", listOf(
            "One app: the separate development versions are combined. Home opens on a living surface with your pinned and usual tools, and the orb inside every tool opens the same Jump panel.",
            "Home reminds you of jobs that failed (\"Needs another look\"), and Echo, Air control, the camera calibration and Ruler sit together under Senses.",
            "Ultimate+: Octopus Mesh shares tasks between your own trusted devices on the same Wi-Fi, with no server. It is off until you pair.",
            "Terminal: Paste and Enter keys, a separate row of arrow keys, and a button that brings the keyboard back.",
        ), tries = listOf("mesh", "terminal")),
        Entry("1.62.2", listOf(
            "Air control and camera calibration really start now: the gesture library couldn't find its own logging code in the release build (\"ExceptionInInitializerError\") and gave up.",
            "Privacy: the gesture library can report anonymous usage statistics to Google; that is now removed from the app, so nothing about your camera use leaves the phone.",
        )),
        Entry("1.62.1", listOf(
            "Air control and camera calibration start properly: their hand-tracking models were packed compressed in the app, which MediaPipe can't read, so it said hand tracking wasn't available and showed no camera.",
            "The camera picture now shows even if gesture tracking fails, with the reason on screen.",
        )),
        Entry("1.62.0", listOf(
            "Air control: prop the phone up and control it with your hand from across the room (about 2-3 m). Swipe left or right to change tracks, thumbs up or down for volume, open palm to pause, pinch for the flashlight; push, pull, hold, fist, peace sign, pointing up and I-love-you are there too.",
            "Tap any gesture to choose what it does. Every gesture is also a trigger for Rules, like \"When I give a thumbs up to the camera, …\".",
            "Live view: your hand's skeleton, a trail behind your palm and an air pointer on your fingertip, with a pop and a buzz for each gesture. Everything runs on the phone; frames are never saved.",
        )),
        Entry("1.61.0", listOf(
            "Echo camera calibration: the front camera finds your hand (21 points, on the phone) and estimates its distance from your palm's size, while Echo measures the same hand by sound. 20 seconds of moving your hand gives a correction fitted to hundreds of matched readings, with how closely they then agree.",
            "Open it from Echo → Distance → Calibrate with the camera. Set your palm's width once (measure it with the Ruler) for exact distances. Frames are looked at and dropped; nothing is saved.",
        )),
        Entry("1.60.0", listOf(
            "Search everything from Home, like a command palette: apps to launch, Ultimate's tools, contacts, notifications, things you copied, files, Android settings, what you watched and played, Echo's readings and your rules, in one list.",
            "The best match sits on top; everything else is grouped with a badge, so a contact, a message and an app with the same name are told apart at a glance.",
            "Contacts are searched once you allow it, from \"Search your contacts too\".",
            "Echo detects your hand again: 1.59's voice filter mistook a real hand (which moves its arm and stirs nearby reflections) for noise. It now judges other sound by the far distances only, where a voice shows and a hand doesn't, and no longer learns from bursts of noise.",
            "The Echo test's buttons sit above the navigation bar on the cover screen.",
        )),
        Entry("1.59.0", listOf(
            "Echo no longer mistakes your voice for your hand: talking, music or a hiss reaches Echo's sound band, but it changes every distance at once, so it is now recognised and ignored (and Echo says so).",
            "Stop always stops: the button follows what Echo is really doing, and starting again waits for the microphone to be free.",
            "Long-press the app icon on your home screen for Echo, Ruler, Record and Clean up.",
            "New home screen widget: Echo, Ruler, Record and the flashlight in one tap, with the last thing your phone sensed.",
            "The Echo test's buttons and the ruler no longer hide behind the navigation bar.",
        )),
        Entry("1.58.0", listOf(
            "Echo calibration: after the Echo test, one tap fits a correction to the heights you held, so distances read true on your phone. It tells you how close the correction gets.",
            "Hold your hand over the bottom edge, where the speaker and microphone are, with your arm coming in from the side. Over the top of the screen, Echo measured a slanted path and often your forearm instead of your hand.",
        )),
        Entry("1.57.0", listOf(
            "Ruler: a true-size ruler on your screen in centimetres and inches, checked once against a bank card so it's exact on your phone.",
            "The Echo test tells you how to measure real heights: measure a mug or a stack of books with the Ruler, stand it beside the phone and hold your palm level with its top. The ruler opens from the test.",
        )),
        Entry("1.56.0", listOf(
            "Local AI: Needle, a small AI model by Cactus, now runs on your phone. Type on Home, like \"turn the volume down to 20\" or \"free up some space\", and it suggests the action; tap to do it. Nothing you type leaves the phone.",
            "Turn it on in About and settings → Local AI (a one-time 35 MB download in Ultimate+; Ultimate imports the file).",
            "Typing \"when …\" on Home offers to make it a rule.",
        )),
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
