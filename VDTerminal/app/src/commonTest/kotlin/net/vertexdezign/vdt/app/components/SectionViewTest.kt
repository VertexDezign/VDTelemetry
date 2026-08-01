package net.vertexdezign.vdt.app.components

import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.PfMode
import net.vertexdezign.vdt.model.PfNozzles
import net.vertexdezign.vdt.model.PfSubSection
import net.vertexdezign.vdt.model.PfValue
import net.vertexdezign.vdt.model.PfWorkArea
import net.vertexdezign.vdt.model.PrecisionFarming
import net.vertexdezign.vdt.model.SectionSide
import net.vertexdezign.vdt.model.WorkArea
import net.vertexdezign.vdt.model.WorkSection
import net.vertexdezign.vdt.model.WorkWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The section view's arithmetic — the parts that decide what a glance at the panel says.
 *
 * "Working" and "ready" are one word apart and mean different things to someone deciding whether to
 * lift the tool, and a slice that reads green because PF has no data for it would be a lie about the
 * ground. Both are wrong in ways that look right in a screenshot, which is what these pin down.
 */
class SectionViewTest {
  private fun area(active: Boolean = true, processing: Boolean = false, type: String? = "SPRAYER") =
    WorkArea(index = 1, type = type, active = active, processing = processing)

  @Test
  fun tellsWorkingApartFromMerelyReady() {
    // Lowered, in gear, section on — but nothing has gone under it in the last 200 ms.
    val ready = workAreaStatus(listOf(area()))!!
    assertEquals("Sprayer · ready", ready.label)
    assertEquals(VdtColors.Amber, ready.color)

    val working = workAreaStatus(listOf(area(processing = true)))!!
    assertEquals("Sprayer · working", working.label)
    assertEquals(VdtColors.Green, working.color)

    // Raised, or driving backwards, or switched off: the tool covers nothing.
    val off = workAreaStatus(listOf(area(active = false)))!!
    assertEquals("Sprayer · off", off.label)
    assertEquals(VdtColors.Gray, off.color)
  }

  @Test
  fun oneWorkingAreaMakesTheWholeToolWorking() {
    // A cultivator with a sowing area behind it reports several; the tool is working if any part is.
    val status = workAreaStatus(listOf(area(type = "CULTIVATOR"), area(type = "SOWINGMACHINE", processing = true)))!!
    assertEquals(VdtColors.Green, status.color)
    // Named by what it leads with, not by whichever part happens to be busy.
    assertTrue(status.label.startsWith("Cultivator"))
  }

  @Test
  fun aToolWithNoWorkAreasHasNoStatusLine() {
    assertNull(workAreaStatus(emptyList()))
    // A modded type with no name still gets a line — the state is the point, the word is decoration.
    assertEquals("Tool · ready", workAreaStatus(listOf(area(type = null)))!!.label)
  }

  @Test
  fun aSliceWithoutDataIsGreyRatherThanGood() {
    // PF's isValid goes false off the field and on ground the soil sample has not uncovered. Ramping
    // that to green would paint "nothing needed here" over ground we know nothing about.
    val blank = PfSubSection(valid = false, n = 0f, nTarget = 0f)
    assertEquals(VdtColors.Gray, sliceColor(blank, PfMode.FERTILIZER))
  }

  @Test
  fun rampsASliceFromRedAtEmptyToGreenAtTarget() {
    fun slice(level: Float) = PfSubSection(valid = true, n = level, nTarget = 100f)
    assertEquals(VdtColors.Red, sliceColor(slice(0f), PfMode.FERTILIZER))
    assertEquals(VdtColors.Amber, sliceColor(slice(50f), PfMode.FERTILIZER))
    assertEquals(VdtColors.Green, sliceColor(slice(100f), PfMode.FERTILIZER))
    // Past target is not more than green, and never wraps back toward red.
    assertEquals(VdtColors.Green, sliceColor(slice(400f), PfMode.FERTILIZER))
    // Nothing needed here at all: the target is zero, which is a fine place to be, not a divide by it.
    assertEquals(VdtColors.Green, sliceColor(PfSubSection(valid = true, n = 0f, nTarget = 0f), PfMode.FERTILIZER))
  }

  @Test
  fun readsTheModeThatMachineIsIn() {
    // The same slice means different things in the two tanks: lime is about pH, fertilizer about N.
    val slice = PfSubSection(valid = true, n = 0f, nTarget = 100f, ph = 6.8f, phTarget = 6.8f)
    assertEquals(VdtColors.Red, sliceColor(slice, PfMode.FERTILIZER))
    assertEquals(VdtColors.Green, sliceColor(slice, PfMode.LIME))
  }

  @Test
  fun anAllInvalidStripIsNoStripAtAll() {
    // A sprayer with herbicide in the tank: PF fills sub-sections in only while liming or
    // fertilizing, so every slice reads "no data" and the strip would be a row of grey cells that
    // looks broken rather than absent.
    val herbicide =
      PrecisionFarming(
        mode = PfMode.OTHER,
        workAreas = listOf(PfWorkArea(1, List(6) { PfSubSection(valid = false) })),
      )
    assertTrue(activeStrip(herbicide).isEmpty())

    // One valid slice is enough — a boom crossing the field edge is exactly the case worth drawing.
    val crossing =
      PrecisionFarming(
        mode = PfMode.FERTILIZER,
        workAreas = listOf(PfWorkArea(1, listOf(PfSubSection(valid = false), PfSubSection(valid = true)))),
      )
    assertEquals(2, activeStrip(crossing).size)
    assertTrue(activeStrip(null).isEmpty())
  }

  @Test
  fun prefersTheNozzlesOverAShutoffBarPfHasFrozen() {
    // PF removes the base game's work-width controls on the machines it drives the nozzles of, so
    // their sections sit all-on forever. The nozzle states are the live answer, and they already
    // account for the sections.
    val sections = List(4) { WorkSection(active = true, side = SectionSide.LEFT) }
    val pf = PrecisionFarming(nozzles = PfNozzles(count = 3, activeCount = 2, active = listOf(true, true, false)))
    val bar = sprayBar(WorkWidth(sections = sections), pf)
    assertEquals(SprayBar.Nozzles(listOf(true, true, false)), bar)

    // A tool PF drives no nozzles on keeps the shutoff bar, which on those machines does move.
    assertEquals(SprayBar.Sections(sections), sprayBar(WorkWidth(sections = sections), PrecisionFarming()))
    assertEquals(SprayBar.Sections(sections), sprayBar(WorkWidth(sections = sections), null))

    // And a tool with neither gets no bar rather than an empty one.
    assertNull(sprayBar(null, null))
    assertNull(sprayBar(WorkWidth(), PrecisionFarming(nozzles = PfNozzles())))
  }

  @Test
  fun reportsHowMuchOfTheBoomIsSpraying() {
    assertEquals(0.5f, PfNozzles(count = 24, activeCount = 12).fraction)
    // Never a divide by zero on a machine that reported the subtree but no nozzles.
    assertEquals(0f, PfNozzles().fraction)
  }

  @Test
  fun writesTheRateAsAReadingAndATarget() {
    assertEquals("45 → 90 kg/ha", rateLabel(PfMode.FERTILIZER, PfValue(45f, 90f, "kg/ha")))
    // At or above target there is nothing to aim for, so the arrow goes away rather than pointing back.
    assertEquals("90 kg/ha", rateLabel(PfMode.FERTILIZER, PfValue(90f, 90f, "kg/ha")))
    assertEquals("95 kg/ha", rateLabel(PfMode.FERTILIZER, PfValue(95f, 90f, "kg/ha")))
    // pH is a decimal, and carries no unit of its own.
    assertEquals("5.9 → 6.8", rateLabel(PfMode.LIME, PfValue(5.9f, 6.8f)))
  }
}
