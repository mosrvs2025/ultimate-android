# Ultimate: one app, many senses

Ultimate is one Android app made of independent modules that share a single interface. This page describes how the
modules fit together, and how the remaining modules from the unified brief will be added without breaking that.

## What exists today

Ultimate already has 30-odd modules. Each implements `core/Tool.kt` and is listed in `UltimateApp.tools`, gated by
`BuildConfig.HAS_*`, so smaller editions build from the same code. They include:

- **Ultimate Orb:** a floating overlay that shows what the app is working on, with drop targets.
- **Terminal:** a local PTY terminal plus SSH client and SFTP. Remote access is in Ultimate+ only and is opt-in.
- **Automations:** Rules (when X, do Y) and Recipes.
- **Senses that already exist:** Sensors, Sound meter, Recorder, and on-device transcription.
- **Records:** Notification history, Clipboard history, and the You timeline.
- **Media:** Squeeze, Studio, Video to audio and Photos.
- **Device:** Files, Storage, Clean up, Pluck and Checkup.
- **Network:** Send to computer, Network scanner and Traffic inspector.

The shared foundation that was already in place:

| Concern | Where |
|---|---|
| Navigation | `home/Shell` routes between Home and full-screen tools, and handles Back |
| Background work | `core/Work.kt`: every long job reports progress to Home, the orb and notifications |
| Shared results | `Tool.library`: one Library across all tools |
| Contextual actions | Share routing by MIME type, `ToolLaunch` deep links, the orb's drop targets |
| Storage | Per-module JSON files and SharedPreferences, all on the phone |
| Failure isolation | Crash catcher with in-app reports; each module's errors stay in its own state |

## New in this increment: the senses layer (`core/Senses.kt`)

Modules need to cooperate without knowing about each other. That needs two pieces:

1. **Signals.** A module publishes `Signal(source, kind, label, data)`, for example `echo:approach`. Anything can
   listen.
   - Automations listens: every signal reaches `RuleEngine.fire(Event.Sensed)`, so a rule can say "when something
     comes closer to the phone, turn on the flashlight".
   - The bus keeps the last 100 signals in memory and saves nothing.
   - It never blocks: a slow listener drops old signals rather than stalling the sensor.
2. **Sessions.** Anything that uses a sensor continuously, or reaches outside the phone, registers a session with a
   `stop` action.
   - Home shows these sessions under **Live now**, each with a Stop button, plus **Stop everything**.
   - A session whose stop throws is still removed, so the control never claims something is off when it isn't.

## Echo (`echo/`), the first new sense

Echo is sonar made from the speaker and microphone:

- It plays an 18.75 kHz tone and watches the energy just above and below it.
- Something moving toward the phone shifts its echo up in pitch (Doppler); something moving away shifts it down.
- It publishes `echo:approach` and `echo:recede`.

**Implementation:**

- `EchoMath` is pure. It uses Goertzel filters and a self-calibrating detector with a refractory period, and is
  unit-tested against synthesised echoes.
- `EchoEngine` runs the audio.
  - The tone buffer is an exact whole number of cycles, so it loops without a click.
  - It prefers the `UNPROCESSED` mic source, because the voice processing in other sources strips high frequencies.

**Privacy and battery:**

- Echo records and keeps no audio.
- It runs only after you press Start, under a microphone foreground service. That service shows a notification with
  Stop the whole time.
- When you leave the screen, it stops unless a rule is listening.

**Fallbacks:**

- If the phone reports that its speaker or mic doesn't reach ultrasound, Echo says so.
- If the mic never hears the tone (volume off, or the phone filters it), Echo stops with an explanation instead of
  silently sensing nothing.

## Rules for every module

1. **Local first.** Processing stays on the phone, and each module's data lives in its own store.
2. **Purpose-specific permissions.** A permission is requested only when the feature that needs it starts, with the
   reason shown.
3. **Visible and stoppable.** Anything continuous or remote is a session: it appears under Live now and in the
   notification shade, with Stop.
4. **Dangerous remote abilities are off by default.** The SSH server and port forwards, Send to computer, and any
   future Mesh control all need explicit enabling and pairing.
5. **Failure stays contained.** A module catches its own errors into its own state, and the bus never lets one
   module stall another.
6. **One look, distinct interactions.** Modules share the theme, the gooey motion and the orb. Each has its own
   interaction: Echo is a field of sound you move through, not a dashboard.

## Next increments (not built yet)

| Module | Plan | Talks to the bus as |
|---|---|---|
| **Vision** | CameraX analysis on demand: scene and text labels, QR codes, motion in frame. ML Kit on-device models where the edition allows. A session while the camera is open. | `vision:text`, `vision:qr`, `vision:person` |
| **Local AI** | A small on-device model (MediaPipe LLM inference or ML Kit GenAI where the phone supports it) that reads the recent signals and the You timeline and suggests or drafts rules. It never acts without a rule you approved. Falls back to the existing bring-your-own-key AI in Ultimate+. | Listens to everything; publishes `ai:suggestion` |
| **Mesh** | Explicit pairing between your own devices: QR code with a shared key, then LAN or Wi-Fi Direct. Once paired, devices exchange signals so a rule on one phone can act on a signal from another. Every paired link is a session with Stop. | Forwards the signals you choose, tagged with the device |
| **Echo, stage 2** | Gestures (wave and push patterns) and presence over time, built on the same detector. | `echo:wave`, `echo:presence` |

A new experiment needs only these steps:

1. Implement `Tool`.
2. Publish `Signal`s.
3. Register a session if it runs continuously.
4. Add its signal names to `rules/SenseNames` so people can pick them as triggers.

The rest of the app (Home, Automations, the orb and Live now) picks it up from there.

## Platform layers (2.12.0)

How the pieces added in 2.10 to 2.12 fit the design above. Nothing here replaces the senses bus; they are new modules plugged into it.

| Layer | Code | Role | Publishes |
|---|---|---|---|
| Raw record | `worldscan/WorldSensorRecorder`, `WorldExport.LogLines` | One log format for every sensor recording (World Scan walks, Movement recordings, motion received from another device) | — |
| Fusion | `fusion/` (pure Kotlin) | One clock (`Trace`, `TraceBuilder`), three independent distance witnesses, agreement and contradiction, events, calibration from verified truth, fixed-code answers | `movement:lap`, `running`, `walking`, `stopped`, `kilometre`, `disagree` |
| Spatial memory | `worldscan/` (places, multi-walk merge), `room/` (Room scan) | Persistent maps; `fusion/ScanQA` answers from their stored records | `spatial:*` (existing) |
| Devices | `mesh/` | Sealed transport, discovery (UDP, NSD, Bluetooth presence), lending a phone's sensors, distance by sound | `mesh:device_joined`, `device_back`, `device_lost`, `device_near`, `device_away` |

Rules for these layers:
1. **Raw observations are the record; reports are recomputed.** `MovementAnalyzer.ALGO` versions the analysis.
2. **Every number says how it was obtained** (`fusion/Evidence.kt`: Observed, Derived, Inferred) and carries an uncertainty.
3. **Calibration learns only from verified truth**, never from an estimate (`fusion/Calibration.kt`).
4. **Anything that reaches another device is sealed** (`mesh/MeshWire`, AES-256-GCM, key from the pairing code) and refused unless the owner is lending.
5. **Combinations come from the bus, not from modules knowing each other**: a rule can react to a lap, to a phone coming within arm's reach, or to a new device joining, because those are ordinary signals.

Known structural debt (not fixed in 2.12.0): two spatial stores (`room/` and `worldscan/`) with different map models; `context/Motion.kt` duplicates part of `fusion/Pdr` for the live "walking" UI bend; the mesh has no forward secrecy and the pairing code is the only secret.

## Related repositories (inspected for 2.12.0, kept independent)
* **ghostnode**: local Needle tool proposals with approval for every action, plus an unimplemented plan for "authenticated encrypted transport, revocation and replay tests". Ultimate's mesh now has the sealed transport and replay tests that plan describes; ghostnode's envelope idea of an explicit `expiresAt` per request is equivalent to `MeshWire.MAX_SKEW_MS`. Its rule that even read-only tool calls need a visible approval matches lending being off by default. No code was copied: its JNI/Needle integration duplicates what `ai/` already has.
* **ShatterOS**: several screens as windows onto one world, laid out by hand in millimetres. The reusable idea is the layout: the mesh can now measure the distance between two phones by sound, which could place ShatterOS viewports automatically. That is a feature for ShatterOS to consume, so nothing was merged.
* **videocompress**: a README for a Needle-driven media assistant; Ultimate's Squeeze and palette already cover it. Nothing to integrate.
