# Finance app — the farm's books (issue #48, under #46)

Plan for a Finance app: the month-by-month overview the in-game finances screen shows, the money
notifications as a running log, and base-game loans you can take and repay from the terminal.

Written and implemented 2026-08-07 against `main` @ `058dd8b` (mod `VERSION 10`), on branch
`48-implement-financial-app`.

Status: **built, singleplayer-validated in-game 2026-08-08** — the notification hook confirmed with a
vehicle and a farmland purchase, and one month rollover shifted the table's columns correctly.
Multiplayer is still unproven. Delete this file when the branch merges: the reasoning has moved into
the module headers, and the leftovers are in `FUTURE.md` ("Finance (#48, under #46)" and the in-game
checks).

Issue #46 has three children. This plan covers **#48 only** — base game. #47 (Enhanced Loan System,
which *replaces* base-game loans) and #12 (Invoices, bidirectional like Tasks) come after, and the
"where #47 plugs in" section below exists so this round doesn't paint them into a corner.

Scope for round 1, decided with the user before writing this:

- **All three of the issue's bullets**: the multi-month overview, the notification history, and
  borrow/repay. The issue's fourth bullet — *"Bonus: draw some graphs"* — is **deferred to a round 2**,
  once there's real data in the panel to shape a chart around. The export is designed to feed it (see
  "History depth").
- **History depth**: export up to **12 periods** where the game has them, not just the in-game 5. The
  app shows 5 by default, matching the game; the rest is what a trend graph will want.
- **Loans are absolute**: one `setLoan(amount)` command carrying the *target* loan, not a delta. See
  "The write path".

---

## Where the data lives

All of it is base-game state reachable from a client, so this is a `collect/` channel, not an
integration. Everything below is from the game's own Lua (bundled by the `fs25-modding-skill`), cited
by function name.

### The overview table

`Farm.stats` is a `FarmStats`, which owns two finance fields:

| Field | What it is |
| --- | --- |
| `stats.finances` | a `FinanceStats` for the **current period** |
| `stats.financesHistory` | array of archived `FinanceStats`, one per past period, oldest first |

A `FinanceStats` is 33 named buckets in the base game — `FinanceStats.statNames` on PC (the mobile
build has fewer, and **a mod can append its own**, so this is read live, never hardcoded):

```
newVehiclesCost soldVehicles newHandtoolsCost soldHandtools newAnimalsCost soldAnimals
constructionCost soldBuildings fieldPurchase fieldSelling vehicleRunningCost vehicleLeasingCost
propertyMaintenance propertyIncome productionCosts soldWood soldBales soldWool soldMilk soldProducts
purchaseFuel purchaseSeeds purchaseFertilizer purchaseSaplings purchaseWater purchaseBales
purchasePallets harvestIncome incomeBga missionIncome wagePayment other loanInterest
```

Each is a signed currency amount (expenses negative), fed by `FarmStats:changeFinanceStats`, which
`Farm:changeBalance` calls with the `MoneyType`'s `statistic`. `FinanceStats.statNamesI18n[name]` holds
the localized row label (`g_i18n:getText("finance_" .. name)`), populated in `FinanceStats.new`.

**Archiving is per period, i.e. per in-game month**: `Farm:periodChanged` (subscribed to
`MessageType.PERIOD_CHANGED`) calls `FarmStats:archiveFinances`, which pushes the current bucket onto
`financesHistory` and starts a fresh one. The mobile build has a shorter `statNames` list; we read the
live table rather than hardcoding, so that falls out for free.

The in-game screen (`InGameMenuStatisticsFrame`, the Finances sub-category) renders exactly this:

- `FINANCES.PAST_PERIOD_COUNT = 4` columns of history plus the current one, headed with
  `g_i18n:formatPeriod(period, false)` (`InGameMenuStatisticsFrame:updateFinances`).
- one row per `FinanceStats.statNames` entry, labelled from `statNamesI18n`
  (`:populateCellForItemInSection`).
- a footer with the balance, the loan, and a per-column total — the sum over every stat name
  (`:updateDayTotals`).

**Only 5 survive a save.** `FarmStats:saveToXMLFile` writes the current bucket plus at most the last
four, and `:loadFromXMLFile` reads back what it finds. So a long uninterrupted session accumulates
more than five in memory and a reload drops back to five. Exporting up to 12 costs nothing and is
honest about what is there; it just won't always be 12.

### The loan

On `Farm`:

| Field / method | |
| --- | --- |
| `farm.loan` | current loan; streamed in `Farm:writeStream`, so a client has it |
| `farm.loanMax` | borrowing ceiling, maintained by `Farm:updateMaxLoan` |
| `farm:getBalance()` | the money |
| `farm:calculateDailyLoanInterest()` | `floor(rate / daysInYear * loan)` — the daily bite |

Constants on `Farm`: `MIN_LOAN` 500 000, `MAX_LOAN` 3 000 000, `EQUITY_LOAN_RATIO` 0.8,
`LOAN_INTEREST_RATE` 0.04. `updateMaxLoan` snaps `0.8 × equity` to 5 000 and clamps into
[MIN, MAX]; it runs on `MessageType.FARM_PROPERTY_CHANGED`, which `Farm` subscribes to on client and
server alike.

We **read `farm.loanMax`, we do not recompute it.** `Farm:getEquity` walks every farmland and every
placeable on the map; doing that on our cadence would be the most expensive thing in the mod, and the
in-game screen reads the cached field too — so we are exactly as accurate as the game's own UI, which
is the bar.

`InGameMenuStatisticsFrame:updateFinancesLoanButtons` is the rule set worth mirroring:

- Borrow is offered when `farm.loan < farm.loanMax`.
- Repay is offered when `farm.loan > 0` **and** `farm.money >= FINANCES.LOAN_STEP` (5 000).
- Both need `getHasPlayerPermission("farmManager")` (`:hasPlayerLoanPermission`).
- The whole block is gated on `Platform.gameplay.hasLoans` — false on some platforms.

### The notifications

There is exactly one client-side funnel for the money pop-ups: **`HUD:showMoneyChange(moneyType, text)`**.

- **Singleplayer / host**: `FSBaseMission:addMoneyChange` accumulates per money type into
  `mission.moneyChanges`, and `FSBaseMission:broadcastNotifications` calls `hud:showMoneyChange` when
  the local farm is the one affected.
- **Multiplayer client**: `MoneyChangeEvent:run` calls `hud:addMoneyChange` then
  `hud:showMoneyChange` — and guards on `g_currentMission:getFarmId() == self.farmId` first.

So the hook is farm-scoped for free, and fires in both topologies. `showMoneyChange` reads
`self.moneyChanges[moneyType.id]`, formats `"+ 12,345 €(Harvest income)"`, pushes it to
`addSideNotification`, and **zeroes the bucket on the way out** — which is why we must
**prepend**, not append:

```lua
HUD.showMoneyChange = Utils.prependedFunction(HUD.showMoneyChange, VDT.FinanceExporter.onMoneyChange)
```

Two details to copy rather than invent:

- The label is `g_i18n:getText(moneyType.title, moneyType.customEnv)` unless the caller passed `text`,
  in which case `text` is **already localized** by the time it reaches the HUD (both `MoneyChangeEvent`
  and `broadcastNotifications` run it through `g_i18n` first). Use `text` when present.
- The game suppresses a notification for a change in `(-1, 0)` — `change > 0` or `change <= -1`. Match
  it, so the log holds exactly what the player saw.

`moneyType.statistic` (e.g. `"harvestIncome"`) joins each log entry to a row of the overview table.
Note `MoneyType.LOAN` carries `statistic = "loan"`, which is **not** a `FinanceStats.statNames` entry —
so taking a loan never lands in the table. That is correct: it is a balance-sheet move, not income.

---

## Multiplayer

The one part of this feature that needs the mod to *ask* for something.

`FarmStats` is server state and is **not** in `Farm:writeStream`. Clients get finance data pushed as
`FinanceStatsEvent`:

- **At join**, `FSBaseMission:sendInitialClientState` sends `ChangeLoanEvent` plus
  `FinanceStatsEvent.new(i, farmId)` for `i = 0..4` — the current period and four of history.
- **Then, every ~5 s**, `FSBaseMission:update` re-sends **index 0 only**, and only when
  `farm.stats.financesVersionCounter` has moved since that user last got one.

So on a client the current column stays fresh by itself, and the **history columns go stale after a
month rolls over**. The in-game screen fixes this in `InGameMenuStatisticsFrame:update`: when
`financesHistoryVersionCounter ~= financesHistoryVersionCounterLocal`, it re-requests `i = 1..4`.

We do the same, with one deliberate difference: **keep our own copy of the counter** rather than
writing the game's `financesHistoryVersionCounterLocal`. Clobbering that field would make the in-game
screen think it was already up to date and skip its own refresh. The cost of two independent watchers
is a handful of duplicate requests after a month boundary; the server just answers them.

Gated on `not g_currentMission:getIsServer()`, throttled to 5 s, and skipped entirely when the channel
is disabled. `FinanceStatsEvent.new` asserts `0 <= historyIndex <= 255`, so the request loop is capped
at what we intend to show.

**Consequence for the export**: an MP client can only ever have 5 periods (the server never offers
more unsolicited, and the client has no way to know how many exist). Twelve periods is a
singleplayer/host reality. The app must handle a short `periods` array, which it does anyway.

---

## The channel: `finance.json`

One channel, not two. The table and the log are one app and one screen, the whole file is a couple of
KB, and splitting by cadence — the reason `production` and `storage` are separate — buys nothing here
because both halves move on the same slow clock.

```
name        finance
fileName    finance.json
VERSION     1                  (own version, like husbandry/missions)
intervalMs  5000
```

**5 s** because that is how often an MP client's own current-period data refreshes; being faster would
export the same numbers again. On top of the interval, three event kicks:

- the `showMoneyChange` hook — a new log entry should land while the pop-up is still on screen;
- `MessageType.PERIOD_CHANGED` — the whole table shifts a column;
- `ChangeLoanEvent` (the game publishes it after `Farm.loan` moves) — so borrow/repay feels instant.

**Deliberately not `MessageType.MONEY_CHANGED`.** `Farm:changeBalance` publishes it on any change ≥ 1
unit, which includes vehicle running costs ticking while you drive. Marking dirty on that would push
the channel to frame rate through `writeDirty`'s one-heavy-channel-per-frame drain. The 5 s interval
covers the balance perfectly well.

Subscriptions and the hook are installed lazily from `tick()`, the way `MissionExporter` subscribes —
`MessageType` ids and the `HUD` class both exist by then, and a disabled channel never ticks.

### Shape

```json
{
  "version": "1",
  "balance": 1284310,
  "loan": 300000,
  "loanMax": 500000,
  "loanStep": 5000,
  "loanInterestPerDay": 32,
  "loansAvailable": true,
  "canManageLoan": true,
  "periods": [
    { "index": 0, "period": 6, "label": "August", "year": 2026, "current": true, "total": -12345 },
    { "index": 1, "period": 5, "label": "July",   "year": 2026, "total": 98765 }
  ],
  "stats": [
    { "name": "harvestIncome", "title": "Harvest income", "values": [12000, 0, 45000, 0, 0] },
    { "name": "purchaseFuel",  "title": "Fuel",           "values": [-1250, -980, -3100, 0, 0] }
  ],
  "history": [
    {
      "seq": 41, "amount": -1250, "type": "purchaseFuel", "title": "Fuel",
      "date": "12.08.2026", "time": "14:32"
    }
  ]
}
```

Decisions baked into that shape:

- **Row-major, not column-major.** `stats[].values[i]` aligns with `periods[i]`. This is how the
  in-game table reads, it carries the localized `title` once instead of once per column, and the app
  renders rows × columns without a join.
- **Newest first, `index 0` is the current period.** Matches `FinanceStatsEvent`'s own `historyIndex`
  convention. The in-game screen prints oldest-left; the app reverses for display, which is a view
  concern.
- **Every stat row is exported, including all-zero ones.** The mod stays a faithful mirror of
  `statNames` — including any bucket a third-party mod appended; hiding empty rows is a toggle the app
  can offer without a mod round-trip. 33-odd rows × up to 12 values is ~2 KB.
- **`name` is the raw stat name, `title` is the game's localization.** Same split as everywhere else in
  this repo: the token is the stable key, the title is what the game already translated.
- **`date` / `time` use `EnvironmentExporter`'s format** (`DD.MM.YYYY`, `2023 + currentYear`,
  `ValueMapper.mapPeriodToMonth`), so a history row reads the same as `environment.date` in the main
  telemetry file.
- **`history` is capped at 100 entries, newest first**, and is **session-scoped in-memory**. The mod
  persists nothing of its own today (see FUTURE.md, "VDT-owned data"), and a money log is exactly the
  kind of thing that would need a savegame-keyed store to be worth persisting. Noted as a follow-up
  rather than smuggled in.
- **`canManageLoan` / `loansAvailable`** drive whether the app renders the loan controls at all —
  mirroring `hasPlayerLoanPermission` and `Platform.gameplay.hasLoans`. The server re-checks the
  permission when the event lands; this is for the UI, not the boundary.
- **Spectator / no farm**: `{ "version": "1" }` and nothing else, same as the other channels.

`period` and `year` are both carried so a 12-period export doesn't show two ambiguous "August"s. `year`
walks backwards from `environment.currentYear`, decrementing when the period wraps below 1.

---

## The write path: `setLoan`

One command, carrying the **target** loan amount:

```xml
<command id="7" type="setLoan" amount="250000"/>
```

The engine's `ChangeLoanEvent.new(loanValue, farmId)` takes a **delta** when a client sends it to the
server; the server clamps `curLoan + delta` into `[0, max(loanMax, curLoan)]`, calls
`farm:changeBalance(delta)` with the *actual* clamped delta, and broadcasts the new absolute loan back.

So the mod computes `delta = target - farm.loan` at execution time and sends
`ChangeLoanEvent.new(delta, farmId)` through `g_client:getServerConnection():sendEvent(...)` — exactly
what `InGameMenuStatisticsFrame:onButtonBorrow` / `:onButtonRepay` do, just with a caller-chosen step
instead of a hardcoded 5 000.

**Why absolute rather than a delta on the wire**: it is the repo's stated command rule (`Protocol.kt`),
and it is genuinely better here. A redelivered `setLoan(250000)` computes `delta = 0` and does nothing;
a redelivered `+5000` doubles the borrow. The channel is at-most-once by id either way, but
self-correcting beats relying on that.

Guards mod-side, mirroring the in-game screen so a refusal leaves a log line instead of a silent no-op:

- `getHasPlayerPermission("farmManager")`, else refuse.
- `Platform.gameplay.hasLoans`, else refuse.
- target is a finite number `>= 0`, and `delta ~= 0` (nothing to do).
- borrowing: refuse a target above `max(loanMax, loan)` — the server would clamp it anyway, but
  refusing means the app's optimistic state and the game's agree.
- repaying: refuse when `-delta > farm.money`. The engine does **not** check this — `changeBalance`
  will happily push the balance negative — but the in-game screen won't offer the button, so neither
  do we. This is the one place we are stricter than the engine, and it is on purpose.

`requiresVehicle = false`. The app offers ±5 000 quick taps plus a stepper for larger jumps, snapped
to 5 000 (which is the granularity `loanMax` is snapped to), and confirms before a repayment that
takes the balance under a threshold.

---

## App shape

New app `finance`, registered in `AppRegistry` after `MissionsApp`. Always available — base-game data,
so the panel renders its own waiting/empty states, like `AnimalsApp`.

`FinancePanel(finance: FinanceData?, onCommand: (ClientMessage) -> Unit)`:

1. **Headline strip** — balance (negative styled red, as `InGameMenuStatisticsFrame:onMoneyChange`
   does), loan, and the daily interest it is costing.
2. **The month table** — rows × 5 period columns, expandable to whatever the export carried. Positive
   green / negative red, a totals row, and a "hide empty rows" toggle (33-odd rows is a lot on a small
   display, and most are zero in any given month). Sortable headers, following the pattern the animals
   table just landed with.
3. **The log** — a feed of money notifications, newest first, each with its game date/time, localized
   label, and signed amount.
4. **Loan controls** — borrow/repay, hidden entirely when `loansAvailable` is false and disabled with
   a reason when `canManageLoan` is false. Confirm dialog on the way out for large repayments.

Plus a **balance widget** for the widget catalog (`VdtApp.widgets`) — balance and loan on a tile is the
obvious thing to want on a dashboard page.

Wiring, all of it existing pattern: `ServerMessage.Finance(data)` in `Protocol.kt`,
`VdtParser.parseFinance`, `watcher.register("finance.json", nullOnAbsent = true)` in `Server.kt`, a
collect-and-broadcast job beside `missionsJob`, a `StateFlow` on `VdtStore`, a branch in
`TelemetryRepository`.

---

## Where #47 plugs in

Not built here, but worth stating so this round doesn't block it. Enhanced Loan System **replaces**
base-game loans: it runs its own `ELS_loanManager` with multiple concurrent annuity loans, each with a
rate, a term and a redemption schedule, and moves money with its own `ELS_addRemoveMoneyEvent`.

The seams that keep #47 cheap:

- The **overview table and the log are untouched by #47** — ELS's payments still land in
  `FinanceStats`, and its money movements still surface as notifications through the same HUD funnel.
  Only the loan section is affected.
- The loan block in `finance.json` (`loan` / `loanMax` / `loanStep` / `loanInterestPerDay` /
  `loansAvailable`) is a flat, self-contained group. #47 adds a sibling `loans[]` array from
  `src/integrations/` — presence of which tells the app to render the ELS section instead of the
  base-game one, the same "dispatch on aspect presence, not on a `type` field" rule the ISOBUS work
  settled on.
- `setLoan` stays the base-game command. ELS gets its own commands against its own events; it does not
  overload this one.

---

## Files

**Mod** (`vdTelemetry/`)

| | |
| --- | --- |
| `src/collect/FinanceExporter.lua` | new — the channel, the `showMoneyChange` hook, the MP history refresh |
| `src/model/FinanceModel.lua` | new — annotation-only `---@class` shape defs |
| `src/command/FinanceControl.lua` | new — `setLoan` |
| `VDTelemetry.lua` | add both to `sourceFiles` (exporter with the collectors, control with the commands; both after `ProductionExporter` for `ownFarmId`) |
| `spec/FinanceExporter_spec.lua` | new — collect(), the hook's amount/visibility rules, the period walk-back |
| `spec/FinanceControl_spec.lua` | new — the guard matrix, and that the delta sent is `target - loan` |

`VDTelemetry.VERSION` is **not** bumped: this is a separate channel with its own version, like
husbandry and missions.

**Shared / server / app** (`VDTerminal/`)

| | |
| --- | --- |
| `shared/…/model/Finance.kt` | new — `FinanceData`, `FinancePeriod`, `FinanceStatRow`, `MoneyEvent` |
| `shared/…/Protocol.kt` | `ServerMessage.Finance`, `ClientMessage.SetLoan` |
| `shared/…/VdtParser.kt` | `parseFinance` |
| `shared/src/jvmTest/…/FinanceModelTest.kt` | new — decode + round-trip over the fixtures |
| `server/…/Server.kt` | register + broadcast the channel |
| `server/…/CommandWriter.kt` | `SetLoan` → `<command type="setLoan" amount="…"/>` |
| `app/…/apps/FinanceApp.kt` | new |
| `app/…/panels/FinancePanel.kt` | new (+ a `FinanceParts.kt` if it grows) |
| `app/…/widgets/` | balance widget |
| `app/…/apps/AppRegistry.kt`, `state/VdtStore.kt`, `net/TelemetryRepository.kt`, `Main.kt` | wiring |

**Fixtures + docs**

- **`examples/json/finance/vanilla.json` is a committed real-game capture**, taken once the channel ran
  in game, and `FinanceModelTest.parsesTheVanillaCapture` reads it. It is a fresh singleplayer save, so
  it carries one period and an empty log — the shapes it cannot show (several archived periods, a
  populated log, a short MP-client export) stay **inline synthetic JSON** in `FinanceModelTest`, built
  from the game's real `FinanceStats.statNames` with invented amounts, following
  `SectionViewModelTest`'s precedent. That folder holds *real game captures only* — a hand-written file
  claiming to be one was rejected before — so the missing captures are listed under `FUTURE.md` →
  "Captures wanted as fixtures" rather than faked.
- `FUTURE.md` — graphs, persisting the log, the accepted limitations, and the in-game checks below.
- delete `finance-plan.md` when the feature lands (repo convention).

---

## Open questions

- **Does the current period's column update live on the host?** `changeFinanceStats` mutates
  `stats.finances` in place with no message behind it, so our 5 s interval is what surfaces it. Should
  be fine; wants confirming with a fuel purchase in front of the panel.
- **`loanMax` on a fresh MP client.** `Farm.new` calls `setInitialEconomy` → `updateMaxLoan` before any
  farmland is known, and it is only recomputed on `FARM_PROPERTY_CHANGED`. A client that has joined and
  touched nothing may therefore report `MIN_LOAN` (500 000) until the first property change. The
  in-game screen has the identical bug, so we match it — but it is worth an in-game check, and if it
  bites, the honest fix is to show "up to 500 000" rather than to recompute equity ourselves.
- **Notification volume.** If `showMoneyChange` turns out to fire far more often than expected (a
  contract payout burst, a big multi-station sale), the 100-entry cap may cover only minutes. Cheap to
  raise; wants a real session first.

## In-game checks nobody has run

- Confirm the log catches the same events the side notifications show, in the same order, in SP.
- Roll a month over with the panel open and confirm the columns shift (SP) and refresh (MP client).
- Borrow and repay from the terminal in MP and confirm the server clamps as expected, and that
  `ChangeLoanEvent`'s broadcast makes the panel update without waiting for the interval.
- Join an MP session as a non-manager and confirm the loan controls are disabled rather than failing
  silently.
