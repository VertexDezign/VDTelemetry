package net.vertexdezign.vdt.server

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Runtime configuration. Ports `paths.go`: env overrides, OS-specific default game dir.
 *
 * The mod now emits JSON, so the default telemetry file is `vdTelemetry.json` (override with
 * `VDT_FILE`).
 */
object Config {
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
        return gameDir().resolve("vdTelemetry.json")
    }
}
