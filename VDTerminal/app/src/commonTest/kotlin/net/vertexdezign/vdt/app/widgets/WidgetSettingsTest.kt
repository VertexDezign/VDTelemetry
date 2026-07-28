package net.vertexdezign.vdt.app.widgets

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WidgetSettings] is what makes two tiles of one widget independent — the reason a page can hold an
 * overview map and a zoomed-in one at the same time.
 */
class WidgetSettingsTest {
  @Test
  fun twoInstancesDoNotSeeEachOthersState() {
    val settings = MapSettings()
    val first = WidgetSettings(settings, "w-1")
    val second = WidgetSettings(settings, "w-2")

    first.putFloat("zoom", 4f)
    second.putFloat("zoom", 1f)

    assertEquals(4f, first.getFloat("zoom", 1f))
    assertEquals(1f, second.getFloat("zoom", 1f))
  }

  @Test
  fun anUnwrittenNameFallsBackToTheDefault() {
    val instance = WidgetSettings(MapSettings(), "w-1")
    assertEquals(1f, instance.getFloat("zoom", 1f))
    assertTrue(instance.getBoolean("autoCenter", true))
    assertEquals("none", instance.getString("groundLayer", "none"))
  }

  @Test
  fun purgeForgetsOneInstanceAndLeavesTheRestAlone() {
    val settings = MapSettings()
    WidgetSettings(settings, "w-1").putFloat("zoom", 4f)
    WidgetSettings(settings, "w-1").putString("groundLayer", "soil")
    WidgetSettings(settings, "w-2").putFloat("zoom", 8f)
    settings.putString("vdt.pages.v2", "[]") // an unrelated key must survive too

    WidgetSettings.purge(settings, "w-1")

    assertEquals(1f, WidgetSettings(settings, "w-1").getFloat("zoom", 1f)) // back to the default
    assertEquals("none", WidgetSettings(settings, "w-1").getString("groundLayer", "none"))
    assertEquals(8f, WidgetSettings(settings, "w-2").getFloat("zoom", 1f))
    assertEquals("[]", settings.getString("vdt.pages.v2", ""))
  }

  @Test
  fun purgeMatchesOnTheWholePrefixNotJustItsStart() {
    // "w-1" must not take "w-10" with it — instance ids are minted from a base-36 counter-free
    // random, so one being a prefix of another is entirely possible.
    val settings = MapSettings()
    WidgetSettings(settings, "w-1").putFloat("zoom", 4f)
    WidgetSettings(settings, "w-10").putFloat("zoom", 8f)

    WidgetSettings.purge(settings, "w-1")

    assertEquals(8f, WidgetSettings(settings, "w-10").getFloat("zoom", 1f))
  }
}
