package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.CourseProgress
import net.vertexdezign.vdt.model.GpsCourseData
import net.vertexdezign.vdt.model.Player
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.polylineLength
import net.vertexdezign.vdt.model.projectOnPolyline
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Two samples further apart than this are not bridged: the game was paused, the server stalled, or
 * the telemetry was switched off in between, and whatever the vehicle did then is not ours to vouch
 * for. The same bound [net.vertexdezign.vdt.model.WorkSweep] uses for the coverage trail.
 */
private const val MAX_GAP_MS = 1000L

/** A jump along the line further than this between two samples is a teleport, not a stretch driven. */
private const val MAX_JUMP_METERS = 40f

/**
 * How far off the line the vehicle may be and still count as on it. A side offset puts a steered
 * vehicle up to half a swath beside the line it follows; this is the floor for a course generated
 * without a working width, or for a narrow one.
 */
private const val MIN_ON_LINE_METERS = 3f

/**
 * Which stretches of the course lines have been driven with the steering assist engaged — see
 * [CourseProgress] for why this replaces the game's own flags, and why it is the line and not the
 * ground.
 *
 * Each telemetry sample taken while the assist is steering is projected onto the line it is following
 * (the game's `currentSegmentIndex`), and the stretch between it and the previous sample on the same
 * line is marked. The position is the map marker's, which in a vehicle is the vehicle: the stretch
 * ends under the machine, so the line changes colour right behind it.
 *
 * Progress belongs to one course. A different [GpsCourseData.courseId] — a new field, a new working
 * width, or the same field after the game dropped the course — starts over, as the game's flags do.
 *
 * Thread-safe: the course and the samples arrive on different collectors, and the snapshot is taken on
 * the publish timer.
 */
class CourseTracker {
  private var course: GpsCourseData? = null

  /** Per line: its length, in the normalized units the course is sent in. */
  private var lengths = emptyMap<Int, Float>()

  /** Per line: driven stretches as `[from, to]` fractions of its length, sorted and disjoint. */
  private val driven = mutableMapOf<Int, MutableList<ClosedFloatingPointRange<Float>>>()

  private var last: Mark? = null

  private class Mark(val line: Int, val along: Float, val atMs: Long)

  /**
   * The course being driven, as the gpsCourse channel publishes it. The same id keeps what has been
   * driven — the file is rewritten without the course changing, and a reparse is not a new field.
   */
  @Synchronized
  fun setCourse(data: GpsCourseData?) {
    val next = data?.takeUnless { it.isEmpty || it.courseId.isBlank() }
    if (next != null && next.courseId == course?.courseId) {
      course = next
      return
    }
    course = next
    lengths = next?.segments?.associate { it.i to polylineLength(it.p) }.orEmpty()
    driven.clear()
    last = null
  }

  /**
   * Fold one telemetry sample in. [player] is the map marker — the vehicle, while one is driven —
   * and [terrainSize] the map edge in meters, which puts the thresholds into the course's frame.
   */
  @Synchronized
  fun record(vehicle: Vehicle?, player: Player?, terrainSize: Float, nowMs: Long) {
    val current = course
    val gps = vehicle?.gps
    val state = gps?.course
    // Only while the assist is actually steering, and only against the geometry this state indexes:
    // the live state names a new course a beat before its geometry arrives.
    if (current == null || gps == null || !gps.active || state == null || state.courseId != current.courseId ||
      player == null || terrainSize <= 0f
    ) {
      last = null
      return
    }
    val line = current.segments.firstOrNull { it.i == state.segmentIndex }
    val length = line?.let { lengths[it.i] } ?: 0f
    val hit = line?.let { projectOnPolyline(it.p, player.posX, player.posZ) }
    val onLine = max(current.implementWidth / 2f, MIN_ON_LINE_METERS) / terrainSize
    if (line == null || length <= 0f || hit == null || hit.off > onLine) {
      last = null
      return
    }

    val prev = last
    last = Mark(line.i, hit.along, nowMs)
    if (prev == null || prev.line != line.i || nowMs - prev.atMs > MAX_GAP_MS) return
    if (abs(hit.along - prev.along) > MAX_JUMP_METERS / terrainSize) return
    val from = min(prev.along, hit.along) / length
    val to = max(prev.along, hit.along) / length
    if (to > from) mark(line.i, from.coerceIn(0f, 1f)..to.coerceIn(0f, 1f))
  }

  /** What has been driven on the current course, or null when there is no course. */
  @Synchronized
  fun snapshot(): CourseProgress? {
    val current = course ?: return null
    return CourseProgress(
      courseId = current.courseId,
      driven = driven.mapValues { (_, spans) -> spans.flatMap { listOf(it.start, it.endInclusive) } },
    )
  }

  /** Add [span] to [line]'s stretches, merging it with every one it overlaps or touches. */
  private fun mark(line: Int, span: ClosedFloatingPointRange<Float>) {
    val spans = driven.getOrPut(line) { mutableListOf() }
    var lo = span.start
    var hi = span.endInclusive
    val kept = spans.filter { it.endInclusive < lo || it.start > hi }
    for (other in spans) {
      if (other.endInclusive >= lo && other.start <= hi) {
        lo = min(lo, other.start)
        hi = max(hi, other.endInclusive)
      }
    }
    spans.clear()
    spans += kept
    spans += lo..hi
    spans.sortBy { it.start }
  }
}
