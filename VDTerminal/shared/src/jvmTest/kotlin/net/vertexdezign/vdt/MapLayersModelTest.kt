package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.LayerKind
import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.MapLayerLegendEntry
import net.vertexdezign.vdt.model.MapLayersInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/mapLayers` fixtures through the real server path
 * ([VdtParser.parseMapLayer] / [VdtParser.parseMapLayerCatalog]), asserts a lossless JSON round-trip,
 * [MapLayerData.decodeCells]'s padding/junk tolerance, and [MapLayersInfo.from]'s version
 * stability/sensitivity — the ground-layer channel's half of the mod↔Kotlin contract.
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

  private fun crops() = VdtParser.parseMapLayer(example("crops.json"))

  private fun assertRoundTrips(data: MapLayerData) {
    val encoded = json.encodeToString(MapLayerData.serializer(), data)
    val decoded = json.decodeFromString(MapLayerData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  @Test
  fun parsesOneRasterPlanePerFile() {
    val data = crops()

    assertEquals("3", data.version)
    assertEquals(2048f, data.terrainSize)
    assertEquals(8, data.gridSize)
    assertEquals("crops", data.id)
    assertEquals(2, data.legend.size)
    // Every entry on the crops plane is a fruit type, so they share one kind and differ by label —
    // which is exactly the case where branching on `v` (a map-order-dependent fruit index) would be
    // wrong and branching on `label` (localized: this capture is a German client) would be worse.
    assertEquals(MapLayerLegendEntry(1, "Weizen", "#c8b262", "crop"), data.legend[0])
    assertEquals(MapLayerLegendEntry(2, "Mais", "#f5d743", "crop"), data.legend[1])
    assertEquals(8, data.rows.size)

    // Each plane file repeats the geometry, so it decodes without reference to the catalogue.
    val growth = VdtParser.parseMapLayer(example("growth.json"))
    assertEquals("growth", growth.id)
    assertEquals(8, growth.gridSize)
    assertEquals(2048f, growth.terrainSize)
    assertEquals(listOf("cultivated", "growing"), growth.legend.map { it.kind })

    val soil = VdtParser.parseMapLayer(example("soil.json"))
    assertEquals(listOf("needsLime", "fertilized"), soil.legend.map { it.kind })

    assertRoundTrips(data)
    assertRoundTrips(growth)
  }

  @Test
  fun parsesTheCatalogue() {
    val catalog = VdtParser.parseMapLayerCatalog(example("index.json"))

    assertEquals("3", catalog.version)
    assertEquals(2048f, catalog.terrainSize)
    assertEquals(8, catalog.gridSize)
    assertEquals(listOf("crops", "growth", "soil"), catalog.layers.map { it.id })
    assertEquals("Crops", catalog.layers[0].label)
    // Which planes the mod is actually sweeping -- what the server reconciles the dashboards' union
    // against, since the command that carries the subscription can be lost with the command file.
    assertEquals(listOf(true, false, false), catalog.layers.map { it.active })
  }

  /** Every field is optional on the wire — the mod writes only what it has. */
  @Test
  fun parsesAPlaneWithOmittedFields() {
    val data = VdtParser.parseMapLayer("""{"version":"3"}""")

    assertEquals("3", data.version)
    assertEquals(0f, data.terrainSize)
    assertEquals(0, data.gridSize)
    assertEquals("", data.id)
    assertTrue(data.legend.isEmpty())
    assertTrue(data.rows.isEmpty())
    assertRoundTrips(data)
  }

  /**
   * A plane written before `mapLayers` version 3 has no `kind` anywhere, and a Precision Farming
   * plane never has one at all — its values are measurements, not states. Both decode to null, which
   * is "no grouping available" and must stay distinguishable from a known kind rather than
   * collapsing into one.
   */
  @Test
  fun legendKindIsNullWhereTheModEmitsNone() {
    val old = VdtParser.parseMapLayer(
      """{"version":"2","id":"growth","legend":[{"v":21,"label":"Ready to harvest","color":"#c68b1f"}]}""",
    )
    assertNull(old.legend.single().kind)
    assertRoundTrips(old)

    val pf = VdtParser.parseMapLayer(
      """{"version":"3","id":"pfNitrogen","legend":[{"v":1,"label":"30 kg/ha","color":"#1a4dd1"}]}""",
    )
    assertNull(pf.legend.single().kind)
    assertRoundTrips(pf)
  }

  /**
   * Why [MapLayerLegendEntry.kind] is a string and [LayerKind] is resolved from it rather than being
   * the wire type.
   *
   * The parser runs with `coerceInputValues = true`, so an enumerator kotlinx doesn't recognise is
   * NOT an error — it is silently replaced by the property's default, taking the actual token with
   * it. A `kind` the mod adds later would then reach the app as null with nothing left to log, count
   * or name. As a string it survives, and [MapLayerLegendEntry.knownKind] reports honestly that this
   * build doesn't know it.
   */
  @Test
  fun anUnknownKindSurvivesAsItsRawToken() {
    val data = VdtParser.parseMapLayer(
      """{"version":"4","id":"growth","legend":[{"v":40,"label":"Ridge","kind":"ridge"}]}""",
    )
    val entry = data.legend.single()
    assertEquals("ridge", entry.kind, "the token must reach the app intact, not be coerced away")
    assertNull(entry.knownKind, "and must not be resolved to some kind this build does know")
    assertRoundTrips(data)
  }

  /** The tokens are camelCase, which is exactly what no Kotlin enum member name can be. */
  @Test
  fun layerKindResolvesEveryTokenTheModEmits() {
    assertEquals(LayerKind.HARVEST, LayerKind.of("harvest"))
    assertEquals(LayerKind.NEEDS_PLOWING, LayerKind.of("needsPlowing"))
    assertEquals(LayerKind.CROP, LayerKind.of("crop"))
    // Case-sensitive, and deliberately so: "HARVEST" is not a token the mod ever writes, and
    // accepting it would be inventing a second spelling of the contract.
    assertNull(LayerKind.of("HARVEST"))
    assertNull(LayerKind.of(null))
    // Every member is reachable from its own token — no entry can drift out of the lookup.
    assertEquals(LayerKind.entries, LayerKind.entries.map { LayerKind.of(it.token) })
  }

  /**
   * The content version is the cache key for everything derived from a plane, not only for the PNG —
   * so a legend that differs only in `kind` must not reuse the previous version, or a consumer that
   * grouped cells by kind would keep the grouping it built before the meaning changed.
   */
  @Test
  fun contentVersionSeparatesLegendsThatDifferOnlyInKind() {
    fun plane(kind: String?) = MapLayerData(
      version = "3",
      gridSize = 2,
      id = "growth",
      legend = listOf(MapLayerLegendEntry(21, "Ready to harvest", "#c68b1f", kind)),
      rows = listOf("15"),
    )
    assertNotEquals(plane("harvest").contentVersion, plane("cut").contentVersion)
    assertNotEquals(plane("harvest").contentVersion, plane(null).contentVersion)
    assertEquals(plane("harvest").contentVersion, plane("harvest").contentVersion)
  }

  @Test
  fun decodeCellsHandlesTrimmedShortAndMissingRows() {
    val layer =
      MapLayerData(
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
    val layer = MapLayerData(id = "growth", rows = listOf("zz01gg02"))
    val cells = layer.decodeCells(gridSize = 4)

    assertEquals(0, cells[0]) // "zz" isn't valid hex
    assertEquals(1, cells[1])
    assertEquals(0, cells[2]) // "gg" isn't valid hex
    assertEquals(2, cells[3])
  }

  /** A signed pair parses as a number but isn't a cell value — the decoder's contract is 0..255. */
  @Test
  fun decodeCellsTreatsSignedPairsAsZero() {
    val cells = MapLayerData(id = "growth", rows = listOf("-1+2 3ff")).decodeCells(gridSize = 4)

    assertEquals(0, cells[0]) // "-1" would parse as -1 with a signed parse
    assertEquals(0, cells[1]) // "+2" likewise
    assertEquals(0, cells[2]) // " 3" is not two hex digits
    assertEquals(255, cells[3])
    assertTrue(cells.all { it in 0..255 }, "every decoded cell must stay in 0..255")
  }

  /** A corrupt grid size must degrade to blank like any other junk, not allocate or throw. */
  @Test
  fun decodeCellsRejectsAnOutOfRangeGridSize() {
    val layer = MapLayerData(id = "crops", rows = listOf("0102"))

    assertEquals(0, layer.decodeCells(gridSize = 0).size)
    assertEquals(0, layer.decodeCells(gridSize = -4).size)
    assertEquals(0, layer.decodeCells(gridSize = MapLayerData.MAX_GRID_SIZE + 1).size)
    assertEquals(0, layer.decodeCells(gridSize = 100_000).size) // gridSize² overflows Int
  }

  @Test
  fun mapLayersRideTheServerMessageDiscriminator() {
    val message: ServerMessage = ServerMessage.MapLayers(info())
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

  private fun info() = MapLayersInfo.from(
    VdtParser.parseMapLayerCatalog(example("index.json")),
    mapOf(
      "crops" to crops(),
      "growth" to VdtParser.parseMapLayer(example("growth.json")),
      "soil" to VdtParser.parseMapLayer(example("soil.json")),
    ),
  )

  /**
   * The catalogue decides what the app is offered, and the rasters only fill in the details — so a
   * plane the mod hasn't swept is still listed, with nothing to fetch and nothing to draw.
   */
  @Test
  fun infoListsEveryCataloguedPlaneIncludingUnsweptOnes() {
    val catalog = VdtParser.parseMapLayerCatalog(example("index.json"))
    val info = MapLayersInfo.from(catalog, mapOf("crops" to crops()))

    assertEquals(listOf("crops", "growth", "soil"), info.layers.map { it.id })
    assertEquals(listOf("Crops", "Growth", "Soil"), info.layers.map { it.label })

    val crops = info.layers.first { it.id == "crops" }
    assertNotNull(crops.version)
    assertEquals(crops().legend, crops.legend)

    val growth = info.layers.first { it.id == "growth" }
    assertNull(growth.version, "an unswept plane has no raster to fetch")
    assertTrue(growth.legend.isEmpty())
  }

  /** A file for a plane the catalogue doesn't list is last session's leftover, not an offer. */
  @Test
  fun infoIgnoresRastersTheCatalogueDoesNotList() {
    val catalog = VdtParser.parseMapLayerCatalog(example("index.json"))
    val info = MapLayersInfo.from(catalog, mapOf("nutrients" to crops().copy(id = "nutrients")))

    assertEquals(listOf("crops", "growth", "soil"), info.layers.map { it.id })
  }

  /**
   * Each plane carries its OWN version, which is the point of the split: the app refetches the
   * overlay it is showing only when that overlay changed, not when some other plane moved.
   */
  @Test
  fun eachPlaneVersionsIndependently() {
    val catalog = VdtParser.parseMapLayerCatalog(example("index.json"))
    val growth = VdtParser.parseMapLayer(example("growth.json"))
    val before = MapLayersInfo.from(catalog, mapOf("crops" to crops(), "growth" to growth))

    val movedGrowth = growth.copy(rows = growth.rows.toMutableList().also { it[0] = "0101" })
    val after = MapLayersInfo.from(catalog, mapOf("crops" to crops(), "growth" to movedGrowth))

    assertEquals(
      before.layers.first { it.id == "crops" }.version,
      after.layers.first { it.id == "crops" }.version,
      "a change to one plane must not move another plane's version",
    )
    assertNotEquals(
      before.layers.first { it.id == "growth" }.version,
      after.layers.first { it.id == "growth" }.version,
      "a changed cell must change that plane's version",
    )
  }

  /**
   * The memoized version is a derived value, not part of the wire shape: it must not appear in the
   * serialized form (which would put it in the file contract and in the app's decode), and `copy()`
   * must re-derive it rather than inherit the receiver's.
   */
  @Test
  fun contentVersionIsDerivedAndNotSerialized() {
    val data = crops()

    val encoded = json.encodeToString(MapLayerData.serializer(), data)
    assertFalse(encoded.contains("contentVersion"), "the memoized version must stay out of the JSON")

    // Same content, independently parsed -> same version (it is content-derived, not per-instance).
    assertEquals(data.contentVersion, crops().contentVersion)
    // Repeated reads are stable (the memo returns what it computed).
    assertEquals(data.contentVersion, data.contentVersion)
    // copy() builds a new instance, so its lazy re-derives from the NEW content.
    assertNotEquals(data.contentVersion, data.copy(gridSize = data.gridSize + 1).contentVersion)
  }

  /**
   * The version is what the immutable PNG cache is keyed on, so anything that alters the rendered
   * image has to move it — including the legend, which decides the colors — and row content must be
   * hashed positionally rather than as a bag of strings. The id is in there too: the URL it is served
   * under is `/api/map-layer/{id}?v=`, so two planes must not share a version.
   */
  @Test
  fun contentVersionCoversLegendsRowOrderAndId() {
    val data = crops()
    val version = data.contentVersion
    assertTrue(version.isNotEmpty(), "version must be a non-empty opaque string")

    assertNotEquals(
      version,
      data.copy(legend = data.legend.map { it.copy(color = "#000000") }).contentVersion,
      "a legend color change must change the version",
    )
    assertNotEquals(version, data.copy(rows = data.rows.reversed()).contentVersion, "row order counts")
    assertNotEquals(version, data.copy(id = "growth").contentVersion, "the id is part of the identity")
  }

  /**
   * The hash input is length-prefixed per list, so values can't drift across a list boundary: a
   * legend entry's three values must not hash the same as three rows in an otherwise empty plane.
   */
  @Test
  fun contentVersionEncodesStructureNotJustValues() {
    fun planeOf(legend: List<MapLayerLegendEntry>, rows: List<String>) =
      MapLayerData(gridSize = 2, id = "crops", legend = legend, rows = rows)

    val asLegend = planeOf(listOf(MapLayerLegendEntry(v = 1, label = "x", color = "c")), emptyList())
    val asRows = planeOf(emptyList(), listOf("1", "x", "c"))
    assertNotEquals(
      asLegend.contentVersion,
      asRows.contentVersion,
      "the same values in different structures must not share a version",
    )

    // A resolved-but-empty color and an unresolved one are different data, even though both happen to
    // render transparent today.
    val nullColor = planeOf(listOf(MapLayerLegendEntry(v = 1, label = "x", color = null)), emptyList())
    val emptyColor = planeOf(listOf(MapLayerLegendEntry(v = 1, label = "x", color = "")), emptyList())
    assertNotEquals(nullColor.contentVersion, emptyColor.contentVersion, "null and \"\" must differ")
  }
}
