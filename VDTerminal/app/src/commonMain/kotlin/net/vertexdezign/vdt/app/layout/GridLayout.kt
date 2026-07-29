package net.vertexdezign.vdt.app.layout

import kotlinx.serialization.Serializable
import net.vertexdezign.vdt.app.layout.GridLayout.Companion.COLUMNS
import net.vertexdezign.vdt.app.layout.GridLayout.Companion.ROWS
import net.vertexdezign.vdt.app.widgets.WidgetConfig
import kotlin.math.roundToInt
import kotlin.random.Random

/** A grid position (column/row), used for free-slot enumeration and drag hit-testing. */
data class GridPos(val col: Int, val row: Int)

/**
 * One placed widget: the widget *type* [widgetId] (resolved against `WidgetRegistry`) rendered at
 * top-left [col]/[row], spanning [colSpan]×[rowSpan] cells, configured by [config].
 *
 * [instanceId] — not [widgetId] — is this tile's identity. Type and instance used to be the same
 * thing, which capped a page at one tile per widget: two maps couldn't coexist, the picker had to
 * hide anything already placed, and per-widget state had nowhere instance-specific to live. A
 * generated id per placement lifts all three at once, and gives instance-scoped state a stable key
 * that survives the tile being moved or resized.
 */
@Serializable
data class LayoutCell(
  val instanceId: String,
  val widgetId: String,
  val col: Int,
  val row: Int,
  val colSpan: Int = 1,
  val rowSpan: Int = 1,
  val config: WidgetConfig = emptyMap(),
) {
  fun covers(c: Int, r: Int): Boolean = c in col until col + colSpan && r in row until row + rowSpan

  fun overlaps(other: LayoutCell): Boolean = col < other.col + other.colSpan &&
    other.col < col + colSpan &&
    row < other.row + other.rowSpan &&
    other.row < row + rowSpan
}

/**
 * A fresh instance id. Random rather than derived from the widget or its position: the id has to
 * outlive both, since a tile keeps its instance-scoped state when it is moved, resized or
 * reconfigured.
 */
fun newInstanceId(): String = "w-" + Random.nextLong(0, Long.MAX_VALUE).toString(36)

/**
 * A screen's widget arrangement as data: a [columns] × [rows] grid holding placed [cells]. Any grid
 * position not covered by a cell is a free slot (an "add" target in edit mode). Serializable so a
 * user's customized layout can be persisted (see [LayoutStore]); each app ships a default instance.
 *
 * Every live layout is on one of the two frozen presets — [COLUMNS] × [ROWS] landscape,
 * [PORTRAIT_COLUMNS] × [PORTRAIT_ROWS] portrait — never a per-page setting. Which one a layout is on
 * is [GridAspect]'s business; a [net.vertexdezign.vdt.app.pages.Page] carries one arrangement per
 * aspect. The dimensions are still carried here rather than assumed, because a layout is only
 * meaningful in the coordinate space it was written in: that's what lets [rescaledTo] re-express a
 * layout saved under an older grid, or seed one aspect's arrangement from the other's, instead of
 * throwing it away.
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

  /**
   * True if [cell] fits: in bounds and clear of every existing cell except the instances in
   * [ignoring] — the tiles being moved or resized by the caller, which must not block themselves.
   */
  private fun fits(cell: LayoutCell, ignoring: Set<String>): Boolean =
    inBounds(cell) && cells.none { it.instanceId !in ignoring && it.overlaps(cell) }

  /**
   * Re-expresses this layout in a [columns] × [rows] grid by scaling every cell's edges by the ratio
   * between the two grids. Used to carry a layout saved under an older grid forward to the current
   * one (see `PageStore`): exact when the new grid is a whole multiple of the old (the old 3-column
   * pages divide into 12 cleanly), and rounding each edge to the nearest grid line when it isn't
   * (7 rows divides nothing, so row edges land where they land — a widget keeps its proportions and
   * its neighbours, not its exact height).
   *
   * Rounding both edges through the same function is monotone, so cells that didn't overlap before
   * don't start overlapping now; the guard against that only bites if the grid ever shrinks far
   * enough to collapse two neighbours onto the same line, where the later cell is dropped.
   */
  fun rescaledTo(columns: Int, rows: Int): GridLayout {
    if (columns == this.columns && rows == this.rows) return this
    val scaleX = columns.toDouble() / this.columns
    val scaleY = rows.toDouble() / this.rows
    val scaled = mutableListOf<LayoutCell>()
    for (cell in cells) {
      val left = (cell.col * scaleX).roundToInt().coerceIn(0, columns - 1)
      val top = (cell.row * scaleY).roundToInt().coerceIn(0, rows - 1)
      val right = ((cell.col + cell.colSpan) * scaleX).roundToInt().coerceIn(left + 1, columns)
      val bottom = ((cell.row + cell.rowSpan) * scaleY).roundToInt().coerceIn(top + 1, rows)
      val next = cell.copy(col = left, row = top, colSpan = right - left, rowSpan = bottom - top)
      if (scaled.none { it.overlaps(next) }) scaled += next
    }
    return GridLayout(columns, rows, scaled)
  }

  /**
   * Places [widgetId] with its origin at [col]/[row], sized [colSpan] × [rowSpan] — the size the
   * widget asks for. When that doesn't fit (the grid edge, or a neighbour) it is squeezed down
   * towards [minColSpan] × [minRowSpan] and the largest fitting size is used; if even the minimum
   * doesn't fit, the add is refused. Widgets declare all four (see
   * [net.vertexdezign.vdt.app.widgets.Widget]), so a tile arrives at a usable size instead of the
   * 1×1 sliver a fine grid would otherwise give it.
   */
  fun addWidget(
    instanceId: String,
    widgetId: String,
    col: Int,
    row: Int,
    colSpan: Int = 1,
    rowSpan: Int = 1,
    minColSpan: Int = 1,
    minRowSpan: Int = 1,
    config: WidgetConfig = emptyMap(),
  ): GridLayout {
    val wantCols = colSpan.coerceAtLeast(1)
    val wantRows = rowSpan.coerceAtLeast(1)
    // A widget declaring a minimum above its default is an authoring slip, not a reason to refuse it.
    val leastCols = minColSpan.coerceIn(1, wantCols)
    val leastRows = minRowSpan.coerceIn(1, wantRows)
    val placed =
      (wantCols downTo leastCols)
        .flatMap { cs ->
          (wantRows downTo leastRows).map { rs -> LayoutCell(instanceId, widgetId, col, row, cs, rs, config) }
        }
        .filter { fits(it, ignoring = emptySet()) }
        // Biggest area wins; between equal areas prefer the wider one — tiles read better landscape.
        .maxWithOrNull(compareBy({ it.colSpan * it.rowSpan }, { it.colSpan }))
        ?: return this
    return copy(cells = cells + placed)
  }

  /**
   * Replaces the settings of the instance [instanceId]; no-op if that tile is gone. Placement is
   * untouched — reconfiguring a tile never moves or resizes it, so instance-scoped state keyed on the
   * id stays attached to it.
   */
  fun reconfigure(instanceId: String, config: WidgetConfig): GridLayout {
    if (cells.none { it.instanceId == instanceId }) return this
    return copy(cells = cells.map { if (it.instanceId == instanceId) it.copy(config = config) else it })
  }

  fun removeAt(col: Int, row: Int): GridLayout {
    val cell = cellCovering(col, row) ?: return this
    return copy(cells = cells.filterNot { it.instanceId == cell.instanceId })
  }

  /**
   * Resize [cell] to the given spans, clamped to the grid edge and to the widget's own
   * [minColSpan] × [minRowSpan] floor; refused if it would cover another widget. The floor is what
   * stops a tile being shrunk below the size at which it can still be read.
   */
  fun resize(cell: LayoutCell, colSpan: Int, rowSpan: Int, minColSpan: Int = 1, minRowSpan: Int = 1): GridLayout {
    val roomCols = columns - cell.col
    val roomRows = rows - cell.row
    val leastCols = minColSpan.coerceAtLeast(1)
    val leastRows = minRowSpan.coerceAtLeast(1)
    // Nothing to offer when the widget's floor is already wider/taller than the room left to the edge.
    if (leastCols > roomCols || leastRows > roomRows) return this
    val resized =
      cell.copy(
        colSpan = colSpan.coerceIn(leastCols, roomCols),
        rowSpan = rowSpan.coerceIn(leastRows, roomRows),
      )
    if (!fits(resized, ignoring = setOf(cell.instanceId))) return this
    return copy(cells = cells.map { if (it.instanceId == cell.instanceId) resized else it })
  }

  /**
   * Drag the widget whose origin is [from] onto [to]: move it to a free slot if it fits there, else
   * swap it with the widget occupying [to] (both must fit at the swapped origins). No-op otherwise.
   */
  fun moveOrSwap(from: GridPos, to: GridPos): GridLayout {
    val moving = cellOrigin(from.col, from.row) ?: return this
    val target = cellCovering(to.col, to.row)
    if (target?.instanceId == moving.instanceId) return this
    if (target == null) {
      val moved = moving.copy(col = to.col, row = to.row)
      if (!fits(moved, ignoring = setOf(moving.instanceId))) return this
      return copy(cells = cells.map { if (it.instanceId == moving.instanceId) moved else it })
    }
    val movedMoving = moving.copy(col = target.col, row = target.row)
    val movedTarget = target.copy(col = moving.col, row = moving.row)
    val ignore = setOf(moving.instanceId, target.instanceId)
    if (!fits(movedMoving, ignore) || !fits(movedTarget, ignore) || movedMoving.overlaps(movedTarget)) return this
    return copy(
      cells =
      cells.map {
        when (it.instanceId) {
          moving.instanceId -> movedMoving
          target.instanceId -> movedTarget
          else -> it
        }
      },
    )
  }

  companion object {
    /**
     * The landscape grid, frozen rather than measured.
     *
     * The readability floor used to sit on the *cell* — the grid was user-resizable, so a per-widget
     * span floor couldn't have bounded the pixels, and cells had to stay big enough for the largest
     * tile. That put a ~200×140dp floor under everything and made single-stat tiles and app
     * shortcuts impossible. Now each widget carries its own floor, so the cell can be small, and a
     * widget simply says how many cells it needs.
     *
     * 12×7 is tuned to an 11" tablet held landscape: a ~1194×696dp body works out at roughly
     * 91×90dp per cell, i.e. **square**, which is why a 1×1 tile reads as a proper icon rather than
     * a stripe.
     */
    const val COLUMNS = 12
    const val ROWS = 7

    /**
     * The portrait grid.
     *
     * This reverses a decision that was made deliberately. Portrait used to be the *same* 12×7, with
     * cells simply going tall and narrow, on the reasoning that widgets which care should centre
     * their content rather than the app carry a layout per orientation. That trade holds for a tablet
     * turned sideways. It does not survive a phone: on 393dp of width, 12 columns are 32dp each, so
     * every span a widget declares — and `ShortcutWidget`'s square-tile logic with them — is being
     * measured against a 4:1 stripe. A phone did not get a cramped version of the page, it got a
     * wrong one.
     *
     * 6×12 is tuned the same way 12×7 was, on the body a phone actually gives once the shell's chrome
     * is gone (see [net.vertexdezign.vdt.app.DisplayShell]). An iPhone 15 Pro standing up is a
     * 393×793dp viewport, so a 377×777dp body after the grid's own padding, which works out at
     * 56×57dp per cell — square again, and a 1×1 shortcut lands at 56dp, comfortably over a thumb's
     * 44dp.
     *
     * Six columns rather than five or seven because it is exactly half of [COLUMNS]: [rescaledTo]
     * from landscape is then a clean 2:1 on the X axis, so a portrait arrangement seeded from an
     * existing page keeps its columns exactly instead of rounding into them.
     */
    const val PORTRAIT_COLUMNS = 6
    const val PORTRAIT_ROWS = 12

    /** A blank layout on [aspect]'s grid — what a newly created page starts from. */
    fun empty(aspect: GridAspect = GridAspect.Landscape): GridLayout =
      GridLayout(aspect.columns, aspect.rows, emptyList())
  }
}
