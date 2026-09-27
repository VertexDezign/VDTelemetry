package net.vertexdezign.vdt.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * The plate a logo needs to be read on [ground], worked out from the logo itself: null when it reads
 * on [ground] as it is, else black — the dark ground the game shows every logo on — or white when
 * the logo loses more of itself on black than on white.
 *
 * "Reads" is luminance contrast, never hue: a pixel is lost when it is under [LOST_BELOW] against the
 * ground, and a logo needs a plate once [PLATE_FROM] of its visible pixels are lost. A red mark on a
 * green header is as different as colours get and can still be the same brightness, which is exactly
 * the logo a colour-blind reader cannot see (see the design rules in the README).
 *
 * Tuned on ten real logos, five DLC and five base game, against the default green. The ones that
 * need a plate lose half or more of themselves (0.48–1.00) and the ones that do not lose nothing, so
 * the share is not a sensitive number. The contrast bound is: at 1.5:1 BvL's blue went without a plate
 * and read weakly, and at 3:1 dark marks like Stallkamp started asking for white, unlike the game.
 *
 * Only for a brand the accent table doesn't list (see [BrandAccent.autoPlate]); a listed brand's
 * plate is chosen by hand.
 */
fun logoPlateFor(pixels: IntArray, ground: Color): Color? {
  val groundLum = ground.luminance()
  var visible = 0
  var lostOnGround = 0
  var lostOnBlack = 0
  var lostOnWhite = 0
  for (argb in pixels) {
    if ((argb ushr 24) < VISIBLE_ALPHA) continue
    visible++
    val lum = Color(argb).luminance()
    if (contrast(lum, groundLum) < LOST_BELOW) lostOnGround++
    if (contrast(lum, 0f) < LOST_BELOW) lostOnBlack++
    if (contrast(lum, 1f) < LOST_BELOW) lostOnWhite++
  }
  if (visible == 0 || lostOnGround < visible * PLATE_FROM) return null
  return if (lostOnBlack <= lostOnWhite) Color.Black else Color.White
}

/** [logoPlateFor] on a decoded logo. One pass over a trimmed logo, a few tens of thousands of pixels. */
fun logoPlateFor(logo: ImageBitmap, ground: Color): Color? {
  val pixels = IntArray(logo.width * logo.height)
  logo.readPixels(pixels)
  return logoPlateFor(pixels, ground)
}

private fun contrast(a: Float, b: Float): Float = (max(a, b) + 0.05f) / (min(a, b) + 0.05f)

private const val LOST_BELOW = 2f
private const val PLATE_FROM = 0.25f

/** Half-transparent and up counts as part of the logo; its anti-aliased fringe does not. */
private const val VISIBLE_ALPHA = 128
