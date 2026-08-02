package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.DischargeReason
import net.vertexdezign.vdt.model.DischargeState
import net.vertexdezign.vdt.model.DriveDirection
import net.vertexdezign.vdt.model.FillDisplayType
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.MotorState
import net.vertexdezign.vdt.model.PipeState
import net.vertexdezign.vdt.model.PlowSide
import net.vertexdezign.vdt.model.SprayCategory
import net.vertexdezign.vdt.model.SprayerKind
import net.vertexdezign.vdt.model.TillageKind
import net.vertexdezign.vdt.model.TipState
import net.vertexdezign.vdt.model.VdtData
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
  fun decodesTheSownCropOffTheSowingAspect() {
    // The crop is the one thing a seeding terminal exists to say and the fill unit cannot: a hopper
    // reports the fill type it holds, never which of the machine's declared seeds is selected. The
    // first capture taken at v9 (issue #58), so it is also the in-game proof that the aspect works.
    val data = model("sowingMachine.json")
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
    val seeder = assertNotNull(model("sowingMachine.json").vehicle?.implement?.single())
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

  @Test
  fun serverMessageUsesTypeDiscriminator() {
    val msg: ServerMessage = ServerMessage.Telemetry(model("combine.json"))
    val encoded = json.encodeToString(ServerMessage.serializer(), msg)
    assertTrue(encoded.contains("\"type\":\"telemetry\""), "expected discriminator, got: $encoded")

    val decoded = json.decodeFromString(ServerMessage.serializer(), encoded)
    assertEquals(msg, decoded)
  }
}
