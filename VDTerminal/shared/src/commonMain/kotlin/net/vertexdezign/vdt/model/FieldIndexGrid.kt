package net.vertexdezign.vdt.model

import kotlin.math.ceil
import kotlin.math.floor

/** A raster cell is a byte, so this is every value [MapLayerData.decodeCells] can produce, plus one. */
private const val CELL_VALUES = 256

/** Cell value 0 is the planes' "nothing here" — the mod never records it as seen, so it is never in a legend. */
private const val NOTHING = 0

/** Owner of a cell no field claims. Farmland ids start at 1, so 0 is free to mean "none". */
private const val NO_FIELD = 0

/**
 * Which field owns each cell of a ground-layer raster — the join that turns a plane of ground states
 * into a per-field breakdown.
 *
 * The two sides co-register by construction, which is why this is a counting pass and not a
 * projection: `map.json`'s polygons are normalized `[0,1]` over the terrain
 * (`MapExporter.normalizeCoord`: `(world + size/2) / size`), and a plane's cell `(row, col)` is
 * sampled at `-size/2 + (col + 0.5) * size / gridSize` — so cell `col` spans normalized
 * `[col/gridSize, (col+1)/gridSize]` and its centre sits at `(col + 0.5) / gridSize`. Multiply a
 * normalized coordinate by [gridSize] and both live in the same cell frame.
 *
 * Built once per `(map, gridSize)` and reused across sweeps: the fill is bounded by the same
 * `gridSize²` the counting pass walks, and the map changes far more rarely than the raster does.
 *
 * ### Why not [WorkSweep]'s scanline
 *
 * `CoverageRecorder.fill` takes the **outermost** crossings on each row, which fills the convex hull —
 * correct there, because a swept area is convex by construction. Fields are not: an L-shape, a field
 * wrapped around a wood or a farmyard, a field with a bite out of it for a road are all ordinary, and
 * hull-filling one would claim its neighbours' cells and quietly corrupt both fields' numbers. So this
 * collects every crossing on the row, sorts them and fills between consecutive **pairs** — the
 * even-odd rule.
 *
 * What it does keep from that fill are the two habits that make a cell mean one thing: a cell is
 * claimed when its **centre** lies inside the polygon (not when the polygon grazes it), and an edge
 * crossing a scanline is counted half-open, so a vertex sitting exactly on the row is counted once
 * rather than twice.
 *
 * ### Overlap
 *
 * The game forbids it: `FieldManager:loadMapData` drops a field whose polygon touches a second
 * farmland, and refuses two fields on one farmland — which is what makes field id, farmland id and the
 * displayed field number one integer. A contested cell is therefore last-writer-wins, and [overlaps]
 * counts them so a test can assert the rule instead of the code assuming it.
 */
class FieldIndexGrid private constructor(
  /** Cells per edge; 0 when there is no usable geometry, which makes every [histogram] empty. */
  val gridSize: Int,
  /** The map's terrain edge in meters — kept to size cells in hectares and to refuse a raster from another map. */
  val terrainSize: Float,
  /** Field id per cell, row-major, [NO_FIELD] for ground no field covers. */
  private val owner: IntArray,
  /** Field ids present in [owner], ascending. The row order of every [FieldStatusData] built here. */
  private val ids: IntArray,
  /** id -> index into [ids], -1 for an id no cell carries. Sized to the largest id, so lookup is an array read. */
  private val slotOfId: IntArray,
  /**
   * Cells a second field claimed after a first one already had them. Expected to be 0 on every real
   * map — see the class note; a non-zero count means the map broke the game's own 1:1 rule, and the
   * numbers for both fields are then approximate rather than wrong in a way anyone can see.
   */
  val overlaps: Int,
) {
  /** How many fields this grid resolved at least one cell for. */
  val fieldCount: Int get() = ids.size

  /** The field covering one cell, or 0 for none. Out-of-range coordinates read as 0 rather than throwing. */
  fun fieldAt(row: Int, col: Int): Int =
    if (gridSize <= 0 || row < 0 || col < 0 || row >= gridSize || col >= gridSize) {
      NO_FIELD
    } else {
      owner[row * gridSize + col]
    }

  /**
   * Count [layer]'s cells per field and per [MapLayerLegendEntry.kind].
   *
   * One walk over the raster: an array read for the owner, an array read for the bucket, an increment.
   * Returns an empty result — never a partial or a wrong one — when the raster can't be trusted to
   * line up: a different [MapLayerData.gridSize], a [MapLayerData.terrainSize] from another map, or a
   * raster that failed to decode.
   *
   * Cells are grouped by kind rather than by value, so every step of the growth plane's growing
   * gradient counts as `growing` and a value the legend doesn't name counts as [UNKNOWN_FIELD_KIND].
   * On a plane whose entries share one kind — every crops-plane entry is `crop` — that means one slice
   * covering the whole field; the finer question ("which crop, where") is a different pass over the
   * same grid, and `fieldInfo.crop` already answers the coarse version of it.
   */
  fun histogram(layer: MapLayerData): FieldStatusData {
    val empty = FieldStatusData(layerId = layer.id)
    if (gridSize <= 0) return empty
    // A plane sampled at another resolution or on another map cannot be laid over this index. Both are
    // real: the mod may bump GRID_SIZE, and a map load replaces the geometry a beat before or after the
    // raster it belongs with.
    if (layer.gridSize != gridSize) return empty
    if (terrainSize > 0f && layer.terrainSize > 0f && layer.terrainSize != terrainSize) return empty
    val raster = layer.decodeCells(gridSize)
    if (raster.size != owner.size) return empty

    // Bucket 0 is always the unknown one so the counting loop never has to create a bucket mid-walk;
    // it is dropped below when nothing landed in it, which is the normal case.
    val bucketNames = mutableListOf(UNKNOWN_FIELD_KIND)
    val bucketOfKind = HashMap<String, Int>()
    bucketOfKind[UNKNOWN_FIELD_KIND] = 0
    val bucketOfValue = IntArray(CELL_VALUES) // every value starts unknown; the legend names the rest
    for (entry in layer.legend) {
      if (entry.v <= NOTHING || entry.v >= CELL_VALUES) continue
      val kind = entry.kind ?: UNKNOWN_FIELD_KIND
      var bucket = bucketOfKind[kind]
      if (bucket == null) {
        bucket = bucketNames.size
        bucketNames.add(kind)
        bucketOfKind[kind] = bucket
      }
      bucketOfValue[entry.v] = bucket
    }

    val counts = Array(ids.size) { IntArray(bucketNames.size) }
    val sampled = IntArray(ids.size)
    val blank = IntArray(ids.size)
    for (cell in raster.indices) {
      val id = owner[cell]
      if (id == NO_FIELD) continue
      val slot = slotOfId[id]
      val value = raster[cell]
      if (value == NOTHING) {
        blank[slot]++
        continue
      }
      counts[slot][bucketOfValue[value]]++
      sampled[slot]++
    }

    val meters = (if (terrainSize > 0f) terrainSize else layer.terrainSize) / gridSize
    return FieldStatusData(
      layerId = layer.id,
      haPerCell = meters * meters / 10_000f,
      fields =
      ids.indices.map { slot ->
        val row = counts[slot]
        val slices = mutableListOf<FieldStatusSlice>()
        for (bucket in row.indices) {
          if (row[bucket] > 0) slices.add(FieldStatusSlice(bucketNames[bucket], row[bucket]))
        }
        // Ties broken by kind, so the same raster always produces the same order — the app draws these
        // in order, and a stacked bar whose segments swap places between sweeps reads as movement.
        slices.sortWith(compareByDescending<FieldStatusSlice> { it.cells }.thenBy { it.kind })
        FieldStatus(id = ids[slot], cells = sampled[slot], blank = blank[slot], slices = slices)
      },
    )
  }

  companion object {
    private val EMPTY = FieldIndexGrid(0, 0f, IntArray(0), IntArray(0), IntArray(0), 0)

    /**
     * Rasterize [map]'s field polygons onto a [gridSize]² grid.
     *
     * Fields without a usable polygon (fewer than three points, or an odd coordinate count) and fields
     * with a non-positive id are skipped — id 0 is how a cell says "no field", so a field carrying the
     * model's default id could not be told from bare ground. A field too small or too thin to contain
     * any cell centre claims nothing and simply never appears in a [histogram]; that is the case the
     * caller falls back to `fieldInfo`'s point sample for.
     */
    fun of(map: MapData, gridSize: Int): FieldIndexGrid {
      if (gridSize <= 0 || gridSize > MapLayerData.MAX_GRID_SIZE) return EMPTY
      val owner = IntArray(gridSize * gridSize)
      var overlaps = 0
      var maxId = 0
      // One buffer for every field's crossings: a polygon is capped at MAX_POLYGON_POINTS mod-side, so
      // a row can never produce more crossings than a polygon has edges.
      var crossings = FloatArray(0)

      for (field in map.fields) {
        val polygon = field.polygon
        val points = polygon.size / 2
        if (field.id <= NO_FIELD || points < 3 || polygon.size % 2 != 0) continue
        if (crossings.size < points) crossings = FloatArray(points)

        // Normalized -> cell coordinates. Everything below is in cells.
        val xs = FloatArray(points) { polygon[it * 2] * gridSize }
        val zs = FloatArray(points) { polygon[it * 2 + 1] * gridSize }

        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (z in zs) {
          if (z < minZ) minZ = z
          if (z > maxZ) maxZ = z
        }
        if (minZ > maxZ) continue
        val firstRow = floor(minZ).toInt().coerceIn(0, gridSize - 1)
        val lastRow = ceil(maxZ).toInt().coerceIn(0, gridSize - 1)

        for (row in firstRow..lastRow) {
          val centre = row + 0.5f
          var found = 0
          for (i in 0 until points) {
            val j = if (i + 1 == points) 0 else i + 1
            val z1 = zs[i]
            val z2 = zs[j]
            // Half-open: of the two edges meeting at a vertex that sits exactly on the scanline, only
            // one crosses it, so the vertex contributes a single crossing and the even-odd pairing
            // stays intact. A horizontal edge on the row crosses with neither, which is what keeps it
            // from opening a span that never closes.
            val crosses = (z1 <= centre && z2 > centre) || (z2 <= centre && z1 > centre)
            if (!crosses) continue
            crossings[found++] = xs[i] + (centre - z1) / (z2 - z1) * (xs[j] - xs[i])
          }
          if (found < 2) continue
          // Insertion sort: crossings per row are two or four on almost every field, and this runs on
          // a sub-range of a reused buffer, which the stdlib sorts can't do without allocating.
          for (i in 1 until found) {
            val value = crossings[i]
            var k = i - 1
            while (k >= 0 && crossings[k] > value) {
              crossings[k + 1] = crossings[k]
              k--
            }
            crossings[k + 1] = value
          }

          var pair = 0
          while (pair + 1 < found) {
            val lo = crossings[pair]
            val hi = crossings[pair + 1]
            pair += 2
            // Cell `col` stands for the point at col + 0.5, so the cells inside are those whose centre
            // lies in [lo, hi]. Bounds-checked before clamping, or a span entirely off one side of the
            // map would clamp both ends onto the edge cell and claim it.
            val fromCol = ceil(lo - 0.5f).toInt()
            val toCol = floor(hi - 0.5f).toInt()
            if (toCol < 0 || fromCol > gridSize - 1 || fromCol > toCol) continue
            val base = row * gridSize
            for (col in fromCol.coerceAtLeast(0)..toCol.coerceAtMost(gridSize - 1)) {
              val previous = owner[base + col]
              if (previous != NO_FIELD && previous != field.id) overlaps++
              owner[base + col] = field.id
            }
          }
        }
        if (field.id > maxId) maxId = field.id
      }

      // Which ids actually landed, read off the grid rather than off the field list: a field whose
      // polygon claimed no cell centre has nothing to report, and a row of zeroes in every breakdown
      // is worse than an absence the caller can fall back from.
      val slotOfId = IntArray(maxId + 1) { -1 }
      var present = 0
      for (id in owner) {
        if (id != NO_FIELD && slotOfId[id] < 0) {
          slotOfId[id] = 0 // marked; the real slot is assigned below, in id order
          present++
        }
      }
      val ids = IntArray(present)
      var slot = 0
      for (id in slotOfId.indices) {
        if (slotOfId[id] == 0 && id != NO_FIELD) {
          ids[slot] = id
          slotOfId[id] = slot
          slot++
        }
      }
      return FieldIndexGrid(gridSize, map.terrainSize, owner, ids, slotOfId, overlaps)
    }
  }
}

/**
 * The per-field breakdown of one plane, building the index grid on the way.
 *
 * The convenience form, for a caller with no reason to hold the grid. Anything recomputing this per
 * sweep should keep the [FieldIndexGrid] instead — it depends only on the map, and rebuilding it for
 * every raster does the same work twice.
 */
fun fieldStatus(map: MapData, layer: MapLayerData): FieldStatusData =
  FieldIndexGrid.of(map, layer.gridSize).histogram(layer)
