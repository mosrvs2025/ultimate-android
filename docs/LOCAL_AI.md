# Local AI: Cactus Needle on the phone

Ultimate runs [Needle](https://github.com/cactus-compute/needle), a small tool-calling model by Cactus (Apache-2.0),
entirely on the phone. You type a request on Home; Needle proposes an action; you tap to do it.

## How it fits

```
Home ask bar ─┬─ "when / if / every time …" ──► rule parser (deterministic, tested) ─► "Make this a rule"
              └─ anything else ──► Needle (on-device, ~0.5 s) ─► proposal if confidence ≥ 0.5 ─► tap ─► AiActions
                                   keyword matcher still lists tools below, as before
```

**Engine.**
- The prebuilt Android engine (`libneedle.a` + `needle.h`) is fetched by `scripts/fetch-needle.sh` from Hugging Face
  at build time. It is a binary, so it is never committed.
- It is linked into a small JNI bridge (`app/src/main/cpp/needle_jni.c`, CMake) with the NDK's static libc++.
- It is built for ARM64 and ARMv7, in the Ultimate and Ultimate+ editions only.
- Elsewhere, or when the engine is missing, the bridge builds as a stub and the feature says it isn't available.
- The bridge needs only `libc`, `libm`, `libdl` and `liblog`. That was checked with `readelf`.

**Model.**
- `needle3.cact` is 35 MB.
- Ultimate+ downloads it once from Hugging Face. Ultimate (no internet permission) imports the file instead.
- After that, nothing leaves the phone.

**Threading.**
- The engine is process-global and not thread-safe. All calls run on one dedicated thread (`LocalAi`).

## Verified here

- **The bridge, end to end.** The same `needle_jni.c` was compiled for Linux against Needle's Linux engine and called
  from Java with the app's exact tool list (`AiTools.TOOLS`) and system prompt.

  | Request | Call | Confidence |
  |---|---|---|
  | "turn the volume down to 20" | `set_volume(20)` | 0.91 |
  | "turn on the flashlight" | `flashlight(on=true)` | 0.94 |
  | "free up some space" | `clean_up()` | 0.86 |

  Each took about 0.5 s on this machine, with peak memory around 140 MB.
- **The Android bridge links** for ARM64 (2.0 MB) and ARMv7 (1.3 MB), with no unresolved symbols beyond the system
  libraries.
- **Not yet verified on a phone.**

## Accuracy, and the confirm-first design

The stock model was run with a draft of this tool list on 27 everyday requests:

| Confidence ≥ | Acted on | Right |
|---|---|---|
| 0.0 | 19 | 15 |
| 0.4 | 12 | 10 |
| 0.5 | 9 | 8 |
| 0.6 | 5 | 5 |

**Strong:** flashlight, volume, storage, clean-up.

**Weak:**
- Choosing between app tools. "make this video smaller" became `record_audio` (0.45).
- Requests about later. "when I push toward the phone, turn on the flashlight" just turned the flashlight on.

**Hence:**
- Needle only ever proposes, at confidence ≥ 0.5.
- "When…" requests go to the rule parser.
- The keyword matcher keeps offering tools.

## Next

- **Fine-tune Needle on Ultimate's own requests.** `needle finetune` trains a LoRA adapter from JSONL, and
  `needle generate-data` can synthesise examples. This is the route to reliable tool routing and to rules.
- **Data questions about your own records.** Give Needle tools that query the timeline (notifications, clipboard,
  played media, Echo sessions), such as "how many times did I check Instagram last night". The app computes the
  answer; Needle only picks the query.
- **Whistle.** Cactus's speech model shares the engine, so the same bridge can take spoken requests.
