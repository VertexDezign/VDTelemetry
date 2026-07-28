package net.vertexdezign.vdt.app.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A cell whose instance id *is* its widget id — the single-instance-per-page case, which keeps these
 * assertions readable. [instanced] covers the case the two differ.
 */
private fun cell(id: String, col: Int, row: Int, colSpan: Int = 1, rowSpan: Int = 1) =
  LayoutCell(id, id, col, row, colSpan, rowSpan)

/** Two tiles of the same widget type: distinct instance ids, one widget id. */
private fun instanced(instanceId: String, widgetId: String, col: Int, row: Int, colSpan: Int = 1, rowSpan: Int = 1) =
  LayoutCell(instanceId, widgetId, col, row, colSpan, rowSpan)

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
  fun addWidgetPlacesAtTheSizeTheWidgetAsksFor() {
    // The latent bug this replaced: every add landed 1x1, whatever the widget needed.
    assertEquals(
      listOf(cell("a", 1, 0, colSpan = 4, rowSpan = 3)),
      grid(12, 6).addWidget("a", "a", 1, 0, colSpan = 4, rowSpan = 3, minColSpan = 2, minRowSpan = 2).cells,
    )
  }

  @Test
  fun addWidgetShrinksTowardsTheMinimumWhenTheDefaultDoesNotFit() {
    // Only 3 columns left to the edge, so the 4-wide default squeezes to 3 rather than being refused.
    val placed = grid(12, 6).addWidget("a", "a", 9, 0, colSpan = 4, rowSpan = 3, minColSpan = 2, minRowSpan = 2)
    assertEquals(cell("a", 9, 0, colSpan = 3, rowSpan = 3), placed.cells.single())
  }

  @Test
  fun addWidgetShrinksAroundANeighbourNotJustTheGridEdge() {
    val layout = grid(12, 6, cell("b", 4, 0, colSpan = 2, rowSpan = 6))
    val placed = layout.addWidget("a", "a", 0, 0, colSpan = 6, rowSpan = 3, minColSpan = 2, minRowSpan = 2)
    assertEquals(cell("a", 0, 0, colSpan = 4, rowSpan = 3), placed.cells.first { it.widgetId == "a" })
  }

  @Test
  fun addWidgetPrefersTheLargestFittingSizeAndThenTheWiderOne() {
    // A single blocker on the diagonal rules out 3x3 but leaves both 3x2 and 2x3 free. Equal area,
    // so the tie-break decides: the wider one.
    val layout = grid(12, 6, cell("b", 2, 2))
    val placed = layout.addWidget("a", "a", 0, 0, colSpan = 3, rowSpan = 3, minColSpan = 2, minRowSpan = 2)
    assertEquals(cell("a", 0, 0, colSpan = 3, rowSpan = 2), placed.cells.first { it.widgetId == "a" })
  }

  @Test
  fun addWidgetRefusedWhenEvenTheMinimumDoesNotFit() {
    val layout = grid(12, 6, cell("b", 2, 0, colSpan = 10, rowSpan = 6))
    // Two free columns, but this widget needs three.
    assertSame(layout, layout.addWidget("a", "a", 0, 0, colSpan = 4, rowSpan = 3, minColSpan = 3, minRowSpan = 2))
  }

  @Test
  fun addWidgetRefusesAnOccupiedSlot() {
    val layout = grid(3, 2, cell("a", 1, 0))
    assertSame(layout, layout.addWidget("b", "b", 1, 0))
  }

  @Test
  fun addWidgetRefusesOutOfBounds() {
    val layout = grid(3, 2)
    assertSame(layout, layout.addWidget("a", "a", 3, 0)) // col 3 is outside 0..2
  }

  @Test
  fun addWidgetSurvivesAWidgetDeclaringAMinimumAboveItsDefault() {
    // An authoring slip shouldn't make the widget unplaceable — the default wins.
    val placed = grid(12, 6).addWidget("a", "a", 0, 0, colSpan = 2, rowSpan = 2, minColSpan = 5, minRowSpan = 5)
    assertEquals(cell("a", 0, 0, colSpan = 2, rowSpan = 2), placed.cells.single())
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
  fun resizeStopsAtTheWidgetsOwnFloorNotAtOneCell() {
    val a = cell("a", 0, 0, colSpan = 4, rowSpan = 3)
    val layout = grid(12, 6, a)
    val shrunk = layout.resize(a, 1, 1, minColSpan = 3, minRowSpan = 2).cells.single()
    assertEquals(cell("a", 0, 0, colSpan = 3, rowSpan = 2), shrunk)
  }

  @Test
  fun resizeAtTheFloorReportsNoChangeSoTheControlCanGreyOut() {
    // The edit overlay offers a direction only when resize() returns a different layout.
    val a = cell("a", 0, 0, colSpan = 3, rowSpan = 2)
    val layout = grid(12, 6, a)
    assertEquals(layout, layout.resize(a, 2, 2, minColSpan = 3, minRowSpan = 2))
  }

  @Test
  fun resizeRefusedWhenTheFloorNoLongerFitsBetweenTheOriginAndTheEdge() {
    val a = cell("a", 10, 0) // only 2 columns left to the edge
    val layout = grid(12, 6, a)
    assertSame(layout, layout.resize(a, 3, 1, minColSpan = 3, minRowSpan = 1))
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

/**
 * Placed tiles are identified by instance, not by widget type — what lets a page hold two of the
 * same widget, each with its own settings.
 */
class WidgetInstanceTest {
  @Test
  fun twoInstancesOfOneWidgetCoexist() {
    val layout = grid(12, 6, instanced("m1", "map", 0, 0, colSpan = 4, rowSpan = 4))
    val next = layout.addWidget("m2", "map", 4, 0, colSpan = 4, rowSpan = 4)
    assertEquals(listOf("m1", "m2"), next.cells.map { it.instanceId })
    assertEquals(listOf("map", "map"), next.cells.map { it.widgetId })
  }

  @Test
  fun resizingOneInstanceLeavesItsTwinAlone() {
    val a = instanced("m1", "map", 0, 0, colSpan = 4, rowSpan = 4)
    val b = instanced("m2", "map", 4, 0, colSpan = 4, rowSpan = 4)
    val resized = grid(12, 6, a, b).resize(a, colSpan = 3, rowSpan = 4)
    assertEquals(3, resized.cells.first { it.instanceId == "m1" }.colSpan)
    assertEquals(b, resized.cells.first { it.instanceId == "m2" }) // untouched, spans and all
  }

  @Test
  fun removingOneInstanceLeavesItsTwinPlaced() {
    val layout = grid(12, 6, instanced("m1", "map", 0, 0), instanced("m2", "map", 4, 0))
    assertEquals(listOf("m2"), layout.removeAt(0, 0).cells.map { it.instanceId })
  }

  @Test
  fun sameTypeInstancesSwapWithEachOther() {
    val layout = grid(12, 6, instanced("m1", "map", 0, 0), instanced("m2", "map", 4, 0))
    val swapped = layout.moveOrSwap(GridPos(0, 0), GridPos(4, 0))
    assertEquals(4, swapped.cells.first { it.instanceId == "m1" }.col)
    assertEquals(0, swapped.cells.first { it.instanceId == "m2" }.col)
  }

  @Test
  fun addWidgetCarriesTheConfigItWasPlacedWith() {
    val placed = grid(12, 6).addWidget("s1", "shortcut", 0, 0, config = mapOf("app" to "storage"))
    assertEquals(mapOf("app" to "storage"), placed.cells.single().config)
  }

  @Test
  fun reconfigureTouchesOnlyTheAddressedInstanceAndNotItsPlacement() {
    val layout =
      grid(
        12,
        6,
        instanced("s1", "shortcut", 0, 0),
        instanced("s2", "shortcut", 1, 0),
      )
    val next = layout.reconfigure("s1", mapOf("app" to "animals"))
    val first = next.cells.first { it.instanceId == "s1" }
    assertEquals(mapOf("app" to "animals"), first.config)
    assertEquals(0, first.col) // reconfiguring must not move the tile — instance state is keyed on it
    assertEquals(1, first.colSpan)
    assertEquals(emptyMap(), next.cells.first { it.instanceId == "s2" }.config)
  }

  @Test
  fun reconfigureIsANoopForAnInstanceThatIsGone() {
    // The dialog is modal, but the page behind it can still change — a stale id must not add a cell.
    val layout = grid(12, 6, instanced("s1", "shortcut", 0, 0))
    assertSame(layout, layout.reconfigure("deleted", mapOf("app" to "map")))
  }

  @Test
  fun rescalingKeepsInstanceIdentityAndConfig() {
    val old = GridLayout(3, 2, listOf(LayoutCell("s1", "shortcut", 0, 0, 1, 1, mapOf("app" to "storage"))))
    val cell = old.rescaledTo(12, 6).cells.single()
    assertEquals("s1", cell.instanceId)
    assertEquals(mapOf("app" to "storage"), cell.config)
  }
}

/**
 * [GridLayout.rescaledTo] carries a layout saved under an older grid onto the current one — the
 * migration that let the per-page grid stepper go away.
 */
class GridRescaleTest {
  @Test
  fun aWholeMultipleScalesExactly() {
    // The clean case: 3x2 -> 12x6 is x4 across and x3 down, so no edge rounds. (The live grid is
    // 12x7, where rows don't divide — anUnevenRatioRoundsAndStillTilesWithoutGaps covers that.)
    val old = grid(3, 2, cell("map", 0, 0, colSpan = 2, rowSpan = 2), cell("tasks", 2, 0), cell("crops", 2, 1))
    val next = old.rescaledTo(12, 6)
    assertEquals(12, next.columns)
    assertEquals(6, next.rows)
    assertEquals(
      listOf(
        cell("map", 0, 0, colSpan = 8, rowSpan = 6),
        cell("tasks", 8, 0, colSpan = 4, rowSpan = 3),
        cell("crops", 8, 3, colSpan = 4, rowSpan = 3),
      ),
      next.cells,
    )
  }

  @Test
  fun anUnevenRatioRoundsAndStillTilesWithoutGaps() {
    // 5 columns into 12 doesn't divide: edges round, but adjacent cells stay adjacent because both
    // sides of a shared edge round the same way.
    val old = grid(5, 2, cell("a", 0, 0, colSpan = 2, rowSpan = 1), cell("b", 2, 0, colSpan = 3, rowSpan = 1))
    val next = old.rescaledTo(12, 6)
    val a = next.cells.first { it.widgetId == "a" }
    val b = next.cells.first { it.widgetId == "b" }
    assertEquals(a.col + a.colSpan, b.col) // no gap and no overlap at the seam
    assertEquals(12, b.col + b.colSpan) // and the pair still spans the full width
  }

  @Test
  fun everyRescaledCellStaysInBoundsAndClearOfTheOthers() {
    val old = grid(5, 3, cell("a", 0, 0, colSpan = 3, rowSpan = 2), cell("b", 3, 0, colSpan = 2, rowSpan = 3))
    val next = old.rescaledTo(12, 6)
    for (c in next.cells) {
      assertTrue(c.col >= 0 && c.row >= 0)
      assertTrue(c.col + c.colSpan <= next.columns && c.row + c.rowSpan <= next.rows)
      assertTrue(c.colSpan >= 1 && c.rowSpan >= 1)
    }
    assertFalse(next.cells[0].overlaps(next.cells[1]))
  }

  @Test
  fun aLayoutAlreadyOnTheTargetGridIsUntouched() {
    val layout = grid(12, 6, cell("a", 0, 0, colSpan = 4, rowSpan = 3))
    assertSame(layout, layout.rescaledTo(12, 6))
  }
}
