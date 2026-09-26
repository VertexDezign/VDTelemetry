package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How big this device draws the terminal, as a percentage of the browser's own dp.
 *
 * The screens in use run from a phone to a 13" tablet held at arm's length in a cab, and no one dp
 * size is right for all of them — a button that is comfortable on the iPad is a speck on a small
 * Android tablet, and text sized for the phone wastes the big screen. So each device picks for
 * itself, persisted in its own browser storage. Everything scales together (type, icons, touch
 * targets, spacing), which is what keeps a panel laid out the same at every size; the page grid still
 * fills the screen, so a bigger size means bigger contents in the same tiles.
 */
class UiScaleStore(private val settings: Settings) {
  private val _percent = MutableStateFlow(settings.getIntOrNull(KEY)?.takeIf { it in STEPS } ?: DEFAULT)

  val percent: StateFlow<Int> = _percent.asStateFlow()

  fun set(percent: Int) {
    require(percent in STEPS) { "not a UI size step: $percent" }
    _percent.value = percent
    if (percent == DEFAULT) settings.remove(KEY) else settings.putInt(KEY, percent)
  }

  companion object {
    const val KEY = "vdt.uiScale"
    const val DEFAULT = 100

    /** The sizes on offer. A handful of fixed steps rather than a slider: tapped in a cab, not dragged. */
    val STEPS = listOf(85, 100, 115, 130, 150)
  }
}
