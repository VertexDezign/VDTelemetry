package net.vertexdezign.vdt

import net.vertexdezign.vdt.model.AdsCheck
import net.vertexdezign.vdt.model.AdsLamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `vehicle.ads` subtree the mod writes when Advanced Damage System is installed (mod version 13,
 * `src/integrations/AdvancedDamageSystem.lua`).
 *
 * There is no committed fixture for it — a capture needs the mod running — so these decode the shape
 * the collector emits. What is worth pinning down is the tolerance the whole model is built on: a
 * machine that reports only some of this must decode to nulls rather than to zeroes, because for
 * every field here "absent" is a real and different answer from "none".
 */
class AdsModelTest {
  @Test
  fun decodesTheWholeSubtree() {
    val ads =
      assertNotNull(
        VdtParser
          .parseJson(
            """
            {
              "version": "13",
              "vehicle": {
                "motor": { "temperatur": { "value": 93, "min": 20, "max": 120, "unit": "°C" } },
                "ads": {
                  "lamps": { "engine": "OFF", "warning": "WARN", "brakes": "OFF",
                             "battery": "CRIT", "coolant": "COLD", "service": "OFF" },
                  "service": { "hours": 3.3, "interval": 5.4 },
                  "inspected": { "condition": 73, "service": 42, "complete": true },
                  "checks": { "radiator": "HEAVY", "airIntake": "OK", "lubrication": "VERY_DRY" },
                  "electrical": { "systemVoltage": 13.8, "unit": "V" },
                  "transmissionTemperatur": { "value": 71, "min": 20, "max": 120, "unit": "°C" }
                }
              }
            }
            """.trimIndent(),
          ).vehicle
          ?.ads,
      )

    val lamps = assertNotNull(ads.lamps)
    assertEquals(AdsLamp.OFF, lamps.engine)
    assertEquals(AdsLamp.WARN, lamps.warning)
    assertEquals(AdsLamp.CRIT, lamps.battery)
    assertEquals(AdsLamp.COLD, lamps.coolant)

    val service = assertNotNull(ads.service)
    assertEquals(3.3f, service.hours)
    assertEquals(5.4f, service.interval)
    assertTrue(service.fraction < 1f, "3.3 of 5.4 hours is not yet due")

    val inspected = assertNotNull(ads.inspected)
    assertEquals(73, inspected.condition)
    assertEquals(42, inspected.service)
    assertTrue(inspected.complete)

    val checks = assertNotNull(ads.checks)
    assertEquals(AdsCheck.HEAVY, checks.radiator)
    assertEquals(AdsCheck.OK, checks.airIntake)
    assertEquals(AdsCheck.VERY_DRY, checks.lubrication)

    assertEquals(13.8f, assertNotNull(ads.electrical).systemVoltage)
    assertEquals(71, assertNotNull(ads.transmissionTemperatur).value)
  }

  @Test
  fun everyAbsentPartStaysNullRatherThanBecomingZero() {
    // What a plain tractor under ADS looks like: no CVT, nothing to grease, never inspected, and too
    // old for four of the six lamps. Each of those is a distinct answer from a zero or an OFF.
    val ads =
      assertNotNull(
        VdtParser
          .parseJson(
            """
            {"version":"13","vehicle":{"ads":{
              "lamps":{"battery":"OFF","coolant":"WARN"},
              "checks":{"radiator":"SLIGHT","airIntake":"OK"}
            }}}
            """.trimIndent(),
          ).vehicle
          ?.ads,
      )
    val lamps = assertNotNull(ads.lamps)
    assertEquals(AdsLamp.OFF, lamps.battery)
    assertNull(lamps.engine, "a lamp the machine does not have must not decode as OFF")
    assertNull(lamps.service)
    assertNull(assertNotNull(ads.checks).lubrication, "nothing to grease is not 'grease is fine'")
    assertNull(ads.service)
    assertNull(ads.inspected)
    assertNull(ads.electrical)
    assertNull(ads.transmissionTemperatur, "no CVT is not a very cold one")
  }

  @Test
  fun aVehicleWithoutTheModHasNoAdsSubtreeAtAll() {
    assertNull(VdtParser.parseJson("""{"version":"13","vehicle":{"name":"Fendt"}}""").vehicle?.ads)
  }

  @Test
  fun anUnknownLampOrBandFromANewerModDoesNotBreakTheParse() {
    // The lenient contract the whole model relies on: the mod is free to add states ahead of the app.
    val vehicle =
      assertNotNull(
        VdtParser
          .parseJson(
            """{"version":"14","vehicle":{"ads":{"lamps":{"engine":"OFF"},"somethingNew":{"a":1}}}}""",
          ).vehicle,
      )
    assertEquals(AdsLamp.OFF, assertNotNull(vehicle.ads?.lamps).engine)
  }
}
