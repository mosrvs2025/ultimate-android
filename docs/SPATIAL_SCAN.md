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
| Noisy 3D shape | The app integrated depth at 20 Hz but ARCore repeats one raw depth picture for several frames, so "seen 3 times" was satisfied by a single picture. No motion gating, no edge rejection, nothing ever removed. | One picture counted once (timestamp + checksum); solid needs evidence from ≥ 4 key frames (3 cm or 2.5° apart); skipped above 100°/s or 1.2 m/s; depth-edge pixels dropped; range 0.4–4.5 m; rays carve free space so things seen through later fade; isolated cubes are not drawn or measured. |
| "No depth at the dot" much of the time | The readout used only confident raw depth (≥ 110/255) in a 5×5 window. Raw depth-from-motion is sparse on plain walls and doors; that is expected, but there was no fallback. | Falls back to ARCore's filled-in depth, labelled "≈ … (estimate)". Raw-only is still used for the map and tap-measure. |
| Two "bed" labels, labels drifting | Same-label sightings merged within a fixed 0.7 m (a bed is 2 m), position from the single depth cell at the box centre (often the wall behind). | Merge radius per kind of thing; position = median depth over the middle of the box; higher score thresholds. |
| Questionable labels ("book", "suitcase") | Detector limits: EfficientDet-Lite0, 80 COCO classes, 320 px input. | Thresholds raised only. A better detector is future work; geometry can't fix this. |
| "Doesn't match a room you've saved" | The matcher sliced points "0.3–2.2 m above the floor": with the floor wrong by metres the two slices were different parts of the room. Also 5° steps with no refinement, and a noisy map on both sides. I can't tell from the video whether the earlier saved room was this same room. | Matching uses tall structure (independent of the floor), 2° search + sub-cell refinement, reports fit and spread, refuses ambiguous fits, and runs automatically. Old saved rooms are re-measured on load, but they contain the old noise: rescan and save them again. |

I did not have Gemini's proposed fixes in this session, so nothing here is taken from them or checked against them.

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
