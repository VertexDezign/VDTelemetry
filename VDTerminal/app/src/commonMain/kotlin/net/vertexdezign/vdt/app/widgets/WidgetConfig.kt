package net.vertexdezign.vdt.app.widgets

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A placed widget instance's settings, stored on its
 * [net.vertexdezign.vdt.app.layout.LayoutCell] and handed to
 * [Widget.Content].
 *
 * A plain string map rather than a type per widget: the layout schema then stays independent of the
 * widget types that give the values meaning, so a page round-trips through JSON on its own, and a
 * key some widget has since stopped reading is an entry nothing looks at instead of a decode
 * failure. Widgets don't index it directly — they go through [ConfigOption.resolve], which turns a
 * missing or no-longer-offered value into the option's default.
 */
typealias WidgetConfig = Map<String, String>

/**
 * One per-instance setting a [Widget] accepts: pick one of [choices], stored under [key].
 *
 * Deliberately not a general form model. Every setting there is a use for so far is a single choice
 * from a short list ("which app does this shortcut open", "which slot does this render"), which one
 * dialog can render and one string map can hold. The first widget that genuinely needs a slider or
 * free text is the moment to widen this — not before.
 *
 * The options a widget declares may depend on the session (an app that isn't installed shouldn't be
 * offered), which is why [Widget.configOptions] is composable.
 */
data class ConfigOption(val key: String, val label: String, val choices: List<Choice>) {
  /** One selectable value. [label] is what the dialog shows; [value] is what gets persisted. */
  data class Choice(val value: String, val label: String, val icon: ImageVector? = null)

  /**
   * The value in force for [config]: what it stores, or the first choice when that key is unset.
   * Null only when the widget offers nothing at all.
   *
   * A stored value is returned **verbatim, even when it is no longer among [choices]** — this
   * deliberately does not validate. Choices can narrow between sessions (they are composable, and
   * an app is unavailable until its channel arrives, so a shortcut's list is briefly short right
   * after connecting), and quietly swapping in a different one would retarget a tile the user placed
   * on purpose. Only the widget knows whether a value it no longer offers means "fall back" or "say
   * so", so it gets to decide.
   */
  fun resolve(config: WidgetConfig): String? = config[key] ?: choices.firstOrNull()?.value
}
