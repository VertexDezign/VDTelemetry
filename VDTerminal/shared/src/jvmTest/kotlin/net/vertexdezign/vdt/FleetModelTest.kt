package net.vertexdezign.vdt

import kotlinx.serialization.json.Json
import net.vertexdezign.vdt.model.AdsState
import net.vertexdezign.vdt.model.FillDisplayType
import net.vertexdezign.vdt.model.FleetData
import net.vertexdezign.vdt.model.GameDate
import net.vertexdezign.vdt.model.PropertyState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `fleet.json` channel the mod writes for the farm's machines
 * (`src/collect/FleetExporter.lua`, plus the ADS block from
 * `src/integrations/AdvancedDamageSystem.lua`).
 *
 * [parsesTheCapture] runs the committed `examples/json/fleet` capture through the real server path.
 * The rest use inline JSON, for the states that capture does not contain — a machine in a workshop,
 * one carrying a fault, one ADS has never looked at — and say so where they do.
 *
 * What is worth pinning down either way is where **absent means something**: a machine with no
 * condition reported is not a machine in perfect condition, a leased one has no sell value rather
 * than a zero one, and an implement is not a machine that has been taken out of the tab rotation.
 */
class FleetModelTest {
  private val json = Json { encodeDefaults = true }

  private fun example(name: String): String {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
      val candidate = File(dir, "examples/json/fleet/$name")
      if (candidate.exists()) return candidate.readText()
      dir = dir.parentFile
    }
    error("Could not locate examples/json/fleet/$name from ${File(".").absolutePath}")
  }

  private fun assertRoundTrips(data: FleetData) {
    val encoded = json.encodeToString(FleetData.serializer(), data)
    assertEquals(data, json.decodeFromString(FleetData.serializer(), encoded), "JSON round-trip should be lossless")
  }

  @Test
  fun parsesTheCapture() {
    val data = VdtParser.parseFleet(example("fleet.json"))
    assertEquals("1", data.version)
    assertEquals(GameDate(year = 1, month = 6, day = 1), data.date)
    assertEquals(31, data.vehicles.size)
    assertRoundTrips(data)

    val byId = data.vehicles.associateBy { it.id }

    // The tractor a helper is driving, and the two mowers on it: the engine chain-walks its AI flag
    // up the attacher joints, so an implement of a working rig says so on its own row.
    val deutz = assertNotNull(byId[626])
    assertTrue(deutz.isAI)
    assertTrue(deutz.isMotorized)
    assertEquals(100, assertNotNull(assertNotNull(deutz.motorFillUnits).fuel).fillLevelPercentage)
    assertTrue(assertNotNull(byId[627]).isAI, "a mower behind a helper is being driven by it")
    assertEquals(626, assertNotNull(byId[628]).attachedTo)

    // The tractor the capturing player was sitting in. isControlled is on the seat, so the slurry
    // tanker and the trailing shoe behind it carry neither flag — the app reads them off the rig.
    val puma = assertNotNull(byId[630])
    assertTrue(puma.isEntered)
    assertTrue(puma.isControlled)
    assertFalse(assertNotNull(byId[648]).isControlled, "an implement has no seat to be controlled from")
    assertEquals(630, assertNotNull(byId[648]).attachedTo)

    // Machines out of the tab rotation — the capturing player's parking mod, confirmed by them. The
    // flag would read the same on a machine whose own XML ships isTabbable="false", which is the one
    // thing it cannot tell apart (see FleetVehicle.isParked).
    assertTrue(assertNotNull(byId[635]).isParked, "the fire engine")
    assertTrue(assertNotNull(byId[640]).isParked, "the old IVECO")
    assertFalse(assertNotNull(byId[650]).isParked, "the Actros is in the rotation")
    assertNull(assertNotNull(byId[645]).isTabbable, "a spreader has no seat at all")

    // An electric machine gets no ADS block: that mod excludes them outright.
    val loader = assertNotNull(byId[652])
    val charge = assertNotNull(assertNotNull(loader.motorFillUnits).fuel)
    assertEquals("electriccharge", charge.type)
    assertEquals("kWh", charge.unit)
    assertNull(loader.ads)

    // The one leased machine: a leasing rate and no sell value.
    val leased = assertNotNull(byId[653])
    assertEquals(PropertyState.LEASED, leased.propertyState)
    assertEquals(14818, leased.leasePerDay)
    assertNull(leased.sellPrice)

    // The two machines a contract lent the farm — the one place this channel lists something the
    // game's own overview does not. Neither is owned, so neither carries a price of either kind.
    val mbTrac = assertNotNull(byId[654])
    val baler = assertNotNull(byId[655])
    assertEquals(PropertyState.MISSION, mbTrac.propertyState)
    assertEquals(PropertyState.MISSION, baler.propertyState)
    for (borrowed in listOf(mbTrac, baler)) {
      assertNull(borrowed.sellPrice, "contract equipment is not the farm's to sell")
      assertNull(borrowed.leasePerDay, "and it is not leased either")
    }
    // The baler brings the first STEP fill unit any capture has had: net measured in whole rolls.
    val net = assertNotNull(baler.fillUnits).fillUnit.single { it.type == "BALE_NET" }
    assertEquals(FillDisplayType.STEP, net.display)
    assertEquals(2f, net.value)
    assertEquals(2, net.capacity)
    // It has been worked, unlike everything the farm bought: 41.9 hours and real wear on the clock.
    assertEquals(41.9f, baler.hours)
    assertEquals(17, assertNotNull(baler.wearable).wear)

    // A fresh save, so every ADS record is the one its purchase wrote: nothing overdue, nothing
    // broken, and no inspection thorough enough to make its condition figure exact.
    val ads = assertNotNull(deutz.ads)
    assertEquals(AdsState.READY, ads.state)
    assertFalse(ads.isServiceOverdue)
    assertFalse(ads.needsAttention)
    assertEquals(100, assertNotNull(ads.inspected).condition)
    assertFalse(assertNotNull(ads.inspected).complete)
    assertEquals(GameDate(year = 1, month = 6, day = 1), ads.lastInspection)
    assertNull(ads.lastMaintenance, "never serviced")
    assertNull(ads.maintenanceCost)
  }

  @Test
  fun parsesTheMultiplayerCapture() {
    val data = VdtParser.parseFleet(example("mp.json"))
    val today = assertNotNull(data.date)
    assertEquals(GameDate(year = 2, month = 6, day = 1), today)
    assertEquals(63, data.vehicles.size)
    assertRoundTrips(data)

    val byId = data.vehicles.associateBy { it.id }

    // THE ONE THAT MATTERS. Advanced Damage System's per-vehicle spec syncs to a client — state,
    // condition and the whole maintenance log — which is why the collector reads it rather than the
    // mod's own ADS_Main.vehicles table, keyed by a uniqueId that is nil on a client. Thirteen of
    // these machines carry the block, six with a service history behind them.
    assertEquals(13, data.vehicles.count { it.ads != null })
    val vredo = assertNotNull(assertNotNull(byId[434]).ads)
    assertEquals(95, assertNotNull(vredo.inspected).condition)
    assertEquals(5290, vredo.maintenanceCost)
    assertEquals(GameDate(year = 2, month = 6, day = 1), vredo.lastMaintenance)
    assertEquals(0, today.monthsSince(assertNotNull(vredo.lastMaintenance)), "serviced this month")

    val jd6m = assertNotNull(assertNotNull(byId[442]).ads)
    assertEquals(91, assertNotNull(jd6m.inspected).condition)
    assertEquals(9, today.monthsSince(assertNotNull(jd6m.lastInspection)), "inspected nine months ago")

    // A played-in fleet: vanilla damage is real on the implements ADS does not manage, and pinned to
    // zero on the machines it does — the two-source rule the app reads condition by.
    assertEquals(63, assertNotNull(assertNotNull(byId[410]).wearable).damage, "a well-used trailer")
    val valtra = assertNotNull(byId[464])
    assertEquals(0, assertNotNull(valtra.wearable).damage, "ADS pins it")
    assertEquals(100, assertNotNull(valtra.wearable).wear, "but the paint is still real, and gone")
    assertEquals(79, assertNotNull(assertNotNull(valtra.ads).inspected).condition)

    // Seven machines put away with the parking mod, a forage harvester among them.
    assertEquals(7, data.vehicles.count { it.isParked })
    assertTrue(assertNotNull(byId[436]).isParked)

    // Consumables the way the game measures them: rolls of wrap as whole slots, additive to a
    // hundredth of a litre.
    val wrap = assertNotNull(assertNotNull(byId[452]).fillUnits).fillUnit.single()
    assertEquals(FillDisplayType.STEP, wrap.display)
    assertEquals(6, wrap.capacity)
    val additive = assertNotNull(assertNotNull(byId[447]).fillUnits).fillUnit.single { it.type == "SILAGE_ADDITIVE" }
    assertEquals(2, additive.precision)

    assertTrue(data.vehicles.none { it.propertyState == PropertyState.MISSION }, "no contract running")
  }

  @Test
  fun decodesAPlainMachine() {
    val data =
      VdtParser.parseFleet(
        """
        {
          "version": "1",
          "date": { "year": 2, "month": 7, "day": 11 },
          "vehicles": [
            {
              "id": 42,
              "name": "Fendt 942 Vario",
              "type": "tractor",
              "category": "Tractors",
              "age": 14,
              "hours": 1234.5,
              "propertyState": "OWNED",
              "sellPrice": 284900,
              "wearable": { "damage": 12, "wear": 31, "dirt": 64, "unit": "%" },
              "motorFillUnits": {
                "fuel": { "value": 320, "type": "diesel", "title": "Diesel", "unit": "l",
                          "capacity": 600, "fillLevelPercentage": 53 }
              },
              "isAI": true,
              "posX": 0.5, "posZ": 0.75
            }
          ]
        }
        """.trimIndent(),
      )

    assertEquals("1", data.version)
    assertEquals(GameDate(2, 7, 11), data.date)
    val machine = data.vehicles.single()
    assertEquals(42, machine.id)
    assertEquals("Tractors", machine.category)
    assertEquals(14, machine.age)
    assertEquals(1234.5f, machine.hours)
    assertEquals(PropertyState.OWNED, machine.propertyState)
    assertEquals(284900, machine.sellPrice)
    assertNull(machine.leasePerDay, "an owned machine has no leasing rate")
    assertEquals(12, assertNotNull(machine.wearable).damage)
    assertEquals(53, assertNotNull(machine.motorFillUnits?.fuel).fillLevelPercentage)
    assertTrue(machine.isMotorized)
    assertTrue(machine.isAI)
    assertFalse(machine.isIdle, "a helper is driving it")
    assertFalse(machine.isParked, "nobody has put it away")
    assertNull(machine.ads, "no ADS block without the mod")
  }

  @Test
  fun decodesAnImplementAndALeasedMachine() {
    val data =
      VdtParser.parseFleet(
        """
        {
          "version": "1",
          "vehicles": [
            { "id": 7, "name": "Lemken Juwel 8", "type": "toolTrailed", "category": "Ploughs",
              "age": 3, "hours": 61.2, "propertyState": "OWNED", "attachedTo": 42,
              "wearable": { "damage": 40, "wear": 55, "dirt": 90, "unit": "%" } },
            { "id": 9, "name": "Claas Lexion 8900", "type": "harvester", "age": 0, "hours": 3.5,
              "propertyState": "LEASED", "leasePerDay": 6000,
              "motorFillUnits": { "fuel": { "value": 40, "capacity": 1000, "fillLevelPercentage": 4 } } }
          ]
        }
        """.trimIndent(),
      )

    val (plough, combine) = data.vehicles
    assertFalse(plough.isMotorized, "an implement has no motor fill units")
    assertEquals(42, plough.attachedTo)
    assertTrue(plough.isIdle, "nobody is driving a plough")
    assertNull(plough.isTabbable, "and it has no seat to be tabbed into")
    assertFalse(plough.isParked)

    assertEquals(PropertyState.LEASED, combine.propertyState)
    assertEquals(6000, combine.leasePerDay)
    assertNull(combine.sellPrice, "a leased machine has no sell value — not a zero one")
  }

  @Test
  fun decodesTheAdsMaintenanceBlock() {
    val ads =
      assertNotNull(
        VdtParser
          .parseFleet(
            """
            {
              "version": "1",
              "date": { "year": 2, "month": 7, "day": 11 },
              "vehicles": [
                {
                  "id": 1, "name": "Deutz-Fahr 6190", "type": "tractor", "age": 26, "hours": 812.4,
                  "propertyState": "OWNED", "sellPrice": 91000,
                  "wearable": { "damage": 0, "wear": 44, "dirt": 12, "unit": "%" },
                  "ads": {
                    "state": "MAINTENANCE",
                    "inspected": { "condition": 61, "service": 18, "complete": false },
                    "service": { "hours": 71.5, "interval": 60 },
                    "lastInspection": { "year": 2, "month": 4, "day": 3 },
                    "lastMaintenance": { "year": 1, "month": 12, "day": 20 },
                    "breakdowns": [
                      { "id": "ENGINE_OIL_LEAK", "part": "Engine", "severity": "Major",
                        "description": "Oil is dripping from the sump", "stage": 2 }
                    ],
                    "workshop": { "remaining": 4.3, "finishHour": 16.5, "finishInDays": 1, "price": 1450 },
                    "maintenanceCost": 8600
                  }
                }
              ]
            }
            """.trimIndent(),
          ).vehicles
          .single()
          .ads,
      )

    assertEquals(AdsState.MAINTENANCE, ads.state)
    assertTrue(ads.isInWorkshop)
    assertTrue(ads.isServiceOverdue, "71.5 hours into a 60-hour interval")
    assertTrue(ads.needsAttention)
    assertEquals(61, assertNotNull(ads.inspected).condition)
    assertFalse(assertNotNull(ads.inspected).complete, "an ordinary inspection, not a full one")
    assertEquals("ENGINE_OIL_LEAK", ads.breakdowns.single().id)
    assertEquals(2, ads.breakdowns.single().stage)
    assertEquals(1, assertNotNull(ads.workshop).finishInDays)
    assertEquals(8600, ads.maintenanceCost)

    // The two dates read against the document's own "today", which is why it is carried.
    val today = GameDate(2, 7, 11)
    assertEquals(3, today.monthsSince(assertNotNull(ads.lastInspection)))
    assertEquals(7, today.monthsSince(assertNotNull(ads.lastMaintenance)))
  }

  @Test
  fun toleratesAMachineAdsHasNeverLookedAt() {
    val ads =
      assertNotNull(
        VdtParser
          .parseFleet(
            """
            { "version": "1", "vehicles": [
              { "id": 1, "name": "Massey Ferguson 8S", "type": "tractor", "age": 1, "hours": 12.0,
                "propertyState": "OWNED", "ads": { "state": "READY" } }
            ] }
            """.trimIndent(),
          ).vehicles
          .single()
          .ads,
      )

    assertEquals(AdsState.READY, ads.state)
    assertNull(ads.inspected, "never inspected — and that is not a condition of 100%")
    assertNull(ads.workshop)
    assertTrue(ads.breakdowns.isEmpty())
    assertFalse(ads.needsAttention)
  }

  @Test
  fun aStateThisBuildCannotNameIsNotReadToBeReady() {
    // What a later ADS with a state we have never heard of would put on the wire. The lenient parser
    // coerces the token onto the field's default, so the default has to be the honest answer: a
    // machine standing in a workshop this build cannot name must not read as ready to work.
    val ads =
      assertNotNull(
        VdtParser
          .parseFleet(
            """
            { "version": "1", "vehicles": [
              { "id": 1, "name": "Massey Ferguson 8S", "type": "tractor", "age": 1, "hours": 12.0,
                "propertyState": "OWNED", "ads": { "state": "CALIBRATION" } }
            ] }
            """.trimIndent(),
          ).vehicles
          .single()
          .ads,
      )

    assertEquals(AdsState.UNKNOWN, ads.state)
    assertFalse(ads.isInWorkshop, "unread is not the same as in the shop")
    assertTrue(ads.needsAttention, "whatever it is, it is not the state that reads as ready")
  }

  @Test
  fun aMachineTakenOutOfTheTabRotationReadsAsParked() {
    // What the parking mods do: Enterable:setIsTabbable(false). The flag is saved and synced, so it
    // survives a reload and reaches a multiplayer client.
    val data =
      VdtParser.parseFleet(
        """
        { "version": "1", "vehicles": [
          { "id": 1, "name": "Parked", "type": "tractor", "age": 4, "hours": 90.0,
            "propertyState": "OWNED", "isTabbable": false },
          { "id": 2, "name": "Ready", "type": "tractor", "age": 4, "hours": 90.0,
            "propertyState": "OWNED", "isTabbable": true }
        ] }
        """.trimIndent(),
      )

    val (parked, ready) = data.vehicles
    assertTrue(parked.isParked)
    assertTrue(parked.isIdle, "put away and undriven are separate questions")
    assertFalse(ready.isParked)
  }

  @Test
  fun anEmptyFleetIsStillADocument() {
    // Spectating, or a farm that owns nothing: the mod writes the document without a vehicle list,
    // which has to decode as an empty fleet rather than as no channel at all.
    val data = VdtParser.parseFleet("""{ "version": "1" }""")
    assertEquals("1", data.version)
    assertTrue(data.vehicles.isEmpty())
    assertNull(data.date)
  }
}
