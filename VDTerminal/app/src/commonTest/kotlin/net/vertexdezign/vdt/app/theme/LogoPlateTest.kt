package net.vertexdezign.vdt.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [logoPlateFor] on synthetic logos: which plate, if any, a logo gets on the default green. */
class LogoPlateTest {
  private val green = Color(0xFF256E2B)

  /** A logo of [n] pixels: [marked] of them [ink], the rest [rest]. */
  private fun logo(ink: Color, marked: Int = 100, rest: Color = Color.Transparent, n: Int = 100) =
    IntArray(n) { if (it < marked) ink.toArgb() else rest.toArgb() }

  @Test
  fun aWhiteLogoReadsOnTheGreenAsItIs() {
    assertNull(logoPlateFor(logo(Color.White), green))
  }

  @Test
  fun aRedLogoOfTheGreensBrightnessGetsTheGamesBlackPlate() {
    // As different as colours get, and nearly the same luminance: the logo a colour-blind reader loses.
    assertEquals(Color.Black, logoPlateFor(logo(Color(0xFFE20026)), green))
  }

  @Test
  fun aLogoThatLosesOnBlackTooGetsWhite() {
    val darkGround = Color(0xFF303030)
    assertEquals(Color.White, logoPlateFor(logo(Color(0xFF202020)), darkGround))
  }

  @Test
  fun transparentPixelsAreNotPartOfTheLogo() {
    // Mostly empty canvas around a white mark: the canvas must not count as lost.
    assertNull(logoPlateFor(logo(Color.White, marked = 5), green))
    assertNull(logoPlateFor(IntArray(100), green), "a logo with nothing visible needs no plate")
  }

  @Test
  fun aPlateOnceAQuarterOfTheLogoIsLost() {
    val red = Color(0xFFE20026)
    assertNull(logoPlateFor(logo(red, marked = 20, rest = Color.White), green), "a fifth lost still reads")
    assertEquals(Color.Black, logoPlateFor(logo(red, marked = 30, rest = Color.White), green))
  }
}
