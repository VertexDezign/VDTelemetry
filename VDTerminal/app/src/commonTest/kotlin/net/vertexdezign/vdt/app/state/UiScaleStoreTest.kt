package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UiScaleStoreTest {
  @Test
  fun startsAtTheBrowsersOwnSize() {
    assertEquals(100, UiScaleStore(MapSettings()).percent.value)
  }

  @Test
  fun refusesASizeThatIsNotAStep() {
    assertFailsWith<IllegalArgumentException> { UiScaleStore(MapSettings()).set(123) }
  }

  @Test
  fun persistsAChoiceAndForgetsTheDefault() {
    val settings = MapSettings()
    UiScaleStore(settings).set(130)
    assertEquals(130, UiScaleStore(settings).percent.value)

    UiScaleStore(settings).set(100)
    assertNull(settings.getIntOrNull(UiScaleStore.KEY))
  }

  @Test
  fun aStoredValueOffTheStepsFallsBackToTheDefault() {
    val settings = MapSettings()
    settings.putInt(UiScaleStore.KEY, 123)
    assertEquals(100, UiScaleStore(settings).percent.value)
  }
}
