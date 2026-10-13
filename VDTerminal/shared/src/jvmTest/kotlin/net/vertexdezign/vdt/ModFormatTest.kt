package net.vertexdezign.vdt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ModFormat.expected] against the mod's own `VERSION` constants, read straight out of the Lua — the
 * table is hand-kept, and a channel bumped on one side only would have the terminal ask every player
 * for an update that does not exist.
 */
class ModFormatTest {
  /** Channel → the Lua file that owns its `VERSION`, relative to `vdTelemetry/`. */
  private val sources =
    mapOf(
      "telemetry" to "VDTelemetry.lua",
      "map" to "src/collect/MapExporter.lua",
      "mapVehicles" to "src/collect/MapVehiclesExporter.lua",
      "mapLayers" to "src/collect/MapLayersExporter.lua",
      "gpsCourse" to "src/collect/GpsCourseExporter.lua",
      "fieldInfo" to "src/collect/FieldInfoExporter.lua",
      "production" to "src/collect/ProductionExporter.lua",
      "storage" to "src/collect/StorageExporter.lua",
      "husbandry" to "src/collect/HusbandryExporter.lua",
      "fleet" to "src/collect/FleetExporter.lua",
      "prices" to "src/collect/PricesExporter.lua",
      "missions" to "src/collect/MissionExporter.lua",
      "finance" to "src/collect/FinanceExporter.lua",
      "cropCalendar" to "src/collect/CropCalendarExporter.lua",
      "weather" to "src/collect/WeatherExporter.lua",
      "taskList" to "src/integrations/TaskList.lua",
      "cropRotation" to "src/integrations/CropRotation.lua",
      "autoDrive" to "src/integrations/AutoDrive.lua",
      "invoices" to "src/integrations/Invoices.lua",
    )

  private val versionLine = Regex("""^[A-Za-z.]+\.VERSION = (\d+)$""", RegexOption.MULTILINE)

  private fun modDir(): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "vdTelemetry/VDTelemetry.lua")
      if (candidate.exists()) return candidate.parentFile
      dir = dir.parentFile
    }
    error("Could not locate vdTelemetry/ from ${File(".").absolutePath}")
  }

  @Test
  fun everyChannelIsMapped() {
    assertEquals(ModFormat.expected.keys, sources.keys)
  }

  @Test
  fun expectedVersionsMatchTheMod() {
    val mod = modDir()
    for ((channel, path) in sources) {
      val matches = versionLine.findAll(File(mod, path).readText()).toList()
      assertEquals(1, matches.size, "$path should declare exactly one VERSION")
      assertEquals(
        matches.single().groupValues[1].toInt(),
        ModFormat.expected[channel],
        "$channel: ModFormat disagrees with $path",
      )
    }
  }

  @Test
  fun checkComparesBothWays() {
    assertNull(ModFormat.check("storage", "4"))
    assertEquals(ModFormat.Mismatch.ModOutdated, ModFormat.check("storage", "3"))
    assertEquals(ModFormat.Mismatch.TerminalOutdated, ModFormat.check("storage", "5"))
  }

  @Test
  fun checkIgnoresWhatItCannotCompare() {
    assertNull(ModFormat.check("storage", ""))
    assertNull(ModFormat.check("storage", "v4"))
    assertNull(ModFormat.check("someFutureChannel", "1"))
  }
}
