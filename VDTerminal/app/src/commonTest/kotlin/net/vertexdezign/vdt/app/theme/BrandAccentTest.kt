package net.vertexdezign.vdt.app.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which brands' logos sit on a plate in the header, and on which one (see [BrandAccent.logoPlate]). */
class BrandAccentTest {
  @Test
  fun aBrandTheTableDoesNotKnowGetsTheGamesBlackPlate() {
    assertEquals(Color.Black, brandAccentFor("STOLL").logoPlate)
  }

  @Test
  fun aListedBrandDrawsItsLogoOnItsOwnColourUnlessGivenAPlate() {
    assertNull(brandAccentFor("VALTRA").logoPlate)
  }

  @Test
  fun aListedBrandWhoseLogoNeedsAPlateHasOne() {
    // Steyr's red wordmark vanishes on Steyr red.
    assertEquals(Color.White, brandAccentFor("STEYR").logoPlate)
  }
}
