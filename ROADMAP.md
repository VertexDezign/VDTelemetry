# Roadmap

Post-migration improvements to the telemetry pipeline. These are follow-ups to the
Kotlin rewrite (see `VDTerminal/kotlin-migration-plan.md`), focused on latency, a
command back-channel, and serialization.

**Current data flow:** the mod (`vdTelemetry/`) writes `vdTelemetry.xml` to the FS25 user
dir on a timer → the server (`VDTerminal/server`) watches the directory, debounces, parses
via `shared`, and broadcasts `ServerMessage.Telemetry` over WebSocket → the app renders it.

**Baseline already changed:** the mod write interval was dropped from 500 ms to **100 ms**
(`VDTelemetry.lua` `update()` timer) and validated on a real save (~50–70 fps, no FPS delta,
no rhythmic stutter in the F8 script profiler). The items below build on that.

---

## Near-term

### 1. Configurable write interval (mod)

- **What:** move the hard-coded 100 ms write interval into `vdTelemetrySettings.xml`
  (e.g. `VDTS.export.intervalMs`, default 100).
- **Why:** per-machine flexibility — low-end setups can back off; power users can push it.
- **How:** add the field to `writeDefaultSettings()` / `loadSettingsFromFile()`
  (`VDTelemetry.lua`) and read it into the `update()` timer threshold. Clamp to a sane
  floor (sub-frame intervals are pointless: the game frame is ~16–33 ms).
- **Note:** settings are read once at init (`loadSettingsFromFile`), so this is
  **apply-on-restart**, not live. Acceptable for a tuning knob.

### 2. Reduce server debounce

- **What:** drop `XmlSource.DEBOUNCE_MS` from **150 ms** to **~40 ms** (constant), or expose
  it as a `VDT_DEBOUNCE_MS` env var to match the existing config style (`VDT_PORT`, …).
- **Why:** the 150 ms `delay()` is a flat latency floor now *larger* than the mod's write
  interval, and it coalesces the 10 Hz stream down to ~6–7 Hz. It re-adds most of the
  latency the mod-side change removed.
- **Why a constant (not derived from the interval):** the debounce only needs to cover the
  event burst + write time of a **single** `XMLFile.save`, which is ~constant regardless of
  how far apart saves are. A torn read already self-heals via the `try/catch` +
  last-good-state in `reparse()`. The only real constraint is `debounce < interval`, which
  ~40 ms satisfies for any realistic interval.

### 3. Client-side interpolation (app)

- **What:** tween fast-changing values (speed, rpm, player position/heading) between samples
  in the Compose app instead of snapping to each update.
- **Why:** decouples *perceived* smoothness from the write/broadcast rate. Smooth rendering
  over modest-rate samples reads as continuous — this is what lets us keep write rates
  conservative without the dashboard looking choppy. Reduces the pressure to chase high
  sample rates at all.
- **How:** `Animatable`/`animate*AsState` on the values; the map panel already plans an
  `Animatable` offset for the player marker (migration plan Phase 8).

---

## Larger features

### 4. Command back-channel (app → mod)

Let the app send commands to the mod (toggle lights, set cruise speed, …).

- **Transport: a file, not a pipe.** Server writes `commands.xml` (or `.json`) into the same
  user dir; the mod polls it in `update()`. See "Rejected approaches" for why not pipes.
- **No duplicate execution:** each command carries a monotonic `id`; the mod tracks
  `lastCommandId` and executes only ids greater than it. Tolerant of the server rewriting
  the whole file; keep a small ring of recent commands so a missed poll doesn't drop
  intermediate messages.
- **Atomicity is the *server's* job:** losing a command is bad (unlike lossy telemetry), so
  the server — native Linux, real `rename(2)` — writes temp + atomic rename. The mod side
  (which lacks `os.rename`) doesn't need to write anything back.
- **Mod-side reads:** guard with `fileExists` before `XMLFile.load`; poll on the same timer
  cadence (command latency ≈ poll interval, fine for button presses). Execution flows
  through the vehicle API / `FS25_additionalInputs` action events.
- **Protocol:** mirrors the migration plan's `ClientMessage` sealed interface (app → server
  over WS → server → `commands.xml` → mod).
- **Constraint:** telemetry (and by symmetry command handling) is **client-side only** —
  `exportEnabled` and `xmlFileLocation` are gated on `g_dedicatedServerInfo == nil`. The
  file lives on the *client's* machine, not the dedicated server box.

### 5. Migrate serialization XML → JSON

- **What:** replace the on-disk XML with JSON on both sides.
- **Why it fits:** the server already speaks JSON on the wire (`ServerMessage` via
  kotlinx.serialization). JSON on disk means **one `@Serializable` model for file-in and
  WS-out, and xmlutil can be dropped** from `shared`. Genuine simplification, not just taste.
- **Caveats:**
  - **No engine JSON writer** in the FS Lua API. Vendor a small pure-Lua encoder or
    hand-build the string. For a small fixed-shape payload, hand-building is trivial and
    faster than `XMLFile`; **escape** untrusted fields (PDA `filename`/paths contain
    backslashes and quotes).
  - **`os.rename` is unavailable in the sandbox**, so the mod can't do atomic temp+rename.
    Not a blocker for telemetry: it's a lossy stream — a torn read fails to parse, the
    server keeps last-good-state, and the next sample lands one interval later.
  - **Loses the XSD as the published contract.** Replace it with the shared Kotlin model +
    `examples/*` fixtures as the contract (the migration plan already leans this way). Update
    the example fixtures and parse tests accordingly.
- **Sequencing:** independent of the latency work. Natural first step is to make any new
  **fast/command channel JSON from day one**, then migrate the main document later.

---

## Deferred / conditional

### Fast/slow channel split

Split telemetry into a small high-rate channel (speed, position, heading, rpm) and a
low-rate channel (implements, fill units, weather, PDA). **Shelved** — a single 100 ms
channel showed no FPS cost on a heavy save, so the extra complexity (two watchers, two
writes, server-side merge) isn't justified yet. Revisit only if a single channel proves too
heavy on the write or reparse side.

### Staleness / connection detection

Detect "no telemetry for N intervals → paused/disconnected" and surface it in the UI. If the
server needs to know the mod's cadence for this, **emit the interval on the telemetry root**
(`<VDT version="1" interval="100">`) — it rides the contract the server already parses, with
no second file and no coupling to the mod's private settings. (This is the *only* good reason
to share the interval with the server — **not** debounce, which stays a constant.)

---

## Decisions & rejected approaches

- **Named pipe for the command channel — rejected.** Although `io.open` works in the FS Lua
  sandbox (a pipe mod writes telemetry with `io.open("\\\\.\\pipe\\…", "w")`), *reading*
  commands is the hard part: stdlib `io` offers only **blocking** `file:read` with no
  select/poll and no `PIPE_NOWAIT`, so a read on the render thread stalls the game. Writing
  is the easy direction; reading is what breaks the frame loop. On Linux there's also the
  **Wine boundary**: a Win32 named pipe lives in the Wine prefix's object namespace, which a
  native Linux server can't join without a Wine-side agent. The file approach sidesteps both,
  because the user dir is a real Linux path shared by both processes.
- **Server deriving debounce from `vdTelemetrySettings.xml` — rejected.** Debounce isn't a
  function of the write interval (see item 2), and it would couple the server to the mod's
  private config schema and force it to locate/watch a second file. Debounce stays a small
  constant (or its own env var).
