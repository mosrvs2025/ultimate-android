# Sound Meter 3.4: APK findings and Ultimate handoff

Date: 2026-10-25. Static analysis only; the application was not launched.

## Input identity

- Uploaded archive: Sound_Meter_3.4.apks
- Archive SHA-256: e4059c4f86d7cbfd5b9278246e57015f8e2a226915fd7d7c50b99cf133f9441f
- base.apk SHA-256: ba5a0648a7e8523f18f880601ce0df7419ca69871fe4551e52da5ca48c69fdea
- Package: com.splendapps.decibel
- Manifest version: 3.4, code 111; min SDK 32, target SDK 37.
- Five members: base.apk and arm64_v8a, en, es, xxhdpi configuration APKs.
- Base DEX: classes.dex (6,503,176 bytes); bundled Facebook audience-network DEX (5,455,144 bytes).
- Method: Python ZIP inspection, binary XML/resource decoding and Androguard DEX analysis/disassembly. REA/JADX MCP was not connected in this session; these findings are not REA-generated Evidence records.
- No original binaries or wholesale decompiled implementation are committed.

## Findings

### 1. Reported high levels contain an artificial contribution

Observed in base classes.dex, class Lww0;, method run()V, measurement branch entered at byte offset 0x01de:

| Byte offset | Recovered operation |
| --- | --- |
| 0x0202 | MediaRecorder.getMaxAmplitude() |
| 0x0216–0x022c | Convert to double; abs; log10; multiply by 20.0 |
| 0x022e–0x023e | Compare the result with 90.0; skip the following adjustment when below 90 |
| 0x024c–0x0288 | Count consecutive trailing history values at least 90 |
| 0x0294–0x029e | Add (count modulo 5) multiplied by 5 |
| 0x02a0–0x02b0 | Add Random.nextInt(6), i.e. integer 0 through 5 |
| 0x02b2–0x02c8 | Only continue to storage for result greater than 0 and no greater than 120 |
| 0x02cc–0x0304 | Update current value, history, sum, count and arithmetic mean |

This is bytecode-derived behavior, not a claim about observed readings on a phone. DAD decompilation produced misleading control flow and raw floating-point bit patterns; disassembly and IEEE-754 decoding resolved both.

Example numeric probe: amplitude 32767 gives 20*log10(32767) = 90.30873 before adjustment. Depending on history and random draw, the formula can yield 90.30873 through 115.30873. This range describes the recovered calculation, not physical sound pressure or a device experiment.

**Rebuild decision:** do not reproduce the history-dependent boost or random additions. Display smoothing must not fabricate measurements.

### 2. The main measurement path is peak amplitude, not a calibrated RMS pipeline

The inspected loop takes a recorder maximum and applies 20*log10(abs(amplitude)). No microphone sensitivity reference, RMS calculation, A-weighting filter or calibration offset appears in that calculation.

The app initializes its sampling interval F to 200 ms in DecibelApp.<init>, offsets 0x00e6–0x00ea. The measurement thread sleeps using F at 0x036e–0x0374. Other writes/settings could alter that interval; 200 ms is the observed default.

**Inference:** this path does not establish calibrated acoustic dB SPL or dBA. This is bounded to the recovered path, not proof that every possible class or feature lacks calibration.

### 3. Its average is an arithmetic average of the accepted displayed values

Lww0.run, offsets 0x02e6–0x0304, increments count, adds the adjusted value to a sum and divides sum by count. Thus the stored average can include the artificial contributions described above.

For a replacement, an energy average must average linear mean-square signal energy over actual sample counts before conversion to decibels. Equal-duration 40 and 80 dB levels have an energy average of approximately 76.99013 dB, versus an arithmetic average of 60 dB. This is a mathematical illustration, not a measurement.

### 4. The recorder writes a temporary audio file on the supplied APK's supported Android versions

DecibelApp.n constructs Lpc1; and calls B(), then prepares and starts MediaRecorder.

In Lpc1;.B()V, the SDK >29 branch sets an 8000 Hz, mono recording configuration and supplies an app-private path ending /mic_tmp to setOutputFile (offsets 0x0076–0x00e6). It attempts to delete a pre-existing file first. The older branch uses /dev/null. Since this APK declares min SDK 32, the newer path is the relevant one.

**Observed:** a microphone recording output path is configured. **Unknown:** file lifetime after all stop/error paths, actual contents on a device, or whether anything is transmitted. This finding alone does not establish transmission.

**Rebuild decision:** process PCM in memory by default and persist level summaries only. Raw audio should require a separate explicit recording choice.

### 5. The package includes substantial advertising and analytics components

Manifest declarations include INTERNET, ACCESS_NETWORK_STATE, advertising-ID/ad-services permissions, RECORD_AUDIO, billing, WAKE_LOCK and MODIFY_AUDIO_SETTINGS. Components/resources identify Google advertising, Facebook Audience Network and Firebase measurement/crash components. Resources include banner/interstitial/native ad identifiers and a lifetime ad-removal purchase flow.

**Observed:** declarations and packaged components. **Unknown:** which SDKs execute, consent behavior and actual traffic. Presence is not proof of transmission.

**Rebuild decision:** retain Ultimate's offline model; microphone permission is necessary for a meter, advertising and billing integrations are not.

## Proposed Ultimate tool: Sound meter

These are new design choices, not claims about features recovered from the original.

1. One clear Start/Stop control, current level, session elapsed time and a live level history. Request microphone permission only after Start.
2. Without a calibration profile, label the reading dBFS (digital microphone level). Optional reference calibration enables an explicitly labeled estimated SPL view. Do not label unweighted results dBA.
3. Capture mono PCM through AudioRecord. Prefer an unprocessed source only where supported; expose the chosen route and processing limitations. Handle microphone denial, interruption, route changes, mute, clipping and recorder initialization/read failures.
4. Compute RMS, sample peak, sample-count-weighted energy average and min/max interval levels. Label sample peak as a digital peak; do not imply certified acoustic peak pressure.
5. Save named sessions with timestamps, duration, calibration state, microphone route, gap/clipping markers and level summaries. Export CSV with units in column names. Do not silently fill interrupted periods with invented data.
6. Offer side-by-side session comparison and timestamped user notes. Use an accessible numeric display alongside graph colors.
7. For the first implementation, stop measurement when the tool leaves the foreground and visibly finish or pause the session. Background microphone capture needs its own declared microphone foreground-service lifecycle; do not attach it blindly to the existing data-sync service.
8. Integrate through core/Tool.kt with id sound-meter, no accepted shared MIME types, summary LibraryItems and its own session-detail screen.

## Coordination with Claude

Claude's source branch is claude/android-app-redesign-nrs3yt. Its Ultimate tool contract, shared library, theme and work routing are reusable. The dedicated analysis branch is codex/sound-meter-analysis-20261025; no modifications were made to Claude's branch.

At inspection time, UltimateApp.kt already used BuildConfig feature flags for the shipped tools. Account for the actual current build variants when implementing registration; the README alone is insufficient. Avoid assumptions based on the earlier main-branch Squeeze-only snapshot.

The follow-up implementation adds a Sound meter tool to Ultimate with deterministic PCM RMS/peak levels, sample-weighted energy averaging, a live timeline, reference calibration, named sessions in the shared Library, session comparison and CSV export. Capture stops on screen exit/backgrounding, mute, silence, route change or recorder errors. Samples remain in memory; only level summaries are saved. The other editions do not request microphone permission. Debug build and all 43 unit tests passed, including nine new measurement/export tests. Merged manifests were checked for edition-specific microphone permission and absence of INTERNET. Physical-device microphone accuracy and lifecycle behavior remain unverified.

## Verification required for a new implementation

- Deterministic known PCM: silence, fixed-amplitude sine, amplitude doubling (~6.0206 dB), clipping and uneven-length blocks.
- Confirm energy averaging differs appropriately from arithmetic dB averaging.
- Permission denial/revocation, backgrounding, navigation, interruption and route change release the microphone and show state accurately.
- On a real device, compare against a reference instrument at multiple levels. A single offset cannot correct all microphone processing, frequency-response or saturation effects.
- Check exported session values, units, calibration metadata and gap handling against the displayed session.
- Recheck merged manifest for unwanted network/SDK declarations and intentional microphone permission.

## Coverage and remaining work

Answered: uploaded identity/splits, main amplitude calculation, random/history contribution, average calculation, recorder output path, declared SDK/permission footprint and Ultimate integration boundary.

Unresolved: rendered original UI, device behavior, microphone response, calibration elsewhere outside the inspected calculation, complete temporary-file cleanup and network behavior. No emulator, physical-device or original-app runtime test was performed.
