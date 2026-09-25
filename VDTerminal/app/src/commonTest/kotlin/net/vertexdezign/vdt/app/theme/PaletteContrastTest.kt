package net.vertexdezign.vdt.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holds both palettes to the ratios [VdtPalette] promises, so a tweak to one tone cannot quietly push
 * a label under AA on the palette nobody was looking at.
 */
class PaletteContrastTest {
  private fun ratio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
  }

  private fun assertReads(name: String, ink: Color, on: Color, atLeast: Float) {
    val r = ratio(ink, on)
    assertTrue(r >= atLeast, "$name reads at $r:1, needs $atLeast:1")
  }

  private fun checkPalette(label: String, p: VdtPalette) {
    for (bg in listOf("panel" to p.panel, "surface" to p.surface)) {
      assertReads("$label text on ${bg.first}", p.text, bg.second, 4.5f)
      assertReads("$label secondary on ${bg.first}", p.textSecondary, bg.second, 4.5f)
    }
    // Hue ink on the panel.
    for ((name, hue) in listOf("green" to p.green, "accentText" to p.accentText, "red" to p.red, "amber" to p.amber)) {
      assertReads("$label $name on panel", hue, p.panel, 4.5f)
    }
    // Ink on a hue fill: the other half of "text on a coloured fill is onFill".
    for ((name, fill) in listOf("green" to p.green, "red" to p.red, "amber" to p.amber, "blue" to p.progressBlue)) {
      assertReads("$label onFill on $name", p.onFill, fill, 4.5f)
    }
    // Disabled is quieter than secondary, or it says nothing.
    assertTrue(ratio(p.textDisabled, p.panel) < ratio(p.textSecondary, p.panel), "$label disabled must be quieter")
  }

  @Test
  fun lightPaletteKeepsItsRatios() = checkPalette("light", VdtPalette.Light)

  @Test
  fun darkPaletteKeepsItsRatios() = checkPalette("dark", VdtPalette.Dark)
}
