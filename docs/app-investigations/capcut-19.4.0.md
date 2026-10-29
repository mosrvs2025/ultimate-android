# CapCut 19.4.0 inspection and independent Studio implementation

## Artifact observations

Input: `CapCut_19.4.0.apks`, 203,521,915 bytes.
SHA-256: `c53d5ce5b69b6394929b562254697936369f8770134ed6691e13da71c83f013f`.

The archive contains a 116,335,072-byte base APK, a 78,594,142-byte ARM64 split, language/density splits and dynamic-feature splits. Several feature splits are small placeholders; their names do not prove the full feature is present there.

Inspected ZIP members and DEX string tables from the supplied bytes. No original app execution, account access, paid-feature unlock, API requests or private-server testing occurred. No original binary, licensed media, fonts or proprietary engine is redistributed. This is a bounded package inspection, not a complete reverse engineering of CapCut.

Observed packaged native libraries include `libcapcut`, `libclipflow`, audio processing, encoders/decoders, rendering libraries and a separate Pitaya native split. Names alone do not establish the algorithms or performance of those engines.

Observed assets include AI-writer templates, bundled models and local Lynx UI packages, including subscription interfaces. Model names do not establish offline capability or licensing for redistribution.

DEX references include draft save/session processors, CanvasConfig types, track-material classes, text-to-speech, transcript-editing modules and automatic-caption configurations. Network path strings include common-task submit/query/cancel and AI-related routes. These observations show interface/code references, not that every feature executes, works offline, or is available to a given account.

## Design choices carried into Studio

- Keep an editable project separate from the rendered file. Store original URI references, source/trim ranges, ordering, speed, color/title controls, canvas and music in a versioned project schema.
- Use a single project representation for preview, export and undo/redo. Keep each clip's output clock distinct from the original source clock.
- Keep editing reversible and preserve original media. Import through Android's document picker with persistable read grants.
- Keep UI operations independent of rendering; a background export uses Ultimate's existing WorkSource, progress notification, terminal log and shared Library.
- Show actual local capabilities. Manual BPM cuts are not called automatic beat detection; static per-clip title cards are not called automatic captions.
- Use our own Kotlin/Compose and Apache-licensed AndroidX Media3 implementation. No CapCut native libraries or project format are required.

## Implemented scope

Studio is included in Ultimate and Ultimate+, with an additional standalone offline Studio edition. It provides video import, a selectable thumbnail sequence, full-project scrubber, trim/split/reorder/duplicate/delete, undo/redo, speed, volume, rotate, four color looks, exposure, fit/fill canvas, 9:16/16:9/1:1 output, per-clip titles, a looping soundtrack, manual BPM grid cuts, named saved drafts and MP4 export at 720 or 1080 short-side resolution. Exports use H.264/AAC and do not add a watermark.

Preview uses ExoPlayer. Export uses Media3 Composition/Transformer with the same visual effect builder. Preview crosses clip boundaries by loading the next clip and can have gaps; it is not a gapless CompositionPlayer preview. Music preview is periodically resynchronized to the project clock. Export renders one continuous composition. HDR exports are tone-mapped to SDR.

Original documents must remain accessible to reopen/export drafts. Project files contain references, not portable copies of the media. Undo history lasts for the current session and retains 80 edits. Drafts and export history are local. No automatic transcription, generative AI, footage analysis, proprietary templates, multi-video compositing, keyframe editor or transition system is claimed.

## Verification and boundaries

The pure timeline tests cover speed-aware splitting, trim validation, offsets, undo/redo, beat-grid rounding, beat-offset handling, short-source refusal and even output dimensions. JSON tests cover editable-project round trips and rejection of unknown schemas.

Android build results and device coverage are reported with the delivered build. JVM tests and APK packaging do not establish physical-device decoder/GPU/encoder compatibility, audio-video sync, HDR appearance, lifecycle behavior or editing UX. Those require actual Android execution on representative footage.

## Inputs with the most value next

1. A short screen recording of the user's most-used CapCut workflow and a finished reference edit. This sets priorities more precisely than more binaries.
2. Three to five original clips plus one song and the desired result. Include portrait/landscape, a normal H.264 clip and a phone HDR/HEVC clip if available.
3. An Alight Motion APK and a short example project/workflow for keyframes and motion design, or a VN APK for multi-track editing workflows. Each app requires its own artifact investigation; an APK does not grant rights to its engine or services.
4. On-device feedback from the user's Fold: import, scrub, split, export and share, plus any error text. A screen recording helps reveal fold/unfold layout and decoder issues.
5. A privately supplied stable signing configuration for long-term updates. No keystore or signing credential should be committed to this repository.
