package net.vertexdezign.vdt.server

import net.vertexdezign.vdt.model.COVERAGE_LAYER_ID
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WorkArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coverage mask's arithmetic — where a pass is recorded, and where it is deliberately not.
 *
 * Two of these pin things that are wrong in ways nobody would notice from a screenshot: a boom that
 * paints nothing because its footprint fell between two scanlines, and a stripe drawn across ground
 * the tool was never on because two samples were bridged that shouldn't have been.
 */
class CoverageRecorderTest {
  /** A 2 km map, so the grid is the full 1024 cells and one cell is ~2 m. */
  private val terrain = 2048f

  /**
   * A work area quad. [x]/[z] is the start corner, [width] runs across the boom and [depth] along
   * travel — all in meters, converted to the normalized frame the mod actually sends.
   */
  private fun area(
    x: Float,
    z: Float,
    width: Float,
    depth: Float,
    active: Boolean = true,
  ) = WorkArea(
    index = 1,
    type = "SPRAYER",
    active = active,
    shape =
      listOf(
        x / terrain,
        z / terrain,
        (x + width) / terrain,
        z / terrain,
        x / terrain,
        (z + depth) / terrain,
      ),
  )

  private fun rig(vararg areas: WorkArea) = Vehicle(implement = listOf(Implement(workAreas = areas.toList())))

  /** Cells marked worked in the current mask. */
  private fun CoverageRecorder.worked(): Int =
    snapshot()
      .rows
      .sumOf { row -> row.chunked(2).count { it != "00" } }

  @Test
  fun paintsAWideShallowBoomAcrossItsWholeWidth() {
    // The case that fails silently: a 24 m boom is 12 cells wide and 30 cm deep — a fifth of a cell —
    // so on scanline crossings alone the quad can fall between two row centres and paint nothing at
    // all. Whether it lands on a centre depends on where in the field you happen to be.
    val recorder = CoverageRecorder()
    recorder.record(rig(area(x = 500f, z = 501f, width = 24f, depth = 0.3f)), terrain, nowMs = 0)
    assertTrue(recorder.worked() >= 12, "a 24 m boom must cover its width, not a corner of it")
  }

  @Test
  fun bridgesTheGroundBetweenTwoSamples() {
    // At working speed the tool moves further between ticks than its own footprint is deep, so the
    // stamps alone leave the pass striped. 100 ms at ~15 km/h is about 0.4 m; the quad is 0.3 m.
    val recorder = CoverageRecorder()
    recorder.record(rig(area(x = 500f, z = 500f, width = 24f, depth = 0.3f)), terrain, nowMs = 0)
    val stamped = recorder.worked()

    // Same tool, 20 m further down the field — a gap of ten cells that has to be filled in.
    recorder.record(rig(area(x = 500f, z = 520f, width = 24f, depth = 0.3f)), terrain, nowMs = 100)
    assertTrue(recorder.worked() > stamped * 5, "the ground between two samples is covered ground")
  }

  @Test
  fun refusesToBridgeAcrossAGapItCannotVouchFor() {
    // A sample from another era: the game was paused, the app was catching up, the server stalled.
    // Whatever happened in between, the tool was not necessarily working through it.
    val stale = CoverageRecorder()
    stale.record(rig(area(500f, 500f, 24f, 0.3f)), terrain, nowMs = 0)
    val first = stale.worked()
    stale.record(rig(area(500f, 520f, 24f, 0.3f)), terrain, nowMs = 5_000)
    assertEquals(first * 2, stale.worked(), "a stale sample gets a stamp, not a stripe")

    // A teleport, or a different tool wearing the same slot after a hitch change.
    val jumped = CoverageRecorder()
    jumped.record(rig(area(500f, 500f, 24f, 0.3f)), terrain, nowMs = 0)
    jumped.record(rig(area(1200f, 1200f, 24f, 0.3f)), terrain, nowMs = 100)
    assertEquals(first * 2, jumped.worked(), "a jump that far is not a pass")

    // And lifting the tool ends the trail: lowering it again elsewhere must not draw a line back.
    val lifted = CoverageRecorder()
    lifted.record(rig(area(500f, 500f, 24f, 0.3f)), terrain, nowMs = 0)
    lifted.record(rig(area(500f, 505f, 24f, 0.3f, active = false)), terrain, nowMs = 100)
    lifted.record(rig(area(500f, 510f, 24f, 0.3f)), terrain, nowMs = 200)
    assertEquals(first * 2, lifted.worked(), "the headland between two passes is not worked")
  }

  @Test
  fun recordsOnlyWhatIsActuallyWorkingGround() {
    val recorder = CoverageRecorder()
    // A raised implement covers nothing, and on foot there is no rig at all.
    recorder.record(rig(area(500f, 500f, 24f, 0.3f, active = false)), terrain, nowMs = 0)
    recorder.record(null, terrain, nowMs = 100)
    assertEquals(0, recorder.worked())
    assertNull(recorder.snapshotIfChanged(), "nothing happened, so there is nothing to republish")

    // A rig reports several areas — a cultivator with a seeder behind it — and every one counts.
    recorder.record(rig(area(500f, 500f, 6f, 0.3f), area(600f, 600f, 6f, 0.3f)), terrain, nowMs = 200)
    assertTrue(recorder.worked() >= 6, "every working part of the rig is recorded, not just the first")
  }

  @Test
  fun startsOverWhenAnotherMapIsLoaded() {
    val recorder = CoverageRecorder()
    recorder.record(rig(area(500f, 500f, 24f, 0.3f)), terrain, nowMs = 0)
    assertTrue(recorder.worked() > 0)

    // A different terrain size is a different map, and coverage of a field on that one painted onto
    // this one would be worse than none at all.
    recorder.record(rig(area(500f, 500f, 24f, 0.3f)), terrain * 2, nowMs = 100)
    val fresh = recorder.snapshot()
    assertEquals(1024, fresh.gridSize, "the grid is capped, so a 4 km map is 4 m cells rather than 2")
    assertEquals(terrain * 2, fresh.terrainSize)
  }

  @Test
  fun publishesARasterTheLayerPipelineCanRender() {
    val recorder = CoverageRecorder()
    assertFalse(recorder.hasGrid())
    recorder.record(rig(area(500f, 500f, 24f, 0.3f)), terrain, nowMs = 0)
    assertTrue(recorder.hasGrid())

    val raster = assertNotNull(recorder.snapshotIfChanged())
    assertEquals(COVERAGE_LAYER_ID, raster.id)
    assertEquals(1024, raster.gridSize)
    assertEquals(1024, raster.rows.size)
    // Two hex chars per cell, right-trimmed like the mod's own rows — a field is a small part of a map.
    assertTrue(raster.rows.count { it.isEmpty() } > 1000, "empty rows cost nothing to send")
    assertTrue(raster.rows.any { it.endsWith("01") }, "a trimmed row ends at the last worked cell")
    // One legend value: "has this been covered" has one answer, and the renderer needs a colour for it.
    assertEquals(1, raster.legend.size)
    assertNotNull(MapLayerRenderer.render(raster), "the raster has to survive the ordinary render path")

    // Nothing has moved since, so there is no new version to make the app refetch a PNG it holds.
    assertNull(recorder.snapshotIfChanged())
  }

  @Test
  fun clearsOnRequestAndSaysSo() {
    val recorder = CoverageRecorder()
    recorder.record(rig(area(500f, 500f, 24f, 0.3f)), terrain, nowMs = 0)
    recorder.snapshotIfChanged()

    recorder.reset()
    // The cleared mask is a change like any other: the app has to be told, or it keeps drawing the
    // trail that was just wiped.
    val cleared = assertNotNull(recorder.snapshotIfChanged())
    assertTrue(cleared.rows.all { it.isEmpty() })
    assertEquals(0, recorder.worked())

    // And the trail is broken by the reset — the next sample starts a new one rather than bridging
    // back to where the machine was before the driver cleared it.
    recorder.record(rig(area(500f, 520f, 24f, 0.3f)), terrain, nowMs = 100)
    val after = recorder.worked()
    recorder.reset()
    recorder.record(rig(area(500f, 500f, 24f, 0.3f)), terrain, nowMs = 0)
    recorder.record(rig(area(500f, 520f, 24f, 0.3f)), terrain, nowMs = 100)
    assertTrue(recorder.worked() > after, "a bridged pass covers more than a single stamp")
  }
}
