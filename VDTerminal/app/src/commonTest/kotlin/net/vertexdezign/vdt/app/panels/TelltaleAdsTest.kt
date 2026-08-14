package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.Ads
import net.vertexdezign.vdt.model.AdsLamp
import net.vertexdezign.vdt.model.AdsLamps
import net.vertexdezign.vdt.model.AdsService
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.Motor
import net.vertexdezign.vdt.model.MotorFillUnits
import net.vertexdezign.vdt.model.MotorState
import net.vertexdezign.vdt.model.Temperatur
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.Wearable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The six maintenance lamps once Advanced Damage System is feeding them.
 *
 * Two things here are easy to get wrong and expensive when wrong. A lamp the machine is too old to
 * have must stay *absent* rather than becoming an unlit claim about a dashboard that never had it —
 * the same rule the drivetrain lamps follow. And severity has to reach the band through more than
 * hue, because a ladder told apart by colour alone is a ladder some people cannot read.
 */
class TelltaleAdsTest {
  private fun withLamps(lamps: AdsLamps) = Vehicle(ads = Ads(lamps = lamps))

  @Test
  fun adsLampsLightTheMaintenanceBand() {
    val vehicle = withLamps(
      AdsLamps(
        engine = AdsLamp.CRIT,
        warning = AdsLamp.WARN,
        brakes = AdsLamp.OFF,
        battery = AdsLamp.WARN,
        coolant = AdsLamp.COLD,
        service = AdsLamp.OFF,
      ),
    )
    assertEquals(true, Telltale.EngineWarning.stateIn(vehicle))
    assertEquals(true, Telltale.GeneralWarning.stateIn(vehicle))
    assertEquals(false, Telltale.BrakeSystem.stateIn(vehicle))
    assertEquals(true, Telltale.Battery.stateIn(vehicle))
    // Cold is a lamp that is ON. It is not a fault, but a dark lamp would be the wrong claim.
    assertEquals(true, Telltale.Temperature.stateIn(vehicle))
    assertEquals(false, Telltale.Service.stateIn(vehicle))
  }

  @Test
  fun aLampTheMachineIsTooOldToHaveStaysAbsent() {
    // What ADS reports for a 1960s tractor: a battery and a coolant lamp, and nothing else.
    val vintage = withLamps(AdsLamps(battery = AdsLamp.OFF, coolant = AdsLamp.OFF))
    assertEquals(false, Telltale.Battery.stateIn(vintage))
    assertEquals(false, Telltale.Temperature.stateIn(vintage))
    assertNull(Telltale.EngineWarning.stateIn(vintage), "a 1960s tractor has no engine-fault lamp")
    assertNull(Telltale.BrakeSystem.stateIn(vintage))
    assertNull(Telltale.Service.stateIn(vintage))
  }

  @Test
  fun severityIsCarriedByColourAndByFlash() {
    val critical = withLamps(AdsLamps(engine = AdsLamp.CRIT))
    val warning = withLamps(AdsLamps(engine = AdsLamp.WARN))
    val cold = withLamps(AdsLamps(coolant = AdsLamp.COLD))

    assertEquals(ClusterColors.Warn, Telltale.EngineWarning.colourIn(critical))
    assertEquals(ClusterColors.Set, Telltale.EngineWarning.colourIn(warning))
    assertEquals(ClusterColors.Beam, Telltale.Temperature.colourIn(cold))
    // Three severities, three colours — and none of them shared.
    assertNotEquals(Telltale.EngineWarning.colourIn(critical), Telltale.EngineWarning.colourIn(warning))

    // ... and the second channel, so the ladder does not rest on hue alone.
    assertTrue(Telltale.EngineWarning.blinksIn(critical), "a critical lamp is the one thing that flashes")
    assertFalse(Telltale.EngineWarning.blinksIn(warning))
    assertFalse(Telltale.Temperature.blinksIn(cold))
  }

  @Test
  fun theSignalsStillFlashWhateverAdsSays() {
    // blinksIn must not have quietly become "critical only" — the indicators were flashing first.
    assertTrue(Telltale.TurnLeft.blinksIn(Vehicle()))
    assertTrue(Telltale.TurnRight.blinksIn(withLamps(AdsLamps(engine = AdsLamp.OFF))))
  }

  @Test
  fun theBulbCheckLightsBothHalvesOfTheBandTogether() {
    // What ADS sends while the starter turns: every lamp the machine has, lit. The band's own check
    // (see [lampCheck]) reads the same motor state out of the same sample, so the six and their
    // neighbours come on and go out as one — a check timed here instead could have half the band
    // still proving itself while the other half had gone back to reporting.
    val cranking = Vehicle(
      motor = Motor(state = MotorState.STARTING),
      ads = Ads(lamps = AdsLamps(engine = AdsLamp.WARN, coolant = AdsLamp.WARN)),
    )
    assertTrue(lampCheck(cranking))
    assertEquals(true, Telltale.EngineWarning.stateIn(cranking))
    assertEquals(true, Telltale.Temperature.stateIn(cranking))
  }

  @Test
  fun aColdCoolantLampIsItsOwnSymbol() {
    val cold = withLamps(AdsLamps(coolant = AdsLamp.COLD))
    val hot = withLamps(AdsLamps(coolant = AdsLamp.CRIT))
    assertEquals(ClusterIcons.TemperatureCold, Telltale.Temperature.iconIn(cold))
    assertEquals(ClusterIcons.Temperature, Telltale.Temperature.iconIn(hot))
    // Not warmed up and boiling must not be one shape in two colours.
    assertNotEquals(Telltale.Temperature.iconIn(cold), Telltale.Temperature.iconIn(hot))
  }

  @Test
  fun withoutAdsTheTwoLampsTheBaseGameCanSupportStillWork() {
    // No `ads` at all: the coolant gauge and vanilla damage are what these were lit from before, and
    // a game without the mod has no reason to lose them.
    val hot = Vehicle(motor = Motor(temperatur = Temperatur(value = 118, min = 20, max = 120)))
    assertEquals(true, Telltale.Temperature.stateIn(hot))
    assertEquals(true, Telltale.GeneralWarning.stateIn(Vehicle(wearable = Wearable(damage = 80))))
    // The four with no base-game source stay absent.
    assertNull(Telltale.EngineWarning.stateIn(hot))
    assertNull(Telltale.Battery.stateIn(hot))
    assertNull(Telltale.BrakeSystem.stateIn(hot))
    assertNull(Telltale.Service.stateIn(hot))
  }

  @Test
  fun adsOutranksTheBaseGameDerivationItReplaces() {
    // ADS pins vanilla damage to 0 on a vehicle it manages, so the derived lamp would read "fine"
    // exactly when the mod is saying otherwise.
    val vehicle = Vehicle(
      wearable = Wearable(damage = 0),
      ads = Ads(lamps = AdsLamps(warning = AdsLamp.CRIT)),
    )
    assertEquals(true, Telltale.GeneralWarning.stateIn(vehicle))
  }

  @Test
  fun theBaseGameFallbacksDoNotPutBackALampAdsSaysIsNotThere() {
    // The vintage tractor again, this time boiling and battered, so both fallbacks have something to
    // say. They must stay quiet: ADS has just told us this dashboard has neither lamp, and lighting
    // one from the gauge would be inventing it.
    val vintage = Vehicle(
      motor = Motor(temperatur = Temperatur(value = 118, min = 20, max = 120)),
      wearable = Wearable(damage = 80),
      ads = Ads(lamps = AdsLamps(battery = AdsLamp.OFF)),
    )
    assertNull(Telltale.Temperature.stateIn(vintage))
    assertNull(Telltale.GeneralWarning.stateIn(vintage))
    assertEquals(false, Telltale.Battery.stateIn(vintage), "the one lamp it does have still reports")
    // Same again where ADS is there but reports no lamps at all — a machine it manages and has no
    // dashboard for is still not a machine we can guess a dashboard for.
    assertNull(Telltale.Temperature.stateIn(vintage.copy(ads = Ads())))
  }
}

/** The CVT temperature's bar on the level strip, which only some machines have. */
class ClusterLevelsAdsTest {
  private val coolant = Motor(temperatur = Temperatur(value = 88, min = 20, max = 120))

  @Test
  fun theTransmissionBarSitsBesideTheCoolantOneAndAheadOfTheTanks() {
    val vehicle = Vehicle(
      motor = coolant.copy(fillUnits = MotorFillUnits(fuel = FillUnit(value = 100f, capacity = 200))),
      ads = Ads(transmissionTemperatur = Temperatur(value = 71, min = 20, max = 120)),
    )
    // Condition before contents: adding it must not move the fuel bar the driver has learned.
    assertEquals(listOf("TEMP", "TRANS", "FUEL"), levelsOf(vehicle).map { it.label })
    assertEquals(0.51f, levelsOf(vehicle)[1].fraction)
    // A temperature is in trouble at the top of its gauge, not the bottom.
    assertEquals(Danger.High, levelsOf(vehicle)[1].danger)
  }

  @Test
  fun aMachineWithNoCvtGetsNoSecondBar() {
    assertEquals(listOf("TEMP"), levelsOf(Vehicle(motor = coolant)).map { it.label })
    assertEquals(listOf("TEMP"), levelsOf(Vehicle(motor = coolant, ads = Ads())).map { it.label })
  }

  @Test
  fun theTwoTemperatureBarsAreToldApartByTheirGlyphs() {
    // The strip draws icons only — its labels are for the screen reader — so two identical
    // thermometers would be two bars nobody could tell apart.
    val vehicle = Vehicle(
      motor = coolant,
      ads = Ads(transmissionTemperatur = Temperatur(value = 71, min = 20, max = 120)),
    )
    val icons = levelsOf(vehicle).map { it.icon }
    assertEquals(icons.toSet().size, icons.size, "two bars share a glyph")
  }
}

/** The service tile's own arithmetic: what "due" means. */
class ClusterServiceTest {
  @Test
  fun theIntervalFractionIsHoursOverInterval() {
    assertEquals(0.5f, AdsService(hours = 2.5f, interval = 5f).fraction)
    assertTrue(AdsService(hours = 6f, interval = 5f).fraction > 1f, "past the interval is overdue")
    // A machine ADS reports no interval for must not divide by zero.
    assertEquals(0f, AdsService(hours = 3f, interval = 0f).fraction)
  }
}
