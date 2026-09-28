package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.Gps
import net.vertexdezign.vdt.model.GpsCourseData
import net.vertexdezign.vdt.model.GpsCourseSegment
import net.vertexdezign.vdt.model.GpsCourseState
import net.vertexdezign.vdt.model.Player
import net.vertexdezign.vdt.model.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which stretches of the course lines have been driven — the replacement for the game's own "worked"
 * flag, which marks a whole line done after 2.5 s of steering on it.
 */
class CourseTrackerTest {
  /** A 2 km map; everything below is in meters and sent normalized, as the mod sends it. */
  private val terrain = 2048f

  /** Two neighbouring 12 m lines running 200 m north from z = 500, on x = 506 and x = 518. */
  private val course =
    GpsCourseData(
      courseId = "c1",
      implementWidth = 12f,
      segments = listOf(line(1, x = 506f), line(2, x = 518f)),
    )

  private fun line(i: Int, x: Float) = GpsCourseSegment(
    i = i,
    p = listOf(
      x / terrain,
      500f / terrain,
      x / terrain,
      700f / terrain,
    ),
  )

  private fun tracker() = CourseTracker().apply { setCourse(course) }

  /**
   * Drive north along x from [fromZ] to [toZ] in 2 m steps at 10 Hz, following [line], with the
   * steering assist [active] or not. Returns the clock after the last sample.
   */
  private fun CourseTracker.drive(
    x: Float,
    fromZ: Float,
    toZ: Float,
    line: Int = 1,
    active: Boolean = true,
    courseId: String = "c1",
    startMs: Long = 0,
  ): Long {
    var z = fromZ
    var now = startMs
    val step = if (toZ >= fromZ) 2f else -2f
    while (if (step > 0) z <= toZ else z >= toZ) {
      val vehicle =
        Vehicle(
          gps = Gps(enabled = true, active = active, course = GpsCourseState(courseId = courseId, segmentIndex = line)),
        )
      record(vehicle, Player(posX = x / terrain, posZ = z / terrain), terrain, now)
      z += step
      now += 100
    }
    return now
  }

  private fun CourseTracker.fraction(line: Int) = assertNotNull(snapshot()).fractionOf(line)

  @Test
  fun marksTheStretchDrivenBehindTheMachine() {
    val tracker = tracker()
    tracker.drive(x = 506f, fromZ = 500f, toZ = 600f)
    val progress = assertNotNull(tracker.snapshot())
    assertEquals("c1", progress.courseId)
    val spans = assertNotNull(progress.driven[1])
    assertEquals(2, spans.size, "one unbroken stretch: $spans")
    assertEquals(0f, spans[0], 1e-4f)
    assertEquals(0.5f, spans[1], 1e-4f, "the stretch ends where the machine is, halfway up the line")
    assertNull(progress.driven[2], "the neighbour was never followed")
  }

  @Test
  fun drivingTheWholeLineMakesItDone() {
    val tracker = tracker()
    tracker.drive(x = 506f, fromZ = 500f, toZ = 700f)
    assertEquals(1f, tracker.fraction(1), 1e-4f)
    assertEquals(1, assertNotNull(tracker.snapshot()).doneCount)
  }

  @Test
  fun aSideOffsetStillDrivesTheLine() {
    // Steering a 4 m side offset: the machine is beside the line, but it is following it.
    val tracker = tracker()
    tracker.drive(x = 510f, fromZ = 500f, toZ = 600f)
    assertEquals(0.5f, tracker.fraction(1), 1e-4f)
  }

  @Test
  fun drivesBothWaysAndMergesTheStretches() {
    val tracker = tracker()
    val t = tracker.drive(x = 506f, fromZ = 500f, toZ = 560f)
    // Back down over part of it, then further up: one stretch, not three.
    tracker.drive(x = 506f, fromZ = 540f, toZ = 520f, startMs = t + 5_000)
    tracker.drive(x = 506f, fromZ = 550f, toZ = 620f, startMs = t + 10_000)
    val spans = assertNotNull(assertNotNull(tracker.snapshot()).driven[1])
    assertEquals(2, spans.size, "overlapping stretches merge: $spans")
    assertEquals(0.6f, spans[1], 1e-4f)
  }

  @Test
  fun marksNothingWhileTheAssistIsNotSteering() {
    val tracker = tracker()
    tracker.drive(x = 506f, fromZ = 500f, toZ = 700f, active = false)
    assertTrue(assertNotNull(tracker.snapshot()).driven.isEmpty())
  }

  @Test
  fun marksNothingAgainstGeometryItHasNotReceived() {
    // The live state names the next course a beat before its lines arrive; its index means nothing here.
    val tracker = tracker()
    tracker.drive(x = 506f, fromZ = 500f, toZ = 700f, courseId = "c2")
    assertTrue(assertNotNull(tracker.snapshot()).driven.isEmpty())
  }

  @Test
  fun marksNothingFarOffTheLine() {
    // The game names a current line before the machine has reached it — driving onto the field.
    val tracker = tracker()
    tracker.drive(x = 540f, fromZ = 500f, toZ = 700f)
    assertTrue(assertNotNull(tracker.snapshot()).driven.isEmpty())
  }

  @Test
  fun theNeighbouringLineIsNotThisOne() {
    // A swath away is the next line over: the game naming line 1 while the machine still drives line 2.
    val tracker = tracker()
    tracker.drive(x = 518f, fromZ = 500f, toZ = 700f)
    assertTrue(assertNotNull(tracker.snapshot()).driven.isEmpty())
  }

  @Test
  fun doesNotBridgeAGapItCannotVouchFor() {
    val tracker = tracker()
    val t = tracker.drive(x = 506f, fromZ = 500f, toZ = 520f)
    // Paused, then picked up further along: the ground in between was not seen being driven.
    tracker.drive(x = 506f, fromZ = 600f, toZ = 620f, startMs = t + 5_000)
    assertEquals(0.2f, tracker.fraction(1), 1e-4f)
    val spans = assertNotNull(assertNotNull(tracker.snapshot()).driven[1])
    assertEquals(4, spans.size, "two stretches with the gap left open: $spans")
  }

  @Test
  fun switchingLinesStartsANewStretch() {
    // The turn at the headland: the next sample is on another line, and nothing joins the two.
    val tracker = tracker()
    val t = tracker.drive(x = 506f, fromZ = 500f, toZ = 700f)
    tracker.drive(x = 518f, fromZ = 700f, toZ = 600f, line = 2, startMs = t)
    assertEquals(1f, tracker.fraction(1), 1e-4f)
    assertEquals(0.5f, tracker.fraction(2), 1e-4f)
  }

  @Test
  fun aNewCourseStartsOverAndTheSameOneDoesNot() {
    val tracker = tracker()
    tracker.drive(x = 506f, fromZ = 500f, toZ = 600f)
    // The file rewritten for the same course: what was driven stays.
    tracker.setCourse(course.copy())
    assertEquals(0.5f, tracker.fraction(1), 1e-4f)
    // A new course — another field, another width, or this one regenerated — starts every line over.
    tracker.setCourse(course.copy(courseId = "c2"))
    assertEquals("c2", assertNotNull(tracker.snapshot()).courseId)
    assertTrue(assertNotNull(tracker.snapshot()).driven.isEmpty())
    // And leaving the field is no course at all.
    tracker.setCourse(GpsCourseData(courseId = "c3"))
    assertNull(tracker.snapshot())
  }
}
