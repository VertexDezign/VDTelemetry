package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.DischargeReason
import net.vertexdezign.vdt.model.DischargeState
import net.vertexdezign.vdt.model.DriveDirection
import net.vertexdezign.vdt.model.FillDisplayType
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.MixState
import net.vertexdezign.vdt.model.MotorState
import net.vertexdezign.vdt.model.PfMode
import net.vertexdezign.vdt.model.PipeState
import net.vertexdezign.vdt.model.PlowSide
import net.vertexdezign.vdt.model.SprayCategory
import net.vertexdezign.vdt.model.SprayerKind
import net.vertexdezign.vdt.model.SteeringLayout
import net.vertexdezign.vdt.model.TillageKind
import net.vertexdezign.vdt.model.TipState
import net.vertexdezign.vdt.model.VdtData
import net.vertexdezign.vdt.model.Vehicle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Decodes the committed `examples/json` fixtures through the real server path ([VdtParser.parseJson])
 * and asserts the model's field mapping, a lossless JSON round-trip, and the [ServerMessage] wire
 * discriminator.
 */
class VdtModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    // Walk up from the module dir to find the repo-root `examples/json` fixtures.
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/$name from ${File(".").absolutePath}")
  }

  private fun model(name: String): VdtData = VdtParser.parseJson(example(name))

  private fun assertJsonRoundTrips(data: VdtData) {
    val encoded = json.encodeToString(VdtData.serializer(), data)
    val decoded = json.decodeFromString(VdtData.serializer(), encoded)
    assertEquals(data, decoded, "JSON round-trip should be lossless")
  }

  // A whole capture, checked for a lossless round trip before anything is read off it. The targeted
  // assertions on the captures below only ever see the fields they name, so this is what catches a
  // field of the new subtrees that decodes but does not come back out again.
  private fun capture(name: String): VdtData = model(name).also { assertJsonRoundTrips(it) }

  @Test
  fun parsesTractorWithCultivator() {
    val data = model("tractor_with_cultivator.json")

    assertEquals("5", data.version)
    assertEquals("01.08.2024", data.environment?.date)

    // weather
    assertEquals(
      28,
      data.environment
        ?.weather
        ?.temperature
        ?.current,
    )
    assertEquals(
      "°C",
      data.environment
        ?.weather
        ?.temperature
        ?.unit,
    )

    // pda / map data
    val pda = assertNotNull(data.environment?.pda)
    assertEquals("S:/common/Farming Simulator 25/data/maps/mapUS/textures/ui/overview.dds", pda.filename)
    assertEquals(2048, pda.width)
    assertEquals(2048, pda.height)
    assertEquals(0.4532542f, pda.player?.posX)
    assertEquals(0.42799774f, pda.player?.posZ)
    assertEquals(91, pda.player?.heading)
    assertEquals("°", pda.player?.headingUnit)
    assertEquals(1, pda.player?.farmId)

    val v = assertNotNull(data.vehicle)
    assertEquals("Valtra T195 Active", v.name)
    assertEquals("tractor", v.type)
    assertEquals(0f, v.speed?.value)
    assertEquals("km/h", v.speed?.unit)
    assertEquals(DriveDirection.STOPPED, v.speed?.direction)

    // Enhanced Vehicle wasn't installed when this was captured, so the drivetrain telltales are
    // absent from the JSON entirely and stay null — "unknown", which a consumer must not draw as
    // "off". enhanced_vehicle.json is the counterpart capture, taken with the mod installed.
    assertEquals(null, v.motor?.diffLock)
    assertEquals(null, v.motor?.awd)
    assertEquals(null, v.motor?.parkingBrake)

    assertJsonRoundTrips(data)
  }

  @Test
  fun parsesCombine() {
    val data = model("combine.json")
    val v = assertNotNull(data.vehicle)

    assertEquals("combineDrivable", v.type)
    assertEquals(3.92f, v.speed?.value)
    assertEquals(FoldableState.EXTENDED, v.foldable)
    // `numStates` is absent from this fixture (it was captured before the mod exported it) and so
    // falls back to 0; current == target == 1 is implied by the captured RETRACTED label.
    assertEquals(PipeState.RETRACTED, v.pipe?.state)
    assertEquals(1, v.pipe?.current)
    assertEquals(1, v.pipe?.target)

    // combine motor has fuel + def but no air
    assertEquals(
      947f,
      v.motor
        ?.fillUnits
        ?.fuel
        ?.value,
    )
    assertEquals(
      110f,
      v.motor
        ?.fillUnits
        ?.def
        ?.value,
    )
    assertEquals(null, v.motor?.fillUnits?.air)

    // vehicle-level fillUnits use the repeated `fillUnit` form
    val fillUnits = assertNotNull(v.fillUnits)
    assertEquals(1, fillUnits.fillUnit.size)
    assertEquals(13500, fillUnits.fillUnit[0].capacity)
    assertEquals(5054f, fillUnits.fillUnit[0].value)
    assertEquals(37, fillUnits.fillUnit[0].fillLevelPercentage)
    // display hints are absent at their engine defaults
    assertEquals(0, fillUnits.fillUnit[0].precision)
    assertEquals(FillDisplayType.BAR, fillUnits.fillUnit[0].display)

    assertEquals(1, v.implement.size)
    assertEquals("cutter", v.implement[0].type)

    assertJsonRoundTrips(data)
  }

  @Test
  fun parsesMultipleImplements() {
    val data = model("mutliple_implements.json")
    val v = assertNotNull(data.vehicle)

    // Reversing, and carrying the placeholder group this machine has no business having: it has no
    // ranges, and the capture predates the mod dropping the name `getGearGroupToDisplay` returns for
    // that case. The fixture records what v5 wrote — don't "fix" it.
    assertEquals("N", v.motor?.gear?.group)
    assertEquals("R", v.motor?.gear?.value)

    assertEquals(2, v.implement.size)

    assertJsonRoundTrips(data)
  }

  @Test
  fun parsesNestedTrailersAndAggregatesFillUnits() {
    val data = model("nested_trailers.json")
    val v = assertNotNull(data.vehicle)

    // BACK is a trailer that itself pulls a nested trailer; both carry wheat.
    val back = assertNotNull(v.implement.firstOrNull { it.position == "BACK" })
    assertEquals("trailer", back.type)
    assertEquals(
      18500f,
      back.fillUnits
        ?.fillUnit
        ?.singleOrNull()
        ?.value,
    )

    // the nested trailer is reachable and carries its own fill unit
    val nested = assertNotNull(back.implement.singleOrNull())
    assertEquals("Rudolph DK 280 RP", nested.name)
    assertEquals(
      "WHEAT",
      nested.fillUnits
        ?.fillUnit
        ?.singleOrNull()
        ?.type,
    )
    assertEquals(
      18500f,
      nested.fillUnits
        ?.fillUnit
        ?.singleOrNull()
        ?.value,
    )

    // the whole BACK chain exposes both fill units — this recursive walk mirrors what the
    // Implements panel's collectFillUnits does (and what its "merged" toggle then sums).
    // `sumOf` has no Float overload, hence map/sum — same as mergeFillUnits.
    fun totalFill(imp: Implement): Float =
      (
        imp.fillUnits
          ?.fillUnit
          ?.map { it.value }
          ?.sum() ?: 0f
      ) +
        imp.implement.map { totalFill(it) }.sum()
    assertEquals(37000f, totalFill(back))

    assertJsonRoundTrips(data)
  }

  @Test
  fun coercesNullCapacityToDefault() {
    // A pass-through fill unit (a forage/carrot harvester's output) has no capacity in its XML, so
    // the engine reports +inf and the mod's JSON encoder emits `capacity: null`. A strict parse blew
    // up on that and froze the whole feed; coerceInputValues must fall it back to the default (0).
    val text =
      """{"version":"1","vehicle":{"fillUnits":{"fillUnit":""" +
        """[{"value":0,"fillLevelPercentage":0,"capacity":null,"unit":""}]}}}"""
    val data = VdtParser.parseJson(text)
    val unit =
      assertNotNull(
        data.vehicle
          ?.fillUnits
          ?.fillUnit
          ?.singleOrNull(),
      )
    assertEquals(0, unit.capacity)
  }

  @Test
  fun decodesFractionalConsumableFillUnit() {
    // Bale net/twine/wrap are the game's `Consumable` spec on a fill unit measured in SLOTS: capacity
    // is the slot count and the level is "spare rolls + how much of the mounted one is left", so it
    // is fractional. Inline rather than a fixture: no captured baler JSON exists yet, and inventing
    // one would put made-up fill-type names in examples/json.
    val text =
      """{"version":"2","vehicle":{"fillUnits":{"fillUnit":[""" +
        """{"value":2.4,"capacity":4,"fillLevelPercentage":60,"title":"Netz","unit":"","display":"STEP"}]}}}"""
    val data = VdtParser.parseJson(text)
    assertJsonRoundTrips(data)
    val unit =
      assertNotNull(
        data.vehicle
          ?.fillUnits
          ?.fillUnit
          ?.singleOrNull(),
      )
    assertEquals(2.4f, unit.value)
    assertEquals(4, unit.capacity)
    assertEquals(FillDisplayType.STEP, unit.display)
    assertEquals(0, unit.precision)
  }

  @Test
  fun decodesSchemaAndSelection() {
    // The rig diagram: each object names a silhouette and lists where children hang off it, and the
    // child points back with jointDescIndex. enhanced_vehicle.json carries the plain shape of this;
    // what stays inline is what no capture holds — a control group, non-zero silhouette offsets, and
    // a schema with no borders at all, none of which could be hand-written without inventing
    // per-vehicle XML data into examples/json.
    val text =
      """{"version":"4","vehicle":{"schema":{"name":"HARVESTER","offsetX":0.25,"offsetY":0.5,""" +
        """"attacherJoint":[{"x":0.1,"y":0.2,"rotation":0,"invertX":false},""" +
        """{"x":0.9,"y":0.3,"rotation":1.5,"invertX":true,"liftedOffsetY":5}]},""" +
        """"selection":{"selected":false},""" +
        """"implement":[{"position":"FRONT","jointDescIndex":2,"schema":{"name":"COMBINE_HEADER"},""" +
        """"selection":{"selected":true,"controlGroup":{"current":2,"name":"Greifer",""" +
        """"names":["Kran","Greifer"]}}}]}}"""
    val data = VdtParser.parseJson(text)
    assertJsonRoundTrips(data)
    val v = assertNotNull(data.vehicle)

    val schema = assertNotNull(v.schema)
    assertEquals("HARVESTER", schema.name)
    assertEquals(0.25f, schema.offsetX)
    assertEquals(2, schema.attacherJoint.size)
    assertTrue(schema.attacherJoint[1].invertX)
    assertEquals(5f, schema.attacherJoint[1].liftedOffsetY)
    // absent border fields stay null rather than defaulting to a misleading 0
    assertEquals(null, schema.borderLeft)

    val imp = assertNotNull(v.implement.singleOrNull())
    // the child indexes into the *parent's* joint list
    assertEquals(2, imp.jointDescIndex)
    assertEquals("COMBINE_HEADER", imp.schema?.name)
    assertTrue(imp.schema?.attacherJoint?.isEmpty() == true)

    // exactly one node in the rig is selected, and it carries the control group
    assertEquals(false, v.selection?.selected)
    assertEquals(true, imp.selection?.selected)
    val group = assertNotNull(imp.selection?.controlGroup)
    assertEquals(2, group.current)
    assertEquals("Greifer", group.name)
    assertEquals(listOf("Kran", "Greifer"), group.names)
  }

  @Test
  fun decodesUnloadingAndWorkAspects() {
    // The §4 aspects. Inline for the same reason as the schema case: the committed captures predate
    // all of them, and they only appear on machines (auger wagon, tipper, baler) none of the four
    // fixtures contain.
    val text =
      """{"version":"5","vehicle":{"discharge":{"state":"GROUND","allowed":true,"nodeIndex":1,""" +
        """"fillUnitIndex":2,"hasObject":false,"hitTerrain":true},""" +
        """"harvest":{"swathActive":true,"swathAvailable":true,"chopperAvailable":false},""" +
        """"implement":[{"position":"BACK","tipping":{"state":"OPENING","preferredSide":3,"count":3},""" +
        """"discharge":{"state":"OFF","allowed":true,"reason":"NO_FREE_CAPACITY"},""" +
        """"workMode":{"current":2,"count":2,"name":"Arbeit"},""" +
        """"workWidth":{"left":3.0,"leftMax":3.0,"right":1.5,"rightMax":3.0,"total":4.5,"unit":"m"},""" +
        """"baleCounter":{"session":12,"lifetime":480}}]}}"""
    val data = VdtParser.parseJson(text)
    assertJsonRoundTrips(data)
    val v = assertNotNull(data.vehicle)

    val discharge = assertNotNull(v.discharge)
    assertEquals(DischargeState.GROUND, discharge.state)
    assertEquals(1, discharge.nodeIndex)
    assertTrue(discharge.hitTerrain == true)
    // nothing wrong -> no reason at all, rather than a "fine" sentinel
    assertEquals(null, discharge.reason)

    assertEquals(true, v.harvest?.swathActive)
    assertEquals(false, v.harvest?.chopperAvailable)

    val imp = assertNotNull(v.implement.singleOrNull())
    // a tipper can be mid-OPENING with nothing discharging yet, and with a side chosen but not in use
    assertEquals(TipState.OPENING, imp.tipping?.state)
    assertEquals(null, imp.tipping?.side)
    assertEquals(3, imp.tipping?.preferredSide)
    assertEquals(DischargeState.OFF, imp.discharge?.state)
    assertEquals(DischargeReason.NO_FREE_CAPACITY, imp.discharge?.reason)

    assertEquals("Arbeit", imp.workMode?.name)
    // sides are independent: right folded to half width
    assertEquals(3f, imp.workWidth?.left)
    assertEquals(1.5f, imp.workWidth?.right)
    assertEquals(4.5f, imp.workWidth?.total)
    assertEquals(480, imp.baleCounter?.lifetime)
  }

  @Test
  fun decodesEnhancedVehicleDrivetrainTelltales() {
    // The one capture taken *with* Enhanced Vehicle installed — the counterpart to the absent case
    // asserted in parsesTractorWithCultivator. The integration only decorates the controlled
    // vehicle's motor, so the telltales sit there and nowhere else in the rig.
    val data = model("enhanced_vehicle.json")
    val v = assertNotNull(data.vehicle)
    assertEquals("DEUTZ-FAHR 8280 TTV", v.name)

    val motor = assertNotNull(v.motor)
    // the core motor subtree still decodes alongside the added fields
    assertEquals(MotorState.OFF, motor.state)
    assertEquals(2200, motor.rpm?.max)

    assertEquals(true, motor.awd)
    assertEquals(false, motor.parkingBrake)
    // engaged AWD with both diffs open: each of the four is independent, so `false` here has to
    // decode as a real `false` and not fall in with the never-reported case
    assertEquals(false, motor.diffLock?.front)
    assertEquals(false, motor.diffLock?.back)

    assertJsonRoundTrips(data)
  }

  @Test
  fun decodesPartialDiffLockWithoutInventingTheOtherSide() {
    // The integration sets each side only once Enhanced Vehicle hands it a boolean, so a rear-only
    // lock arrives as `{"back": …}` with no `front` key at all. That has to stay null rather than
    // collapsing to false, which would read as a front axle that exists and is unlocked. Inline
    // because enhanced_vehicle.json is a tractor that reports both sides.
    val text = """{"version":"5","vehicle":{"motor":{"state":"ON","diffLock":{"back":true}}}}"""
    val data = VdtParser.parseJson(text)
    assertJsonRoundTrips(data)

    val motor = assertNotNull(data.vehicle?.motor)
    val diffLock = assertNotNull(motor.diffLock)
    assertEquals(null, diffLock.front)
    assertEquals(true, diffLock.back)
    // awd / parkingBrake are independently optional — Enhanced Vehicle can report one and not others
    assertEquals(null, motor.awd)
    assertEquals(null, motor.parkingBrake)
  }

  @Test
  fun decodesTransmissionDirectionSeparatelyFromTravelDirection() {
    // The two are deliberately different questions. `speed.direction` is how the machine is *moving*
    // and the game reports STOPPED below walking pace; `motor.direction` is what the transmission is
    // set to, which stays put. A tractor stood still in reverse — waiting to back onto a trailer — is
    // exactly the case that motivated the field, and the one the four committed captures cannot show:
    // they were all taken at v5, before the mod exported it, so there `motor.direction` is null.
    val text =
      """{"version":"6","vehicle":{"speed":{"value":0,"unit":"km/h","direction":"STOPPED"},""" +
        """"motor":{"state":"ON","direction":"BACKWARD"}}}"""
    val data = VdtParser.parseJson(text)
    assertJsonRoundTrips(data)

    assertEquals(DriveDirection.STOPPED, data.vehicle?.speed?.direction)
    assertEquals(DriveDirection.BACKWARD, data.vehicle?.motor?.direction)

    // STOPPED on the motor is *neutral*, and has to decode as a real value rather than as absence.
    val neutral = VdtParser.parseJson("""{"version":"6","vehicle":{"motor":{"direction":"STOPPED"}}}""")
    assertEquals(DriveDirection.STOPPED, neutral.vehicle?.motor?.direction)

    // Absent means "not reported", not "forward" — a v5 capture must not be drawn as sitting in gear.
    assertEquals(null, model("combine.json").vehicle?.motor?.direction)
  }

  @Test
  fun decodesAllFourMotorStates() {
    // Mod version 14. The engine has four and the mod exported three, so IGNITION never appeared and
    // STARTING meant the key turned rather than the starter cranking. Inline because the committed
    // captures are all of a machine either off or running — the two states in the middle last a
    // second and no capture has ever caught one.
    fun state(name: String) =
      VdtParser
        .parseJson("""{"version":"14","vehicle":{"motor":{"state":"$name"}}}""")
        .vehicle
        ?.motor
        ?.state

    assertEquals(MotorState.OFF, state("OFF"))
    assertEquals(MotorState.IGNITION, state("IGNITION"))
    assertEquals(MotorState.STARTING, state("STARTING"))
    assertEquals(MotorState.ON, state("ON"))

    // Only ON is a running engine. A key turned and a starter cranking are both machines that are not
    // going yet, and the `!= OFF` test they used to pass is exactly the bug this version fixed.
    assertEquals(listOf(false, false, false, true), MotorState.entries.map { it.isRunning })

    assertJsonRoundTrips(VdtParser.parseJson("""{"version":"14","vehicle":{"motor":{"state":"IGNITION"}}}"""))

    // A capture from before the field is still an engine we can't claim is running.
    assertEquals(
      MotorState.OFF,
      VdtParser
        .parseJson("""{"vehicle":{"motor":{}}}""")
        .vehicle
        ?.motor
        ?.state,
    )
  }

  @Test
  fun decodesTheSteeringModeAndTheDrivingPosition() {
    // Mod version 10. Both halves are optional and independent — this is the machine that has both,
    // which is the one the whole block was added for (a Xerion turns its cab *and* crab-steers).
    val text =
      """{"version":"10","vehicle":{"steering":{"mode":{"name":"Crab steering","index":3,"count":3,""" +
        """"layout":"CRAB_LEFT"},"reversed":true,"changing":false}}}"""
    val data = VdtParser.parseJson(text)
    assertJsonRoundTrips(data)

    val steering = data.vehicle?.steering
    assertEquals("Crab steering", steering?.mode?.name)
    assertEquals(3, steering?.mode?.index)
    assertEquals(SteeringLayout.CRAB_LEFT, steering?.mode?.layout)
    assertEquals(true, steering?.reversed)

    // A machine whose wheels the mod couldn't read sends the name and no layout; the terminal has to
    // tell that from a machine that sent no mode at all.
    val unread = VdtParser.parseJson("""{"vehicle":{"steering":{"mode":{"name":"4WS","index":2,"count":2}}}}""")
    assertEquals(
      null,
      unread.vehicle
        ?.steering
        ?.mode
        ?.layout,
    )
    assertEquals(
      "4WS",
      unread.vehicle
        ?.steering
        ?.mode
        ?.name,
    )
    assertEquals(null, unread.vehicle?.steering?.reversed, "no reversible seat is null, not false")

    // A layout this build has never heard of coerces to null rather than failing the whole feed —
    // the mod is allowed to run ahead of the terminal.
    val ahead = VdtParser.parseJson("""{"vehicle":{"steering":{"mode":{"index":1,"count":2,"layout":"DIAGONAL"}}}}""")
    assertEquals(
      null,
      ahead.vehicle
        ?.steering
        ?.mode
        ?.layout,
    )

    // And the captures, all taken before the field existed, must not invent one.
    assertEquals(null, model("combine.json").vehicle?.steering)
  }

  @Test
  fun decodesTheSownCropOffTheSowingAspect() {
    // The crop is the one thing a seeding terminal exists to say and the fill unit cannot: a hopper
    // reports the fill type it holds, never which of the machine's declared seeds is selected. The
    // first capture taken at v9 (issue #58), so it is also the in-game proof that the aspect works.
    val data = model("telemetry/precisionFarming/sowingMachine.json")
    assertEquals("9", data.version)
    assertJsonRoundTrips(data)

    val seeder = assertNotNull(data.vehicle?.implement?.single())
    val sowing = assertNotNull(seeder.sowing)
    assertEquals("WHEAT", sowing.fruitType)
    assertEquals("Weizen", sowing.title)
    // 1 of 9 — the index is into the machine's own seed list, so it pairs with the count to say
    // whether there is a choice at all.
    assertEquals(1, sowing.seedIndex)
    assertEquals(9, sowing.seedCount)
    assertTrue(sowing.changeAllowed)
    assertTrue(sowing.directPlanting)

    // Absent at the engine default rather than emitted as 1.
    assertEquals(null, sowing.usageScale)
  }

  @Test
  fun sowingJoinsItsHopperThroughFillTypeOnACombinationMachine() {
    // This capture is a *fertilizing* seeder: two fill units, and `precisionFarming.mode` FERTILIZER.
    // It is the case that decided the panel dispatches on aspect presence rather than on `type` —
    // one machine that is two functions at once, and whose type name
    // (`pdlc_skyAgriculturePack.fertilizingSowingMachineWorkEffects`) no switch could enumerate.
    val seeder = assertNotNull(capture("telemetry/precisionFarming/sowingMachine.json").vehicle?.implement?.single())
    val units = assertNotNull(seeder.fillUnits?.fillUnit)
    assertEquals(listOf("WHEAT", "FERTILIZER"), units.map { it.type })

    // `sowing.fillType` is what joins the aspect to the right one of the two — the panel must not
    // assume the seed hopper is the first unit, and on this machine the fertilizer tank is the same
    // size class, so there is nothing else to disambiguate them by.
    val sowing = assertNotNull(seeder.sowing)
    assertEquals("WHEAT", sowing.fillType)
    val hopper = assertNotNull(units.singleOrNull { it.type == sowing.fillType })
    assertEquals(1760f, hopper.value)
    assertEquals(1760, hopper.capacity)
  }

  @Test
  fun decodesASowingMachineThatNamesNoCrop() {
    // A machine can declare no seeds at all, and a modded crop may not resolve. The three name fields
    // go null together; everything else about the hopper still decodes, so the panel can say "no crop
    // selected" rather than losing the section.
    val text =
      """{"version":"9","vehicle":{"sowing":{"seedIndex":1,"seedCount":0,"changeAllowed":false}}}"""
    val sowing = assertNotNull(VdtParser.parseJson(text).vehicle?.sowing)

    assertEquals(null, sowing.fruitType)
    assertEquals(null, sowing.fillType)
    assertEquals(null, sowing.title)
    assertEquals(0, sowing.seedCount)
    assertEquals(false, sowing.changeAllowed)
    // Defaulted, not absent: a machine that reports a hopper always reports whether it sows direct.
    assertEquals(false, sowing.directPlanting)
  }

  @Test
  fun absentSowingAspectStaysNull() {
    // The whole feature dispatches on aspect presence, so "no sowing subtree" must decode as null
    // rather than a default-constructed hopper — otherwise every tractor grows a seeder section.
    assertEquals(null, model("tractor_with_cultivator.json").vehicle?.sowing)
    assertEquals(
      null,
      model("mutliple_implements.json")
        .vehicle
        ?.implement
        ?.first()
        ?.sowing,
    )
  }

  @Test
  fun everyIsobusAspectIsAbsentOnAMachineThatHasNone() {
    // The dispatch contract, stated once for all four: a mower carries none of them, and each must
    // decode as null rather than as a default-constructed section. A default here would put a
    // cultivator readout on a mower — with `kind = CULTIVATOR` and `deepMode = true` invented whole.
    val mower =
      assertNotNull(
        model("mutliple_implements.json")
          .vehicle
          ?.implement
          ?.first(),
      )
    assertEquals(null, mower.sowing)
    assertEquals(null, mower.spraying)
    assertEquals(null, mower.plow)
    assertEquals(null, mower.tillage)
  }

  @Test
  fun decodesTheSprayerAspectIncludingItsAbsentHalves() {
    // A fertilizer spreader mid-pass. `category` and `sprayType` come from the spray-type manager,
    // which is a different table from the vehicle's own — see collect/aspects/Spraying.lua.
    val text =
      """{"version":"9","vehicle":{"spraying":{"kind":"SOLID_FERTILIZER","active":true,""" +
        """"doubledAmount":true,"doubledAmountAvailable":true,"allowsSpraying":true,""" +
        """"fillType":"FERTILIZER","title":"Mineraldünger","sprayType":"FERTILIZER",""" +
        """"category":"FERTILIZER","nominalUsagePerMin":42.38}}}"""
    val data = VdtParser.parseJson(text)
    assertJsonRoundTrips(data)

    val spraying = assertNotNull(data.vehicle?.spraying)
    assertEquals(SprayerKind.SOLID_FERTILIZER, spraying.kind)
    assertEquals(SprayCategory.FERTILIZER, spraying.category)
    assertEquals("FERTILIZER", spraying.fillType)
    assertTrue(spraying.active)
    assertTrue(spraying.doubledAmountAvailable)
    assertEquals(42.38f, spraying.nominalUsagePerMin)

    // A slurry tanker with an empty tank: no material to name, and doubling is not its control. The
    // machine is still fully described — absence here is the answer, not a gap.
    val tanker =
      assertNotNull(
        VdtParser
          .parseJson(
            """{"version":"9","vehicle":{"spraying":{"kind":"SLURRY_TANKER",""" +
              """"doubledAmountAvailable":false}}}""",
          ).vehicle
          ?.spraying,
      )
    assertEquals(SprayerKind.SLURRY_TANKER, tanker.kind)
    assertEquals(null, tanker.fillType)
    assertEquals(null, tanker.category)
    assertEquals(null, tanker.nominalUsagePerMin)
    assertEquals(false, tanker.doubledAmountAvailable)
  }

  @Test
  fun decodesTheSpreaderCaptures() {
    // Real v9 captures, and the first proof the five-way kind split works on actual machines rather
    // than on stubs. The same AgriSpread hopper appears twice — carrying fertilizer and carrying lime
    // — which is exactly the pair the split exists for: one machine, one `kind`, two `category`s.
    fun spraying(name: String) =
      assertNotNull(
        capture("telemetry/precisionFarming/$name")
          .vehicle
          ?.implement
          ?.first()
          ?.spraying,
      )

    val fertilizer = spraying("fertilizerSpreader.json")
    assertEquals(SprayerKind.SOLID_FERTILIZER, fertilizer.kind)
    assertEquals(SprayCategory.FERTILIZER, fertilizer.category)
    assertEquals("FERTILIZER", fertilizer.fillType)

    val lime = spraying("fertilizerSpreader_lime.json")
    assertEquals(SprayerKind.SOLID_FERTILIZER, lime.kind)
    assertEquals(SprayCategory.LIME, lime.category)
    assertEquals("LIME", lime.fillType)

    // Manure and slurry keep their own kinds, and both are categorised as fertilizer by the game —
    // the category is what the material *does*, the kind is what the machine is.
    val manure = spraying("manureSpreader.json")
    assertEquals(SprayerKind.MANURE_SPREADER, manure.kind)
    assertEquals(SprayCategory.FERTILIZER, manure.category)
    assertEquals("MANURE", manure.fillType)

    val slurry = spraying("liquidManure_dribbleBar.json")
    assertEquals(SprayerKind.SLURRY_TANKER, slurry.kind)
    assertEquals("DIGESTATE", slurry.fillType)
    // The barrel carries its own load, so nothing is sourced from elsewhere — unlike the dribble bar
    // hanging off it, which is the subject of its own test below.
    assertEquals(false, slurry.externalSource)

    // The self-propelled Rogator is the catch-all: a herbicide boom is none of the four.
    val boom = assertNotNull(capture("telemetry/precisionFarming/selfDrivingSprayer.json").vehicle?.spraying)
    assertEquals(SprayerKind.SPRAYER, boom.kind)
    assertEquals(SprayCategory.HERBICIDE, boom.category)
    // Nothing has been sprayed this session, so the engine never computed a usage figure. Absent
    // rather than zero — a rating of 0 l/min would be a claim.
    assertEquals(null, boom.nominalUsagePerMin)
  }

  @Test
  fun decodesThePlowCaptures() {
    // The same LEMKEN plough folded for transport and unfolded to work. Rotation is barred while it
    // is folded — which is the whole reason the two predicates are carried separately — and the side
    // survives the fold, because a folded plough is still turned whichever way it was left.
    fun plow(name: String) =
      assertNotNull(
        capture("telemetry/precisionFarming/$name")
          .vehicle
          ?.implement
          ?.first()
          ?.plow,
      )

    val transport = plow("plow_transportMode.json")
    assertEquals(false, transport.rotationAllowed)
    assertEquals(false, transport.canToggleRotation)
    assertEquals(PlowSide.RIGHT, transport.side)

    val working = plow("plow_workingMode.json")
    assertEquals(true, working.rotationAllowed)
    assertEquals(true, working.canToggleRotation)
    assertEquals(PlowSide.RIGHT, working.side)
  }

  @Test
  fun decodesTheTillageCaptures() {
    fun tillage(name: String) =
      assertNotNull(
        capture("telemetry/precisionFarming/$name")
          .vehicle
          ?.implement
          ?.first()
          ?.tillage,
      )

    // A subsoiler that does not run in deep mode — the two flags are independent, which is why both
    // are carried. `isSubsoiler` is what the machine is; `useDeepMode` is what it leaves behind.
    val subsoiler = tillage("deepCultivator.json")
    assertEquals(TillageKind.SUBSOILER, subsoiler.kind)
    assertEquals(false, subsoiler.deepMode)

    assertEquals(TillageKind.CULTIVATOR, tillage("seedingCultivator.json").kind)
  }

  @Test
  fun combinationMachinesCarryTwoAspectsInTheRealCaptures() {
    // Three different shapes of combination machine, all from real captures — the case the aspect
    // dispatch exists for, and none of them expressible as a switch on `type`.
    fun implement(name: String) =
      assertNotNull(
        capture("telemetry/precisionFarming/$name")
          .vehicle
          ?.implement
          ?.first(),
      )

    // Seed + fertilizer: a drill that also spreads.
    val drill = implement("sowingMachine.json")
    assertEquals("WHEAT", assertNotNull(drill.sowing).fillType)
    assertEquals(SprayerKind.SOLID_FERTILIZER, assertNotNull(drill.spraying).kind)

    // Seed + tillage: a cultivator that also drills. `type` is `cultivatingSowingMachine`, a third
    // name again — there is no enumerable set of these.
    val topDown = implement("seedingCultivator.json")
    assertEquals("OILSEEDRADISH", assertNotNull(topDown.sowing).fruitType)
    assertEquals(TillageKind.CULTIVATOR, assertNotNull(topDown.tillage).kind)
    assertEquals(null, topDown.spraying)

    // Slurry + tillage: a disc harrow that injects.
    val methys = implement("vredoLiquidManure_discHarrow.json")
    assertEquals(SprayerKind.SLURRY_TANKER, assertNotNull(methys.spraying).kind)
    assertEquals(TillageKind.CULTIVATOR, assertNotNull(methys.tillage).kind)
  }

  @Test
  fun anApplicatorWithNoTankNamesWhatTheMachineInFrontIsFeedingIt() {
    // Both captures taken mid-application. A dribble bar and an injecting disc harrow each carry
    // nothing of their own — their `fillUnits` is a single blank unit — yet both must name the
    // material, or a terminal shows an implement doing visible work with nothing to say about it.
    fun applicator(
      name: String,
      index: Int,
    ) = assertNotNull(
      capture("telemetry/precisionFarming/$name")
        .vehicle
        ?.implement
        ?.get(index),
    )

    // The Bomech hangs off the Kaweco barrel, so it is an implement of an implement.
    val bomech = assertNotNull(applicator("liquidManure_dribbleBar.json", 0).implement.single())
    val bomechSpray = assertNotNull(bomech.spraying)
    assertEquals("DIGESTATE", bomechSpray.fillType)
    assertEquals(SprayCategory.FERTILIZER, bomechSpray.category)
    assertTrue(bomechSpray.externalSource, "material comes from the barrel it is hitched to")
    // Its own tank really is empty — which is the point: the level to watch is the barrel's, and the
    // barrel is the parent implement, so a panel has to walk *up* to find it.
    assertEquals("", assertNotNull(bomech.fillUnits?.fillUnit?.single()).type)
    assertEquals(
      "DIGESTATE",
      assertNotNull(
        applicator("liquidManure_dribbleBar.json", 0)
          .fillUnits
          ?.fillUnit
          ?.single(),
      ).type,
    )
    // Sprayer effects are running here, and the work area agrees.
    assertTrue(bomechSpray.active)
    assertTrue(assertNotNull(bomech.workAreas.single()).processing)

    val methys = applicator("vredoLiquidManure_discHarrow.json", 0)
    val methysSpray = assertNotNull(methys.spraying)
    assertEquals("LIQUIDMANURE", methysSpray.fillType)
    assertTrue(methysSpray.externalSource, "material comes from the Vredo it is hitched to")
    assertEquals("", assertNotNull(methys.fillUnits?.fillUnit?.single()).type)
    // …and here is the caveat that `active` is a positive signal only: this machine applies through
    // its CULTIVATOR work areas, not sprayer ones, so the effect predicate never fires even though
    // it is demonstrably working. Anything asking "is this implement running" must use workAreas.
    assertEquals(false, methysSpray.active)
    assertTrue(assertNotNull(methys.workAreas.single()).processing)
  }

  @Test
  fun theDoubledAmountControlIsOnlyVisibleWithoutPrecisionFarming() {
    // The same rig captured with and without PF, which is the only way to see this field work: PF
    // hard-overrides the getter to (false, false) because its variable-rate control replaces doubling
    // outright, so every PF capture says false no matter the machine.
    fun barrel(dir: String) =
      assertNotNull(
        capture("telemetry/$dir/liquidManure_dribbleBar.json")
          .vehicle
          ?.implement
          ?.first()
          ?.spraying,
      )

    // Vanilla: a slurry tanker *does* offer doubling. This is the base-game rule and it reads the
    // opposite way round from how it sounds — the engine allows it when `not isFertilizerSprayer`,
    // so slurry and manure get it and fertilizer sprayers do not.
    assertTrue(barrel("vanilla").doubledAmountAvailable)
    // …and the same machine under PF says no, correctly: the control really is gone.
    assertEquals(false, barrel("precisionFarming").doubledAmountAvailable)

    // The dribble bar behind it had doubling switched on when this was captured.
    val bomech =
      assertNotNull(
        capture("telemetry/vanilla/liquidManure_dribbleBar.json")
          .vehicle
          ?.implement
          ?.first()
          ?.implement
          ?.single()
          ?.spraying,
      )
    assertTrue(bomech.doubledAmount)
    assertTrue(bomech.doubledAmountAvailable)

    // It is parked here rather than applying, so the engine never resolved a source material — the
    // fallback's honest limit, and the reason the field is absent rather than guessed. The PF capture
    // of the same machine mid-application is the counterpart: there it names DIGESTATE and sets
    // externalSource. Same rig, and the difference is purely whether it has worked yet.
    assertEquals(null, bomech.fillType)
    assertEquals(false, bomech.externalSource)
  }

  @Test
  fun theAspectsDoNotDependOnPrecisionFarming() {
    // Everything above is derived from base-game calls, including the five-way `kind` split, which is
    // only modelled on PF's. The vanilla capture is the evidence: no `precisionFarming` subtree
    // anywhere, and the spraying aspect is fully populated regardless.
    val barrel =
      assertNotNull(
        capture("telemetry/vanilla/liquidManure_dribbleBar.json")
          .vehicle
          ?.implement
          ?.first(),
      )
    assertEquals(null, barrel.precisionFarming)

    val spraying = assertNotNull(barrel.spraying)
    assertEquals(SprayerKind.SLURRY_TANKER, spraying.kind)
    assertEquals(SprayCategory.FERTILIZER, spraying.category)
    assertEquals("DIGESTATE", spraying.fillType)
  }

  @Test
  fun aSelfPropelledMachineNeverReportsItsFuelTankAsSprayMaterial() {
    // The Vredo VT5536's own Sprayer spec resolves its tank to fill unit 1, which on a self-propelled
    // machine is the diesel tank — it used to publish `fillType: DIESEL`. The whole aspect is now
    // withheld there, because the engine derives its material *and* its kind from that same index.
    val vredo = assertNotNull(capture("telemetry/precisionFarming/vredoLiquidManure_discHarrow.json").vehicle)
    assertEquals(null, vredo.spraying)

    // The slurry it is actually carrying is still visible as ordinary cargo, and the implement doing
    // the work reports for itself — so nothing is lost by withholding the broken subtree.
    assertEquals("LIQUIDMANURE", assertNotNull(vredo.fillUnits?.fillUnit?.single()).type)
    assertEquals("LIQUIDMANURE", assertNotNull(vredo.implement.single().spraying).fillType)
  }

  @Test
  fun theRecapturedRigsCarryTheManualApplicationRate() {
    // The captures taken since mod VERSION 11 added `precisionFarming.manual`/`canToggleAuto` and 12
    // added the live `rate` — seven of the twelve in this folder, six of them carrying rate data (the
    // seventh is a herbicide boom, which PF keeps no rates for). The other five are still version 9
    // and have no rate block at all, which is the whole reason these are asserted here rather than
    // the shape being left to `SectionViewModelTest`'s inline JSON.
    val vredo = capture("telemetry/precisionFarming/vredoLiquidManure_discHarrow.json")
    assertEquals("12", vredo.version)

    val methys =
      assertNotNull(
        vredo.vehicle
          ?.implement
          ?.single()
          ?.precisionFarming,
      )
    assertTrue(methys.auto)
    assertTrue(methys.canToggleAuto, "every shipped machine lets the player leave auto")

    // The step is an index into PF's level tables and says nothing on its own, so the mod converts it
    // twice: `change` in the units the nitrogen readout already speaks, `rate` in the product it
    // costs — quoted in m³/ha here because a slurry tanker is what PF measures in cubic metres.
    val manual = assertNotNull(methys.manual)
    assertEquals(4, manual.step)
    assertEquals(1, manual.min)
    assertEquals(45, manual.max)
    assertEquals(20f, manual.change)
    assertEquals(5f, manual.rate)
    assertEquals("m³/ha", manual.rateUnit)
    // Well inside the machine's own range, so both ends of the step control are live.
    assertTrue(manual.canStep(1))
    assertTrue(manual.canStep(-1))

    // This machine was captured **in auto, mid-pass**, and it is the case that proves the two rates
    // are not the same number dressed twice: it is laying down 10 m³/ha while step 4 would nominally
    // cost 5, because auto sizes the pass to the deficit it is closing (70 against a 110 target) and
    // is free to exceed any step. Reporting only the step would have understated it by half.
    assertEquals(10f, methys.rate)
    assertEquals("m³/ha", methys.rateUnit)
    assertEquals(70f, assertNotNull(methys.primary).level)
    assertEquals(110f, methys.primary?.target)

    // Herbicide on the Rogator, which is the negative half of the contract: PF keeps no rates in that
    // mode, so the mod withholds the step even though the machine still stores one. `canToggleAuto`
    // is unaffected — the mode switch exists on a machine with nothing to apply it to.
    val boom = assertNotNull(capture("telemetry/precisionFarming/selfDrivingSprayer.json").vehicle?.precisionFarming)
    assertEquals(PfMode.OTHER, boom.mode)
    assertEquals(null, boom.manual)
    assertTrue(boom.canToggleAuto)

    // The barrel rig, recaptured after the substitution was moved onto PF's own `getIsVehicleValid`.
    // The Kaweco Profi II is sold *without* a spreading tool and declares no `<workAreas>` at all, so
    // PF rejects it as the rig's sprayer and the mod hands it the Bomech's numbers — identical step,
    // identical reading, which is the substitution PF's own HUD makes.
    //
    // Reading `spec_manureBarrel.attachedTool` instead, which is what this first shipped doing, never
    // fired here: that field needs `manureBarrel#attacherJointIndex`, an attribute whose job is to
    // silence work areas this machine does not have, so its absence from the XML is correct.
    val kaweco =
      assertNotNull(capture("telemetry/precisionFarming/liquidManure_dribbleBar.json").vehicle?.implement?.single())
    val barrel = assertNotNull(kaweco.precisionFarming)
    val bomech = assertNotNull(assertNotNull(kaweco.implement.single()).precisionFarming)
    assertEquals(false, barrel.auto)
    assertEquals(2, assertNotNull(barrel.manual).step)
    assertEquals(70f, assertNotNull(barrel.primary).level)
    assertEquals(barrel.manual, bomech.manual)
    assertEquals(barrel.primary, bomech.primary)

    // In **manual**, mid-pass, the live rate and the step's nominal cost agree — and they should:
    // PF derives the liters from the step in that mode, so the two run through the same conversion
    // from the same figure. It is auto (the Vredo above) that separates them.
    assertEquals(2.02f, barrel.rate)
    assertEquals(barrel.manual.rate, barrel.rate)
    assertEquals(barrel.rate, bomech.rate, "the borrowed block carries the live rate too")

    // What the barrel does NOT take is the per-slice strip: its `index` joins to work areas the
    // barrel does not own, so the numbers travel and the strip stays with the machine it describes.
    assertTrue(barrel.workAreas.isEmpty())
    assertEquals(1, bomech.workAreas.size)
  }

  @Test
  fun eachMachineKindIsWeighedInTheUnitPrecisionFarmingPrintsForIt() {
    // PF quotes a rate in a different unit per kind of machine, and the mod mirrors that arithmetic
    // rather than inventing one, so the terminal and the in-game display agree instead of merely
    // both being plausible. These captures are the evidence, one machine per branch.
    fun rates(name: String) =
      assertNotNull(
        capture("telemetry/precisionFarming/$name")
          .vehicle
          ?.implement
          ?.first(),
      )

    // Solid fertilizer: weighed in kilos. Auto is putting down three times what step 2 would cost,
    // because it is sizing the pass to a 90-against-120 deficit.
    val solid = assertNotNull(rates("fertilizerSpreader.json").precisionFarming)
    assertEquals(PfMode.FERTILIZER, solid.mode)
    assertEquals(111.11f, solid.rate)
    assertEquals("kg/ha", solid.rateUnit)
    assertEquals(37.04f, assertNotNull(solid.manual).rate)
    assertEquals("kg/ha", solid.manual.rateUnit)

    // The same AgriSpread hopper carrying lime instead — and now it is weighed in tonnes. The unit
    // follows what is in the tank, not what the machine is, exactly as PF's own `hasLimeLoaded`
    // branch does: one machine, two materials, two units. Nothing about the hardware changed.
    val lime = assertNotNull(rates("fertilizerSpreader_lime.json").precisionFarming)
    assertEquals(PfMode.LIME, lime.mode)
    assertEquals(4.38f, lime.rate)
    assertEquals("t/ha", lime.rateUnit)
    assertEquals(1.75f, assertNotNull(lime.manual).rate)
    // The step moves pH by a quarter, which is the increment the readout speaks in that mode.
    assertEquals(0.25f, lime.manual.change)
    assertEquals(6.38f, assertNotNull(lime.primary).level)

    // Manure: tonnes again, but decided by the machine this time rather than by the tank.
    val manure = assertNotNull(rates("manureSpreader.json").precisionFarming)
    assertEquals(5.71f, manure.rate)
    assertEquals("t/ha", manure.rateUnit)
    assertEquals(5f, assertNotNull(manure.manual).rate)
    assertEquals("t/ha", manure.manual.rateUnit)

    // Liquid fertilizer: measured in liters and weighed against nothing, the only branch that needs
    // no `massPerLiter` at all. The Patriot is self-propelled, so its rates hang off the vehicle.
    val liquid =
      assertNotNull(
        capture("telemetry/precisionFarming/selfDrivingSprayer_liquidFertilizer.json").vehicle?.precisionFarming,
      )
    assertEquals(12.82f, liquid.rate)
    assertEquals("l/ha", liquid.rateUnit)
    assertEquals(25.64f, assertNotNull(liquid.manual).rate)
    assertEquals("l/ha", liquid.manual.rateUnit)
  }

  @Test
  fun aBoomMostlyShutAppliesLessThanItsStepNominallyCosts() {
    // The Patriot in **manual** on step 2, and the live rate is half what that step nominally costs:
    // 12.82 against 25.64 l/ha. Not an error — PF scales the state change it applies by the share of
    // the boom actually open (`changeValue * alpha`, where alpha is
    // `getNumExtendedSprayerNozzleEffectsActive`'s active-over-total), then floors it at its minimum
    // rate. With 23 of 95 nozzles spraying that lands on the step-1 figure.
    //
    // So the nominal and the live rate diverge in *both* modes, for opposite reasons: auto exceeds
    // the step to close a deficit (the Vredo), and manual falls short of it when the boom is mostly
    // shut. Carrying only the step would have overstated this pass by half.
    val patriot =
      assertNotNull(capture("telemetry/precisionFarming/selfDrivingSprayer_liquidFertilizer.json").vehicle)
    val pf = assertNotNull(patriot.precisionFarming)
    assertEquals(false, pf.auto)
    assertEquals(2, assertNotNull(pf.manual).step)
    assertEquals(25.64f, pf.manual.rate)
    assertEquals(12.82f, pf.rate)

    val nozzles = assertNotNull(pf.nozzles)
    assertEquals(95, nozzles.count)
    assertEquals(23, nozzles.activeCount)

    // And the reason the app draws that nozzle bar rather than the shutoff bar, captured for the
    // first time: the base game's sections all read **on** while three quarters of the boom is shut.
    // PF removes `VariableWorkWidth`'s controls on the machines it drives nozzles for, so those
    // sections are frozen and say nothing — two bars here would be one honest and one stuck.
    val width = assertNotNull(patriot.workWidth)
    assertEquals(9, width.sections.size)
    assertTrue(width.sections.all { it.active })
    assertEquals(9, width.activeCount)
  }

  @Test
  fun theLiveRateFollowsTheToolBeingDownRatherThanGroundChangingThisInstant() {
    // The lime capture caught the exact moment the distinction matters: a work area is **active** —
    // the spreader is lowered, in contact and driving forward — while none is **processing**, which
    // is only true within 200 ms of ground actually changing.
    val spreader =
      assertNotNull(
        capture("telemetry/precisionFarming/fertilizerSpreader_lime.json")
          .vehicle
          ?.implement
          ?.first(),
      )
    assertTrue(spreader.workAreas.any { it.active })
    assertTrue(spreader.workAreas.none { it.processing })

    // And the rate is there anyway, which is the whole reason the mod gates on `getIsWorkAreaActive`.
    // Gated on processing, this machine would blink its rate away several times a second while
    // visibly spreading — and gated on nothing, it would keep printing after the boom came up, which
    // is what the in-game HUD does.
    assertEquals(4.38f, assertNotNull(spreader.precisionFarming).rate)
  }

  @Test
  fun spreaderKindAndMaterialAreSeparateQuestions() {
    // A lime spreader: the hardware is a solid-fertilizer hopper (which is what decides the rate is
    // quoted in kg/ha), the material in it is lime. Collapsing the two would lose either the unit or
    // the material — and the base game only offers the coarse split, so `kind` is deliberately finer
    // than `isFertilizerSprayer`.
    val limer =
      assertNotNull(
        VdtParser
          .parseJson(
            """{"version":"9","vehicle":{"spraying":{"kind":"SOLID_FERTILIZER",""" +
              """"fillType":"LIME","category":"LIME"}}}""",
          ).vehicle
          ?.spraying,
      )
    assertEquals(SprayerKind.SOLID_FERTILIZER, limer.kind)
    assertEquals(SprayCategory.LIME, limer.category)

    // And the kind holds while the hopper is empty — it is what the machine accepts, not what it has.
    val empty =
      assertNotNull(
        VdtParser
          .parseJson("""{"version":"9","vehicle":{"spraying":{"kind":"SOLID_FERTILIZER"}}}""")
          .vehicle
          ?.spraying,
      )
    assertEquals(SprayerKind.SOLID_FERTILIZER, empty.kind)
    assertEquals(null, empty.category)
  }

  @Test
  fun decodesThePlowSideAndLeavesItNullOnANonReversiblePlow() {
    val turned =
      assertNotNull(
        VdtParser
          .parseJson(
            """{"version":"9","vehicle":{"implement":[{"position":"BACK","plow":{"side":"LEFT",""" +
              """"rotationAllowed":true,"canToggleRotation":false,"limitToField":true,""" +
              """"forceLimitToField":false}}]}}""",
          ).vehicle
          ?.implement
          ?.single()
          ?.plow,
      )
    assertEquals(PlowSide.LEFT, turned.side)
    // Mechanically free to turn but not right now (still lowered) — the two are separate answers.
    assertTrue(turned.rotationAllowed)
    assertEquals(false, turned.canToggleRotation)

    // A plough with no turn animation reports no side at all. Null must survive as "does not
    // reverse"; defaulting it to LEFT would draw a rotation indicator on a machine that has none.
    val fixed =
      assertNotNull(
        VdtParser
          .parseJson("""{"version":"9","vehicle":{"plow":{"limitToField":false}}}""")
          .vehicle
          ?.plow,
      )
    assertEquals(null, fixed.side)
    assertEquals(false, fixed.limitToField)
  }

  @Test
  fun decodesTheTillageKinds() {
    val subsoiler =
      assertNotNull(
        VdtParser
          .parseJson(
            """{"version":"9","vehicle":{"tillage":{"kind":"SUBSOILER","deepMode":true,""" +
              """"limitToField":false}}}""",
          ).vehicle
          ?.tillage,
      )
    assertEquals(TillageKind.SUBSOILER, subsoiler.kind)
    assertEquals(false, subsoiler.limitToField)
  }

  @Test
  fun aCombinationMachineCarriesTwoAspectsAtOnce() {
    // The case the dispatch rule exists for, stated as a decode contract: one implement, two
    // functions. Nothing downstream may treat the ISOBUS aspects as mutually exclusive, and no
    // switch on `type` could have produced both sections.
    val both =
      assertNotNull(
        VdtParser
          .parseJson(
            """{"version":"9","vehicle":{"implement":[{"position":"BACK",""" +
              """"type":"pdlc_skyAgriculturePack.fertilizingSowingMachineWorkEffects",""" +
              """"sowing":{"seedIndex":1,"seedCount":9,"fruitType":"WHEAT","fillType":"WHEAT"},""" +
              """"spraying":{"kind":"SPRAYER","fillType":"FERTILIZER"}}]}}""",
          ).vehicle
          ?.implement
          ?.single(),
      )
    val hopper = assertNotNull(both.sowing)
    val tank = assertNotNull(both.spraying)
    assertEquals("WHEAT", hopper.fillType)
    assertEquals("FERTILIZER", tank.fillType)
    // …and the two join to different tanks, which is what `fillType` is carried for.
    assertTrue(hopper.fillType != tank.fillType)
  }

  // -------------------------------------------------------------------------
  // Mixer wagon (issue #113)
  // -------------------------------------------------------------------------

  /**
   * The four committed mixer captures, all vanilla and all mod version 16 — `correct` (towed, a
   * finished mix), `selfDriving_outOfRatio`, `selfDriving_single` and `selfDriving_mixing` (a valid
   * ratio with the mix cycle still counting down).
   *
   * Between them they cover the three loaded [MixState] answers, both places the aspect can sit (a
   * self-propelled machine *is* the vehicle; a towed one is an implement), a mix cycle full, part way
   * down and expired, and two different tip-side counts. What none of them can be is empty, or
   * mid-tip, which is why those stay inline below.
   */
  private fun mixerCapture(name: String): Vehicle =
    assertNotNull(capture("telemetry/vanilla/mixerWagon_$name.json").vehicle)

  /** The machine carrying the mixer — the vehicle itself, or its single implement. */
  private fun mixerMachine(name: String): Implement? = mixerCapture(name).implement.singleOrNull()

  @Test
  fun theAspectSitsOnTheVehicleOrOnTheImplement() {
    // Both shapes, captured. This is why `mixer` had to go on Vehicle *and* Implement rather than
    // only the latter, and it is the only thing that exercises either path end to end.
    val selfPropelled = mixerCapture("selfDriving_single")
    assertEquals("drivableMixerWagon", selfPropelled.type)
    assertTrue(selfPropelled.implement.isEmpty())
    assertNotNull(selfPropelled.mixer)

    val towed = mixerCapture("correct")
    assertEquals("tractor", towed.type)
    assertEquals(null, towed.mixer, "the tractor pulling it has no mixer of its own")
    val wagon = assertNotNull(towed.implement.single())
    assertEquals("mixerWagon", wagon.type)
    assertEquals("BACK", wagon.position)
    assertNotNull(wagon.mixer)
  }

  @Test
  fun theRecipeLookupLandsOnEveryCapturedMachine() {
    // MixerWagon:onLoad drops the recipe's own fill type and the ingredients' authored titles, so
    // the collector finds the recipe back by matching the ingredient names it kept. Without it there
    // is no `recipe` — and therefore no way to say a mix is finished — and the bars fall back to
    // their materials' names. Two machines, two maps' worth of loading, same four titles.
    for (name in listOf("correct", "selfDriving_single", "selfDriving_outOfRatio")) {
      val mixer = assertNotNull(mixerMachine(name)?.mixer ?: mixerCapture(name).mixer)
      assertEquals("FORAGE", mixer.recipe, name)
      assertEquals(listOf("Heu", "Silage", "Stroh", "Mineralfutter"), mixer.ingredients.map { it.title }, name)
    }
  }

  @Test
  fun theThreeLoadedMixStatesAreEachCaptured() {
    // Read off the tub's fill type, never recomputed from the bars — and here is each answer on a
    // real machine.
    val ready = assertNotNull(assertNotNull(mixerMachine("correct")).mixer)
    assertEquals("FORAGE", ready.fillType)
    assertEquals("Totalmischration", ready.title)
    assertEquals(MixState.READY, ready.state)

    val offRatio = assertNotNull(mixerCapture("selfDriving_outOfRatio").mixer)
    assertEquals("FORAGE_MIXING", offRatio.fillType)
    assertEquals("Futter", offRatio.title)
    assertEquals(MixState.OUT_OF_RATIO, offRatio.state)

    // One material in: the tub reports that material, and it is not a mix at all. Note the recipe is
    // still resolved — `state` is SINGLE because the fill type is not the recipe's, not for want of one.
    val single = assertNotNull(mixerCapture("selfDriving_single").mixer)
    assertEquals("DRYGRASS_WINDROW", single.fillType)
    assertEquals("FORAGE", single.recipe)
    assertEquals(MixState.SINGLE, single.state)
  }

  @Test
  fun aMixerBarIsAShareOfTheLoadNotOfTheCapacity() {
    // The single easiest thing to get wrong, and the out-of-ratio capture contains a real instance of
    // it rather than a constructed one. The game's own HUD sums the ingredient levels and divides by
    // that sum. The straw is 39% of the 18000 l load — over its 30% ceiling, which is exactly why the
    // engine called the whole mix FORAGE_MIXING — but only 28% of the 25000 l tub, which is inside. A
    // bar drawn against `capacity` would have shown four ticks on a mix the game had already rejected.
    val mixer = assertNotNull(mixerCapture("selfDriving_outOfRatio").mixer)
    assertEquals(18000.0, mixer.loaded)

    val straw = mixer.ingredients.single { it.name == "straw" }
    val share = mixer.shareOf(straw)
    assertEquals(0.389, share, 0.001)
    assertTrue(!straw.holds(share), "7000 of 18000 l is 39%, over straw's 30% ceiling")
    assertTrue(straw.holds(7000.0 / mixer.capacity), "against capacity it would read 28% and pass")

    // …and it is the *only* one outside its window, so the engine's verdict has exactly one cause.
    assertEquals(listOf(straw), mixer.ingredients.filterNot { it.holds(mixer.shareOf(it)) })
  }

  @Test
  fun theBarsCanAllBeWrongWhileTheMixIsFine() {
    // The other half of that rule, from the single-material capture: 6000 l of hay and nothing else
    // is 100% hay against a 20–75% window, and silage is at 0% against a 20% floor — two ingredients
    // "outside" their windows on a machine that is simply mid-load. The engine says so itself by
    // reporting the material rather than FORAGE_MIXING, which is why the panel only passes a verdict
    // when the engine has.
    val mixer = assertNotNull(mixerCapture("selfDriving_single").mixer)
    assertEquals(MixState.SINGLE, mixer.state)
    assertEquals(2, mixer.ingredients.count { !it.holds(mixer.shareOf(it)) })
  }

  @Test
  fun anIngredientPoolingSeveralMaterialsIsUnweighed() {
    // Half of the base game's forage recipe turns out to be pooled, which is what made this worth
    // guarding: one litre count for two materials of different density, and no record of which went
    // in. Absent rather than a weight computed from whichever we reached first.
    val byName = assertNotNull(mixerCapture("selfDriving_outOfRatio").mixer).ingredients.associateBy { it.name }

    val hay = assertNotNull(byName["dryGrass"])
    assertEquals(listOf("DRYGRASS_WINDROW", "HAY_PELLETS"), hay.fillTypes)
    assertEquals(6000.0, hay.value)
    assertEquals(null, hay.mass, "6000 l of it, and still no honest weight")

    val silage = assertNotNull(byName["silage"])
    assertEquals(listOf("SILAGE"), silage.fillTypes)
    assertEquals(2.25, silage.mass)
  }

  @Test
  fun anIngredientTheRecipeMakesOptionalIsNeverShort() {
    // Straw and mineral feed really are declared with a 0% minimum, so an empty mineral-feed bar is
    // not a fault and must not be flagged as one — which `holds` gets right only because the window
    // is inclusive of its ends. The "correct" capture is a finished mix carrying none of it at all.
    val byName = assertNotNull(assertNotNull(mixerMachine("correct")).mixer).ingredients.associateBy { it.name }
    val mineral = assertNotNull(byName["mineralFeed"])
    assertEquals(0, mineral.minPercentage)
    assertEquals(7, mineral.maxPercentage)
    assertEquals(0.0, mineral.value)
    assertTrue(mineral.holds(0.0))
  }

  @Test
  fun theMixCycleIsCapturedBothRunningAndExpired() {
    // `remaining` counts MixerWagon's activeTimer down from `mixingTime` after every fill change, and
    // the engine never clamps it — so the only two things a panel may show are a positive countdown
    // and nothing. All three states are here: a full cycle just restarted, one part way down, and a
    // parked wagon with none left.
    // …and this one is the reason the timer is worth showing at all: its ratio is already valid and
    // the engine already calls the load FORAGE, but the mixing time has not run out.
    val justFilled = assertNotNull(mixerCapture("selfDriving_mixing").mixer)
    assertEquals(MixState.READY, justFilled.state)
    assertEquals(5000, justFilled.mixingTime)
    assertEquals(5000, justFilled.remaining, "a fill change resets the timer to the machine's full time")

    val partWay = assertNotNull(mixerCapture("selfDriving_single").mixer)
    assertEquals(1514, partWay.remaining)

    val parked = assertNotNull(assertNotNull(mixerMachine("correct")).mixer)
    assertEquals(0, parked.remaining)
  }

  @Test
  fun theDrumTurnsBecauseTheMachineIsOnNotBecauseItIsMixing() {
    // The pair that cost the panel its "Pickup" chip. On the self-propelled captures turn-on and the
    // drum are the same observable; on the parked towed one both are off while it is still powered,
    // which is the only reason `powered` is worth a field of its own.
    val on = mixerCapture("selfDriving_single")
    assertEquals(true, on.isTurnedOn)
    assertTrue(assertNotNull(on.mixer).running)

    val off = assertNotNull(mixerMachine("correct"))
    assertEquals(false, off.isTurnedOn)
    val mixer = assertNotNull(off.mixer)
    assertTrue(!mixer.running)
    assertTrue(mixer.powered, "hitched to a running tractor, just not switched on")
  }

  @Test
  fun theCapturedWagonsNameTheirTipSides() {
    // Two machines, two different counts, all localized by the engine at load and 1-indexed by
    // `preferredSide`. `side` is absent on both because neither was tipping.
    val fourWay = assertNotNull(mixerCapture("selfDriving_single").tipping)
    assertEquals(TipState.CLOSED, fourWay.state)
    assertEquals(4, fourWay.count)
    assertEquals(listOf("Links", "Rechts", "Links hinten", "Rechts hinten"), fourWay.sides)
    assertEquals(1, fourWay.preferredSide)
    assertEquals(null, fourWay.side)

    val twoWay = assertNotNull(assertNotNull(mixerMachine("correct")).tipping)
    assertEquals(2, twoWay.count)
    assertEquals(listOf("Links", "Rechts"), twoWay.sides)
    assertEquals(2, twoWay.preferredSide)
    assertEquals("Rechts", twoWay.sides[assertNotNull(twoWay.preferredSide) - 1])
  }

  @Test
  fun aMixerWithoutARecipeIsStillAMixer() {
    // No `#recipe` in the machine's XML: an empty ingredient list and a trailer with a drum. Nothing
    // may assume bars exist, and without a recipe we cannot claim the load is finished feed. Inline,
    // because every machine captured so far declares one.
    val mixer =
      assertNotNull(
        VdtParser
          .parseJson(
            """{"version":"16","vehicle":{"mixer":{"running":true,"powered":true,""" +
              """"remaining":0,"mixingTime":5000,"value":5000.0,"capacity":8000,""" +
              """"fillType":"SILAGE"}}}""",
          ).vehicle
          ?.mixer,
      )
    assertTrue(mixer.ingredients.isEmpty())
    assertEquals(null, mixer.recipe)
    assertEquals(0.0, mixer.loaded)
    assertEquals(MixState.SINGLE, mixer.state)
  }

  @Test
  fun anEmptyTubIsEmptyWhateverTheBarsSay() {
    // The one state no capture can be in, since a wagon worth capturing has something in it.
    val mixer =
      assertNotNull(
        VdtParser
          .parseJson(
            """{"version":"16","vehicle":{"mixer":{"running":false,"powered":true,""" +
              """"remaining":0,"mixingTime":5000,"value":0.0,"capacity":12000,"recipe":"FORAGE",""" +
              """"ingredients":[{"name":"silage","minPercentage":20,"maxPercentage":75,"value":0.0}]}}}""",
          ).vehicle
          ?.mixer,
      )
    assertEquals(MixState.EMPTY, mixer.state)
    assertEquals(0.0, mixer.shareOf(mixer.ingredients.single()))
  }

  @Test
  fun mixerHoldsIsInclusiveOfItsWindowEnds() {
    val hay = assertNotNull(mixerCapture("selfDriving_single").mixer).ingredients.first()
    assertEquals(20, hay.minPercentage)
    assertEquals(75, hay.maxPercentage)
    assertTrue(hay.holds(0.20))
    assertTrue(hay.holds(0.75))
    assertTrue(!hay.holds(0.1999))
    assertTrue(!hay.holds(0.7501))
  }

  // -------------------------------------------------------------------------
  // Mass, and the tip sides' names
  // -------------------------------------------------------------------------

  @Test
  fun massIsTheMachineNotItsLoad() {
    // The towed capture: an 8.2 t wagon reading 12.12 t with 12400 l in it. The difference looks like
    // a payload and is not one — see [Mass] and the two tests below.
    val mass = assertNotNull(assertNotNull(mixerMachine("correct")).mass)
    assertEquals(12.12, mass.value)
    assertEquals(8.2, mass.empty)
  }

  @Test
  fun massesDoNotSumIntoATrainWeight() {
    // AttacherJoints:getAdditionalComponentMass folds a HARD-ATTACHED implement's whole mass into its
    // attacher's root component, so the tractor is already carrying the wagon. Adding the two would
    // count 12 t twice — and the tractor's own difference-from-empty is the wagon rather than
    // anything it holds.
    val towed = mixerCapture("correct")
    val tractor = assertNotNull(towed.mass)
    val wagon = assertNotNull(assertNotNull(towed.implement.single()).mass)
    assertEquals(17.024, tractor.value)
    assertEquals(3.59, tractor.empty)
    assertTrue(
      tractor.value - assertNotNull(tractor.empty) > wagon.value,
      "a 3.6 t tractor does not carry 13 t of anything: that is the wagon, counted on both",
    )
  }

  @Test
  fun theMassDifferenceIsNotALoadOnASelfPropelledMachine() {
    // The reason [Mixer.mass] exists. All three self-propelled captures are the same machine with the
    // same 14.965 t empty mass and a full 270 l diesel tank, so the mix's density and the constant on
    // top of it can be solved for directly: 0.300 kg/l, and 835 kg of *machine* that is not the load.
    // Only 224 kg of that is the diesel; the rest is whatever else the engine adds. An empty wagon
    // reads that constant as its "payload", which is what a real game showed as 617 kg of nothing.
    val mixing = assertNotNull(mixerCapture("selfDriving_mixing").mixer)
    val outOfRatio = assertNotNull(mixerCapture("selfDriving_outOfRatio").mixer)
    val a = assertNotNull(mixerCapture("selfDriving_mixing").mass)
    val b = assertNotNull(mixerCapture("selfDriving_outOfRatio").mass)
    assertEquals(a.empty, b.empty, "same machine")

    val perLitre = (b.value - a.value) / (outOfRatio.value - mixing.value)
    assertEquals(0.0003, perLitre, 0.000001)

    val constant = (a.value - assertNotNull(a.empty)) - mixing.value * perLitre
    assertTrue(constant > 0.8, "an empty tub would still read ${'$'}constant t of 'load'")
  }

  @Test
  fun theTubIsWeighedOnItsOwn() {
    // What the panel prints, and it reaches zero. Inline because the committed captures are version
    // 16 and predate the field; a re-capture would pin it against a real machine.
    fun tub(json: String) = assertNotNull(VdtParser.parseJson(json).vehicle?.mixer)

    val loaded = tub("""{"version":"17","vehicle":{"mixer":{"value":18000.0,"capacity":25000,"mass":5.4}}}""")
    assertEquals(5.4, loaded.mass)

    // An empty tub weighs nothing — 0 rather than absent, so the panel has a number to print.
    val empty = tub("""{"version":"17","vehicle":{"mixer":{"value":0.0,"capacity":25000,"mass":0.0}}}""")
    assertEquals(0.0, empty.mass)

    // Absent when the mod could not resolve a density; the panel falls back to the gross mass.
    assertEquals(null, tub("""{"version":"17","vehicle":{"mixer":{"value":100.0,"capacity":25000}}}""").mass)
  }

  @Test
  fun theEmptyMassIsAbsentUntilTheEngineHasRunItsFirstMassUpdate() {
    // getDefaultMass reads `component.defaultMass or 0` until Vehicle:updateMass has filled it in, so
    // the mod omits it rather than reporting a zero that would make the machine look weightless empty.
    val mass =
      assertNotNull(
        VdtParser.parseJson("""{"version":"17","vehicle":{"mass":{"value":7.2}}}""").vehicle?.mass,
      )
    assertEquals(7.2, mass.value)
    assertEquals(null, mass.empty)
  }

  @Test
  fun tipSidesAreNamedAndOneIndexed() {
    // The names are 1-indexed by `side`/`preferredSide` because the engine's indices are, so a
    // consumer reads `sides[side - 1]`. Off by one here mislabels which way the wagon is unloading.
    // Inline for the mid-tip case: every captured wagon was closed, so `side` is absent on all of
    // them and only `preferredSide` has ever been seen resolved.
    val tipping =
      assertNotNull(
        VdtParser
          .parseJson(
            """{"version":"16","vehicle":{"tipping":{"state":"OPEN","side":2,""" +
              """"preferredSide":3,"count":3,"sides":["Links","Rechts","Hinten"]}}}""",
          ).vehicle
          ?.tipping,
      )
    assertEquals(TipState.OPEN, tipping.state)
    assertEquals("Rechts", tipping.sides[assertNotNull(tipping.side) - 1])
    assertEquals("Hinten", tipping.sides[assertNotNull(tipping.preferredSide) - 1])
  }

  @Test
  fun tipSidesAreEmptyRatherThanMissingOnAnUnnamedTrailer() {
    val tipping =
      assertNotNull(
        VdtParser
          .parseJson("""{"version":"16","vehicle":{"tipping":{"state":"CLOSED","count":1}}}""")
          .vehicle
          ?.tipping,
      )
    assertTrue(tipping.sides.isEmpty())
  }

  @Test
  fun serverMessageUsesTypeDiscriminator() {
    val msg: ServerMessage = ServerMessage.Telemetry(model("combine.json"))
    val encoded = json.encodeToString(ServerMessage.serializer(), msg)
    assertTrue(encoded.contains("\"type\":\"telemetry\""), "expected discriminator, got: $encoded")

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(msg, decoded)
  }
}
