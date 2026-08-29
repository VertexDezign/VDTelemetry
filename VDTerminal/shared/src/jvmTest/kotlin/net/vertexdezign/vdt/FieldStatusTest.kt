package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.FieldIndexGrid
import net.vertexdezign.vdt.model.FieldStatusData
import net.vertexdezign.vdt.model.FieldStatusSlice
import net.vertexdezign.vdt.model.FieldStatuses
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapField
import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.MapLayerLegendEntry
import net.vertexdezign.vdt.model.SliceGrouping
import net.vertexdezign.vdt.model.UNKNOWN_FIELD_KIND
import net.vertexdezign.vdt.model.fieldStatus
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the per-field status histogram — [FieldIndexGrid]'s polygon rasterisation and the counting
 * pass over a ground-layer raster.
 *
 * Mostly synthetic on purpose, and not because fixtures are unavailable: the committed
 * `examples/json/mapLayers` planes are 8-cell grids, so joining 77 real fields against 64 cells
 * proves the code runs and nothing else. The shapes that matter here — a concave field, a field in
 * another field's notch, a cell centre just inside or just outside an edge — are geometry, and a
 * hand-built polygon states them exactly where a capture would only happen to contain them. The
 * fixtures still get their own test below, at the two things they *can* answer: that the real map's
 * fields never overlap, and that the claimed area matches the areas the mod exported.
 *
 * `FUTURE.md` -> "Captures wanted as fixtures" carries the 512² `growth` capture this would rather be
 * asserting against.
 */
class FieldStatusTest {
  private val json = Json { encodeDefaults = true }

  private fun example(path: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/$path")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/$path from ${File(".").absolutePath}")
  }

  /** Cell coordinates on an 8-cell grid as the normalized `[0,1]` pairs a polygon carries. */
  private fun polygon(vararg cells: Float): List<Float> = cells.map { it / GRID }

  /**
   * A U: two prongs with a gap between them, and the gap is field 2.
   *
   * ```
   *  z=0  1 1 1 1 1 1 . .
   *  z=1  1 1 1 1 1 1 . .
   *  z=2  1 1 2 2 1 1 . .
   *  z=3  1 1 2 2 1 1 . .
   * ```
   *
   * The shape the whole fill rule exists for. Taking the outermost crossings on each row — which is
   * what `CoverageRecorder.fill` does, correctly, for a convex swept area — would fill the U solid and
   * hand field 1 the four cells that belong to field 2.
   */
  private val uShapedMap =
    MapData(
      terrainSize = TERRAIN,
      fields =
      listOf(
        MapField(
          id = 1,
          areaHa = 1f,
          polygon = polygon(0f, 0f, 6f, 0f, 6f, 4f, 4f, 4f, 4f, 2f, 2f, 2f, 2f, 4f, 0f, 4f),
        ),
        MapField(id = 2, areaHa = 1f, polygon = polygon(2f, 2f, 4f, 2f, 4f, 4f, 2f, 4f)),
      ),
    )

  /**
   * A growth-like plane over [uShapedMap]. Values are the mod's own: two steps of the growing
   * gradient (11, 12) that must collapse into one `growing` slice, harvest (21), cut (22), one value
   * the legend doesn't list (99) and one it lists without a kind (5) — both of which belong in the
   * unknown bucket — and 0, which is the planes' "nothing here" and belongs in neither.
   */
  private val plane =
    MapLayerData(
      version = "3",
      terrainSize = TERRAIN,
      gridSize = GRID.toInt(),
      id = "growth",
      legend =
      listOf(
        MapLayerLegendEntry(v = 5, label = "Nameless", color = "#000000", kind = null),
        MapLayerLegendEntry(v = 11, label = "Growing 1", color = "#2b7a06", kind = "growing"),
        MapLayerLegendEntry(v = 12, label = "Growing 2", color = "#2b7a06", kind = "growing"),
        MapLayerLegendEntry(v = 21, label = "Ready", color = "#e0b400", kind = "harvest"),
        MapLayerLegendEntry(v = 22, label = "Cut", color = "#8f7f4f", kind = "cut"),
      ),
      rows =
      listOf(
        "151515151515", // 21 21 21 21 21 21 .  .
        "161616160b0b", // 22 22 22 22 11 11 .  .
        "0c0015156305", // 12 .  21 21 99 05 .  .
        "000016001616", // .  .  22 .  22 22 .  .
        "",
        "",
        "",
        "",
      ),
    )

  @Test
  fun fillsConcaveFieldsByEvenOdd() {
    val grid = FieldIndexGrid.of(uShapedMap, GRID.toInt())

    assertEquals(2, grid.fieldCount)
    // The game forbids two fields sharing ground (FieldManager:loadMapData drops the offender), so a
    // contested cell would mean the fill claimed one, not that the map did.
    assertEquals(0, grid.overlaps)

    val claimed =
      (0 until GRID.toInt()).joinToString("\n") { row ->
        (0 until GRID.toInt()).joinToString("") { col ->
          when (val id = grid.fieldAt(row, col)) {
            0 -> "."
            else -> id.toString()
          }
        }
      }
    assertEquals(
      """
      111111..
      111111..
      112211..
      112211..
      ........
      ........
      ........
      ........
      """.trimIndent(),
      claimed,
      "the notch belongs to field 2; a hull fill would give both its cells to field 1",
    )
  }

  @Test
  fun claimsCellsByTheirCentre() {
    // A strip on cell 3's left side: it covers ground in cell 3 but not the point cell 3 stands for,
    // so it claims nothing. The same rule is what stops a field bleeding a cell into its neighbour
    // along every edge.
    val leftEdge =
      MapData(
        terrainSize = TERRAIN,
        fields = listOf(MapField(id = 7, polygon = polygon(3f, 0f, 3.4f, 0f, 3.4f, 8f, 3f, 8f))),
      )
    assertEquals(0, FieldIndexGrid.of(leftEdge, GRID.toInt()).fieldCount)

    // The span is closed at both ends, so an edge landing exactly on the centre is inside — the same
    // choice `CoverageRecorder.fill` makes. It decides nothing in practice (a coordinate is rounded to
    // five decimals mod-side and a cell centre is (col + 0.5) / gridSize), but the fill has to answer
    // it one way, and a cell no polygon claims would be worse than one claimed twice.
    val onTheCentre =
      MapData(
        terrainSize = TERRAIN,
        fields = listOf(MapField(id = 7, polygon = polygon(3f, 0f, 3.5f, 0f, 3.5f, 8f, 3f, 8f))),
      )
    assertEquals(1, FieldIndexGrid.of(onTheCentre, GRID.toInt()).fieldCount)

    // Shifted right by half a cell it now contains the centres of column 3 and nothing else.
    val overCentre =
      MapData(
        terrainSize = TERRAIN,
        fields = listOf(MapField(id = 7, polygon = polygon(3.4f, 0f, 3.9f, 0f, 3.9f, 8f, 3.4f, 8f))),
      )
    val grid = FieldIndexGrid.of(overCentre, GRID.toInt())
    assertEquals(1, grid.fieldCount)
    for (row in 0 until GRID.toInt()) {
      assertEquals(7, grid.fieldAt(row, 3))
      assertEquals(0, grid.fieldAt(row, 2))
      assertEquals(0, grid.fieldAt(row, 4))
    }
  }

  @Test
  fun countsCellsPerFieldAndKind() {
    val status = fieldStatus(uShapedMap, plane)

    assertEquals("growth", status.layerId)
    // 2048 m over 8 cells: a 256 m cell, 6.5536 ha of ground.
    assertEquals(6.5536f, status.haPerCell, 1e-4f)

    val one = assertNotNull(status.byId[1])
    assertEquals(20, one.polygonCells)
    assertEquals(3, one.blank, "three of field 1's cells carry value 0, which is not a state")
    assertEquals(17, one.cells)
    assertEquals(
      listOf(
        // Equal counts, so the kind breaks the tie — the app draws these in order, and segments that
        // swap places between sweeps read as movement.
        FieldStatusSlice("cut", 6),
        FieldStatusSlice("harvest", 6),
        // Both gradient steps, collapsed: the growth plane's growing values are a gradient, not a
        // vocabulary, which is exactly why the grouping is by kind and not by value.
        FieldStatusSlice("growing", 3),
        // One value the legend never listed, one it listed with no kind. Neither is foldable into a
        // kind it isn't, so both land here.
        FieldStatusSlice(UNKNOWN_FIELD_KIND, 2),
      ),
      one.slices,
    )

    val two = assertNotNull(status.byId[2])
    assertEquals(4, two.polygonCells)
    assertEquals(3, two.cells)
    assertEquals(1, two.blank)
    assertEquals(listOf(FieldStatusSlice("harvest", 2), FieldStatusSlice("cut", 1)), two.slices)

    // Ascending by id, so a list rendered straight off this doesn't reorder itself.
    assertEquals(listOf(1, 2), status.fields.map { it.id })
  }

  @Test
  fun derivesSharesOffTheSampledCells() {
    val one = assertNotNull(fieldStatus(uShapedMap, plane).byId[1])

    // Of the sampled ground, not of the polygon: "62 % ready to harvest" means 62 % of the ground that
    // is in a state, never 62 % of the title deed.
    assertEquals(6f / 17f, one.fractionOf("harvest"), 1e-6f)
    assertEquals(0f, one.fractionOf("withered"), "a kind the field has none of reads as zero, not as absent")
    assertEquals(FieldStatusSlice("cut", 6), one.dominant)
    assertEquals(6, one.cellsOf("cut"))
    assertEquals(0, one.cellsOf("stubble"))

    val empty = net.vertexdezign.vdt.model.FieldStatus(id = 9, cells = 0, blank = 12)
    assertEquals(0f, empty.fraction(0), "an unsampled field divides by zero otherwise")
    assertNull(empty.dominant)
  }

  @Test
  fun refusesARasterThatDoesNotLineUp() {
    val grid = FieldIndexGrid.of(uShapedMap, GRID.toInt())

    // A plane sampled at another resolution: the mod may bump GRID_SIZE, and half a join is worse
    // than none because nothing downstream could tell it was half.
    assertEquals(emptyList(), grid.histogram(plane.copy(gridSize = 16)).fields)
    // A plane from another map, which is what a map load looks like for the beat the two disagree.
    assertEquals(emptyList(), grid.histogram(plane.copy(terrainSize = 4096f)).fields)
    // ...and the layer id still comes back, so a caller can say which plane it got nothing for.
    assertEquals("growth", grid.histogram(plane.copy(gridSize = 16)).layerId)

    // Geometry that can't be rasterized at all.
    assertEquals(0, FieldIndexGrid.of(uShapedMap, 0).fieldCount)
    assertEquals(emptyList(), FieldIndexGrid.of(uShapedMap, 0).histogram(plane).fields)
    assertEquals(
      0,
      FieldIndexGrid.of(
        MapData(
          terrainSize = TERRAIN,
          fields =
          listOf(
            // Two points is a line, not a polygon.
            MapField(id = 3, polygon = polygon(0f, 0f, 4f, 4f)),
            // Id 0 is how a cell says "no field", so a field carrying the model default is skipped
            // rather than made indistinguishable from bare ground.
            MapField(id = 0, polygon = polygon(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)),
            // The map defined no usable outline (MapExporter omits the key).
            MapField(id = 4, polygon = emptyList()),
          ),
        ),
        GRID.toInt(),
      ).fieldCount,
    )
  }

  @Test
  fun sharesTheWholeFieldForAPlaneWhoseZeroMeansNothingToReport() {
    // A soil-shaped plane: only the cells with a condition carry a value, and 0 means "fine here"
    // rather than "not field ground". Two of field 1's twenty cells need plowing.
    val soil =
      plane.copy(
        id = "soil",
        legend = listOf(MapLayerLegendEntry(v = 2, label = "Needs plowing", color = "#7b4b2a", kind = "needsPlowing")),
        rows = listOf("020200000000", "", "", "", "", "", "", ""),
      )
    val one = assertNotNull(fieldStatus(uShapedMap, soil).byId[1])

    assertEquals(2, one.cells)
    assertEquals(18, one.blank)
    assertEquals(20, one.polygonCells)
    // Of the sampled cells it is everything, which is exactly the reading that would be wrong: two
    // unploughed cells on a twenty-cell field is a tenth of the field, not all of it.
    assertEquals(1f, one.fractionOf("needsPlowing"), 1e-6f)
    assertEquals(0.1f, one.polygonFractionOf("needsPlowing"), 1e-6f)
    assertEquals(0f, one.polygonFractionOf("weed"))
  }

  /**
   * A crops plane over [uShapedMap] — the case kind-grouping cannot answer.
   *
   * Every entry is a fruit type, so every entry's kind is `crop`; what tells them apart is the label.
   * Values here are fruit-type indices (1 wheat, 2 barley, 7 canola), plus one the legend never listed
   * (99) and one it listed with no kind (5).
   *
   * ```
   *  z=0  W W W W W B . .
   *  z=1  W W W W W W . .
   *  z=2  W W C C W B . .        C is field 2's notch
   *  z=3  W . C . ? n . .        ? unlisted, n listed-but-nameless
   * ```
   */
  private val cropsPlane =
    MapLayerData(
      version = "3",
      terrainSize = TERRAIN,
      gridSize = GRID.toInt(),
      id = "crops",
      legend =
      listOf(
        MapLayerLegendEntry(v = 1, label = "Wheat", color = "#d8c15a", kind = "crop"),
        MapLayerLegendEntry(v = 2, label = "Barley", color = "#c8b44a", kind = "crop"),
        MapLayerLegendEntry(v = 5, label = "Nameless", color = "#000000", kind = null),
        MapLayerLegendEntry(v = 7, label = "Canola", color = "#e8d24a", kind = "crop"),
      ),
      rows =
      listOf(
        "010101010102", // W W W W W B .  .
        "010101010101", // W W W W W W .  .
        "010107070102", // W W C C W B .  .
        "010007006305", // W .  C .  ?  n .  .
        "",
        "",
        "",
        "",
      ),
    )

  @Test
  fun countsCropsPerFruitRatherThanPerKind() {
    // No grouping passed: FIELD_STATUS_PLANES routes the crops plane to VALUE, so the server and the
    // app cannot disagree about how this plane is counted.
    val status = fieldStatus(uShapedMap, cropsPlane)

    val one = assertNotNull(status.byId[1])
    assertEquals(20, one.polygonCells)
    assertEquals(19, one.cells)
    assertEquals(1, one.blank)
    assertEquals(
      listOf(
        // One slice per fruit, named. The kind rides along for code to branch on; the label is what a
        // reader sees, and it is localized exactly like the fieldInfo.crop it stands in for.
        FieldStatusSlice("crop", 15, "Wheat"),
        FieldStatusSlice("crop", 2, "Barley"),
        // Equal counts and the same kind, so the label breaks the tie and the order is stable across
        // sweeps. The unlisted value has no label at all, which sorts first.
        FieldStatusSlice(UNKNOWN_FIELD_KIND, 1, null),
        FieldStatusSlice(UNKNOWN_FIELD_KIND, 1, "Nameless"),
      ),
      one.slices,
    )
    assertEquals("Wheat", assertNotNull(one.dominant).label)
    assertEquals(15f / 19f, one.fractionOf("crop"), 1e-6f, "fractionOf still counts the KIND, across its fruits")

    val two = assertNotNull(status.byId[2])
    assertEquals(listOf(FieldStatusSlice("crop", 3, "Canola")), two.slices)
  }

  @Test
  fun kindGroupingWouldSayOnlyThatSomethingIsPlanted() {
    // The reason SliceGrouping exists, stated as a test: counted by kind, a field carrying two fruits
    // and two unnamed values collapses to "planted, 17 cells" and the crop is gone.
    val byKind = FieldIndexGrid.of(uShapedMap, GRID.toInt()).histogram(cropsPlane, SliceGrouping.KIND)

    val one = assertNotNull(byKind.byId[1])
    assertEquals(listOf(FieldStatusSlice("crop", 17), FieldStatusSlice(UNKNOWN_FIELD_KIND, 2)), one.slices)
    assertTrue(one.slices.all { it.label == null }, "a kind-grouped slice has nothing finer to name")
  }

  @Test
  fun roundTripsAValueGroupedPlane() {
    val status = fieldStatus(uShapedMap, cropsPlane)
    val encoded = json.encodeToString(FieldStatusData.serializer(), status)
    assertEquals(
      status,
      json.decodeFromString(FieldStatusData.serializer(), encoded),
      "the label has to survive the wire, or the app falls back to the point sample forever",
    )
  }

  @Test
  fun carriesEveryPlaneUnderOneMessage() {
    val statuses =
      FieldStatuses(listOf(fieldStatus(uShapedMap, plane), fieldStatus(uShapedMap, plane.copy(id = "soil"))))

    assertEquals("growth", assertNotNull(statuses.growth).layerId)
    assertEquals("soil", assertNotNull(statuses.soil).layerId)
    // A plane nobody swept is absent, never zeroed — the two say different things to a reader.
    assertNull(FieldStatuses(listOf(fieldStatus(uShapedMap, plane))).soil)

    val encoded = json.encodeToString(FieldStatuses.serializer(), statuses)
    assertEquals(statuses, json.decodeFromString(FieldStatuses.serializer(), encoded), "round-trip should be lossless")
  }

  @Test
  fun roundTripsOverTheWire() {
    val status = fieldStatus(uShapedMap, plane)
    val encoded = json.encodeToString(FieldStatusData.serializer(), status)
    assertEquals(
      status,
      json.decodeFromString(FieldStatusData.serializer(), encoded),
      "JSON round-trip should be lossless",
    )
  }

  @Test
  fun holdsAgainstTheRealMap() {
    val map = VdtParser.parseMap(example("map/vanilla.json"))
    val grid = FieldIndexGrid.of(map, 512)

    // The 1:1 rule the whole join rests on — field id, farmland id and the displayed field number are
    // one integer because the game refuses anything else. Asserted rather than assumed.
    assertEquals(0, grid.overlaps)
    assertEquals(map.fields.size, grid.fieldCount, "every field on this map is big enough to hold a cell centre")

    // Area is the independent check on the rasterisation: the mod computed areaHa from the game's own
    // farmland, and the cells this fill claims have to add up to it. They cannot match exactly — the
    // polygon is thinned mod-side and a 4 m cell is either in or out — so this is a bound, not an
    // equality. The worst field on this map lands at 6 %, and the small ones are the loose ones, which
    // is the same coarseness the app suppresses a breakdown below.
    val cellHa = (map.terrainSize / 512f).let { it * it } / 10_000f
    var worst = 0f
    for (field in map.fields) {
      val cells = (0 until 512).sumOf { row -> (0 until 512).count { col -> grid.fieldAt(row, col) == field.id } }
      val error = abs(cells * cellHa - field.areaHa) / field.areaHa
      if (error > worst) worst = error
    }
    assertTrue(worst < 0.10f, "worst per-field area error was ${(worst * 100).toInt()}%")

    // The committed planes are 8x8, so this is a smoke test of the join and nothing more: every id it
    // reports has to be a field on this map, and every count has to fit inside its polygon.
    val layer = VdtParser.parseMapLayer(example("mapLayers/growth.json"))
    val status = fieldStatus(map, layer)
    val ids = map.fields.map { it.id }.toSet()
    for (field in status.fields) {
      assertTrue(field.id in ids, "field ${field.id} is not on this map")
      assertEquals(field.polygonCells, field.cells + field.blank)
      assertEquals(field.cells, field.slices.sumOf { it.cells })
    }
  }

  private companion object {
    /** Cells per edge in the synthetic grids; a float because every polygon here divides by it. */
    const val GRID = 8f

    /** An ordinary 2 km map, matching the committed fixtures so the two can be compared directly. */
    const val TERRAIN = 2048f
  }
}
