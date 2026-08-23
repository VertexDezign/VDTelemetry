# Future work

Everything planned, deferred or still open, in one place. Collected 2026-08-06 from the plan documents as they were
retired.

**What this file is.** A plan describes one feature and is deleted once that feature is built — the reasoning moves into
the code as it is written, which is where it is actually read. What a plan cannot hand to the code is the work it
*didn't* do: the deferred item, the open call, the check nobody ran. That is what lands here. Each entry says what the
work is and why it was left, not how to do it.

**What this file is not.** Standing rules and accepted limitations are *decisions*, not future work, and nobody reads a
backlog before writing a panel. So the two design rules — hue never carries a state on its own, and a mark that carries
meaning is an `Icon` rather than a character — live in `VDTerminal/README.md` → "Design rules" (summarised in
`CLAUDE.md`), and a limitation that belongs to one feature lives as a comment on that feature. The "Accepted
limitations" section that used to close this file was dissolved into those two homes on 2026-08-15.

Pruned 2026-08-13 and again 2026-08-15: entries describing work that is now finished were removed, and the "validated
in-game" narratives compressed to a line. The 2026-08-15 pass went further and dropped the *design records* of shipped
features — why the invoice totals are computed the way they are, how the ELS detector works, what the change-detection
hook funnels through. Every one of those was checked against the file it documents before it went: they live in the
module headers now, which is where the next reader is. What stays is what the work did **not** do. Nothing was lost that
the code does not already carry — `git log -p FUTURE.md` has the long form if a decision needs its original reasoning.

Retired plans, if the full reasoning is wanted (`git show <sha>:<file>`):

| plan                   | last content at                                    | covered                                         |
|------------------------|----------------------------------------------------|-------------------------------------------------|
| `gps-course-plan.md`   | `8e4cda3` (2026-08-02, the commit that removed it) | issue #43 — GPS course, section view, coverage  |
| `farm-page-plan.md`    | `3caa3e2` (2026-07-12)                             | the farm page, TaskList + CropRotation channels |
| `vehicle-data-plan.md` | `afe2585` (2026-07-25)                             | the vehicle export gaps, §1–§4                  |
| `map-layers-plan.md`   | `df511b3` (2026-07-25)                             | ground-layer overlays, per-plane split          |
| `mission-plan.md`      | `91e9fca` (2026-08-06)                             | issue #17 — contracts                           |

Still live: **`isobus-plan.md`** (issue #58). Its mod side is built; its app side is not, so the plan stays as the
working spec. It is indexed below rather than duplicated.

---

## The vehicle page never caught up with the export

The four rounds of `vehicle-data-plan.md` deliberately stopped at the data layer, on the rule *export first, UI later*,
and left every rendering decision to a redesign that was coming. The redesign happened — display modes, the pillar
cluster, pages, widget instances — **and did not consume that inbox.** Re-checked 2026-08-15: `VDTerminal/app` still
contains no reference to `pipe`, `cover`, `discharge`,
`tipping`, `baleCounter`, `workMode`, `schema`, `selection` or `controlGroup`. All of it is exported, none of it is
drawn.

- **The rig diagram** — the one that matters most, because it is a UI limitation caused by a data gap that has since
  been closed. `RigSlotPanel` still finds its slots by string-matching `position ==
  "FRONT"` / `"BACK"`, so anything nested or sideways is unrepresentable: `examples/json/nested_trailers.json`
  is a committed fixture the app cannot draw. Mod version 4 exports what a real diagram needs —
  `schema` (the engine's own `schemaOverlay`: name, offsets, and per-joint `x`/`y`/`rotation`/`invertX`
  plus the lifted offsets) and `jointDescIndex` on each implement, which is the link that turns flat per-object data
  into a drawable tree. The layout arithmetic was kept **out** of the mod on purpose so the diagram can change without a
  mod release; `InputHelpDisplay:collectVehicleSchemaDisplayOverlays`
  is the algorithm to mirror, depth cap of 5 included.
- **Selection and control groups.** `selection.selected` is the engine's own per-object flag, so walking the tree finds
  the selected node without touching the root's ordered `selectableObjects`;
  `selection.controlGroup` is `{current, name, names}` off `spec_cylindered`. The game's own HUD prints only the group
  *number* — we have the names from the XML, so the dashboard can do better than the game.
- **Pipe and cover indicators**, on the vehicle and its implements. Both are objects now — `cover
  {state, index, count}`, `pipe {state, current, target, numStates}` — so a panel can say *which* cover of several is
  open and how far along a multi-state pipe is, rather than in-or-out.
- **The stepped fill bar.** `fillUnit.display == STEP` marks consumables, where capacity is a slot count: the game draws
  one segment per slot with the part-used roll's fraction inside the next one, and labels it `"2 / 2"` (a `ceil`, not a
  percentage). `components/FillUnitsDisplay.kt` carries the note and renders a continuous bar.
- **The work aspects.** `discharge`, `tipping`, `harvest`, `workMode`, `baleCounter` — `workWidth` is the one that has
  since been drawn, by the section view.
  `discharge.reason` is the pick of them — the engine's own verdict on why unloading is refused (`NO_FREE_CAPACITY`,
  `NO_ACCESS_LAND`, …), the same code behind its on-screen warning, and nothing a dashboard could work out for itself.

### Two open calls on the mod side

- **`showOnHud` vs `showOnInfoHud`.** `aspects/FillUnit.lua` filters on `showOnInfoHud`, which is the *info-box* flag;
  the *fill-bar* flag a dashboard is mirroring is `showOnHud`. They are independent XML attributes, so today we drop
  units the game draws a bar for and show units it doesn't. Not taken, deliberately: the existing skip was added for a
  real symptom — a forage/carrot harvester's pass-through output showing up — and if those units carry
  `showOnHud="true"` the switch brings them straight back. It buys nothing observable today, so it waits on the in-game
  check below. If it is ever taken, keep our tolerance for zero-capacity units (mods that ship `capacity = 0`); that
  divergence from the game's `0 < capacity` gate is on purpose.
- **`Consumable` as a first-class block.** The fill unit already reports the right *level*. A dedicated block off
  `spec_consumable.types[]` would add which variation is loaded, `showWarning`,
  `allowRefillDialog` and the storage/consuming split — which is what a *"you're on your last roll"*
  alert needs, and nothing else. Do it only if that alert is wanted.

### Making pipe and cover controllable

An ordinary direct-call control, if it is wanted. `Pipe:setPipeState(state)` and
`Cover:setCoverState(state)` each take an **absolute** state and each own their multiplayer event (`SetPipeStateEvent`,
`SetCoverStateEvent`), which is exactly what the lossy command channel needs — same shape as `LightControl` and
`MotorControl`, which call the engine setters directly. Two quirks worth knowing: `setPipeState` clamps to `numStates`,
and `setCoverState` silently no-ops unless the vehicle `hasCovers` and the state is within `0..#covers`.

This is **not** a departure from how `ImplementControl` works. That control routes lower/fold/activate through
FS25_additionalInputs' `vdAI*` functions because additionalInputs **already owns** that spec-aware logic —
attacher-joint lowering, fold-to-middle, `requiresPower`, the implement chain — and hand-rolling it per spec was fragile
(a self-propelled foldable like the Krone BigM reports "lowered"
via the Foldable fold-middle state, not Attachable, so a hand-rolled `setLoweredAll` no-ops on it). The rule is to use
what is in vdAI, **not** to extend vdAI with functions only VDTelemetry needs. There is no pipe or cover function there,
so calling the engine directly is the normal path, not an exception.

---

## ISOBUS (#58)

`isobus-plan.md` is still the spec; this is the index. Round 1's four aspects — `sowing`, `spraying`,
`plow`, `tillage` — are built, tested and captured against real machines. `IsoBusApp` / `IsoBusPanel` /
`IsoBusWidget` now exist, built for the **mixer wagon** (#113) — so the app side has a shell and the
dispatch rule has its first real user, but **none of round 1's four aspects has a section yet**.

- **Round 1's four sections.** Sowing, spraying, plough and tillage, rendered by aspect presence in the
  plan's order, added to `IsoBusPanel`'s stack next to the mixer's — the dispatch list is
  `IsoBusMachine.hasSection`, and each of the four has to be added to it and to the flattening the
  panel does out of `Vehicle` / `Implement`. The one piece of refactoring to do first: extract the
  section-shutoff bar and the work-area readout out of `RigSlotPanel` instead of reimplementing them.
- **The machine art for those four** is 17 generated SVGs on the unmerged `58-isobus-aspects` branch,
  along with `tools/isobus-art/` and `tools/isobus-mockup/`. **Whether Compose Resources decodes SVG at
  runtime in the wasm build was never verified** — only accessor generation was. The mixer's art is a
  PNG and sidesteps it, so the question is still open and blocks that branch, not this one.
- **Round 2 classes**, in rough value order: baler + wrapper (bale in progress, bale type, auto-drop —
  `Baler.lua` carries all of it), trailer / forage wagon, then harvesters, as the issue suggests. The
  mixer wagon is done (#113).
- **Round 2 controls.** Seed index (`setSeedIndex` / `changeSeedIndex` already send `SetSeedIndexEvent`), plough
  rotation (`setRotationMax` / `setRotationCenter`, both take `noEventSend` and own their event), the sprayer's
  doubled-amount toggle. None of them needs an MP event of our own.
- **A derived l/ha rate**, or not at all. `getSprayerUsage` scales by the machine's *speed limit* rather than its actual
  speed — that is how the game holds consumption per hectare constant — so what we export is `nominalUsagePerMin`, a
  rating rather than live draw. Turning it into a per-hectare figure means reasoning about what the engine is actually
  holding constant, checked against
  `processSprayerArea`. Precision Farming already publishes a true rate when it is installed.
- **Known gap:** `SaltSpreader` is a different specialization (WorkArea + TurnOnVehicle, no `Sprayer`), so road-salt
  equipment gets no aspect at all. Winter/road kit rather than a field implement — it needs its own collector if it ever
  matters.
- **Open:** whether the tillage aspect's `limitToField` is worth keeping. `Cultivator` registers no read/write stream,
  so on a client the field is whatever `onLoad` left behind. A call to make once it has been seen on a client.

### Mixer wagon (#113), what round 1 left

Built, and **well covered**: five captures — four vanilla singleplayer ones under
`examples/json/telemetry/vanilla/mixerWagon_*.json` plus
`examples/json/telemetry/modded/mixerWagon_selfDriving_empty_moddedReceipe.json` — drive eighteen
`VdtModelTest` cases between them, and they closed every question this list opened with. The recipe lookup
lands on both machines; the authored ingredient titles arrive; the per-ingredient weight appears on half
the base game's forage recipe (silage and mineral feed accept one material each, hay and straw pool two
and are correctly unweighed); the mix cycle has been seen full, part way down and expired; the aspect has
been seen on a `Vehicle` (self-propelled) *and* on an `Implement` (towed); and the empty tub weighs zero
while its machine still reads 617 kg over its empty mass, which is the bug `mixer.mass` exists for, now
captured rather than recounted. The layout was still only ever reviewed through ImageMagick mockups,
because the sandbox has no browser.

- **Multiplayer, half answered.** The modded capture is off a **joined client**, and everything the aspect
  takes from `onLoad` is there: the ingredient names and windows, the authored titles, the recipe's own
  fill type (found back through `g_currentMission.animalFoodSystem`, which a client also has), the mixing
  time, the tip sides — and `mass`, so `Vehicle:updateMass` had run on that machine there. What an empty
  tub cannot show is the sync itself: `mixer.remaining` and the per-ingredient levels take an unusual
  route, since the mixer's fill unit is out of the normal fill-unit sync (`synchronizeFillLevel = false`)
  and the levels ride `MixerWagon`'s own update stream, which the client re-applies through
  `addFillUnitFillLevel` — also what sets `activeTimer`. So what is still wanted is a **loaded** wagon on a
  client, mid-cycle.
- **A wagon mid-tip.** Every captured wagon is `CLOSED`, so `tipping.side` (as against `preferredSide`)
  has never been seen resolved, and neither has the discharge refusal chip — `discharge.reason` is absent
  in all five, which is correct but means the wording has never been read on screen.
- **A loaded capture at version 17.** The empty one pins `mixer.mass` at zero on a real machine; the four
  loaded ones are v16 and predate the field, so a *loaded* tub's own weight is still asserted from inline
  JSON.
- **A per-fill-unit `mass` is the eventual home** for what `mixer.mass` does. Every load has this
  problem, not just a mixer's: `Mass.value - Mass.empty` counts the diesel, the DEF and anything
  hard-attached, so no machine's load can be read off it. `aspects/FillUnit.lua` already resolves each
  unit's fill type and could weigh it there — the mixer's own field exists because the tub cannot be
  found again in the exported unit list (it carries no indices).
- **Controls, deliberately not built.** Tip side (`setPreferedTipSide`) and start/stop tipping have **no
  `vdAI*` counterpart in FS25_additionalInputs**, so wiring them would mean calling the engine directly
  for the first time on a driving-time control. Round 1 stayed read-only rather than settle that here.
- **The over-max case has no number.** An ingredient above its window is fixed by adding *something
  else*, so its row is flagged but says nothing actionable. The figure is well-defined
  (`value / max - loaded` litres of anything else) — deliberately left out until the bars have been used
  in anger, because it would repeat a different number on every over row.
- **Deliberately not collected**, both with reasons in `collect/aspects/Mixer.lua`: `spec.baleTriggers`
  (built under `if self.isServer`, so nil on a client) and the bale-not-accepted warning (an event, not
  state — a poll cannot see it). A mixer wagon eating a bale that is not in its recipe is exactly what a
  terminal should say, so this is worth revisiting if the event can be hooked cheaply.
- **One machine, all mixers.** `isobus_mixer_wagon.png` is a twin-auger vertical mixer standing in for
  the class, the same bargain the rest of the ISOBUS art set makes.

---

## Missions (#17)

Built and validated in-game. Four things were left.

- **Command outcomes have nowhere to go — deliberately, for now.** The engine answers every action with a state
  (`MissionStartState` has 8 values, five of them ordinary refusals a user should see) and the mod can only log it. The
  call (2026-08-05) was to skip the reply path and revisit if it bites. It is mitigated rather than absent: the app
  greys accept at the farm's contract cap and hides the buttons without the `manageContracts` right, so the two
  likeliest refusals are prevented rather than reported, and the channel's next write is event-driven off
  `MISSION_STATUS_CHANGED` and lands within a tick. If it does bite, the options are a `lastCommand` block on the
  channel (cheap, fits the existing one-way plumbing) or a real mod→app reply channel (bigger — and every other control
  would then use it).
- **Mission vehicles.** Accepting with equipment spawns machines at the shop and `mission.vehicles` is on the object.
  Showing them, and where they are, is the natural round 2.
- **A time-out alert.** A running contract about to expire is an `AlertRule` candidate: `minutesLeft` is already on the
  wire, and `AlertEngine` / `KeyedAlertRule` already exist (`TasksApp` is the precedent).
- **How much detail belongs on a driving screen.** The panel is a menu; the widget is what someone glances at while
  working. The split wants the same review the ISOBUS layout got.
- **Out of scope, explicitly:** contracting (`MANAGE_CONTRACTING`). Working *for* another farm is a separate permission
  and a separate feature.

---

## Finance (#48, under #46)

Built and validated in-game, in singleplayer and on a multiplayer client. The design lives in the module headers of
`src/collect/FinanceExporter.lua` and `src/command/FinanceControl.lua`. Only a handful of month rollovers have been
watched, so the current period's column is still worth half an eye. What was left:

- **The graphs.** The issue's own "Bonus: draw some graphs" was deliberately deferred until there was real data in the
  panel to shape a chart around. The export already feeds it: the mod carries up to twelve periods where the game has
  them (the app shows the in-game five by default), and each column carries its own `total`, so a per-month
  income/expense chart needs no mod change at all.
- **The money log is session-scoped and in memory.** It starts empty on every game launch, and a cap of 100 entries
  drops the oldest. **The cap is settled for now (2026-08-11): 100 is enough in practice**, so neither raising it nor
  making it configurable is worth doing. Persisting the log is the same open question as everything under "VDT-owned
  data" below — it would be *savegame* state, and the FS25 sandbox makes reading anything back an XML problem. Worth
  doing only if the log turns out to be something people look back through.
- **Command outcomes have nowhere to go**, same as Missions. `setLoan`'s two guards are deliberately asymmetric — a
  too-large borrow is **clamped** to the ceiling, an unaffordable repayment is **refused** outright — and either outcome
  only reaches a log line. Mitigated the same way: the app greys the buttons using `canManageLoan` / `loanMax` / the
  balance, so both outcomes are prevented rather than reported, and the channel is event-driven off `ChangeLoanEvent`,
  which should land the result within a tick — that last part is an expectation, not a measurement; it is the open
  in-game check under "In-game checks" below. **The prevention half is confirmed (2026-08-11):** a non-`farmManager`'s
  controls disable with a reason rather than firing a command the server drops.
- **The too-large-borrow clamp in `FinanceControl` cannot be provoked from the app**, so it will never be seen to work:
  the app never asks for more than the ceiling. It still is not dead code — it covers a command built against a *stale*
  `loanMax` — but that race cannot be staged on demand, so don't delete the clamp on the strength of it never firing.
- **The stat-row set is not fixed**: the exporter walks the live `FinanceStats.statNames`, so a mod that appends a
  bucket (one savegame's `dryingCharge`) arrives with its localized title and a correct total for free. Never write a
  test asserting a row count.
- **Only five periods survive a save.** `FarmStats:saveToXMLFile` writes the current bucket plus four, so a long
  uninterrupted session accumulates more than five in memory and a reload drops back to five. Not a bug and not fixable
  from here — the app must simply cope with the column count changing.
- **`loanMax` on a freshly joined client may read `Farm.MIN_LOAN`.** `Farm:setInitialEconomy` calls
  `updateMaxLoan` before any farmland is known, and it is only recomputed on `FARM_PROPERTY_CHANGED`. The in-game screen
  reads the same cached field and has the identical quirk, so we match it rather than walking every placeable ourselves.
  If it bites in practice, say "up to 500,000" rather than recomputing equity.

---

## Enhanced Loan System (#47, under #46)

Built on top of #48 and validated in-game, in singleplayer and from a multiplayer client — including a terminal-created
loan surviving a server restart, which is the proof it reached the server's table rather than only the client's. How the
detector, the permission split and the re-derived bounds work is in `src/integrations/EnhancedLoanSystem.lua` and
`src/command/EnhancedLoanControl.lua`. What was left:

- **The borrowing ceiling is expensive.** `maxLoanAmountForFarm` walks every vehicle calling `getSellPrice()`
  plus every farmland — the same cost that made us read `farm.loanMax` rather than recompute equity for the base loan.
  Cached for 30 s. If that still shows up in a profile, the next step is to recompute it only while the app actually has
  the take-loan controls open.
- **Still open:** paid-off loans accumulate in the export (the mod keeps them forever, and the app only shows a count).
  If a long-running farm ends up with dozens, cap or summarise them mod-side.
- **Not built:** ELS's server settings (interest rate, mortgage ratios, max duration) are read-only here. They are a
  settings screen, not a finance one, and changing them from a terminal is a different feature.

---

## Invoices (#12, under #46)

The last child of #46, on top of #48. Billing between farms via FS25_Invoices, in its own event-driven
`invoices.json` channel (`src/integrations/Invoices.lua`) rendered as a second tab in the Finance app, plus five
commands (`src/command/InvoiceControl.lua`) — the mod's own server is the boundary throughout. Validated in a two-farm
multiplayer session on 2026-08-11 — raising an invoice and paying it, the core round trip. The reasoning (how the totals
and discounts are computed, why the direction inverts, what change detection hooks) is in those two module headers. What
that session did not cover, and what was left:

- **This feature has no singleplayer form.** An invoice needs two different farms and singleplayer has one, so the
  channel correctly exports the settings, the work-type catalogue, no farms and no invoices there. Every check below
  needs a **two-farm multiplayer session**, which is also the only place a fixture can be captured —
  `examples/json/invoices/invoices.json` came from the first one.
- **Not built: the three picker-backed line types.** Vehicle sale, consumable (pallet/bale) sale and product (fillType)
  sale transfer ownership of real objects on payment, which a command cannot assemble — each would need a new pick-list
  export of its own. They are exported with a `needsPicker`
  token and shown in the builder as in-game-only, rather than silently dropped.
- **Not built: writing the mod's settings** (VAT simulation, penalties, reminders). They are
  `serverOnly` and would be one more command, but they are a server-economy screen rather than a finance one — the same
  call as ELS's settings above.
- **Open: how long the list stays usable.** Paid invoices are never deleted by the mod, so a long-running server
  accumulates them. The app sinks them below the live ones and offers a direction filter; if that stops being enough,
  cap or summarise them mod-side (the same shape as ELS's paid-off-loan problem).
- **Command outcomes have nowhere to go**, same as Missions and the loans. `actions` is recomputed on every write and
  the channel writes on every mutation, so the window is one file write — but a proposal the other party validates
  between our write and the player's tap is simply refused by the mod's server, leaving only a log line. Mitigated the
  same way as `setLoan`: the app greys the button from `actions` and the balance, so the outcome is prevented rather
  than reported.
- **The builder's line cap is the mod's, not a usable one.** `ClientMessage.CreateInvoice` rejects more than 100 lines
  because that is where the mod's own server refuses, and the builder disables "Add line" at that point — but a terminal
  form gets unwieldy long before 100. If anyone ever fills one, the answer is a lower soft cap in the app, not a change
  to the contract.
- **Not built: per-line names.** The in-game wizard lets a player rename a line (`customLabel`, and
  `customLabelByField` for field work); the terminal sends the work type's own localized name. A free-text name is one
  more optional attribute on `<line/>` if it turns out to matter — the read side already carries `InvoiceLine.name` and
  displays it.

### In-game checks nobody has run

All of these need a **two-farm multiplayer session**. Creating and paying an invoice is done (2026-08-11); what is left
is everything that round trip does not touch.

- **Both creation paths.** The 2026-08-11 session exercised one of them. `createAndSendInvoice` is called directly on a
  **host** and nothing recomputes the totals, where a **client**'s goes through the mod's server-side sanitising — so
  the untested side of that asymmetry still wants a look, and it is the one place the two could disagree.
- The channel writes on join (the `applySyncData` path through `notifyUI`), and a savegame's existing invoices appear
  without waiting for a change (the first-sight `markDirty`).
- A payment shows up in the finance table under `invoiceExpense` / `invoiceIncome`. Should follow from the mod
  registering those as `FinanceStats` buckets — which is also why nothing of ours should ever account for invoice money
  a second time — but it is one glance at a panel that is already open.
- A proposal raised from the payer side can be validated, and refused, from the issuer side.
- Letting one go overdue (two period rollovers) lands `penalty`, `overdue` and the recomputed
  `totalDue`, and `daysUntilPenalty` counted down honestly on the way there.
- A non-`farmManager` player gets no `actions` at all rather than buttons that fail.
- The localized work-type and unit labels resolve through `customEnv` — no `Missing 'invoice_work_…'`
  strings reach the panel — on a non-English client too.

---

## Map layers

Both remaining items were declined on 2026-07-25. They are kept as the record of what they would cost, not as a queue.

- **Sub-value toggles inside a layer** — hide weeds, keep needs-plowing. The app has the legends but **not** the raster
  cell values; those exist only in the server-rendered PNG. So this is not app-only work. For **crops** and **growth**,
  where each cell holds exactly one value with its own legend colour, it is feasible as a server render-filter
  (`/api/map-layer/{id}?hide=…`, enabled values drawn and the rest transparent) with no data-model change. The
  alternative — raster rows to the app, which draws and filters the bitmap itself — is a big shift away from the
  deliberate "PNG server-side, legends-only over the WebSocket" design.
- **Independent soil toggles need the data de-collapsed first.** `classifySoil` returns *one* value per cell by priority
  (weeds > stones > needs-plow > needs-lime > fertilized), mirroring the game, so a cell that is both weedy and needs
  plowing stores only "weeds" — the plough state underneath was never captured, and hiding weeds cannot reveal it. Doing
  it properly means promoting each soil sub-state to its own single-value plane: mod classification, wire model, and
  stacking order in the app. Only if independent soil visibility is actually wanted; if it is picked up, fold it into
  one coherent re-model of the layer set rather than two passes.
- **The per-frame tick.** The per-plane split left VDTelemetry's own tick at 0.5–0.6% of script time — the scheduler
  itself, not any one channel. `0b391c7` trimmed the idle path (skip channels with neither cadence nor tick, bail out of
  `writeDirty` while nothing is queued, throttle the offered-layer recheck to 5 s), and it is worth re-reading the
  profiler after that. The mod has to run *something* every frame, so this entry never goes away entirely.

---

## Channel cadence and profiles

The scheduler, per-channel `enabled`/`intervalMs`, the profile presets and the Kotlin cadence measurement all shipped.
One thing was left, and it is a trap rather than a feature:

- **There is no app→mod config push.** Per-channel config is read from the settings XML **at load only**, and the mod
  rewrites that XML on any in-game change — so an app that edits it live gets clobbered. Wiring per-channel tuning into
  the app needs a push channel of its own, the way the command channel works. Re-checked 2026-08-15: `shared` carries the
  *observed* cadence (the diagnostics feed) and no channel config at all.
- Related, if that is built: a per-channel `intervalOverride` is **ignored unless the profile is
  `custom`**, so an app doing the tuning has to stamp `profile = custom` as it goes.

---

## VDT-owned data

Every write path today drives a *mod's* own state through its own multiplayer events; VDTelemetry persists nothing of
its own. The first real case for changing that:

### Assigning a CropRotation plan to a field

CropRotation's planner stores plans as a flat list and carries **nothing that ties a plan to a field** — there is no
notion of "field 7 follows the *Heavy Soil* rotation"; you read the plan and apply it by hand. A VDT-owned map of
`fieldId → rotation index` (plus, plausibly, the current position in the rotation, so the app can say *what to plant
next*) is exactly the kind of data VDT can own without fighting the mod. Field ids come from `g_fieldManager`; joining
our map against `cropRotations[index]`
gives a per-field "assigned rotation" view, and against the field's current fruit a "next crop" hint. The write side is
a VDT command against a VDT store, **not** a mutation of the mod.

Four things to work out when it is picked up:

- **Persistence location and lifetime.** This is *savegame* state, not client settings — two savegames must not share a
  field→rotation map. Keying by savegame id inside `modSettings/<modName>/`, versus writing into the savegame directory
  the way both target mods do (both hook off
  `FSBaseMission.saveSavegame`), is an open choice. The latter matches the neighbours and gets save/load timing for
  free.
- **Reading it back is XML, not JSON.** The FS25 Lua sandbox restricts `io.open` to write mode — which is why the
  command channel is XML in the first place (see `CommandChannel.lua`). Any store the mod has to *read* has the same
  constraint, whatever we emit for telemetry.
- **Referential integrity.** Plans are referenced by `index`, and `addDeleteCropRotations` deletes by clearing the slot,
  so an assignment can dangle. Resolve dangling references on load and treat a missing plan as "unassigned" rather than
  an error.
- **Multiplayer.** Unlike task and rotation edits, this state has no mod event to ride on. Either scope it to the local
  client — simplest, and consistent with telemetry being client-side only — or build a VDT sync event. Start local.

### CropRotation position / field data

Sampling the crop-history density map at the player's position (`historyStateManager.historyStates[i].map:getState`,
`YieldCalculator:potentialYieldAtPosition`,
`:getYieldMultiplier`), or sweeping `g_fieldManager:getFields()` for a per-field table. Both need a timer, because the
position changes as you drive, and both need in-game profiling before they are trusted. The channel registry already
supports it: a channel whose `markDirty()` is driven by a position bucket rather than by a message.

---

## In-game checks nobody has run

Each one is cheap to do while playing and settles something above.

- **A southern-hemisphere map**, to confirm the calendar's column labels really shift: `g_i18n:formatPeriod` keys off
  `environment.daylight.latitude < 0` and should label period 1 September rather than March. This is the whole reason
  the labels cross the wire instead of being a lookup table in the app.

- Does a base-game baler set `uiDisplayType="STEP"` on its consumable fill unit? It is visible in the exported JSON as
  `display`, so this is just a matter of looking. Decides whether the stepped bar is worth building.
- Does a multi-state pipe report sensibly — an auger wagon should give `pipe.numStates > 2`? Read the JSON; nothing
  renders it.
- Does a multi-cover vehicle really report `cover.state > 1`? Parked for want of such a vehicle, and low risk: a
  single-cover vehicle only ever has state 0 or 1, where the old and new mappers agree exactly, so the only changed
  behaviour is `state >= 2`, which used to return `UNKNOWN` and was wrong by construction. The tell that you are looking
  at one is the action prompt reading **"Next cover"** rather than "Open/Close cover" — `Cover:updateActionText` uses
  that string only while `0 < state < #covers`.
- Does `schema` come out populated on a real rig, and does `jointDescIndex` line up with the parent's
  `attacherJoint` list? The one thing the synthetic tests cannot confirm.
- Does `controlGroup` populate on a front loader or crane, with sensible `names`? They come from vehicle XML and may be
  unresolved i18n keys on some mods. Same question for `workMode.name`.
- Does `discharge.reason` read `NO_FREE_CAPACITY` when the game refuses to unload? Back a trailer up to a full silo.
  This is the highest-value single check of the work aspects.
- Do any fill units in normal use differ between `showOnHud` and `showOnInfoHud` — in particular, does a forage/carrot
  harvester's pass-through output carry `showOnHud="true"`? This gates the filter switch above.
- Does the wake-lock fallback hold an **Android** tablet awake? iPadOS is answered — an unmuted clip holds the screen,
  a muted one doesn't (see `VDTerminal/README.md` → display mode) — and Android is the other half of the same question,
  where the autoplay policy and the idle timer are a different pair of rules. Open `http://<lan-ip>:3001`, tap the
  coffee cup, confirm **AWAKE**, leave it past the screen timeout. If Android turns out not to need the audio session,
  the unmute could be made conditional and the driver's music left alone there.
- Does borrowing from the terminal land without waiting out the 5 s interval? It should: the mod subscribes to
  `ChangeLoanEvent`, which the engine publishes on both sides of the wire. Note it is about the *base-game* loan, so an
  ELS save cannot answer it.

## Steering (#57)

Built and driven in-game, layout derivation included, against a machine with dog-walk modes and sides:
`vehicle.steering` (mod version 10) and its two marks in the pillar readout. What is left:

- **`CRAB` has never been seen on a real machine.** It is the sideless dog walk — the rear axle steered along with the
  front rather than held over — and it is in the model because the engine's data allows it (a mode may set a steering
  node's `rotScale` negative with no offset), not because anything is known to be built that way. The machines checked
  so far all use the offset, which is `CRAB_LEFT` /
  `CRAB_RIGHT`. If it never turns up, the value and its share of the left glyph can go.
- **A frame that steers on its own joint reports no layout.** `spec_articulatedAxis` is a third mechanism the derivation
  doesn't read, so an articulated machine falls back to printing the mode's number. Left out deliberately: it needs its
  own geometry and there is no way to tell here whether any machine with steering modes actually uses it.
- **Neither half is controllable.** `setCrabSteering(state)` and `setIsReverseDriving(state)` each take an absolute
  value and each own their multiplayer event (`SetCrabSteeringEvent`,
  `ReverseDrivingSetStateEvent`) — the same shape as `LightControl`, and the same reasoning as the pipe and cover
  controls above. Not built because #57 asked to *see* them. `setIsReverseDriving` refuses while an implement on a
  disabling attacher joint is fitted (`getIsReverseDrivingAllowed`), which a control would want to reflect rather than
  fire and ignore.

## Precision Farming rates (#77)

Built, and validated in singleplayer and from a multiplayer client across all five of PF's rate units and both modes.
The reasoning lives in `src/integrations/PrecisionFarming.lua` and
`components/SectionView.kt`; the captures that pin it are in `examples/json/telemetry/precisionFarming/`
and are named in `VdtModelTest`. What it did not do:

- **A nested implement has no slot of its own.** The mod reports the Bomech's `position` as an empty string, so
  `RigSlotPanel` can never address it directly; it is seen through its parent's tile or not at all. Fine for a section
  view, and the thing to fix properly whenever the rig diagram in the first section of this file gets built.
- **PF's third keybind is not mirrored.** In auto with no crop in the ground, `TOGGLE_SEEDS` cycles which fruit the tool
  fertilises *for* (`setSprayAmountDefaultFruitRequirementIndex`, off
  `nApplyAutoModeFruitRequirementDefaultIndex`). It changes the auto target, so it belongs beside the auto toggle and
  the step if auto mode is ever given more than a badge — and it would need the fruit list exported, which nothing does
  today.
- **The step is a rig-wide command.** It addresses whatever PF calls the rig's valid sprayer rather than a slot, so a
  hypothetical rig with two PF machines is driven as one. That is PF's own model (`getValidSprayerToUse` returns the
  first valid machine), and a rig you would tow two sprayers on is not a rig anyone drives — but it is the assumption to
  revisit if one ever turns up.

## Advanced Damage System (#79)

Built: the six dashboard lamps with ADS's severity and its production-year gating, the engine temperature as ADS's, the
engine load it wears the engine on, the service interval and system voltage. The reasoning is in
`src/integrations/AdvancedDamageSystem.lua` and
`panels/ClusterService.kt`. What it did not do:

- **The pre-shift chores are not exported, and that is the decision rather than an omission.**
  Radiator and air-intake clogging and the lubrication level were collected at first, in ADS's own coarse bands, and
  then dropped: a driver learns them by getting out and walking round the machine, so a dashboard that printed them
  would hand over the walk. The bands were not enough to save them — the objection is to knowing at all, not to knowing
  exactly. The Lua spec pins it (`pre-shift
  checks`), and reversing it means the collector, `AdsChecks`/`AdsCheck` and a row on the service tile — all of which
  `ba4d8e4` removed, so `git show ba4d8e4` is where they are.

- **Almost nothing has been checked in game.** The integration is written against ADS's source rather than against a
  running session. The one exception is the bulb check on the starter, driven on 2026-08-14 both with ADS and without
  (#85): the band lights whole for the crank and goes back to reporting when the engine catches. Still worth watching
  for specifically: whether the coolant lamp reads COLD for a plausible length of time after a cold start, whether a
  lamp latched by a breakdown clears when the breakdown is repaired, and whether an ADS-managed machine's engine
  temperature really does arrive on an MP client (which is the whole claim about the vanilla figure being unsynced).
  Also that the lamps turn up on every machine: the year gate is read from `ADS_Main.hud.indicators` and there is no
  mirrored fallback any more, so anywhere that table is not built the band is simply empty — an empty band with the rest
  of the `ads` block present is that case, not a machine with no lamps.
- **The `oil` lamp is exported by nobody and drawn by nobody.** ADS computes it (`serviceLevel < 0.2`)
  and then hides it from its own HUD, so drawing it would tell the player something the mod chose to withhold.
  `transmission` is worse: declared in `ADS_Breakdowns.DASHBOARD` and referenced by not one breakdown in the mod. If a
  future ADS starts drawing either, they are two lamps and two glyphs away.
- **The fleet is now exported** — issue #84, the `fleet` channel and its `contributeFleetVehicle` stage. It does not
  read `ADS_Main.vehicles` (keyed by `uniqueId`, which is nil on an MP client) but the per-vehicle spec of every machine
  the farm owns, which is fully synced. See the section below for what that left open.
- **A fixture is wanted.** No committed capture has the driven vehicle's `ads` block — the fleet captures carry the
  *fleet* one (`examples/json/fleet/`), which is the maintenance record rather than the dashboard — so `AdsModelTest`
  decodes the shape with inline JSON and says so at the top. See the section below for the rule those follow.

---

## Captures wanted as fixtures

The schema, selection and work aspects are all tested synthetically, because none of the committed captures contains a
machine that has them.

- **A tipping trailer** and **a baler.** Between them they cover `tipping`, `discharge`, `baleCounter`, the `STEP`
  consumable bar, and they would give `jointDescIndex` its first real chain.
- **More finance captures.** `examples/json/finance/vanilla.json` is a fresh singleplayer save, so it has one period and
  an empty log. Still wanted: **a played-in save** (several archived periods, to see how many a real game carries), **an
  MP client** (does the history really stop at five?), and **one with notifications in the log** — the hook itself is
  confirmed working in singleplayer, so this is now wanted as a fixture rather than as proof. `FinanceModelTest` covers
  those three shapes with inline JSON meanwhile.
- **A capture with Advanced Damage System installed**, for the `ads` block — ideally a CVT machine (so
  `transmissionTemperatur` is present) that is a little overdue for service and carrying a breakdown or two, so the
  lamps, the interval and the load are all non-trivial in the one file.
  `AdsModelTest` covers the shape with inline JSON meanwhile.
- **A fleet capture with a machine actually in trouble** — wanted, but nothing to chase: the playthrough these came
  from has not produced a breakdown or an overdue service yet, so it waits on the game rather than on anyone.
  Two are committed —
  `examples/json/fleet/fleet.json` (fresh singleplayer: a helper's rig, the player's own rig, contract equipment, a
  leased tractor, an electric loader ADS excludes, four machines parked) and `mp.json` (a played-in multiplayer client:
  ADS histories, real wear, consumables in slots). Between them the shapes are covered except the ones that need a
  machine in **trouble**: nothing in either is overdue, in a workshop, carrying a discovered fault, or has ever had a
  *complete* inspection — so `FleetAds.workshop`, the breakdown list and an exact condition figure are still inline
  JSON in `FleetModelTest`.
- **More invoices captures.** `examples/json/invoices/invoices.json` came out of the 2026-08-11 two-farm session and
  drives `InvoicesModelTest.parsesTheTwoFarmCapture`: three invoices from farm 1's side, one of them a proposal showing
  the direction inversion, a discounted line, and the full 56-entry German work-type catalogue. What it does not
  contain, because that session never got there: an **incoming** invoice, a **paid** one, and one that has **accrued a
  penalty**. `InvoicesModelTest`
  covers those three with inline JSON meanwhile, and says so at the top.
- **A loaded mixer wagon on a multiplayer client**, and one **mid-tip**. Five are committed and cover the
  rest: `vanilla/mixerWagon_correct` (towed, a finished mix, two tip sides),
  `vanilla/mixerWagon_selfDriving_outOfRatio` (the straw at 39% of the load against a 30% ceiling — a real
  instance of the share-of-the-load trap and now the test for it), `vanilla/mixerWagon_selfDriving_single`
  (one material in, the cycle part way down), `vanilla/mixerWagon_selfDriving_mixing` (a full cycle just
  restarted — the ratio is valid there, the mixing time simply has not run out) and
  `modded/mixerWagon_selfDriving_empty_moddedReceipe` (an empty tub on a joined MP client, and a second
  map's recipe: same four ingredient names as the base game's, different windows and wider material
  pools). What none of them shows is a wagon actually tipping — so `tipping.side` and `discharge.reason`
  are still unobserved — or the ingredient levels *arriving* on a client, which needs a loaded one.
- The rule these follow: fixtures are **real game captures, never hand-authored**. A hand-written file claiming to be a
  capture was rejected before, and fill-type names live in `fillTypes.xml`, which is not readable from here — inventing
  them would put made-up game data in `examples/json`.


## Releasing (#80)

The first alpha's packaging is built: a tag drives `.github/workflows/release.yml`, which zips the mod, builds a
bundled-JRE app image per OS with jpackage, and publishes a prerelease. What it does not do yet:

- **Nothing is signed.** Windows SmartScreen warns on every download, and the setup guides spend a bullet each
  explaining that away. A code-signing certificate costs money and buys less than it used to: since EV lost its
  automatic bypass, SmartScreen goes by reputation earned per publisher over many downloads, so signing would only
  start that clock rather than end the warning. Until then the warning is the honest answer, not a bug to hide.
- **The bundled runtime is jpackage's default**, i.e. every JDK module — ~70 MB compressed per OS. A `jlink`
  `--add-modules` set trimmed to what Netty and Ktor actually touch (`jdk.unsupported` and `java.management` among
  them) would roughly halve it. Worth doing once the module set can be verified by running the result, not guessed.
- **No icon on the app image.** jpackage takes `--icon`, which wants a `.ico` on Windows; the repo has only the mod's
  `.dds`. Until then the launcher wears the stock Java icon.
- **macOS is not built**, deliberately: the server reads files as the game writes them, so it has to live on the
  machine running FS25. Only worth revisiting if someone actually wants to point `VDT_FILE` at a network share.
- **Nothing has run the packaged archive on a JDK newer than the one it ships.** The floor is 25 and the release bundles
  25, so the combination a player gets is the one CI smoke-tests; a developer's own `installDist` on 26 is not. The
  smoke test's "no `WARNING:` on startup" check is what would catch the next JDK tightening its native-access defaults,
  and it only runs on the release path.
- **The release pins [FSTools](https://github.com/VertexDezign/FSTools) to `v0.2.0`.** CI packs the mod with the same
  `fs pack` used locally, so `.fsignore` is the one definition of what ships; tracking `main` instead would mean a
  change over there silently changing what is released from here. Bumping the pin is a deliberate step, and worth a
  `workflow_dispatch` dry run when it happens. The release also runs `fs validate`, which the mod otherwise gets no CI
  coverage from.
- **In-game there is still no warning when FS25_additionalInputs is missing** — the mod logs it and silently disables
  the export, so the symptom is an empty dashboard. `VDTelemetry:loadMap` carries the `TODO display warning in ui`.
  Both setup guides currently route around it by telling the player to read `log.txt`, which is not a fix.
