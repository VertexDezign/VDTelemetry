package net.vertexdezign.vdt.server

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {
  /** The layout the mod actually writes: the commands folder is a sibling of `telemetry/`. */
  @Test
  fun modLayoutStepsOutOfTheTelemetryFolder() {
    assertEquals(
      Path("/game/modSettings/FS25_vdTelemetry/commands/commands.xml"),
      Config.commandPathFor(Path("/game/modSettings/FS25_vdTelemetry/telemetry/vdTelemetry.json")),
    )
  }

  /**
   * Off that layout the old derivation took the grandparent anyway, putting the commands a level
   * above the folder the user named (`/tmp/x.json` -> `/commands/`). They now land beside the
   * telemetry file.
   */
  @Test
  fun pathOutsideTheModLayoutKeepsCommandsBesideTheTelemetryFile() {
    assertEquals(
      Path("/tmp/commands/commands.xml"),
      Config.commandPathFor(Path("/tmp/vdTelemetry.json")),
    )
  }

  /** A bare filename has no grandparent to take — that used to throw before it could be logged. */
  @Test
  fun bareFilenameResolvesAgainstTheWorkingDirectory() {
    val cwd = Path("").toAbsolutePath()
    assertEquals(
      cwd.resolve("commands").resolve("commands.xml"),
      Config.commandPathFor(Path("vdTelemetry.json")),
    )
  }

  /** The mod writes `telemetry` lowercase; a Windows path may not, and means the same folder. */
  @Test
  fun telemetryFolderMatchIgnoresCase() {
    assertEquals(
      Path("/game/FS25_vdTelemetry/commands/commands.xml"),
      Config.commandPathFor(Path("/game/FS25_vdTelemetry/Telemetry/vdTelemetry.json")),
    )
  }

  /** `..` segments are normalized away before the folder name is read. */
  @Test
  fun relativeSegmentsAreNormalizedBeforeMatching() {
    assertEquals(
      Path("/game/mod/commands/commands.xml"),
      Config.commandPathFor(Path("/game/mod/layers/../telemetry/vdTelemetry.json")),
    )
  }

  /** A telemetry file directly at the filesystem root has no parent at all. */
  @Test
  fun rootLevelFileDoesNotThrow() {
    assertEquals(
      Path("/commands/commands.xml"),
      Config.commandPathFor(Path("/vdTelemetry.json")),
    )
  }
}
