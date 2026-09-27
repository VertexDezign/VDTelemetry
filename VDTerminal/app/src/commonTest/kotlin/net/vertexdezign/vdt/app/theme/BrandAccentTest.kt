package net.vertexdezign.vdt.app.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which brands' logos sit on a plate in the header, and on which one (see [BrandAccent.logoPlate]). */
class BrandAccentTest {
  @Test
  fun aBrandTheTableDoesNotKnowAsksItsLogo() {
    val accent = brandAccentFor("STOLL")
    assertTrue(accent.autoPlate)
    assertNull(accent.logoPlate)
  }

  @Test
  fun aListedBrandsPlateIsChosenByHand() {
    assertFalse(brandAccentFor("VALTRA").autoPlate)
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
