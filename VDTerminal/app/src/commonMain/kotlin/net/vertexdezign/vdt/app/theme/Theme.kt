package net.vertexdezign.vdt.app.theme

import androidx.compose.ui.graphics.Color

/** Terminal palette, ported from the Tailwind `@theme` tokens. */
object VdtColors {
  val Green = Color(0xFF2D8633)
  val Light = Color(0xFFE6E7E8)
  val Gray = Color(0xFFCACAD0)
  val DarkGray = Color(0xFF666666)
  val Panel = Color(0xFFF0F0F2)
  val Accent = Color(0xFF00A35C)

  val Black = Color(0xFF000000)
  val White = Color(0xFFFFFFFF)
  val TextDark = Color(0xFF333333)

  val PanelBorder = Color(0xFFD1D5DB) // gray-300
  val TrackGray = Color(0xFFE5E7EB) // gray-200
  val ProgressBlue = Color(0xFF2563EB) // blue-600
  val Red = Color(0xFFDC2626)
}

/** Per-brand accent, ported from the `.brand-*` CSS classes. */
data class BrandAccent(val active: Color, val text: Color, val labelText: Color)

private val defaultAccent = BrandAccent(VdtColors.Green, VdtColors.White, VdtColors.White)

private val brandAccents: Map<String, BrandAccent> =
  mapOf(
    "claas" to BrandAccent(Color(0xFFB4C618), VdtColors.White, Color(0xFFFE0000)),
    "fendt" to BrandAccent(Color(0xFF008B45), VdtColors.White, VdtColors.White),
    "steyr" to BrandAccent(Color(0xFFE20026), VdtColors.White, VdtColors.White),
    "valtra" to BrandAccent(Color(0xFFE4002B), VdtColors.White, VdtColors.White),
    "mercedesbenztrucks" to BrandAccent(Color(0xFFE9EC5D), VdtColors.Black, VdtColors.Black),
    "johndeere" to BrandAccent(Color(0xFF367C2B), VdtColors.White, VdtColors.White),
  )

/** Resolves the accent from a brand name, mirroring `brand-${name.toLowerCase().replace(/\s+/g,"")}`. */
fun brandAccentFor(brandName: String?): BrandAccent {
  if (brandName.isNullOrBlank()) return defaultAccent
  val key = brandName.lowercase().filterNot { it.isWhitespace() }
  return brandAccents[key] ?: defaultAccent
}
