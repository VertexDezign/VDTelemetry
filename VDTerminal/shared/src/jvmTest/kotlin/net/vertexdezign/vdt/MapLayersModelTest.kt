package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.MapLayer
import net.vertexdezign.vdt.model.MapLayerLegendEntry
import net.vertexdezign.vdt.model.MapLayersData
import net.vertexdezign.vdt.model.MapLayersInfo
import net.vertexdezign.vdt.model.contentVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/mapLayers` fixtures through the real server path
 * ([VdtParser.parseMapLayers]), asserts a lossless JSON round-trip, [MapLayer.decodeCells]'s
 * padding/junk tolerance, and [MapLayersInfo.from]'s version stability/sensitivity — the ground-layer
 * channel's half of the mod↔Kotlin contract.
 */
class MapLayersModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/mapLayers/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/mapLayers/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: MapLayersData) {
    val encoded = json.encodeToString(MapLayersData.serializer(), data)
    val decoded = json.decodeFromString(MapLayersData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesBasicMapLayers() {
    val data = VdtParser.parseMapLayers(example("basic.json"))

    assertEquals("1", data.version)
    assertEquals(2048f, data.terrainSize)
    assertEquals(8, data.gridSize)
    assertEquals(3, data.layers.size)

    val crops = data.layers.first { it.id == "crops" }
    assertEquals(2, crops.legend.size)
    assertEquals(MapLayerLegendEntry(1, "Weizen", "#c8b262"), crops.legend[0])
    assertEquals(MapLayerLegendEntry(2, "Mais", "#f5d743"), crops.legend[1])
    assertEquals(8, crops.rows.size)

    val growth = data.layers.first { it.id == "growth" }
    assertEquals(2, growth.legend.size)

    val soil = data.layers.first { it.id == "soil" }
    assertEquals(2, soil.legend.size)

    assertRoundTrips(data)
  }

  @Test
  fun parsesEmptyMapLayersWithOmittedFields() {
    // The mod omits every optional field when no sweep has completed yet.
    val data = VdtParser.parseMapLayers(example("empty.json"))

    assertEquals("1", data.version)
    assertEquals(0f, data.terrainSize)
    assertEquals(0, data.gridSize)
    assertTrue(data.layers.isEmpty())
    assertRoundTrips(data)
  }

  @Test
  fun decodeCellsHandlesTrimmedShortAndMissingRows() {
    val layer =
      MapLayer(
        id = "crops",
        rows = listOf("", "0102", "0102030405060708"),
        // row 3 is entirely missing from the list -> zero-padded
      )

    val cells = layer.decodeCells(gridSize = 4)

    // Row 0: all-zero (empty string).
    assertEquals(0, cells[0 * 4 + 0])
    assertEquals(0, cells[0 * 4 + 3])
    // Row 1: short row "0102" -> cols 0,1 populated, cols 2,3 zero-padded.
    assertEquals(1, cells[1 * 4 + 0])
    assertEquals(2, cells[1 * 4 + 1])
    assertEquals(0, cells[1 * 4 + 2])
    assertEquals(0, cells[1 * 4 + 3])
    // Row 2: full row, all 4 cells.
    assertEquals(1, cells[2 * 4 + 0])
    assertEquals(2, cells[2 * 4 + 1])
    assertEquals(3, cells[2 * 4 + 2])
    assertEquals(4, cells[2 * 4 + 3])
    // Row 3: missing from `rows` entirely -> zero-padded, not an out-of-bounds error.
    assertEquals(0, cells[3 * 4 + 0])
    assertEquals(0, cells[3 * 4 + 3])
  }

  @Test
  fun decodeCellsTreatsMalformedBytesAsZero() {
    val layer = MapLayer(id = "growth", rows = listOf("zz01gg02"))
    val cells = layer.decodeCells(gridSize = 4)

    assertEquals(0, cells[0]) // "zz" isn't valid hex
    assertEquals(1, cells[1])
    assertEquals(0, cells[2]) // "gg" isn't valid hex
    assertEquals(2, cells[3])
  }

  @Test
  fun mapLayersRideTheServerMessageDiscriminator() {
    val data = VdtParser.parseMapLayers(example("basic.json"))
    val info = MapLayersInfo.from(data)
    val message: ServerMessage = ServerMessage.MapLayers(info)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    assertTrue(encoded.contains("\"type\":\"mapLayers\""), "expected the mapLayers discriminator in $encoded")
    // The raster rows must never cross the wire, only legends.
    assertTrue(!encoded.contains("\"rows\""), "rows must not be present in the broadcast message: $encoded")

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(message, assertNotNull(decoded as? ServerMessage.MapLayers))
  }

  /** "File gone" must cross the wire so the app clears its overlay (same rule as the other map channels). */
  @Test
  fun mapLayersCarryTheAbsentFileNull() {
    val message: ServerMessage = ServerMessage.MapLayers(null)
    val encoded = json.encodeToString(ServerMessage.serializer(), message)

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertNull(assertNotNull(decoded as? ServerMessage.MapLayers).data)
  }

  @Test
  fun infoVersionIsStableForIdenticalDataAndSensitiveToChanges() {
    val data = VdtParser.parseMapLayers(example("basic.json"))

    val versionA = MapLayersInfo.from(data).version
    val versionB = MapLayersInfo.from(data).version
    assertEquals(versionA, versionB, "identical data must produce the same version")

    val changed =
      data.copy(
        layers =
          data.layers.map {
            if (it.id == "crops") it.copy(rows = it.rows.toMutableList().also { rows -> rows[0] = "0101" }) else it
          },
      )
    assertNotEquals(versionA, MapLayersInfo.from(changed).version, "a changed cell must change the version")
  }

  /**
   * The version is what the immutable PNG cache is keyed on, so anything that alters the rendered
   * image has to move it — including the legend, which decides the colors — and row content must be
   * hashed positionally rather than as a bag of strings.
   */
  @Test
  fun contentVersionCoversLegendsAndRowOrder() {
    val data = VdtParser.parseMapLayers(example("basic.json"))
    val version = data.contentVersion()
    assertTrue(version.isNotEmpty(), "version must be a non-empty opaque string")

    val recolored =
      data.copy(
        layers =
          data.layers.map { layer ->
            if (layer.id == "crops") {
              layer.copy(legend = layer.legend.map { it.copy(color = "#000000") })
            } else {
              layer
            }
          },
      )
    assertNotEquals(version, recolored.contentVersion(), "a legend color change must change the version")

    val reordered =
      data.copy(
        layers =
          data.layers.map { layer ->
            if (layer.id == "crops") layer.copy(rows = layer.rows.reversed()) else layer
          },
      )
    assertNotEquals(version, reordered.contentVersion(), "reordered rows must change the version")
  }

  /**
   * The hash input is length-prefixed per list, so values can't drift across a list boundary: a
   * legend entry's three values must not hash the same as three rows in an otherwise empty layer.
   */
  @Test
  fun contentVersionEncodesStructureNotJustValues() {
    fun layerOf(
      legend: List<MapLayerLegendEntry>,
      rows: List<String>,
    ) = MapLayersData(gridSize = 2, layers = listOf(MapLayer(id = "crops", legend = legend, rows = rows)))

    val asLegend = layerOf(listOf(MapLayerLegendEntry(v = 1, label = "x", color = "c")), emptyList())
    val asRows = layerOf(emptyList(), listOf("1", "x", "c"))
    assertNotEquals(
      asLegend.contentVersion(),
      asRows.contentVersion(),
      "the same values in different structures must not share a version",
    )

    // A resolved-but-empty color and an unresolved one are different data, even though both happen to
    // render transparent today.
    val nullColor = layerOf(listOf(MapLayerLegendEntry(v = 1, label = "x", color = null)), emptyList())
    val emptyColor = layerOf(listOf(MapLayerLegendEntry(v = 1, label = "x", color = "")), emptyList())
    assertNotEquals(nullColor.contentVersion(), emptyColor.contentVersion(), "null and \"\" must differ")
  }

  @Test
  fun infoStripsRowsButKeepsLegends() {
    val data = VdtParser.parseMapLayers(example("basic.json"))
    val info = MapLayersInfo.from(data)

    assertEquals(data.layers.map { it.id }, info.layers.map { it.id })
    assertEquals(data.layers.map { it.legend }, info.layers.map { it.legend })
  }
}
