package net.vertexdezign.vdt.app.widgets

import androidx.compose.runtime.Composable
import net.vertexdezign.vdt.app.apps.AppRegistry
import net.vertexdezign.vdt.app.apps.availableApps

/**
 * The catalog of placeable [Widget]s: every widget contributed by a registered app
 * ([net.vertexdezign.vdt.app.apps.VdtApp.widgets]). Every widget belongs to an app — to add one,
 * add it to its app's `widgets`.
 *
 * Saved page layouts resolve their widget ids here (unknown ids are dropped on load).
 */
object WidgetRegistry {
  val widgets: List<Widget> = AppRegistry.apps.flatMap { it.widgets }

  fun byId(id: String): Widget? = widgets.firstOrNull { it.id == id }
}

/**
 * The widgets that can actually be placed right now: those provided by an available app. Ownership
 * carries availability, so a widget never has to restate its app's mod-installed condition.
 *
 * Only the add-widget picker uses this. Widgets *already placed* on a page are deliberately left
 * alone when their app goes away — they render their own "not installed" state rather than being
 * silently deleted from a layout the user built, and they light up again if the mod returns.
 */
@Composable
fun availableWidgets(): List<Widget> = availableApps().flatMap { it.widgets }
