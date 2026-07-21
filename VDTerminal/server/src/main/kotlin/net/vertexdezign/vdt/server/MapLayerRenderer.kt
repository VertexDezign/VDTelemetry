package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.MapLayersData
import net.vertexdezign.vdt.model.contentVersion
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
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
   * Per-id cache of the last rendered PNG, keyed on [contentVersion] so a new sweep invalidates it.
   * Capped at 3 entries (one per real layer id today) as a safety net against unbounded growth if
   * layer ids ever churn. A concurrent duplicate render on a miss is benign — both requests compute
   * and store the same bytes for the same key — so this only needs a thread-safe map, not a lock.
   */
  private val cache = ConcurrentHashMap<String, Pair<String, ByteArray>>()
  private const val MAX_CACHE_ENTRIES = 3

  /**
   * Rendered PNG for [layerId], memoized on `(layerId, contentVersion)`. Null for an unknown id.
   * [version] is the caller's already-computed [contentVersion] of [data], so the route can validate
   * the request against it without hashing the rows twice.
   */
  fun rendered(
    data: MapLayersData,
    layerId: String,
    version: String = data.contentVersion(),
  ): ByteArray? {
    cache[layerId]?.let { (cachedVersion, bytes) -> if (cachedVersion == version) return bytes }

    val bytes = render(data, layerId) ?: return null
    if (cache.size >= MAX_CACHE_ENTRIES && !cache.containsKey(layerId)) {
      cache.keys.firstOrNull()?.let { cache.remove(it) }
    }
    cache[layerId] = version to bytes
    return bytes
  }

  /** Render [layerId] fresh, bypassing the cache. Null when [layerId] doesn't match any layer. */
  fun render(
    data: MapLayersData,
    layerId: String,
  ): ByteArray? {
    val layer = data.layers.firstOrNull { it.id == layerId } ?: return null
    val gridSize = data.gridSize
    if (gridSize <= 0) return null

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
