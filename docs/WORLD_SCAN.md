# Ultimate World Scan

**Status: a working end-to-end tool in Ultimate+ (2.7.1). Tested in simulation and by unit tests; not yet measured on a phone in a street.** Every number below says which of those it is.

World Scan is a personal, local-first mapping instrument: you walk with the phone held up, and it builds a 3D map from ARCore's motion tracking and depth, places the walk on the Earth with GPS, keeps the walk's raw record, and lets you come back and walk the same place again to improve the map. Nothing is uploaded; files leave the phone only when you share one.

It is a research instrument, not a survey tool, and it does not close loops. Section 6 lists exactly what it cannot do.

## 1. What was reviewed in PR #39, and what was found

The draft (`feature/world-scan-foundation`) added a geographic model, a foreground sensor recorder, four tests and a roadmap. Its build and tests were green on CI. Reviewed line by line, these are the things that were wrong or missing, and what this branch does about them:

| Finding | Why it matters | Fix |
| --- | --- | --- |
| `LocationListener` implemented only the Android 11+ callbacks. | Minimum SDK is 29. On Android 10 the system calls `onStatusChanged`/`onProviderEnabled`/`onProviderDisabled`, which were abstract there, and the app would crash with `AbstractMethodError` on the first GPS fix. The code compiled, so CI could not see it. | Implemented all three. |
| `recordCameraFrame()` assumed ARCore's frame timestamp is on the same clock as sensors and GPS. | ARCore's documentation says the time base "is specifically not defined". On a phone whose camera uses the monotonic clock the two differ by the time the phone has spent asleep: seconds to days. GPS and camera could never be compared. | `ClockSync` (section 3.1); every pose is logged with both clocks. |
| `WorldScanSession.matchingTiles` required the two sessions to have an identical anchor. | Two walks on different days start at different first fixes, so two real walks could never match. | `GlobalTile`: an anchor-independent lattice; `globalTiles()`. |
| Sensor callbacks, JSON building and file writes ran on the main thread; nothing bounded the file. | ~120 events a second at game rate: dropped frames in the camera view and about 60 MB an hour of text. | Background `HandlerThread`, hand-built lines, gzip written as it goes with a size cap that stops only the high-rate sensor lines, so GPS and poses (which a walk cannot be placed without) continue. |
| No inverse of the geo projection, no export. | A walk could not be shown on a map or shared. | `GeoAnchor.unproject`, GeoJSON and PLY export. |
| `isUsable()` accuracy limits and the east/north/up maths. | Checked: correct for the intended use. The projection is a local tangent approximation (WGS84 radii of curvature); round trip error under 1 cm at 600 m in a test. | No change. |

## 2. How it fits together

```
 ARCore (camera, depth, motion tracking)       Android sensors + GPS
        |  frame, pose, raw depth + confidence          |  accel, gyro, rotation, magnetic, pressure, light, fixes
        v                                               v
  WalkScan (ultimateplus)  ----- poses with BOTH clocks ---->  WorldSensorRecorder  -> sensors.jsonl.gz
        |
        |  new depth pictures only
        v
  ScanIntegrator (shared with Room scan, walking settings)  ->  RoomMap pieces, 10 cm cubes, ~15 m of path each
        |
        v   Stop (or tracking is lost for good)
  WalkStore.save:  ClockSync -> poses on the sensors' clock -> GeoFit (walk <-> GPS) -> files
        |                                             |
        |  first walk of a place                      |  a later walk of the same place
        v                                             v
   base map = union of its pieces            WalkFusion.merge: WalkAligner (whole walk, then each piece) -> fuse or refuse
        |
        v
  WorldViewer: top-down map + replay + 3D + Details;  WorldExport: GeoJSON path, PLY points
```

Reused from Room scan: the ARCore session set-up, raw depth and confidence handling, `ScanIntegrator` (confidence, range and edge filters, key-frame gating, free-space carving, body exclusion), `RoomMap` (log-odds cubes, fusing, binary format), `Alignment`, `Orbit`, the tool/registration pattern. Room scan itself is unchanged except for one added method, `RoomMap.absorb`.

What the first draft's checklist asked for and where it is: UI and consent (the tool, permission asks, a live indicator, Stop; foreground only), ARCore integration (`WalkScan`), long-route localisation (3.2, 3.4), review/replay (viewer, `SessionLog`), 3D processing (map and PLY), multi-session persistence (`WalkStore`).

## 3. The algorithms, and what was chosen over what

### 3.1 Putting the camera on the sensors' clock (`ClockSync`)

Each frame is stamped with ARCore's own time and with the elapsed-realtime clock when the frame arrived. Their difference is `offset + latency`, and latency is never negative, so the offset is the smallest difference recently seen; the smallest per 5 s is kept and the median of the nearest three is used, which follows slow drift and ignores one odd bucket. The honest error bar is the typical latency above that minimum (`jitterNs`); the minimum latency itself cannot be known. At walking pace 30 ms is 4 cm, far below GPS noise. The offset, jitter and drift are stored with each walk and shown in Details.

Test (simulated delays, an offset of 123 s and 20 ppm drift): offset followed to within 6 ms, jitter reported 8-18 ms against 12 ms true, drift read as 40-105 ms/hour against 72 true; a stream on the sensor clock itself is recognised as such.

### 3.2 Placing a walk on the Earth (`GeoFit`)

Motion tracking is smooth and metric but starts facing an arbitrary way; GPS knows north but wanders by metres. The walk's path (matched in time to each fix) is fitted to the fixes by a weighted rigid fit (a turn and a shift; Procrustes in closed form) with a Huber loss, so a fix thrown off by a building does not drag the fit. Weights come from each fix's reported accuracy (floored at 2 m, because GPS error is correlated from fix to fix).

The important output is not the fit but the **uncertainty the walk's own geometry allows**, widened for the fact that GPS errors are not fresh noise at every fix (a bias lasts many seconds, so 140 fixes are worth far fewer independent ones). How much one fix's error carries over to the next (`rho`) is read off the fit's own residuals, and the uncertainty is multiplied by `sqrt((1+rho)/(1-rho))`; white noise leaves it unchanged. Without this the first version was over-confident, which a combined-walks test exposed (claimed ±0.29° against a real 1.0° error). the heading's standard deviation is `1/sqrt(Σ wᵢ|rᵢ|²)` (Fisher information of the rotation, before that widening), so a 6 m straight walk reports about ±35° and is refused, and a 140 m L-shaped walk reports well under 1°. A walk is `GOOD` only if heading σ ≤ 4°, at least 70% of fixes agree, GPS-vs-walk length agrees within 8% and the residuals are consistent with the reported accuracies; `WEAK` up to 20°; otherwise it is **not placed** and says why. A fitted scale far from 1 is reported as evidence that something is wrong, never used to rescale the map. ARCore's frame is right-handed Y-up: looking from above, x is east and −z north when the turn is 0, and a mirrored GPS track cannot pass as good (tested).

Calibration test (simulated; 40 different draws of a 1.2 m slowly-varying bias plus 1.5 m white noise on a 140 m L-shaped walk): the true heading fell within 2 claimed sigmas in 38 of 40 (95% is the ideal), mean claimed ±0.65° against a mean error of 0.58°.

Other tests: a 140 m L-shaped walk with simulated GPS (1.5 m white noise plus a slow 1.2 m bias, reported accuracy 5 m) recovered the heading to within 2° (asserted) and the reported σ covered the error; a 60 m outlier fix did not move the fit; a short straight walk was refused.

**Not used: ARCore Geospatial API (VPS).** It would give global pose from Google's street imagery, but it needs an API key, a network connection and Google Cloud, and it only works where Street View coverage exists. That contradicts local-first. The `GeoRegistration` type is the seam where a Geospatial pose could be added later as one more constraint.

### 3.3 Capturing a walk (`WalkScan`, `WalkSettings`)

Depth goes through Room scan's `ScanIntegrator`, with settings for walking. **This matters:** the room defaults drop any depth picture taken while the phone moves faster than 1.2 m/s, and an ordinary walking pace is 1.2-1.5 m/s. In a simulated 12 m walk at 1.4 m/s the room defaults used at most 2 of about 86 frames; the walking settings used all of them (test `theRoomScannersDefaultsThrowAWalkAway`). Cubes are 10 cm and range is capped at 5 m because that is where ARCore's raw depth is best.

Every ~15 m of path (or 90 s) the current map piece is closed and a new one started. Pieces exist so that a later walk can be fitted **piece by piece**, because motion tracking drifts about 1% of the distance and one rigid move cannot be right for both ends of a long walk.

A walk **ends by itself** (and what was captured is saved) when tracking is lost for more than 4 s, or when it comes back further from where it left off than a person could have walked in that time plus 1 m (`TrackingWatch`). ARCore can resume in a new frame without saying so, and nothing before and after a new frame can be put in one map. It also ends when the app goes to the background: collection is foreground-only.

### 3.4 Adding a later walk (`WalkAligner`, `WalkFusion`)

1. **Structure**, not everything: columns with at least 4 cubes spanning at least 0.7 m of height (facades, walls, trunks, parked cars). Flat ground cannot make two walks look alike.
2. **Search**: a coarse-to-fine chamfer match. The saved structure becomes a distance field; each candidate (turn, shift) is scored by how close the fresh structure lands, which is smooth, so a nearly-right pose scores nearly right. A sweep keeps the best few *distinct* poses and a pattern search polishes each. If both walks were placed on the Earth the sweep is confined to the few degrees and metres GPS leaves open; otherwise every heading is tried near the middle of the saved map.
3. **Refuse** if the best match is not clearly the best: a rival pose that fits almost as well (a long straight wall slides along itself) marks it ambiguous and the walk is **not added**. Also refused if the walks do not overlap enough (≤ 25% of walls within 30 cm), the whole-walk fit is looser than 50 cm, or there is no ground in common to set the height.
4. **Per piece**: each ~15 m piece is then fitted again close to the whole-walk placement (±4°, ±1.5 m). It takes its own fit if that is unambiguous and better, the whole-walk placement if that is already good (≤ 35 cm), and is **left out** otherwise.
5. **Fuse**: a piece only *removes* what the saved map holds (something that moved, or noise) when its fit is tight (≤ 15 cm); a looser fit can only add and confirm. A refused walk changes nothing.
6. **Nothing is lost**: every walk keeps its own pieces, track and sensor log, and a merge changes only `base.bin.gz`, with one-step undo. A refused walk can be tried again.

Rejected: the room scanner's nearest-point alignment (its voxel-lattice bias is 8-17 cm, measured earlier), and global bundle adjustment / loop closure, which needs feature observations the phone does not keep (see section 7).

**Bug found in review of this design, by reasoning before any test:** `RoomMap.fuse` treats a cube next to an existing one as the same surface. Used to join the pieces of one walk into a first map, it would have thinned every wall to a sparse scatter. Pieces of one walk share a frame and are joined with a new exact union, `RoomMap.absorb`; `fuse` is used only for a second scan.

### 3.5 Measured, in simulation

All numbers in this table come from tests on a made-up street (ray-cast depth over boxes with Gaussian range noise of 1 cm plus 0.4 cm·d², i.e. 9 cm at 4.5 m, and random confidence values; the simulator has no wild outliers, dropouts, moving people or lighting, which real depth does, so these results are optimistic), **not from a phone**. They show the code recovers a known truth from data with known defects; they say nothing yet about how it does on a real street.

| Test | Result |
| --- | --- |
| Walking pace through the room defaults vs the walking settings (12 m at 1.4 m/s) | ≤ 2 of ~86 frames vs all |
| 36 m simulated walk, facade 3.5-4.5 m away | centre of the facade within 0.3 cm (median) and 1.9 cm (90th percentile) of the true plane; the map is 11 cm thick (1 σ), following the depth noise by design (it counts readings, it does not average them); 93% of the facade length recovered; under 3% of the "upright structure" columns fall on the walked pavement strip (asserted) |
| Two simulated walks of one street, second in an unrelated frame, no GPS | found the true turn within 1.5°, height shift within 15 cm, whole-walk fit 6.1 cm, 4 of 4 pieces added |
| Same with 3× realistic heading drift (0.04°/m) | whole-walk fit 9.4 cm; 3 of 4 pieces fitted individually; average fit 6 cm |
| Long flat wall only | refused as ambiguous, map unchanged |
| A different street | refused, map unchanged (checked point by point) |

### 3.6 Placing the place from every walk (`PlaceGeo`, 2.7.1)

A walk's GPS error is mostly a slow bias (buildings, which satellites were in view that day), so one walk cannot average it away. Walks made at different times have independent errors. Every walk added to a place has its path carried into the place's frame by the alignment it was merged with (piece by piece), and all the fixes are fitted together with the same robust fit as one walk. The heading uncertainty is then `1/sqrt(Σ information)`, which falls with every walk; a place whose first walk had no GPS is placed by a later one. The place's placement is what the next merge's search window is built from. In the test (three simulated walks of the same L-shaped route, independent GPS bias and noise) the claimed heading uncertainty falls from ±0.92° to ±0.64° to ±0.52° as walks are added. Across 20 different draws of three walks the true heading fell within 2 claimed sigmas in 18 (mean claimed ±0.50°, mean error 0.59°): slightly over-confident, so read the combined number as a floor. Position uncertainty barely moves (3.08 m to 3.02 m) because it is limited by the 2 m per-fix floor, not by the number of fixes.

### 3.7 What is recorded about the phone

Each walk stores the highest thermal status Android reported and the battery percentage at the start and end (whole percents), shown in Details, so the checklist's heat and battery question is answered by the app. A walk is stopped and saved at "severe" heat.

## 4. Capture format (JSON lines, gzip, schema 2)

All times are Android elapsed-realtime nanoseconds except `startedEpochMillis`. Cadence differs per stream.

- `{"type":"session","schema":2,"startedEpochMillis":…,"sdk":…,"sensors":[{"type","name","maxRange","resolution","minDelayUs"}]}` (the capability matrix)
- `{"type":"sensor","s":<sensor type>,"t":…,"a":<accuracy>,"v":[…]}` accelerometer and gyroscope at game rate, rotation vector at 20 Hz, magnetic field, pressure and light at normal rate
- `{"type":"location","t":…,"lat":…,"lon":…,"acc":…,"alt":…,"speed":…,"bearing":…,"provider":"gps"}`
- `{"type":"pose","tf":<ARCore frame time>,"tr":<elapsed-realtime when received>,"p":[x,y,z],"q":[x,y,z,w],"k":"TRACKING"}` at most 10 a second
- `{"type":"note","t":…,"kind":"start|end|piece|tracking-lost","detail":"…"}`

`SessionLog.read` reads it back (a log cut short by a crash is read as far as it goes and reported incomplete). The line formats are built in `LogLines`, which the recorder and the tests share.

## 5. On the phone

`files/worldscan/places/<place id>/` holds `place.json`, `base.bin.gz` (the merged map in the first walk's frame), `base.prev.gz` (before the last merge) and `walks/<walk id>/` with `walk.json`, `track.bin.gz`, `piece_NNN.bin.gz` and `sensors.jsonl.gz`. The size depends on the walk and has not been measured on a device; Details shows the real size of each place. A walk will not start with under 300 MB free. Interrupted logs older than a week are removed.

## 6. What it cannot do (read before trusting a number)

- **No loop closure.** A long walk that returns to its start will not meet itself; the gap is the accumulated drift (about 1% of the distance). The per-piece fitting fixes this only between *walks*, not within one.
- **Accurate locally, roughly placed globally.** Depth is good to centimetres to tens of centimetres within about 5 m; placement on Earth is good to metres and a few degrees, and only when the walk had a good GPS view and was long and bendy enough (Details states the uncertainty).
- **Needs light and texture.** ARCore loses tracking in the dark, on blank walls, and in fast turns; the walk then ends.
- **Moving things are not understood.** Cars and people are mapped where they were; a later walk can clear them only when it fits tightly and looked straight through the spot.
- **Repeat walks must overlap.** Walk the same route the same way. Walking it backwards shows the other side of the street, which shares almost nothing.
- **Straight featureless routes cannot be merged** (a long wall slides along itself); the walk is kept and the reason shown.
- **No colour, no mesh, no splats.** The map is height-coloured cubes. Colour from the camera image would need a calibrated mapping that cannot be verified here.
- **No neighbourhood-scale index or streaming yet.** A walk's whole map stays in memory until it is saved (about 100 MB a kilometre in simulation; **not measured on a phone**), so a very long walk is stopped and saved when the heap stays above 85%, and the viewer holds a place's merged map in memory. A few kilometres of street is the intended scale, a city is not.
- **Not verified on a device.** No part of this ran on a phone before 2.7.0 shipped. The first real walk is the test (checklist below).
- Samsung and Pixel phones differ in depth quality and in how ARCore behaves under heat; nothing has been profiled for thermals or battery.

## 7. Roadmap, in the order it would pay off

1. **Measure on a phone** (checklist below) and set the thresholds from data instead of reasoning.
2. **Loop closure within a walk**: keep sparse visual features per piece and add loop constraints to a pose graph over the pieces.
3. **Colour**, once the camera-to-depth mapping is verified on a device.
4. **Streaming tiles** keyed by `GlobalTile`, so a place can be larger than memory.
5. **Optional Geospatial pose** as one more prior, explicitly opt-in because it uses the network.
6. **Export for desktop reconstruction**: poses + frames + depth for Gaussian splatting / mesh, again opt-in.

## 8. Device test checklist

Do these once, outdoors, on a phone with ARCore depth, and record the numbers from the Details tab.

1. First start: camera and location prompts appear once; "Tracking ✓", "Depth ✓" and a GPS accuracy appear on the capture screen; Start enables only when tracking is up.
2. Walk 60-100 m along a street with buildings at 3-5 m, an L-shaped route, normal pace. Check: the minimap fills in behind you; speed reads about 1.2-1.5 m/s; the AR points colour by height.
3. Stop. The saved dialog should say "Placed on the map ±x° heading" with x small. Record heading σ, GPS fit, scale (should be within ±5% of 1.000), clock jitter, tracking share.
4. Open the walk: Map, drag and pinch, North up, GPS dots, replay scrubber; 3D view; Details.
5. Share path (GeoJSON): open it in a map viewer and check the line lies on the street within the GPS error.
6. Add a walk from the same start, same route. Record whole-walk fit, pieces added, pieces fitted individually, margin. Then Undo last add and confirm the map returns.
7. Add a walk on a different street; it should be saved but refused.
8. Cover the camera mid-walk for 5 s: the walk should end by itself and save.
9. Press Home mid-walk: the walk should stop and save, with the reason shown.
10. Thermals and battery: note phone temperature and battery drop for a 10 minute walk.

## 9. Privacy

Foreground only, started by a visible button, with a visible recording indicator. Camera pictures are looked at and dropped (only depth, shape and path are kept); the sensor log holds numbers only. Nothing is uploaded. Maps are private to the app. A shared GeoJSON path shows where you walked, and the share dialog says so. People and number plates are not recorded as images; they can appear as shapes in the map. Do not map private property without permission.
