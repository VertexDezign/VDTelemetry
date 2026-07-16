package net.vertexdezign.vdt.app.apps

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.widgets.Widget

/**
 * A launchable **App** — a single-purpose feature (Map, Tasks, Crop Rotation), modelled after a
 * normal iOS/Android app. Like those, an app owns a dedicated full-screen view ([FullPage]) and may
 * contribute one or more [widgets] to the catalog that pages can place as tiles.
 *
 * Apps are registered in [AppRegistry]; their widgets flow into `WidgetRegistry` automatically. This
 * is distinct from a [net.vertexdezign.vdt.app.pages.Page], which is a *customizable canvas* of
 * widgets rather than a single feature.
 */
interface VdtApp {
  /** Stable identifier, persisted as the open app. */
  val id: String
  val title: String
  val icon: ImageVector

  /**
   * Widgets this app provides to the catalog (placeable on any page). Often just the app's own tile
   * today; an app may grow to expose several.
   */
  val widgets: List<Widget> get() = emptyList()

  /** The app's dedicated full-screen view, filling [modifier]'s bounds. */
  @Composable
  fun FullPage(modifier: Modifier)
}
