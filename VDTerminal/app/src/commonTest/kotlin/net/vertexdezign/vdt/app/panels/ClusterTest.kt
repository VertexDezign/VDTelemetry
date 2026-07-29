package net.vertexdezign.vdt.app.panels

import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.model.DiffLock
import net.vertexdezign.vdt.model.DriveDirection
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.FillUnits
import net.vertexdezign.vdt.model.Gear
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Indicator
import net.vertexdezign.vdt.model.Light
import net.vertexdezign.vdt.model.Lights
import net.vertexdezign.vdt.model.Motor
import net.vertexdezign.vdt.model.MotorFillUnits
import net.vertexdezign.vdt.model.Speed
import net.vertexdezign.vdt.model.Temperatur
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.Wearable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The telltale band's one hard rule: a lamp we have no state for is **absent**, not off.
 *
 * An unlit lamp is a claim — "the diff lock is open" — and for the drivetrain trio that claim is only
 * ours to make when Enhanced Vehicle is installed and managing this vehicle. Getting this wrong shows
 * up as a cluster that quietly lies about the machine, which is exactly the class of bug nobody
 * notices until it matters.
 */
class TelltaleStateTest {
  @Test
  fun aLampWithNoDataIsAbsentRatherThanOff() {
    val bare = Vehicle()
    for (lamp in Telltale.entries) {
      assertNull(lamp.stateIn(bare), "${lamp.key} claimed a state on a vehicle that reports nothing")
    }
  }

  @Test
  fun theDrivetrainLampsFollowEnhancedVehiclesNulls() {
    // A rear-only diff lock: the front side never arrives, so its lamp must stay absent while the
    // rear one reports honestly.
    val vehicle = Vehicle(motor = Motor(diffLock = DiffLock(front = null, back = false), awd = true))
    assertNull(Telltale.DiffLockFront.stateIn(vehicle))
    assertEquals(false, Telltale.DiffLockRear.stateIn(vehicle))
    assertEquals(true, Telltale.Awd.stateIn(vehicle))
    assertNull(Telltale.ParkingBrake.stateIn(vehicle))
  }

  @Test
  fun theLightLampsReadTheirSubtrees() {
    val vehicle =
      Vehicle(
        lights = Lights(indicator = Indicator(left = true), light = Light(highBeam = true), beaconLight = false),
      )
    assertEquals(true, Telltale.TurnLeft.stateIn(vehicle))
    assertEquals(false, Telltale.TurnRight.stateIn(vehicle))
    assertEquals(true, Telltale.HighBeam.stateIn(vehicle))
    assertEquals(false, Telltale.Beacon.stateIn(vehicle))
    // No workLight subtree at all — absent, not "both off".
    assertNull(Telltale.WorkFront.stateIn(vehicle))
  }

  @Test
  fun everyLampKeyIsUniqueAndStable() {
    // Keys are persisted in a tile's config, so a collision or a rename silently drops lamps from
    // bands users have already configured.
    val keys = Telltale.entries.map { it.key }
    assertEquals(keys.size, keys.toSet().size, "duplicate telltale key in $keys")
  }
}

/** The one derived lamp, and the thresholds we picked for it. */
class EngineWarningTest {
  private fun tempAt(value: Int) = Vehicle(motor = Motor(temperatur = Temperatur(value = value, min = 20, max = 120)))

  @Test
  fun aWorkingEngineIsNotAWarning() {
    // 89°C is a combine under load in the committed captures; the threshold has to clear that.
    assertEquals(false, engineWarning(tempAt(89)))
    assertEquals(false, engineWarning(tempAt(37)))
  }

  @Test
  fun anOverheatingEngineIs() {
    // 90% of the 20..120 span is 110.
    assertEquals(true, engineWarning(tempAt(110)))
    assertEquals(true, engineWarning(tempAt(120)))
  }

  @Test
  fun damageAloneRaisesIt() {
    assertEquals(true, engineWarning(Vehicle(wearable = Wearable(damage = 80))))
    assertEquals(false, engineWarning(Vehicle(wearable = Wearable(damage = 10))))
  }

  @Test
  fun nothingReportedMeansNoLamp() {
    assertNull(engineWarning(Vehicle()))
    // A degenerate gauge (no span) can't be read as hot, and mustn't be read as cold either.
    assertNull(engineWarning(Vehicle(motor = Motor(temperatur = Temperatur(value = 50, min = 0, max = 0)))))
  }
}

/** What the big readout puts on each line. */
class ClusterReadoutTest {
  @Test
  fun theGearCarriesItsGroupWhenThereIsOne() {
    assertEquals("E2", gearText(Vehicle(motor = Motor(gear = Gear(value = "2", group = "E")))))
    assertEquals("D", gearText(Vehicle(motor = Motor(gear = Gear(value = "D")))))
    assertEquals("N", gearText(Vehicle(motor = Motor(gear = Gear(value = "2", group = "E", isNeutral = true)))))
    assertNull(gearText(Vehicle()), "a vehicle with no transmission shouldn't invent a gear")
  }

  @Test
  fun directionIsOnlyClaimedWhileMoving() {
    assertEquals("F", directionText(Vehicle(speed = Speed(direction = DriveDirection.FORWARD))))
    assertEquals("R", directionText(Vehicle(speed = Speed(direction = DriveDirection.BACKWARD))))
    assertNull(directionText(Vehicle(speed = Speed(direction = DriveDirection.STOPPED))))
    assertNull(directionText(Vehicle()))
  }

  @Test
  fun oneDecimalWithoutStringFormat() {
    // String.format is JVM-only and this also runs on wasmJs, hence the hand-rolled version.
    assertEquals("17.8", format1(17.83f))
    assertEquals("0.0", format1(0f))
    assertEquals("53.0", format1(53f))
    assertEquals("4.0", format1(3.96f))
  }
}

/** Which bars the level strip draws, and in what order. */
class ClusterLevelsTest {
  private fun tank(value: Float, capacity: Int, title: String = "") =
    FillUnit(value = value, capacity = capacity, title = title)

  @Test
  fun engineTanksComeFirstAndInAFixedOrder() {
    val vehicle =
      Vehicle(
        motor =
        Motor(
          fillUnits =
          MotorFillUnits(fuel = tank(190f, 380), def = tank(70f, 70), air = tank(2364f, 3000)),
        ),
      )
    assertEquals(listOf("FUEL", "DEF", "AIR"), levelsOf(vehicle).map { it.label })
    assertEquals(0.5f, levelsOf(vehicle).first().fraction)
  }

  @Test
  fun aFullTankStillGetsABar() {
    // A missing air bar reads as a fault rather than as "fine", so a named engine tank always shows.
    val vehicle = Vehicle(motor = Motor(fillUnits = MotorFillUnits(air = tank(3000f, 3000))))
    assertEquals(listOf("AIR"), levelsOf(vehicle).map { it.label })
    assertEquals(1f, levelsOf(vehicle).single().fraction)
  }

  @Test
  fun cargoIsNotOnThisStrip() {
    // The strip answers "can the machine keep going", which is three bars that mean the same thing on
    // every rig. What's in the hopper changes shape as you hitch things up and belongs to the rig-slot
    // tiles, which name it and give figures.
    val vehicle =
      Vehicle(
        motor = Motor(fillUnits = MotorFillUnits(fuel = tank(380f, 380))),
        fillUnits = FillUnits(listOf(tank(10f, 100, title = "Seed"))),
        implement =
        listOf(
          Implement(
            name = "Sprayer",
            fillUnits = FillUnits(listOf(tank(300f, 1200, title = "Herbicide"))),
            implement = listOf(Implement(fillUnits = FillUnits(listOf(tank(500f, 1000, title = "Water"))))),
          ),
        ),
      )
    assertEquals(listOf("FUEL"), levelsOf(vehicle).map { it.label })
  }

  @Test
  fun aLevelIsDrivenByLitresNotThePreRoundedPercent() {
    // fillLevelPercentage staircases ~1% at a time and judders visibly on a bar this size.
    val precise = FillUnit(value = 123f, capacity = 380, fillLevelPercentage = 32)
    val vehicle = Vehicle(motor = Motor(fillUnits = MotorFillUnits(fuel = precise)))
    assertEquals(123f / 380f, levelsOf(vehicle).single().fraction)
  }

  @Test
  fun aVehicleWithNoTanksAtAllHasNoBars() {
    assertTrue(levelsOf(Vehicle()).isEmpty())
    assertFalse(levelsOf(Vehicle(motor = Motor())).isNotEmpty())
  }
}

/** The band sizes its lamps to the tile, so a short band is big lamps rather than a mostly empty strip. */
class TelltaleLayoutTest {
  @Test
  fun fewerLampsMeansBiggerOnes() {
    val band = 361.dp to 107.dp // the portrait Pillar page's band, inside the surface padding
    val thirteen = lampSize(13, band.first, band.second)
    val four = lampSize(4, band.first, band.second)
    assertTrue(four > thirteen, "4 lamps ($four) should be no smaller than 13 ($thirteen)")
  }

  @Test
  fun everyLampFitsTheTileItWasSizedFor() {
    val width = 361.dp
    val height = 107.dp
    for (count in 1..Telltale.entries.size) {
      val size = lampSize(count, width, height)
      val perRow = ((width.value + 8f) / (size.value + 8f)).toInt().coerceAtLeast(1)
      val rows = (count + perRow - 1) / perRow
      assertTrue(rows * (size.value + 8f) - 8f <= height.value, "$count lamps at $size overflow the band")
    }
  }

  @Test
  fun aBandWithNoRoomStillReturnsAUsableSize() {
    // Squeezed past what fits, the lamps stop shrinking rather than going to zero: better to clip a
    // band than to render invisible lamps.
    assertTrue(lampSize(13, 40.dp, 12.dp).value > 0f)
  }
}
