package net.vertexdezign.vdt

import net.vertexdezign.vdt.model.AdsLamp
import net.vertexdezign.vdt.model.AdsLoad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                  "electrical": { "systemVoltage": 13.8, "unit": "V" },
                  "load": { "value": 112, "overloadAt": 85, "unit": "%" },
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

    val load = assertNotNull(ads.load)
    // Past 100 on purpose: ADS lets the draft term take it to 115, and the overrun is the point.
    assertEquals(112.0, load.value)
    assertTrue(load.overloaded)
    assertFalse(AdsLoad(value = 84.0, overloadAt = 85.0).overloaded)
    // A machine reporting no threshold must not read as permanently overloaded.
    assertFalse(AdsLoad(value = 50.0, overloadAt = 0.0).overloaded)

    assertEquals(13.8f, assertNotNull(ads.electrical).systemVoltage)
    assertEquals(71, assertNotNull(ads.transmissionTemperatur).value)
  }

  @Test
  fun everyAbsentPartStaysNullRatherThanBecomingZero() {
    // What a plain tractor under ADS looks like: no CVT, never inspected, and too old for four of
    // the six lamps. Each of those is a distinct answer from a zero or an OFF.
    val ads =
      assertNotNull(
        VdtParser
          .parseJson(
            """
            {"version":"13","vehicle":{"ads":{
              "lamps":{"battery":"OFF","coolant":"WARN"}
            }}}
            """.trimIndent(),
          ).vehicle
          ?.ads,
      )
    val lamps = assertNotNull(ads.lamps)
    assertEquals(AdsLamp.OFF, lamps.battery)
    assertNull(lamps.engine, "a lamp the machine does not have must not decode as OFF")
    assertNull(lamps.service)
    assertNull(ads.service)
    assertNull(ads.inspected)
    assertNull(ads.electrical)
    assertNull(ads.load)
    assertNull(ads.transmissionTemperatur, "no CVT is not a very cold one")
  }

  @Test
  fun aVehicleWithoutTheModHasNoAdsSubtreeAtAll() {
    assertNull(VdtParser.parseJson("""{"version":"13","vehicle":{"name":"Fendt"}}""").vehicle?.ads)
  }

  @Test
  fun anUnknownLampOrBandFromANewerModDoesNotBreakTheParse() {
    // The lenient contract the whole model relies on: the mod is free to add states ahead of the app.
    // Both halves of it — a block the app has never heard of, and a severity it has never heard of.
    val vehicle =
      assertNotNull(
        VdtParser
          .parseJson(
            """
            {"version":"14","vehicle":{"ads":{
              "lamps":{"engine":"OFF","coolant":"MELTDOWN"},
              "somethingNew":{"a":1}
            }}}
            """.trimIndent(),
          ).vehicle,
      )
    val lamps = assertNotNull(vehicle.ads?.lamps)
    assertEquals(AdsLamp.OFF, lamps.engine)
    // A severity we cannot read is a severity we say nothing about — the same answer as a lamp the
    // machine does not have, which is the honest one: we do not know how bad MELTDOWN is.
    assertNull(lamps.coolant, "an unknown severity must coerce to null, not throw")
  }
}
