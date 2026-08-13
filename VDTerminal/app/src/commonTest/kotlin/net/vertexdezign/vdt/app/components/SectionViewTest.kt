package net.vertexdezign.vdt.app.components

import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.PfManual
import net.vertexdezign.vdt.model.PfMode
import net.vertexdezign.vdt.model.PfNozzles
import net.vertexdezign.vdt.model.PfSubSection
import net.vertexdezign.vdt.model.PfValue
import net.vertexdezign.vdt.model.PfWorkArea
import net.vertexdezign.vdt.model.PrecisionFarming
import net.vertexdezign.vdt.model.SectionSide
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.WorkArea
import net.vertexdezign.vdt.model.WorkSection
import net.vertexdezign.vdt.model.WorkWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    assertEquals(VdtColors.TextDisabled, off.color)
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
    val nozzles = PfNozzles(count = 3, activeCount = 2, active = listOf(true, true, false))
    val bar = sprayBar(WorkWidth(sections = sections), PrecisionFarming(nozzles = nozzles))
    assertEquals(SprayBar.Nozzles(nozzles), bar)

    // A tool PF drives no nozzles on keeps the shutoff bar, which on those machines does move.
    assertEquals(SprayBar.Sections(sections), sprayBar(WorkWidth(sections = sections), PrecisionFarming()))
    assertEquals(SprayBar.Sections(sections), sprayBar(WorkWidth(sections = sections), null))

    // And a tool with neither gets no bar rather than an empty one.
    assertNull(sprayBar(null, null))
    assertNull(sprayBar(WorkWidth(), PrecisionFarming(nozzles = PfNozzles())))
  }

  @Test
  fun shadesAPulsingNozzleWithoutLettingItLookShut() {
    // A pulse-width-modulation boom mid-turn: the inside is dialled right down and the outside is wide
    // open, and every one of them is still active. The floor is what keeps the quietest one distinct
    // from the shut cell beside it.
    assertEquals(1f, nozzleAlpha(1f))
    assertTrue(nozzleAlpha(0f) >= 0.4f, "a pulsing nozzle must not read as a closed one")
    assertTrue(nozzleAlpha(0.2f) < nozzleAlpha(0.8f), "the gradient across a turning boom is the point")
    // Out-of-range values clamp instead of producing an invalid colour.
    assertEquals(nozzleAlpha(0f), nozzleAlpha(-1f))
    assertEquals(nozzleAlpha(1f), nozzleAlpha(4f))

    // Absent amounts mean full flow, which is every machine without PWM.
    val plain = PfNozzles(count = 2, activeCount = 2, active = listOf(true, true))
    assertEquals(1f, plain.amountAt(0))
    assertEquals(1f, plain.amountAt(9))
    assertEquals(0.35f, PfNozzles(amount = listOf(0.35f)).amountAt(0))
  }

  @Test
  fun reportsHowMuchOfTheBoomIsSpraying() {
    assertEquals(0.5f, PfNozzles(count = 24, activeCount = 12).fraction)
    assertEquals(0.5f, PfNozzles(count = 24, activeCount = 12).saved)
    // Never a divide by zero on a machine that reported the subtree but no nozzles.
    assertEquals(0f, PfNozzles().fraction)
  }

  @Test
  fun callsItASavingOnlyWhereItIsOne() {
    val nozzles = PfNozzles(count = 10, activeCount = 4, active = List(10) { it < 4 })
    assertEquals(nozzles, spotNozzles(PrecisionFarming(mode = PfMode.OTHER, spotSpray = true, nozzles = nozzles)))

    // Without the spot-spray config the closed nozzles are folded-away boom: less liquid over less
    // ground, which is not a saving and must not be called one.
    assertNull(spotNozzles(PrecisionFarming(spotSpray = false, nozzles = nozzles)))
    assertNull(spotNozzles(PrecisionFarming(nozzles = nozzles)))
    assertNull(spotNozzles(PrecisionFarming(spotSpray = true)))
  }

  @Test
  fun holdsTheRowsOpenThroughTheThingsThatFlicker() {
    // The flicker this fixes: getIsWorkAreaProcessing is only true within 200 ms of the tool changing
    // ground, and a spot sprayer over clean crop changes nothing — so it toggles with the weeds. What
    // decides whether the saving means anything is the boom being down and moving, which is `active`.
    val spotting = workAreaStatus(listOf(area(active = true, processing = false)))!!
    assertTrue(spotting.active)
    assertFalse(spotting.working)
    assertFalse(workAreaStatus(listOf(area(active = false)))!!.active)

    // The rate line's room is claimed from the mode, not from having a number this instant: the rates
    // go absent off the field, and a slot that followed the value would blink at every headland.
    assertTrue(readoutSlotShown(PrecisionFarming(mode = PfMode.FERTILIZER)))
    assertTrue(readoutSlotShown(PrecisionFarming(mode = PfMode.LIME)))
    // Herbicide keeps no rates, so the row is only worth holding for a spot-spray saving.
    assertFalse(readoutSlotShown(PrecisionFarming(mode = PfMode.OTHER)))
    assertTrue(
      readoutSlotShown(PrecisionFarming(mode = PfMode.OTHER, spotSpray = true, nozzles = PfNozzles(count = 4))),
    )
    assertFalse(readoutSlotShown(null))

    // Same for the strip: held through the moments nothing is valid (a headland, unsampled ground),
    // never claimed in a mode where PF computes no slices at all.
    val slices = listOf(PfWorkArea(1, listOf(PfSubSection(valid = false))))
    assertTrue(stripSlotShown(PrecisionFarming(mode = PfMode.FERTILIZER, workAreas = slices)))
    assertFalse(stripSlotShown(PrecisionFarming(mode = PfMode.OTHER, workAreas = slices)))
    // And not claimed at all on a multiplayer client, which never receives sub-sections.
    assertFalse(stripSlotShown(PrecisionFarming(mode = PfMode.FERTILIZER)))
  }

  @Test
  fun findsTheBoomWhereverItIsHitched() {
    val sections = List(4) { WorkSection(active = true, side = SectionSide.LEFT) }
    val sprayer =
      Implement(
        workWidth = WorkWidth(total = 18f, sections = sections),
        workAreas = listOf(area(type = "SPRAYER")),
      )

    // The ordinary case: the tractor has no boom of its own, the tool behind it does.
    val rig = Vehicle(implement = listOf(sprayer))
    val boom = boomOf(rig)!!
    assertEquals(SprayBar.Sections(sections), boom.bar)
    assertEquals(18f, boom.width)
    assertTrue(boom.status!!.active)

    // Hitched behind another implement — a rig is walked to the end, not one deep.
    assertEquals(boom.bar, boomOf(Vehicle(implement = listOf(Implement(implement = listOf(sprayer)))))!!.bar)

    // A self-propelled sprayer IS the boom, and it is described by its own rather than by a trailer.
    val selfPropelled =
      Vehicle(
        workWidth = WorkWidth(total = 36f, sections = List(2) { WorkSection(active = true) }),
        workAreas = listOf(area(type = "SPRAYER")),
        implement = listOf(sprayer),
      )
    assertEquals(36f, boomOf(selfPropelled)!!.width)

    // Nothing on the rig works ground: no strip at all rather than an empty bar.
    assertNull(boomOf(Vehicle(implement = listOf(Implement()))))
    assertNull(boomOf(null))
  }

  @Test
  fun takesTheNozzleCountAndWidthFromTheToolTheBarCameFrom() {
    // PF's nozzles win the bar (the sections behind them are frozen), and the count beside it must be
    // that same tool's — a rig where the numbers come from different implements would read as one boom.
    val nozzles = PfNozzles(count = 24, activeCount = 9, active = List(24) { it < 9 })
    val rig =
      Vehicle(
        implement =
        listOf(
          Implement(
            workWidth = WorkWidth(total = 24f, sections = List(6) { WorkSection(active = true) }),
            workAreas = listOf(area(type = "SPRAYER", processing = true)),
            precisionFarming = PrecisionFarming(nozzles = nozzles),
          ),
        ),
      )
    val boom = boomOf(rig)!!
    assertEquals(SprayBar.Nozzles(nozzles), boom.bar)
    assertEquals(nozzles, boom.nozzles)
    assertEquals(24f, boom.width)
    assertEquals(VdtColors.Green, boom.status!!.color)

    // A tool that reports no aggregate width falls back to a work area that is actually down, the same
    // rule the panel's status line uses — a raised implement's width is not the width being worked.
    val folding =
      Implement(
        workWidth = WorkWidth(sections = List(3) { WorkSection(active = true) }),
        workAreas = listOf(area(active = false).copy(width = 12f), area(active = true).copy(width = 6f)),
      )
    assertEquals(6f, boomOf(Vehicle(implement = listOf(folding)))!!.width)
    // And nothing at all rather than a zero: the strip drops the width instead of claiming "0 m".
    assertNull(boomOf(Vehicle(implement = listOf(folding.copy(workAreas = listOf(area())))))!!.width)
  }

  @Test
  fun writesTheRateAsAReadingAndATarget() {
    assertEquals(
      RateFigures("45", "90", "kg/ha"),
      rateFigures(PfMode.FERTILIZER, PfValue(45f, 90f, "kg/ha"), null),
    )
    // At or above target there is nothing to aim for, so the arrow goes away rather than pointing back.
    assertEquals(RateFigures("90", null, "kg/ha"), rateFigures(PfMode.FERTILIZER, PfValue(90f, 90f, "kg/ha"), null))
    assertEquals(RateFigures("95", null, "kg/ha"), rateFigures(PfMode.FERTILIZER, PfValue(95f, 90f, "kg/ha"), null))
    // pH is a decimal, and carries no unit of its own.
    assertEquals(RateFigures("5.9", "6.8", null), rateFigures(PfMode.LIME, PfValue(5.9f, 6.8f), null))
  }

  // The difference between the modes, and the reason the readout could not simply keep printing the
  // target: in manual the tool applies a fixed step whatever the ground says, so where it *leaves* the
  // soil is the reading plus that step — nothing to do with the map's target.
  @Test
  fun readsTheManualStepRatherThanTheTargetWhenTheRateIsSetByHand() {
    val manual = PfManual(step = 3, min = 1, max = 7, change = 15f, rate = 600f, rateUnit = "kg/ha")
    assertEquals(
      RateFigures("45", "60", "kg/ha"),
      rateFigures(PfMode.FERTILIZER, PfValue(45f, 90f, "kg/ha"), manual),
    )
    // Overshooting the target is a real outcome of choosing your own rate, and is shown, not clamped.
    assertEquals(
      RateFigures("85", "100", "kg/ha"),
      rateFigures(PfMode.FERTILIZER, PfValue(85f, 90f, "kg/ha"), manual),
    )
    // A step the machine reports no change for moves nothing, so the reading stands alone.
    assertEquals(
      RateFigures("45", null, "kg/ha"),
      rateFigures(PfMode.FERTILIZER, PfValue(45f, 90f, "kg/ha"), manual.copy(change = 0f)),
    )
  }

  @Test
  fun stepsWithinTheMachinesOwnBounds() {
    val manual = PfManual(step = 1, min = 1, max = 3)
    assertFalse(manual.canStep(-1))
    assertTrue(manual.canStep(1))
    assertEquals(1, manual.stepped(-1))
    assertEquals(2, manual.stepped(1))

    val top = manual.copy(step = 3)
    assertFalse(top.canStep(1))
    assertEquals(3, top.stepped(1))
  }

  @Test
  fun aSlotDrawsTheChainMemberThatIsActuallyWorking() {
    // The rig this was reported for: a slurry tanker with a dribble bar behind it. The barrel reports
    // no work areas at all (the base game shuts them off while a tool is attached) and a Precision
    // Farming block with a mode and no readings — so a panel reading the head drew an empty line
    // while the machine 21 m wide behind it had everything.
    val bar =
      Implement(
        workAreas = listOf(area().copy(width = 21f)),
        precisionFarming = PrecisionFarming(mode = PfMode.FERTILIZER, nitrogen = PfValue(45f, 90f, "kg/ha")),
      )
    val barrel = Implement(precisionFarming = PrecisionFarming(mode = PfMode.FERTILIZER), implement = listOf(bar))
    assertEquals(bar, sectionMember(barrel))

    // Capability is not the test: the barrel has a PF block, and stopping there is the bug.
    assertEquals(PfMode.FERTILIZER, barrel.precisionFarming?.mode)
    assertNull(barrel.precisionFarming?.primary)

    // A head with anything of its own wins outright — every ordinary implement, unaffected.
    val cultivator = Implement(workAreas = listOf(area()), implement = listOf(bar))
    assertEquals(cultivator, sectionMember(cultivator))
    // Shutoff sections count as much as work areas do.
    val folding =
      Implement(workWidth = WorkWidth(sections = listOf(WorkSection(active = true))), implement = listOf(bar))
    assertEquals(folding, sectionMember(folding))

    // And a chain with nothing anywhere stays on the head rather than picking an arbitrary trailer:
    // the view draws nothing either way, and the slot should still describe what is hitched to it.
    val trailer = Implement(implement = listOf(Implement()))
    assertEquals(trailer, sectionMember(trailer))
  }

  @Test
  fun aPrimeMoverDoesNotRedrawTheRatesOfTheToolItIsDriving() {
    // The Vredo VT5536 with an injecting disc harrow, which is where this was reported. The mod's
    // `rateSource` gives a machine that applies nothing itself the rates of the one it is driving, so
    // the vehicle's block is byte-for-byte the harrow's — and the harrow is hitched at BACK with a
    // slot of its own, so both tiles drew the same reading and the same live step buttons.
    val rates = PrecisionFarming(mode = PfMode.FERTILIZER, nitrogen = PfValue(205f, 220f, "kg/ha"))
    val harrow = Implement(position = "BACK", workAreas = listOf(area()), precisionFarming = rates)
    val vredo = Vehicle(precisionFarming = rates, implement = listOf(harrow))
    assertNull(ownRates(vredo))
    // The tool keeps them, so the rig still shows the rates exactly once.
    assertEquals(rates, sectionMember(harrow).precisionFarming)

    // A self-propelled sprayer works its own ground, so it keeps them: the Rogator's own SPRAYER
    // area is what separates the two, never the PF block, which is the thing that may be borrowed.
    val rogator = Vehicle(workAreas = listOf(area()), precisionFarming = rates)
    assertEquals(rates, ownRates(rogator))
    // Shutoff sections count the same way work areas do, as everywhere else in this view.
    val sectioned =
      Vehicle(workWidth = WorkWidth(sections = listOf(WorkSection(active = true))), precisionFarming = rates)
    assertEquals(rates, ownRates(sectioned))

    // Nothing to borrow is still nothing to draw.
    assertNull(ownRates(Vehicle(workAreas = listOf(area()))))
  }

  @Test
  fun namesTheModeFromTheModeFlag() {
    val manual = PfManual(step = 3, min = 1, max = 7)
    assertEquals("AUTO", modeLabel(auto = true, manual = null))
    assertEquals("MAN 3/7", modeLabel(auto = false, manual = manual))
    // A tool PF gave us no step for is still in manual — the chip must not read AUTO there. The mod
    // withholds the step in a mode with no rates, which is exactly when this happens.
    assertEquals("MAN", modeLabel(auto = false, manual = null))
    // And auto wins over a step that is simply the last one set: PF stores it in either mode.
    assertEquals("AUTO", modeLabel(auto = true, manual = manual))
  }

  @Test
  fun printsWhatTheManualPassCostsInProduct() {
    val manual = PrecisionFarming(auto = false)
    assertEquals("600 kg/ha", rateCost(manual, PfManual(rate = 600f, rateUnit = "kg/ha")))
    // A slurry tanker is set in a couple of cubic metres per hectare; rounding that to a whole number
    // would throw the setting away.
    assertEquals("2.4 m³/ha", rateCost(manual, PfManual(rate = 2.4f, rateUnit = "m³/ha")))
    // Nothing in the tank, nothing to cost it against — the step is still real, the price is not ours.
    assertNull(rateCost(manual, PfManual(rate = null, rateUnit = "kg/ha")))
    assertNull(rateCost(manual, PfManual(rate = 600f, rateUnit = null)))
  }

  @Test
  fun printsTheLiveRateInAutoWhereNoStepDescribesIt() {
    // Auto: the tool reads the map and picks its own rate per square metre, so the only figure there
    // is is what is actually coming out. The caller has already filtered `manual` to null here.
    val auto = PrecisionFarming(auto = true, rate = 3.2f, rateUnit = "m³/ha")
    assertEquals("3.2 m³/ha", rateCost(auto, null))

    // The boom comes up and PF stops refreshing the field, so the mod withholds it rather than leave
    // the rate of the pass that just ended on screen looking live.
    assertNull(rateCost(PrecisionFarming(auto = true), null))

    // In manual the nominal step cost wins over the live figure, exactly as PF's own HUD does: it is
    // the number the step is chosen by, and it holds with the tool stopped.
    val running = PrecisionFarming(auto = false, rate = 3.2f, rateUnit = "m³/ha")
    assertEquals("600 kg/ha", rateCost(running, PfManual(rate = 600f, rateUnit = "kg/ha")))
    // …but a step the mod could not cost falls through to what the machine is really applying,
    // rather than printing nothing while the boom is visibly running.
    assertEquals("3.2 m³/ha", rateCost(running, PfManual(rate = null)))
  }
}
