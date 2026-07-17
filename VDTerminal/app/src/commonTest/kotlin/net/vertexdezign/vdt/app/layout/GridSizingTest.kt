package net.vertexdezign.vdt.app.layout

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [maxGridSide] derives how many columns/rows keep every cell at least `minCellPx` wide/tall in the
 * measured viewport, so the grid steppers can't shrink cells below readability. These pin the formula
 * and its edge cases (unmeasured viewport, the exact fit boundary, and both clamps).
 */
class GridSizingTest {
  // A cell floor of 200px with an 8px gap, used across the fit-boundary cases below.
  private val minCell = 200f
  private val gap = 8f

  @Test
  fun unmeasuredViewportAllowsTheHardCeiling() {
    // Before onSizeChanged fires the body size is 0 (or momentarily negative after inset): stay usable.
    assertEquals(GridLayout.MAX_SIDE, maxGridSide(availablePx = 0f, minCellPx = minCell, gapPx = gap))
    assertEquals(GridLayout.MAX_SIDE, maxGridSide(availablePx = -50f, minCellPx = minCell, gapPx = gap))
  }

  @Test
  fun fitsExactlyAtTheBoundaryAndNotOnePixelLess() {
    // Three cells need 3*200 + 2*8 = 616px. At the exact width three fit; one pixel short drops to two.
    assertEquals(3, maxGridSide(availablePx = 616f, minCellPx = minCell, gapPx = gap))
    assertEquals(2, maxGridSide(availablePx = 615f, minCellPx = minCell, gapPx = gap))
  }

  @Test
  fun clampsToMinSideWhenSpaceIsTiny() {
    // Not even one cell fits, but a page always has at least one column/row.
    assertEquals(GridLayout.MIN_SIDE, maxGridSide(availablePx = 50f, minCellPx = minCell, gapPx = gap))
  }

  @Test
  fun clampsToMaxSideWhenSpaceIsAmple() {
    // Room for far more than MAX_SIDE cells is still capped at the hard ceiling.
    assertEquals(GridLayout.MAX_SIDE, maxGridSide(availablePx = 100_000f, minCellPx = minCell, gapPx = gap))
  }

  @Test
  fun accountsForTheInterCellGap() {
    // With six cells the five 8px gaps matter: 6*200 + 5*8 = 1240 fits six, 1239 only fits five.
    assertEquals(6, maxGridSide(availablePx = 1240f, minCellPx = minCell, gapPx = gap))
    assertEquals(5, maxGridSide(availablePx = 1239f, minCellPx = minCell, gapPx = gap))
  }
}
