# Echo: spatial perception with a phone's speaker and microphones

**Question:** can an ordinary phone gain real spatial awareness from sound, and which techniques add genuinely new
spatial information rather than repackaging motion detection?

This document covers:
1. what was tried;
2. what was measured, and how;
3. what failed or was ruled out;
4. what to run next.

It separates numbers measured in synthetic experiments (reproducible in CI) from numbers measured on a phone (produced
by the in-app bench, still to be collected).

## 1. Baseline (kept working)

**Motion mode** (`EchoEngine`, `EchoMath`) plays a continuous 18.75 kHz tone and compares the energy just above and
below it (Goertzel filters at ±47–188 Hz).

- A reflector moving toward the phone raises the pitch of its echo; one moving away lowers it.
- It publishes `echo:approach` and `echo:recede`.
- It is unchanged in this work, and `EchoTest` still covers it.

**What it gives:** direction of radial motion.
**What it doesn't give:** distance, position or bearing. A still hand is invisible to it.

## 2. Signal designs compared

| Design | What it measures | New spatial information? | Status |
|---|---|---|---|
| Continuous tone, Doppler | Radial velocity sign and rough magnitude | No: motion only | Baseline, kept |
| **Repeating chirp + matched filter** (FMCW-style; see CAT, FingerIO, LLAP) | **Time of flight, so distance** to each reflector | **Yes: distance** | **Built: Distance mode** |
| Complex (phase) background subtraction on the chirp profile | Sub-millimetre changes at each distance | Sensitivity, not a new dimension | Built: inside the tracker |
| Two microphones, time difference of arrival | Left/right of the reflector | Yes, coarsely, only where two distinct mics are accessible | Built: lean or angle |
| Several simultaneous tones (OFDM-style) | Phase per tone, so finer distance tracking | Same dimension as the chirp, finer | Not built: next experiment |
| Phone swept by hand (synthetic aperture) with the gyroscope | Bearing by moving one mic | Possibly | Not built: see §6 |

### Chirp parameters (`Sonar.kt`)

- **The chirp:** a linear sweep from 17 to 20 kHz.
  - It lasts 10 ms (480 samples) with a Tukey taper.
  - The taper removes the audible clicks a hard-edged chirp makes.
- **Repetition:** once every 2048 samples (42.7 ms).
  - This sets the update rate: about 23 measurements a second.
  - It also sets the unambiguous range, 7.3 m.
- **Range resolution:** c / 2B = 343 / 6000 ≈ 5.7 cm.
- **Distance per sample of delay:** 3.57 mm.
  - Sub-sample peak interpolation gives finer precision than this.
  - Precision is not the same as resolution: two hands closer together than 5.7 cm still merge into one peak.
- **The correlation:** computed in the frequency domain, with negative frequencies removed.
  - The result is an analytic (complex) echo profile.
  - Its magnitude has no carrier ripple; its phase moves with tiny motion.
- **Zero distance:** the playback-to-capture latency of Android audio is unknown and varies by phone.
  - Distances are therefore measured from the direct-path peak (speaker to mic through the body and air).
  - This is the standard approach, and it makes the method independent of latency.
- **Separating motion from the room:**
  - The still scene (table, the phone's own body, walls) is learned as a slow complex average.
  - The target is the strongest change against that average, from 3 cm to 1 m.
  - Confidence is that change divided by the median change; 4 or more counts as a detection.
- **Velocity:** taken from the slope of the range track, ignoring jumps that are physically impossible.

## 3. Results

### 3a. Synthetic experiments

These are reproducible: `SonarTest`, run in CI. The output is written to `app/build/echo-experiment.txt`.

The simulated room has:
- a direct path at an arbitrary latency;
- a table echo at 20 cm (amplitude 0.25);
- a hand echo of amplitude 0.04;
- Gaussian noise.

**Ranging a hand at 30 cm, 10 trials per noise level:**

| Noise σ | Ranged within 2 cm | Mean error |
|---|---|---|
| 0.002 | 10/10 | 3.9 mm |
| 0.010 | 10/10 | 4.6 mm |
| 0.030 | 10/10 | 6.1 mm |
| 0.060 | 4/10 | 9.0 mm |
| 0.100 | 0/10 | — |

- **Finding:** the matched filter's processing gain (480-sample chirp, about 27 dB) keeps ranging reliable until the
  per-sample noise is close to the hand echo's amplitude.
- **Breakdown:** between σ = 0.03 and 0.06.

**Resolution:**

| Separation | Result |
|---|---|
| 8 cm | Two peaks |
| 4 cm | One peak |

- **Finding:** this matches the 5.7 cm theory.
- **Consequence:** fingers on one hand will not separate. A hand and a forearm, or two hands, can.

**Other checks that pass:**
- hands at 8, 15, 30 and 50 cm are ranged within 2 cm;
- a still room reports no target;
- a hand moving from 40 to 20 cm reads negative velocity;
- ranging works when the direct path sits at the wrap-around of the period.

### 3b. On a phone (to collect)

The in-app **Bench** (Echo → Distance → Run the bench) measures four things:

| Measure | How |
|---|---|
| False alarms | Fraction of frames with a detection in a still room |
| Accuracy | Median reading and spread with the hand at 10, 20 and 30 cm |
| Latency | Median and slowest processing time per frame, on top of the 42.7 ms sound period |
| Battery | Current drawn while idle against while sensing, where the phone reports it |

- Its report is copyable, so results from different phones and changes can be compared.
#### Bench 1: Samsung Galaxy SM-F956U1, 1.53.0 (first tracker)

**Results:**
- **Link:** direct path 1723× above the noise. The mic hears the chirp perfectly.
- **False detections in a still room:** 100% of frames.
- **Hand at 10 / 20 / 30 cm:** read 4.4 / 6.1 / 5.1 cm. It is always the same few centimetres, whatever the hand does.
- **Processing:** median 1.6 ms per frame, slowest 3.9 ms.
- **Battery:** no measurable difference (−12 mA, within fluctuation).
- **Hardware the phone reported:**
  - four built-in mics: one at the bottom, three at the top;
  - top and bottom 14.3 cm apart;
  - no unprocessed source;
  - near-ultrasound supported on both the speaker and the mic.
- **Only one channel arrived.** The stereo request didn't yield two distinct mics.

**Diagnosis:**
- The speaker and microphone clocks are not locked. The direct path, about 1700× stronger than any echo, shifts by
  fractions of a sample and changes gain slightly from frame to frame.
- After subtracting the still scene, that wobble leaves a large residue just past zero distance, larger than a hand's
  whole echo.
- The tracker therefore always "found" a target at 3–6 cm. The synthetic tests had no such jitter, so they missed it.

**Fix (1.53.1):**
1. **Lock each frame to the direct path.** Divide the complex profile by the direct path's complex value, which
   cancels the common drift in phase and gain.
2. **Use a per-bin constant false-alarm rate.**
   - Each distance bin learns its own usual change.
   - A detection needs 5× that usual change.
   - The jittery bins near the direct path are therefore held to a much higher bar.
3. **Change the stereo request.** It now tries the camcorder source first, which on Samsung usually records the top
   and bottom mics separately.
4. **Widen the direction search** to cover the 14.3 cm spacing (up to ±21 samples).

**New test (`SonarRealismTest`):**
- It reproduces the phone's failure, which in turn validates the simulation: the direct path jitters by ±0.35 samples
  and ±3% in gain.
- Before the fix: 100/100 false alarms, and the hand read 2.9 cm when held at 10 cm.
- After the fix:
  - at most 5 false alarms in 100 frames;
  - the hand at 10, 20 and 30 cm is ranged within 2.5 cm, from an echo just 1% of the direct path.

#### Bench 2: same phone, 1.53.1

**Results:**
- **Link:** 542× above the noise.
- **False detections:** 0%, down from 100%.
- **Hand at 10 / 20 / 30 cm:** read 20.5 / 15.7 / 20.2 cm, detected in 73% / 100% / 8% of frames.
- **Battery:** +252 mA while sensing. That is much higher than bench 1, possibly from screen brightness or the
  speaker's volume. To recheck.

**Diagnosis:**
- The jitter fix worked.
- The remaining error looks like target choice. A hand above the phone comes with an arm behind it, also moving and
  often echoing more strongly, and the tracker reported the strongest return.
- At 30 cm the hand's echo is about 9× weaker than at 10 cm, so it rarely cleared the bar.

**Fix (1.54.0):**
- Report the nearest clear return: the first local peak at least 40% as strong as the best.
- `SonarRealismTest.theNearestMovingThingWinsOverAStrongerArmBehindIt` covers it. Before the fix it reported the arm
  at 32 cm instead of the hand at 15 cm.

**New tool: session logs.**
- They record every frame's numbers with markers the user taps.
- The point is to replay real sessions through the tracker here. That is more precise than a video.

#### Bench 3: same phone, 1.54.0

**Results:**
- **False detections:** 0%.
- **Hand at 10 / 20 / 30 cm:** read 6.5 / 3.9 / 3.9 cm. At 20 and 30 cm the spread was exactly ±0.0 cm.
- **Battery:** +34 mA.

**Diagnosis:**
- 3.9 cm was exactly the edge of the search window: the strongest change sat against the edge, not at a peak.
- A hand over the phone also changes how strongly the speaker reaches the mic.
- Locking each frame to the direct path then rescaled every still echo by a different factor from the direct path's
  own far-reaching ripples. This left a smear that "nearest wins" (added in 1.54.0) always picked.
- Reproduced in `SonarRealismTest.aHandThatDisturbsTheDirectPathIsStillRangedNotTheEdge`. Before the fix, a hand at
  12 cm read 4.6 cm.

**Fix (1.55.0): model the frame instead of normalising it.** Each frame is fitted as two parts, by complex least
squares:
1. **The direct path:** its exact shape (the chirp's autocorrelation), placed at the measured sub-sample delay, with
   its own strength.
2. **The still room:** the learned echoes, with their own overall strength.

Only what neither part explains counts as motion. On top of that:
- The search starts at 7 cm, the resolution limit beside the direct path.
- Only true peaks count, never the window's edge.
- A nearer peak wins only if its raw change is at least 40% of the strongest's. The per-bin noise scaling otherwise
  flattered faint ripples sitting in quiet bins.
- Debug profile from the test (hand at 20 cm): the hand is now the dominant peak (score ~110). Before, the leakage
  near the phone scored 110 and the hand 50–70.

**Known limit, now tested and documented:**
- A hand within ~5 cm of a strong still echo (say, a box at 20 cm) merges with it. That's the 5.7 cm resolution limit.

**New in 1.55.0:**
- Gestures (`EchoGestures`), as signals rules can use:
  - **push:** at least 6 cm toward the phone within 0.5 s;
  - **pull:** the same, away from the phone;
  - **hover:** held within 3 cm for 1.2 s.
- A Reach view showing where the hand is and how far Echo has detected so far this session.

## 4. What the hardware allows

Android reports the following, and the in-app panel shows them labelled REPORTED:

- `PROPERTY_SUPPORT_SPEAKER/MIC_NEAR_ULTRASOUND`.
  - Many phones return nothing; that is not the same as "no".
- Whether the `UNPROCESSED` microphone source exists.
  - Echo prefers it, because voice processing in other sources can strip high frequencies.
- The built-in microphones, and their positions when stated (`AudioManager.getMicrophones()`).
  - Most phones don't state positions.
  - Without positions, direction can only be a sign (a lean), not an angle.
- **Stereo capture.**
  - Echo asks for it, and many phones grant it.
  - Some fill both channels from the same mic.
  - Echo detects identical channels and then reports one microphone rather than inventing a direction.
- **Gyroscope.**
  - Used to gate readings. Above about 17°/s of rotation, every echo shifts at once, so readings pause and the
    screen says so.
  - This is honest motion gating, not motion compensation (see §6).

## 5. Ruled out, and why

- **Acoustic images of hands or objects.**
  - Angular resolution needs an aperture many wavelengths wide.
  - At 18.5 kHz the wavelength is about 1.85 cm, and a phone's mics are 1–15 cm apart, with only two usually
    accessible.
  - Beamforming on two mics 1.5 cm apart resolves about λ/D ≈ 1.2 rad (70°). That is a lean, not a picture.
  - Any "shape" drawn from this data would be invented.
  - **The interface never draws one.** On one mic, the space map shows the target as an arc across every direction it
    could be in.
- **Absolute distance without the direct path.**
  - Android gives no reliable output-to-input timestamp alignment for this.
  - Relative timing against the direct path is the robust approach.
- **Running Doppler and ranging at once on overlapping bands.**
  - They interfere.
  - Distance mode instead derives velocity from the range track, so one signal serves both.
- **Biometric anything.** Out of scope by design. Echo senses reflectors, not people.

## 6. Next experiments, in order of expected value

1. **Phase tracking at the target's distance bin** (LLAP-style).
   - The profile is already complex.
   - Unwrapping the phase at the target bin gives millimetre-scale displacement: λ/2 ≈ 9 mm per 2π.
   - This could enable finger-scale gestures such as a "push" or a "tap in the air".
2. **Multi-tone (OFDM) probing** for robustness against speaker frequency-response nulls.
3. **Gyroscope-assisted synthetic aperture.**
   - Sweep the phone slowly in an arc. The gyroscope gives the mic's path, which yields a larger virtual aperture.
   - This is the only route to more than a lean on a single-mic phone.
   - It depends on the audio-to-sensor timestamp alignment, which must be measured first.
4. **Two paired phones as a wide-baseline array**, through Mesh.
   - With explicit pairing, two phones a metre apart form an aperture large enough to localise a reflector in 2D.
   - It requires clock-offset estimation from each phone's chirp heard by the other, which needs no network timing.
5. **Camera fusion**, opt-in.
   - Overlay the measured distance and lean on the camera view, to show visually where Echo's estimate lands.
   - Clearly labelled; the camera never fills in geometry the sound didn't measure.

## 7. Interface rules

Every view carries a tag:

| Tag | Meaning | Where it's used |
|---|---|---|
| **MEASURED** | Direct output of a measurement | Echogram, spectrum, distance |
| **ESTIMATE** | Derived with assumptions | Speed, direction |
| **ILLUSTRATION** | Driven by data but not a measurement | Motion mode's rings |
| **REPORTED** | What Android says about the hardware | Hardware panel |

Echo runs only after you press Start. It shows a notification with Stop and appears under Live now on Home. It
records and keeps no audio.
