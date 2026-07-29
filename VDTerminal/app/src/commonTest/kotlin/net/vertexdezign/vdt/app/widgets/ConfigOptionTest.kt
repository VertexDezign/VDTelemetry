package net.vertexdezign.vdt.app.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ConfigOption.resolveAll] — the multi-select contract, which differs from [ConfigOption.resolve] on
 * both ends: unset means *everything* rather than the first choice, and a stale value is dropped
 * rather than handed back.
 */
class MultiConfigOptionTest {
  private val lamps =
    ConfigOption(
      key = "lamps",
      label = "Lamps",
      choices = listOf(
        ConfigOption.Choice("turnLeft", "Turn left"),
        ConfigOption.Choice("hazard", "Hazard"),
        ConfigOption.Choice("beacon", "Beacon"),
      ),
      multi = true,
    )

  @Test
  fun anUnconfiguredInstanceTakesEveryChoice() {
    // A band that showed nothing until configured would just look broken.
    assertEquals(listOf("turnLeft", "hazard", "beacon"), lamps.resolveAll(emptyMap()))
  }

  @Test
  fun aStoredSetIsReadBackInChoiceOrder() {
    // Order comes from the choices, not from storage, so a band reads the same however it was clicked
    // together.
    assertEquals(listOf("turnLeft", "beacon"), lamps.resolveAll(mapOf("lamps" to "beacon,turnLeft")))
  }

  @Test
  fun selectingNothingIsAnAnswer() {
    // Distinct from unset, which is why the empty string has to survive the round trip.
    assertEquals(emptyList(), lamps.resolveAll(mapOf("lamps" to "")))
  }

  @Test
  fun aValueNoLongerOfferedIsDropped() {
    // Unlike the single-choice case: the set *is* the setting, so a lamp that no longer exists is one
    // missing lamp rather than a tile whose whole subject went wrong.
    assertEquals(listOf("hazard"), lamps.resolveAll(mapOf("lamps" to "hazard,retiredLamp")))
  }

  @Test
  fun joinRoundTripsThroughResolveAll() {
    val chosen = listOf("turnLeft", "beacon")
    assertEquals(chosen, lamps.resolveAll(mapOf("lamps" to ConfigOption.join(chosen))))
  }
}

/**
 * [ConfigOption.resolve] is the whole contract between a stored config and what a widget renders:
 * what was saved, or the first choice when nothing was.
 */
class ConfigOptionTest {
  private val option =
    ConfigOption(
      key = "app",
      label = "Opens",
      choices = listOf(
        ConfigOption.Choice("map", "Map"),
        ConfigOption.Choice("storage", "Storage"),
      ),
    )

  @Test
  fun aStoredValueWins() {
    assertEquals("storage", option.resolve(mapOf("app" to "storage")))
  }

  @Test
  fun anUnconfiguredInstanceTakesTheFirstChoice() {
    assertEquals("map", option.resolve(emptyMap()))
  }

  @Test
  fun aStoredValueIsKeptEvenWhenItIsNoLongerOffered() {
    // Choices narrow between sessions — an app is unavailable until its channel arrives — so
    // substituting a different one here would retarget a tile the user placed deliberately. The
    // widget decides what an unrecognised value means; resolve just hands it back.
    assertEquals("uninstalledMod", option.resolve(mapOf("app" to "uninstalledMod")))
  }

  @Test
  fun otherWidgetsKeysAreIgnored() {
    assertEquals("map", option.resolve(mapOf("target" to "storage")))
  }

  @Test
  fun nullOnlyWithNothingStoredAndNothingToChooseFrom() {
    // An option whose choices are all currently unavailable still can't invent a value — but a tile
    // configured before they went away keeps the one it has.
    val empty = ConfigOption(key = "app", label = "Opens", choices = emptyList())
    assertNull(empty.resolve(emptyMap()))
    assertEquals("map", empty.resolve(mapOf("app" to "map")))
  }
}
