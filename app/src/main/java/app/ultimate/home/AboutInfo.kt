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
