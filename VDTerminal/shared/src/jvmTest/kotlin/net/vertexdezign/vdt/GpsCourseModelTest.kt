package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.GpsCourseData
import net.vertexdezign.vdt.model.GpsCourseState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json/gpsCourse` fixtures through the real server path
 * ([VdtParser.parseGpsCourse]) and asserts the geometry channel's half of the mod↔Kotlin contract,
 * plus the worked-lines bitmask — which is the one piece of wire format the two sides have to agree
 * on bit for bit, so it is pinned from both ends (the mod's side is `spec/GpsCourse_spec.lua`).
 */
class GpsCourseModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/gpsCourse/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/gpsCourse/$name from ${File(".").absolutePath}")
  }

  @Test
  fun parsesACourseWithHeadlandsLinesAndAnIsland() {
    val data = VdtParser.parseGpsCourse(example("basic.json"))
    assertEquals("3", data.courseId)
    assertEquals(6f, data.implementWidth)
    assertEquals(2, data.numHeadlands)
    assertFalse(data.isEmpty)

    assertEquals(6, data.segments.size)
    // The index is the mod's own, and the key the live state joins on — not the list position.
    assertContentEquals(listOf(1, 2, 3, 4, 5, 6), data.segments.map { it.i })
    assertEquals(listOf("headland", "headland", "line", "line", "line", "island"), data.segments.map { it.kind })
    assertEquals(1, data.segments[0].headlandIndex)
    assertNull(data.segments[2].headlandIndex)

    // A straight line is its two ends; a headland ring closes back on its start.
    assertEquals(4, data.segments[2].p.size)
    assertEquals(data.segments[0].p.take(2), data.segments[0].p.takeLast(2))

    // The detected field, which the app draws under the lines.
    assertEquals(8, data.boundary.size)
    assertEquals(1, data.islands.size)
  }

  @Test
  fun readsAnEmptyCourseAsNothingToDraw() {
    // What the mod publishes when the driver leaves the field — a file, not a deletion, so the app
    // clears rather than keeping the last field's lines on screen.
    val data = VdtParser.parseGpsCourse(example("empty.json"))
    assertEquals("", data.courseId)
    assertTrue(data.isEmpty)
    assertTrue(data.boundary.isEmpty())
  }

  @Test
  fun parsesTheModsOwnCaptureOfAWorkedField() {
    // Unlike basic.json this one was written by the mod in game, so it is the fixture that pins the
    // *writer*: integers where the model has floats, `islands` omitted rather than sent empty, and a
    // course shaped the way the game really generates them.
    val data = VdtParser.parseGpsCourse(example("gpsCourse.json"))
    assertEquals("1", data.version)
    assertEquals("3", data.courseId)
    assertEquals(11f, data.implementWidth)
    assertEquals(1, data.numHeadlands)
    assertEquals(0f, data.sideOffset)
    // "Automatic", the game's sentinel, comes through as itself rather than as an angle.
    assertEquals(-1f, data.workDirection)
    assertTrue(data.islands.isEmpty())

    assertEquals(29, data.segments.size)
    assertContentEquals((1..29).toList(), data.segments.map { it.i })
    val (headlands, lines) = data.segments.partition { it.kind == "headland" }
    assertEquals(14, lines.size)
    assertTrue(lines.all { it.kind == "line" && it.headlandIndex == null })
    assertTrue(headlands.all { it.headlandIndex == 1 })

    // A "line" is a polyline, not an AB pair — this field's first one carries five points — so the
    // overlay has to stroke a path per segment and never just first-to-last.
    assertEquals(10, lines.first().p.size)

    // And one headland ring arrives as a *chain* of separately indexed segments, each starting where
    // the previous ended and the last closing back on the first. Worked shading therefore marks
    // pieces of a ring rather than the ring, which is why the mask is keyed by segment index.
    headlands.zipWithNext { a, b -> assertEquals(a.p.takeLast(2), b.p.take(2)) }
    assertEquals(headlands.last().p.takeLast(2), headlands.first().p.take(2))

    // Everything the app projects is normalized map space; one stray world coordinate would land a
    // line somewhere off the terrain entirely.
    assertTrue((data.boundary + data.segments.flatMap { it.p }).all { it in 0f..1f }, "coordinates are normalized")
    // The boundary closes on itself, which is what lets the app stroke it without a special case.
    assertEquals(data.boundary.take(2), data.boundary.takeLast(2))
  }

  @Test
  fun roundTripsLosslessly() {
    for (fixture in listOf("basic.json", "gpsCourse.json")) {
      val data = VdtParser.parseGpsCourse(example(fixture))
      val encoded = json.encodeToString(GpsCourseData.serializer(), data)
      assertEquals(data, json.decodeFromString(GpsCourseData.serializer(), encoded), "$fixture should round-trip")
    }
  }

  @Test
  fun toleratesAModAheadOfTheClient() {
    val data =
      VdtParser.parseGpsCourse(
        """{"version":"9","courseId":"1","segments":[{"i":1,"kind":"contour","p":[0,0,1,1],"radius":12}]}""",
      )
    // Unknown key ignored, unknown kind passed through as a token rather than failing the parse.
    assertEquals("contour", data.segments.single().kind)
    // Absent fields fall back to defaults.
    assertEquals(0f, data.implementWidth)
  }

  @Test
  fun readsTheLiveHalfOffTheTelemetryTick() {
    // The other half of the channel, on the main telemetry (mod VERSION 8). Inline rather than from
    // examples/json/*.json: those are real captures, and none has been retaken since the bump.
    val data =
      VdtParser.parseJson(
        """
        {"version":"8","vehicle":{"name":"Valtra T195","gps":{"enabled":true,"active":true,"heading":271,
        "headingUnit":"°","linesVisible":true,"course":{"courseId":"3","segmentIndex":4,"isLeft":true,
        "segmentCount":6,"workedCount":2,"worked":"5","deviationM":-0.14,"distanceToEndM":83.50}}}}
        """.trimIndent(),
      )
    val course = data.vehicle?.gps?.course
    assertEquals("3", course?.courseId)
    assertEquals(4, course?.segmentIndex)
    assertEquals(true, course?.isLeft)
    assertEquals(-0.14f, course?.deviationM)
    assertEquals(83.5f, course?.distanceToEndM)
    assertTrue(course!!.isWorked(3))

    // A vehicle with a steering spec but no course at all: the subtree is simply absent.
    val noCourse =
      VdtParser.parseJson(
        """{"version":"8","vehicle":{"gps":{"enabled":true,"active":false,"heading":0}}}""",
      )
    assertNull(noCourse.vehicle?.gps?.course)
  }

  @Test
  fun decodesTheWorkedBitmaskTheWayTheModPacksIt() {
    // Four segments per character, bit 0 = the lowest index in the group: "5" == 0b0101 == 1 and 3.
    val first = GpsCourseState(worked = "5", segmentCount = 4, workedCount = 2)
    assertTrue(first.isWorked(1))
    assertFalse(first.isWorked(2))
    assertTrue(first.isWorked(3))
    assertFalse(first.isWorked(4))

    // Groups run ascending, so segment 5 is bit 0 of the SECOND character.
    val second = GpsCourseState(worked = "01", segmentCount = 5, workedCount = 1)
    assertFalse(second.isWorked(1))
    assertTrue(second.isWorked(5))

    // Past the trimmed tail, before the start, and with no mask at all: all "not worked", never a throw.
    assertFalse(second.isWorked(9))
    assertFalse(second.isWorked(0))
    assertFalse(second.isWorked(-1))
    assertFalse(GpsCourseState().isWorked(1))
  }
}
