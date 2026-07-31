package net.vertexdezign.vdt.app.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The aspect breakpoint, and that each preset actually delivers the roughly-square cell it was picked
 * for on the device it was picked for. The cell size is the whole argument for these constants, so it
 * is the thing worth pinning down.
 */
class GridAspectTest {
  /** What [WidgetGrid] lays out: n cells and n-1 gaps inside a body already inset by the padding. */
  private fun cell(body: Double, count: Int) = (body - (count - 1) * CELL_GAP.value) / count

  @Test
  fun theBreakpointIsTheBodysAspect() {
    // An 11" tablet held either way, and a phone standing up.
    assertEquals(GridAspect.Landscape, GridAspect.of(1194.dp, 696.dp))
    assertEquals(GridAspect.Portrait, GridAspect.of(834.dp, 1074.dp))
    assertEquals(GridAspect.Portrait, GridAspect.of(393.dp, 777.dp))
    // A phone held sideways stays on the landscape grid: 12x7 gives it ~70x56dp, near enough square.
    assertEquals(GridAspect.Landscape, GridAspect.of(844.dp, 390.dp))
    // Dead square falls to landscape, which is the grid every page already has an arrangement on.
    assertEquals(GridAspect.Landscape, GridAspect.of(700.dp, 700.dp))
  }

  @Test
  fun theLandscapeGridIsSquareOnAnElevenInchTablet() {
    // 1194x696dp viewport, less the grid's own 8dp padding on each edge.
    val width = cell(1194.0 - 2 * GRID_PADDING.value, GridAspect.Landscape.columns)
    val height = cell(696.0 - 2 * GRID_PADDING.value, GridAspect.Landscape.rows)
    assertTrue(width in 88.0..93.0, "landscape cell width was $width")
    assertTrue(height in 88.0..93.0, "landscape cell height was $height")
  }

  @Test
  fun thePortraitGridIsSquareOnAPhone() {
    // iPhone 15 Pro standing up with no browser or shell chrome: 393x793dp (852 screen, less the
    // 59dp status bar). That body is what 6x12 was chosen against — see GridLayout.PORTRAIT_COLUMNS.
    val width = cell(393.0 - 2 * GRID_PADDING.value, GridAspect.Portrait.columns)
    val height = cell(793.0 - 2 * GRID_PADDING.value, GridAspect.Portrait.rows)
    assertTrue(width in 54.0..59.0, "portrait cell width was $width")
    assertTrue(height in 54.0..59.0, "portrait cell height was $height")
    // A 1x1 shortcut still has to be hittable with a thumb.
    assertTrue(width >= 44.0 && height >= 44.0, "a 1x1 tile is ${width}x$height, under a 44dp target")
  }

  @Test
  fun theTwoGridsShareAColumnRatio() {
    // 12 -> 6 is exactly 2:1, so rescaledTo carries a landscape arrangement into portrait without
    // rounding any column edge. Losing this would not break anything, but it would make every seeded
    // and derived portrait page slightly wrong in a way nobody would think to look for.
    assertEquals(0, GridAspect.Landscape.columns % GridAspect.Portrait.columns)
  }
}
