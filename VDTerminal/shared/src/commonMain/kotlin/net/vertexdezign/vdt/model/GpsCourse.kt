package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the **gpsCourse** channel the mod writes to `gpsCourse.json` (separate file, its own
 * cadence — see the mod's `src/collect/GpsCourseExporter.lua`): the guidance lines FS25's steering
 * assist generated for the field the driven vehicle is on.
 *
 * The game does not work in AB lines: it generates a whole field course, which is why this is a list
 * of polylines plus the detected field boundary rather than one line and a spacing. It is rewritten
 * only when that course changes — a different field, a different implement width, changed AI settings
 * — so everything here is effectively static while a field is being worked. The part that is not
 * (current line, cross-track error, worked lines) rides on the telemetry tick as [GpsCourseState].
 *
 * [courseId] joins the two, and is `""` when there is no course at all: the mod publishes the empty
 * model rather than deleting the file, so the app clears its overlay instead of drawing the last
 * field's lines over the next one.
 *
 * All coordinates are normalized `[0,1]` map coordinates in the exact frame of [MapData] and
 * [Player.posX]/[Player.posZ], so the course projects with the same math as everything else on the
 * map.
 */
@Serializable
data class GpsCourseData(
  val version: String = "",
  val courseId: String = "",
  /** Working width the course was generated for, in meters — the swath each line covers. */
  val implementWidth: Float = 0f,
  val numHeadlands: Int = 0,
  /** Meters the lines are shifted sideways by; the geometry here already includes it. */
  val sideOffset: Float = 0f,
  /** Radians, or -1 for the game's "automatic". */
  val workDirection: Float = 0f,
  /** The detected field boundary, flat `[x1, z1, x2, z2, …]`; finer than [MapField.polygon]. */
  val boundary: List<Float> = emptyList(),
  /** Boundaries of the field's islands, each flat like [boundary]. */
  val islands: List<List<Float>> = emptyList(),
  val segments: List<GpsCourseSegment> = emptyList(),
) {
  /** No course to draw — either nothing was published yet, or the driver has left the field. */
  val isEmpty: Boolean get() = segments.isEmpty()
}

@Serializable
data class GpsCourseSegment(
  /**
   * The game's own segment index, and the key everything else uses: [GpsCourseState.segmentIndex]
   * names one of these, and [GpsCourseState.isWorked] is asked about one of these.
   */
  val i: Int = 0,
  /**
   * `"line"`, `"headland"` or `"island"` — the game's three kinds, which it also colors its own
   * debug draw by. A string token rather than an enum so an unknown future kind draws as a plain
   * line instead of breaking the parse.
   */
  val kind: String = "line",
  /** Which headland ring, for `kind == "headland"`. */
  val headlandIndex: Int? = null,
  /** Flat normalized polyline `[x1, z1, x2, z2, …]`; a straight line is just its two ends. */
  val p: List<Float> = emptyList(),
)
