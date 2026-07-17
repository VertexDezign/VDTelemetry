package net.vertexdezign.vdt.app.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun cell(id: String, col: Int, row: Int, colSpan: Int = 1, rowSpan: Int = 1) =
  LayoutCell(id, col, row, colSpan, rowSpan)

private fun grid(columns: Int, rows: Int, vararg cells: LayoutCell) = GridLayout(columns, rows, cells.toList())

/** The cell-geometry primitives the layout algebra is built on. */
class LayoutCellTest {
  @Test
  fun coversIsTopLeftInclusiveBottomRightExclusive() {
    val c = cell("a", col = 1, row = 1, colSpan = 2, rowSpan = 2) // occupies cols 1..2, rows 1..2
    assertTrue(c.covers(1, 1))
    assertTrue(c.covers(2, 2))
    assertFalse(c.covers(0, 1)) // just left
    assertFalse(c.covers(3, 1)) // right edge is exclusive (col + colSpan)
    assertFalse(c.covers(1, 3)) // bottom edge is exclusive (row + rowSpan)
  }

  @Test
  fun overlapsIsTrueOnlyForSharedCells() {
    val a = cell("a", 0, 0, colSpan = 2, rowSpan = 2) // cols 0..1, rows 0..1
    assertTrue(a.overlaps(cell("b", 1, 1))) // shares (1,1)
    assertFalse(a.overlaps(cell("b", 2, 0))) // immediately to the right
    assertFalse(a.overlaps(cell("b", 0, 2))) // immediately below
    assertTrue(a.overlaps(a)) // a cell overlaps itself
  }
}

/** The pure, self-validating layout mutators — every one returns a new layout or the same on a no-op. */
class GridLayoutTest {
  @Test
  fun cellCoveringFindsTheSpanningWidgetAndNullOnAFreeSlot() {
    val layout = grid(3, 2, cell("map", 0, 0, colSpan = 2, rowSpan = 2))
    assertEquals("map", layout.cellCovering(1, 1)?.widgetId) // interior of the span
    assertNull(layout.cellCovering(2, 0)) // free slot
  }

  @Test
  fun freePositionsAreRowMajorAndExcludeCoveredCells() {
    val layout = grid(3, 2, cell("map", 0, 0, colSpan = 2, rowSpan = 2)) // covers the left 2x2 block
    assertEquals(listOf(GridPos(2, 0), GridPos(2, 1)), layout.freePositions())
  }

  @Test
  fun withGridSizeClampsToTheAllowedRange() {
    val layout = grid(3, 2)
    assertEquals(GridLayout.MIN_SIDE, layout.withGridSize(0, 0).columns)
    assertEquals(GridLayout.MAX_SIDE, layout.withGridSize(99, 99).rows)
  }

  @Test
  fun withGridSizeGrowingKeepsEveryCell() {
    val layout = grid(3, 2, cell("a", 2, 1))
    assertEquals(layout.cells, layout.withGridSize(4, 4).cells)
  }

  @Test
  fun withGridSizeShrinkingDropsOnlyOutOfBoundsCells() {
    val layout = grid(3, 3, cell("keep", 0, 0), cell("drop", 2, 2))
    assertEquals(listOf(cell("keep", 0, 0)), layout.withGridSize(2, 2).cells)
  }

  @Test
  fun addWidgetPlacesAOneByOneAtAFreeSlot() {
    assertEquals(listOf(cell("a", 1, 0)), grid(3, 2).addWidget("a", 1, 0).cells)
  }

  @Test
  fun addWidgetRefusesAnOccupiedSlot() {
    val layout = grid(3, 2, cell("a", 1, 0))
    assertSame(layout, layout.addWidget("b", 1, 0))
  }

  @Test
  fun addWidgetRefusesOutOfBounds() {
    val layout = grid(3, 2)
    assertSame(layout, layout.addWidget("a", 3, 0)) // col 3 is outside 0..2
  }

  @Test
  fun removeAtDropsTheCoveringWidgetFromAnyOfItsCells() {
    val layout = grid(3, 2, cell("map", 0, 0, colSpan = 2, rowSpan = 2))
    assertTrue(layout.removeAt(1, 1).cells.isEmpty()) // removing from the interior still drops the span
  }

  @Test
  fun removeAtIsANoopOnAFreeSlot() {
    val layout = grid(3, 2, cell("a", 0, 0))
    assertSame(layout, layout.removeAt(2, 1))
  }

  @Test
  fun resizeGrowsWithinBounds() {
    val c = cell("a", 0, 0)
    assertEquals(cell("a", 0, 0, colSpan = 2, rowSpan = 1), grid(3, 2, c).resize(c, 2, 1).cells.single())
  }

  @Test
  fun resizeClampsTheSpanToTheGridEdge() {
    val c = cell("a", 1, 0)
    assertEquals(2, grid(3, 2, c).resize(c, 5, 1).cells.single().colSpan) // columns - col = 2
  }

  @Test
  fun resizeClampsTheSpanToAtLeastOne() {
    val c = cell("a", 0, 0)
    assertEquals(1, grid(3, 2, c).resize(c, 0, 0).cells.single().colSpan)
  }

  @Test
  fun resizeRefusedWhenItWouldCoverANeighbour() {
    val a = cell("a", 0, 0)
    val layout = grid(3, 2, a, cell("b", 1, 0))
    assertSame(layout, layout.resize(a, 2, 1)) // widening a would collide with b
  }

  @Test
  fun moveToFreeSlotRelocatesTheWidget() {
    val layout = grid(3, 2, cell("a", 0, 0))
    assertEquals(listOf(cell("a", 2, 1)), layout.moveOrSwap(GridPos(0, 0), GridPos(2, 1)).cells)
  }

  @Test
  fun moveIsANoopWhenTheSourceIsNotAWidgetOrigin() {
    val layout = grid(3, 2, cell("map", 0, 0, colSpan = 2, rowSpan = 2))
    // (1,1) is covered by map but its origin is (0,0), so there's nothing to pick up here.
    assertSame(layout, layout.moveOrSwap(GridPos(1, 1), GridPos(2, 0)))
  }

  @Test
  fun moveOntoTheWidgetsOwnCellIsANoop() {
    val layout = grid(3, 2, cell("map", 0, 0, colSpan = 2, rowSpan = 2))
    assertSame(layout, layout.moveOrSwap(GridPos(0, 0), GridPos(1, 1)))
  }

  @Test
  fun moveRefusedWhenTheWidgetWouldNotFitAtTheTarget() {
    val a = cell("a", 0, 0, colSpan = 2, rowSpan = 1) // 2 wide
    val layout = grid(3, 2, a)
    // Origin at col 2 would need cols 2..3 — off the 3-wide grid.
    assertSame(layout, layout.moveOrSwap(GridPos(0, 0), GridPos(2, 0)))
  }

  @Test
  fun dragOntoAnotherWidgetSwapsThem() {
    val layout = grid(3, 2, cell("a", 0, 0), cell("b", 2, 0))
    val swapped = layout.moveOrSwap(GridPos(0, 0), GridPos(2, 0))
    assertEquals(setOf(cell("a", 2, 0), cell("b", 0, 0)), swapped.cells.toSet())
  }

  @Test
  fun swapRefusedWhenTheWidgetsWouldNotFitAtEachOthersOrigins() {
    val a = cell("a", 0, 0, colSpan = 2, rowSpan = 1) // wide widget on the left
    val b = cell("b", 2, 0) // 1x1 on the right edge
    val layout = grid(3, 2, a, b)
    // Swapping would put the 2-wide 'a' at col 2, overflowing the grid.
    assertSame(layout, layout.moveOrSwap(GridPos(0, 0), GridPos(2, 0)))
  }
}
