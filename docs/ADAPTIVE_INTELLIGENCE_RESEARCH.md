# Adaptive intelligence: what comes after the toolbox

Research and one experiment, written against the source at `claude/spatial` (Ultimate+ 1.89.0, build 103; `main` is
being synced to it by PR #24). Every statement about the code was checked in the source. Statements about research
literature are from memory: titles, authors and years are given so they can be looked up, but verify them before
citing formally. Claims about hardware that I could not test are labelled **unverified**.

## 0. The finding in one paragraph

Ultimate has more senses than almost any phone app (camera hands and face, sonar, watch motion and heart rate, BLE
and Wi-Fi zones, a learned-gesture stack, a Local AI, Rules, an orb) but they meet in only two places: the Rules engine
(discrete events: "when X happens, do Y") and the pinch decision in `IntentEngine`. There is **no continuous model of the
situation**, **no memory of what you did in which situation**, and **no feedback from "you undid that"**. The bus is a
doorbell, not a nervous system. The next generation is therefore not another sensor or another tool; it is the thin layer
that turns the senses into a *situation*, learns *your* habits in it, offers the right verb for the object in your hand,
and learns from being corrected, while staying silent unless it is sure. This report maps that layer, lists twenty-one
concepts, rejects the weak ones, and implements and measures the first step (`context/HabitModel.kt`).

## 1. Research concepts that matter here

| Idea | Source (verify before citing) | What it means for Ultimate |
| --- | --- | --- |
| Ubiquitous computing, calm technology | Weiser, "The Computer for the 21st Century" (1991); Weiser & Brown, "Designing Calm Technology" (1995) | Information should live in the periphery and move to the centre only when needed. The orb and glance badge are already peripheral; the missing part is knowing *when* to step forward |
| Context-aware computing | Schilit, Adams & Want (1994); Dey & Abowd, "Towards a Better Understanding of Context and Context-Awareness" (1999) | Context = anything characterising the situation of a user or entity. Ultimate publishes events but not a situation |
| Mixed-initiative interaction | Horvitz, "Principles of Mixed-Initiative User Interfaces" (CHI 1999; the Lumiere project) | Act only when expected utility beats the cost of a wrong guess; always allow cheap dismissal; learn from dismissal. Rules are user-initiated only; nothing is mixed-initiative yet |
| Interruptibility and receptivity | Horvitz et al., BusyBody (CSCW 2004); Pielot et al., in-situ notification study (MobileHCI 2014); Mehrotra et al., receptivity to notifications (CHI 2016) | Deliver at breakpoints (stopped walking, picked up the phone), not at random moments. Ultimate has the signals (watch motion, heart rate, hand near) and does not use them for this |
| Adaptive UIs and their danger | Sears & Shneiderman, split menus (1994); Findlater & McGrenere, adaptive menus (CHI 2004); Gajos & Weld, SUPPLE (2004) | Adaptation helps only if it is predictable and does not move things under the finger. `HomeLayout.constellation` already freezes the order for a visit, for this reason |
| Ability-based design | Wobbrock et al. (TACCESS 2011) | Adapt the system to the user's current ability, not the reverse. Relevant to Air, face navigation and one-handed use |
| Multimodal fusion, "put that there" | Bolt (SIGGRAPH 1980); Oviatt on multimodal interfaces | Speech plus pointing resolves "that" and "there". Ultimate has voice commands (`AirCommands`, `LocalAi`), a pointer (`AirPointer`) and a tap witness (`WristTap`) but never combines them in one sentence |
| Proxemic interaction | Greenberg, Marquardt et al., "Proxemic Interactions: The New Ubicomp?" (2011) | Distance, orientation and movement between people and devices as input. Echo measures hand distance; the Spatial module measures watch distance. Nothing uses them as an interface |
| Active acoustic sensing | Nandakumar et al., FingerIO (CHI 2016) and ApneaApp (MobiSys 2015); Tung & Shin, EchoTag (MobiSys 2015); Gupta et al., SoundWave (CHI 2012); Google Soli, Lien et al. (SIGGRAPH 2016) | A phone speaker and microphone can sense motion, finger-scale movement, breathing and the *place* a phone lies. Echo is a research-grade subset of this |
| Wrist sensing | Laput & Harrison, ViBand (UIST 2016) and fine-grained hand activity from smartwatches (CHI 2019) | A watch's inertial sensor reads taps, hand activity and what you are holding. `WristTap` is the first slice |
| Shake-to-pair co-location | Mayrhofer & Gellersen, "Shake well before use" (2007) | Two devices shaken together prove they are in one hand with no setup. Phone IMU and watch IMU are both available |
| Predicting pointing targets | Murata (1998); Lank et al. endpoint prediction (2007) | The end of a movement can be predicted from its first half. Air pointing is noisy and could use it |
| Just-in-time adaptive interventions | Nahum-Shani et al. (2018) | Intervene when state and need coincide; otherwise do nothing. Same logic as a "calm" suggestion gate |
| Human-computer symbiosis | Licklider (1960); Clark & Chalmers, extended mind (1998) | A tool becomes part of thinking when it is reliable, fast and predictable. Surprise breaks that |
| Dynamic difficulty | Hunicke (2005) | Games adapt invisibly to the player. The lesson for UI: adapt the *effort* (targets, thresholds), not the layout |

## 2. What Ultimate already has (verified in source)

Paths are under `app/src/<set>/java/app/ultimate/`. "Verified" means it exists and has unit tests; hardware behaviour is
a separate question (section 7).

| Capability | Where | State |
| --- | --- | --- |
| Shared bus of discrete signals, live sessions with Stop | `main/core/Senses.kt` | Verified. Publishers: Echo, watch, Air, Gesture Studio, Spatial, body (heart, mood). Consumers that act: Rules, the quick-actions widget, search ("Sensed" results). Nothing else reads it |
| Evidence fusion, noisy-OR with fading | `main/air/IntentEngine.kt` (`IntentEngine`, `PinchFusion`, `WristTap`) | Verified, used **only** for the pinch |
| Per-feature fusion | `main/spatial/SpatialMath.kt` (`SpatialModel`: value, confidence, age; `RssiFilter`, `ProximityZones`, `StepPath`, `Heatmap`, `Fingerprints`); `main/senselab/SenseHub.kt` (nine streams on one timeline) | Verified (logic); radios **unverified** on real hardware |
| Sonar, motion and distance | `main/echo/` (`EchoEngine`, `SonarEngine`, `EchoCalibration`, `CameraRange`, `VoiceGate`, `EchoBench`) | Verified (logic); accuracy varies by phone |
| Learned gestures from sonar / watch / hand shapes | `main/echo/NeuralGestures.kt` (`TinyNet`, `GestureBrain`), `main/air/ShapeLearner.kt`, `main/air/WatchPatterns.kt`, `main/senselab/` | Verified on synthetic data; tiny networks only when they beat example matching |
| Camera hand and face control | `vision/AirTool.kt`, `AirMode.kt`, `AirCursorService.kt`, `main/air/AirPointer.kt`, `FaceExpressions.kt`, `FingerShapes.kt`, `Playground.kt` (`FaceNavigator`, `DeskPiano`) | Verified (logic) |
| Watch link: motion, heart, beacon, patterns | `wear/`, `ultimateplus/watch/WatchLink.kt`, `main/air/WatchFeed.kt`, `WristCalibration.kt` | Verified (logic); watch behaviour **unverified** |
| Body and day understanding | `main/health/` (`HeartWatch`, `YouToday`, `MindCheck`, `NotificationFacts`, `HealthInsights`), `main/footprint/`, `main/timeline/` | Verified |
| Rules incl. sensed triggers | `main/rules/` (`RuleModel`, `RuleParser`, `RuleEngine`, `RulesService`/`LiveTriggers`) | Verified |
| Local AI: 11 actions, direct phone commands | `main/ai/` (`LocalAi`, `AiTools`, `PhoneCommands`) | Verified; model quality limited (small) |
| Orb, glance badge, jump, home ranking | `main/orb/OrbService.kt`, `OrbMath.kt`, `main/home/HomeLayout.kt` (`constellation`: time of day, 48 h), `main/home/JumpPanel.kt` | Verified |
| Usage memory | `main/insights/InsightsStore.kt` (`recentOpens`: tool id and time only) | Verified. No situation is stored with an open |
| "Hand it anything" hub, share targets, Library, clipboard history | `main/home/HandSheet.kt`, `main/clips/`, `main/home/LibraryScreen.kt` | Verified |

**Underused combinations visible in the code**

1. Signals are consumed one at a time. No code asks "what is true *now*", for example "held, walking, watch near, evening".
2. `InsightsStore.recentOpens()` knows what you open and when, and the senses know what is happening; nothing joins them.
3. Rules can fire on `echo:approach` but a hand getting closer is a *continuous* quantity thrown away as an event.
4. `IntentEngine` is generic ("intent", "source", "confidence") yet only the pinch uses it.
5. `AirCommands` (spoken commands) and `AirPointer` (a cursor) never share a sentence.
6. The watch can buzz but only ever buzzes for its own features; haptics are an unused output channel.
7. Undo exists for Rules actions and Local AI actions, but an undo is never counted as a signal about the trigger.
8. `NotificationFacts` and the notification log classify what arrives, but delivery timing never looks at the body.

## 3. Twenty-one concepts

Each: the idea; what exists to build on. Verdicts come from section 4. K = keep, M = merge into another, R = reject.

1. **Situation layer** (K). A small, continuously updated state ("held / lying on a surface / pocket", "still / walking",
   "at a known place", "watch near / far / gone", "heart calm / high", "charging", "headphones", "part of day") published
   as *state* the whole app can read, with a confidence. Builds on `Senses`, `SpatialModel`, `HeartWatch`, rules' live triggers.
2. **Habit compass** (K, **implemented**). Learn "in a situation like this I open that", explain why, stay silent unless
   sure; order Home, the orb and Jump by it. Builds on `InsightsStore`, `HomeLayout`. See section 6.
3. **Quorum confirmation** (K). Replace "Are you sure?" dialogs with agreement between independent senses: a delete
   proceeds on a deliberate pinch seen by the camera *and* felt by the watch, or a held hand-shape *and* a tap. Generalises
   `PinchFusion`/`IntentEngine`. Dangerous actions get a stricter quorum than harmless ones.
4. **Receptive moments** (K). Hold non-urgent notifications and release them as a digest at a breakpoint: walking just
   stopped, the phone was picked up, the heart rate calmed. Uses `NotificationFacts` urgency, watch motion, `HeartWatch`.
5. **Noun-first orb** (K). The orb offers *verbs for the thing in hand*: after you film a 1.2 GB video, "Squeeze"; after
   you copy a link, "Send to computer"; after a photo, "Clean up similar". Context is the object, not the app. Uses clipboard
   history, Library, the Hand-it-anything hub.
6. **Peripersonal orb** (K, experimental). The orb's size and openness follow the distance of your hand from the phone, as
   in peripersonal-space research: nothing when far, a hint at 40 cm, open at 15 cm. Continuous Echo distance rather than an
   event. Uses `SonarEngine`, `OrbService`.
7. **Shake-well-before-use trust** (K). Phone and watch moving in correlated rhythm for one second prove they are in one
   hand; use it as a *step-up convenience* before revealing the SSH vault or sending a file, never as a security boundary.
   Uses `WatchFeed` and the phone IMU.
8. **Haptic glance** (K). Tap the watch twice and it answers in rhythm: the number of items that really matter, as short
   buzzes, so you do not take out the phone. Output channel that exists nowhere in the app today. Uses the watch app,
   `NotificationFacts`.
9. **Acoustic placement sensing** (K, research). A short inaudible chirp and its echo tell whether the phone is on a hard
   table, in a pocket, in a bag, or in a hand (EchoTag-style fingerprints), more robustly than proximity plus light. Feeds
   concept 1. Uses `EchoBench`, `SonarEngine`.
10. **Two-sonar desk touchpad** (R for now). Phone and tablet each measure hand range; the intersection locates the hand
    on the desk. Sound clocks and interference make it a lab project.
11. **Bedside breathing and light-sleep alarm** (K, research). Echo's chest-motion sensing at under a metre plus watch
    motion to wake at a light moment. Builds on `EchoEngine`, `HeartWatch`. Many phones filter ultrasound; health claims
    must be avoided.
12. **Posture and gait morphing** (M into 1). When the phone is walked with, switch to glance mode (big targets, short
    text, voice); one-handed grip moves frequent controls into thumb reach. Needs only step detector and touch history; the
    state is part of the situation layer, the morph a UI rule.
13. **Outcome ledger and veto learning** (K). Count every automated action as success, undone within N seconds, or ignored,
    and feed the result back to thresholds and to the habit model as negative evidence with a scope ("not when charging").
    Turns one-off undo into learning.
14. **Silent prefetch** (K). When the habit model is confident, warm what the likely tool will need (Local AI engine load,
    storage scan cache, Library thumbnails) with no UI. A wrong guess costs a little battery, not trust. The lowest-risk way
    to use prediction.
15. **Blind controls** (K, research). A small learned vocabulary of pocket gestures (taps on the back, covering the
    proximity sensor in a rhythm, wrist flicks) mapped to orb actions, with haptic replies. Uses Gesture Studio, `WristTap`,
    `ShapeLearner`-style learning.
16. **Echo hover** (R for now). A hand approaching the screen shows what a button would do (S Pen "air view" for fingers).
    Echo's near-field resolution is too coarse and the camera too hungry. Reconsider after calibration data exists.
17. **Walk-away sealing** (M into 1). When the watch leaves the zone and Echo sees nothing for two minutes, lock the SSH vault
    and hide notification text. Already expressible as a Rule (`spatial:watch_gone`), so it is configuration of existing
    parts, not a new idea.
18. **Rooms as modes** (R). Named rooms from `Fingerprints` selecting interface presets. Rules plus places already do this.
19. **One-frame proxemics** (R). Sample one front-camera frame at unlock to scale text to viewing distance. The camera
    indicator on every unlock and the trust cost outweigh the benefit.
20. **Magnetic air cursor** (K, research). Predict the target of an Air Mode movement from its first half and let it pull
    toward the nearest control. Needs the accessibility tree's control bounds, which `AirCursorService` deliberately does not
    read; a privacy decision before an engineering one.
21. **Put that there** (K). One utterance, resolved by every sense: "send *that* to *him*" with *that* = the object under
    the pointer, the last selection or the last copied item, confirmed by the watch tap. Uses `AirCommands`, `LocalAi`
    (`AiTools`), `AirPointer`, `IntentEngine`.

## 4. Challenging the ideas

Scores 0 to 3. Value, feasibility on actual Android hardware, reuse of existing code, cost (battery and performance;
3 = cheap), privacy (3 = nothing new leaves or is sensed), reliability (3 = few false positives). "AI needed?" asks
whether anything beyond counts, thresholds and filters is required.

| # | Idea | Value | Feas. | Reuse | Cost | Privacy | Reliab. | AI needed? | Better than a plain UI? | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Situation layer | 3 | 3 | 3 | 3 | 3 | 2 | No (rules and filters) | Enables everything else | **Keep (foundation)** |
| 2 | Habit compass | 3 | 3 | 3 | 3 | 3 | 2 | No, counts beat a network at this data size | Yes, saves taps; risk: layout churn | **Keep, implemented** |
| 3 | Quorum confirmation | 2 | 2 | 3 | 2 | 3 | 2 | No | Yes for hands-free; no for touch | **Keep** |
| 4 | Receptive moments | 3 | 2 | 2 | 3 | 3 | 1 | No | Yes if it never delays anything urgent | **Keep, strict exemptions** |
| 5 | Noun-first orb | 3 | 3 | 3 | 3 | 3 | 2 | Little (rules per object type) | Yes: removes find-the-tool steps | **Keep** |
| 6 | Peripersonal orb | 2 | 1 | 2 | 1 | 2 | 1 | No | Novel; unclear it beats a tap | Keep as experiment |
| 7 | Motion-sync trust | 2 | 2 | 2 | 2 | 3 | 2 | No (correlation) | Yes: removes a prompt | **Keep** |
| 8 | Haptic glance | 2 | 3 | 2 | 3 | 3 | 2 | No | Yes: no screen needed | **Keep** |
| 9 | Acoustic placement | 3 | 1 | 2 | 2 | 2 | 1 | Small classifier | Beats proximity+light if it works | Keep as research |
| 10 | Two-sonar touchpad | 1 | 0 | 1 | 1 | 3 | 0 | No | No | **Reject** |
| 11 | Bedside breathing | 2 | 1 | 2 | 1 | 1 | 1 | No | Contactless is the point | Keep as research, no health claims |
| 12 | Posture and gait morph | 2 | 2 | 1 | 3 | 3 | 2 | No | Yes if it never moves controls mid-task | Merge into 1 |
| 13 | Outcome ledger | 3 | 3 | 2 | 3 | 3 | 3 | No | Makes every other idea safer | **Keep** |
| 14 | Silent prefetch | 2 | 3 | 2 | 2 | 3 | 3 | No | Yes: faster, invisible | **Keep** |
| 15 | Blind controls | 2 | 1 | 2 | 2 | 3 | 1 | Small networks exist | Yes eyes-free; false triggers in a pocket are the risk | Keep as research |
| 16 | Echo hover | 1 | 0 | 1 | 1 | 3 | 0 | No | Not proven | **Reject** for now |
| 17 | Walk-away sealing | 2 | 3 | 3 | 3 | 3 | 2 | No | Already a Rule | Merge (config) |
| 18 | Rooms as modes | 1 | 3 | 3 | 3 | 3 | 2 | No | Repackaging | **Reject** |
| 19 | One-frame proxemics | 1 | 2 | 1 | 1 | 0 | 2 | No | Camera flash every unlock | **Reject** |
| 20 | Magnetic air cursor | 2 | 1 | 1 | 2 | 1 | 2 | No | Large win for Air accuracy; privacy cost | Keep as research, privacy-gated |
| 21 | Put that there | 3 | 2 | 3 | 2 | 3 | 2 | Local AI for parsing only | Yes hands-free; no for touch | **Keep** |

**Combinations.** 1 + 12 + 17 + 9 become the situation layer (one state, many contributors). 2 + 13 + 14 become the
learning loop (predict, act silently, learn from correction). 3 + 21 become one multimodal fusion feature. 4 + 8 become a
digest that can be pulled by tapping the watch. 5 sits on top of the learning loop.

**Where AI is not needed** (and this matters): 1, 2, 3, 4, 7, 8, 13, 14. The honest summary: the next step needs filters,
counts and thresholds, applied across senses. The Local AI is useful only where language is the input (concept 21).

## 5. The five strongest opportunities

1. **Situation layer plus habit compass.** Everything else reads from it. Evidence: a measured benchmark (section 6) and
   code that already exists on both sides of the join.
2. **Put that there / quorum confirmation.** `IntentEngine` is a ready-made, tested fusion core that only one feature uses.
   It makes hands-free control precise, which is the main weakness of Air mode.
3. **Receptive moments with haptic glance.** Notification overload is the most common complaint about phones; Ultimate has
   a watch, heart rate and a classified notification log that no other app combines.
4. **Noun-first orb.** It changes the unit of interaction from the app to the object. It needs no new sensor and could
   remove the most taps.
5. **Outcome ledger.** Not glamorous, but it is what makes every automatic behaviour trustworthy: if the user undoes it,
   it learns. Without it each new automatic feature adds risk.

Acoustic placement sensing is the most "only Ultimate could do this" idea, but it has the lowest certainty, so it stays in
research.

## 6. Prototype designs and the implemented experiment

### Prototype A: Habit compass (the implemented experiment)

*Demo.* Home's top tiles and the orb's bloom reorder by the situation. Long-press any tile shows "Why here: charging, evening,
after Storage". A "Not now" swipe records a veto. Off by default; "Forget my habits" wipes it.
*Built.* `context/HabitModel.kt` (model, explanations, persistence, silent-unless-sure gate) with `HabitModelTest`.
*Not built* (deliberately, to leave UI and data collection unchanged until you decide): reading the situation from Android
state (charging, headphones, Wi-Fi, watch zone), recording opens with their situation, and the Home/orb wiring.

**What it is.** Naive Bayes over coarse words (`part`, `days`, `charging`, `headphones`, `wifi`, `watch`, `prev`) with a
21-day half-life, additive smoothing, at most 1,500 stored observations, JSON persistence. It returns a ranking with
reasons; `suggest()` returns a single tool only if the weighted evidence is at least 5 opens, probability at least 0.45
and 1.5 times the runner-up, otherwise null.

**Measured** (`HabitModelTest.benchmarkAgainstWhatHomeDoesToday`, JVM unit test, report written to
`app/build/habit-benchmark.txt`). Synthetic users, 70 days, ten opens per day over twelve tools, online evaluation: each
open is predicted *before* the model learns from it, after a 14-day warm-up (560 scored opens). The comparison baseline is
the real `HomeLayout.constellation` (time of day, last 48 hours).

| User | Method | Top-1 | Top-3 |
| --- | --- | --- | --- |
| Routine user (routines in headphones/charging/part of day/previous tool, plus 25% random opens) | Most opened overall | 18.0% | 49.5% |
| | **Home today** | 25.2% | 48.4% |
| | **Habit model** | **59.8%** | **68.4%** |
| Random user (no structure; chance is 8.3% / 25%) | Most opened overall | 7.1% | 25.0% |
| | Home today | 8.2% | 24.6% |
| | Habit model | 9.5% | 25.9% |

For the routine user the silent suggestion fired on 72.9% of opens and was right 76.0% of the time it fired. For the random
user it fired on 0.0% of opens (so it stays quiet when there is nothing to learn, which is the calm-technology requirement).

**What this does and does not show.** The routine user is *invented by me* with exactly the kind of structure the model
reads, so the numbers prove the model learns routines quickly and does not hallucinate habits in noise. They do **not** prove
real people have such routines or that this beats Home on a real phone. The 72.9% fire rate is too high for a real
interface; a rate limit and a per-tool cooldown belong in the UI layer. The first honest measurement is on real opens, which
needs the collection step above and your consent.

Tests: 5 passing (benchmark with assertions, explanation and silence, fading of old habits, save/restore/forget, coarse
words only). Run alone with
`gradle :app:testUltimateplusDebugUnitTest --tests '*HabitModelTest'`. The full suite and lint were not re-run on this branch;
the change adds one source file and one test file and touches nothing else, so it cannot change any existing behaviour, but it
has not been through the release gate and is **not merged or published**. Branch: `claude/situational-habits`.

### Prototype B: Put that there (design)

*Demo.* In Air mode say "send that to my laptop". The pointer hovers over a photo; the cursor ring thickens as evidence
accumulates (voice "that" 0.5, pointer dwell 0.5, watch tap 0.65, fused with `IntentEngine`); at 0.8 it fires, otherwise it
asks. *Reuse.* `AirCommands` for the utterance, `LocalAi`/`AiTools` for parsing, `AirPointer` for the target, `WristTap`,
`IntentEngine`. *New.* A resolver that maps "that/this/it" to the candidates (pointed object, last selection, last copy) and
reports its choice before acting. *Measure.* Resolution accuracy on recorded sessions from `SenseRecorder`; false fires per hour
with the camera idle.

### Prototype C: Receptive moments (design)

*Demo.* A "Digest" mode: non-urgent notifications collect silently; two quick taps on the watch (or the next time you stop
walking and lift the phone) shows or buzzes the digest. Exempt: calls, alarms, anything `NotificationFacts` marks urgent,
and anyone you starred. *Reuse.* The notification listener and log, `NotificationFacts`, watch motion, `HeartWatch`.
*Measure.* Notifications delayed, time delayed, any urgent item delayed at all (target zero), and how often you pulled the
digest versus it arriving at a breakpoint. The risk is silently delaying something important; the design therefore starts
as a *suggestion to batch* and shows everything it held.

## 7. Technical limitations and honest verification

- **Only the habit model is implemented.** Everything else here is design.
- **Synthetic data.** The benchmark's users are generated. No real usage was available or used.
- **No device.** Nothing on this branch was run on a phone, watch or tablet. Echo accuracy, BLE and Wi-Fi behaviour, camera
  tracking and watch sensing remain as unverified as listed in `docs/CURRENT_STATE.md` section 8.
- **Literature from memory.** Titles and years are what I recall; check before citing.
- **Hardware limits that bound these ideas.** Many phones filter ultrasound (Echo, concepts 6, 9, 11, 16); Android throttles Wi-Fi
  scans; true distance sensing (UWB, Bluetooth channel sounding) is absent on the target watch; HRV and SpO2 are not exposed
  to this app; background camera use is restricted and visibly indicated.
- **Privacy.** The situation layer and habit model store only coarse words and tool ids on the phone; they are the same kind of
  data as `recentOpens`, but joined, so the join itself (what you do where and when) is sensitive and needs its own switch,
  a "why" screen and a one-tap wipe. The acoustic and camera ideas are the most invasive and are marked research.
- **Failure modes to design for.** Layout churn (adaptation moving things under a finger), over-confidence early on, feedback
  loops (showing a tool makes you open it, which reinforces it; counter with exploration and with counting only opens that
  were not suggestions), and silent delay (concept 4).

## 8. Long-term vision: after the toolbox

A toolbox waits to be opened. The direction is a phone that keeps a quiet model of **situation, object and habit**, and
offers the next step only when that model is sure, in the least intrusive channel that works: a small shift of the orb, a
tap on the wrist, a digest at a natural pause. Tools stop being destinations and become *verbs* applied to whatever is in
hand (a video, a link, a file), chosen by what you are doing rather than by where you navigated. Control spreads across
bodies and devices: a look, a word, a pointing hand, and a tap felt at the wrist agree on one decision, so no dialog is
needed. Every automatic act is legible ("because: charging, evening"), reversible with one gesture, and every reversal
teaches it. It runs on the phone, stores coarse words, and has an off switch and a wipe for each sense.

Order of work, each step independently useful and independently releasable: (1) record opens with their situation behind
an opt-in and show "why" (Habit compass, real data); (2) the outcome ledger; (3) the situation layer as a published state
object; (4) noun-first orb verbs; (5) put-that-there on top of `IntentEngine`; (6) receptive digests with haptic glance;
(7) the research items (acoustic placement, bedside sensing, blind controls), each as a measured experiment in Echo Bench
before anything ships.

The measure of success is not how many things it can do. It is how often it does nothing, and is right when it does.
