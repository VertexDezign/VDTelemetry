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

  /** Renders the tile filling [modifier]'s bounds (each widget supplies its own panel chrome). */
  @Composable
  fun Content(modifier: Modifier)
}
