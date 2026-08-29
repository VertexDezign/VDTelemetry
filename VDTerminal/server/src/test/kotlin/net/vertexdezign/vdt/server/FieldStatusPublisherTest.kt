package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.CROPS_LAYER_ID
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
    // The planes it counts are a specific set; a plane outside it is not a substitute for one in it.
    // A Precision Farming plane is the real example: a value there is a measurement, not a state, so
    // its legend carries no kind and there is nothing to bucket cells into.
    assertNull(publisher.update(map, mapOf("pfPh" to growth("1515").copy(id = "pfPh"))), "no counted plane")
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

    // A Precision Farming sweep re-emits the whole keyed map with a new instance in it. Nothing this
    // publisher counts has moved, so it must not rebuild anything — and returning the same instance is
    // what makes the StateFlow drop it instead of broadcasting an identical breakdown to every
    // dashboard on every sweep of every plane.
    val withPf = mapOf(GROWTH_LAYER_ID to ready, "pfPh" to growth("0101").copy(id = "pfPh"))
    assertSame(first, publisher.update(map, withPf))

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
  fun countsTheCropsPlanePerFruit() {
    val publisher = FieldStatusPublisher()
    val crops =
      MapLayerData(
        version = "3",
        terrainSize = 1024f,
        gridSize = 4,
        id = CROPS_LAYER_ID,
        legend =
        listOf(
          MapLayerLegendEntry(v = 1, label = "Wheat", color = "#d8c15a", kind = "crop"),
          MapLayerLegendEntry(v = 2, label = "Barley", color = "#c8b44a", kind = "crop"),
        ),
        rows = listOf("0101", "0102", "", ""),
      )

    val status = assertNotNull(publisher.update(map, mapOf(CROPS_LAYER_ID to crops)))
    // The grouping is per plane, and the publisher takes it from the same FIELD_STATUS_PLANES the app
    // subscribes off — so a plane cannot be swept under one rule and counted under another.
    assertEquals(
      listOf(FieldStatusSlice("crop", 3, "Wheat"), FieldStatusSlice("crop", 1, "Barley")),
      assertNotNull(status.crops).fields.single().slices,
    )
  }

  @Test
  fun leavesOutAPlaneThatCannotBeLaidOverTheGrid() {
    val publisher = FieldStatusPublisher()
    val first = assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to ready)))
    assertNotNull(first.growth)

    // A map load replaces the geometry a beat away from the raster it belongs with, and the histogram
    // refuses the pair. What it returns then is a plane with no fields in it -- indistinguishable from
    // a plane that was swept and found nothing -- so the publisher has to leave it out rather than
    // pass the refusal on as a breakdown. Present-and-empty reads downstream as "0 ha ready to
    // harvest", where the honest answer is that we cannot tell.
    assertNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to ready.copy(terrainSize = 2048f))), "another map")
    // And the histogram it used to hold goes with it: it describes a sweep this raster has replaced,
    // so serving it on would answer with the previous grid's numbers.
    assertNull(publisher.current())

    // The other way a plane can fail to fit is disagreeing with the plane that sized the index -- one
    // raster alone always fits, because it is what the grid was built to. Registry order puts growth
    // first, so soil at another resolution is the one refused, and the plane that does fit is counted
    // beside it as usual.
    val soil =
      MapLayerData(
        version = "3",
        terrainSize = 1024f,
        gridSize = 4,
        id = SOIL_LAYER_ID,
        legend = listOf(MapLayerLegendEntry(v = 2, label = "Needs plowing", color = "#7b4b2a", kind = "needsPlowing")),
        rows = listOf("0200", "0200", "", ""),
      )
    val mixed =
      assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to ready, SOIL_LAYER_ID to soil.copy(gridSize = 8))))
    assertNotNull(mixed.growth, "the plane the grid was built to is counted")
    assertNull(mixed.soil, "the plane that does not fit is absent, not present and zeroed")

    // Both come back on their own once the two agree again.
    val agreed = assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to ready, SOIL_LAYER_ID to soil)))
    assertNotNull(agreed.growth)
    assertNotNull(agreed.soil)
  }

  @Test
  fun countsAMalformedRasterAsBlankRatherThanCallingItAFailure() {
    val publisher = FieldStatusPublisher()

    // Geometry the index accepts, content it cannot make sense of: junk hex, a row that stops early,
    // a row missing altogether. There is no decode failure to report here -- decodeCells is total for
    // an accepted grid size, and blank is its documented answer for junk, which is also how an
    // ordinary empty row arrives. So this is a plane that was swept and found nothing, and the
    // publisher is right to serve it: refusing it would withhold the answer "the field is bare".
    val malformed = growth("zzzz", "15")
    val status = assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to malformed)))
    val field = assertNotNull(status.growth).fields.single()

    // One cell survived -- the readable half of the short row -- and the rest of the polygon is blank.
    assertEquals(listOf(FieldStatusSlice("harvest", 1)), field.slices)
    assertEquals(1, field.cells)
    assertEquals(3, field.blank)
    assertEquals(4, field.polygonCells, "every cell is accounted for; none was lost to the junk")

    // And the wholly unreadable raster is the all-blank field, which the app reads as bare rather
    // than as an absence -- the distinction fieldHeadline's isBareByRaster rests on.
    val unreadable = assertNotNull(publisher.update(map, mapOf(GROWTH_LAYER_ID to growth("zzzz", "zzzz"))))
    val bare = assertNotNull(unreadable.growth).fields.single()
    assertEquals(0, bare.cells)
    assertEquals(4, bare.polygonCells)
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
