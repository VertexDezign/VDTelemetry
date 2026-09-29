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
   * names one of these, and [CourseProgress.driven] is keyed by it.
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

/**
 * A line counts as done once this much of its length has been driven.
 *
 * Not all of it: steering drops off a few metres before the headland, where the driver takes the turn,
 * and a course line runs right up to it — so a line driven end to end the way anyone actually drives
 * one still comes out a little short, more so on the short lines in a field's corners.
 */
const val COURSE_LINE_DONE_FRACTION = 0.9f

/**
 * Which stretches of each course line have been driven with the steering assist engaged — tracked by
 * the **server** from the telemetry it already receives, and sent in place of the game's own "worked"
 * flags ([GpsCourseState.worked]).
 *
 * The game's flag is not a statement about how much of a line was driven. `SteeringFieldCourse` sets
 * it once steering has been engaged on a line for 2.5 s, so a line driven for a metre reads exactly
 * like one driven end to end. The only thing in the game that reads it is the course visual; picking
 * the next line does not, so nothing is lost by answering the question ourselves.
 *
 * What is recorded is the line itself, not the ground: while the assist is steering, the vehicle's
 * position is projected onto the line it is following and the stretch between two samples is marked.
 * That is deliberately not the coverage mask — lines generated narrower than the tool overlap, and a
 * line whose ground a neighbour already worked is still a line nobody has driven.
 *
 * Its lifetime is the course's, like the game's flags: a new [courseId] starts every line over. A
 * server restart does too, which the game's flags would have survived.
 */
@Serializable
data class CourseProgress(
  /** The [GpsCourseData.courseId] these stretches belong to; ignore them for any other course. */
  val courseId: String = "",
  /**
   * Per line, keyed by [GpsCourseSegment.i]: the driven stretches as flat `[from1, to1, from2, to2, …]`
   * fractions of the line's length, measured from its first point, sorted and never overlapping.
   * Lines never driven are left out.
   */
  val driven: Map<Int, List<Float>> = emptyMap(),
) {
  /** How much of the line with index [i] has been driven, 0..1. */
  fun fractionOf(i: Int): Float {
    val spans = driven[i] ?: return 0f
    var sum = 0f
    for (k in 0 until spans.size / 2) sum += spans[2 * k + 1] - spans[2 * k]
    return sum
  }

  /** How many lines count as done. */
  val doneCount: Int get() = driven.keys.count { fractionOf(it) >= COURSE_LINE_DONE_FRACTION }
}
