package net.vertexdezign.vdt.app.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** The aspect breakpoint: which of a page's two arrangements a body gets. */
class GridAspectTest {
  @Test
  fun theBreakpointIsTheBodysAspect() {
    // An 11" tablet held either way, and a phone standing up.
    assertEquals(GridAspect.Landscape, GridAspect.of(1194.dp, 696.dp))
    assertEquals(GridAspect.Portrait, GridAspect.of(834.dp, 1074.dp))
    assertEquals(GridAspect.Portrait, GridAspect.of(393.dp, 777.dp))
    // A phone held sideways is the same shape of page as the tablet, only smaller.
    assertEquals(GridAspect.Landscape, GridAspect.of(844.dp, 390.dp))
    // Dead square falls to landscape, which is the arrangement every page has.
    assertEquals(GridAspect.Landscape, GridAspect.of(700.dp, 700.dp))
  }
}
