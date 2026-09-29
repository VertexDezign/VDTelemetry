# Baler ISOBUS screen — plan

One ISOBUS section for balers, round and square, with a baler-wrapper combination as an extension of the
round one, and one for standalone bale wrappers (the same `BaleWrapper` specialization, so the same
aspect). The machine is **drawn in code** (no bitmap art), faces left, and **every moving part is driven by
exported state** — nothing loops on a timer that the game does not also show.

Scope the user settled before this plan (2026-09-29): bale counter + reset, bale size (a dropdown), auto-drop
+ a manual drop button, net/twine level, baler-wrapper combos and standalone wrappers. Inline tube wrappers
(`InlineWrapper`) are out. Art drawn in code, animation driven by live state.

## What the engine gives us (and what reaches a client)

All of it read from `Baler.lua`, `BaleCounter.lua`, `BaleWrapper.lua` in the skill's lua-source.

| Fact | Where | On an MP client |
|---|---|---|
| Round or square | `spec.isRoundBaler` (any bale type `isRoundBale`) | load-time, yes |
| Bale being formed | fill unit `spec.fillUnitIndex` level / capacity | `onReadStream` + fill-unit sync |
| Material coming in | `spec.lastAreaBiggerZero` | yes, `onReadUpdateStream` |
| Door / tailgate | `spec.unloadingState` CLOSED 1 / OPENING 2 / OPEN 3 / CLOSING 4, **only when `hasUnloadingAnimation`** (a round baler) | yes, `BalerSetIsUnloadingBaleEvent` |
| Finished bales still on the machine | `#spec.bales`; each `bale.time` 0..1 along `baleAnimCurve` (square channel) | yes, `BalerSetBaleTimeEvent` |
| Bale sizes offered | `spec.baleTypes[i]` diameter / width / height / length | load-time |
| Selected vs. next size | `currentBaleTypeIndex`, `preSelectedBaleTypeIndex` — a change waits for an empty chamber | yes, `BalerBaleTypeEvent` |
| Auto-drop | `spec.automaticDrop`, or `spec.platformAutomaticDrop` when `hasPlatform`; `toggleableAutomaticDrop` | yes, `BalerAutomaticDropEvent` |
| Platform (bale accumulator) | `hasPlatform`, `platformReadyToDrop` | yes |
| Non-stop buffer | `nonStopBaling`, `buffer.fillUnitIndex`, `buffer.unloadingStarted` | yes |
| Counter | `spec_baleCounter.sessionCounter` / `lifetimeCounter` (exported already, **not drawn**) | yes |
| Wrapper | `spec_baleWrapper.baleWrapperState` 0..6, `currentWrapper.currentTime / animTime`, round vs. square wrapper, `automaticDrop` | state yes; wrap time advances in `onUpdate` on every peer |

Net, twine and wrap film are already exported as fill units (the consumable roll counts `FillUnitsDisplay`
already draws). The section picks them by fill type (`BALE_NET`, `BALE_TWINE`, `BALE_WRAP`).
The pickup's raised/lowered position is the `lowered` aspect, which asks `Pickup` first.

## Mod side — export v26 (built)

- **`collect/aspects/Baler.lua`** → `baler`: `round`, `fillUnit` (the chamber's position in the exported
  `fillUnits` — `VDT.FillUnit.reportedIndex` translates, because the export skips diesel and hidden
  units), `consumable` (the net or twine unit, found through the `Consumable` spec — the captured
  GÖWEIL VARIO-Master exports its net with no fill type), `working`, `powered`, `door` (round only), `bales[{ position }]` (position on square balers
  only), `baleTypes`, `baleType`, `nextBaleType` (chosen, waiting for an empty chamber), `autoDrop { on,
  canToggle }` (a platform baler's own flag where it has one), `platform { ready }`, `buffer { fillUnit,
  overloading }`, and `unload` — `UNLOAD | UNLOAD_UNFINISHED | CLOSE | DROP_PLATFORM`, a copy of
  `Baler.updateActionEvents`' tree plus the key's power gate.
- **`collect/aspects/BaleWrapper.lua`** → `baleWrapper`: `consumable` (the film), `state` (`EMPTY LOADING LOADED WRAPPING WRAPPED
  DROPPING RESETTING`), `round`, `progress`, `autoDrop`, `canDrop`, `unsupportedBale` (the game's "this
  bale cannot be wrapped" warning — mostly a standalone wrapper's). No drop-area verdict: it is an
  `overlapBox`, asked only when the drop command lands.
- **`command/BalerControl.lua`**, all through `TargetResolver`:
  `resetBaleCounter`, `setBaleType { index }` (absolute), `setBaleAutoDrop { part = baler|wrapper, on }`
  (absolute; a baler-wrapper has both), `unloadBale { action }` (runs only while `unload` still names that
  action — the engine's handler toggles the door), `dropWrappedBale` (the game's own
  `BaleWrapper.actionEventEmpty`). Each repeats the power gate of the key it stands in for.
- `baler.collector { fillUnit }` on a baler with a bale collector (`balerLoader`: Baler + BaleLoader). The
  collector's count is a fill unit in bales. **Its unload is not commandable** — BaleLoader is its own
  2700-line state machine; see FUTURE.md.
- Specs: `spec/Baler_spec.lua`.

## App side — `BalerSection.kt` + `BalerArt.kt`

- Dispatch on `baler != null || baleWrapper != null` in `IsoBusMachine.hasSection`, like the mixer.
  A baler-wrapper is **one** machine carrying both aspects (unlike the combine's two), so no rig pairing is
  needed. A standalone wrapper has `baleWrapper` alone: its screen is the wrapper art, the wrap state in
  words, auto-drop and the drop button — no counter, no size.
- **Layout**: the art in the middle; on the left the bale (fill %, bale size, bales on the machine), on the
  right the counter (session large, lifetime small) with its reset. Bottom strip: chips that are their own
  controls (the #116 rule): bale size, auto-drop, and the drop button labelled with the engine's action.
  Net/twine/film as the existing stepped roll display.
- **Bale size is a dropdown** of the sizes the machine offers (only when it offers more than one). While a
  size is chosen but the chamber is not empty yet, the chip shows both: "125 cm → 150 cm, after this bale".
- **`BalerArt.kt`** — a `Canvas`, geometry kept as pure functions (testable in `commonTest`), colours from
  `VdtColors` roles. Drawbar and pickup on the left, bale exit on the right.
  - *Round*: the chamber is a circle; the bale inside is drawn as a spiral whose radius follows
    **√fill** (so its area is the volume). It turns while `working`. The tailgate is a hinged polygon that
    swings open through OPENING→OPEN and back; the state gives the direction and the app tweens it,
    because the game's animation time is not exported. When `bales` empties during OPEN, the bale rolls out
    onto the ground behind the machine.
  - *Collector*: 0..capacity bales on a rack behind a square baler, from `collector.fillUnit`.
  - *Square*: a long channel. The plunger strokes while `working`; the forming bale is a slab
    that gets longer with the fill level; finished bales sit at their `position` along the channel and chute,
    and leave off the end when their position reaches 1. Platform: the bale waits on the tilted platform
    while `ready`.
  - *Pickup*: the tine reel drops and lifts with `lowered` and turns while the machine is turned on.
  - *Standalone wrapper*: loading arm + turntable, no chamber or pickup; LOADING swings the arm with a
    bale in it.
  - *Wrapper*: a turntable behind the chamber; the bale on it gains film bands with `progress`, and the
    satellite arms turn while `WRAPPING`. States reached in words too (colour-blind rule): "Wrapping",
    "Ready to drop", "Dropping".
  - Below a size floor the art is dropped rather than shrunk (the combine rule), and every state it
    shows is also in words on the chips, so a small tile loses the picture and nothing else.
- Door open vs. closed, bale finished vs. forming, auto-drop on vs. off: each differs in **shape or
  word**, never by hue alone.

## Order

1. ~~Mod: aspects, commands, v26. Captures.~~
2. ~~Kotlin model + `BalerModelTest` over the captures.~~
3. Commands on the app side (`Protocol.kt` + `CommandWriter`).
4. `BalerArt` geometry + tests, then the section, then the chips.

## Captures (`examples/json/telemetry/vanilla/baler/`, singleplayer, 2026-09-29)

Round baler filling / door opening / door open (KRONE VariPack), self-propelled round baler (Vermeer
ZR5), square baler (KRONE BiG Pack, two bales in the channel), baler-wrapper wrapping (John Deere C441R),
standalone wrapper loading / wrapping (GÖWEIL G5020), and a stationary baler-wrapper fed from a buffer
(GÖWEIL VARIO-Master V140, `stationaryBalerWrapper.json`) — the non-stop capture.

`squareBaler_collector.json` is a KRONE BiG Pack 1290 HDP VC with its bale collector (`balerLoader`), one
bale on the rack. A platform baler in the `Baler.platform` sense has not been found in the base game — the
collector is its equivalent; the platform path is covered by the specs only.

Recaptured after `consumable` and `collector` were added: `squareBaler_collector`,
`stationaryBalerWrapper`, `selfDrivingRoundBaler` — the untyped net on the VARIO-Master resolves.

Still wanted: **an MP-client capture** of any of them.

## Open questions

- Is `BaleCounter` on every base-game baler? If some lack it the counter block hides; the captures answer it.
- No issue number: branch `baler-isobus`, commit subjects without an issue.
