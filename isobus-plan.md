# ISOBUS panel — implement-aware terminal (issue #58)

Plan for a panel that shows *what the attached implement actually is*, the way an ISOBUS terminal
does: the tractor lends the screen, the implement decides what goes on it.

Status: **planned, nothing built**. Written 2026-08-02 against `main` @ `2be60ea` (mod `VERSION 8`).

Scope for round 1, decided with the user before writing this:

- **Classes:** tillage + crop care only — **sowing machine, sprayer/spreader, cultivator, plow**.
  Grass/baling (baler, wrapper, mower, tedder, windrower), transport (trailer, forage wagon, mixer
  wagon) and the front loader are explicitly **deferred to a round 2**. The issue itself scopes round
  1 to "equipment attached to tractors" and leaves harvesters for later; this narrows it further.
- **Read-only.** No new commands. The existing lower / fold / activate buttons stay where they are
  (`RigSlotPanel`); nothing in this round mutates game state. Seed change, plow rotation and doubled
  amount are round-2 candidates and are noted below where their setters live.

---

## Where this stands

The aspect layer already carries a surprising amount of what a terminal needs, and all of it rides
the **main telemetry channel** — so this feature needs **no new channel**: no file, no watcher, no
store, no `ServerMessage` variant, no fixture plumbing. That is the whole reason it was picked ahead
of the finance and mission apps.

Already collected, per object, by `src/collect/aspects/` (see `Aspects.apply`):

| Aspect | What it gives a terminal |
| --- | --- |
| `fillUnits` | tank/hopper levels, capacity, `usage`, fill type, `precision`, BAR vs STEP |
| `workWidth` | live width **plus the per-section shutoff bar** (`sections[].active/side`) |
| `workAreas` | per-area `active` / `processing` + the footprint in the shared normalized map frame |
| `workMode` | the discrete mode a tool is switched to, with its XML name |
| `foldable` / `lowered` / `isTurnedOn` | transport vs work state |
| `wearable` | damage / wear / dirt |
| `discharge` / `tipping` / `pipe` / `cover` | unloading, with the engine's own "why not" reason |
| `baleCounter`, `harvest` | round-2 classes, already there |
| `precisionFarming` | real application rates **when PF is installed** (`src/integrations/`) |

`RigSlotPanel` renders this today, and it is deliberately **type-agnostic**: name, condition,
fold/power/raise, fill units, the section bar, work areas, PF. That is the right thing for a slot
tile and it should not change. **The ISOBUS panel is the type-aware complement** — same data plus
the per-class state the aspect layer has never collected.

The gap is exactly the machine-specific state:

- **what crop is in the seeder** — not exported at all;
- **what is in the sprayer and at what rate** — only via PF today, nothing in the base game;
- **which way the plow is turned** — not exported;
- **how the cultivator is configured** — not exported.

---

## Core design: sections keyed by aspect presence, not one profile per type

The obvious reading of "depending on what is attached we display corresponding stuff" is a switch on
the implement's `type` string. **Don't.** Two reasons:

1. `type` is the vehicle type name from the game's type manager. It is modder-defined and open-ended
   (`sprayerFertilizer`, anything a mod registers), so a switch on it silently falls through to the
   default for exactly the machines a terminal is most interesting on.
2. **An implement is not one class.** `FertilizingSowingMachine` has `spec_sowingMachine` *and*
   `spec_sprayer`; `FertilizingCultivator` has cultivator + sprayer. A single-profile switch has to
   pick one and drop the other.

So: the panel is **a stack of sections, each keyed to the presence of its aspect subtree** — render
the sowing section iff `implement.sowing != null`, the spray section iff `implement.spraying != null`,
and so on. This is the rule the aspect layer already follows end to end ("a nil assignment leaves the
key out of the Lua table, so absent aspects become absent JSON keys"), it composes correctly on
combination machines, and it degrades to "nothing specific to show" without a special case. It is
also how real ISOBUS works — by function, not by machine name.

`type` stays useful as a **label and icon hint**, not as the dispatcher.

---

## The four new aspects

All four are `src/collect/aspects/` collectors in the existing shape: pure, return `nil` when the
spec is absent, wired into `Aspects.apply`, mirrored into `src/model/AspectModel.lua` and `Model.kt`.
Engine references are `file:line` into the extracted source bundled with `fs25-modding-skill`
(`references/lua-source/vehicles/specializations/`).

### `sowing` — `spec_sowingMachine`

The headline number for the whole feature: **which crop is in the hopper**.

| Field | Source | Note |
| --- | --- | --- |
| `fruitType` | `spec.seeds[spec.currentSeed]` → `g_fruitTypeManager` | the selected crop; the title is what the panel prints |
| `seedIndex` / `seedCount` | `spec.currentSeed`, `#spec.seeds` | "3 of 7" — says a choice exists |
| `fillType` | `getSowingMachineSeedFillTypeIndex()` (`:342`) | joins to the fill unit |
| `changeAllowed` | `getIsSeedChangeAllowed()` (`:336`) — `spec.allowsSeedChanging` (`:157`) | greyed vs. live in a round-2 control |
| `directPlanting` | `spec.useDirectPlanting` (`:105`) | direct-sow vs. needs a seedbed |
| `usageScale` | `spec.seedUsageScale` (`:175`) | consumption multiplier |
| `working` | `spec.isWorking` / `spec.isProcessing` (`:107,108`) | |

Also worth carrying: `spec.warnings` and the `showFruitCanNotBePlantedWarning` /
`showWrongFruitForMissionWarning` / `showWaterPlantingRequiredWarning` family (`:93-216`) — these are
the engine's own "you are about to waste a hopper of seed" flags and are precisely the thing a
terminal should surface. Treat them as a **round-1 stretch**, not core.

**MP:** `currentSeed` is stream-synced (`onReadStream`/`onWriteStream`, `:243-250`), so the crop is
readable on a client. Good.

**Round-2 control:** `setSeedIndex` / `changeSeedIndex` (`:299,314`) already send
`SetSeedIndexEvent`, so a command handler would not need its own MP event.

### `spraying` — `spec_sprayer`

Covers sprayers, fertilizer spreaders, slurry tankers and manure spreaders — the spec does all four.
(And **solid** fertilizer and lime spreaders: the game has no separate spreader specialization. See
"How the four aspects landed" finding 7 for why `kind` ended up splitting five ways rather than three.)

| Field | Source | Note |
| --- | --- | --- |
| `kind` | `spec.isSlurryTanker` / `isManureSpreader` / `isFertilizerSprayer` (`:204-206`) | the machine's own classification, mutually exclusive |
| `sprayType` | `getActiveSprayType()` (`:599`) | **see the two-tables trap below** |
| `doubledAmount` | `spec.doubledAmountIsActive` (`:187`) | the one rate control the base game offers |
| `doubledAmountAvailable` | `getSprayerDoubledAmountActive(nil)`'s **second** return | `false` on a slurry tanker — don't show a toggle that can't apply |
| `active` | `getAreEffectsVisible()` (`:435`) — `g_time < lastSprayTime + 100` | actually spraying, not merely on |
| `usagePerMin` | `spec.workAreaParameters.usagePerMin` (`:916`) | **verify on a client, see below** |
| `allowsSpraying` | `spec.allowsSpraying` | |

Three traps, all worth writing into the collector's header comment:

- **Two different "spray type" tables.** `spec.sprayTypes` (and what `getActiveSprayType()` returns)
  are the **vehicle XML's** entries — `fillTypes`, `fillUnitIndex`, `usageScale`. That is *not*
  `g_sprayTypeManager`'s spray type, which is where `name`, `isFertilizer`, `isLime`, `isHerbicide`
  and `litersPerSecond` live (`SprayTypeManager.lua:61-68`). `workAreaParameters.sprayType` holds the
  **manager index**. The panel wants the manager's record; reach it via
  `g_sprayTypeManager:getSprayTypeByFillTypeIndex(fillType)`.
- **`getActiveSprayType()` returns nil** when nothing in the tank matches a declared type — an empty
  or wrongly-filled machine. Absent, not a default.
- **`usagePerMin` may be server-only.** It is written in `onStartWorkAreaProcessing` (`:916`), and
  work-area processing is driven from the server side of the update tick. This is the same class of
  problem the PrecisionFarming integration already documents about `ExtendedSprayer` ("which half of
  it survives multiplayer"). **Check it on a real client before shipping it**; if it reads 0 there,
  emit it only when non-zero rather than publishing a permanent lie, and let PF's rates carry the
  number when PF is installed.

A **derived l/ha** is deliberately *not* in round 1. `getSprayerUsage` (`:472`) is
`scale * litersPerSecond * self.speedLimit * workWidth * dt * 0.001` — it scales by the machine's
*speed limit*, not its actual speed, so turning it into a per-hectare rate requires reasoning about
what the engine is actually holding constant. PF already publishes a true rate. Settle this in round
2 with the formula checked against `processSprayerArea` (`:290`), or not at all.

### `plow` — `spec_plow`

| Field | Source | Note |
| --- | --- | --- |
| `rotated` | `spec.rotationMax` (`:147`) | boolean: which way the bodies are turned. **The** plow readout |
| `rotationAllowed` | `getIsPlowRotationAllowed()` (`:339`) | false mid-fold |
| `canToggleRotation` | `getCanTogglePlowRotation()` (`:349`) | adds lowered + powered |
| `limitToField` | `getPlowLimitToField()` (`:363`) | |
| `forceLimitToField` | `getPlowForceLimitToField()` (`:366`) | when true the setting is not the player's to change |

**MP:** `rotationMax` is stream-synced (`:205-224`). Good.

**Round-2 control:** `setRotationMax` / `setRotationCenter` (`:283,301`) take `noEventSend` and own
their event.

### `tillage` — `spec_cultivator`

The thinnest of the four; it exists so a cultivator/power harrow/subsoiler isn't the one common
implement with no section at all.

| Field | Source | Note |
| --- | --- | --- |
| `kind` | `spec.isSubsoiler` / `spec.isPowerHarrow` (`:65,66`) | else plain cultivator |
| `deepMode` | `spec.useDeepMode` (`:67`) | |
| `limitToField` | `spec.limitToField` (`:74`) | |
| `enabled` | `spec.isEnabled` (`:69`) | the engine switches this off itself (`:173-189`) |
| `working` | `spec.isWorking` (`:115`) — `0.5 < getLastSpeed()` | |

**MP:** `Cultivator` registers **no** `onReadStream`/`onWriteStream` (`:42-51`) — none of this is
synced, so on a client it is whatever `onLoad` left behind. Verify every field on a client; drop the
ones that don't survive rather than shipping stale values. This is the weakest of the four and it is
fine for it to end up as two fields.

Note `FertilizingCultivator` is a *separate* spec that adds a sprayer — it carries almost no state of
its own (`spec.needsSetIsTurnedOn` and nothing else), so the `spraying` section covers it. No
collector needed.

---

## Mod → app sync

Standard for this repo, and the reason the checklist matters: **the Lua model, `Model.kt` and the
fixtures move together.**

1. Four collectors in `src/collect/aspects/`, namespaced `VDT.*`, wired into `Aspects.apply` in
   `Model.kt` field order.
2. `src/model/AspectModel.lua` gains the four `---@class` shapes; `ImplementModel.lua` and
   `VehicleModel.lua` gain the four optional fields (a self-propelled sprayer needs them on the
   vehicle too — the same reason `RigSlotPanel.slotState()` reads `workWidth` off the vehicle).
3. `VDTelemetry.VERSION` **8 → 9**.
4. `Vehicle.kt`: four `@Serializable` data classes + four nullable fields on both `Vehicle` and
   `Implement`. Every field nullable/defaulted, per the existing model.
5. **Fixtures.** `examples/json/` needs a capture per class. Per the precedent in `VdtModelTest`,
   these are **real game captures, not hand-authored** — a hand-written file that claims to be a
   capture was rejected before. So each aspect needs a session in-game, and its tests should assert
   decoding against a genuinely captured file.
   **Done for sowing:** `examples/json/sowingMachine.json` (2026-08-02) — a v9 capture of a Case IH
   Puma + SKY Easydrill P250, which is a *fertilizing* seeder and therefore also the committed
   evidence for the composite case that decided the dispatch rule.
6. `shared:jvmTest` — decode + round-trip over the new fixtures, plus the absent-aspect case (an
   implement with no sowing subtree decodes to null, not a default-constructed object).

---

## App shape

Follows `VdtApp` exactly, as `TasksApp` / `ProductionApp` do:

- **`IsoBusApp`** in `apps/`, registered in `AppRegistry`. `isAvailable()` stays `true` — this is
  core data, not an optional mod, and the panel renders its own "nothing attached" state the way
  `RigSlotPanel` does for an empty slot.
- **`IsoBusPanel`** in `panels/`, `BoxWithConstraints`-measured and thinning out as it narrows —
  the established rule here is that what fits is a fact about the width the tile ended up with, not
  something the page declares.
- **`IsoBusWidget`** with a `ConfigOption` for **which slot** (`FRONT` / `REAR` / `VEHICLE`), mirroring
  `RigSlotWidget.SLOT_KEY`, so a page can carry a seeder tile and a sprayer tile at once on a
  combination rig. That is a **placement** decision, so it belongs in the gear dialog — per the
  established split, only driving-time modes go on the panel header.
- `FullPage` lays the sections out for whatever is attached across all slots.

Section rendering, in order, each present only when its aspect is:

| Section | Shows |
| --- | --- |
| **Sowing** | crop (name + fill-type icon), *n* of *m*, hopper level, direct-sow badge, warnings |
| **Spraying** | what's in the tank, kind, doubled-amount state, active indicator, rate (PF's when present) |
| **Plow** | rotation side — a left/right glyph, not text — plus limit-to-field |
| **Tillage** | kind, deep mode, limit-to-field |
| **Sections** | the existing `workWidth.sections` shutoff bar, reused from `RigSlotPanel` |
| **Work** | `workAreas` active/processing, `workMode` |

The section bar and the work-area readout **already exist** in `RigSlotPanel`. Extract them rather
than reimplementing; that extraction is the one piece of refactoring round 1 should do.

Glyphs: `ClusterIcons.kt` is the precedent — own-drawn, **fills only**, since the ImageMagick render
loop used to review them silently drops strokes. The plow rotation indicator is a natural fit for
that treatment. Note the sandbox cannot run the app (Gradle is host-only, no browser), so anything
visual gets reviewed by rendering at real dp sizes with ImageMagick.

---

## Open questions

1. ~~**Fixtures need game time.**~~ Settled for sowing — the capture landed the same day the aspect
   did, which is the cadence to keep: build the collector, take one capture, assert against it. Still
   outstanding for the sprayer, plow and cultivator.
2. ~~**`usagePerMin` on a client**~~ — settled from the source, see "How the four aspects landed"
   finding 2. The field that *can* be stale is the plough's `limitToField` instead (finding 3), and
   the whole tillage aspect, which is synchronized not at all.
3. **Cultivator sync** — confirmed absent (no streams, no events). The aspect was cut back to three
   fields; whether `limitToField` is worth keeping there at all is a call to make once it has been
   seen on a client.
4. **Does this replace anything on the default Vehicle page?** Memory says the seed layout is not
   settled and the user wanted more apps before fixing it — so round 1 should *add* the app and leave
   the seeded pages alone.

## How the four aspects landed (2026-08-02)

All four collectors are built, unit-tested and formatter-clean; steps 1–2 of the sequencing below are
done and the app side has not been started. Six things came out differently from the plan above, and
the plan text is left as written so the difference is visible:

1. **`usagePerMin` → `nominalUsagePerMin`, and it is not what the plan assumed.** Reading
   `getSprayerUsage` properly (`Sprayer.lua:472-496`) shows it scales by the machine's **speed limit**
   rather than its actual speed — that is how the game holds consumption per hectare constant — so
   dividing back out by `dt` yields a figure that does not move as you slow down. It is a *rating*
   ("litres a minute at full speed"), not live draw. Renamed so no panel can read it as current
   consumption, and documented in both the collector and `Model.kt`. The derived l/ha stays out.
2. **The multiplayer worry about it was wrong, and in our favour.** `WorkArea:onUpdateTick` raises
   `onStartWorkAreaProcessing` with **no `isServer` gate** (`WorkArea.lua:131-133`), so
   `workAreaParameters` is populated on a client for the vehicle being driven — which is the only
   vehicle this mod reports. No field had to be dropped for this.
3. **A new multiplayer hole, in the plough instead.** `limitToField` is broadcast on change
   (`PlowLimitToFieldEvent`) but is **not in the join stream** (`Plow.lua:205-224` carries only
   `rotationMax` + the animation time), so a client that joins mid-session reads the load default
   until somebody toggles it. Kept — the game's own HUD has the same hole — but called out in the
   collector and the model.
4. **The plough reports a `side`, not the engine's bool.** `spec.rotationMax` means "at the max end of
   the turn animation", and *which end is left* is the per-machine `spec.rotateLeftToMax`. The
   engine's own left/right reasoning is `getAIInvertMarkersOnTurn` (`:507-515`):
   `rotationMax == rotateLeftToMax` is left. A consumer must never see the raw bool.
5. **Two planned tillage fields were dropped.** `spec.isWorking` is literally `0.5 < getLastSpeed()`
   — a speed threshold dressed up as a state, which `speed` already answers better — and
   `spec.isEnabled` is flipped by the engine mid-tick without meaning anything a display can act on.
   Same discipline as the sowing aspect: `workAreas[].active/processing` is the honest answer to "is
   this thing working".
6. **`sowing` lost `working` and the warnings** for the reason now written into its header: both are
   set inside work-area processing. Note this is *not* contradicted by finding 2 — the sprayer's
   `workAreaParameters` really are written on a client, but `spec.isWorking` on the seeder is set in
   `processSowingMachineArea`, which only runs for an area the engine decided to process. The
   warnings additionally only matter behind `isActiveForInputIgnoreSelectionIgnoreAI`.

7. **`kind` had to split five ways, not three** (found on review: "what about solid fertilizer / lime /
   manure spreaders?"). The answer to *coverage* is yes — the game has **no separate spreader
   specialization**, so a disc spreader, a lime spreader and a manure spreader are all `Sprayer`, and
   `ManureBarrel` even lists `Sprayer` as a prerequisite. But the base game's own classification is
   too coarse to say so: `isFertilizerSprayer` is a catch-all defined as *not slurry and not manure*,
   which swallows solid fertilizer, lime and herbicide alike. Precision Farming splits it further —
   and **derives the split from base-game calls only**
   (`ExtendedSprayer.lua:125-126`: accepts `FERTILIZER`/`LIME` → solid, `LIQUIDFERTILIZER` → liquid) —
   so the same split is made here and holds whether or not PF is installed. PF's precedence and its
   lumping of lime with solid fertilizer are both followed, so our labels agree with the HUD it draws.
   This matters beyond naming: **the unit a rate is quoted in follows `kind`** — PF prints kg/ha,
   l/ha, m³/ha and t/ha for the four.
   Two rules fell out of it, both now enforced by tests: `kind` is a **capability** (what the tank
   accepts, so an empty spreader is still a spreader) while `category`/`fillType` is **what is loaded
   now**; and both are read from the **same** fill unit, or a combination machine reports one tank's
   capability against another's load.
   Known gap, deliberately left: `SaltSpreader` is a different specialization (WorkArea +
   TurnOnVehicle, no Sprayer), so road-salt equipment gets no aspect. Winter/road kit, not a field
   implement — it needs its own collector if it ever matters.

Test coverage: `spec/Sowing_spec.lua` (6) and `spec/IsoBusAspects_spec.lua` (25) on the mod side, and
thirteen `VdtModelTest` cases on the Kotlin side — including one that pins **every** aspect absent on
a machine that has none, and four driven by the real captures below.

## What the first captures found (2026-08-02)

Eleven captures under `examples/json/telemetry/precisionFarming/`, all `version: 9`. Most of it came
out right first time — the five-way `kind` split, both plough states, the tillage kinds, and
**three different shapes of combination machine** (seed+fertilizer, seed+tillage, slurry+tillage),
which is the dispatch rule validated on real machines rather than stubs. Two genuine bugs, one
non-bug:

1. **A self-propelled machine reported `fillType: DIESEL`.** The Vredo VT5536, a self-propelled manure
   barrel, named diesel as its spray material and produced a nominal usage to match — because
   `getSprayerFillUnitIndex()` falls back to `spec.fillUnitIndex`, whose XML default is **1**, and on a
   self-propelled machine unit 1 is the fuel tank. The engine derives `isSlurryTanker` from that same
   index, so on such a machine *nothing* the spec says about material is trustworthy: `kind` came out
   `SPRAYER` for a slurry machine for the same reason. Fixed by **emitting no `spraying` aspect at
   all** when the resolved tank is one of the motor's propellant units — a sprayer whose spray tank is
   the fuel tank is definitionally broken, and on this machine the real applicator is the attached
   implement, which reports for itself. A subset would have looked plausible and been wrong.
2. **A tankless applicator reported no material.** A dribble bar, an injector or a disc harrow carries
   nothing and draws from the barrel it is hitched to; two of the eleven captures are this shape, so
   it is the common case rather than an exotic one. `fillType` now falls back to
   `workAreaParameters.sprayFillType` — the engine's own resolution of which vehicle's tank feeds this
   one — and a new `externalFill` flag says the level to watch belongs to the machine in front. This
   was flagged as deferred in the original plan ("NOT collected, deliberately"); the captures showed
   that was the wrong call.
3. **`doubledAmountAvailable` is false on all eleven, and that is correct.** Precision Farming
   hard-overrides `getSprayerDoubledAmountActive` to `return false, false`
   (`ExtendedSprayer.lua:1299-1301`) because its variable-rate control replaces doubling outright —
   confirmed by the user. It did expose that the plan and the collector had the **base-game** rule
   backwards: doubling is offered when `not isFertilizerSprayer`, i.e. on slurry tankers and manure
   spreaders, *not* on fertilizer sprayers. Comments corrected; the base-game behaviour remains
   unobserved, because every capture so far has PF installed.

### Both were retaken mid-application, and both fixes hold

`vredoLiquidManure_discHarrow.json` and `liquidManure_dribbleBar.json` were recaptured while actually
working. The Vredo's bogus vehicle-level `spraying` is **gone**; the Bomech dribble bar and the Methys
disc harrow each name their material (`DIGESTATE`, `LIQUIDMANURE`) despite an empty tank of their own.
Four fixture-driven tests now cover both. Two further things the retake taught:

4. **`lastIsExternallyFilled` is not what its name says**, so the flag built on it was dead. Both
   applicators reported it false while visibly drawing from the barrel in front, because
   `getIsSprayerExternallyFilled` (`Sprayer.lua:319-343`) returns false unless **`getIsAIActive()`** —
   it means "a hired worker is being topped up by the game", a different mechanic entirely. The flag
   is now called `externalSource` and is derived from *our own* fallback having been taken (own tank
   empty, material resolved from `sprayFillType`), which is exactly the question a panel is asking:
   whose fill level should I be watching?
5. **`active` is a positive signal only.** The Methys reports `active: false` while
   `workAreas[].processing: true` and demonstrably injecting, because it applies through its
   **CULTIVATOR** work areas and the effect predicate only tracks areas the *sprayer* processes. So a
   combination machine can be working with `active` false. Anything asking "is this implement
   running" must use `workAreas`; `active` may only be trusted when true. Noted in both models.

### The vanilla capture settles `doubledAmountAvailable`

`examples/json/telemetry/vanilla/liquidManure_dribbleBar.json` is the **same rig without Precision
Farming**, and it is the only way this field was ever going to be observable. It reads
`doubledAmountAvailable: true` on the Kaweco barrel — so the base-game rule really is what the source
said and the opposite of what the original plan wrote: doubling is offered on **slurry tankers and
manure spreaders**, not on fertilizer sprayers. The Bomech behind it was captured with
`doubledAmount: true`, so the control is recorded both available and engaged. Paired against the PF
capture of the same machine (which says false), one test now pins both halves.

It also carries no `precisionFarming` subtree anywhere, which is the evidence that none of these
aspects depend on PF — including the five-way `kind` split, which is only *modelled* on PF's.

And it shows the fallback's honest limit: the Bomech is parked rather than applying there, so the
engine never resolved a source material and `fillType` is **absent** rather than guessed.

### `externalSource` closed on real data

Both slurry rigs were captured a third time, and the Bomech and the Methys now carry
`externalSource: true` alongside a named material and an empty tank of their own. The vanilla capture
of the *same* Bomech, parked, has neither — so the pair is the whole contract in two files: one rig,
and the only difference is whether it has worked yet.

That leaves the mod side with nothing outstanding. Every field of every round-1 aspect has now been
seen on a real machine except the base-game `doubledAmount` toggle in the *engaged* state on a
fertilizer sprayer, which cannot exist (the base game does not offer the control there).

## Sequencing

1. **Aspects, one at a time**, `sowing` first — it is the highest-value readout and the cleanest MP
   story, and it proves the section-dispatch rule end to end. Then `plow` (also synced), then
   `spraying` (the trap-heavy one), then `tillage` (the one that may shrink).
2. **Model + version bump + Kotlin model + tests** alongside each, not batched at the end.
3. **Extract the section bar / work-area readout** out of `RigSlotPanel`.
4. **`IsoBusPanel` + widget + app**, sections in the order above.
5. **In-game validation** — including a client — then real fixtures.

Round 2, in rough value order: baler + wrapper (bale in progress, bale type, auto-drop — `Baler.lua`
carries all of it), trailer/forage wagon, mixer wagon (the game ships a `MixerWagonHUDExtension`
whose mixing-ratio bar is worth copying), then the controls listed above, then harvesters as the
issue suggests.
