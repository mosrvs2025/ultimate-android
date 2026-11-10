# Ultimate: current state

> Written against real source, builds and measurements, not from memory. Where something could not be verified without a
> physical device, it says so. Read this first; `README.md` describes the product, `docs/ARCHITECTURE.md` the original
> module design, and `CHANGELOG.md` every version.

## 1. Identity

| | |
| --- | --- |
| Version | **1.98.0**, build (versionCode) **115** (`app/build.gradle.kts`); 1.89.0 (build 103) was the version synced to `main` by PR #24 |
| Watch app | **1.6.0**, versionCode **7** (`wear/build.gradle.kts`); it also ships inside the phone app, which installs it on the watch |
| Source commit of this version | `76b5f27e68cb7c079f86087862b3ea84013e3dd2` (branch `claude/spatial`, pull request #24); the code is unchanged since. This file and the release tooling were added on top, and `main` contains all of it after the merge of #24. `git log -1 --format=%H -- app/build.gradle.kts` on `main` shows the exact commit |
| Package | `app.ultimate.plus` (Ultimate+), `app.ultimate` (Ultimate, offline); other editions below |
| SDK | minSdk 29 (Android 10), targetSdk 35, compileSdk 35 |
| Signing certificate (SHA-256, public) | `d2143ab20357ef4678fe3e4f87197860b3e6121fc1342591993bf7b8086bfd7d` |
| Tools in Ultimate+ | **38** (measured, see section 4) |
| Tools in Ultimate (offline) | **31** (measured) |
| Language and size | Kotlin and Compose, about 58,000 lines in `app/` and `wear/`, plus Java (SSH) and a small C bridge (`app/src/main/cpp/needle_jni.c`) |

How this state was reached: `main` had been left at v1.86 (`9667a2f`, PR #23) while versions 1.87 to 1.89 lived on
`claude/spatial` in a draft PR (#24). Nothing was lost or duplicated: `main` held only two merge commits that
`claude/spatial` did not, and they add no content. Every other development branch was already contained in `main`.

## 2. Check it yourself

```
git log --oneline -3 main
grep -nE 'versionName|versionCode' app/build.gradle.kts wear/build.gradle.kts
gradle :app:testUltimateplusDebugUnitTest --tests '*ToolInventoryTest'   # writes app/build/tool-inventory-ultimateplus.txt
```

Needs `scripts/fetch-models.sh` first (hand and face models and the Local AI engine are binaries and are never committed).

## 3. Editions

One code base builds seven apps (`productFlavors` in `app/build.gradle.kts`); `BuildConfig.HAS_*` flags decide which tools
and permissions each gets.

| Edition | Package | What it is |
| --- | --- | --- |
| **Ultimate+** (`ultimateplus`) | `app.ultimate.plus` | Everything, including SSH client and server, send to computer, network tools, the watch link, Octopus Mesh. Has the internet permission |
| **Ultimate** (`ultimate`) | `app.ultimate` | The same tools without those, and **no internet permission** (checked in CI). Local AI imports its model from a file instead of downloading |
| Studio, Traffic Inspector, Squeeze, Video to audio, Pluck | `app.ultimate.studio`, `.traffic`, `app.squeeze`, `.audio`, `app.pluck` | Single-purpose apps from the same code |
| Wear OS watch app (`wear` module) | `app.ultimate.plus` | Installed on the watch; same package and key as Ultimate+ |

## 4. Tool inventory (Ultimate+, 38 tools, measured)

Produced by `ToolInventoryTest`, which lists `UltimateApp.tools` per edition (`app/build/tool-inventory-<edition>.txt`).
Descriptions are each tool's own tagline.

| # | id | Tool | In | What it says it does |
| --- | --- | --- | --- | --- |
| 1 | `watch` | Watch | Ultimate+ only | Control the phone from your watch: flick, twist and shake, or tap its buttons |
| 2 | `mesh` | Octopus Mesh | Ultimate+ only | Your trusted devices share work, no server |
| 3 | `ssh` | SSH and SFTP | Ultimate+ only | Approved computers only |
| 4 | `hosts` | SSH hosts | Ultimate+ only | Connect to your servers |
| 5 | `checkup` | Phone checkup | both | Security, privacy and what your apps can reach |
| 6 | `studio` | Studio | both | Turn your clips and music into a film. Offline. |
| 7 | `recorder` | Recorder | both | Record voice or music, even with the screen off |
| 8 | `photos` | Photos | both | Resize, crop and remove location from photos |
| 9 | `prompter` | Teleprompter | both | Read your script while you film |
| 10 | `recipes` | Recipes | both | Chain tools together and automate them |
| 11 | `squeeze` | Squeeze | both | Shrink videos to a fraction of the size |
| 12 | `audio` | Video to audio | both | Video to MP3, plus audio cutting, converting, speed and joining |
| 13 | `pluck` | Pluck | both | Extract any app's APK and see what's inside |
| 14 | `sound-meter` | Sound meter | both | See your sound. Save and compare your space. |
| 15 | `echo` | Echo | both | Feel movement near the phone, with sound you can't hear |
| 16 | `ruler` | Ruler | both | A true-size ruler on your screen |
| 17 | `air` | Air control | both | Control your phone with hand gestures, from across the room |
| 18 | `spatial` | Spatial | both | Your watch's distance, Wi-Fi map, and the places you're in |
| 19 | `playground` | Playground | both | Play a piano on your desk, draw in the air, put stickers on your face |
| 20 | `echo-camera` | Echo camera calibration | both | Let the camera teach Echo how far your hand really is |
| 21 | `echo-teach` | Teach Echo | both | Show the camera a few gestures; Echo learns them by sound, for use with the camera off |
| 22 | `traffic` | Traffic inspector | Ultimate+ only | See who your apps talk to, live |
| 23 | `network` | Network scanner | Ultimate+ only | Every device on your Wi-Fi and what it exposes |
| 24 | `send` | Send to computer | Ultimate+ only | Files to and from any computer on your Wi-Fi |
| 25 | `files` | Files | both | Browse, organise and install from your storage |
| 26 | `inbox` | Notification history | both | Search every notification, even unsent ones |
| 27 | `clips` | Clipboard | both | Everything you copied, kept and searchable |
| 28 | `cleanup` | Clean up | both | Swipe, de-duplicate and free space |
| 29 | `storage` | Storage | both | See exactly what fills your phone |
| 30 | `terminal` | Terminal | both | Run commands on your phone |
| 31 | `orb` | Floating orb | both | Ultimate on top of every app |
| 32 | `rules` | Rules | both | When this happens, do that |
| 33 | `focus` | Focus | both | A little companion that keeps you on task |
| 34 | `timeline` | You | both | How you're doing: body, mind, days and what your phone knows |
| 35 | `health` | Health | both | Steps, sleep, heart and body from your watch, and how they relate to your phone use |
| 36 | `gesture-studio` | Gesture Studio | both | Every sense on one screen: teach a gesture once and the phone, watch and Echo all learn it |
| 37 | `screentime` | Screen time | both | Where your time goes, with gentle limits |
| 38 | `sensors` | Sensors | both | Every sensor in your phone, live |

Not tools but part of the product: the Home screen (search bar, body pulse, live cards, tools), About and settings, the
Library and Activity tabs, the Open-with player (`PlayerActivity`, section 6.11), and background services.

## 5. Architecture

- **A tool** implements `core/Tool.kt` (`id`, `title`, `tagline`, `icon`, `headline`, `library`, a Compose `Screen`). All
  tools are listed in `UltimateApp.tools` and gated by `BuildConfig.HAS_*`. Tools in the `vision` and `ultimateplus` source
  sets are created by reflection (`Class.forName`) so smaller editions compile without them.
- **Source sets**: `main` (shared), `vision` (MediaPipe camera tools; Ultimate and Ultimate+), `ultimateplus` (SSH, mesh,
  watch link, checkup), one folder per single-purpose edition.
- **The senses bus**: `core/Senses.kt`. Anything that senses publishes `Signal(source, kind, label)`; its key is
  `source:kind`. `UltimateApp` forwards every signal to the Rules engine as `Event.Sensed`. Sources in use: `echo`,
  `watch`, `air`, `studio`, `body`, `spatial`.
- **Work and notifications**: `core/Work.kt` (`WorkHub`, `WorkService`) runs long jobs with progress and a notification.
- **Storage**: SharedPreferences, small JSON files and SQLite (notification log). Nothing is uploaded. Local-first is a
  design rule, and each opt-in sensor has a Stop.
- **Pure logic is separated and tested**: the maths for each subsystem lives in plain Kotlin files with no Android
  dependencies (`SpatialMath.kt`, `AirPointer.kt`, `IntentEngine.kt`, `MindCheck.kt`, ...), covered by unit tests.

## 6. Subsystems

### 6.1 Sensor fusion (what is and is not fused)
Fusion is done **per feature**, not in one global model:
- `air/IntentEngine.kt`: `IntentEngine` combines evidence from several senses as independent witnesses (noisy-OR with a
  fading window). `PinchFusion` uses it so the camera's thumb-to-finger gap plus a tap felt by the watch (`WristTap`)
  close a pinch the camera alone would not. Used by `AirModeService`.
- `spatial/SpatialMath.kt`: `SpatialModel` keeps each spatial sense's value, confidence and age, fades stale senses and
  drops them after 15 s; overall confidence is noisy-OR. Fed by `SpatialEngine` (Wi-Fi, watch beacon, place, motion).
- `senselab/SenseHub.kt` (Gesture Studio): puts phone proximity, light, gyroscope, linear acceleration, microphone
  loudness, the watch's motion and Echo's range on one timeline; `StudioMath` learns gestures across them.
- Echo with the camera: `echo/CameraRange.kt` and `vision/HandCalibrationTool.kt` pair the camera's distance (from palm
  size) with Echo's raw sonar range to fit a correction (`EchoCalibration`).
- **Not fused**: Echo, the camera and the watch's motion are not fed into `SpatialModel`; there is no single position
  estimate of you or your devices.

### 6.2 Echo (sonar)
`echo/`: `EchoEngine` (Motion mode: a steady near-ultrasonic tone, movement from the Doppler shift; `EchoDetector`,
`EchoMath`), `SonarEngine` and `Sonar.kt` (Distance mode: chirps, FFT, range and bearing; `SonarTracker`), `EchoCalibration`
and `CameraRange` (camera-assisted calibration), `LearnedGestures.kt` and `NeuralGestures.kt` (`SonarFeatures`,
`GestureModel` k-nearest, `TinyNet` a small neural network, `GestureBrain` which uses the network only when it beats example
matching on held-back examples; trained on the phone from examples the camera labels), `VoiceGate` (ignores your voice on
phones whose microphone is always processed), `EchoService` (foreground service), `EchoBench` (hardware probe).
UI: Reach, Echogram, Space, Spectrum tabs; "Camera calibration" (`vision/HandCalibrationTool`) and "Teach Echo"
(`vision/TeachEchoTool`) are separate tools. Research notes: `docs/ECHO_RESEARCH.md`.

### 6.3 Air control (camera)
`vision/AirTool.kt` (screen: Hand, Face and Both modes, the gesture-to-action list, practice, watch strip, Record for
training), `HolisticCamera.kt` (one camera running the gesture recogniser and the face landmarker together; dark-room
detection and ring light), `AirMode.kt` (`AirModeService`: a foreground camera service that turns your hand into a
cursor in any app) with `AirCursorService.kt` (accessibility overlay that draws the cursor and performs taps, drags and
Back/Home/Recents; reads no screen content). Pure logic in `air/`: `AirPointer` (One-Euro smoothing, pinch with
hysteresis, tap/long-press/drag, drift rewind), `ShortcutHold` (hand-shape shortcuts need a still hold with a ring),
`ShapeLearner` (a `TinyNet` that learns your confirmed and abandoned holds), `FingerShapes` (per-finger shapes),
`FaceExpressions`, `AirGestures`/`AirActions`/`PhoneActions` (swipes, push/pull, signs and what they trigger),
`AirCommands` (the Ask pill's spoken commands), `PhoneContext` (pauses Air mode while the phone is held), `SenseRecorder`
(opt-in recording of hand points, pinch, sign, watch motion and events for training; no images; 50 MB cap; zip export).
MediaPipe models (`gesture_recognizer.task`, `face_landmarker.task`, `hand_landmarker.task`) are fetched at build time.
**Playground** (`vision/PlaygroundTool.kt`): desk piano (`DeskPiano`: fingertip strikes need a lift then a downward tap;
finger pads or keyboard), air draw (`AirCanvas`), face fun (stickers and paint kept in face coordinates, `FaceFrame`),
hands-free calling by face (`FaceNavigator`: eyebrows, eye-look, head pitch, mouth/tongue, with live meters;
`TongueDetector` reads the picture below the lower lip because the face tracker has no tongue).

### 6.4 Watch integration
Watch app (`wear/`): `MainActivity` (Wear Compose; toggles for gestures, Share heart rate, Proximity beacon, wrist),
`PhoneLink` (Wearable MessageClient to the phone), `WristGestures` (flick, twist, shake from gyroscope and linear
acceleration; refractory time and rebound absorption), `HeartService` (foreground service: a measurement burst about every
5 minutes, closely only while heart rate is 110 or more), `BeaconService` (BLE advertisement carrying only Ultimate's
UUID), `WatchUpdate` (receives an updated watch APK from the phone).
Phone (`ultimateplus/.../watch/`): `WatchLink` (receives messages; publishes `watch:*` signals; debounces flick
rebounds), `WatchTool`, `WatchSensors` (live graph, pause and scrub, Pattern Studio), `WatchInstaller` (pairs and installs
the watch app over wireless debugging with an embedded `libadb`), `WatchUpdater` (updates it over the Data Layer).
Shared in `main/.../air/`: `WatchFeed` (live motion for other tools), `WatchMessages`, `WristCalibration` (learns which
axis a flick turns about on this watch), `WatchPatterns` (teach-your-own motion patterns).

### 6.5 Intent engine
See 6.1: `air/IntentEngine.kt` (`IntentEngine`, `WristTap`, `PinchFusion`). It is the only place senses vote on one
decision today (the pinch).

### 6.6 Rules
`rules/`: `RuleModel` (`TriggerType`: Charging, Wifi, Headphones, BatteryBelow, AtTime, Notification, Copied, Sensed;
`SenseNames` lists the pickable signals), `RuleParser` (plain sentences such as "when my heart rate stays high, turn on Do
Not Disturb"; body, Echo, watch and spatial phrases), `RuleEngine`, `RulesService`/`LiveTriggers` (live triggers),
`RulesTool` (UI, history). Sensed triggers match `source:kind` signals from the senses bus.

### 6.7 Local AI
`ai/`: `LocalAi` (Cactus Needle 3 running on the phone through `NeedleNative` and `app/src/main/cpp/needle_jni.c`; the
35 MB model is downloaded once in Ultimate+ or imported in Ultimate), `AiTools` (11 actions and the JSON parsing, minimum
confidence 0.5), `AiActions` (carries them out, with Undo for volume and brightness), `PhoneCommands` (brightness, volume
and flashlight are read directly, without the model, and a model answer that does not fit the sentence is dropped).
The engine is a binary fetched by `scripts/fetch-needle.sh` at a pinned revision with SHA-256 checks; **without it the
build still succeeds but Local AI is a stub**, which is why the release workflow now checks the finished APKs. Notes:
`docs/LOCAL_AI.md`. It only proposes; you confirm (spoken commands with 80% confidence or more run directly).

### 6.8 Orb
`orb/OrbService.kt` (overlay service: the floating orb, bloom of actions, peek notes, drop target, glance badge),
`OrbMath.kt`, `OrbTool`. Spatial can open and close it when your watch comes close to or moves away from the phone
(`OrbService.BLOOM`/`CLOSE`, opt-in). Design notes: `docs/LIVING_SURFACE.md`.

### 6.9 Spatial
`spatial/`: `SpatialEngine` (Wi-Fi RSSI of the connected network and a scan list, the watch's BLE beacon through a service
UUID scan, steps and heading, capability detection for BLE, UWB, Wi-Fi RTT, Bluetooth channel sounding),
`SpatialMath.kt` (`RssiFilter` Kalman filter, `ProximityZones` Near/Mid/Far/Gone with hysteresis and dwell and a two-step
calibration, `StepPath`, `Heatmap`, `Fingerprints` for place recognition, `SpatialModel`), `SpatialService` ("Keep sensing"
foreground service with Stop), `SpatialTool` (dashboard: fused senses with confidence, watch rings, Wi-Fi, walked signal
map, places, capabilities). Output is zones and similarities, never metres.

### 6.10 Contextual intelligence already implemented
- **You** (`timeline/YouTool.kt`): Today (`health/YouToday.kt`: mood check-in `MoodLog`, an explainable day read
  `MindCheck`, body, phone life, money and health facts read from notifications `NotificationFacts`, learned
  patterns), Days (search every record), Patterns and Privacy (`footprint/`), "About me" for any AI.
- **Health** (`health/`): Health Connect reader, `HealthInsights` (correlations between health data and phone use),
  `HeartWatch` (high/calm heart-rate moments published as `body:*` signals), `LiveHeart`.
- **Home** (`home/`): Body pulse strip, time-of-day tool ranking (`HomeLayout`), insight cards.
- **Notifications and clipboard**: logs with fate and sender analysis (`inbox/`, `insights/`), `Focus`, `Screen time`.
- **Search** (`search/`): command palette across apps, people, messages, files and tools; verbs like "play" and "open"
  are understood; "YouTube notifications" opens Notification history already searching.
- **Learning on the phone**: `ShapeLearner` (your hand shapes), `GestureBrain` (Echo gestures), `WatchPatterns` (your
  motion patterns), `PhoneContext`, `VoiceGate`. Nothing leaves the phone.

### 6.11 Player
`player/PlayerActivity.kt` and `PlaybackService.kt` (Media3 session): Ultimate+ appears under "Open with" for audio,
video and pictures; album art, seek, skip, folder queue, repeat, speed, sleep timer, background playback.

## 7. Release and CI

- `.github/workflows/android.yml` (tests, lint and minified release builds on pushes to `main` and pull requests),
  `terminal-smoke.yml` (emulator test on pull requests), `ultimate-companion.yml` (separate companion preview build),
  `release.yml` (**Release APKs**, run manually from Actions).
- `release.yml` fetches the models and engine, runs every edition's unit tests, builds and signs every edition and the
  watch APK with the permanent key from the `ULTIMATE_KEYSTORE_BASE64` and `ULTIMATE_KEYSTORE_PASSWORD` repository
  secrets (alias `ultimate`; secrets never appear in logs or files), writes `SHA256SUMS.txt`, and publishes release
  `v<versionName>` with notes taken from the in-app changelog.
- Guards (new): refuses to publish when a newer release already exists, or when the version was already released from a
  different commit; refuses to publish unless every APK carries the signing certificate in section 1, the right
  versionName and versionCode, the three MediaPipe models and a real (not stub) Local AI engine, and unless the offline
  edition has no INTERNET permission.
- Updating over an installed build only works with the same signing key. A build signed with another key installs fresh
  but cannot update (this happened to two local test builds in this project's history; they were replaced).

## 8. Known limitations, bugs and unfinished work

Verified by tests (pure logic) versus **not verifiable without hardware**:
- **Not validated on real hardware by automated tests** and reported only anecdotally: Spatial's BLE beacon on a Galaxy
  Watch Ultra (SM-L705U) and its zones, Wi-Fi scanning limits (Android throttles scans to a few per minute), the walked
  map's drift, the watch heart-rate bursts and the beacon service on Wear OS, Air mode and Playground tracking quality,
  Echo accuracy on different phones (many filter ultrasound), the new media player's folder queue.
- **Hardware limits**: Bluetooth channel sounding (true distance) needs Android 16 on phone and watch and supporting
  chips; the build SDK is 35, so the app only detects it. The Galaxy Watch Ultra has no UWB. Wi-Fi RTT needs 802.11mc
  routers. Wear OS 5 and Health Services give no live HRV or SpO2 to this app yet.
- **Not built**: fusing the watch's motion, the camera and Echo into `SpatialModel`; pinch and fist-clench detection on the
  watch; wrist-rotation menu navigation; combined hand-plus-watch gestures; counting taught watch patterns all day (they
  are matched only while the Watch screen streams); automatic anomaly bookmarks; routing Echo's sound through the
  earpiece; a camera view inside Echo; 3D piano keyboard and AR pinning (needs ARCore); a larger "Smarter Local AI" model
  (Gemma or Qwen); deep links from History records into the exact YouTube video; two-hand tracking.
- **Known behaviour to be aware of**: Local AI is a small model and only proposes; brightness needs the "Modify system
  settings" permission once. A phone call from the Call tab needs the call permission (otherwise the dialer opens). On
  devices without telephony the Call tab is hidden. Tablet layouts exist for Home, Air control and Files only.
- **Build environment**: the build is memory hungry (lint across seven editions); on small machines run Gradle tasks one at
  a time (`--max-workers=2`). Robolectric prints a harmless `DirectoryNotEmptyException` at test-JVM exit. The emulator
  workflow occasionally fails while downloading the NDK on GitHub's runners; re-run it.
- **Git-ignored binaries**: `app/src/vision/assets/*.task` and `app/src/main/cpp/needle/` are fetched by
  `scripts/fetch-models.sh`. A checkout without them builds a degraded app silently; CI and the release workflow now
  check for that.

## 9. Branches and pull requests

All 26 other development branches are fully contained in `main` or in `claude/spatial` (zero commits of their own).
They are kept, not deleted, and can be pruned. The pull requests #1 to #23 are merged. Open at the time of writing: #24
(the sync, merged as part of this work).

| Branch | Status |
| --- | --- |
| `main` | The reference. After this sync: v1.89.0 build 103 |
| `claude/spatial` | v1.89.0 source (PR #24), merged into `main` |
| `claude/*` (all others), `ccr-*`, `integration/consolidate`, `codex/living-command-surface`, `codex/terminal-navigation-fix` | Already in `main`; nothing to integrate |
| `codex/ultimate-ssh` | **Not merged, deliberately.** An older stand-alone import with its own root commit (no merge base with `main`); every file it has already exists in `main`, which has since moved far beyond it (the SSH client, terminal and orb were carried into `main`). Merging would only add unrelated history |

## 10. Tests performed

Run on the code of v1.89.0 plus the release-tooling commit (the documentation commits after it change no code), on Linux
with JDK 17 and Robolectric (no device):

| Check | Result |
| --- | --- |
| Unit tests, Ultimate+ (`testUltimateplusDebugUnitTest`) | 600 run, 0 failures, 0 errors, 11 skipped |
| Unit tests, Ultimate (`testUltimateDebugUnitTest`) | 536 run, 0 failures, 0 errors, 0 skipped |
| Unit tests, Squeeze (`testSqueezeDebugUnitTest`) | 536 run, 0 failures, 0 errors, 4 skipped |
| Unit tests, watch (`wear`, `testReleaseUnitTest`) | 9 run, 0 failures |
| Tool inventory (`ToolInventoryTest`) | Ultimate+ 38 tools, Ultimate 31, unique ids, minimum 30 enforced for Ultimate+ |
| Android lint, every edition | passed (no errors) |
| `assembleRelease`, phone editions and watch | built, minified |
| Signature | every APK carries the certificate in section 1 (`apksigner verify --print-certs`) |
| Completeness | the three MediaPipe `.task` models and the real Local AI engine are inside Ultimate+ and Ultimate; Ultimate has no INTERNET permission |

The single-purpose editions other than Squeeze run the same shared tests in GitHub's CI (`android.yml`). Skipped tests are
tests marked ignored in the source (hardware-dependent). The same checks are repeated by `release.yml` before anything is
published.

Cannot be run here: anything needing the phone's camera, microphone, speaker, radios or the watch (see section 8). The
emulator smoke test runs in GitHub Actions on pull requests.

## 11. Next recommended improvements

1. Validate Spatial on the real phone and Watch Ultra and tune `ProximityZones` from recordings (Record for training exists).
2. Feed the watch's motion, the camera hand pose and Echo into `SpatialModel`, and put the intent engine in front of
   more decisions than the pinch.
3. Watch-side matching of taught patterns so they count all day, then anomaly bookmarks.
4. A larger optional Local AI model, keeping direct `PhoneCommands` for device controls.
5. Train a first personal gesture model from recordings; compare it with the current small networks on held-back data.
6. Move the signing identity checks into a reusable script so local builds and CI share them.
7. Prune merged branches; add repository topics and a short description so other tools find this file.
