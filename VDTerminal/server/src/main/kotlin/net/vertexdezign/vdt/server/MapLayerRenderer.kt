package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.MapLayer
import net.vertexdezign.vdt.model.MapLayersData
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Renders one [net.vertexdezign.vdt.model.MapLayer] into a translucent PNG, one pixel per grid cell
 * — the ground-layer counterpart to [ImagePipeline], reusing its `setRGB` bulk ARGB-array idiom.
 *
 * A cell whose value is 0 or absent from the layer's legend renders fully transparent; every other
 * cell gets the legend's color at a fixed [ALPHA], since this is drawn as an overlay on top of the
 * base map image, not a standalone picture.
 */
object MapLayerRenderer {
  private const val ALPHA = 0x99

  /**
   * The rendered PNGs of a single [MapLayersData.contentVersion], held together so the cache is
   * bounded by the number of layers in one snapshot (three today) with no eviction policy to get
   * wrong — a version bump drops the whole generation at once rather than picking an entry to
   * discard.
   */
  private data class Generation(
    val version: String,
    val layers: Map<String, ByteArray>,
  )

  @Volatile private var generation: Generation? = null

  /**
   * Rendered PNG for [layerId], memoized on `(layerId, version)`. Null for an unknown id.
   * [version] is the caller's already-computed [MapLayersData.contentVersion] of [data], so the
   * route can validate the request against it without re-deriving it here.
   *
   * Lock-free, and safe under concurrent requests for different versions: the version is bound to
   * the map it was rendered from, so a racing older generation can only overwrite a newer one
   * wholesale — costing a re-render on the next request, never serving one version's bytes under
   * another's (which is the one thing an immutable-for-a-year cache URL cannot survive).
   */
  fun rendered(
    data: MapLayersData,
    layerId: String,
    version: String = data.contentVersion,
  ): ByteArray? {
    val cached = generation
    if (cached != null && cached.version == version) {
      cached.layers[layerId]?.let { return it }
    }

    val bytes = render(data, layerId) ?: return null
    val base = if (cached != null && cached.version == version) cached.layers else emptyMap()
    generation = Generation(version, base + (layerId to bytes))
    return bytes
  }

  /** Render [layerId] fresh, bypassing the cache. Null when [layerId] doesn't match any layer. */
  fun render(
    data: MapLayersData,
    layerId: String,
  ): ByteArray? {
    val layer = data.layers.firstOrNull { it.id == layerId } ?: return null
    val gridSize = data.gridSize
    // Same bound decodeCells enforces: past it decodeCells returns blank, and setRGB would then be
    // handed a pixel array that doesn't match the image it was told to fill.
    if (gridSize <= 0 || gridSize > MapLayer.MAX_GRID_SIZE) return null

    val colors = layer.legend.associate { it.v to parseArgb(it.color) }
    val cells = layer.decodeCells(gridSize)
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
