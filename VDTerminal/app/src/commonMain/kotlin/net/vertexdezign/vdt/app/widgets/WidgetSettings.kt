package net.vertexdezign.vdt.app.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.russhwolf.settings.Settings

/**
 * The instance id of the tile currently being rendered; `WidgetGrid` provides it around each cell.
 *
 * Ambient rather than another [Widget.Content] parameter because almost nothing needs it: it is the
 * key for a widget's own view state, which most widgets don't have. The default covers rendering
 * outside any grid — an app's own full-screen view, which is a single stable place of its own and so
 * keeps its own zoom and filters rather than borrowing some tile's.
 */
val LocalWidgetInstance = staticCompositionLocalOf { "app" }

/**
 * One instance's **view state** — zoom, pan, which filters are open — stored in [Settings] under a
 * per-instance prefix.
 *
 * This is the other half of [WidgetConfig], and the split is deliberate:
 *
 * - **Config** is what the user declared. It belongs to the layout, so it rides on the
 *   `LayoutCell` and is deleted with it.
 * - **View state** is incidental and written constantly — a pinch stores a new zoom on every frame
 *   of the gesture — and `PageStore` re-encodes the entire page list on every write. Putting it on
 *   the cell would rewrite every page of the layout per gesture, so it lives here instead.
 *
 * The prefix is what makes two tiles of one widget independent: before it, a second map on a page
 * shared the first's zoom, filters and ground layer through the same global keys.
 */
class WidgetSettings(private val settings: Settings, instanceId: String) {
  private val prefix = prefixFor(instanceId)

  fun getFloat(name: String, default: Float): Float = settings.getFloat(prefix + name, default)

  fun putFloat(name: String, value: Float): Unit = settings.putFloat(prefix + name, value)

  fun getBoolean(name: String, default: Boolean): Boolean = settings.getBoolean(prefix + name, default)

  fun putBoolean(name: String, value: Boolean): Unit = settings.putBoolean(prefix + name, value)

  fun getString(name: String, default: String): String = settings.getString(prefix + name, default)

  fun putString(name: String, value: String): Unit = settings.putString(prefix + name, value)

  companion object {
    private fun prefixFor(instanceId: String) = "vdt.widget.$instanceId."

    /**
     * Forgets everything scoped to [instanceId] — what a removed tile leaves behind.
     *
     * Called by `PageStore` for every instance that disappears from the saved layout, so a new tile
     * can never inherit the zoom of a deleted one that happened to be given the same id.
     */
    fun purge(settings: Settings, instanceId: String) {
      val prefix = prefixFor(instanceId)
      for (key in settings.keys.filter { it.startsWith(prefix) }) settings.remove(key)
    }
  }
}

/** [WidgetSettings] for the tile being rendered, per [LocalWidgetInstance]. */
@Composable
fun rememberWidgetSettings(settings: Settings): WidgetSettings {
  val instanceId = LocalWidgetInstance.current
  return remember(settings, instanceId) { WidgetSettings(settings, instanceId) }
}
