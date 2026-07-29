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
 * One per-instance setting a [Widget] accepts: pick from [choices], stored under [key]. One choice
 * normally; [multi] makes it any number of them.
 *
 * Deliberately not a general form model. Nearly every setting is a single choice from a short list
 * ("which app does this shortcut open", "which slot does this render"), which one dialog can render
 * and one string map can hold. The telltale band is the case that made [multi] worth having — which
 * lamps matter differs per rig, and the alternative was a band that grows forever. A slider or free
 * text is still not here, and should wait for a widget that genuinely needs one.
 *
 * The options a widget declares may depend on the session (an app that isn't installed shouldn't be
 * offered), which is why [Widget.configOptions] is composable.
 */
data class ConfigOption(val key: String, val label: String, val choices: List<Choice>, val multi: Boolean = false) {
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

  /**
   * The values in force for a [multi] option: what it stores, split on [SEPARATOR], or **every**
   * choice when the key is unset.
   *
   * All-by-default rather than none: an unconfigured telltale band that showed no lamps would look
   * broken, and "I haven't said" is much better served by "show me what you have" than by an empty
   * tile. Selecting nothing is still expressible and still means nothing — it stores the empty
   * string, which is why absent and empty have to be told apart here rather than collapsed.
   *
   * Order comes from [choices], not from storage, so a band reads the same however it was clicked
   * together. Values no longer offered are dropped, unlike [resolve]'s single value: the set is the
   * setting here, so a stale entry is just a lamp that isn't shown rather than the whole tile's
   * subject going wrong.
   */
  fun resolveAll(config: WidgetConfig): List<String> {
    val stored = config[key] ?: return choices.map { it.value }
    val selected = stored.split(SEPARATOR).filter { it.isNotBlank() }.toSet()
    return choices.map { it.value }.filter { it in selected }
  }

  companion object {
    /** Joins a [multi] option's values. Choice values are our own slugs, so a comma can't occur in one. */
    const val SEPARATOR = ","

    /** Stores [values] for a [multi] option, in the form [resolveAll] reads back. */
    fun join(values: Iterable<String>): String = values.joinToString(SEPARATOR)
  }
}
