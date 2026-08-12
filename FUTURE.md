# Future work

Everything planned, deferred or still open, in one place. Collected 2026-08-06 from the plan documents
as they were retired.

**What this file is.** A plan describes one feature and is deleted once that feature is built — the
reasoning moves into the code as it is written, which is where it is actually read. What a plan cannot
hand to the code is the work it *didn't* do: the deferred item, the open call, the check nobody ran.
That is what lands here. Each entry says what the work is and why it was left, not how to do it.

Retired plans, if the full reasoning is wanted (`git show <sha>:<file>`):

| plan | last content at | covered |
| --- | --- | --- |
| `gps-course-plan.md` | `8e4cda3` (2026-08-02, the commit that removed it) | issue #43 — GPS course, section view, coverage |
| `farm-page-plan.md` | `3caa3e2` (2026-07-12) | the farm page, TaskList + CropRotation channels |
| `vehicle-data-plan.md` | `afe2585` (2026-07-25) | the vehicle export gaps, §1–§4 |
| `map-layers-plan.md` | `df511b3` (2026-07-25) | ground-layer overlays, per-plane split |
| `mission-plan.md` | `91e9fca` (2026-08-06) | issue #17 — contracts |

Still live: **`isobus-plan.md`** (issue #58). Its mod side is built; its app side is not, so the plan
stays as the working spec. It is indexed below rather than duplicated.

---

## The vehicle page never caught up with the export

The four rounds of `vehicle-data-plan.md` deliberately stopped at the data layer, on the rule *export
first, UI later*, and left every rendering decision to a redesign that was coming. The redesign
happened — display modes, the pillar cluster, pages, widget instances — **and did not consume that
inbox.** Checked 2026-08-06: `VDTerminal/app` contains no reference to `pipe`, `cover`, `discharge`,
`tipping`, `baleCounter`, `workMode`, `schema`, `selection` or `controlGroup`. All of it is exported,
none of it is drawn.

- **The rig diagram** — the one that matters most, because it is a UI limitation caused by a data gap
  that has since been closed. `RigSlotPanel` still finds its slots by string-matching `position ==
  "FRONT"` / `"BACK"`, so anything nested or sideways is unrepresentable: `examples/json/nested_trailers.json`
  is a committed fixture the app cannot draw. Mod version 4 exports what a real diagram needs —
  `schema` (the engine's own `schemaOverlay`: name, offsets, and per-joint `x`/`y`/`rotation`/`invertX`
  plus the lifted offsets) and `jointDescIndex` on each implement, which is the link that turns flat
  per-object data into a drawable tree. The layout arithmetic was kept **out** of the mod on purpose so
  the diagram can change without a mod release; `InputHelpDisplay:collectVehicleSchemaDisplayOverlays`
  is the algorithm to mirror, depth cap of 5 included.
- **Selection and control groups.** `selection.selected` is the engine's own per-object flag, so walking
  the tree finds the selected node without touching the root's ordered `selectableObjects`;
  `selection.controlGroup` is `{current, name, names}` off `spec_cylindered`. The game's own HUD prints
  only the group *number* — we have the names from the XML, so the dashboard can do better than the game.
- **Pipe and cover indicators**, on the vehicle and its implements. Both are objects now — `cover
  {state, index, count}`, `pipe {state, current, target, numStates}` — so a panel can say *which* cover
  of several is open and how far along a multi-state pipe is, rather than in-or-out.
- **The stepped fill bar.** `fillUnit.display == STEP` marks consumables, where capacity is a slot
  count: the game draws one segment per slot with the part-used roll's fraction inside the next one,
  and labels it `"2 / 2"` (a `ceil`, not a percentage). `components/FillUnitsDisplay.kt` carries the
  note and renders a continuous bar.
- **The work aspects.** `discharge`, `tipping`, `harvest`, `workMode`, `workWidth`, `baleCounter`.
  `discharge.reason` is the pick of them — the engine's own verdict on why unloading is refused
  (`NO_FREE_CAPACITY`, `NO_ACCESS_LAND`, …), the same code behind its on-screen warning, and nothing a
  dashboard could work out for itself.

### Two open calls on the mod side

- **`showOnHud` vs `showOnInfoHud`.** `aspects/FillUnit.lua` filters on `showOnInfoHud`, which is the
  *info-box* flag; the *fill-bar* flag a dashboard is mirroring is `showOnHud`. They are independent XML
  attributes, so today we drop units the game draws a bar for and show units it doesn't. Not taken,
  deliberately: the existing skip was added for a real symptom — a forage/carrot harvester's
  pass-through output showing up — and if those units carry `showOnHud="true"` the switch brings them
  straight back. It buys nothing observable today, so it waits on the in-game check below. If it is
  ever taken, keep our tolerance for zero-capacity units (mods that ship `capacity = 0`); that
  divergence from the game's `0 < capacity` gate is on purpose.
- **`Consumable` as a first-class block.** The fill unit already reports the right *level*. A dedicated
  block off `spec_consumable.types[]` would add which variation is loaded, `showWarning`,
  `allowRefillDialog` and the storage/consuming split — which is what a *"you're on your last roll"*
  alert needs, and nothing else. Do it only if that alert is wanted.

### Making pipe and cover controllable

An ordinary direct-call control, if it is wanted. `Pipe:setPipeState(state)` and
`Cover:setCoverState(state)` each take an **absolute** state and each own their multiplayer event
(`SetPipeStateEvent`, `SetCoverStateEvent`), which is exactly what the lossy command channel needs —
same shape as `LightControl` and `MotorControl`, which call the engine setters directly. Two quirks
worth knowing: `setPipeState` clamps to `numStates`, and `setCoverState` silently no-ops unless the
vehicle `hasCovers` and the state is within `0..#covers`.

This is **not** a departure from how `ImplementControl` works. That control routes lower/fold/activate
through FS25_additionalInputs' `vdAI*` functions because additionalInputs **already owns** that
spec-aware logic — attacher-joint lowering, fold-to-middle, `requiresPower`, the implement chain — and
hand-rolling it per spec was fragile (a self-propelled foldable like the Krone BigM reports "lowered"
via the Foldable fold-middle state, not Attachable, so a hand-rolled `setLoweredAll` no-ops on it). The
rule is to use what is in vdAI, **not** to extend vdAI with functions only VDTelemetry needs. There is
no pipe or cover function there, so calling the engine directly is the normal path, not an exception.

---

## ISOBUS (#58)

`isobus-plan.md` is still the spec; this is the index. Round 1's four aspects — `sowing`, `spraying`,
`plow`, `tillage` — are built, tested and captured against real machines. **The app side is not
started**: there is no `IsoBusApp`, no `IsoBusPanel`, no widget.

- **Round 1, app side.** The app, the panel (sections rendered by aspect presence, in the plan's order)
  and a widget with a slot `ConfigOption` — `FRONT` / `REAR` / `VEHICLE`, mirroring
  `RigSlotWidget.SLOT_KEY` — so a combination rig can carry a seeder tile and a sprayer tile at once.
  The one piece of refactoring it should do first: extract the section-shutoff bar and the work-area
  readout out of `RigSlotPanel` instead of reimplementing them.
- **Round 2 classes**, in rough value order: baler + wrapper (bale in progress, bale type, auto-drop —
  `Baler.lua` carries all of it), trailer / forage wagon, mixer wagon (the game ships a
  `MixerWagonHUDExtension` whose mixing-ratio bar is worth copying), then harvesters, as the issue
  suggests.
- **Round 2 controls.** Seed index (`setSeedIndex` / `changeSeedIndex` already send `SetSeedIndexEvent`),
  plough rotation (`setRotationMax` / `setRotationCenter`, both take `noEventSend` and own their event),
  the sprayer's doubled-amount toggle. None of them needs an MP event of our own.
- **A derived l/ha rate**, or not at all. `getSprayerUsage` scales by the machine's *speed limit* rather
  than its actual speed — that is how the game holds consumption per hectare constant — so what we
  export is `nominalUsagePerMin`, a rating rather than live draw. Turning it into a per-hectare figure
  means reasoning about what the engine is actually holding constant, checked against
  `processSprayerArea`. Precision Farming already publishes a true rate when it is installed.
- **Known gap:** `SaltSpreader` is a different specialization (WorkArea + TurnOnVehicle, no `Sprayer`),
  so road-salt equipment gets no aspect at all. Winter/road kit rather than a field implement — it needs
  its own collector if it ever matters.
- **Open:** whether the tillage aspect's `limitToField` is worth keeping. `Cultivator` registers no
  read/write stream, so on a client the field is whatever `onLoad` left behind. A call to make once it
  has been seen on a client.

---

## Missions (#17)

Built and validated in-game. Four things were left.

- **Command outcomes have nowhere to go — deliberately, for now.** The engine answers every action with
  a state (`MissionStartState` has 8 values, five of them ordinary refusals a user should see) and the
  mod can only log it. The call (2026-08-05) was to skip the reply path and revisit if it bites. It is
  mitigated rather than absent: the app greys accept at the farm's contract cap and hides the buttons
  without the `manageContracts` right, so the two likeliest refusals are prevented rather than reported,
  and the channel's next write is event-driven off `MISSION_STATUS_CHANGED` and lands within a tick. If
  it does bite, the options are a `lastCommand` block on the channel (cheap, fits the existing one-way
  plumbing) or a real mod→app reply channel (bigger — and every other control would then use it).
- **Mission vehicles.** Accepting with equipment spawns machines at the shop and `mission.vehicles` is on
  the object. Showing them, and where they are, is the natural round 2.
- **A time-out alert.** A running contract about to expire is an `AlertRule` candidate: `minutesLeft` is
  already on the wire, and `AlertEngine` / `KeyedAlertRule` already exist (`TasksApp` is the precedent).
- **How much detail belongs on a driving screen.** The panel is a menu; the widget is what someone
  glances at while working. The split wants the same review the ISOBUS layout got.
- **Out of scope, explicitly:** contracting (`MANAGE_CONTRACTING`). Working *for* another farm is a
  separate permission and a separate feature.

---

## Finance (#48, under #46)

Built and validated in-game: **singleplayer 2026-08-08** (a vehicle purchase and a farmland purchase
both appeared in the app's log as they appeared in the game's own notifications — the
`HUD:showMoneyChange` hook, end to end — and a month rollover shifted the table's columns correctly),
then **on a multiplayer client 2026-08-09**, which is the path the host never takes: the notification
funnel reached through `MoneyChangeEvent:run` rather than `FSBaseMission:broadcastNotifications`, and
archived columns refreshed by re-requesting `FinanceStatsEvent`. Both hold. A third pass **2026-08-11**
confirmed the current period's column ticks live on the 5 s interval — the only thing that surfaces it,
since `FarmStats:changeFinanceStats` mutates the bucket in place with no message behind it. Only a
handful of month rollovers have been watched, so that one is worth half an eye still. The design lives
in the module headers of `src/collect/FinanceExporter.lua` and `src/command/FinanceControl.lua`; the
checks below are what is still unseen.

- **The graphs.** The issue's own "Bonus: draw some graphs" was deliberately deferred until there was
  real data in the panel to shape a chart around. The export already feeds it: the mod carries up to
  twelve periods where the game has them (the app shows the in-game five by default), and each column
  carries its own `total`, so a per-month income/expense chart needs no mod change at all.
- **The money log is session-scoped and in memory.** It starts empty on every game launch, and a cap of
  100 entries drops the oldest. **The cap is settled for now (2026-08-11): 100 is enough in practice**,
  so neither raising it nor making it configurable is worth doing. Persisting the log is the same open
  question as everything under "VDT-owned data" below — it would be *savegame* state, and the FS25
  sandbox makes reading anything back an XML problem. Worth doing only if the log turns out to be
  something people look back through.
- **Command outcomes have nowhere to go**, same as Missions. `setLoan`'s two guards are deliberately
  asymmetric — a too-large borrow is **clamped** to the ceiling, an unaffordable repayment is
  **refused** outright — and either outcome only reaches a log line. Mitigated the same way: the app
  greys the buttons using `canManageLoan` / `loanMax` / the balance, so both outcomes are prevented
  rather than reported, and the channel is event-driven off `ChangeLoanEvent`, which should land the
  result within a tick — that last part is an expectation, not a measurement; it is the open in-game
  check under "In-game checks" below. **The prevention half is confirmed (2026-08-11):** a
  non-`farmManager`'s controls disable with a reason rather than firing a command the server drops.
- **The too-large-borrow clamp cannot be provoked from the app**, so it will never be seen to work.
  `LoanControls` coerces its target into `[0, max(loanMax, loan)]` and greys the step-up button at the
  ceiling, so no sequence of taps asks for more. It is still not dead code — it covers a command built
  against a *stale* `loanMax` (sell farmland between the export and the tap) — but that race cannot be
  staged on demand. Recorded so nobody deletes the clamp on the strength of it never firing.
- **The stat-row set is not fixed, and third-party buckets ride along for free.** The first capture
  came back with **34** rows rather than the base game's 33: a mod in that savegame had appended
  `dryingCharge` ("Trocknungsgebühren") to `FinanceStats.statNames`, and it arrived with its localized
  title and a correct column total without a line of code. That works because the exporter walks the
  live `statNames` table; hardcoding the base-game 33 would have silently dropped it. Nothing to do —
  recorded so nobody "tidies up" that read, and so nobody writes a test asserting a fixed row count.
- **Only five periods survive a save.** `FarmStats:saveToXMLFile` writes the current bucket plus four,
  so a long uninterrupted session accumulates more than five in memory and a reload drops back to five.
  Not a bug and not fixable from here — the app must simply cope with the column count changing.
- **`loanMax` on a freshly joined client may read `Farm.MIN_LOAN`.** `Farm:setInitialEconomy` calls
  `updateMaxLoan` before any farmland is known, and it is only recomputed on `FARM_PROPERTY_CHANGED`.
  The in-game screen reads the same cached field and has the identical quirk, so we match it rather
  than walking every placeable ourselves. If it bites in practice, say "up to 500,000" rather than
  recomputing equity.
---

## Enhanced Loan System (#47, under #46)

Built on top of #48. **Validated in-game on 2026-08-09, singleplayer and on a multiplayer client:**
the loans render instead of the base-game block, and `takeLoan` / `repayLoan` both land from a client
against a dedicated server — reaching the mod's own manager directly, as its in-game buttons do. **A
terminal-created loan then survived a server restart (2026-08-11)**, which is the end-to-end proof it
reached the server's table rather than only the client's: `ELS_loan` is a replicated Object, so a
client creating one sends `OBJECT_CREATED` and the server files it into its own loan table, while a
redemption's field changes ride the client's dirty-object update stream. The SP capture is committed as
`examples/json/finance/els.json` and now drives `FinanceModelTest`. The `enhancedLoans` block on the
finance channel carries the bank's terms and the farm's annuity loans; `takeLoan` / `repayLoan` drive
the mod's own `ELS_loanManager`. Its *presence* is the whole signal — when it is there the base-game
loan fields are absent and the app renders this instead, the same "dispatch on presence" rule the
ISOBUS sections use.

- **ELS does not disable base loans the way you would expect.** It overwrites
  `InGameMenuStatisticsFrame.hasPlayerLoanPermission`, a method on the in-game *frame*, leaving both
  `Platform.gameplay.hasLoans` and the `farmManager` right saying yes. Without the detector the
  terminal kept offering Borrow/Repay for a system the player no longer has, and `setLoan` would have
  created a base-game loan behind ELS's back. Fixed; recorded so nobody "simplifies" that check away.
- **It uses a different permission from the base loan.** ELS gates on `MANAGE_RIGHTS`, the base loan on
  `farmManager`, so `enhancedLoans.canManage` and `canManageLoan` are genuinely different questions and
  can disagree for the same player.
- **ELS clamps nothing outside its dialogs.** `addLoan` accepts any amount and any term; the mod's
  limits live in the GUI's text-input handlers, which a terminal never goes through. Every bound is
  therefore re-derived in `EnhancedLoanControl` — and the borrowing ceiling is recomputed *fresh* there
  rather than trusted from the read side's 30 s cache.
- **The ceiling is expensive.** `maxLoanAmountForFarm` walks every vehicle calling `getSellPrice()`
  plus every farmland — the same cost that made us read `farm.loanMax` rather than recompute equity for
  the base loan. Cached for 30 s. If that still shows up in a profile, the next step is to recompute it
  only while the app actually has the take-loan controls open.
- **The annuity formula is duplicated in the app**, so the take-loan panel can price a deal before the
  command goes — both the monthly instalment and the total the loan will cost, the second by running
  the amortization the way `calculateTotalAmount` runs it (monthly interest against an instalment
  priced on annual compounding, so the debt clears a month or two inside the term and the total lands
  *below* instalment × months). `FinanceFormatTest` pins both to the mod's arithmetic — the total to
  the figure the ELS capture carries — and the mod stays the authority.
- **Still open:** paid-off loans accumulate in the export (the mod keeps them forever, and the app only
  shows a count). If a long-running farm ends up with dozens, cap or summarise them mod-side.
- **Not built:** ELS's server settings (interest rate, mortgage ratios, max duration) are read-only
  here. They are a settings screen, not a finance one, and changing them from a terminal is a different
  feature.

---

## Invoices (#12, under #46)

The last child of #46, on top of #48. Billing between farms via FS25_Invoices, in its own event-driven
`invoices.json` channel (`src/integrations/Invoices.lua`) rendered as a second tab in the Finance app,
plus five commands (`src/command/InvoiceControl.lua`). **Validated in a two-farm multiplayer session on
2026-08-11:** an invoice raised from the terminal reaches the other farm, and paying it works as
expected — the core round trip, and the first time this repo has been exercised with two farms at all.
The narrower checks below are what that session did not cover. The mod's own server is the boundary
throughout: every command drives one of its service methods, which from a client sends its event, and
its server re-checks the `farmManager` right, the invoice's state, which farm the caller is, and (for a
payment) whether it can be afforded.

- **This feature has no singleplayer form.** An invoice needs two different farms and singleplayer has
  one, so the channel correctly exports the settings, the work-type catalogue, no farms and no invoices
  there. Every check below needs a **two-farm multiplayer session**, which is also the only place a
  fixture can be captured — `examples/json/invoices/invoices.json` came from the first one.
- **Paying an invoice moves two different numbers, and the VAT between them is destroyed.** The payer
  loses `total + penalty`; the issuer receives `totalHT + penalty`, and nobody collects the difference
  (`InvoiceService:executePayment`). That is the mod's simulation, not a rounding error — so the
  channel carries `totalDue` and `credit` separately and the app prints both. A single "total" would be
  a lie to one of the two parties.
- **Its money already lands in our finance table for free.** The mod registers `invoiceIncome` /
  `invoiceExpense` as `FinanceStats` buckets, so invoice payments show up in the monthly table and the
  money log with their localized titles and no code of ours — the same way `dryingCharge` did. Recorded
  so nobody adds a second accounting of them.
- **The manager is on the mission, not in the mod's environment.** `g_currentMission.invoicesManager`
  is reachable directly; only `Invoice` / `InvoiceService` need `FS25_Invoices.*`. Both are required
  before the channel reports available, because without the classes the state and unit tokens would
  have to be hardcoded numbers. *Considered and rejected*: recovering the class tables from the live
  objects (`getmetatable(manager.service).__index`), which would survive the mod being installed under
  a renamed zip — left out because the ELS and CropRotation integrations already bet on the env key and
  were validated that way, and the `Invoice` half only works when a repository row happens to exist.
- **Change detection hooks the mod's own `InvoiceService:notifyUI`**, which every mutation funnels
  through — creation, payment, deletion, proposal validation, the join sync, the penalty sync. Two
  things do *not* go through it and had to be added separately: `loadFromXML` at mission start (so
  `tick()` marks dirty once when it installs the hook), and **switching farm in game**, which changes
  who is asking rather than what is stored — every farm-scoped field in the document moves with it, so
  the channel subscribes to `MessageType.PLAYER_FARM_CHANGED` as well. A channel with a write interval
  would have self-corrected within seconds; this one is purely event-driven, so it would have kept
  showing the previous farm's invoices indefinitely.
- **A farm needs a NAME to be billable.** `InvoicesMainDashboard:loadFarms`'s `isValidFarm` requires a
  non-empty name on top of "not the spectator" — and a map or another mod can create a farm the player
  never sees (one server had a nameless *farm 14*). Mirrored in `VDT.Invoices.isBillableFarm`, used by
  both the exported recipient list and `createInvoice`'s guard, so the terminal cannot offer or send a
  recipient the mod would refuse. An invoice such a farm somehow raised is still *shown* — it just has
  no name, and the app falls back to "Farm 14".
- **The proposal direction inverts, and it is genuinely confusing.** A proposal is raised by the
  *payer* and answered by the *issuer*, so it is outgoing for the farm that asked and incoming for the
  farm that must approve it. The mod computes `direction` with FS25_Invoices' own accessors rather than
  letting the app rediscover the rule.
- **`actions` is what the buttons dispatch on**, mirroring the mod's server-side checks — deliberately
  *excluding* affordability, which moves faster than this channel writes. The app greys Pay against the
  finance channel's balance instead.
- **A discount's money value is recomputed, never reconstructed.** The mod's
  `Invoice.computeLineDiscountAmount` takes `computeLineGross(price, quantity, unit) - amount`; deriving
  it as `amount / (1 - discountRate)` instead disagrees by a unit or two, because the mod rounds twice
  (once on the gross, once after the discount). `InvoiceLine.grossAmount` does it the mod's way, and
  `Invoice.discountTotal` sums it for the list row and the detail footer, which is where the mod shows
  it too. The per-line rate is entered in the builder and clamped mod-side by the mod's own
  `sanitizeDiscountRate`.
- **A list row shows the tax-inclusive total in both directions** (`totalDue`), which is what the mod's
  own `InvoicesListRenderer` prints. `credit` — what the issuer actually banks, net of the destroyed
  VAT — is a *detail* figure, shown in the expanded totals and in the pay confirmation where there is
  room to explain it. A row that showed `credit` for outgoing invoices would not add up against the
  header total above it.
- **`createInvoice` is the first command with child elements.** Nothing in the channel prevented it —
  `CommandChannel.poll` already hands each control the live `XMLFile` and its key — but `CommandWriter`
  grew an open/close form for it, and line quantities go through `BigDecimal` so a ten-million-litre
  figure does not reach the mod as `1.0E7`.
- **The mod's server-side sanitising only runs on the client→server path.** On a host,
  `createAndSendInvoice` is called directly and nothing recomputes the totals — so `InvoiceControl`
  builds line amounts with the mod's own `Invoice.computeLineAmount` and totals with
  `populateFromData`, correct on both paths rather than only on the re-checked one.
- **Not built: the three picker-backed line types.** Vehicle sale, consumable (pallet/bale) sale and
  product (fillType) sale transfer ownership of real objects on payment, which a command cannot
  assemble — each would need a new pick-list export of its own. They are exported with a `needsPicker`
  token and shown in the builder as in-game-only, rather than silently dropped.
- **Not built: writing the mod's settings** (VAT simulation, penalties, reminders). They are
  `serverOnly` and would be one more command, but they are a server-economy screen rather than a
  finance one — the same call as ELS's settings above.
- **Open: how long the list stays usable.** Paid invoices are never deleted by the mod, so a
  long-running server accumulates them. The app sinks them below the live ones and offers a direction
  filter; if that stops being enough, cap or summarise them mod-side (the same shape as ELS's
  paid-off-loan problem).
- **Command outcomes have nowhere to go**, same as Missions and the loans. `actions` is recomputed on
  every write and the channel writes on every mutation, so the window is one file write — but a
  proposal the other party validates between our write and the player's tap is simply refused by the
  mod's server, leaving only a log line. Mitigated the same way as `setLoan`: the app greys the button
  from `actions` and the balance, so the outcome is prevented rather than reported.
- **The builder's line cap is the mod's, not a usable one.** `ClientMessage.CreateInvoice` rejects more
  than 100 lines because that is where the mod's own server refuses, and the builder disables "Add
  line" at that point — but a terminal form gets unwieldy long before 100. If anyone ever fills one,
  the answer is a lower soft cap in the app, not a change to the contract.
- **Not built: per-line names.** The in-game wizard lets a player rename a line (`customLabel`, and
  `customLabelByField` for field work); the terminal sends the work type's own localized name. A
  free-text name is one more optional attribute on `<line/>` if it turns out to matter — the read side
  already carries `InvoiceLine.name` and displays it.

### In-game checks nobody has run

All of these need a **two-farm multiplayer session**. Creating and paying an invoice is done
(2026-08-11); what is left is everything that round trip does not touch.

- **Both creation paths.** The 2026-08-11 session exercised one of them. `createAndSendInvoice` is
  called directly on a **host** and nothing recomputes the totals, where a **client**'s goes through the
  mod's server-side sanitising — so the untested side of that asymmetry still wants a look, and it is
  the one place the two could disagree.
- The channel writes on join (the `applySyncData` path through `notifyUI`), and a savegame's existing
  invoices appear without waiting for a change (the first-sight `markDirty`).
- A payment shows up in the finance table under `invoiceExpense` / `invoiceIncome`. Should follow from
  the mod registering the buckets, but it is one glance at a panel that is already open.
- A proposal raised from the payer side can be validated, and refused, from the issuer side.
- Letting one go overdue (two period rollovers) lands `penalty`, `overdue` and the recomputed
  `totalDue`, and `daysUntilPenalty` counted down honestly on the way there.
- A non-`farmManager` player gets no `actions` at all rather than buttons that fail.
- The localized work-type and unit labels resolve through `customEnv` — no `Missing 'invoice_work_…'`
  strings reach the panel — on a non-English client too.

---

## Map layers

Both remaining items were declined on 2026-07-25. They are kept as the record of what they would cost,
not as a queue.

- **Sub-value toggles inside a layer** — hide weeds, keep needs-plowing. The app has the legends but
  **not** the raster cell values; those exist only in the server-rendered PNG. So this is not app-only
  work. For **crops** and **growth**, where each cell holds exactly one value with its own legend
  colour, it is feasible as a server render-filter (`/api/map-layer/{id}?hide=…`, enabled values drawn
  and the rest transparent) with no data-model change. The alternative — raster rows to the app, which
  draws and filters the bitmap itself — is a big shift away from the deliberate "PNG server-side,
  legends-only over the WebSocket" design.
- **Independent soil toggles need the data de-collapsed first.** `classifySoil` returns *one* value per
  cell by priority (weeds > stones > needs-plow > needs-lime > fertilized), mirroring the game, so a cell
  that is both weedy and needs plowing stores only "weeds" — the plough state underneath was never
  captured, and hiding weeds cannot reveal it. Doing it properly means promoting each soil sub-state to
  its own single-value plane: mod classification, wire model, and stacking order in the app. Only if
  independent soil visibility is actually wanted; if it is picked up, fold it into one coherent re-model
  of the layer set rather than two passes.
- **The per-frame tick.** The per-plane split left VDTelemetry's own tick at 0.5–0.6% of script time —
  the scheduler itself, not any one channel. `0b391c7` trimmed the idle path (skip channels with neither
  cadence nor tick, bail out of `writeDirty` while nothing is queued, throttle the offered-layer recheck
  to 5 s), and it is worth re-reading the profiler after that. The mod has to run *something* every
  frame, so this entry never goes away entirely.

---

## Channel cadence and profiles

The scheduler, per-channel `enabled`/`intervalMs`, the profile presets and the Kotlin cadence
measurement all shipped. One thing was left, and it is a trap rather than a feature:

- **There is no app→mod config push.** Per-channel config is read from the settings XML **at load
  only**, and the mod rewrites that XML on any in-game change — so an app that edits it live gets
  clobbered. Wiring per-channel tuning into the app needs a push channel of its own, the way the
  command channel works. Checked 2026-08-06: nothing in `shared` carries channel config.
- Related, if that is built: a per-channel `intervalOverride` is **ignored unless the profile is
  `custom`**, so an app doing the tuning has to stamp `profile = custom` as it goes.

---

## VDT-owned data

Every write path today drives a *mod's* own state through its own multiplayer events; VDTelemetry
persists nothing of its own. The first real case for changing that:

### Assigning a CropRotation plan to a field

CropRotation's planner stores plans as a flat list and carries **nothing that ties a plan to a field** —
there is no notion of "field 7 follows the *Heavy Soil* rotation"; you read the plan and apply it by
hand. A VDT-owned map of `fieldId → rotation index` (plus, plausibly, the current position in the
rotation, so the app can say *what to plant next*) is exactly the kind of data VDT can own without
fighting the mod. Field ids come from `g_fieldManager`; joining our map against `cropRotations[index]`
gives a per-field "assigned rotation" view, and against the field's current fruit a "next crop" hint.
The write side is a VDT command against a VDT store, **not** a mutation of the mod.

Four things to work out when it is picked up:

- **Persistence location and lifetime.** This is *savegame* state, not client settings — two savegames
  must not share a field→rotation map. Keying by savegame id inside `modSettings/<modName>/`, versus
  writing into the savegame directory the way both target mods do (both hook off
  `FSBaseMission.saveSavegame`), is an open choice. The latter matches the neighbours and gets save/load
  timing for free.
- **Reading it back is XML, not JSON.** The FS25 Lua sandbox restricts `io.open` to write mode — which is
  why the command channel is XML in the first place (see `CommandChannel.lua`). Any store the mod has to
  *read* has the same constraint, whatever we emit for telemetry.
- **Referential integrity.** Plans are referenced by `index`, and `addDeleteCropRotations` deletes by
  clearing the slot, so an assignment can dangle. Resolve dangling references on load and treat a
  missing plan as "unassigned" rather than an error.
- **Multiplayer.** Unlike task and rotation edits, this state has no mod event to ride on. Either scope
  it to the local client — simplest, and consistent with telemetry being client-side only — or build a
  VDT sync event. Start local.

### CropRotation position / field data

Sampling the crop-history density map at the player's position
(`historyStateManager.historyStates[i].map:getState`, `YieldCalculator:potentialYieldAtPosition`,
`:getYieldMultiplier`), or sweeping `g_fieldManager:getFields()` for a per-field table. Both need a
timer, because the position changes as you drive, and both need in-game profiling before they are
trusted. The channel registry already supports it: a channel whose `markDirty()` is driven by a position
bucket rather than by a message.

---

## In-game checks nobody has run

Each one is cheap to do while playing and settles something above.

- Does a base-game baler set `uiDisplayType="STEP"` on its consumable fill unit? It is visible in the
  exported JSON as `display`, so this is just a matter of looking. Decides whether the stepped bar is
  worth building.
- Does a multi-state pipe report sensibly — an auger wagon should give `pipe.numStates > 2`? Read the
  JSON; nothing renders it.
- Does a multi-cover vehicle really report `cover.state > 1`? Parked for want of such a vehicle, and low
  risk: a single-cover vehicle only ever has state 0 or 1, where the old and new mappers agree exactly,
  so the only changed behaviour is `state >= 2`, which used to return `UNKNOWN` and was wrong by
  construction. The tell that you are looking at one is the action prompt reading **"Next cover"** rather
  than "Open/Close cover" — `Cover:updateActionText` uses that string only while `0 < state < #covers`.
- Does `schema` come out populated on a real rig, and does `jointDescIndex` line up with the parent's
  `attacherJoint` list? The one thing the synthetic tests cannot confirm.
- Does `controlGroup` populate on a front loader or crane, with sensible `names`? They come from vehicle
  XML and may be unresolved i18n keys on some mods. Same question for `workMode.name`.
- Does `discharge.reason` read `NO_FREE_CAPACITY` when the game refuses to unload? Back a trailer up to
  a full silo. This is the highest-value single check of the work aspects.
- Do any fill units in normal use differ between `showOnHud` and `showOnInfoHud` — in particular, does a
  forage/carrot harvester's pass-through output carry `showOnHud="true"`? This gates the filter switch
  above.
- Does borrowing from the terminal land without waiting out the 5 s interval? It should: the mod
  subscribes to `ChangeLoanEvent`, which the engine publishes on both sides of the wire. Note it is
  about the *base-game* loan, so an ELS save cannot answer it.

## Steering (#57)

Built and driven: `vehicle.steering` (mod version 10) and its two marks in the pillar readout, left of
the gear. The layout derivation was checked in game against a machine with dog-walk modes, sides and
all. What is left:

- **`CRAB` has never been seen on a real machine.** It is the sideless dog walk — the rear axle steered
  along with the front rather than held over — and it is in the model because the engine's data allows
  it (a mode may set a steering node's `rotScale` negative with no offset), not because anything is
  known to be built that way. The machines checked so far all use the offset, which is `CRAB_LEFT` /
  `CRAB_RIGHT`. If it never turns up, the value and its share of the left glyph can go.
- **A frame that steers on its own joint reports no layout.** `spec_articulatedAxis` is a third
  mechanism the derivation doesn't read, so an articulated machine falls back to printing the mode's
  number. Left out deliberately: it needs its own geometry and there is no way to tell here whether any
  machine with steering modes actually uses it.
- **Neither half is controllable.** `setCrabSteering(state)` and `setIsReverseDriving(state)` each take
  an absolute value and each own their multiplayer event (`SetCrabSteeringEvent`,
  `ReverseDrivingSetStateEvent`) — the same shape as `LightControl`, and the same reasoning as the pipe
  and cover controls above. Not built because #57 asked to *see* them. `setIsReverseDriving` refuses
  while an implement on a disabling attacher joint is fitted (`getIsReverseDrivingAllowed`), which a
  control would want to reflect rather than fire and ignore.

## Precision Farming rates (#77)

Built: the arrow in the rate readout is a Material Icon rather than a "→" that renders as tofu, and the
manual application rate is exported (`precisionFarming.manual`, mod version 11), rendered, and
drivable from the rig panel — auto/manual on the chip, the step on the two buttons either side.

- **In game, nobody has driven it yet.** The Lua side is covered synthetically; the checks that matter
  are that the mode chip and the step follow PF's own HUD on a real spreader, that the product rate
  agrees with what that HUD prints in all four machine kinds (kg/ha, l/ha, m³/ha, t/ha), and that a
  step from the terminal survives a **multiplayer client** — `setSprayAmountManualValue` sends
  `ExtendedSprayerAmountEvent` to the server from there, which is the path the specs cannot exercise.
- **The live rate in auto mode is not exported.** `spec.lastLitersPerHectar` is what PF's HUD prints
  when the tool picks its own rate, and it is maintained on clients too (`getSprayerUsage` runs from
  `onStartWorkAreaProcessing`, with no `isServer` gate). It was left out because it is only meaningful
  while the tool is turned on and working, so it needs a "nothing coming out right now" state the
  step-derived manual rate does not — and the manual rate was what the issue asked for.
- **PF's third keybind is not mirrored.** In auto with no crop in the ground, `TOGGLE_SEEDS` cycles
  which fruit the tool fertilises *for* (`setSprayAmountDefaultFruitRequirementIndex`, off
  `nApplyAutoModeFruitRequirementDefaultIndex`). It changes the auto target, so it belongs next to
  these two if auto mode is ever given more than a badge — and it would need the fruit list exported,
  which nothing does today.
- **The step is a rig-wide command.** It addresses whatever PF calls the rig's valid sprayer rather
  than a slot, so a hypothetical rig with two PF machines is driven as one. That is PF's own model
  (`getValidSprayerToUse` returns the first valid machine), and a rig you would tow two sprayers on is
  not a rig anyone drives — but it is the assumption to revisit if one ever turns up.

## Captures wanted as fixtures

The schema, selection and work aspects are all tested synthetically, because none of the committed
captures contains a machine that has them.

- **A tipping trailer** and **a baler.** Between them they cover `tipping`, `discharge`, `baleCounter`,
  the `STEP` consumable bar, and they would give `jointDescIndex` its first real chain.
- **More finance captures.** `examples/json/finance/vanilla.json` is a fresh singleplayer save, so it
  has one period and an empty log. Still wanted: **a played-in save** (several archived periods, to see
  how many a real game carries), **an MP client** (does the history really stop at five?), and **one
  with notifications in the log** — the hook itself is confirmed working in singleplayer, so this is
  now wanted as a fixture rather than as proof. `FinanceModelTest` covers those three shapes with
  inline JSON meanwhile.
- **More invoices captures.** `examples/json/invoices/invoices.json` came out of the 2026-08-11
  two-farm session and drives `InvoicesModelTest.parsesTheTwoFarmCapture`: three invoices from farm 1's
  side, one of them a proposal showing the direction inversion, a discounted line, and the full 56-entry
  German work-type catalogue. What it does not contain, because that session never got there: an
  **incoming** invoice, a **paid** one, and one that has **accrued a penalty**. `InvoicesModelTest`
  covers those three with inline JSON meanwhile, and says so at the top.
- The rule these follow: fixtures are **real game captures, never hand-authored**. A hand-written file
  claiming to be a capture was rejected before, and fill-type names live in `fillTypes.xml`, which is not
  readable from here — inventing them would put made-up game data in `examples/json`.

---

## Accepted limitations — not bugs, and not worth re-deriving

- **The wasm build has no font fallback, so an exotic glyph in a `Text` renders as tofu.** A browser
  falls back through the system's fonts; Compose/wasm draws into a canvas with the fonts it bundles,
  which here are the two DSEG faces plus the default — and that default does not cover Geometric Shapes
  or Dingbats. `▲ ▼ ✕` therefore came out as boxes in the finance sort headers, the invoice direction
  mark and the builder's remove button (fixed 2026-08-11 by using Material `Icon`s, which are vectors
  and depend on no font at all). `SectionView`'s `"$level → $target"` rate readout is confirmed to have
  it too and has an issue of its own — it is a character inside a sentence rather than a standalone
  mark, so it wants a different answer. Latin-1 and General Punctuation are fine — `— · × − …` are used
  throughout and render. The rule: **a mark that carries meaning is an `Icon`, not a character.** If a
  new glyph is genuinely needed as text, look at it in a browser before shipping it.

- **A 2-slot crop rotation's dropdown preview can be slightly off.** The 2-deep history window wraps
  modulo the rotation length, so in a 2-slot rotation "two back" lands on the slot itself, and the
  preview reads that self-reference from the slot's *stored* crop rather than the hovered candidate.
  Verified in-game and left as-is: rotations that short carry almost no history.
- **Crop names follow the game's language; the app localizes nothing.** The mod hands over strings the
  game has already localized — crop names, mission subtitles, the game's own contract detail rows —
  and anything the app writes itself is English. A real localization story would be its own piece of work.
- **A half-upgraded install fails the parse.** `ignoreUnknownKeys` covers a newer mod against an older
  terminal but not the reverse: a v2 mod emitting `"pipe": "RETRACTED"` against a v3+ terminal is a
  string where an object is expected, which `coerceInputValues` does not rescue — it fails the whole
  parse. Mod and terminal ship from the same repo, so this only bites a mixed install. A union
  deserializer would fix it if it ever proves a real support burden.
- **`CROP_ROTATIONS_CHANGED` never reserves its id.** `FS25_CropRotation` sets it by *counting* existing
  `MessageType` entries instead of calling `nextMessageTypeId()`, so depending on mod load order another
  mod can be handed the same id. Harmless for us — the CropRotation channel is poll-driven and nothing
  subscribes to that message — but do not build anything load-bearing on it.
- **Third-party internals are pinned, not stable.** Both optional integrations read another mod's
  internals, so each header pins the version it was written against — FS25_TaskList `1.2.0.1`,
  FS25_CropRotation `1.0.1.0` — and fails soft: guard every field read, `pcall` the yield maths, treat a
  missing field as "no data", because a throw in a collector takes the whole telemetry write down with
  it. The risk does not go away; a mod update can still empty a panel, and the pin is what tells the next
  reader where to look.
