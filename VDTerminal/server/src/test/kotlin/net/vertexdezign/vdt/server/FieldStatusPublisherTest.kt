package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.FieldStatusSlice
import net.vertexdezign.vdt.model.GROWTH_LAYER_ID
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapField
import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.MapLayerLegendEntry
import net.vertexdezign.vdt.model.SOIL_LAYER_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Covers [FieldStatusPublisher]'s caching, which is the only thing it adds to the histogram in
 * `shared`: the flow it is driven from carries **every** ground-layer plane, so a soil sweep re-emits
 * the same keyed map and must not cost a rebuild — and the identity of the value it returns is what
 * decides whether a `MutableStateFlow` broadcasts to every connected dashboard or drops it.
 *
 * The geometry itself is asserted in `:shared:jvmTest` (`FieldStatusTest`); these grids are the
 * smallest thing that produces a countable answer.
 */
class FieldStatusPublisherTest {
  private val map =
    MapData(
      terrainSize = 1024f,
      fields = listOf(MapField(id = 1, areaHa = 6f, polygon = listOf(0f, 0f, 0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f))),
    )

  private fun growth(vararg rows: String) = MapLayerData(
    version = "3",
    terrainSize = 1024f,
    gridSize = 4,
    id = GROWTH_LAYER_ID,
    legend =
    listOf(
      MapLayerLegendEntry(v = 21, label = "Ready", color = "#e0b400", kind = "harvest"),
      MapLayerLegendEntry(v = 22, label = "Cut", color = "#8f7f4f", kind = "cut"),
    ),
    rows = rows.toList() + List(4 - rows.size) { "" },
  )

  /** 21 21 / 21 21 over the field's four cells. */
  private val ready = growth("1515", "1515")

  /** The same field, harvested. */
  private val harvested = growth("1616", "1616")

  @Test
  fun derivesNothingUntilBothInputsAreThere() {
    val publisher = FieldStatusPublisher()

    assertNull(publisher.update(null, emptyMap()), "no map")
    assertNull(publisher.update(map, emptyMap()), "no raster")
    // The plane it counts is a specific one; another plane's raster is not a substitute for it.
    assertNull(publisher.update(map, mapOf("crops" to growth("1515").copy(id = "crops"))), "no counted plane")
    assertNull(publisher.update(MapData(terrainSize = 1024f), mapOf(GROWTH_LAYER_ID to ready)), "no fields")
    assertNull(publisher.current())

    val status = assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to ready)))
    assertEquals(listOf(FieldStatusSlice("harvest", 4)), assertNotNull(status.growth).fields.single().slices)
    assertSame(status, publisher.current())
  }

  @Test
  fun keepsTheSameValueWhenAnotherPlaneSweeps() {
    val publisher = FieldStatusPublisher()
    val first = assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to ready)))

    // A crops sweep re-emits the whole keyed map with a new instance in it. Nothing this publisher
    // counts has moved, so it must not rebuild anything — and returning the same instance is what
    // makes the StateFlow drop it instead of broadcasting an identical breakdown to every dashboard
    // on every sweep of every plane.
    val withCrops = mapOf(GROWTH_LAYER_ID to ready, "crops" to growth("0101").copy(id = "crops"))
    assertSame(first, publisher.update(map, withCrops))

    // An equal-but-different growth instance is the same raster, and the content version says so
    // without walking it twice.
    assertSame(first, publisher.update(map, mapOf(GROWTH_LAYER_ID to growth("1515", "1515"))))
  }

  @Test
  fun recomputesWhenTheRasterOrTheMapMoves() {
    val publisher = FieldStatusPublisher()
    val first = assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to ready)))

    val cut = assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to harvested)))
    assertNotSame(first, cut)
    assertEquals(listOf(FieldStatusSlice("cut", 4)), assertNotNull(cut.growth).fields.single().slices)

    // A bought farmland is a new field, and the index grid it is counted against has to be rebuilt —
    // the case a cache keyed on the raster alone would get wrong for as long as nothing was swept.
    val bigger =
      map.copy(
        fields =
        map.fields + MapField(id = 2, areaHa = 6f, polygon = listOf(0.5f, 0f, 1f, 0f, 1f, 0.5f, 0.5f, 0.5f)),
      )
    val grown = assertNotNull(publisher.update(bigger, mapOf(GROWTH_LAYER_ID to harvested)))
    assertNotSame(cut, grown)
    assertEquals(listOf(1, 2), assertNotNull(grown.growth).fields.map { it.id })
  }

  @Test
  fun countsEveryPlaneItIsGivenOffOneGrid() {
    val publisher = FieldStatusPublisher()
    val soil =
      MapLayerData(
        version = "3",
        terrainSize = 1024f,
        gridSize = 4,
        id = SOIL_LAYER_ID,
        legend = listOf(MapLayerLegendEntry(v = 2, label = "Needs plowing", color = "#7b4b2a", kind = "needsPlowing")),
        // Two of the field's four cells need plowing; the other two carry nothing, which on this plane
        // means "no condition to report" rather than "not field ground".
        rows = listOf("0200", "0200", "", ""),
      )

    val both = assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to ready, SOIL_LAYER_ID to soil)))
    assertEquals(listOf(GROWTH_LAYER_ID, SOIL_LAYER_ID), both.planes.map { it.layerId })

    val condition = assertNotNull(both.soil).fields.single()
    assertEquals(2, condition.cells)
    assertEquals(2, condition.blank)
    // The share is of the whole field, not of the cells that had something to say — otherwise two
    // unploughed cells on a four-cell field would read as the whole field.
    assertEquals(0.5f, condition.polygonFractionOf("needsPlowing"), 1e-6f)

    // A plane nobody has swept is simply absent, never present and zeroed.
    val growthOnly = assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to ready)))
    assertNull(growthOnly.soil)
    assertNotNull(growthOnly.growth)
  }

  @Test
  fun dropsTheGridWithTheMap() {
    val publisher = FieldStatusPublisher()
    publisher.update(map, mapOf(GROWTH_LAYER_ID to ready))

    // A map load takes the geometry away for a beat. What must not survive it is the grid: the next
    // map's polygons are a different set on the same ids, and counting them against the old index
    // would report the previous save's fields with this one's numbers.
    assertNull(publisher.update(null, mapOf(GROWTH_LAYER_ID to ready)))
    assertNull(publisher.current())

    val other =
      MapData(
        terrainSize = 1024f,
        fields = listOf(MapField(id = 1, areaHa = 6f, polygon = listOf(0.5f, 0.5f, 1f, 0.5f, 1f, 1f, 0.5f, 1f))),
      )
    val status = assertNotNull(publisher.update(other, mapOf(GROWTH_LAYER_ID to ready))).growth
    // Field 1 now sits in the far corner, where this raster carries nothing at all.
    assertEquals(0, assertNotNull(status).fields.single().cells)
    assertEquals(4, status.fields.single().blank)
  }
}
