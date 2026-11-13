# Ultimate Android Plus: audit, Octopus Mesh and roadmap

Status as of this branch (`ccr-3ce359cb-6lixjx`). Every claim below says how it was verified. **No physical phone and no emulator were available (the build host has no KVM), so nothing here is verified on hardware.**

## 1. Audit of the three repositories (Phase 1)

| Repo | What exists | What was verified here | Not verified / gaps |
| --- | --- | --- | --- |
| ultimate-android (primary) | ~31k lines of Kotlin/Compose: 20+ offline tools, terminal (Termux libraries), floating Orb overlay, key-only SSH/SFTP (Ultimate+), LAN scanner, VPN traffic inspector, six editions | `testUltimateplusDebugUnitTest` 228/228 pass **before** any change; `assembleUltimateplusDebug` and `assembleUltimateplusRelease` build | README says itself: not tested on a physical device. No device-to-device coordination existed. |
| ghostnode | Independent Kotlin app; Needle 3 on-device inference through JNI (ARM64 only), approval-gated tools, signed request envelope schema | Read code and its `verification/` folder (host x86 CLI results only) | Its README: Samsung execution unverified; LFM3 and pairing not implemented; ARM64 `.so` cannot run on this host. Not merged into Ultimate (kept independent, as asked). |
| ShatterOS | Node.js + Socket.IO + Matter.js shared physics world across browser screens | Not modified in this milestone | Server-authoritative (a central design), no auth on rooms. |

## 2. Architecture decision: Octopus Mesh (ADR-001)

**Context.** The vision asks phones, computers and browsers to share work and recover from disconnections, and asks whether a bio-inspired design is measurably better than a central one.

**Decision.** A pure-Kotlin mesh core in `app/src/main/java/app/ultimate/mesh/`, with no Android imports, so the *same* code runs in the app, in JVM tests with real sockets, and later on a computer:

- `MeshWire` — (superseded in 2.12.0 by the sealed `om2` format, see section 9) originally one signed line per frame: `om1|TYPE|from|seq|sentAt|b64(payload)|HMAC-SHA256`. Key = PBKDF2(pairing code). Rejects bad signatures, frames older than 60 s, and replays (`ReplayGuard`). Payloads are authenticated, **not encrypted**; only non-secret work travels over it.
- `Routing` — one decision function used by both the node and the simulator: a task runs locally if it takes < 15 ms here (the "reflex"); otherwise it goes to the node with the lowest expected `queue + work/capacity + RTT`, avoiding battery devices under 20%.
- `MeshNode` — TCP endpoint (java.net). Heartbeats carry capacity, queued work, battery and the peers it knows, so a mesh forms from one seed address. Failed sends fail over to the next best node, finally to itself. `stop()` closes the listener and forgets every peer.
- `MeshSim` — seeded simulation comparing Centralized, Octopus and Hybrid on identical workloads and outages.
- Android (`src/ultimateplus/.../mesh/`): `MeshManager` adds signed UDP broadcast discovery (port 47800, TCP 47801) and a battery reading; `MeshTool` draws this phone as a mantle and each trusted device as an arm that sways when alive, droops when cut, and carries a light when work travels along it.

**Consequences.** Only in Ultimate+ (needs INTERNET); offline Ultimate is unchanged. Off by default; a persistent "MESH LIVE · Disconnect" bar shows while it runs. Runs only while the app process lives (no foreground service yet), which is the honest Android background constraint.

## 3. Results (Phase 5)

### Real sockets, JVM (verified in `MeshTest`)
Three `MeshNode`s on loopback ("phone" deliberately slow, "laptop", "desktop"):
- the phone is given only the laptop's address and learns the desktop through gossip;
- a small task stays on the phone (reflex); a heavy task runs on the desktop and returns the correct answer (78,498 primes below 10⁶);
- the desktop is killed with no warning; the next heavy task fails over to the laptop and still returns the correct answer; after heartbeats lapse, the desktop is no longer tried;
- a node with a different pairing code is refused and learns nothing; `stop()` immediately refuses new connections.

### Simulation (not hardware) — `app/build/mesh-benchmark.txt`, seed 7, 2,000 tasks, 5 devices
```
With outages (coordinator asleep 8-16 s, phone-b drops twice):
Centralized              ok=0.858 mean= 1537.8ms p95= 4531.5ms msgs= 10359 retries=2155 energy=   29152
Octopus (decentralized)  ok=1.000 mean=  666.0ms p95= 2120.3ms msgs=  3054 retries= 220 energy=   40322
Hybrid                   ok=1.000 mean=  843.0ms p95= 2014.1ms msgs=  6588 retries= 366 energy=   45863

No failures (control):
Centralized              ok=1.000 mean=   44.6ms p95=  106.6ms msgs=  7900 retries=   0 energy=   20732
Octopus (decentralized)  ok=1.000 mean=   39.7ms p95=  143.0ms msgs=  2914 retries=   0 energy=   17726
Hybrid                   ok=1.000 mean=   48.7ms p95=  110.1ms msgs=  6792 retries=   0 energy=   20237
```
Reading it honestly: the octopus design wins on **resilience** (no single point of failure: 100% vs 85.8% completion when the coordinator sleeps) and on **messages** (~2.6× fewer), and is slightly faster on average. The centralized design has the **better tail latency** when nothing fails (p95 107 ms vs 143 ms) because its view of queues is exact, while gossip is up to 250 ms stale. Under outages, Octopus spends **more battery energy** (it leans on the battery laptop while the desktop sleeps). Hybrid buys the central tail latency at ~2.3× the messages. Energy is a proxy, not a measurement.

### Build and tests
- `gradle :app:testUltimateplusDebugUnitTest` — 234/234 pass (228 existing + 6 new).
- `gradle :app:testUltimateDebugUnitTest` — passes (offline edition unaffected).
- `gradle :app:assembleUltimateplusRelease` — builds; R8 keeps `MeshTool` (checked with `dexdump`). Signed with a local debug key only.

## 4. Install and permissions
Install Ultimate+ (`app-ultimateplus-release.apk`, sideload). Octopus Mesh uses permissions the edition already declares: `INTERNET` (TCP 47801, UDP 47800 on the local network), `CHANGE_WIFI_MULTICAST_STATE` (to receive broadcast beacons on some phones), `ACCESS_WIFI_STATE`. No new permissions were added. On each device: Octopus Mesh → type the same pairing code → Start. Devices on one Wi-Fi find each other; otherwise "Add by address". Wi-Fi client isolation (guest networks) blocks it.

## 5. Known limitations
- Not run on a phone. UDP broadcast behaviour differs by vendor and by Wi-Fi.
- (Fixed in 2.12.0: frames are now encrypted, section 9.) Originally frames were signed but not encrypted. Anyone who knows the pairing code is trusted. A 6-character code can be brute-forced offline from a captured frame; treat it as a LAN convenience, not strong security. Use a longer code.
- Only one kernel (prime counting) exists, so it can be checked; the mesh does not move arbitrary code or data between devices, on purpose.
- A foreground service now keeps the mesh alive with a Stop notification (2.10.0), but it is not started at boot: Android restricts that, so after a restart the mesh resumes when the app is opened.
- The simulator processes tasks in arrival order and uses an energy proxy.

## 6. Original experiments (Phase 4 candidates), ranked by evidence-to-cost
1. **Reflex threshold learning** — learn `REFLEX_MS` per device from measured RTT and calibration instead of a constant. Measurable directly with `MeshSim` (p95 vs messages).
2. **Arm autonomy under partition** — each arm keeps its own task log and re-merges when the mantle returns (CRDT task state). Measure completed tasks during a split.
3. **Battery-aware gossip rate** — slow heartbeats on low battery, fast when charging. Measure messages per completed task vs staleness errors.
4. **Needle tool routing across devices** — GhostNode's local model proposes a tool call; the mesh runs it on the device that has the capability (e.g. camera on the phone, compute on the desktop), with the same approval gate. Requires GhostNode pairing (its envelope schema already has `peerId`/`expiresAt`).
5. **Spatial handoff via ShatterOS** — the mesh's peer list drives ShatterOS's viewport layout so a dragged task visibly crosses screens to the device that executes it.
6. **Chromatophore status** — the orb's colour reflects mesh load and which sensors are active, an at-a-glance privacy indicator.

## 7. Roadmap (by evidence)
1. Run `MeshTest`-equivalent scenarios on two physical phones (the next unverified link); record RTT, task time, battery drain.
2. Foreground service with notification "Disconnect" action, matching the SSH server pattern.
3. Encrypt payloads (AES-GCM with a key from the pairing exchange) and replace the static salt with a per-pairing handshake (e.g. SPAKE2).
4. A small JVM/desktop runner for `MeshNode` (the code is already Android-free) and a browser node.
5. Experiments 1 and 3 above, measured in `MeshSim` first, then on devices.

## 8. Devices that help each other (2.10.0)
Everything below runs only after the owner starts the mesh; the parts needing two phones were **not run on hardware** (no second phone here). What was run: the JVM tests listed in section 8.6.

### 8.1 Who is here
`DeviceProfile` (model, kind: phone/foldable/tablet/computer, capabilities, `lends`) rides in every heartbeat, so each device knows what the others can do. `MeshNode` raises `PeerEvent.Joined/Returned/Lost` once per change (tested with real loopback sockets). `Roster` remembers devices across runs so a *new* device is announced ("New device joined your mesh") and a known one that wobbled off Wi-Fi is not.

### 8.2 Finding each other
1. Signed UDP broadcast (as before).
2. Android NSD (`_ultimate-mesh._tcp`), service name carries the key's public fingerprint: for routers that swallow broadcasts.
3. BLE presence (`MeshBle`/`MeshBleCodec`): a service UUID derived from the key (so only same-code phones recognise it) plus a 4-byte tag per device. It carries no data and no connection. Signal strength is shown only as a band (right next to / arm's reach / same room / far), never as metres. A phone seen by Bluetooth but unreachable over Wi-Fi is reported, with the fix (same Wi-Fi, or a hotspot on one phone).
Not done: Wi-Fi Direct / Wi-Fi Aware. Two phones on different networks with no hotspot cannot connect.

### 8.3 Lending a phone (remote recorder)
Commands (`CMD`/`ACK`, signed like every frame) are refused unless the owner switched on "Lend this phone". `rec.start` records accelerometer + gyroscope at 50 Hz into `MotionLog` (16 bytes a row, capped at two hours) under a partial wake lock; `rec.status` returns a live summary; `rec.stop` ends it; `rec.fetch` returns 375-row chunks (each fits a frame). Every use shows a Live-now session, the mesh notification, and a banner with Stop on the lending phone.
`RepCounter` finds sets of effort and counts repetitions: per-axis detrend, smoothing, activity windows, then peaks along the movement's principal axis (so one swing is one cycle however the phone is held). Tested on synthesised signals only (two sets, any orientation, walking cadence, still phone, jittery sampling). It is an ESTIMATE that depends on where the phone sat and has not been compared with a human count.

### 8.4 Distance by sound
`PeerRange` implements two-way ranging (BeepBeep): caller chirps up, callee down (17-20 kHz, 20 ms); each phone records for 1.8 s and finds both chirps in its **own** recording by matched filter on the analytic signal (sub-sample peak, earliest strong peak so a louder echo cannot win, own chirp blanked before looking for the other's). `distance = c/2 * ((a2-a1)+(b2-b1))/rate + (speakerToMic)/2`. No shared clock and no dependence on audio latency. The app runs it three times and reports the median and spread.
Synthetic results (`MeshRemoteTest`): within 2 cm from 0.3 to 7 m for start-up offsets up to 0.3 s either way; 3 cm with a 1.3x louder echo; 4 cm with 40 ppm clock difference. **Not verified:** real speaker/microphone response at 17-20 kHz, volume, phone cases, reverberant rooms, hands over the speaker. Many phones filter near-ultrasound; Android's own property is respected and an all-silent recording is reported as such. A phone in the background may have its microphone silenced by Android, which the app reports instead of guessing.
Audible to people? The band is above most adults' hearing; some young people, dogs and cats may hear it. The chirps are short and tapered.

### 8.5 Privacy and safety
Lending is off by default and separate from pairing. Pairing code stored sealed with a hardware-backed Keystore key, removable with Forget. From 2.12.0 frames are encrypted (section 9), so sensor summaries and recordings are unreadable on the network without the pairing code.

### 8.6 Tests
`MeshRemoteTest` (23): profile round trip, one Joined/Lost event per change, Returned, commands reach the handler, non-lending refusal with reason, no-handler refusal, stranger refused, roster, motion chunk round trip + frame size + cap, five repetition scenarios, eight ranging scenarios. `MeshBleCodecTest` (3).

### 8.7 Next
Run on the two phones (Z Fold + Z Flip): first the join alert and Record motion, then Distance at 0.5 m / 2 m / 5 m with a tape measure, then record a real set of squats against a counted number. Then: an IMU-based exercise label, two phones as an array for Echo (docs/ECHO_RESEARCH.md experiment 4), sharing a World Scan walk from a second phone.

## 9. Sealed transport and signals (2.12.0)
* **Wire format `om2`**: `om2|b64(nonce)|b64(AES-256-GCM(TYPE|from|seq|sentAt|payload))`. Everything, including device ids and frame types, is inside the ciphertext; the GCM tag authenticates it; a random 96-bit nonce per frame. The sealing key is derived from the pairing key separately from the fingerprint and Bluetooth identifiers. `om1` frames are refused. PBKDF2 rounds raised from 20,000 to 120,000. Tests: nothing readable on the wire, two sealings differ, any changed character is refused, stale and replayed frames refused, wrong code refused.
* **Still true**: no forward secrecy; a short code can be guessed offline from one captured frame (the screen now warns when a code is weaker than about 40 bits). A per-pairing key exchange (SPAKE2 or similar) is the next step.
* **Signals**: joins, returns, losses and Bluetooth nearness crossings are published on the senses bus (`mesh:device_joined`, `device_back`, `device_lost`, `device_near`, `device_away`) and are pickable triggers in Rules.
