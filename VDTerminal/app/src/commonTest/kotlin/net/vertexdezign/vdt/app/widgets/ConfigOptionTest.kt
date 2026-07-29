package net.vertexdezign.vdt.app.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
