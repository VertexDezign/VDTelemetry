package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.FieldStatusSlice
import net.vertexdezign.vdt.model.MapData
import net.vertexdezign.vdt.model.MapField
import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.MapLayerLegendEntry
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
    id = FIELD_STATUS_LAYER_ID,
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
    assertNull(publisher.update(map, mapOf("soil" to growth("1515").copy(id = "soil"))), "wrong plane")
    assertNull(publisher.update(MapData(terrainSize = 1024f), mapOf(FIELD_STATUS_LAYER_ID to ready)), "no fields")
    assertNull(publisher.current())

    val status = assertNotNull(publisher.update(map, mapOf(FIELD_STATUS_LAYER_ID to ready)))
    assertEquals(listOf(FieldStatusSlice("harvest", 4)), status.fields.single().slices)
    assertSame(status, publisher.current())
  }

  @Test
  fun keepsTheSameValueWhenAnotherPlaneSweeps() {
    val publisher = FieldStatusPublisher()
    val first = assertNotNull(publisher.update(map, mapOf(FIELD_STATUS_LAYER_ID to ready)))

    // A soil sweep re-emits the whole keyed map with a new instance in it. The growth raster is
    // untouched, so this must not even rebuild the histogram — and returning the same instance is
    // what makes the StateFlow drop it instead of broadcasting an identical breakdown to every
    // dashboard on every sweep of every plane.
    val withSoil = mapOf(FIELD_STATUS_LAYER_ID to ready, "soil" to growth("0101").copy(id = "soil"))
    assertSame(first, publisher.update(map, withSoil))

    // An equal-but-different growth instance is the same raster, and the content version says so
    // without walking it twice.
    assertSame(first, publisher.update(map, mapOf(FIELD_STATUS_LAYER_ID to growth("1515", "1515"))))
  }

  @Test
  fun recomputesWhenTheRasterOrTheMapMoves() {
    val publisher = FieldStatusPublisher()
    val first = assertNotNull(publisher.update(map, mapOf(FIELD_STATUS_LAYER_ID to ready)))

    val cut = assertNotNull(publisher.update(map, mapOf(FIELD_STATUS_LAYER_ID to harvested)))
    assertNotSame(first, cut)
    assertEquals(listOf(FieldStatusSlice("cut", 4)), cut.fields.single().slices)

    // A bought farmland is a new field, and the index grid it is counted against has to be rebuilt —
    // the case a cache keyed on the raster alone would get wrong for as long as nothing was swept.
    val bigger =
      map.copy(
        fields =
        map.fields + MapField(id = 2, areaHa = 6f, polygon = listOf(0.5f, 0f, 1f, 0f, 1f, 0.5f, 0.5f, 0.5f)),
      )
    val grown = assertNotNull(publisher.update(bigger, mapOf(FIELD_STATUS_LAYER_ID to harvested)))
    assertNotSame(cut, grown)
    assertEquals(listOf(1, 2), grown.fields.map { it.id })
  }

  @Test
  fun dropsTheGridWithTheMap() {
    val publisher = FieldStatusPublisher()
    publisher.update(map, mapOf(FIELD_STATUS_LAYER_ID to ready))

    // A map load takes the geometry away for a beat. What must not survive it is the grid: the next
    // map's polygons are a different set on the same ids, and counting them against the old index
    // would report the previous save's fields with this one's numbers.
    assertNull(publisher.update(null, mapOf(FIELD_STATUS_LAYER_ID to ready)))
    assertNull(publisher.current())

    val other =
      MapData(
        terrainSize = 1024f,
        fields = listOf(MapField(id = 1, areaHa = 6f, polygon = listOf(0.5f, 0.5f, 1f, 0.5f, 1f, 1f, 0.5f, 1f))),
      )
    val status = assertNotNull(publisher.update(other, mapOf(FIELD_STATUS_LAYER_ID to ready)))
    // Field 1 now sits in the far corner, where this raster carries nothing at all.
    assertEquals(0, status.fields.single().cells)
    assertEquals(4, status.fields.single().blank)
  }
}
