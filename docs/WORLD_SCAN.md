# Ultimate World Scan — implementation track

Status: **Foundation in review. Not yet an in-app scanning feature or an APK.**

## What has been implemented in this branch

- `worldscan/WorldScanModel.kt`: WGS84 approximate local east/north/up coordinates, validated GNSS observations, 20-meter tiles, timestamp-ordered sessions, ARCore local-frame identifiers, tracking-quality flags and shared-tile detection across revisits.
- `worldscan/WorldSensorRecorder.kt`: foreground-only on-device capture of GNSS location plus available accelerometer, gyroscope, magnetic, rotation-vector, barometric and illumination sensor streams. Writes one append-only JSONL file per explicit recording under `filesDir/worldscan/`.
- `WorldScanModelTest.kt`: validation, metric projection, negative tile indexing, time ordering and repeat-walk matching.
- `recordCameraFrame()` is an **integration hook** for future ARCore capture. No image/depth data is recorded or persisted by this branch. The existing room-scanning engine is not modified.

## Next integration: make it useful on-device

1. **Build and test first:** run `./gradlew :app:testUltimateplusDebugUnitTest --tests '*WorldScanModelTest'` with the repo's normal dependency bootstrap; then compile all editions. These checks have not been run by the author of this branch.
2. **UI and consent:** add a clearly labeled World Scan tool screen with Start, Pause, Stop, session history, storage estimate, permission explanations and an always-visible recording indicator. Request location permission from the UI. Tie `WorldSensorRecorder.stop()` to both Stop and lifecycle cessation. This recorder is not designed for long-running background walks yet.
3. **ARCore integration:** feed time-synchronized pose, tracking state, frame identity, calibrated camera intrinsics and depth observations from the existing room-scanner pipeline. Preserve per-session world-to-local transforms and never assume GPS equals centimeter-level localization. Record depth sparsely under resource limits.
4. **Long-route localization:** separate visual-inertial odometry, GNSS and optional ARCore Geospatial anchors. Detect loop closure via scene features and align sessions only when visual evidence and transform residuals justify fusion. GPS tile overlap alone is *not* a valid scan-merge criterion.
5. **Review/replay:** implement a bounded streaming parser for JSONL, timeline scrubber, debug graphs and exports. Save versioned metadata, consent scope and sensor capability matrix.
6. **3-D processing:** progressively reconstruct georeferenced point clouds/meshes, run robust registration, and optionally export footage/depth/poses for desktop processing or Gaussian splatting. Phones without ARCore depth should degrade gracefully.
7. **Multi-session persistence:** retain individual raw observations and their provenance, maintain aggregate spatial tiles with uncertainty, and never destructively overwrite earlier scans on loose alignment.

## Capture format (JSONL schema 1)

All timestamps except `startedEpochMillis` use Android elapsed-realtime nanoseconds on the device; they are not wall-clock epochs. Streams may differ in cadence and must be synchronized with care.

- `{"type":"session","schema":1,"sessionId":"...","startedEpochMillis":...}`
- `{"type":"sensor","sensorType":1,"tElapsedNanos":...,"accuracy":3,"values":[...]}`
- `{"type":"location","tElapsedNanos":...,"lat":...,"lon":...,"horizontalAccuracyMeters":...,"altitudeMeters":...,"provider":"gps"}`
- `{"type":"camera-frame","tElapsedNanos":...,"localFrameId":"...","tracking":"TRACKING","depthAvailable":true}` (only once wired from an ARCore session)

## Scientific and product integrity

- Respect permission grants, privacy of bystanders and private property, and recording indicators.
- Keep captures private by default. Redact faces, license plates and sensitive locations before export/sharing. Do not upload automatically.
- GPS is a coarse position anchor; barometer is pressure, not a precise floor height; RSSI is not wall imaging or a reliable distance measurement.
- Treat hallucinated or inferred geometry as guesses with confidence, never as raw sensor measurements.
- Measure drift, loop-closure error, coverage, dropped frames, thermals, battery, file size and reconstruction quality on real Samsung phones.
- Add disk quota and bounded IO buffering before high-rate capture or continuous background use.

## Fable 5.2 / second-agent handoff

Review this branch against `main`. First run build/tests and repair failures; then implement the visible World Scan tool and ARCore hooks by reusing the current room-scan pipeline. Preserve existing Room Scan behavior and all Ultimate editions. Optimize real-device reliability, privacy and testability, not marketing claims. Provide tests, profiling numbers and a device test checklist. Do not announce spatial mapping as complete until repeated walk recordings can be viewed and registered reliably.
