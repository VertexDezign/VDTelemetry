package net.vertexdezign.vdt.app.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A placeable dashboard tile — the panels ("widgets") an app arranges on its screen. A widget pulls
 * whatever it renders from `LocalVdtStore`; the page layout only positions it. [title]/[icon] identify the
 * widget in the add-widget picker (the tile itself draws its own panel chrome).
 *
 * Register widgets in [WidgetRegistry]; a [net.vertexdezign.vdt.app.layout.Tile] refers to them by
 * [id], so ids must be stable — they're persisted in saved layouts.
 */
interface Widget {
  val id: String
  val title: String
  val icon: ImageVector

  /**
   * The smallest this tile may be made and still be read — the readability floor, in dp, per widget.
   *
   * Floors **gate edits, not rendering**: a split, a divider drag or a swap that would put the tile
   * below it is refused (see [net.vertexdezign.vdt.app.layout.LayoutFrame]), but a page arranged on a
   * bigger screen and opened on a smaller one renders its tiles anyway, and the widget's compact form
   * has to cope. The defaults are what the old grid's default floor (3×2 cells) came to on a portrait
   * phone's 56dp cell — the smallest these were already being drawn at.
   */
  val minWidth: Dp get() = 184.dp
  val minHeight: Dp get() = 120.dp

  /**
   * The per-instance settings this widget accepts, empty (the default) when it takes none. Each
   * placed tile carries its own answers, so two instances of one widget on the same page can show
   * different things.
   *
   * Declaring an option is all a widget has to do to become configurable: the picker asks for the
   * answers as it places the tile, the edit overlay grows a gear to change them later, and the values
   * arrive back through [Content]'s `config`.
   *
   * Composable so the choices can react to the session — the same reason
   * [net.vertexdezign.vdt.app.apps.VdtApp.isAvailable] is.
   */
  @Composable
  fun configOptions(): List<ConfigOption> = emptyList()

  /**
   * Renders the tile filling [modifier]'s bounds (each widget supplies its own panel chrome), for
   * the instance configured by [config] — normally read through [ConfigOption.resolve], which
   * supplies the default for a key that was never set.
   *
   * A widget must cope with a value it no longer offers rather than assume one of its current
   * choices: [resolve][ConfigOption.resolve] hands stored values back untouched precisely so this
   * decision is the widget's. Falling back to the default is fine where the choices are fixed;
   * where they come and go, saying so is usually better than quietly showing something else.
   *
   * The default lets the callers that render a widget outside any page — an app's own full-screen
   * view — say nothing about configuration, which is exactly what they mean.
   */
  @Composable
  fun Content(modifier: Modifier, config: WidgetConfig = emptyMap())
}
