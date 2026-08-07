package net.vertexdezign.vdt.app.theme

import androidx.compose.ui.graphics.Color

/**
 * Terminal palette, ported from the Tailwind `@theme` tokens.
 *
 * Grouped by **role**, because one hue cannot serve every role. The terminal draws on two families of
 * surface — the light panels ([Panel], [Light], [White], [TrackGray]) and the black shell ([Black]:
 * the footer, display mode, the modal scrims) — and a colour that reads on one is often unreadable on
 * the other. A fill only has to be *seen*; text has to be *read*, and that is a 4.5:1 contrast ratio
 * against whatever is behind it (WCAG AA), or 3:1 for large text and for icons carrying meaning.
 *
 * The ratios quoted below are against [Panel] (`#F0F0F2`), the lightest surface the app puts text on,
 * so they are the worst case for every panel. The scale is deliberately short: on a surface that
 * light, every neutral above `#6E6E6E` fails AA, which leaves room for exactly two readable rungs.
 * A third, quieter rung does not exist — quieter text is made with size and weight (9sp bold labels
 * against 12sp body), never with a paler grey.
 */
object VdtColors {
  // ---- Surfaces ----

  /** App background, behind the panels. */
  val Light = Color(0xFFE6E7E8)

  /** Panel body. */
  val Panel = Color(0xFFF0F0F2)

  val White = Color(0xFFFFFFFF)

  /** The shell surfaces — footer, display-mode chrome, dialog scrims. */
  val Black = Color(0xFF000000)

  /** Progress-bar tracks and neutral chips. */
  val TrackGray = Color(0xFFE5E7EB) // gray-200

  val PanelBorder = Color(0xFFD1D5DB) // gray-300

  /**
   * Unlit marks on a light surface: the dark half of the lightbar, a section slice Precision Farming
   * has no reading for. **Never text or a meaningful icon** — at 1.4:1 against [Panel] it is a shade
   * of the background, which is the point of an unlit cell and the ruin of a label. Use [DarkGray] to
   * write something quietly, or [TextDisabled] for a control that is off.
   */
  val Gray = Color(0xFFCACAD0)

  // ---- Fills: bars, dots, chips, map strokes, and anything drawn on [Black] ----

  /** Brand green. Doubles as ink on light (5.5:1) and as a chip under [White] text (6.3:1). */
  val Green = Color(0xFF256E2B)

  /** Guidance/active hue. Kept bright for the black shell, where it is read at 6.4:1 — on a light surface use [AccentText]. */
  val Accent = Color(0xFF00A35C)

  /** Warning. Also ink on light (4.7:1); [White] on it reads at 5.3:1, which `#D97706` did not. */
  val Amber = Color(0xFFA85408)

  /** Critical. Also ink on light (5.0:1). */
  val Red = Color(0xFFC81E1E)

  val ProgressBlue = Color(0xFF2563EB) // blue-600, 4.5:1 as ink

  // ---- Ink on the light surfaces ----

  /** Primary text: values, titles, anything you read rather than glance at. 11.1:1. */
  val TextDark = Color(0xFF333333)

  /** Secondary text and icons: field labels, captions, empty states, inactive tints. 5.0:1. */
  val DarkGray = Color(0xFF666666)

  /**
   * A control that is off or unavailable, and nothing else. 3.3:1 — below AA on purpose, because
   * "you cannot use this" is exactly what the drop in contrast says, and WCAG exempts disabled
   * controls from the requirement. Text that is merely quiet is [DarkGray].
   */
  val TextDisabled = Color(0xFF7F858D)

  /** [Accent] darkened for text and icons on a light panel: 5.3:1 where [Accent] itself is 2.9:1. */
  val AccentText = Color(0xFF00723F)

  // ---- Ink on the black shell ----

  /** Secondary text on [Black] — 8.3:1 there, and unreadable on any panel. */
  val OnBlackMuted = Color(0xFF9CA3AF) // gray-400

  /** Unlit marks on [Black]: inactive page dots, the info-severity chip. */
  val OnBlackTrack = Color(0xFF4B5563) // gray-600
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
