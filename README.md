# VDTelemetry

A telemetry pipeline for **Farming Simulator 25**, in two parts:

- **[`vdTelemetry/`](vdTelemetry/Readme.md)** — the in-game mod (Lua) that exports live game
  state to `vdTelemetry.xml`.
- **[`VDTerminal/`](VDTerminal/README.md)** — a Kotlin Multiplatform app that watches that file
  and renders a live web dashboard.

`vdTelemetrySchema.xsd` is the contract between the two.

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
