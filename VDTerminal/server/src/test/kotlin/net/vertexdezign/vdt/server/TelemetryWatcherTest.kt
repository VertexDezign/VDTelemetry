package net.vertexdezign.vdt.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TelemetryWatcher.registerRest] — the whole-directory mode the ground-layer rasters use, where the
 * file set is the mod's to decide (one file per raster plane, and Precision Farming will add more).
 *
 * Driven through [TelemetryWatcher.reparseAll] rather than the watch loop: what's under test is the
 * scan, and going through real filesystem events would test the JDK's WatchService instead.
 */
class TelemetryWatcherTest {
  private val dir: Path = Files.createTempDirectory("vdt-watcher-test")

  @AfterTest
  fun cleanUp() {
    dir.toFile().deleteRecursively()
  }

  private fun write(name: String, text: String) =
    dir.resolve(name).also { it.parent.createDirectories() }.writeText(text)

  @Test
  fun discoversEveryUnclaimedJsonKeyedByBaseName() {
    write("crops.json", "crops-body")
    write("growth.json", "growth-body")

    val watcher = TelemetryWatcher(dir)
    val rest = watcher.registerRest { it }
    watcher.reparseAll()

    assertEquals(mapOf("crops" to "crops-body", "growth" to "growth-body"), rest.value)
  }

  /** A file registered by name is that channel's; the catch-all must not also publish it as a plane. */
  @Test
  fun leavesClaimedFilesToTheirOwnChannel() {
    write("index.json", "catalogue")
    write("crops.json", "crops-body")

    val watcher = TelemetryWatcher(dir)
    val index = watcher.register("index.json", nullOnAbsent = true) { it }
    val rest = watcher.registerRest { it }
    watcher.reparseAll()

    assertEquals("catalogue", index.value)
    assertEquals(mapOf("crops" to "crops-body"), rest.value)
  }

  @Test
  fun picksUpNewFilesAndDropsDeletedOnes() {
    write("crops.json", "crops-body")

    val watcher = TelemetryWatcher(dir)
    val rest = watcher.registerRest { it }
    watcher.reparseAll()
    assertEquals(setOf("crops"), rest.value.keys)

    // A plane appearing is how a newly subscribed layer arrives -- no code change per plane, which is
    // the point: the Precision Farming rasters land the same way.
    write("soil.json", "soil-body")
    watcher.reparseAll()
    assertEquals(mapOf("crops" to "crops-body", "soil" to "soil-body"), rest.value)

    dir.resolve("crops.json").deleteExisting()
    watcher.reparseAll()
    assertEquals(mapOf("soil" to "soil-body"), rest.value)
  }

  /** Same guard the per-file channels have: one logical write shows up as several events. */
  @Test
  fun reparsesOnlyWhatChanged() {
    write("crops.json", "crops-body")

    var parses = 0
    val watcher = TelemetryWatcher(dir)
    val rest =
      watcher.registerRest {
        parses += 1
        it
      }
    watcher.reparseAll()
    watcher.reparseAll()
    assertEquals(1, parses, "an unchanged file must not be reparsed")

    // A new mtime is a new write (the content need not differ -- a resweep can produce the same bytes).
    val path = dir.resolve("crops.json")
    path.writeText("crops-body-2")
    path.setLastModifiedTime(
      java.nio.file.attribute.FileTime
        .fromMillis(System.currentTimeMillis() + 5_000),
    )
    watcher.reparseAll()
    assertEquals(2, parses)
    assertEquals("crops-body-2", rest.value["crops"])
  }

  /** A torn read (the mod mid-write) must not take the other planes' last good state with it. */
  @Test
  fun keepsLastGoodStateWhenOneFileFailsToParse() {
    write("crops.json", "good")
    write("growth.json", "boom")

    val watcher = TelemetryWatcher(dir)
    val rest = watcher.registerRest { if (it == "boom") error("torn read") else it }
    watcher.reparseAll()

    assertEquals("good", rest.value["crops"])
    assertNull(rest.value["growth"])
  }

  /** The folder only exists once the mod has loaded a map; until then there is simply nothing. */
  @Test
  fun toleratesAMissingDirectory() {
    val watcher = TelemetryWatcher(dir.resolve("not-there"))
    val rest = watcher.registerRest { it }
    watcher.reparseAll()

    assertTrue(rest.value.isEmpty())
  }

  @Test
  fun reportsCadenceForDiscoveredFilesToo() {
    write("crops.json", "crops-body")

    val watcher = TelemetryWatcher(dir)
    watcher.register("index.json", nullOnAbsent = true) { it }
    watcher.registerRest { it }
    watcher.reparseAll()

    // The discovered ones carry their folder, so the merged diagnostics list can tell a plane file
    // from a top-level channel file of the same name.
    val names = watcher.snapshotCadence().channels.map { it.name }
    assertEquals(listOf("index.json", "${dir.fileName}/crops.json"), names)
  }
}
