package net.vertexdezign.vdt.server

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Runtime configuration. Ports `paths.go`: env overrides, OS-specific default game dir.
 *
 * The mod writes JSON into `modSettings/<MOD_DIR>/telemetry/vdTelemetry.json` (it can only manage
 * files under its own modSettings folder). Override the full path with `VDT_FILE`.
 */
object Config {
  private val log = LoggerFactory.getLogger(Config::class.java)

  /** The mod's folder name under modSettings/ (matches the packaged zip `FS25_vdTelemetry`). */
  private const val MOD_DIR = "FS25_vdTelemetry"

  /** The folder the mod writes its channel files into, inside its modSettings folder. */
  private const val TELEMETRY_DIR = "telemetry"

  /** The folder the mod polls for `commands.xml`, a sibling of [TELEMETRY_DIR]. */
  private const val COMMANDS_DIR = "commands"

  val port: Int
    get() = System.getenv("VDT_PORT")?.toIntOrNull() ?: 3001

  fun gameDir(): Path {
    System.getenv("VDT_GAME_DIR")?.takeIf { it.isNotBlank() }?.let { return Path(it) }

    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
      os.contains("win") -> {
        Path(home, "Documents", "My Games", "FarmingSimulator2025")
      }

      else -> {
        // Linux: Steam / Proton prefix for FS25 (Steam app id 2300320).
        Path(
          home,
          ".steam",
          "steam",
          "steamapps",
          "compatdata",
          "2300320",
          "pfx",
          "drive_c",
          "users",
          "steamuser",
          "Documents",
          "My Games",
          "FarmingSimulator2025",
        )
      }
    }
  }

  /**
   * The telemetry file to watch. Absolute and normalized, because the watchers are built from its
   * *folder* — `telemetryPath().parent`, plus that parent's `mapLayers/` — and a relative
   * `VDT_FILE=vdTelemetry.json` (or a relative `VDT_GAME_DIR`) has no parent at all to take. Both
   * env vars are a user's free text, so neither can be assumed absolute.
   */
  fun telemetryPath(): Path {
    val configured =
      System.getenv("VDT_FILE")?.takeIf { it.isNotBlank() }?.let { Path(it) }
        ?: gameDir()
          .resolve("modSettings")
          .resolve(MOD_DIR)
          .resolve(TELEMETRY_DIR)
          .resolve("vdTelemetry.json")
    return configured.toAbsolutePath().normalize()
  }

  /**
   * The app -> mod command file the server writes. A sibling of the telemetry file's `telemetry/`
   * folder: `modSettings/<MOD_DIR>/commands/commands.xml`. Derived from [telemetryPath] so a single
   * `VDT_FILE` override moves both; override the command file directly with `VDT_COMMAND_FILE`.
   */
  fun commandPath(): Path {
    System.getenv("VDT_COMMAND_FILE")?.takeIf { it.isNotBlank() }?.let { return Path(it) }
    return commandPathFor(telemetryPath())
  }

  /**
   * `<mod>/telemetry/vdTelemetry.json` -> `<mod>/commands/commands.xml`: step out of the folder the
   * mod writes channels into, into the one it polls.
   *
   * The step out is conditional on that folder actually being there, because a `VDT_FILE` that isn't
   * in the mod layout used to be treated as though it were: a plain `.../vdTelemetry.json` put the
   * commands one level above the folder the user named, and a bare filename threw on the missing
   * grandparent. Both fail in the direction nobody notices — telemetry still flows, so the dashboard
   * looks alive while every button press lands somewhere the mod never reads. Off the layout we now
   * write beside the telemetry file instead, which is at least a folder the user pointed at, and say
   * so. Matched case-insensitively so a Windows path that differs only in case still counts.
   */
  internal fun commandPathFor(telemetry: Path): Path {
    val file = telemetry.toAbsolutePath().normalize()
    // No parent means the filesystem root itself — degenerate, but not a reason to throw.
    val dir = file.parent ?: file
    val modDir = dir.takeIf { it.fileName?.toString().equals(TELEMETRY_DIR, ignoreCase = true) }?.parent
    if (modDir == null) {
      log.warn(
        "Telemetry file {} is not inside a '{}' folder, so the mod's command folder can't be derived from it. " +
          "Writing commands to {} — if that isn't where the mod polls, set VDT_COMMAND_FILE.",
        file,
        TELEMETRY_DIR,
        dir.resolve(COMMANDS_DIR),
      )
    }
    return (modDir ?: dir).resolve(COMMANDS_DIR).resolve("commands.xml")
  }

  /**
   * Debounce window (ms) coalescing the burst of filesystem events from a single mod write. Kept a
   * small constant — it only needs to cover one file save, not the write interval — so it stays
   * below the mod's interval (default 100 ms) and doesn't throttle the stream. Override with
   * `VDT_DEBOUNCE_MS`.
   */
  fun debounceMs(): Long = System.getenv("VDT_DEBOUNCE_MS")?.toLongOrNull()?.takeIf { it >= 0 } ?: 40L
}
