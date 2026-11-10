# BGA Control — the biogas plant as a page of its own (no issue)

Plan for integrating **PnH BGA Control** (`FS25_PnH_BgaControl`, Kastor/T4xs, written against 0.0.9.0;
source in `references/FS25_PnH_BgaControl/`) into the Production app, and for giving a Pumps & Hoses
plant a page of its own whether that mod is installed or not.

Status: **all three steps built; single player validated** (2026-11-07, on top of `main` @ `aa64bf0`).
production.json is v3. The steppers work in single player, and an SP capture with BGA Control is
pinned as `examples/json/production/pnhControl/production.json` (`ProductionModelTest`). Still open:
the MP-client path and the other in-game checks at the bottom.

What changed against the plan while building it:

- **No PIT role.** The pre-pit / liquid feed stock is read the way the mod's own screen adds it up
  (`control.stock.liquid`: liquid manure in the bunkers and in any non-fermenter storage of the plant),
  so the plant page does not depend on PumpsAndHoses.lua learning a new role. Whether the DLC's
  `parts[]` misses a slurry pit is still worth a look in game.
- **Percentages are whole percent on the wire** (agitator, recirculation, activity, efficiency); the
  command converts back to the mod's fractions.
- **Steppers keep a local value between exports**, reset whenever the export brings a different one,
  so two quick taps make two steps instead of one.

Decided with the user before writing this:

- **Where:** inside the Production app. The construction header ("BGA (1)") that today only groups
  the plant's two production points becomes a **selectable entry** of its own, opening a plant page.
  No separate app.
- **What it looks like:** a **flow diagram** in the spirit of the mod's in-game control screen
  (inputs → fermenter → outputs), not a stack of cards. It needs a compact layout for portrait and
  phone widths.
- **Without BGA Control** the plant page still exists, drawn from what production.json already
  carries (parts, fills, utilizations, the two production points' on/off). BGA Control adds the
  process readings and the setpoint controls on top.

---

## What BGA Control is, as far as we care

It turns the DLC's plant into a simulated process: tank temperature with thermal inertia, dry matter
(TS), pH, bacterial activity (0.10–1.00) with a 1.5 h stress buffer before it starts dying, an
agitator with rheology, digestate recirculation, a CHP switch and separator throughput. It feeds the
fermenters from the bunker at a **silage flow** and from the pit at a **liquid flow** (L/h), by writing
the DLC's `distributionsPerFillType` on every placeable of the plant.

### Where its state lives

All on the plant's **root placeable** (the one `resolveSandboxRoot()` already gives us), so it is
readable without crossing into the mod's environment:

| Field | Meaning |
| --- | --- |
| `root.bgaControlData.targetTemperature` | setpoint, 30–55 °C |
| `root.bgaControlData.actualTemperature` | tank temperature, °C (moves 0.45 °C / 10 in-game min) |
| `root.bgaTargetSilageFlowLph` (fallback `bgaControlData.silageFlow`) | bunker feed, L/h, ≤ 18 000 × fermenters |
| `root.bgaTargetLiquidFlowLph` (fallback `bgaControlData.liquidFlow`) | pit feed, L/h, ≤ 24 000 × fermenters |
| `root.bgaControlData.agitatorIntensity` | 0.0–1.0 |
| `root.bgaControlData.recirculationRate` | 0.0–0.5 |
| `root.bgaControlData.separatorFlow` | 0–3000 L/h |
| `root.bgaControlData.bacterialActivity` | 0.10–1.00 |
| `root.bgaStressTimer` | seconds of accumulated stress, 0–10 800; **> 5400 = biology dying** (`root.bgaBioIsDying`) |
| `root.bgaControlData.live{Methane,Digestate}Lph`, `live{Bhkw,Torch}Util` | **MP client only** — the server's numbers, received every 5 s |

`bgaControlData` is nil until the mod's first update tick on the server, or until a client has
received the first broadcast (`root.bgaStateReceived`). A nil block means "not ready yet", not
"not installed".

### What it computes and never stores

The **process mode** ("Multi-substrate", "Co-fermentation", …), the **status line** ("Mesophilic
optimal", "Acid crash pH 6.8", …), **TS**, **pH**, the **efficiency** fallback and the **operating
costs** are all computed by `BgaControlDialog:calculateFermenterDiagnostics()` each time the dialog
draws, and the mode/status come back as **its l10n text**, which our environment would resolve to
the key (the same reason PumpsAndHoses.lua does not export the DLC's utilization messages).

So: **port that decision tree** into our integration and export **tokens**; the app words them. It is
~80 lines of pure branching over values we can read. Calling the dialog method with a faked `self`
would also work today but hands back sentences, and breaks the moment the dialog's fields change.

The inputs to that tree come from the mod's **pure helpers**, which we call rather than copy (they
know the 15 substrates and the four display groups, and that table is the part most likely to change
between versions):

- `BgaExtensionMain.getFermenterSubstrateLevels(root, pp)`, `.getFermenterClusterData(root)`,
  `.calculateAgitatorRheology(agit, ts, count)`, `.getSandboxUtilization(root)`,
  `.getLiveBhkwIncome(root, util)`, `.getSeparatorForBga(root)`, `.getSeparatorFlowLph(root)`
- `BgaAdditionalSubstrates.getTotalLevel`, `.getDisplayGroupLevelFromLevels`,
  `.getWeightedDryMatter`, `.getWeightedAcidLoad`, `.getSubstrateByName`,
  `.getStorageSubstrateLevel`, `.getFermenterPoolCapacity`
- `BgaRuntimeFixes.getEfficiency(root)` — the server-authoritative efficiency the recipe runs at
  (synced to clients through the rates in the settings event); the dialog's formula is only its
  fallback.

**Mod-environment isolation** ([[mod-env-isolation]]): all three tables are globals in the mod's own
environment, nil from ours. Reach them through `_G[MOD_NAME]` the way `Invoices.lua` does, with
`MOD_NAME = "FS25_PnH_BgaControl"`. Detect with `g_modIsLoaded[MOD_NAME]` **and** the table being
present. The placeable fields above need none of this.

### How it writes

Every dialog button ends in `saveCurrentValuesToBga()`:

1. write `bgaControlData` (+ `bgaTarget{Silage,Liquid}FlowLph`),
2. `BgaExtensionMain.applyFlowSettingsToCluster(root)`,
3. `BgaSetSettingsEvent.sendEvent(root, temp, silage, liquid, agit, recirc, separator)`,
4. on the server: `bgaRecipeDirty = true` and `updateBgaProductionRecipe(pp)` on every fermenter.

`sendEvent` is a **no-op in single player** (it returns unless `isMultiplayer`), so SP relies entirely
on steps 1, 2 and 4. On an **MP host** it broadcasts the state; on an **MP client** it sends
`BgaSetSettingsEvent` to the server, which checks farm access, clamps, applies and re-broadcasts.
The event always carries **all six setpoints** — there is no per-field write.

The separator has its own path (`setSeparatorThroughput` → `applySeparatorFlowToCluster` + the same
event). Plant on/off is `setProductionState` on the fermenter lines and CHP on/off is
`setProductionState(“POWERPLANT”)` — **both already exist** as our `setProductionEnabled` on the two
merged production points.

---

## Step 1 — export (mod + shared model)

**`src/integrations/PnhBgaControl.lua`** (`VDT.PnhBgaControl`), sourced after `PumpsAndHoses.lua`.
Not a registry stage: `VDT.PumpsAndHoses.collect` calls `VDT.PnhBgaControl.control(root)` per
construction and sets the result as `construction.control` (nil when the mod is absent or the block is
not ready).

Wire shape — `constructions[].control`, all optional:

```jsonc
"control": {
  "temperature":   { "target": 40.0, "actual": 40.0 },
  "feed":          { "silage": 5400, "silageMax": 18000, "liquid": 4000, "liquidMax": 24000 },
  "agitator": 0.9,
  "recirculation": 0.1,
  "separator":     { "present": false, "flow": 0, "state": "NONE" },  // NONE|INACTIVE|OPERATING|FULL|NO_SILO|STANDBY
  "biology":       { "activity": 1.0, "stress": 0, "stressLimit": 5400, "dying": false },
  "dryMatter": 10.1,
  "ph": 7.4,
  "efficiency": 0.99,
  "mode":   "MULTI_SUBSTRATE",   // INACTIVE|LIQUID_MONO|SOLID_MONO|SOLID_MULTI|RECIRCULATION|MULTI_SUBSTRATE|CO_FERMENTATION|MANURE_LIQUID|BEET_FERMENTATION
  "status": "MESOPHILIC_OPTIMAL",// STOPPED|STARTUP|COLD|ECONOMICAL_STABLE|RECIRC_STABLE|DRY_OK|THERMOPHILIC|MESOPHILIC_OPTIMAL|BUFFERING|NO_FEED|SCUM_DRY|HIGH_VISCOSITY|HIGH_SHEAR|ACID_CRASH|AMMONIA_INHIBITION
  "flows":         { "methane": 889, "digestate": 4285 },          // L/h
  "chp":           { "load": 0, "income": 0 },                     // %, currency/h
  "torch": 0,                                                      // %
  "fermenterFill": { "level": 326100, "capacity": 326100 },        // the mod's pooled capacity, not the DLC's
  "costs":         { "perHour": 119 }
}
```

Notes for the collector:

- **Tokens, not text**, for mode, status and separator state (see above). `STARTUP` carries the
  activity percent the app already has.
- **MP client:** methane/digestate/CHP/torch come from `bgaControlData.live*` (the server's numbers);
  computing them locally is what the dialog does on the server only. TS/pH run on the client's
  storage levels, which the engine syncs. Separator state is likely host-only — check, and leave it
  `null` where the client cannot know.
- **Do not smooth** the CHP/torch readings the way the dialog does (0.4/0.6 blend per frame); report
  the raw value, as the existing `parts[].utilization` already does.
- **The pit.** The mod's screen has a liquid-manure "Vorgrube" that is not one of the DLC's roles
  (`counts.pit`: a `spec_silo` placeable whose sandbox root is the plant). Check in game whether our
  `parts[]` misses it; if so, add a `PIT` role to `PumpsAndHoses.lua` with its fills, which helps the
  plant page even without BGA Control.
- **Fermenter substrates.** BGA Control widens the fermenter storage to 15 substrate fill types in
  four display groups. Check what the existing fermenter production point now exports as its storage
  rows; the plant page wants the four groups, not fifteen rows.

Shape changes: `ProductionExporter.VERSION` 2 → 3, `src/model/` annotation, Kotlin
`ConstructionControl` (+ nested types, token fields as `String` so an unknown token renders
neutrally) on `Construction.control`, `ModFormat.expected`, `VdtParser` lenient as always.
**Fixtures are real captures** (SP host + MP client, with the mod; `examples/json/production/`), plus
the existing captures proving the block is absent without it. Lua specs for the ported tree and the
env lookup; `ProductionModelTest` over the new captures.

---

## Step 2 — the plant page (read-only)

**List:** the construction header in `ProductionMasterDetail` becomes an `OwnedRow`-style selectable
entry (plant name, subtitle = mode/status word when BGA Control reports, else the part count). The
fermenter and cogeneration rows stay beneath it, unchanged. Selection keeps working by id: use the
construction id, which never collides with a production point's (both are placeable uniqueIds, but
the root fermenter's merged point has its own; verify, else prefix `construction:`).

**Detail: `PlantDetail`** in a new `panels/PlantDiagram.kt`. The diagram, wide layout:

```
 BGA (1)                                              [Plant ON]
 ┌ Bunkers (2) ───┐   ┌ Fermenters (1) ─────────┐   ┌ Cogeneration [ON] ┐
 │ 188 304 L  71% │   │ Mesophilic · optimal    │   │ load 0%  0 /h     │
 │ feed  5 400 L/h│──>│ efficiency 99%          │   └───────────────────┘
 └────────────────┘   │ temp 40.0 °C (set 40)   │──> methane 889 L/h ──> Torch: idle
 ┌ Pit ───────────┐   │ agitator 90%            │
 │ 49 742 L    1% │──>│ TS 10.1 %   pH 7.4      │──> ┌ Digestate ──────┐
 │ feed  4 000 L/h│   │ fill 326 100 L  100%    │    │ 7 113 L    0%   │
 └────────────────┘   │ biology 100% · stable   │    │ recirc 10%      │
                      └─────────────────────────┘    └─────────────────┘
 Separator: none        Costs 119 /h
```

- **Three columns** (inputs | fermenter | outputs) with connectors drawn on a `Canvas` behind the
  boxes. Flow direction is shown by **line style + an `Icon` arrowhead**, never a `▶` character
  (wasm tofu). A connector carrying flow is solid, an idle one dashed — the difference is shape, not
  hue. Left-to-right is fine: this is a process diagram, not a side view of a machine, so the
  driving-direction rule does not apply.
- **Compact layout** below a width threshold: one column top to bottom (inputs, fermenter, outputs),
  connectors vertical. Same boxes, so the box composables take no layout knowledge.
- **Words carry every state.** The mod paints status green/amber/red; we print the status word and,
  for the bad ones, a warning `Icon` beside it. Fill bars use the existing `ProgressBar`; the "low
  bunker / nearly full tank" warnings become words too.
- **Biology buffer:** when stress > 0, show how much of the 1.5 h buffer is left ("biology dying in
  42 min" / "biology dying") — the one thing the in-game screen does not say outright.
- **Without BGA Control:** the same diagram with only the DLC's data — bunker/tank fills, fermenter
  and CHP utilization words (`utilizationWord`), torch idle/burning, the two on/off states. The
  fermenter box is smaller then, not empty.
- The existing `ConstructionCard` under each production point stays; the plant page supersedes it as
  the place to look, but a reader on the fermenter entry should still see the plant context.

App strings EN + DE in `ProductionStrings.kt` (mode, status and separator tokens, box titles, the
buffer sentence). Test: a wasm test that every exported token has a word in both languages.

---

## Step 3 — controls

**Command:** `setBgaSettings { constructionId, temperature, silageFlow, liquidFlow, agitator,
recirculation, separatorFlow }` — absolute, all six, because that is what the mod's event carries.
The app always sends the full current set with one value changed.

**`src/command/BgaControl.lua`:** resolve the root by construction id (own farm, as
`ProductionControl` does), clamp to the mod's own limits (see the table above; flows scale with the
fermenter count), then mirror `saveCurrentValuesToBga`:

- write the six values onto `bgaControlData` / `bgaTarget*FlowLph`,
- `BgaExtensionMain.applyFlowSettingsToCluster(root)` and, if the separator changed,
  `applySeparatorFlowToCluster(root, flow)`,
- on the server (SP, MP host): mark the recipe dirty and `updateBgaProductionRecipe` on every
  fermenter; then `BgaSetSettingsEvent.sendEvent(...)` (no-op in SP, broadcast on an MP host),
- on an MP client: `sendEvent(...)`, which goes to the server — but only once `root.bgaStateReceived`
  is true, else the mod's own guard drops it; refuse the command and say why rather than send
  defaults over the server's state.

**App:** −/+ steppers on the boxes, at the mod's step sizes — temperature 1 °C, feeds 200 L/h,
agitator and recirculation 10 %, separator 100 L/h. Stepper buttons are `ToolButton`s, hidden (slot
kept) when the control is refused, as elsewhere. Plant and CHP switches reuse
`setProductionEnabled` on the two merged points. The value shown is always the **exported** one, not
an optimistic local copy — an MP client's write comes back on the next 5 s broadcast, and the
stepper's next press must build on what the game says.

Tests: `ClientMessageTest`, `CommandWriterTest`, a Lua spec for clamping and the SP/MP branches.

---

## In-game checks this needs

Done in single player: the steppers apply; two fermenters double the feed limits and the pooled
volume behind one fermenter box; a connected separator reports flow and state (both captured under
`examples/json/production/pnhControl/`). The second capture also showed a feed set against an empty
bunker, so a feed connector now flows only with stock behind it. A bunker reports a row for every substrate
BGA Control lets it take, most at 0 L, so the plant card and the plant page list only the fills
holding something.

Still open:

- MP client (dedicated server): the block appears after the first broadcast, the steppers reach the
  server, and a write before that broadcast is refused rather than lost.
- Separator state on an MP client (expected absent — the flags live on the server's object).
- The pit: does `parts[]` miss a slurry pit the plant has? (The page does not depend on it.)
