package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.VdtParser
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Renders the committed `examples/json/mapLayers/basic.json` fixture and asserts exact ARGB pixel
 * values: a legend-mapped cell gets its color at the fixed alpha, a zero/trimmed-tail cell is fully
 * transparent, and an unknown layer id renders nothing.
 */
class MapLayerRendererTest {
  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/mapLayers/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/mapLayers/$name from ${File(".").absolutePath}")
  }

  private fun decode(bytes: ByteArray): BufferedImage =
    ImageIO.read(ByteArrayInputStream(bytes)) ?: error("failed to decode rendered PNG")

  @Test
  fun rendersLegendColorsAtTheFixedAlpha() {
    val data = VdtParser.parseMapLayers(example("basic.json"))
    val bytes = MapLayerRenderer.render(data, "crops")!!
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
    val data = VdtParser.parseMapLayers(example("basic.json"))
    val img = decode(MapLayerRenderer.render(data, "crops")!!)

    assertEquals(0, img.getRGB(0, 0)) // row 0 is entirely "" (all-zero, right-trimmed)
    assertEquals(0, img.getRGB(2, 1)) // row 1 = "0101": only cols 0-1 are non-zero
  }

  @Test
  fun rendersGrowthAndSoilLayersAtTheirOwnLegendColors() {
    val data = VdtParser.parseMapLayers(example("basic.json"))

    val growth = decode(MapLayerRenderer.render(data, "growth")!!)
    assertEquals(0x994D78B8.toInt(), growth.getRGB(0, 1)) // v=1 "Cultivated"
    assertEquals(0x992B7A06.toInt(), growth.getRGB(0, 2)) // v=11 "Growing"

    val soil = decode(MapLayerRenderer.render(data, "soil")!!)
    assertEquals(0x9915A86C.toInt(), soil.getRGB(0, 1)) // v=21 "Needs lime"
    assertEquals(0x991A4DD1.toInt(), soil.getRGB(0, 2)) // v=31 "Fertilized"
  }

  @Test
  fun returnsNullForAnUnknownLayerId() {
    val data = VdtParser.parseMapLayers(example("basic.json"))
    assertNull(MapLayerRenderer.render(data, "nope"))
  }

  /** A corrupt grid size renders nothing (404) rather than allocating for it or throwing. */
  @Test
  fun returnsNullForAnOutOfRangeGridSize() {
    val data = VdtParser.parseMapLayers(example("basic.json"))

    assertNull(MapLayerRenderer.render(data.copy(gridSize = 0), "crops"))
    assertNull(MapLayerRenderer.render(data.copy(gridSize = -8), "crops"))
    assertNull(MapLayerRenderer.render(data.copy(gridSize = 100_000), "crops"))
  }

  @Test
  fun renderedIsMemoizedUntilTheDataChanges() {
    val data = VdtParser.parseMapLayers(example("basic.json"))
    val first = MapLayerRenderer.rendered(data, "crops")
    val second = MapLayerRenderer.rendered(data, "crops")
    assertEquals(first!!.toList(), second!!.toList())

    val changed = data.copy(terrainSize = data.terrainSize + 1)
    val third = MapLayerRenderer.rendered(changed, "crops")
    assertEquals(first.toList(), third!!.toList()) // same pixels, but recomputed under a new cache key
  }
}
