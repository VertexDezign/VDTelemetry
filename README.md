# VDTelemetry

**Just want to run it?** → [Project page](https://vertexdezign.github.io/VDTelemetry/) ·
[Setup guide (English)](docs/setup.en.md) ·
[Anleitung (Deutsch)](docs/setup.de.md) · [Downloads](https://github.com/VertexDezign/VDTelemetry/releases)

A telemetry pipeline for **Farming Simulator 25**, in two parts:

- **[`vdTelemetry/`](vdTelemetry/Readme.md)** — the in-game mod (Lua) that exports live game
  state to `vdTelemetry.json` and a set of sibling channel files, and takes commands back through a
  command channel.
- **[`VDTerminal/`](VDTerminal/README.md)** — a Kotlin Multiplatform app that watches those files
  and renders a live web dashboard, on this machine or on a tablet or phone on the LAN.

The shared Kotlin model (`VDTerminal/shared/.../model/`) plus the `examples/json/` fixtures are
the contract between the two: changing the data shape means changing the Lua collectors and the
Kotlin model together, and refreshing the fixtures.

Requirements: the mod needs [FS25_additionalInputs](https://github.com/VertexDezign/AdditionalInputs);
the terminal needs JDK 25+ and a WasmGC-capable browser. Each README has the full story — how the mod
is configured and what each channel carries, and how to run the terminal in development or as a single
production process.

![The dashboard in a combine](screenshots/combine.png)

| | |
|---|---|
| ![A tractor with trailers](screenshots/tractor_withTrailers.png) | ![On foot: map, tasks and crop rotation](screenshots/onFoot_page.png) |
| ![Map with a ground layer](screenshots/map_withLayer.png) | ![Editing a page](screenshots/pageEdit.png) |
| ![Light mode](screenshots/lightMode.png) | ![The pillar display](screenshots/pillar_display.png) |

<details>
<summary>The apps</summary>

| | |
|---|---|
| ![Map: precision-farming yield layer](screenshots/apps/map/layer_pfYield.png) | ![Map: filters and ground layers](screenshots/apps/map/map_options.png) |
| ![Fields](screenshots/apps/fields/fieldOverview.png) | ![Crop calendar and weather](screenshots/apps/calendar/overview.png) |
| ![Fleet](screenshots/apps/fleet/overview.png) | ![Fleet: locating a machine on the map](screenshots/apps/fleet/locate_on_map.png) |
| ![Production](screenshots/apps/production/production.png) | ![Production: a biogas plant](screenshots/apps/production/pnh_bga.png) |
| ![Storage](screenshots/apps/storage/silo.png) | ![Storage: unloading bales](screenshots/apps/storage/bale_unloading.png) |
| ![Animals](screenshots/apps/animals/overview.png) | ![Market: prices](screenshots/apps/market/prices.png) |
| ![Market: stock overview](screenshots/apps/market/stock_overview.png) | ![Market: stock details](screenshots/apps/market/stock_details.png) |
| ![Finance](screenshots/apps/finance/overview_withLoanMod.png) | ![Finance: invoices](screenshots/apps/finance/invoices.png) |
| ![Finance: creating an invoice](screenshots/apps/finance/create_invoice.png) | |

</details>

## Licence

MIT — see [`LICENSE`](LICENSE), and [`NOTICE`](NOTICE) for the trademarks this project does not own
and the third-party terms that apply alongside it. Both halves are covered; the mod zip carries the
same licence, and every terminal download — bundled or portable — ships both files.

The one component that is not ours to relicense is the **DSEG** font bundled with the terminal, under
the SIL Open Font License — [`VDTerminal/licenses/DSEG-OFL-1.1.txt`](VDTerminal/licenses/DSEG-OFL-1.1.txt)
travels with every build that contains it.

## Planned and deferred work

**[`FUTURE.md`](FUTURE.md)** collects everything planned, deferred or still open across the whole
repo — the follow-ups finished work left behind and the in-game checks nobody has run. Read it before
proposing something that looks new; it may already be there, with the reason it was left. (A limitation
that is accepted rather than open is a decision, not future work, and lives with the feature it belongs
to.)

## Commit messages

Commit subjects follow:

```
<issue> <modifier> <[area]> <subject>
```

where **issue** and **area** are optional — for example
`LS42-8 ✨ [ui] add short-url filtering`.

- **issue** – `LS42-8` (`<PROJECT>-<number>`) or `#8`.
- **modifier** – a gitmoji describing the kind of change. Write the emoji
  directly, or use the one-letter shorthand below; the local `commit-msg` hook
  rewrites the shorthand to the emoji on commit:

  | shorthand | emoji | meaning                        |
  |-----------|-------|--------------------------------|
  | `+`       | ✨    | new feature                    |
  | `!`       | 🚑    | bug fix                        |
  | `-`       | 🔥    | remove code                    |
  | `r`       | 🔨    | refactor (no behavior change)  |
  | `c`       | 📖    | documentation only             |
  | `t`       | 🚨    | tests                          |
  | `v`       | ⬆️    | upgrade dependencies / versions |
  | `b`       | 💚    | CI                             |
  | `i`       | 🎉    | initial / project setup        |

- **area** – the affected scope in brackets, e.g. `[ui]`, `[service]`,
  `[common]`.
