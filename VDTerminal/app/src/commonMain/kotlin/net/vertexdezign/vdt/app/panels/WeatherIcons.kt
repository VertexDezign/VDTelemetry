package net.vertexdezign.vdt.app.panels

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.model.WeatherKind

/**
 * The forecast glyphs, drawn rather than borrowed from Material — the same call [ClusterIcons] makes,
 * for two of the same reasons and one of its own.
 *
 * *Its own reason:* this is a **set**. Eight conditions have to be told apart at a glance in a strip
 * of 20dp icons, which only works if they share one visual language — one cloud shape, one weight of
 * line, one scale. Material has a sun and a cloud, nothing faithful for partly-cloudy or hail, and
 * assembling the set from four Material glyphs and four drawn ones would leave the strip looking like
 * two strips.
 *
 * *The shared reasons:* a character like `☀` is tofu in the wasm build (no font fallback), and the
 * shapes are what carry the meaning here. **Weather type is distinguished by shape alone** — every
 * glyph is drawn in one ink and tinted by its caller, so nothing about the reading depends on hue
 * (see `VDTerminal/README.md` → "Design rules"). Rain slants, snow is angular, hail is round: the
 * three that hang below the same cloud are told apart by form, not by colour or position.
 *
 * Every glyph is a **fill** on a 24×24 viewport, no strokes, [Color.Black] throughout because `Icon`
 * tints the whole thing — exactly the conventions [ClusterIcons] documents.
 */
object WeatherIcons {
  /** Full sun: a disc and eight rays. */
  val Sun = weather("Sun") {
    fill(SUN_DISC + SUN_RAYS)
  }

  /** A small sun clear of the cloud's upper left, so both shapes stay legible at 16dp. */
  val PartiallyCloudy = weather("PartiallyCloudy") {
    fill(SMALL_SUN + CLOUD_LOW)
  }

  /**
   * The bare cloud, pushed back down by [CLOUD_LIFT]. [CLOUD] is drawn high so the precipitation
   * glyphs have room to hang; with nothing hanging from it the same shape would just sit top-heavy
   * in its box, so this one glyph — and only this one — puts it back in the middle.
   */
  val Cloudy = weather("Cloudy") {
    addGroup(translationY = CLOUD_LIFT)
    fill(CLOUD)
    clearGroup()
  }

  /** Cloud plus three long slanted streaks — the slant and the length are what carry it. */
  val Rain = weather("Rain") { fill(CLOUD + RAIN_DROPS) }

  /** Cloud plus three six-armed flakes, **scattered**: the middle one falls low. See [SNOW_FLAKES]. */
  val Snow = weather("Snow") { fill(CLOUD + SNOW_FLAKES) }

  /** Cloud plus three round stones in a **level row** — the straight line is what reads, not the roundness. */
  val Hail = weather("Hail") { fill(CLOUD + HAIL_STONES) }

  val Thunder = weather("Thunder") { fill(CLOUD + BOLT) }

  /** A banded funnel. Even-odd so the two bands read as slots cut out of the cone. */
  val Twister = weather("Twister") { fill(FUNNEL, PathFillType.EvenOdd) }

  /**
   * A bare ring for a condition we have no glyph for — a weather type a future game version adds.
   * Deliberately unlike every other glyph in the set, so it reads as "no reading" rather than as
   * some eighth kind of weather.
   */
  val Unknown = weather("Unknown") { fill(RING, PathFillType.EvenOdd) }

  /**
   * The wind vane, pointing **up** at zero rotation. The caller rotates it by `windDirection + 180`,
   * which is what the game does to its own arrow — the exported angle is where the wind comes from,
   * so the arrow has to point the other way to show where it is going.
   */
  val WindArrow = weather("WindArrow") {
    fill("M12 3 L18 20 L12 16.4 L6 20 Z")
  }

  /** The glyph for a forecast entry's condition. */
  fun of(kind: WeatherKind): ImageVector = when (kind) {
    WeatherKind.SUN -> Sun
    WeatherKind.PARTIALLY_CLOUDY -> PartiallyCloudy
    WeatherKind.CLOUDY -> Cloudy
    WeatherKind.RAIN -> Rain
    WeatherKind.SNOW -> Snow
    WeatherKind.HAIL -> Hail
    WeatherKind.TWISTER -> Twister
    WeatherKind.THUNDER -> Thunder
    WeatherKind.UNKNOWN -> Unknown
  }

  /** What a screen reader says, and the caption under the "now" block. */
  fun labelOf(kind: WeatherKind): String = when (kind) {
    WeatherKind.SUN -> "Sunny"
    WeatherKind.PARTIALLY_CLOUDY -> "Partly cloudy"
    WeatherKind.CLOUDY -> "Cloudy"
    WeatherKind.RAIN -> "Rain"
    WeatherKind.SNOW -> "Snow"
    WeatherKind.HAIL -> "Hail"
    WeatherKind.TWISTER -> "Twister"
    WeatherKind.THUNDER -> "Thunderstorm"
    WeatherKind.UNKNOWN -> "Unknown"
  }
}

// ---- Path data ----
//
// Circles are written as two half-arcs (`A r r 0 1 0 …` twice) rather than as polygons: the parser
// takes SVG arcs, and a real arc stays round at every size these are drawn at (14dp in the strip,
// 40dp in the "now" block). Overlapping subpaths are unioned by the default NonZero fill, which is
// what lets the cloud be three discs and a bar rather than one hand-fitted outline.
//
// **NonZero only unions subpaths that wind the same way.** Two overlapping subpaths of opposite
// winding cancel to zero and punch a hole instead — which is what the cloud's joining bar used to do
// to the three discs it was meant to merge with. Sweep-flag 0 runs counter-clockwise on screen, so
// every subpath that overlaps another one here is written counter-clockwise too (down the left edge
// first, then across). Subpaths that touch nothing else — rays, drops, flakes — wind either way
// safely; give a new one the counter-clockwise form anyway if it might ever overlap.

/** Disc of radius 5 at the viewport centre. */
private const val SUN_DISC = "M12 7 A5 5 0 1 0 12 17 A5 5 0 1 0 12 7 Z"

/**
 * Eight rays from radius 6.5 to 9.5. The four cardinals are axis-aligned bars; the four diagonals are
 * the same bar rotated 45°, written out as parallelograms because the path has no rotate.
 */
private const val SUN_RAYS =
  "M11 1.5 H13 V4.5 H11 Z" +
    "M11 19.5 H13 V22.5 H11 Z" +
    "M1.5 11 H4.5 V13 H1.5 Z" +
    "M19.5 11 H22.5 V13 H19.5 Z" +
    "M15.89 6.70 L17.30 8.11 L19.42 5.99 L18.01 4.58 Z" +
    "M8.11 6.70 L6.70 8.11 L4.58 5.99 L5.99 4.58 Z" +
    "M15.89 17.30 L17.30 15.89 L19.42 18.01 L18.01 19.42 Z" +
    "M8.11 17.30 L6.70 15.89 L4.58 18.01 L5.99 19.42 Z"

/**
 * The cloud: three discs and a bar joining their bottoms. Spans x 4.5..20.5, y 6..15.5, leaving
 * y 16..23 for whatever hangs off it — two thirds more room than a centred cloud leaves, which is
 * what lets the streaks be long enough to read as slanted and the flakes far enough apart to be
 * counted. [Cloudy], the one glyph with nothing hanging from it, puts the cloud back in the middle.
 */
private const val CLOUD =
  "M8 8.5 A3.5 3.5 0 1 0 8 15.5 A3.5 3.5 0 1 0 8 8.5 Z" +
    "M13 6 A4.5 4.5 0 1 0 13 15 A4.5 4.5 0 1 0 13 6 Z" +
    "M17.5 9.5 A3 3 0 1 0 17.5 15.5 A3 3 0 1 0 17.5 9.5 Z" +
    "M8 12 V15.5 H17.5 V12 Z"

/** How far [CLOUD] sits above centre; [Cloudy] translates by it to undo the lift. */
private const val CLOUD_LIFT = 1.5f

/** The same cloud dropped 3.5 down and shrunk, to leave the corner free for [SMALL_SUN]. */
private const val CLOUD_LOW =
  "M9.5 13.5 A3 3 0 1 0 9.5 19.5 A3 3 0 1 0 9.5 13.5 Z" +
    "M14 11.5 A4 4 0 1 0 14 19.5 A4 4 0 1 0 14 11.5 Z" +
    "M18 14.5 A2.5 2.5 0 1 0 18 19.5 A2.5 2.5 0 1 0 18 14.5 Z" +
    "M9.5 16.5 V19.5 H18 V16.5 Z"

/** Sun for the partly-cloudy glyph: disc at (7, 6.5) with only the rays that clear the cloud. */
private const val SMALL_SUN =
  "M7 3.3 A3.2 3.2 0 1 0 7 9.7 A3.2 3.2 0 1 0 7 3.3 Z" +
    "M6.2 0.4 H7.8 V2.4 H6.2 Z" +
    "M0.4 5.7 H2.4 V7.3 H0.4 Z" +
    "M2.35 1.55 L3.48 2.68 L2.35 3.81 L1.22 2.68 Z" +
    "M11.65 1.55 L12.78 2.68 L11.65 3.81 L10.52 2.68 Z"

/** Three long slanted streaks. The slant is the mark: nothing else in the set leans. */
private const val RAIN_DROPS =
  "M6.7 23 L8.4 23 L10.4 16.6 L8.7 16.6 Z" +
    "M10.7 23 L12.4 23 L14.4 16.6 L12.7 16.6 Z" +
    "M14.7 23 L16.4 23 L18.4 16.6 L16.7 16.6 Z"

/**
 * Three six-armed flakes, each three bars crossed at 60°, with the middle one **fallen 1.6 below its
 * neighbours**.
 *
 * The stagger is the load-bearing part. Arms this thin are sub-pixel down in the forecast strip, so
 * at that size a star and a round stone both resolve to the same grey blob and shape decides nothing
 * — the earlier diamonds were indistinguishable from [HAIL_STONES] for exactly that reason. What does
 * survive the downscale is where the marks sit: snow scatters, [HAIL_STONES] holds a level row. The
 * arms then do the telling at 48dp in the "now" block, where there is resolution to see them.
 *
 * The three bars overlap at each centre, so they are wound the same way — see the winding note above.
 */
private const val SNOW_FLAKES =
  "M8.48 20.95 L8.48 17.05 L7.52 17.05 L7.52 20.95 Z" +
    "M6.55 20.39 L9.93 18.44 L9.45 17.61 L6.07 19.56 Z" +
    "M6.07 18.44 L9.45 20.39 L9.93 19.56 L6.55 17.61 Z" +
    "M12.88 22.55 L12.88 18.65 L11.92 18.65 L11.92 22.55 Z" +
    "M10.95 21.99 L14.33 20.04 L13.85 19.21 L10.47 21.16 Z" +
    "M10.47 20.04 L13.85 21.99 L14.33 21.16 L10.95 19.21 Z" +
    "M17.28 20.95 L17.28 17.05 L16.32 17.05 L16.32 20.95 Z" +
    "M15.35 20.39 L18.73 18.44 L18.25 17.61 L14.87 19.56 Z" +
    "M14.87 18.44 L18.25 20.39 L18.73 19.56 L15.35 17.61 Z"

/** Three round stones on one level line — the row is the mark, against [SNOW_FLAKES]' scatter. */
private const val HAIL_STONES =
  "M8 18.05 A1.75 1.75 0 1 0 8 21.55 A1.75 1.75 0 1 0 8 18.05 Z" +
    "M12.4 18.05 A1.75 1.75 0 1 0 12.4 21.55 A1.75 1.75 0 1 0 12.4 18.05 Z" +
    "M16.8 18.05 A1.75 1.75 0 1 0 16.8 21.55 A1.75 1.75 0 1 0 16.8 18.05 Z"

private const val BOLT = "M14.6 16 L8.8 21.2 L12.2 21.2 L11 22.6 L16.4 17.8 L13 17.8 Z"

/** Funnel plus two slots; even-odd turns the slots into holes rather than more cone. */
private const val FUNNEL =
  "M3 4.5 H21 L13.8 14 L12.8 21 L11.8 23.5 L10.2 21 L9.2 14 Z" +
    "M6.6 7.6 H17.4 V8.9 H6.6 Z" +
    "M8.4 11.1 H15.6 V12.4 H8.4 Z"

/** Outer disc minus an inner one; even-odd leaves the ring. */
private const val RING =
  "M12 3.5 A8.5 8.5 0 1 0 12 20.5 A8.5 8.5 0 1 0 12 3.5 Z" +
    "M12 6 A6 6 0 1 1 12 18 A6 6 0 1 1 12 6 Z"

private fun weather(name: String, block: ImageVector.Builder.() -> Unit): ImageVector = ImageVector.Builder(
  name = "weather.$name",
  defaultWidth = 24.dp,
  defaultHeight = 24.dp,
  viewportWidth = 24f,
  viewportHeight = 24f,
).apply(block).build()

/** One filled subpath set. [Color.Black] is a placeholder — `Icon` tints over it. */
private fun ImageVector.Builder.fill(pathData: String, fillType: PathFillType = PathFillType.NonZero) {
  addPath(addPathNodes(pathData), pathFillType = fillType, fill = SolidColor(Color.Black))
}
