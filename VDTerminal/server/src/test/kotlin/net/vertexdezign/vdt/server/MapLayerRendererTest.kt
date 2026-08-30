package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.MapLayerData
import net.vertexdezign.vdt.model.MapLayerLegendEntry
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Renders small synthetic planes and asserts exact ARGB pixel values: a legend-mapped cell gets its
 * color at the fixed alpha, a zero/trimmed-tail cell is fully transparent, and an unusable grid
 * renders nothing.
 *
 * Synthetic on purpose, unlike the channel-contract tests in `:shared`. What is asserted here is a
 * pixel at a coordinate, so the grid has to be small enough to name cells by hand and stable enough
 * that a re-capture cannot move them — the committed capture is a real 512² sweep and is neither.
 * These grids are the shape the fixtures had when they were 8×8, kept for the same reason.
 */
class MapLayerRendererTest {
  private fun plane(id: String) = when (id) {
    "crops" -> MapLayerData(
      version = "3",
      terrainSize = 2048f,
      gridSize = 8,
      id = "crops",
      legend = listOf(
        MapLayerLegendEntry(1, "Weizen", "#c8b262", "crop"),
        MapLayerLegendEntry(2, "Mais", "#f5d743", "crop"),
      ),
      // Row 0 is entirely trimmed away, row 1 stops after two cells, row 2 is full.
      rows = listOf("", "0101", "0202020202020202"),
    )

    "growth" -> MapLayerData(
      version = "3",
      terrainSize = 2048f,
      gridSize = 8,
      id = "growth",
      legend = listOf(
        MapLayerLegendEntry(1, "Cultivated", "#4d78b8", "cultivated"),
        MapLayerLegendEntry(11, "Growing", "#2b7a06", "growing"),
      ),
      rows = listOf("", "01", "0b"),
    )

    "soil" -> MapLayerData(
      version = "3",
      terrainSize = 2048f,
      gridSize = 8,
      id = "soil",
      legend = listOf(
        MapLayerLegendEntry(21, "Needs lime", "#15a86c", "needsLime"),
        MapLayerLegendEntry(31, "Fertilized", "#1a4dd1", "fertilized"),
      ),
      rows = listOf("", "15", "1f"),
    )

    else -> error("no synthetic plane for $id")
  }

  private fun decode(bytes: ByteArray): BufferedImage =
    ImageIO.read(ByteArrayInputStream(bytes)) ?: error("failed to decode rendered PNG")

  @Test
  fun rendersLegendColorsAtTheFixedAlpha() {
    val bytes = MapLayerRenderer.render(plane("crops"))!!
    val img = decode(bytes)

    assertEquals(8, img.width)
    assertEquals(8, img.height)
    // Row 1, col 0: v=1 -> "Weizen" #c8b262.
    assertEquals(0x99C8B262.toInt(), img.getRGB(0, 1))
    // Row 2: v=2 across the whole row -> "Mais" #f5d743.
    assertEquals(0x99F5D743.toInt(), img.getRGB(0, 2))
    assertEquals(0x99F5D743.toInt(), img.getRGB(7, 2))
  }

  @Test
  fun rendersZeroAndTrimmedTailCellsAsFullyTransparent() {
    val img = decode(MapLayerRenderer.render(plane("crops"))!!)

    assertEquals(0, img.getRGB(0, 0)) // row 0 is entirely "" (all-zero, right-trimmed)
    assertEquals(0, img.getRGB(2, 1)) // row 1 = "0101": only cols 0-1 are non-zero
  }

  @Test
  fun rendersGrowthAndSoilLayersAtTheirOwnLegendColors() {
    val growth = decode(MapLayerRenderer.render(plane("growth"))!!)
    assertEquals(0x994D78B8.toInt(), growth.getRGB(0, 1)) // v=1 "Cultivated"
    assertEquals(0x992B7A06.toInt(), growth.getRGB(0, 2)) // v=11 "Growing"

    val soil = decode(MapLayerRenderer.render(plane("soil"))!!)
    assertEquals(0x9915A86C.toInt(), soil.getRGB(0, 1)) // v=21 "Needs lime"
    assertEquals(0x991A4DD1.toInt(), soil.getRGB(0, 2)) // v=31 "Fertilized"
  }

  /** A corrupt grid size renders nothing (404) rather than allocating for it or throwing. */
  @Test
  fun returnsNullForAnOutOfRangeGridSize() {
    val data = plane("crops")

    assertNull(MapLayerRenderer.render(data.copy(gridSize = 0)))
    assertNull(MapLayerRenderer.render(data.copy(gridSize = -8)))
    assertNull(MapLayerRenderer.render(data.copy(gridSize = 100_000)))
  }

  @Test
  fun renderedIsMemoizedUntilTheDataChanges() {
    val data = plane("crops")
    val first = MapLayerRenderer.rendered(data)
    // Same instance, not merely equal bytes: equal pixels would also be what a re-render produces, so
    // identity is the only assertion that distinguishes a cache hit from a miss.
    assertSame(first, MapLayerRenderer.rendered(data))

    val changed = data.copy(terrainSize = data.terrainSize + 1)
    val third = MapLayerRenderer.rendered(changed)
    assertNotSame(first, third, "a new version must re-render rather than serve the old entry")
    assertEquals(first!!.toList(), third!!.toList()) // same pixels here; only the cache key moved
  }

  /**
   * The planes move independently now, so one plane's new version must not evict another's entry --
   * that would re-render every other overlay on every sweep of the one being watched.
   */
  @Test
  fun eachPlaneIsCachedSeparately() {
    val crops = plane("crops")
    val growth = plane("growth")
    MapLayerRenderer.rendered(crops)
    val growthPng = MapLayerRenderer.rendered(growth)

    // A new version of crops replaces only the crops entry...
    MapLayerRenderer.rendered(crops.copy(terrainSize = crops.terrainSize + 1))
    // ...so growth is still served from cache, byte-identical.
    assertSame(growthPng, MapLayerRenderer.rendered(growth))
  }
}
