# Room scan (Spatial Intelligence), Ultimate+

Added in 2.1.0. Tool id `roomscan`, in the Senses group. Ultimate+ only.

## What it does

- **Scan**: ARCore motion tracking gives the phone's pose; ARCore raw depth gives a distance per pixel. Each confident depth pixel
  (confidence ≥ 110/255, 0.25–6 m) is unprojected into world space and dropped into a 5 cm voxel grid. A voxel counts as solid after
  3 sightings, which removes most depth noise.
- **Measure**: live distance to the centre dot; tap two points for a straight-line distance; room length, width, height and seen
  floor area from the voxels (floor and ceiling are histogram peaks in height; length and width are the tightest wall-aligned box).
- **Recognise**: MediaPipe object detector (EfficientDet-Lite0, COCO) on the camera picture about once a second. A detection is
  placed in 3D using the depth under its box. Only household labels are kept (people and vehicles are dropped on purpose). A thing is
  remembered after 3 sightings within 0.7 m.
- **Remember**: rooms are saved under the app's private files (`files/rooms/<id>/room.json` + `map.bin.gz`), typically well under
  1 MB each. Rename and delete from the viewer.
- **Relocalize**: "Have I been here?" matches the current scan's wall-height slice against each saved room (yaw search + translation
  voting, then an exact overlap score; needs ≥ 55% overlap). On a match the saved room's things appear as green AR labels, a
  `spatial` signal `room_<name>` is published for the rest of the app, and the scan can be merged into the saved room.
- **3D viewer**: drag to orbit, pinch to zoom, points coloured by height, object labels.

## Privacy

Camera pictures are processed in memory and discarded. Only voxels, labels and positions are stored, in app-private storage. Nothing
is uploaded; ARCore Cloud Anchors and the Geospatial API are not used, and the tool makes no network calls.

## Hardware limits

- Needs an ARCore-certified phone with the Depth API and "Google Play Services for AR" (the tool offers to install it). The app still
  installs on phones without ARCore (`com.google.ar.core` is `optional`).
- Most Galaxy phones (S21 and later, A-series) have **no depth sensor**: ARCore computes depth from the camera moving. Expect it to need
  a few seconds of sideways motion before depth appears, to work best at 0.5–5 m, and to fail on blank white walls, mirrors, glass,
  shiny floors and in dim light. Galaxy S20+/S20 Ultra/Note10+ have a ToF sensor ARCore can use, which should be steadier.
- Depth images are small (commonly 160×90 or 640×480 depending on device), so thin things (chair legs, cables) are missed.
- Motion tracking drifts over long walks; there is no loop closure, so a large room scanned in one long loop may show doubled walls.
- ARCore has no on-device persistent anchors, so relocalization is geometric (shape of the room), not visual. Two rooms of the same
  shape and furniture layout can be confused; a mostly empty box room has a 4-way rotational ambiguity.
- EfficientDet-Lite0 knows 80 COCO classes. It has no label for wardrobes, shelves, desks, doors, windows, lamps and so on.

## Measured accuracy

Measured on **synthetic data only** (unit tests, `RoomScanTest`):

| Check | Result |
|---|---|
| Room dimensions from a perfect synthetic scan (5 cm voxels) | within 10 cm of truth, including a room rotated to the axes |
| Depth pixel → world → screen round trip | exact to float precision |
| Relocalization from a different start pose | recovered; saved points map back within 15 cm |
| Relocalization from half the room | recovered |
| A different room (corridor) | rejected |

**Not measured: anything on a real phone.** This was built without access to a device, so frame rate, depth quality, drift, detector
hit rate and real-room measurement error on Galaxy hardware are unknown. The scan screen shows depth resolution and frames per second
so these can be read off the device. Public figures for ARCore depth-from-motion are roughly a few cm of error at 1 m growing to tens of
cm at 4–5 m; treat room measurements as estimates until checked against a tape measure.

## Not done yet

- Hand tracking integration (pointing at a thing to select it). ARCore owns the camera during a scan, so the MediaPipe hand pipeline
  would need to be fed ARCore's CPU image; not attempted.
- Surface meshing (the reconstruction is voxels/points, not triangles), textures, loop closure, export (PLY/OBJ).
- Automatic relocalization in the background; it only runs when asked, inside the tool.
