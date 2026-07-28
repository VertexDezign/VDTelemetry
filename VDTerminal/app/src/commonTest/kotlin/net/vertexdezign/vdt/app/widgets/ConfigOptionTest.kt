package net.vertexdezign.vdt.app.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ConfigOption.resolve] is the whole contract between a stored config and what a widget renders: a
 * saved value only survives while the widget still offers it, and anything else falls back rather
 * than leaving the tile with nothing to show.
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
  fun aStoredValueStillOnOfferWins() {
    assertEquals("storage", option.resolve(mapOf("app" to "storage")))
  }

  @Test
  fun anUnconfiguredInstanceTakesTheFirstChoice() {
    assertEquals("map", option.resolve(emptyMap()))
  }

  @Test
  fun aValueNoLongerOnOfferFallsBackInsteadOfBlanking() {
    // The case this exists for: a shortcut pointed at a mod that has since been uninstalled, so the
    // app it names is no longer among the choices.
    assertEquals("map", option.resolve(mapOf("app" to "uninstalledMod")))
  }

  @Test
  fun otherWidgetsKeysAreIgnored() {
    assertEquals("map", option.resolve(mapOf("target" to "storage")))
  }

  @Test
  fun nullOnlyWhenThereIsNothingToChooseFrom() {
    val empty = ConfigOption(key = "app", label = "Opens", choices = emptyList())
    assertNull(empty.resolve(mapOf("app" to "map")))
  }
}
