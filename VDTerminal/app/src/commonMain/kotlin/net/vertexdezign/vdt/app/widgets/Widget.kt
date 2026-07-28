package net.vertexdezign.vdt.app.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A placeable dashboard tile — the panels ("widgets") an app arranges on its screen. A widget pulls
 * whatever it renders from `LocalVdtStore`; the grid only positions it. [title]/[icon] identify the
 * widget in the add-widget picker (the tile itself draws its own panel chrome).
 *
 * Register widgets in [WidgetRegistry]; a [net.vertexdezign.vdt.app.layout.GridLayout] refers to them
 * by [id], so ids must be stable — they're persisted in saved layouts.
 */
interface Widget {
  val id: String
  val title: String
  val icon: ImageVector

  /**
   * The size this tile is placed at, in cells of the [net.vertexdezign.vdt.app.layout.GridLayout]
   * (12×7, so a cell is roughly 91×90dp — square — on a landscape tablet). Placement squeezes the
   * tile down towards [minColSpan] × [minRowSpan] when the default doesn't fit where it was dropped.
   */
  val defaultColSpan: Int get() = 4
  val defaultRowSpan: Int get() = 3

  /**
   * The smallest this tile may be resized to and still be readable. This is the readability floor —
   * it lives here, per widget, rather than on the cell, which is what lets the grid be fine enough
   * for small tiles (a shortcut) and large ones (the map) to coexist on one page.
   */
  val minColSpan: Int get() = 3
  val minRowSpan: Int get() = 2

  /** Renders the tile filling [modifier]'s bounds (each widget supplies its own panel chrome). */
  @Composable
  fun Content(modifier: Modifier)
}
