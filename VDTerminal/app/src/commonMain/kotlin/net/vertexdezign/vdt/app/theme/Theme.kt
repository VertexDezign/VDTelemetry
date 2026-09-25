package net.vertexdezign.vdt.app.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * One set of terminal colours, grouped by **role**, because one hue cannot serve every role. There are
 * two of them — [Light] and [Dark] — and a screen never names either: it asks [VdtColors] for a role
 * and gets whichever palette [VdtTheme] put in scope.
 *
 * The terminal draws on two families of surface — the panels ([panel], [appBackground], [surface],
 * [track]) and the black shell ([VdtColors.Black]: the footer, display mode, the modal scrims). The
 * shell is black in both palettes, so its colours are constants on [VdtColors], not roles here.
 *
 * A fill only has to be *seen*; text has to be *read*, and that is a 4.5:1 contrast ratio against
 * whatever is behind it (WCAG AA), or 3:1 for large text and for icons carrying meaning. Each palette
 * holds its ratios against its own [panel], the surface the app puts most text on.
 *
 * The hue roles ([green], [amber], [red], [progressBlue]) are **both ink and fill**: text in that hue
 * on a panel, and a chip or bar in that hue with [onFill] written on it. In the light palette they
 * are dark hues under white; in the dark palette they are light hues under near-black — the way
 * round that keeps both uses readable, since no single hue can be ink on a dark panel *and* sit
 * under white text. The consequence for a caller is one rule: text on a hue fill is [onFill], never
 * [VdtColors.White].
 */
@Immutable
data class VdtPalette(
  val isDark: Boolean,
  /** App background, behind the panels. */
  val appBackground: Color,
  /** Panel body. */
  val panel: Color,
  /**
   * A surface raised off the panel: inputs, cards, list stripes, the panel header. White on the light
   * palette, so its old name was `White` — which is also why text on a coloured fill is its own role
   * ([onFill]) now: on the dark palette the two stop being the same colour.
   */
  val surface: Color,
  /** Progress-bar tracks and neutral chips. */
  val track: Color,
  val panelBorder: Color,
  /**
   * Unlit marks: the dark half of the lightbar, a section slice Precision Farming has no reading for.
   * **Never text or a meaningful icon** — it is a shade of the panel on purpose, which is the point of
   * an unlit cell and the ruin of a label.
   */
  val unlit: Color,
  /** Primary text: values, titles, anything you read rather than glance at. */
  val text: Color,
  /** Secondary text and icons: field labels, captions, empty states, inactive tints. */
  val textSecondary: Color,
  /**
   * A control that is off or unavailable, and nothing else. Below AA on purpose, because "you cannot
   * use this" is exactly what the drop in contrast says, and WCAG exempts disabled controls. Text that
   * is merely quiet is [textSecondary].
   */
  val textDisabled: Color,
  /** Brand green, as ink and as a fill. */
  val green: Color,
  /** Guidance/active hue, bright enough to read on the black shell. On a panel use [accentText]. */
  val accent: Color,
  /** [accent] tuned to be read on a panel. */
  val accentText: Color,
  /** Warning. */
  val amber: Color,
  /** Critical. */
  val red: Color,
  val progressBlue: Color,
  /** Text and icons written on a hue fill ([green], [amber], [red], [progressBlue], [accent]). */
  val onFill: Color,
) {
  companion object {
    /**
     * The terminal's original palette, ported from the Tailwind `@theme` tokens. Ratios against
     * [panel] (`#F0F0F2`): on a surface that light every neutral above `#6E6E6E` fails AA, which leaves
     * room for exactly two readable text rungs — quieter text is made with size and weight (9sp bold
     * labels against 12sp body), never with a paler grey.
     */
    val Light =
      VdtPalette(
        isDark = false,
        appBackground = Color(0xFFE6E7E8),
        panel = Color(0xFFF0F0F2),
        surface = Color(0xFFFFFFFF),
        track = Color(0xFFE5E7EB), // gray-200
        panelBorder = Color(0xFFD1D5DB), // gray-300
        unlit = Color(0xFFCACAD0), // 1.4:1
        text = Color(0xFF333333), // 11.1:1
        textSecondary = Color(0xFF666666), // 5.0:1
        textDisabled = Color(0xFF7F858D), // 3.3:1
        green = Color(0xFF256E2B), // 5.5:1 as ink, 6.3:1 under white
        accent = Color(0xFF00A35C), // 2.9:1 on a panel — fill only there
        accentText = Color(0xFF00723F), // 5.3:1
        amber = Color(0xFFA85408), // 4.7:1 as ink, white on it 5.3:1 (`#D97706` did not make it)
        red = Color(0xFFC81E1E), // 5.0:1
        progressBlue = Color(0xFF2563EB), // blue-600, 4.5:1
        onFill = Color(0xFFFFFFFF),
      )

    /**
     * The same roles for a dark room. Ratios against [panel] (`#1C1E21`): text 13.5, secondary 7.5,
     * disabled 3.4, green 7.4, accent text 8.9, amber 8.0, red 5.9, blue 6.5 — and every hue carries
     * [onFill] at 5.9 or better. [surface] sits a step *above* the panel, as a raised surface does in
     * the dark, so a stripe or an input still reads as the lighter thing it is on the light palette.
     */
    val Dark =
      VdtPalette(
        isDark = true,
        appBackground = Color(0xFF121315),
        panel = Color(0xFF1C1E21),
        surface = Color(0xFF2A2D31),
        track = Color(0xFF33373C),
        panelBorder = Color(0xFF41464D),
        unlit = Color(0xFF3F444A), // 1.7:1
        text = Color(0xFFE6E7E9),
        textSecondary = Color(0xFFA9AEB5),
        textDisabled = Color(0xFF70767E),
        green = Color(0xFF63C06A),
        accent = Color(0xFF2BC77A),
        accentText = Color(0xFF3DD68C),
        amber = Color(0xFFF0A43A),
        red = Color(0xFFF47067),
        progressBlue = Color(0xFF6FA0FF),
        onFill = Color(0xFF111315),
      )
  }
}

/** The palette in scope. Static: switching the theme recomposes everything, which is what it should do. */
val LocalVdtPalette = staticCompositionLocalOf { VdtPalette.Light }

/**
 * Puts [palette] in scope for [VdtColors], and hands Material the same colours: the dialogs, menus and
 * text fields the app borrows from Material 3 draw from its colour scheme, not from ours, and would
 * otherwise stay light on a dark terminal. The default content colour is the palette's text too, so a
 * `Text` or `Icon` with no colour of its own is readable on either.
 */
@Composable
fun VdtTheme(palette: VdtPalette, content: @Composable () -> Unit) {
  val scheme =
    if (palette.isDark) {
      darkColorScheme(
        primary = palette.green,
        onPrimary = palette.onFill,
        secondary = palette.accentText,
        onSecondary = palette.onFill,
        error = palette.red,
        onError = palette.onFill,
        background = palette.appBackground,
        onBackground = palette.text,
        surface = palette.panel,
        onSurface = palette.text,
        surfaceVariant = palette.surface,
        onSurfaceVariant = palette.textSecondary,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.surface,
        surfaceContainerHighest = palette.surface,
        outline = palette.panelBorder,
        outlineVariant = palette.panelBorder,
      )
    } else {
      lightColorScheme(
        primary = palette.green,
        onPrimary = palette.onFill,
        secondary = palette.accentText,
        onSecondary = palette.onFill,
        error = palette.red,
        onError = palette.onFill,
        background = palette.appBackground,
        onBackground = palette.text,
        surface = palette.surface,
        onSurface = palette.text,
        onSurfaceVariant = palette.textSecondary,
        outline = palette.panelBorder,
      )
    }
  CompositionLocalProvider(LocalVdtPalette provides palette) {
    MaterialTheme(colorScheme = scheme) {
      CompositionLocalProvider(LocalContentColor provides palette.text, content = content)
    }
  }
}

/**
 * The terminal's colours by role, read from the palette in scope ([VdtTheme]).
 *
 * The role getters are `@Composable`, so they are read in composition. Inside a draw lambda (a
 * `Canvas`, `drawBehind`) read them into a local first — the lambda runs outside composition.
 *
 * [White], [Black] and the `OnBlack*` inks are constants rather than roles: the shell is black on both
 * palettes, and white is still white on the map image and on the shell. White *text on a coloured
 * fill* is [OnFill], and a white *surface* is [Surface] — see [VdtPalette].
 */
object VdtColors {
  /** The whole palette in scope — for a draw lambda or a pure helper, which cannot read the roles below. */
  val palette: VdtPalette
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current

  // ---- Surfaces ----

  /** App background, behind the panels. */
  val Light: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.appBackground

  /** Panel body. */
  val Panel: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.panel

  /** A surface raised off the panel — see [VdtPalette.surface]. */
  val Surface: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.surface

  /** Progress-bar tracks and neutral chips. */
  val TrackGray: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.track

  val PanelBorder: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.panelBorder

  /** Unlit marks — see [VdtPalette.unlit]. **Never text or a meaningful icon.** */
  val Gray: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.unlit

  // ---- Hues: ink on a panel, and fills under [OnFill] ----

  val Green: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.green

  /** Guidance/active hue. Kept bright for the black shell; on a panel use [AccentText]. */
  val Accent: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.accent

  val Amber: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.amber

  val Red: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.red

  val ProgressBlue: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.progressBlue

  /** Text and icons on a hue fill. White on the light palette, near-black on the dark one. */
  val OnFill: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.onFill

  // ---- Ink on the panels ----

  /** Primary text: values, titles, anything you read rather than glance at. */
  val TextDark: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.text

  /** Secondary text and icons: field labels, captions, empty states, inactive tints. */
  val DarkGray: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.textSecondary

  /** A control that is off or unavailable, and nothing else — see [VdtPalette.textDisabled]. */
  val TextDisabled: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.textDisabled

  /** [Accent] tuned to be read on a panel. */
  val AccentText: Color
    @Composable @ReadOnlyComposable
    get() = LocalVdtPalette.current.accentText

  // ---- Constants: the black shell, and white where white is meant ----

  /** Literal white: ink on the black shell, and marks drawn over the map image. */
  val White = Color(0xFFFFFFFF)

  /** The shell surfaces — footer, display-mode chrome, dialog scrims. Black on both palettes. */
  val Black = Color(0xFF000000)

  /** Secondary text on [Black] — 8.3:1 there, and unreadable on any light panel. */
  val OnBlackMuted = Color(0xFF9CA3AF) // gray-400

  /** Unlit marks on [Black]: inactive page dots, the info-severity chip. */
  val OnBlackTrack = Color(0xFF4B5563) // gray-600
}

/** Per-brand accent, ported from the `.brand-*` CSS classes. Brand colours hold on both palettes. */
data class BrandAccent(val active: Color, val text: Color, val labelText: Color)

private val White = Color(0xFFFFFFFF)
private val Black = Color(0xFF000000)

/** The light palette's brand green, fixed: the header is the brand's colour whichever theme is on. */
private val defaultAccent = BrandAccent(Color(0xFF256E2B), White, White)

private val brandAccents: Map<String, BrandAccent> =
  mapOf(
    "claas" to BrandAccent(Color(0xFFB4C618), White, Color(0xFFFE0000)),
    "fendt" to BrandAccent(Color(0xFF008B45), White, White),
    "steyr" to BrandAccent(Color(0xFFE20026), White, White),
    "valtra" to BrandAccent(Color(0xFFE4002B), White, White),
    "mercedesbenztrucks" to BrandAccent(Color(0xFFE9EC5D), Black, Black),
    "johndeere" to BrandAccent(Color(0xFF367C2B), White, White),
  )

/** Resolves the accent from a brand name, mirroring `brand-${name.toLowerCase().replace(/\s+/g,"")}`. */
fun brandAccentFor(brandName: String?): BrandAccent {
  if (brandName.isNullOrBlank()) return defaultAccent
  val key = brandName.lowercase().filterNot { it.isWhitespace() }
  return brandAccents[key] ?: defaultAccent
}
