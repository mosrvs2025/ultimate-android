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

- `MeshWire` — one signed line per frame: `om1|TYPE|from|seq|sentAt|b64(payload)|HMAC-SHA256`. Key = PBKDF2(pairing code). Rejects bad signatures, frames older than 60 s, and replays (`ReplayGuard`). Payloads are authenticated, **not encrypted**; only non-secret work travels over it.
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
- Frames are signed but not encrypted. Anyone who knows the pairing code is trusted. A 6-character code can be brute-forced offline from a captured frame; treat it as a LAN convenience, not strong security. Use a longer code.
- Only one kernel (prime counting) exists, so it can be checked; the mesh does not move arbitrary code or data between devices, on purpose.
- No foreground service: Android will stop the mesh when the app is backgrounded for long.
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
