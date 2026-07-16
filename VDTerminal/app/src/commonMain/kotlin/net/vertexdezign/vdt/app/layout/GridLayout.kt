package net.vertexdezign.vdt.app.layout

import kotlinx.serialization.Serializable

/** A grid position (column/row), used for free-slot enumeration and drag hit-testing. */
data class GridPos(val col: Int, val row: Int)

/**
 * One placed widget: the [widgetId] to render at top-left [col]/[row], spanning [colSpan]×[rowSpan]
 * cells. Ids are resolved against `WidgetRegistry`.
 */
@Serializable
data class LayoutCell(val widgetId: String, val col: Int, val row: Int, val colSpan: Int = 1, val rowSpan: Int = 1) {
  fun covers(c: Int, r: Int): Boolean = c in col until col + colSpan && r in row until row + rowSpan

  fun overlaps(other: LayoutCell): Boolean = col < other.col + other.colSpan &&
    other.col < col + colSpan &&
    row < other.row + other.rowSpan &&
    other.row < row + rowSpan
}

/**
 * A screen's widget arrangement as data: a [columns] × [rows] grid holding placed [cells]. Any grid
 * position not covered by a cell is a free slot (an "add" target in edit mode). Serializable so a
 * user's customized layout can be persisted (see [LayoutStore]); each app ships a default instance.
 *
 * The mutation helpers are pure (return a new layout) and self-validating: they refuse moves/resizes
 * that would leave the grid or overlap another widget, so callers can apply drag/resize gestures
 * optimistically and a blocked gesture is simply a no-op.
 */
@Serializable
data class GridLayout(val columns: Int, val rows: Int, val cells: List<LayoutCell>) {

  fun cellCovering(c: Int, r: Int): LayoutCell? = cells.firstOrNull { it.covers(c, r) }

  private fun cellOrigin(c: Int, r: Int): LayoutCell? = cells.firstOrNull { it.col == c && it.row == r }

  /** Grid positions not covered by any widget, in row-major order. */
  fun freePositions(): List<GridPos> = buildList {
    for (r in 0 until rows) {
      for (c in 0 until columns) {
        if (cellCovering(c, r) == null) add(GridPos(c, r))
      }
    }
  }

  private fun inBounds(cell: LayoutCell): Boolean =
    cell.col >= 0 && cell.row >= 0 && cell.col + cell.colSpan <= columns && cell.row + cell.rowSpan <= rows

  /** True if [cell] fits: in bounds and clear of every existing cell except those in [ignoring]. */
  private fun fits(cell: LayoutCell, ignoring: Set<LayoutCell>): Boolean =
    inBounds(cell) && cells.none { it !in ignoring && it.overlaps(cell) }

  /**
   * Resizes the grid itself, clamped to [MIN_SIDE]..[MAX_SIDE]. Widgets that would fall outside the
   * new bounds are dropped — shrinking is destructive, so callers should surface that.
   */
  fun withGridSize(columns: Int, rows: Int): GridLayout {
    val c = columns.coerceIn(MIN_SIDE, MAX_SIDE)
    val r = rows.coerceIn(MIN_SIDE, MAX_SIDE)
    return copy(
      columns = c,
      rows = r,
      cells = cells.filter { it.col + it.colSpan <= c && it.row + it.rowSpan <= r },
    )
  }

  fun addWidget(widgetId: String, col: Int, row: Int): GridLayout {
    val cell = LayoutCell(widgetId, col, row)
    if (!fits(cell, ignoring = emptySet())) return this
    return copy(cells = cells + cell)
  }

  fun removeAt(col: Int, row: Int): GridLayout {
    val cell = cellCovering(col, row) ?: return this
    return copy(cells = cells - cell)
  }

  /** Resize [cell] to the given spans, clamped to the grid; refused if it would cover another widget. */
  fun resize(cell: LayoutCell, colSpan: Int, rowSpan: Int): GridLayout {
    val resized =
      cell.copy(
        colSpan = colSpan.coerceIn(1, columns - cell.col),
        rowSpan = rowSpan.coerceIn(1, rows - cell.row),
      )
    if (!fits(resized, ignoring = setOf(cell))) return this
    return copy(cells = cells.map { if (it == cell) resized else it })
  }

  /**
   * Drag the widget whose origin is [from] onto [to]: move it to a free slot if it fits there, else
   * swap it with the widget occupying [to] (both must fit at the swapped origins). No-op otherwise.
   */
  fun moveOrSwap(from: GridPos, to: GridPos): GridLayout {
    val moving = cellOrigin(from.col, from.row) ?: return this
    val target = cellCovering(to.col, to.row)
    if (target == moving) return this
    if (target == null) {
      val moved = moving.copy(col = to.col, row = to.row)
      return if (fits(moved, ignoring = setOf(moving))) {
        copy(
          cells = cells.map {
            if (it ==
              moving
            ) {
              moved
            } else {
              it
            }
          },
        )
      } else {
        this
      }
    }
    val movedMoving = moving.copy(col = target.col, row = target.row)
    val movedTarget = target.copy(col = moving.col, row = moving.row)
    val ignore = setOf(moving, target)
    if (!fits(movedMoving, ignore) || !fits(movedTarget, ignore) || movedMoving.overlaps(movedTarget)) return this
    return copy(
      cells =
      cells.map {
        when (it) {
          moving -> movedMoving
          target -> movedTarget
          else -> it
        }
      },
    )
  }

  companion object {
    const val MIN_SIDE = 1
    const val MAX_SIDE = 6
  }
}
