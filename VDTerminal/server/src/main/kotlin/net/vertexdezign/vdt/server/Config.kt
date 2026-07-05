package net.vertexdezign.vdt.server

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Runtime configuration. Ports `paths.go`: env overrides, OS-specific default game dir.
 *
 * The mod writes JSON into `modSettings/<MOD_DIR>/telemetry/vdTelemetry.json` (it can only manage
 * files under its own modSettings folder). Override the full path with `VDT_FILE`.
 */
object Config {
    /** The mod's folder name under modSettings/ (matches the packaged zip `FS25_vdTelemetry`). */
    private const val MOD_DIR = "FS25_vdTelemetry"

    val port: Int
        get() = System.getenv("VDT_PORT")?.toIntOrNull() ?: 3001

    fun gameDir(): Path {
        System.getenv("VDT_GAME_DIR")?.takeIf { it.isNotBlank() }?.let { return Path(it) }

        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") ->
                Path(home, "Documents", "My Games", "FarmingSimulator2025")

            else ->
                // Linux: Steam / Proton prefix for FS25 (Steam app id 2300320).
                Path(
                    home, ".steam", "steam", "steamapps", "compatdata", "2300320",
                    "pfx", "drive_c", "users", "steamuser",
                    "Documents", "My Games", "FarmingSimulator2025",
                )
        }
    }

    fun telemetryPath(): Path {
        System.getenv("VDT_FILE")?.takeIf { it.isNotBlank() }?.let { return Path(it) }
        return gameDir().resolve("modSettings").resolve(MOD_DIR).resolve("telemetry").resolve("vdTelemetry.json")
    }

    /**
     * Debounce window (ms) coalescing the burst of filesystem events from a single mod write. Kept a
     * small constant — it only needs to cover one file save, not the write interval — so it stays
     * below the mod's interval (default 100 ms) and doesn't throttle the stream. Override with
     * `VDT_DEBOUNCE_MS`.
     */
    fun debounceMs(): Long =
        System.getenv("VDT_DEBOUNCE_MS")?.toLongOrNull()?.takeIf { it >= 0 } ?: 40L
}
