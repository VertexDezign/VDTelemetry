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
import net.vertexdezign.vdt.model.Steering
import net.vertexdezign.vdt.model.SteeringLayout
import net.vertexdezign.vdt.model.SteeringMode
import net.vertexdezign.vdt.model.Temperatur
import net.vertexdezign.vdt.model.Vehicle
import net.vertexdezign.vdt.model.Wearable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    // A rear-only diff lock: the front side never arrives, so the lamp reports on the end it can see
    // and says nothing about the other.
    val vehicle = Vehicle(motor = Motor(diffLock = DiffLock(front = null, back = false), awd = true))
    assertEquals(false, Telltale.DiffLock.stateIn(vehicle))
    assertEquals(true, Telltale.Awd.stateIn(vehicle))
    assertNull(Telltale.ParkingBrake.stateIn(vehicle))
    // Neither end reported is no lamp at all, even though the subtree arrived.
    assertNull(Telltale.DiffLock.stateIn(Vehicle(motor = Motor(diffLock = DiffLock()))))
  }

  @Test
  fun oneDiffLockLampLitByEitherEnd() {
    // One lamp over two independent differentials: shutting either is a diff lock engaged, and which
    // end it was is the glyph's business rather than a second lamp's.
    fun lock(front: Boolean?, back: Boolean?) = Vehicle(motor = Motor(diffLock = DiffLock(front, back)))
    assertEquals(false, Telltale.DiffLock.stateIn(lock(false, false)))
    assertEquals(true, Telltale.DiffLock.stateIn(lock(true, false)))
    assertEquals(true, Telltale.DiffLock.stateIn(lock(false, true)))
    assertEquals(true, Telltale.DiffLock.stateIn(lock(true, true)))
    // An end that isn't reported can't hold the lamp off when the other one is shut.
    assertEquals(true, Telltale.DiffLock.stateIn(lock(null, true)))
  }

  @Test
  fun theDiffLockGlyphSaysWhichEndIsShut() {
    fun lock(front: Boolean?, back: Boolean?) = Vehicle(motor = Motor(diffLock = DiffLock(front, back)))
    assertEquals(ClusterIcons.DiffLockFront, Telltale.DiffLock.iconIn(lock(true, false)))
    assertEquals(ClusterIcons.DiffLockRear, Telltale.DiffLock.iconIn(lock(false, true)))
    assertEquals(ClusterIcons.DiffLockBoth, Telltale.DiffLock.iconIn(lock(true, true)))
    // Nothing shut is the resting glyph — both ends, drawn dim, on the segment ghost's convention.
    assertEquals(ClusterIcons.DiffLockBoth, Telltale.DiffLock.iconIn(lock(false, false)))
    // An unreported end is not a shut one: rear-only still reads as the rear glyph.
    assertEquals(ClusterIcons.DiffLockRear, Telltale.DiffLock.iconIn(lock(null, true)))
  }

  @Test
  fun everyOtherLampDrawsItsOwnIconWhateverTheVehicleSays() {
    // iconIn exists for the one lamp with a glyph per state; the rest must not quietly acquire one.
    val vehicle = Vehicle(motor = Motor(diffLock = DiffLock(front = true, back = true), awd = true))
    for (lamp in Telltale.entries.filter { it != Telltale.DiffLock }) {
      assertEquals(lamp.icon, lamp.iconIn(vehicle), "${lamp.key} changed glyph with the vehicle")
    }
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
  fun onlyTheSignalLampsBlink() {
    // A flashing lamp means "signalling". Anything else flashing reads as a fault, so the set is
    // small on purpose and worth failing a build over.
    assertEquals(
      setOf(Telltale.TurnLeft, Telltale.TurnRight),
      Telltale.entries.filter { it.blinks }.toSet(),
    )
  }

  @Test
  fun hazardIsShownAsBothIndicatorsRatherThanAsALampOfItsOwn() {
    // Hazards light both indicators, which the band already draws — at the two edges, flashing
    // together, which is what the machine is doing. A third lamp saying so would be the same fact
    // twice. The glyph stays for the Lighting panel's button.
    assertTrue(Telltale.entries.none { it.key == "hazard" })
  }

  @Test
  fun theTurnLampsAreTheOnesPinnedToTheEdges() {
    assertEquals(BandSide.Start, Telltale.TurnLeft.side)
    assertEquals(BandSide.End, Telltale.TurnRight.side)
    val pinned = Telltale.entries.filter { it.side != BandSide.Middle }
    assertEquals(listOf(Telltale.TurnLeft, Telltale.TurnRight), pinned)
  }

  @Test
  fun everyLampKeyIsUniqueAndStable() {
    // Keys are persisted in a tile's config, so a collision or a rename silently drops lamps from
    // bands users have already configured.
    val keys = Telltale.entries.map { it.key }
    assertEquals(keys.size, keys.toSet().size, "duplicate telltale key in $keys")
  }
}

/** The two derived lamps, and the thresholds we picked for them. */
class MaintenanceLampTest {
  private fun tempAt(value: Int) = Vehicle(motor = Motor(temperatur = Temperatur(value = value, min = 20, max = 120)))

  @Test
  fun aWorkingEngineIsNotAWarning() {
    // 89°C is a combine under load in the committed captures; the threshold has to clear that.
    assertEquals(false, overheating(tempAt(89)))
    assertEquals(false, overheating(tempAt(37)))
  }

  @Test
  fun anOverheatingEngineIs() {
    // 90% of the 20..120 span is 110.
    assertEquals(true, overheating(tempAt(110)))
    assertEquals(true, overheating(tempAt(120)))
  }

  @Test
  fun damageIsItsOwnLampRatherThanPartOfTheTemperatureOne() {
    // They used to share `engineWarning`, which lit for either and so never said which.
    assertEquals(true, needsAttention(Vehicle(wearable = Wearable(damage = 80))))
    assertEquals(false, needsAttention(Vehicle(wearable = Wearable(damage = 10))))
    assertNull(overheating(Vehicle(wearable = Wearable(damage = 80))), "wear is not a temperature")
  }

  @Test
  fun nothingReportedMeansNoLamp() {
    assertNull(overheating(Vehicle()))
    assertNull(needsAttention(Vehicle()))
    // A degenerate gauge (no span) can't be read as hot, and mustn't be read as cold either.
    assertNull(overheating(Vehicle(motor = Motor(temperatur = Temperatur(value = 50, min = 0, max = 0)))))
  }

  @Test
  fun theLampsWaitingOnTheMaintenanceModNeverClaimAnything() {
    // Drawn ahead of their channel. A lamp with no source has to stay absent on *every* vehicle, not
    // just on an empty one — an unlit brake-system lamp is a claim we have no standing to make.
    val fullyReporting =
      Vehicle(
        motor = Motor(temperatur = Temperatur(value = 90, min = 20, max = 120)),
        wearable = Wearable(damage = 90),
      )
    val waiting = listOf(Telltale.EngineWarning, Telltale.Battery, Telltale.BrakeSystem, Telltale.Service)
    for (lamp in waiting) {
      assertNull(lamp.stateIn(fullyReporting), "${lamp.key} lit without a channel behind it")
    }
    // …while the two that *do* have sources read them.
    assertEquals(false, Telltale.Temperature.stateIn(fullyReporting))
    assertEquals(true, Telltale.GeneralWarning.stateIn(fullyReporting))
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
  fun theDirectionFollowsTheTransmissionRatherThanTheMovement() {
    // The point of motor.direction: a tractor stood at a gateway in reverse is still pointed
    // backwards, and the cluster has to keep saying so. `speed.direction` reads STOPPED there.
    val waitingToBackUp =
      Vehicle(speed = Speed(direction = DriveDirection.STOPPED), motor = Motor(direction = DriveDirection.BACKWARD))
    assertEquals(DriveSymbol.Reverse, driveSymbol(waitingToBackUp))
    assertEquals("R", driveSymbol(waitingToBackUp)?.letter)

    // ...and it outranks the roll, so a machine drifting back with forward selected reads forward.
    val rollingBack =
      Vehicle(speed = Speed(direction = DriveDirection.BACKWARD), motor = Motor(direction = DriveDirection.FORWARD))
    assertEquals(DriveSymbol.Forward, driveSymbol(rollingBack), "the transmission wins over the roll")
  }

  @Test
  fun neutralIsALetterWithNoArrow() {
    // STOPPED on the *motor* means neutral, not "not moving" — with automatic direction change the
    // engine reports it below about 1 km/h. There is no direction to point in, so the arrow slot
    // stays empty and only the N is printed.
    val neutral = Vehicle(motor = Motor(direction = DriveDirection.STOPPED))
    assertEquals(DriveSymbol.Neutral, driveSymbol(neutral))
    assertEquals("N", driveSymbol(neutral)?.letter)
    assertNull(driveSymbol(neutral)?.icon, "neutral must not draw an arrow")
    // The two real directions do have one — that asymmetry is why `icon` is nullable at all.
    assertNotNull(DriveSymbol.Forward.icon)
    assertNotNull(DriveSymbol.Reverse.icon)
  }

  @Test
  fun anOlderModWithoutTheMotorDirectionFallsBackToTheMovement() {
    // Mod version 5 and earlier never sent `motor.direction`. Rather than showing nothing at all,
    // those captures keep the pre-#53 behaviour: a direction while moving, nothing once stopped.
    assertEquals(DriveSymbol.Forward, driveSymbol(Vehicle(speed = Speed(direction = DriveDirection.FORWARD))))
    assertEquals(DriveSymbol.Reverse, driveSymbol(Vehicle(speed = Speed(direction = DriveDirection.BACKWARD))))
    assertNull(driveSymbol(Vehicle(speed = Speed(direction = DriveDirection.STOPPED))))
    assertNull(driveSymbol(Vehicle()))
    // A motor that reports no direction is still the older shape, even though the motor exists.
    assertNull(driveSymbol(Vehicle(motor = Motor())))
  }

  @Test
  fun everySteeringLayoutHasItsOwnPicture() {
    // Two layouts drawn the same are two modes the driver cannot tell apart — with one deliberate
    // exception: a crab with no side of its own borrows the left dog walk's picture, because the
    // wheels really are in that shape and there is nothing to mirror it against.
    val icons = SteeringLayout.entries.associateWith { steeringLayoutIcon(it) }
    assertTrue(icons.values.none { it == null }, "a layout with no glyph would fall back to its bare number")

    // The exception, named: a sideless crab borrows the left dog walk's picture, which is the shape
    // its wheels really are in.
    assertEquals(ClusterIcons.SteerCrabLeft, icons[SteeringLayout.CRAB])
    assertEquals(ClusterIcons.SteerCrabLeft, icons[SteeringLayout.CRAB_LEFT])
    // ...and it is the *only* one. In particular the two dog walks must not collapse together: the
    // machine leans the other way in each, and the driver chose which.
    assertEquals(SteeringLayout.entries.size - 1, icons.values.toSet().size, "two layouts share a glyph")
    // An unread layout is the one case that legitimately has none.
    assertNull(steeringLayoutIcon(null))
  }

  @Test
  fun theSteeringMarksAreAbsentOnAMachineThatHasNeither() {
    assertTrue(steeringMarks(Vehicle()).isEmpty())
    assertTrue(steeringMarks(Vehicle(steering = Steering())).isEmpty())
    // One mode is no choice at all — the game hides its own steering box on the same test.
    assertTrue(
      steeringMarks(Vehicle(steering = Steering(mode = SteeringMode(name = "Front", index = 1, count = 1)))).isEmpty(),
    )
  }

  @Test
  fun theSteeringModeDrawsItsShapeAndFallsBackToItsNumber() {
    fun mode(layout: SteeringLayout?, index: Int = 2) =
      steeringMarks(Vehicle(steering = Steering(mode = SteeringMode("Crab", index, 3, layout)))).single()

    assertEquals(ClusterIcons.SteerCrabLeft, mode(SteeringLayout.CRAB).icon)
    assertNull(mode(SteeringLayout.CRAB).text, "a glyph and a number would be the same fact twice")

    // Nothing derived: the number is what is left, and it is at least true.
    assertNull(mode(null).icon)
    assertEquals("2", mode(null).text)
    assertEquals("Crab", mode(null).label)

    // Amber is a value the driver set, so only a mode other than the one the machine loads in.
    assertEquals(ClusterColors.Set, mode(SteeringLayout.CRAB).colour)
    assertEquals(ClusterColors.Digits, mode(SteeringLayout.FRONT, index = 1).colour)
  }

  @Test
  fun theSeatKeepsItsSlotWhenItIsTheNormalWayRound() {
    // Ghosted rather than dropped: a machine that *has* a reversible position and isn't using it is a
    // different thing from one that hasn't got one, and only the first is something to act on.
    val forward = steeringMarks(Vehicle(steering = Steering(reversed = false))).single()
    assertEquals(ClusterIcons.SeatReversed, forward.icon)
    assertEquals(GHOST_ALPHA, forward.alpha)

    val reversed = steeringMarks(Vehicle(steering = Steering(reversed = true))).single()
    assertEquals(1f, reversed.alpha)
    assertEquals(ClusterColors.Set, reversed.colour)
    assertFalse(reversed.blinks)

    // Mid-swivel it is neither way round, and says so.
    assertTrue(steeringMarks(Vehicle(steering = Steering(reversed = true, changing = true))).single().blinks)
  }

  @Test
  fun theTwoMarksSitTogetherInTheOrderTheyAreRead() {
    // The mode first, then the seat: the steering is about the machine and the seat about the driver,
    // and a mark must not move sideways because the other one appeared.
    val both =
      steeringMarks(
        Vehicle(steering = Steering(mode = SteeringMode("Crab", 2, 3, SteeringLayout.CRAB), reversed = true)),
      )
    assertEquals(listOf(ClusterIcons.SteerCrabLeft, ClusterIcons.SeatReversed), both.map { it.icon })
  }

  @Test
  fun aLinesMarksCountTowardsItsWidth() {
    // The digit size is picked from the widest line, so a line whose marks were left out of that sum
    // would size its type too big and run the digits under the label column.
    assertTrue(lineWidth(2, 2) > lineWidth(2, 1), "a second mark has to cost width")
    // An empty slot is still a slot: it is held open so nothing shifts when a mark appears.
    assertEquals(lineWidth(2, 1), lineWidth(2, 0))
    // ...and a line drawn smaller takes proportionally less.
    assertEquals(lineWidth(2, 2) * 0.5f, lineWidth(2, 2, 0.5f))
  }

  @Test
  fun oneDecimalWithoutStringFormat() {
    // String.format is JVM-only and this also runs on wasmJs, hence the hand-rolled version.
    assertEquals("17.8", format1(17.83f))
    assertEquals("0.0", format1(0f))
    assertEquals("53.0", format1(53f))
    assertEquals("4.0", format1(3.96f))
    // Below one, the sign lives nowhere but the sign: integer division truncates towards zero, so
    // -0.5 has no integer digit to carry it.
    assertEquals("-0.5", format1(-0.5f))
    assertEquals("-1.5", format1(-1.5f))
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
      val gap = LAMP_GAP.value
      val perRow = ((width.value + gap) / (size.value + gap)).toInt().coerceAtLeast(1)
      val rows = (count + perRow - 1) / perRow
      assertTrue(rows * (size.value + gap) - gap <= height.value, "$count lamps at $size overflow the band")
    }
  }

  @Test
  fun theEdgeLampsTakeTheirRoomOutOfTheRun() {
    // The turn arrows are laid out beside the wrapping run, not in it, so the run has less width to
    // pack into and its lamps can only get smaller.
    assertTrue(lampSize(11, 361.dp, 107.dp, edges = 2) <= lampSize(11, 361.dp, 107.dp))
  }

  @Test
  fun aBandOfNothingButEdgeLampsGetsBigOnes() {
    // Two turn arrows and nothing between them: they should take the band, not fall to the floor.
    assertEquals(48.dp, lampSize(0, 361.dp, 107.dp, edges = 2))
  }

  @Test
  fun aBandWithNoRoomStillReturnsAUsableSize() {
    // Squeezed past what fits, the lamps stop shrinking rather than going to zero: better to clip a
    // band than to render invisible lamps.
    assertTrue(lampSize(13, 40.dp, 12.dp).value > 0f)
  }
}
