# VDTelemetry — Setup

*[Diese Anleitung auf Deutsch](setup.de.md)*

VDTelemetry shows what your Farming Simulator 25 machine and farm are doing, live, on a second
screen — a browser on the same PC, or a tablet or phone clamped in the cab. It comes in two halves,
and you need both:

- a **mod** that writes the game's state to disk, and
- **VDTerminal**, a small program that reads those files and serves the dashboard.

![The dashboard](https://raw.githubusercontent.com/VertexDezign/VDTelemetry/main/screenshots/overview.png)

## Before you start

| | |
|---|---|
| **The game** | Farming Simulator 25, on Windows or on Linux under Proton |
| **A second mod** | [FS25_additionalInputs](https://github.com/VertexDezign/AdditionalInputs/releases), version **1.1 or newer** (any 1.x). VDTelemetry cannot run without it and will switch itself off if it is missing |
| **A browser** | Any recent Chrome, Edge or Firefox — on the gaming PC, and on the tablet or phone if you want one |

Everything runs on your own machine. Nothing is uploaded anywhere.

## 1. Install the two mods

Download both zips:

- `FS25_vdTelemetry.zip` — from this project's [releases](https://github.com/VertexDezign/VDTelemetry/releases)
- `FS25_additionalInputs.zip` — from [its own releases page](https://github.com/VertexDezign/AdditionalInputs/releases)

Copy **both zip files, unopened**, into your FS25 `mods` folder:

- **Windows:** `Documents\My Games\FarmingSimulator2025\mods`
  If your Documents folder is synced to OneDrive, it is under `OneDrive\Documents\...` instead —
  that is normal, and VDTerminal looks in both places.
- **Linux (Steam/Proton):** buried in the Proton prefix. Find it with:
  ```bash
  find ~ -type d -path '*FarmingSimulator2025/mods' 2>/dev/null
  ```

Start the game, load or create a savegame, and **tick both mods** in the mod selection.

## 2. Start VDTerminal

Download the archive for your system from the same releases page and unpack it anywhere you like —
your Desktop is fine. There is no installer and nothing is written to your system.

- **Windows** — run `VDTerminal\VDTerminal.exe`.
  Windows will warn that the app is unsigned ("Windows protected your PC"). Choose **More info →
  Run anyway**. A black console window opens and stays open: that window *is* the program, so leave
  it running and close it when you're done playing.
  On the first start Windows Firewall will ask whether to allow network access. **Allow it on
  private networks** — without that, the tablet cannot reach the dashboard.
- **Linux** — extract the `.tar.gz` and run `VDTerminal/bin/VDTerminal` from a terminal.

You do **not** need to install Java. The download brings its own.

The console prints the addresses to use — keep them, you need them in the next step:

```text
Game directory: /home/you/.steam/.../FarmingSimulator2025
Server starting on port 3001
Dashboard: http://localhost:3001
  from another device: http://192.168.1.42:3001
```

## 3. Open the dashboard

- **On the gaming PC:** <http://localhost:3001>
- **On a tablet or phone:** the `from another device` address from the console, e.g.
  `http://192.168.1.42:3001`. The device has to be on the same network (same Wi-Fi) as the PC.

On the tablet, use your browser's **Add to Home Screen** to get an icon and a full screen without
browser chrome.

**Keeping the screen on.** Tap the coffee-cup icon in the header once: it says **AWAKE** while the
screen is being held, and tapping again lets it sleep. The tap matters — on an iPad or iPhone the
dashboard cannot hold the screen until you've touched it once, so a tablet you only ever look at will
dim. On a display (see below) there's no header, so one touch anywhere does the same job.

Two things worth knowing:

- On iPhone and iPad this works by playing a **silent** clip, which iOS counts as playback — so it
  can pause music or a podcast you had running. If that bothers you, leave the coffee cup off and use
  the setting below instead.
- It only holds while the dashboard is the app you're looking at. Switch apps or tabs and the screen
  goes back to its normal timeout.

The setting that always works, and is worth doing on a tablet that lives in the cab: *Settings →
Display & Brightness → Auto-Lock → Never* on iOS, *Settings → Display → Screen timeout* on Android.
It's on a charger up there anyway.

## 4. Check that data arrives

Load a savegame and get into a vehicle. The dashboard should fill within a second or two.

If it does, you're done — the rest of this page is only needed when something is missing.

## When the dashboard stays empty

Work down this list; it's ordered by how often each one is the answer.

**The page says it can't connect, or doesn't load at all**

- VDTerminal isn't running, or its console window was closed. Start it again.
- On a tablet: wrong address, wrong network, or the firewall prompt was dismissed. On Windows,
  check Windows Defender Firewall → *Allow an app* and make sure `VDTerminal` is ticked for
  **Private** networks.

**The dashboard loads but shows no data**

1. **Is the export on?** In game: *Settings → General Settings*, find **VDTelemetry** and check that
   **Export enabled** is on.
2. **Is FS25_additionalInputs installed and enabled?** Without it, VDTelemetry disables itself
   silently — there is no in-game message yet. Check the game's `log.txt` for:
   `FS25_additionalInputs is required but not present`.
   The log is next to your savegames, in the `FarmingSimulator2025` folder.
3. **Did VDTerminal find your game folder?** Look at the console for:
   ```text
   Game directory not found: C:\Users\you\Documents\My Games\FarmingSimulator2025
   ```
   If you see that, point it at the right folder yourself (see below).

**Telling VDTerminal where the game folder is**

The folder you want is the one containing `modSettings`, `mods` and `savegame1`.

- **Windows** — create a text file next to `VDTerminal.exe`, call it `start.bat`, and put in it:
  ```bat
  set VDT_GAME_DIR=D:\SomeWhere\FarmingSimulator2025
  VDTerminal.exe
  ```
  Run `start.bat` instead of the `.exe` from then on.
- **Linux** —
  ```bash
  VDT_GAME_DIR="/path/to/FarmingSimulator2025" ./VDTerminal/bin/VDTerminal
  ```

## Settings worth knowing

In game, under *Settings → General Settings → VDTelemetry*:

- **Export enabled** — the master switch. Turning it off deletes the exported files, so the terminal
  can tell that it stopped rather than showing stale data.
- **Write interval** — how often the vehicle data is written. 100 ms is the smoothest; raise it if
  you want fewer writes. At 100 ms this is roughly 140 MB an hour — small next to what a modern SSD
  is rated for, but if you'd rather it never touched the drive at all, the mod's readme has
  [recipes for backing the folder with RAM](https://github.com/VertexDezign/VDTelemetry/blob/main/vdTelemetry/Readme.md#keeping-telemetry-writes-off-the-ssd-optional)
  on Linux and Windows.
- **Performance profile** — how often everything *else* (map, productions, animals, …) is refreshed.
  **Low** also switches the ground-layer map overlays off completely, which is by far the most
  expensive part. Start at **High**, drop it if the game stutters.

Everything else is tuned from the terminal itself or from
`modSettings/FS25_vdTelemetry/vdTelemetrySettings.xml`.

## This is an alpha — please read

- **The programs are unsigned.** Windows SmartScreen and some antivirus tools will complain about
  any new unsigned executable. That's what the warning means; nothing more.
- **Don't expose port 3001 to the internet.** The dashboard has no password and is meant for your
  own network only. Do not forward the port on your router.
- **The dashboard is in English only.** The mod's in-game settings are translated; the terminal's
  own text isn't yet.
- **Multiplayer:** the telemetry is written on your own PC, so each player who wants a dashboard
  runs their own copy. On a dedicated server there is nothing to install.
- Found a bug, or something in this guide that didn't match what you saw? Please open an issue at
  <https://github.com/VertexDezign/VDTelemetry/issues>.
