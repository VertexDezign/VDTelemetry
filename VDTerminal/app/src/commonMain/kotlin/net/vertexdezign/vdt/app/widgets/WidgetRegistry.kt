package net.vertexdezign.vdt.app.widgets

import androidx.compose.runtime.Composable
import net.vertexdezign.vdt.app.apps.AppRegistry
import net.vertexdezign.vdt.app.apps.availableApps

/**
 * The catalog of placeable [Widget]s: every widget contributed by a registered app
 * ([net.vertexdezign.vdt.app.apps.VdtApp.widgets]), plus the [ShortcutWidget]. Widgets belong to an
 * app — to add one, add it to its app's `widgets`.
 *
 * The shortcut is the exception, and belongs to none: it is a tile that opens *whichever* app its
 * instance names, so it is registered once here rather than per app.
 *
 * Saved page layouts resolve their widget ids here (unknown ids are dropped on load).
 */
object WidgetRegistry {
  val widgets: List<Widget> = AppRegistry.apps.flatMap { it.widgets } + ShortcutWidget

  fun byId(id: String): Widget? = widgets.firstOrNull { it.id == id }
}

/**
 * The widgets that can actually be placed right now: those provided by an available app, plus the
 * shortcut. Ownership carries availability, so a widget never has to restate its app's mod-installed
 * condition; the shortcut carries its own, narrowing the apps it offers instead of disappearing.
 *
 * Only the add-widget picker uses this. Widgets *already placed* on a page are deliberately left
 * alone when their app goes away — they render their own "not installed" state rather than being
 * silently deleted from a layout the user built, and they light up again if the mod returns.
 */
@Composable
fun availableWidgets(): List<Widget> = availableApps().flatMap { it.widgets } + ShortcutWidget
