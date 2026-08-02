package net.vertexdezign.vdt

import net.vertexdezign.vdt.model.PfMode
import net.vertexdezign.vdt.model.SectionSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The section view (mod VERSION 8): the shutoff sections on `workWidth`, the `workAreas` each object
 * carries, and the Precision Farming rates hanging off the tool doing the work.
 *
 * Inline JSON rather than the `examples/json` captures: those are real, and none was taken since the
 * bump. The mod's side of the same contract is `spec/WorkAreas_spec.lua` and
 * `spec/PrecisionFarming_spec.lua`.
 */
class SectionViewModelTest {
  private fun vehicle(body: String) = VdtParser.parseJson("""{"version":"8","vehicle":{$body}}""").vehicle!!

  @Test
  fun readsTheShutoffSectionsInBoomOrder() {
    val width =
      vehicle(
        """
        "workWidth":{"left":9,"leftMax":12,"right":12,"rightMax":12,"total":21,"unit":"m","activeCount":4,
        "sections":[{"active":false,"side":"LEFT"},{"active":true,"side":"LEFT"},
        {"active":true,"side":"CENTER"},{"active":true,"side":"RIGHT"},{"active":true,"side":"RIGHT"}]}
        """.trimIndent(),
      ).workWidth!!

    assertEquals(5, width.sections.size)
    assertEquals(4, width.activeCount)
    // Order is the boom's own, left to right — the app draws straight through it.
    assertEquals(
      listOf(SectionSide.LEFT, SectionSide.LEFT, SectionSide.CENTER, SectionSide.RIGHT, SectionSide.RIGHT),
      width.sections.map { it.side },
    )
    assertFalse(width.sections.first().active)
    assertEquals(4, width.sections.count { it.active })
    // The aggregate width still says the same thing it did before sections existed.
    assertEquals(9f, width.left)
    assertEquals(21f, width.total)
  }

  @Test
  fun aToolWithoutSectionsStillReportsItsWidth() {
    val width = vehicle("\"workWidth\":{\"left\":3,\"right\":3,\"total\":6,\"unit\":\"m\"}").workWidth!!
    assertTrue(width.sections.isEmpty())
    assertNull(width.activeCount)
  }

  @Test
  fun readsWorkAreasWithTheirFootprint() {
    val areas =
      vehicle(
        """
        "workAreas":[{"index":1,"type":"SPRAYER","active":true,"processing":true,"width":24,"unit":"m",
        "shape":[0.49414,0.5,0.50586,0.5,0.49414,0.50049]},
        {"index":2,"type":"CULTIVATOR","active":false,"processing":false}]
        """.trimIndent(),
      ).workAreas

    assertEquals(2, areas.size)
    assertEquals("SPRAYER", areas[0].type)
    assertTrue(areas[0].active)
    assertTrue(areas[0].processing)
    assertEquals(24f, areas[0].width)
    // Three corners of the parallelogram, x/z interleaved; the fourth is the consumer's to derive.
    assertEquals(6, areas[0].shape.size)

    // An area with no readable nodes reports what it is doing without anywhere to draw it.
    assertFalse(areas[1].active)
    assertTrue(areas[1].shape.isEmpty())
    assertNull(areas[1].width)
  }

  @Test
  fun readsPrecisionFarmingRatesAndTheSubSectionStrip() {
    val pf =
      vehicle(
        """
        "precisionFarming":{"mode":"FERTILIZER","auto":true,
        "nitrogen":{"level":45,"target":90,"unit":"kg/ha"},"ph":{"level":6.2,"target":6.8},
        "workAreas":[{"index":1,"subSections":[
        {"valid":true,"n":45,"nTarget":90,"ph":6.2,"phTarget":6.8},
        {"valid":false,"n":0,"nTarget":0,"ph":0,"phTarget":0}]}]}
        """.trimIndent(),
      ).precisionFarming!!

    assertEquals(PfMode.FERTILIZER, pf.mode)
    assertTrue(pf.auto)
    // Fertilizing leads with nitrogen; liming with pH. Same tool, different tank.
    assertEquals(pf.nitrogen, pf.primary)
    assertEquals(45f, pf.primary?.level)
    assertEquals(45f, pf.nitrogen?.deficit)
    // Already at target reads as no deficit, never as a negative one.
    assertEquals(0f, pf.ph?.copy(level = 7f)?.deficit)

    val strip = pf.workAreas.single()
    // Joined to WorkArea.index, not to a position in the list.
    assertEquals(1, strip.index)
    assertEquals(2, strip.subSections.size)
    assertTrue(strip.subSections[0].valid)
    assertEquals(90f, strip.subSections[0].nTarget)
    // "No data here", which is not the same as "nothing needed here".
    assertFalse(strip.subSections[1].valid)
  }

  @Test
  fun readsTheNozzleStatesTheAppDrawsAsASprayBar() {
    val pf =
      vehicle(
        """
        "precisionFarming":{"mode":"OTHER","auto":false,
        "nozzles":{"count":6,"activeCount":4,"individual":true,
        "active":[true,true,false,false,true,true]}}
        """.trimIndent(),
      ).precisionFarming!!

    val nozzles = pf.nozzles!!
    assertEquals(6, nozzles.count)
    assertEquals(4, nozzles.activeCount)
    assertTrue(nozzles.individual)
    // Left to right across the boom, so a gap in the middle is a gap in the middle.
    assertEquals(listOf(true, true, false, false, true, true), nozzles.active)
    // Herbicide: PF computes no rates at all, and the nozzles are the only thing this tool can say.
    assertEquals(PfMode.OTHER, pf.mode)
    assertNull(pf.primary)
  }

  @Test
  fun readsThePerNozzleOutputOfAPulsingBoom() {
    // Mid-turn on a pulse-width-modulation sprayer: PF pulses each nozzle in proportion to how fast
    // that part of the boom is travelling, so the inside dials down while the outside stays open —
    // and every one of them is still active. Reading only the flags would show a solid boom.
    val nozzles =
      vehicle(
        """
        "precisionFarming":{"mode":"FERTILIZER",
        "nozzles":{"count":4,"activeCount":4,"individual":true,"active":[true,true,true,true],
        "amount":[1,0.75,0.4,0.15]}}
        """.trimIndent(),
      ).precisionFarming?.nozzles!!

    assertEquals(listOf(1f, 0.75f, 0.4f, 0.15f), nozzles.amount)
    assertEquals(0.4f, nozzles.amountAt(2))
    // Every nozzle open, so the saving is zero even though most of the boom is barely pulsing: the
    // two numbers answer different questions.
    assertEquals(0f, nozzles.saved)

    // Without PWM the mod omits the array entirely, and every nozzle reads as full flow.
    val plain =
      vehicle("\"precisionFarming\":{\"nozzles\":{\"count\":2,\"active\":[true,false]}}").precisionFarming?.nozzles!!
    assertTrue(plain.amount.isEmpty())
    assertEquals(1f, plain.amountAt(0))
  }

  @Test
  fun turnsTheActiveNozzleFractionIntoWhatSpotSprayingSaved() {
    val pf =
      vehicle(
        """
        "precisionFarming":{"mode":"OTHER","auto":false,"spotSpray":true,
        "nozzles":{"count":10,"activeCount":4,"active":[true,true,true,true,false,false,false,false,false,false]}}
        """.trimIndent(),
      ).precisionFarming!!

    assertEquals(true, pf.spotSpray)
    // PF multiplies the sprayer's usage by exactly this fraction, so the saving is the game's own
    // arithmetic rather than an estimate of ours.
    assertEquals(0.4f, pf.nozzles?.fraction)
    assertEquals(0.6f, pf.nozzles?.saved)

    // A machine with no spot-spray configuration at all reports nothing, which is not the same as
    // reporting it fitted and switched off.
    assertNull(vehicle("\"precisionFarming\":{\"mode\":\"OTHER\"}").precisionFarming?.spotSpray)
  }

  @Test
  fun aMultiplayerClientGetsTheAveragesWithoutTheStrip() {
    // PF only fills the sub-sections in on the server, so this is what a client sees. The readout has
    // to come off the averages; the strip is detail on top.
    val pf =
      vehicle(
        """"precisionFarming":{"mode":"LIME","auto":false,"ph":{"level":5.9,"target":6.8}}""",
      ).precisionFarming!!

    assertEquals(PfMode.LIME, pf.mode)
    assertEquals(pf.ph, pf.primary)
    assertNull(pf.nitrogen)
    assertTrue(pf.workAreas.isEmpty())
  }

  @Test
  fun toleratesTokensThisClientHasNeverHeardOf() {
    // A modded work-area type passes through as its token — the type is open (mods call
    // addWorkAreaType), so it is a string and an unknown one must not cost us the area.
    val vehicle =
      vehicle(
        """
        "workAreas":[{"index":1,"type":"FS25_SomeMod.WEEDER","active":true}],
        "workWidth":{"total":6,"sections":[{"active":true,"side":"STARBOARD"}]},
        "precisionFarming":{"mode":"POTASSIUM","auto":true}
        """.trimIndent(),
      )

    assertEquals("FS25_SomeMod.WEEDER", vehicle.workAreas.single().type)
    // The closed enums fall back to their defaults rather than failing the parse — one unknown token
    // must never freeze the whole telemetry feed at last-good-state.
    val section = vehicle.workWidth?.sections?.single()
    assertEquals(SectionSide.CENTER, section?.side)
    assertEquals(PfMode.OTHER, vehicle.precisionFarming?.mode)
  }
}
