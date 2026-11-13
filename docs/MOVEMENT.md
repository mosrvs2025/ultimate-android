# Movement and sensor fusion

Status: 2.11.0. What is **tested on simulated recordings** is separated below from what **has not been tried on a real phone**. Nothing here has been run on a device yet.

## 1. What the audit found (before building)
| Area | What exists | Weakness |
|---|---|---|
| Raw recording | `worldscan/WorldSensorRecorder` writes accelerometer, gyroscope, rotation vector, pressure, GPS and camera poses to one gzip'd JSON-lines log, with both camera clocks so they can be mapped | Only World Scan used it |
| Time | `worldscan/ClockSync` maps the camera's clock onto the sensors' | Reused as is |
| Placing on Earth | `GeoRegistration`, `DriftModel` (robust fit, significance tests) | Specific to a walk's camera track; kept as the authority for map placement |
| Step / motion detectors | `context/Motion.kt` (3-class bounce test, drives UI bending), `mesh/RepCounter` (sets of reps), `health/Workouts.kt` (camera pose reps), watch gesture patterns | Four unconnected detectors, none carries uncertainty |
| Calibration | GPS drift fit; Echo's speaker calibration | No general mechanism; nothing learned from known distances |
| Spatial memory | World Scan places (multi-walk merge, undo) and a separate Room scan store | Two subsystems with different models |
| AI | `ai/` is a 35 MB tool-calling model (Cactus Needle) | It maps phrases to tool calls; it is not a question-answering model, so questions about measurements are answered by fixed code, not by it |

Consolidation done: one `Trace` reader over the existing log format, one analysis (`MovementAnalyzer`) for walks, runs and received recordings. Not yet consolidated: `MotionClassifier` still drives the live "you're walking" UI bend (it could call `Pdr.classify`); `RepCounter` and `Pdr` could share their smoothing code; the Room-scan and World Scan stores remain separate.

## 2. Design
`app.ultimate.fusion` is plain Kotlin with no Android types, so every part runs in JVM tests.

* **Evidence** (`Evidence.kt`): every number is a `Quantity` (value, 1-sigma, unit) tagged **Observed** (read from a sensor or entered by a person), **Derived** (deterministic arithmetic on observations, uncertainty modelled) or **Inferred** (a judgement, such as walking vs running). The screens show these tags; the question answers carry them.
* **Trace** (`Trace.kt`): all sensors on one clock. Camera frame times are mapped with `ClockSync`; if too little data for that, the arrival time is used and the trace says the camera is late by its pipeline latency.
* **Sources** (`Pdr.kt`, `Sources.kt`): three independent witnesses of distance.
  * *Inertial steps*: steps from the accelerometer's vertical bounce in steady bouts of 5+ with an even rhythm; step length from a cadence model times a per-person scale; heading from the gyroscope's turning about the vertical (bias taken from still stretches). Relative direction only (north needs another sensor).
  * *Camera tracking*: horizontal path from ARCore poses; blind where tracking was lost; re-found-place jumps are ignored.
  * *GPS*: a constant-velocity Kalman/RTS smoother weighted by each fix's stated accuracy; over a short span GPS reports no movement unless the person ended more than 1.2x its typical accuracy away (otherwise noise "walks").
* **Fusion** (`Fusion.kt`): 10-second epochs; each sensor reports only epochs it saw completely. Sensors are compared like for like over the epochs both saw. A pair more than 3 combined sigmas apart is a **contradiction**. With three sensors, the one the other two do not side with is the outlier and is left out of the total; with two, nothing can say who is wrong, so both stay and the uncertainty is widened to cover the gap. Epochs no trusted sensor saw are reported as **unobserved**, not filled in. A step total about double the others is flagged as a likely doubled rhythm.
* **Path and events** (`Fusion.kt FusedPath`, `Events.kt`): the path follows the best sensor each second (camera, then steps, then GPS), after fitting each onto the frame (north-up if GPS exists), adding movements so switching sensor never makes the path jump. From it: speed, stops (5 s+ under 0.4 m/s), turns (50 degrees or more within about 4 s; 150+ is a U-turn), laps (return to within a gate of the start after going 30 m away, 15 s+ apart; the gate widens when only steps drew the path), gait windows.
* **Calibration** (`Calibration.kt`): per device, motion, sensor and environment. A correction is the ratio of a **known** distance to what a sensor measured, combined by a Bayesian average that starts from "no bias, within 15%". Only distances a person measured or entered (or, weakly, long open-sky GPS) are accepted; **anything Inferred is refused**, and so is a distance under 50 m or a ratio beyond 2.5x. A correction is applied only after two runs agree it is real. Two uncertainties are kept: confidence in the average (shrinks with runs) and run-to-run spread (does not), floored at 3%. A sensor that saw under 85% of a recording is not allowed to teach.
* **Questions** (`MotionQA.kt`, `ScanQA.kt`): answers are fixed sentences filled from the measurements. No language model is involved, so a number can never be invented. `factsBlock` gives a model (the user's own, if any) a labelled list to phrase from; that is the only route to a model and nothing is sent anywhere by the app.
* **Persistence** (`movement/MovementStore.kt`): the raw log is the record. Reports are recomputed whenever a recording is opened (`MovementAnalyzer.ALGO` versions the algorithm), so improving the analysis improves old recordings. World Scan walks are read in place.

## 3. What was tested (simulation only)
`WalkSim` (test code) walks a known course and writes the log the sensors would have produced, in the real format, read back by the real reader. 21 + 4 + 4 tests check, against known truth: all sensors land on one clock; three sensors agree within their stated uncertainty and the 95% band covers the truth in at least 85% of 24 random walks; a camera scale of 1.5 is found and excluded; two disagreeing sensors widen the doubt instead of blaming one; a doubled step rhythm is recognised; lost camera tracking is reported and the other sensors carry the distance; unseen stretches are not counted; GPS jitter on a still phone does not become distance; laps, turns, running vs walking vs still; calibration learns a 12% stride error from a 400 m course in four runs (error 11% before, under 4% after), refuses inferred and too-short truth, does not trust a weak reference more, and ignores a sensor that missed a third of the walk; and questions are not guessed.

## 4. NOT verified (be sceptical until measured on real phones)
* Step detection on real bodies and carry positions (pocket, hand, armband, bag); the walking/running thresholds were chosen from typical cadences, not tuned on recordings.
* Heading from the gyroscope over long runs, and GPS accuracy figures from real receivers (the model treats stated accuracy as 1 sigma).
* ARCore drift rate (modelled as 2%), and how real tracking loss and re-localisation look.
* The live screen's analysis load for hour-long recordings (it re-reads the file every 6-40 s).
* Battery cost of recording, and recording with the screen off on every vendor's power management (a foreground service with Stop is used).
* Compose layout on other screen sizes.

## 5. Not built yet (honest list)
Prerecorded **video** analysis (the pose models in Workouts could feed `Trace` as a fourth source); Wi-Fi/Bluetooth signal strength as a position sensor; watch heart-rate and motion capture (the reader already accepts `hr` lines; the capture is not wired); a two-device comparison of the same movement; loop-closure drift correction from a user-confirmed return to the start; visual SLAM; whole-building graphs of places; semantic change detection between scans (today: counts of cells added/removed per merge, with the caveat that new is not the same as changed).
