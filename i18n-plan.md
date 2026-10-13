# i18n — localize the terminal (issue #132)

English and German for the VDTerminal app, built so a third language is a set of new files and
nothing else. Background analysis: the issue's linked write-up (why not Compose Resources: async on
wasm, no locale override, composable-only).

## Decisions

- **Typed Kotlin catalogue.** `interface Strings` in `app/.../i18n/`, split into one sub-interface per
  area (`strings.header`, `strings.launcher`, `strings.apps`, …). Each language is a package
  (`i18n/en/`, `i18n/de/`) with one file per area. A missing translation is a compile error, every
  string is synchronous and callable outside composition.
- **The language follows the game by default.** Game data (fill types, vehicle names, missions)
  arrives already translated into the game's language, so app chrome in another language would mix
  two. The mod exports `g_languageShort` as `environment.language` (telemetry v35). The device can
  override it: `Game | EN | DE`, persisted per browser like theme and UI size, picked in the header's
  display-settings menu. A game language the terminal has no catalogue for falls back to the
  browser's language, then English; so does the moment before the first telemetry frame.
- **`LocalStrings` is a `staticCompositionLocalOf`.** A language switch is rare and should rebuild
  the tree.
- **Non-composable holders take `Strings` as a parameter.** `VdtApp.title`, `Widget.title`,
  `AlertRule.title`/`message`, `ConfigOption.label` become `fun title(s: Strings)`; pure
  formatters (`formatMonthsAgo`, `formatAge`, `formatMoney`, …) take `Strings` too, so tests keep
  passing `En` and German assertions sit beside them.
- **Number separators live on `Strings`**, not on a JS `Intl` call: deterministic, testable from
  `commonTest`. Plurals are a function per language (`fun monthsAgo(n: Int)`); en and de share the
  one/other cardinal categories.
- **Language names are written in their own language** in the picker (`EN`, `DE`), so someone who
  switched by mistake can find their way back.

## Steps

1. Mechanism on a small surface: `Strings`/`En`/`De`, `LocalStrings`, `LanguageStore`, mod export,
   picker; migrate the header, footer, launcher, loading screen and app titles.
2. Formatters take separators and unit words from `Strings`; fix `InvoiceBuilder.trimTrailing`'s
   hard-coded `,` strip, which a locale-dependent separator breaks.
3. Migrate app by app (Fleet, Fields, Market, …), one commit each.
4. The remaining non-composable holders: widget titles, config option labels, alert rules.
5. Pseudo-locale pass: a third `Strings` that pads by ~35 % and brackets every string, to find the
   clipping before the real German does (fixed grid, cluster readouts, section strip).
6. `manifest.webmanifest` and `index.html`'s `lang`.

## Open

- Default page titles ("Farm", "Vehicle", …) are seeded once and then persisted as user data; they
  stay in the language they were created in unless a page remembers it still has its default name.
