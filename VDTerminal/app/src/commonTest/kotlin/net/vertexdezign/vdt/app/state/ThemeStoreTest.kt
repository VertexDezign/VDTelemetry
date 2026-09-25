package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeStoreTest {
  @Test
  fun followsTheDeviceUntilToldOtherwise() {
    assertEquals(ThemeMode.System, ThemeStore(MapSettings(), MutableStateFlow(false)).mode.value)
    assertTrue(ThemeStore.isDark(ThemeMode.System, systemDark = true))
    assertFalse(ThemeStore.isDark(ThemeMode.System, systemDark = false))
    // An explicit choice wins over the device either way.
    assertFalse(ThemeStore.isDark(ThemeMode.Light, systemDark = true))
    assertTrue(ThemeStore.isDark(ThemeMode.Dark, systemDark = false))
  }

  @Test
  fun persistsAChoiceAndForgetsItOnAuto() {
    val settings = MapSettings()
    ThemeStore(settings, MutableStateFlow(false)).set(ThemeMode.Dark)
    assertEquals(ThemeMode.Dark, ThemeStore(settings, MutableStateFlow(false)).mode.value)

    ThemeStore(settings, MutableStateFlow(false)).set(ThemeMode.System)
    assertNull(settings.getStringOrNull(ThemeStore.KEY))
  }

  @Test
  fun cyclesThroughAllThree() {
    val store = ThemeStore(MapSettings(), MutableStateFlow(false))
    store.cycle()
    assertEquals(ThemeMode.Light, store.mode.value)
    store.cycle()
    assertEquals(ThemeMode.Dark, store.mode.value)
    store.cycle()
    assertEquals(ThemeMode.System, store.mode.value)
  }

  @Test
  fun anUnknownStoredValueFallsBackToAuto() {
    val settings = MapSettings()
    settings.putString(ThemeStore.KEY, "Sepia")
    assertEquals(ThemeMode.System, ThemeStore(settings, MutableStateFlow(false)).mode.value)
  }
}
