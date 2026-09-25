package net.vertexdezign.vdt.app.state

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which palette this device asked for. [System] follows the browser's `prefers-color-scheme`. */
enum class ThemeMode {
  System,
  Light,
  Dark,
  ;

  /** The next mode in the header toggle's cycle. */
  fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Light or dark, per device. Every tablet in a cab has its own room to answer to, so the choice is
 * persisted in this browser's storage rather than shared.
 *
 * [systemDark] is the browser's own preference, live — an iPad set to switch at sunset switches the
 * terminal with it while [mode] is [ThemeMode.System].
 */
class ThemeStore(private val settings: Settings, val systemDark: StateFlow<Boolean>) {
  private val _mode =
    MutableStateFlow(
      settings.getStringOrNull(KEY)?.let { stored ->
        ThemeMode.entries.firstOrNull { it.name == stored }
      }
        ?: ThemeMode.System,
    )

  val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

  fun set(mode: ThemeMode) {
    _mode.value = mode
    if (mode == ThemeMode.System) settings.remove(KEY) else settings.putString(KEY, mode.name)
  }

  fun cycle() = set(_mode.value.next())

  companion object {
    const val KEY = "vdt.theme"

    /** Whether [mode] resolves to the dark palette, given the browser's own preference. */
    fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
      ThemeMode.System -> systemDark
      ThemeMode.Light -> false
      ThemeMode.Dark -> true
    }
  }
}
