package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.MapLayerData
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Renders one [MapLayerData] into a translucent PNG, one pixel per grid cell — the ground-layer
 * counterpart to [ImagePipeline], reusing its `setRGB` bulk ARGB-array idiom.
 *
 * A cell whose value is 0 or absent from the layer's legend renders fully transparent; every other
 * cell gets the legend's color at a fixed [ALPHA], since this is drawn as an overlay on top of the
 * base map image, not a standalone picture.
 */
object MapLayerRenderer {
  private const val ALPHA = 0x99

  /**
   * The most recently rendered PNG per layer id, each tagged with the version it was rendered from.
   * Bounded by the number of planes the map offers (three today, ~eight once Precision Farming lands)
   * with no eviction policy to get wrong — a plane's new version replaces that plane's entry and
   * leaves the others, which is exactly right now that each plane has its own version and they move
   * independently.
   */
  private data class Rendered(val version: String, val png: ByteArray)

  @Volatile private var cache: Map<String, Rendered> = emptyMap()

  /**
   * Rendered PNG for [data], memoized on `(data.id, version)`. Null when the raster can't be
   * rendered. [version] is the caller's already-computed [MapLayerData.contentVersion] of [data], so
   * the route can validate the request against it without re-deriving it here.
   *
   * Lock-free, and safe under concurrent requests for different versions: each entry carries the
   * version it was rendered from, so a racing older render can only replace a newer entry with an
   * equally self-describing one — costing a re-render on the next request, never serving one
   * version's bytes under another's (the one thing an immutable-for-a-year cache URL cannot
   * survive).
   */
  fun rendered(data: MapLayerData, version: String = data.contentVersion): ByteArray? {
    cache[data.id]?.let { if (it.version == version) return it.png }

    val bytes = render(data) ?: return null
    cache = cache + (data.id to Rendered(version, bytes))
    return bytes
  }

  /** Render [data] fresh, bypassing the cache. Null when the grid size is unusable. */
  fun render(data: MapLayerData): ByteArray? {
    val gridSize = data.gridSize
    // Same bound decodeCells enforces: past it decodeCells returns blank, and setRGB would then be
    // handed a pixel array that doesn't match the image it was told to fill.
    if (gridSize <= 0 || gridSize > MapLayerData.MAX_GRID_SIZE) return null

    val colors = data.legend.associate { it.v to parseArgb(it.color) }
    val cells = data.decodeCells(gridSize)
    val pixels = IntArray(cells.size) { i -> if (cells[i] == 0) 0 else colors[cells[i]] ?: 0 }

    val img = BufferedImage(gridSize, gridSize, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, gridSize, gridSize, pixels, 0, gridSize)

    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
  }

  /** `"#rrggbb"` -> ARGB int at [ALPHA]; fully transparent (0) for null/malformed input. */
  private fun parseArgb(hex: String?): Int {
    if (hex == null || hex.length != 7 || !hex.startsWith("#")) return 0
    val rgb = hex.substring(1).toIntOrNull(16) ?: return 0
    return (ALPHA shl 24) or rgb
  }
}
