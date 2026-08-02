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
  /** A 2 km map, so the grid is the full 2048 cells and one cell is 1 m. */
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

  /**
   * A tool deep enough that a single sample of it covers whole cell centres.
   *
   * Most of a real tool's footprint does not — a boom is a few tens of centimeters deep — and what
   * records a pass is the ground swept *between* samples. These tests are about the bridge itself, so
   * they use a stamp that is measurable on its own: 24 m by 2 m is 24 cells across and two rows deep.
   */
  private fun deep(z: Float) = area(x = 500f, z = z, width = 24f, depth = 2f)

  /** What [deep] covers in one sample: 24 cells across, two rows down. */
  private val stamp = 48

  /** Cells marked worked in the current mask. */
  private fun CoverageRecorder.worked(): Int =
    snapshot()
      .rows
      .sumOf { row -> row.chunked(2).count { it != "00" } }

  @Test
  fun paintsAWideShallowBoomAcrossItsWholeWidth() {
    // A 24 m boom is 24 cells wide and 30 cm deep — under a third of a cell — so its own footprint
    // covers almost no cell centre. What records the pass is the ground swept between samples, which
    // is why a single stamp claims little and two samples claim the corridor between them.
    val recorder = CoverageRecorder()
    recorder.record(rig(area(x = 500f, z = 501f, width = 24f, depth = 0.3f)), terrain, nowMs = 0)
    recorder.record(rig(area(x = 500f, z = 502f, width = 24f, depth = 0.3f)), terrain, nowMs = 100)
    assertTrue(recorder.worked() >= 24, "a 24 m boom must cover its width, not a corner of it")
  }

  @Test
  fun leavesAMissedStripUnworked() {
    // Two mowers side by side with a metre of grass between them — the miss you go back for. Filling
    // every cell a footprint *touches* closes that gap at any resolution, because each mower bleeds
    // half a metre into the cell between them. Cell centres are what keep the strip on the map.
    val recorder = CoverageRecorder()
    val left = { z: Float -> area(x = 500f, z = z, width = 3f, depth = 0.3f) }
    val right = { z: Float -> area(x = 504f, z = z, width = 3f, depth = 0.3f) }
    for (step in 0..20) {
      val z = 500f + step
      recorder.record(rig(left(z), right(z)), terrain, nowMs = step * 100L)
    }

    // The gap runs 503..504 m, which at metre cells is column 503 — and it has to still be there.
    val worked = recorder.snapshot().rows.filter { it.isNotEmpty() }
    assertTrue(worked.size >= 15, "both mowers worked a strip of field")
    assertTrue(
      worked.all { it.substring(503 * 2, 503 * 2 + 2) == "00" },
      "the strip between the two mowers was never mown, and must not read as worked",
    )
    // …while the ground each mower actually covered does read as worked, or the test proves nothing.
    assertTrue(worked.all { it.substring(501 * 2, 501 * 2 + 2) == "01" }, "the left mower's pass is worked")
    assertTrue(worked.all { it.substring(505 * 2, 505 * 2 + 2) == "01" }, "the right mower's pass is worked")
  }

  @Test
  fun bridgesTheGroundBetweenTwoSamples() {
    // At working speed the tool moves further between ticks than its own footprint is deep, so the
    // stamps alone leave the pass striped.
    val recorder = CoverageRecorder()
    recorder.record(rig(deep(500f)), terrain, nowMs = 0)
    assertEquals(stamp, recorder.worked())

    // Same tool, 20 m further down the field — twenty rows that have to be filled in.
    recorder.record(rig(deep(520f)), terrain, nowMs = 100)
    assertTrue(recorder.worked() > stamp * 5, "the ground between two samples is covered ground")
  }

  @Test
  fun refusesToBridgeAcrossAGapItCannotVouchFor() {
    // A sample from another era: the game was paused, the app was catching up, the server stalled.
    // Whatever happened in between, the tool was not necessarily working through it.
    val stale = CoverageRecorder()
    stale.record(rig(deep(500f)), terrain, nowMs = 0)
    stale.record(rig(deep(520f)), terrain, nowMs = 5_000)
    assertEquals(stamp * 2, stale.worked(), "a stale sample gets a stamp, not a stripe")

    // A teleport, or a different tool wearing the same slot after a hitch change.
    val jumped = CoverageRecorder()
    jumped.record(rig(deep(500f)), terrain, nowMs = 0)
    jumped.record(rig(area(1200f, 1200f, 24f, 2f)), terrain, nowMs = 100)
    assertEquals(stamp * 2, jumped.worked(), "a jump that far is not a pass")

    // And lifting the tool ends the trail: lowering it again elsewhere must not draw a line back.
    val lifted = CoverageRecorder()
    lifted.record(rig(deep(500f)), terrain, nowMs = 0)
    lifted.record(rig(area(500f, 505f, 24f, 2f, active = false)), terrain, nowMs = 100)
    lifted.record(rig(deep(510f)), terrain, nowMs = 200)
    assertEquals(stamp * 2, lifted.worked(), "the headland between two passes is not worked")
  }

  @Test
  fun recordsOnlyWhatIsActuallyWorkingGround() {
    val recorder = CoverageRecorder()
    // A raised implement covers nothing, and on foot there is no rig at all.
    recorder.record(rig(area(500f, 500f, 24f, 2f, active = false)), terrain, nowMs = 0)
    recorder.record(null, terrain, nowMs = 100)
    assertEquals(0, recorder.worked())
    assertNull(recorder.snapshotIfChanged(), "nothing happened, so there is nothing to republish")

    // A rig reports several areas — a cultivator with a seeder behind it — and every one counts.
    recorder.record(rig(area(500f, 500f, 6f, 2f), area(600f, 600f, 6f, 2f)), terrain, nowMs = 200)
    assertEquals(24, recorder.worked(), "every working part of the rig is recorded, not just the first")
  }

  @Test
  fun startsOverWhenAnotherMapIsLoaded() {
    val recorder = CoverageRecorder()
    recorder.record(rig(deep(500f)), terrain, nowMs = 0)
    assertTrue(recorder.worked() > 0)

    // A different terrain size is a different map, and coverage of a field on that one painted onto
    // this one would be worse than none at all.
    recorder.record(rig(deep(500f)), terrain * 2, nowMs = 100)
    val fresh = recorder.snapshot()
    assertEquals(2048, fresh.gridSize, "the grid is capped, so a 4 km map gets 2 m cells rather than 1")
    assertEquals(terrain * 2, fresh.terrainSize)
  }

  @Test
  fun publishesARasterTheLayerPipelineCanRender() {
    val recorder = CoverageRecorder()
    assertFalse(recorder.hasGrid())
    recorder.record(rig(deep(500f)), terrain, nowMs = 0)
    assertTrue(recorder.hasGrid())

    val raster = assertNotNull(recorder.snapshotIfChanged())
    assertEquals(COVERAGE_LAYER_ID, raster.id)
    // A metre a cell on an ordinary map — finer than the mod's planes, because this layer is read for
    // whether a strip was missed rather than for what is growing over there.
    assertEquals(2048, raster.gridSize)
    assertEquals(2048, raster.rows.size)
    // Two hex chars per cell, right-trimmed like the mod's own rows — a field is a small part of a map.
    assertTrue(raster.rows.count { it.isEmpty() } > 2000, "empty rows cost nothing to send")
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
    recorder.record(rig(deep(500f)), terrain, nowMs = 0)
    recorder.snapshotIfChanged()

    recorder.reset()
    // The cleared mask is a change like any other: the app has to be told, or it keeps drawing the
    // trail that was just wiped.
    val cleared = assertNotNull(recorder.snapshotIfChanged())
    assertTrue(cleared.rows.all { it.isEmpty() })
    assertEquals(0, recorder.worked())

    // And the trail is broken by the reset — the next sample starts a new one rather than bridging
    // back to where the machine was before the driver cleared it.
    recorder.record(rig(deep(520f)), terrain, nowMs = 100)
    assertEquals(stamp, recorder.worked(), "the pass before the reset is not bridged across it")

    // Where an unbroken pass over the same ground would have covered the whole corridor.
    recorder.reset()
    recorder.record(rig(deep(500f)), terrain, nowMs = 0)
    recorder.record(rig(deep(520f)), terrain, nowMs = 100)
    assertTrue(recorder.worked() > stamp * 5, "a bridged pass covers more than a single stamp")
  }
}
