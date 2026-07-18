package net.vertexdezign.vdt.app.apps

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.alerts.AlertRule
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

  /**
   * Alert rules this app raises from the data streams, evaluated shell-wide by the
   * [net.vertexdezign.vdt.app.alerts.AlertEngine] regardless of what's on screen. An app whose mod
   * is missing never fires: its rules read channels that are absent, and absent data freezes a
   * rule (see [net.vertexdezign.vdt.app.alerts.AlertInputs]).
   */
  val alerts: List<AlertRule> get() = emptyList()

  /**
   * Whether this app exists in *this* installation — i.e. its optional mod is present. An
   * unavailable app is hidden from the launcher, and its [widgets] are withheld from the picker;
   * there's no point offering a screen that can only say "mod not installed".
   *
   * This is about the feature *existing*, not about it having data right now: the Vehicle app stays
   * available while you're on foot (its widgets render their own empty state). Core apps leave it
   * at true.
   *
   * Composable so the launcher reacts if a mod appears or disappears mid-session.
   */
  @Composable
  fun isAvailable(): Boolean = true

  /** The app's dedicated full-screen view, filling [modifier]'s bounds. */
  @Composable
  fun FullPage(modifier: Modifier)
}
