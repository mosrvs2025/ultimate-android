# Room scan (Spatial Intelligence), Ultimate+

Tool id `roomscan`, under Senses, Ultimate+ only. First shipped in 2.1.0; the engine was rebuilt in 2.2.0 after the first real-device
recording (Galaxy, depth 160×90, depth-from-motion). Everything runs on the phone. Nothing is uploaded.

## 1. What the first real-device recording showed, and why

All causes below were found in the 2.1.0 source and reproduced with a simulated phone (`SpatialEngineTest`,
`theOldPipelineReproducesTheDeviceFailureAndTheNewOneDoesNot`).

| Seen on the phone | Root cause in 2.1.0 | Fix in 2.2.0 |
|---|---|---|
| Height 4.95 → 5.95 m, growing during the scan | Floor = densest layer in the *lowest* 0.4 m of all solid cubes; ceiling the same from the *highest*. A few stray readings (6 m range, a mirror, a window, depth edges) moved both outward, and they could only ever grow. | Floor/ceiling need a layer whose area stands clear of the wall baseline (≥ 0.5 m²), below/above every camera position, at a plausible height (1.8–4.6 m). Otherwise "not measured". ARCore plane detection is used as a second opinion on the floor. |
| "floor seen 0.0 m²" | Area was counted at the wrong "floor" height (see above), where there were almost no cubes. | Counted at the supported floor. |
| 6.6 × 4.5 m for a bedroom | Size = 2nd–98th percentile of every point above the floor, so noise outside the walls widened it. | Walls = tall vertical columns; direction by Hough; size = outermost real wall row to outermost real wall row, only when both sides were seen. |
| Noisy 3D shape | The app integrated depth at 20 Hz but ARCore repeats one raw depth picture for several frames, so "seen 3 times" was satisfied by a single picture. No motion gating, no edge rejection, nothing ever removed. | One picture counted once (timestamp + checksum); solid needs evidence from ≥ 3 key frames (4 in 2.2.0) (3 cm or 2.5° apart); skipped above 100°/s or 1.2 m/s; depth-edge pixels dropped; range 0.4–4.5 m; rays carve free space so things seen through later fade; isolated cubes are not drawn or measured. |
| "No depth at the dot" much of the time | The readout used only confident raw depth (≥ 110/255) in a 5×5 window. Raw depth-from-motion is sparse on plain walls and doors; that is expected, but there was no fallback. | Falls back to ARCore's filled-in depth, labelled "≈ … (estimate)". Raw-only is still used for the map and tap-measure. |
| Two "bed" labels, labels drifting | Same-label sightings merged within a fixed 0.7 m (a bed is 2 m), position from the single depth cell at the box centre (often the wall behind). | Merge radius per kind of thing; position = median depth over the middle of the box; higher score thresholds. |
| Questionable labels ("book", "suitcase") | Detector limits: EfficientDet-Lite0, 80 COCO classes, 320 px input. | Thresholds raised only. A better detector is future work; geometry can't fix this. |
| "Doesn't match a room you've saved" | The matcher sliced points "0.3–2.2 m above the floor": with the floor wrong by metres the two slices were different parts of the room. Also 5° steps with no refinement, and a noisy map on both sides. I can't tell from the video whether the earlier saved room was this same room. | Matching uses tall structure (independent of the floor), 2° search + sub-cell refinement, reports fit and spread, refuses ambiguous fits, and runs automatically. Old saved rooms are re-measured on load, but they contain the old noise: rescan and save them again. |

I did not have Gemini's proposed fixes in this session, so nothing here is taken from them or checked against them.

### Second recording (2.2.0): overcorrected

The same room gave 10,836 points (2.1.0: about 45,000), one object, and no walls or ceiling. Causes, and what 2.2.1 changed:

- Four viewpoints per cube, on 160×90 depth whose noise spreads a wall over several cubes, was too many: now three.
- Raw confidence below 110/255 was discarded, which is most of a plain wall: now accepted from 50 with a proportionally smaller weight.
- Free-space carving assumed 4 cm of depth noise per metre; real noise is larger, so rays to noisy hits erased the wall in front of them.
  The untouched margin next to a surface is now 15 cm + 10 cm per metre, and each pass removes less.
- Edge rejection threshold doubled (15 cm or 12% of the distance).
- The simulator's noise was too kind (2 cm at 1.5 m). It is now 5.5 cm at 1.5 m and 19 cm at 3 m; all twelve tests pass with it.
- Added: faint tentative points (drawn, never measured or saved), Mark floor / Mark ceiling, ceiling from ARCore planes, lower detector
  thresholds, wrapped buttons, centred viewer pivot, floor grid.

Not done from the feedback on that recording: hand masking (the hand still leaves some points; the simulated hand leaves under 120
cubes), a 60 fps object tracker, folding the buttons into the floating orb, wireframe room box, label de-cluttering.

### Third recording (2.2.1): merging

2.2.1 responded well (points, objects and a size of 5.31 × 5.02 m appeared during the scan), but "Add to my room" went wrong.
Verified in the source:

- **The live scan was wiped after every Add.** The add handler called `scan.reset()`. That is why the readout fell to "0 points · size not
  measured" straight after Add, and why the 2.21 m ceiling height from the fan was lost (it lived only in the wiped scan, and was never
  stored with the space).
- **Every Add reused the first alignment.** The match was computed once and reused for Scans 2, 3, 4, hence the identical "75% fit ±6 cm".
  That reuse is not wrong while ARCore keeps tracking (the session frame is unchanged), but it was never improved or checked, and the ±6 cm
  was the coarse 10 cm-cell search, not a measurement.
- **Doubled walls.** "8,279 confirmed, 24,367 new": a new cube was only counted as confirming if it fell in exactly the same 5 cm cube as a
  saved one, so with a few centimetres of alignment error nearly every wall point was added again beside the old one. Part of the mess is
  also the base: "First scan, 6 hr ago, 45,483 points" is a 2.1.0 scan, before the noise filtering; old saved spaces are not cleaned up by
  rescanning.
- **Duplicate and ghost things.** Saved things were kept forever as "not seen last scan", and two entries of one thing were never merged.

Changes in 2.3.0: no reset; alignment polished on every Add; a point next to a saved one confirms it; height stored with the space and held
in the live readout; wall-top ceiling estimate (requires ≥ 25° of upward tilt, level-only scans are tested not to produce one); Mark ceiling
asks for a height when there is no distance at the dot; things merged within ~1.1× their size and dropped after two scans that looked at
their place and didn't find them.

A finding worth recording: the first attempt at polishing the alignment used nearest-point ICP on the voxel centres, and in the simulator it
reported a 2 cm fit while being 8–17 cm from the truth: it pulls the two scans' 5 cm cube grids into step with each other rather than the
walls. It was replaced by maximising overlap with a smoothed (about 7 cm) wall-density picture, which does not have that bias. With a good
simulated sensor and no drift it lands within 4 cm; with the ordinary noisy simulated phone, noise of 10+ cm at 3 m bounds the agreement.
The simulator's noise level is my estimate, not something I measured from the phone.

Not done: hand masking, a 60 fps object tracker, merging the buttons into the floating orb (a separate overlay), a pose-graph for drift.

### Fourth recording (2.3.0): the screens

Verified against the code and the video:

- **"Scan again to improve" asked for a name.** The button chose Save or Update only from whether the automatic match had locked; the space
  the scan was started from was used for nothing but the title. Now the target is used for the button, and Update lines up on demand.
- **Ceiling 7.47 m high with "No depth at the dot".** The mark used the last reading of the spot under the dot, which was never cleared.
  A wrong floor mark did the same, which is why the space saved as "3" is entirely purple (every point is far above the false floor) and
  shows 0.0 m² of floor. Marks now need a reading from the last half second, a floor must be below and a ceiling above everywhere the
  phone has been, and a ceiling over an estimated or missing distance offers a height instead.
- **Yellow rulers.** Any tap measured. Now a mode.
- **Legs and feet mapped.** True: nothing excluded the user. Now a cylinder under the phone is skipped.
- **Dresser as suitcase.** The detector has no such class. Now size rules relabel (cabinet) or drop, and wrong labels can be edited.
- **Stale "fits more than one way" message.** Verified: a re-check that found no ambiguity left the old text. Now every check replaces it,
  and checks also run when a new thing is confirmed; the things in common break a tie between two fits (tested on a bare box room).

Not done: palm-on-wall marking (needs hand tracking beside ARCore), anything for the "hardcoded centroid alignment" idea: a guess at an
alignment would corrupt the saved map, so an update that can't be lined up is refused with a reason instead.

### Fifth recording (2.4.0): why walls and ceilings stayed empty

Tapping the readout showed: *45,738 points · 103k raw · 329 views · checks kept 26%: low confidence 28%, out of range 23%, edges 23%*.

- "Out of range" includes pixels where ARCore's raw depth has **no reading at all** (0), which is plain paint. Together with low
  confidence that is half the picture, and the part that is missing is exactly the walls and ceiling. The "edges" figure was my own
  flying-pixel filter being too tight on cluttered scenes.
- Fix: the filled-in depth ARCore also provides (smooth, defined on plain surfaces, less exact) is used wherever the raw reading is
  missing or low-confidence, as weak evidence (weight 0.3, only 0.5–4 m, own edge test, never used to clear cubes). The edge filter went
  from 15 cm / 12% to 20 cm / 15%. The diagnostics line now also says how much was filled in.
- Simulated: a wall with 99% of its raw depth missing was 14% covered after a short walk, and fully covered with the filled-in depth;
  the rest of the room measures the same. Not yet checked on a real phone: the filled-in depth is smoother than the truth, so expect plain
  walls to be a little thicker than the others.
- The sizes disappearing (3.04 × 3.00 m, then 2.71 × 1.75 m, then "size not measured" on save) was the measurement being redone from
  whatever walls were visible each time. Now a wall-to-wall size is kept unless a later one is as large or larger.
- Not done: Gemini's "project rays onto the room shell": that would draw walls that were never seen; and a floor drawn under your feet
  (the skipped area is now 45 cm instead of 55 cm). Palm-on-wall marking still needs hand tracking running beside the scan.

## 2. Architecture

```
ARCore frame ─► ArScan (GL thread: copy new depth picture, pose, planes)
                  └► ScanIntegrator (worker: gate, filter, add evidence, carve) ─► RoomMap (evidence voxels + free-space grid)
RoomMap.cleaned() ─► RoomGeometry.measure ─► RoomStats (nullable, with confidence and notes)
                  └► Aligner.align(new, saved) ─► Alignment (fit, spread, ambiguity)
Fusion.update = RoomMap.fuse (reinforce / add / clear) + ObjectIdentity.merge ─► RoomStore (map, previous map, history)
```

Pure Kotlin in `app/src/main/java/app/ultimate/room/` (tested); ARCore/GL/MediaPipe in `app/src/ultimateplus/.../room/`.

- **RoomMap**: 5 cm cubes with log-odds evidence, last key frame and view count, in primitive open-addressing arrays; a 20 cm grid counts
  rays that passed freely. File format `RMP2` (reads the 2.1.0 `RMAP`).
- **Fusion**: the new scan is transformed into the saved frame. Agreeing cubes gain evidence; new cubes are added; a saved cube is cleared
  only where the new scan looked through that spot (≥ 8 rays) and found nothing solid within 10 cm. Unseen areas never change.
- **Objects**: stable ids, first/last seen, "missing" only when the place was looked at.
- **Store**: `room.json` v2 (stats, objects, sessions, kind, place), `map.bin.gz`, plus the previous pair for one-step undo.

### Tradeoffs

- Occupancy voxels, not a TSDF/mesh: simpler, robust with sparse 160×90 depth, cheap to merge and store (~150 KB a room). Cost: no smooth
  surfaces, 5 cm resolution, thin things (chair legs, cables) are lost, more so now that four viewpoints are required.
- Strict gating means slower build-up and holes on plain walls. Holes are reported as "not measured" rather than filled.
- Alignment is 4-DOF (gravity fixed) from structure: fast and floor-independent, but needs walls or tall furniture; a bare box room is
  ambiguous and is refused rather than guessed.
- No loop closure or pose-graph: tracking drift goes straight into the map and bounds merge accuracy.

## 3. What was verified (simulation only)

`SpatialEngineTest` ray-traces a 4.2 × 3.4 × 2.5 m furnished room into 160×90 depth pictures with range-dependent noise, 2% wild
readings, 10% dropouts, a hand in frame a quarter of the time, a mirror, and tracking drift, in turned and shifted session frames.

| Check | Result |
|---|---|
| 2.1.0 pipeline on the same data | height > 4 m, floor area < 1 m² (matches the device failure) |
| Length / width / height, single noisy scan | within 20 cm / 20 cm / 12 cm; repeatable across two different walks |
| Unseen ceiling or far wall | reported as not measured, low confidence |
| Repeated depth picture, fast turn | not integrated |
| Hand in frame | < 40 stray cubes left in mid-air |
| Second scan aligned to first | recognised; aligner alone ≈ 4 cm without drift, within 25 cm with simulated drift |
| Half a room / a corridor / a bare box room | recognised / refused / flagged ambiguous |
| Merge of 2 then 3 scans | size stays within 15–20 cm; third scan adds little |
| Furniture removed | cleared after one rescan; a quarter-room scan removes < 4% elsewhere |
| Object identity, store history, undo | as specified |

The simulator is my model of a phone, not a phone. Real ARCore depth has structured errors (smearing, scale bias, rolling shutter) it
doesn't model.

## 4. Needs a real-device test

1. Does the height settle near the true ceiling, and does a tape measure agree with length/width (and by how much)?
2. How many "views" per minute get through the gates (tap the readout for diagnostics: used / repeats / too fast / pixels kept)? If
   "too fast" dominates at normal walking pace, the thresholds are too strict for this phone.
3. Does requiring four viewpoints leave the map too sparse on 160×90 depth?
4. Is the same room recognised on a second visit, with what fit and ± cm, and does "Add to …" keep walls sharp or double them?
5. Do duplicate labels stop? Do the "≈ estimate" distances look sane on plain walls?
6. Frame rate and heat over a 4-minute scan.

## 5. Hardware limits

- ARCore Depth API required (the tool offers to install Google Play Services for AR). Most Galaxy phones have no depth sensor: depth
  comes from camera motion, at 160×90 on the test phone, best at 0.5–4 m, poor on plain paint, mirrors, glass, shiny floors, dim light.
- Mirrors and windows produce readings behind the wall. They no longer move floor/ceiling, but can still add phantom structure.
- The detector knows 80 everyday classes: no wardrobe, shelf, desk, door, window, lamp.

## 6. Towards buildings, vehicles and shelving (not done yet)

Present now: every saved space has a `kind` (room, vehicle, shelving, other) and a `place` that groups spaces. That is organisation
only; the engine treats every kind the same.

- **Connected rooms / buildings**: needs a transform between spaces. Plan: when one session passes from space A into B (both recognised
  in the same ARCore session), store the A→B transform as a portal; a place then becomes a graph of spaces with relative poses.
- **Vehicles**: interiors are mostly closer than the 0.4 m minimum and full of glass; expect poor results until tested.
- **Shelving**: needs finer cubes (2 cm) at short range and horizontal-surface extraction per shelf; the map supports a size parameter,
  the UI doesn't expose it yet.
- Also open: loop closure, surface meshing, export (PLY/OBJ), hand-tracking selection (ARCore owns the camera during a scan).
