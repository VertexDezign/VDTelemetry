package net.vertexdezign.vdt.app.widgets

import net.vertexdezign.vdt.app.apps.AppRegistry

/**
 * The catalog of placeable [Widget]s: every widget contributed by a registered app
 * ([net.vertexdezign.vdt.app.apps.VdtApp.widgets]). Every widget belongs to an app — to add one,
 * add it to its app's `widgets`.
 *
 * The add-widget picker lists these, and saved page layouts resolve their widget ids here (unknown
 * ids are dropped on load).
 */
object WidgetRegistry {
  val widgets: List<Widget> = AppRegistry.apps.flatMap { it.widgets }

  fun byId(id: String): Widget? = widgets.firstOrNull { it.id == id }
}
