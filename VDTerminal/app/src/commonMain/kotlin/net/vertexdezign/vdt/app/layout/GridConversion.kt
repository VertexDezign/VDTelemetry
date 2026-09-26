package net.vertexdezign.vdt.app.layout

/**
 * A tile as the old cell grid placed it — as much of a grid cell as converting one needs. Its own
 * type rather than the grid's `LayoutCell`, so the migration outlives the grid code it migrates from.
 */
data class GridPlacement(val tile: Tile, val col: Int, val row: Int, val colSpan: Int = 1, val rowSpan: Int = 1)

/**
 * Converts a [columns] × [rows] grid arrangement into a split tree by **guillotine cuts**: in a region,
 * find the column or row lines that no tile crosses and some tile ends on, cut there into strips, and
 * recurse into each strip. Weights are cell counts. A region with no tiles becomes one [Empty], so free
 * space arrives as a few regions rather than one empty per cell.
 *
 * Which of the clean lines to cut on is chosen, not "all of them": the cut with the fewest leaves
 * wins, and between equals the one with more strips, so three side-by-side tiles become one three-way
 * split. Cutting every line would have split the Vehicle page's 2×2 shortcut dock down the middle and
 * left its free row below as two empties instead of one.
 *
 * When a region can be cut both ways, both are tried the same way: fewer leaves, then fewer strips at
 * the top, then rows before columns — bands across the page, which is how the landscape pages read.
 *
 * A region no line crosses cleanly (a pinwheel) is cut anyway, on the line that crosses the fewest
 * tiles, each crossed tile going whole to the side holding the larger part of it. Lossy on purpose:
 * the page loads, [onLossy] hears about it, and the user fixes it with a divider drag.
 *
 * Weight = cell count differs from the grid by a fraction of a gap per tile (the grid's gaps sit inside
 * spans, a split's between children). Invisible, and not worth modelling.
 */
fun gridToTree(
  columns: Int,
  rows: Int,
  placements: List<GridPlacement>,
  emptyId: () -> String = ::newEmptyId,
  onLossy: (String) -> Unit = {},
): LayoutNode {
  val bounds = Region(0, 0, columns.coerceAtLeast(1), rows.coerceAtLeast(1))
  val pieces =
    placements.mapNotNull {
      Piece(it.tile, Region(it.col, it.row, it.col + it.colSpan, it.row + it.rowSpan)).clippedTo(bounds)
    }
  return Cutter(emptyId, onLossy).cut(bounds, pieces).normalize()
}

/** A cell rectangle, end-exclusive. */
private data class Region(val c0: Int, val r0: Int, val c1: Int, val r1: Int) {
  fun start(axis: Axis) = if (axis == Axis.Row) c0 else r0

  fun end(axis: Axis) = if (axis == Axis.Row) c1 else r1

  fun length(axis: Axis) = end(axis) - start(axis)

  /** This region cut to [from] until [to] along [axis]. */
  fun slice(axis: Axis, from: Int, to: Int) = if (axis ==
    Axis.Row
  ) {
    copy(c0 = from, c1 = to)
  } else {
    copy(r0 = from, r1 = to)
  }

  operator fun contains(other: Region) = other.c0 >= c0 && other.r0 >= r0 && other.c1 <= c1 && other.r1 <= r1
}

private data class Piece(val tile: Tile, val region: Region) {
  /** True if the line at [at] on [axis] runs through the inside of this piece. */
  fun crosses(axis: Axis, at: Int) = region.start(axis) < at && at < region.end(axis)

  fun clippedTo(bounds: Region): Piece? {
    val c =
      Region(
        maxOf(region.c0, bounds.c0),
        maxOf(region.r0, bounds.r0),
        minOf(region.c1, bounds.c1),
        minOf(region.r1, bounds.r1),
      )
    return if (c.c1 > c.c0 && c.r1 > c.r0) copy(region = c) else null
  }
}

/** A converted region and what it costs: the leaves it came to. */
private data class Cut(val node: LayoutNode, val leaves: Int)

private class Cutter(val emptyId: () -> String, val onLossy: (String) -> Unit) {
  /** Comparing cuts asks for the same region more than once; each is only solved once. */
  private val memo = HashMap<Pair<Region, List<Piece>>, Cut>()

  fun cut(region: Region, pieces: List<Piece>): LayoutNode = solve(region, pieces).node

  private fun solve(region: Region, pieces: List<Piece>): Cut = memo.getOrPut(region to pieces) {
    when {
      pieces.isEmpty() -> Cut(Empty(emptyId()), 1)

      pieces.size == 1 && region in pieces.single().region -> Cut(pieces.single().tile, 1)

      else -> {
        // Rows (horizontal lines, a Column split) first: that is the preference on a tie.
        val options =
          listOf(Axis.Column, Axis.Row).mapNotNull { axis ->
            // Only on a tile's edge: a line through free space alone would slice one empty region
            // into a row of single-cell ones.
            val edges = pieces.flatMapTo(mutableSetOf()) { listOf(it.region.start(axis), it.region.end(axis)) }
            val lines = interiorLines(region, axis).filter { at -> at in edges && pieces.none { it.crosses(axis, at) } }
            if (lines.isEmpty()) null else bestStrips(region, pieces, axis, lines)
          }
        options.minWithOrNull(compareBy({ it.leaves }, { (it.node as Split).children.size }))
          ?: lossyCut(region, pieces)
      }
    }
  }

  /**
   * The best way to cut [region] along [axis] on some of [lines]: over prefixes, the fewest leaves so
   * far, and between equals the most strips. Every strip between two clean lines holds whole tiles, so
   * any subset of them is a valid cut; this finds the best one in O(lines²) strips rather than trying
   * every subset.
   */
  private fun bestStrips(region: Region, pieces: List<Piece>, axis: Axis, lines: List<Int>): Cut {
    val points = listOf(region.start(axis)) + lines + region.end(axis)
    val best = arrayOfNulls<Pair<Int, List<Child>>>(points.size)
    best[0] = 0 to emptyList()
    for (i in 1..points.lastIndex) {
      for (j in 0 until i) {
        // One strip spanning the whole region is the region itself, not a cut.
        if (j == 0 && i == points.lastIndex) continue
        val (leaves, children) = best[j] ?: continue
        val strip = region.slice(axis, points[j], points[i])
        val sub = solve(strip, pieces.filter { it.region in strip })
        val candidate = leaves + sub.leaves to children + Child(strip.length(axis).toFloat(), sub.node)
        val current = best[i]
        val better =
          current == null ||
            candidate.first < current.first ||
            (candidate.first == current.first && candidate.second.size > current.second.size)
        if (better) best[i] = candidate
      }
    }
    val (leaves, children) = best.last()!!
    return Cut(Split(axis, children), leaves)
  }

  private fun lossyCut(region: Region, pieces: List<Piece>): Cut {
    val (axis, at) =
      listOf(Axis.Column, Axis.Row)
        .flatMap { axis -> interiorLines(region, axis).map { axis to it } }
        .minByOrNull { (axis, at) -> pieces.count { it.crosses(axis, at) } }
        // A single cell holding several tiles: only a damaged store gets here. Keep the first.
        ?: return Cut(pieces.first().tile, 1).also { onLossy("overlapping tiles ${pieces.map { it.tile.instanceId }}") }
    val crossed = pieces.filter { it.crosses(axis, at) }
    onLossy(
      "no clean cut; split ${crossed.map {
        it.tile.instanceId
      }} at ${if (axis == Axis.Row) "column" else "row"} $at",
    )
    val assigned =
      pieces.map { piece ->
        if (!piece.crosses(axis, at)) return@map piece
        val start = piece.region.start(axis)
        val end = piece.region.end(axis)
        piece.copy(
          region = if (at - start >=
            end - at
          ) {
            piece.region.slice(axis, start, at)
          } else {
            piece.region.slice(axis, at, end)
          },
        )
      }
    val halves =
      listOf(region.slice(axis, region.start(axis), at), region.slice(axis, at, region.end(axis))).map { strip ->
        strip.length(axis) to solve(strip, assigned.filter { it.region in strip })
      }
    return Cut(
      Split(
        axis,
        halves.map { (length, sub) ->
          Child(length.toFloat(), sub.node)
        },
      ),
      halves.sumOf { it.second.leaves },
    )
  }

  /** Grid lines strictly inside [region] that a cut along [axis] could use. */
  private fun interiorLines(region: Region, axis: Axis): List<Int> =
    ((region.start(axis) + 1) until region.end(axis)).toList()
}
