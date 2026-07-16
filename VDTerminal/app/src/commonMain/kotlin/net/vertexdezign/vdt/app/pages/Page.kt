package net.vertexdezign.vdt.app.pages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import net.vertexdezign.vdt.app.layout.GridLayout

/**
 * A **Page** — a screen the *user* composes from widgets, like an Android home screen. Unlike a
 * [net.vertexdezign.vdt.app.apps.VdtApp] (a code-defined feature with its own screen), pages are
 * user data: created, renamed, rearranged and deleted at runtime and persisted by [PageStore].
 */
@Serializable
data class Page(val id: String, val title: String, val icon: PageIcon, val autoShow: AutoShow, val layout: GridLayout)

/**
 * When a page should be shown automatically. On each enter/leave transition the shell activates the
 * first page matching the new state; [Never] pages are only reachable from the launcher.
 */
@Serializable
enum class AutoShow {
  Never,
  InVehicle,
  OnFoot,
  ;

  val label: String
    get() = when (this) {
      Never -> "Manual"
      InVehicle -> "In vehicle"
      OnFoot -> "On foot"
    }
}

/**
 * The icons a user can pick for a page. An enum (not an [ImageVector]) so a page's icon survives
 * serialization; [vector] resolves it for rendering.
 */
@Serializable
enum class PageIcon {
  Tractor,
  Home,
  Map,
  Dashboard,
  Grass,
  Checklist,
  Star,
  Grid,
  ;

  val vector: ImageVector
    get() = when (this) {
      Tractor -> Icons.Filled.Agriculture
      Home -> Icons.Filled.Home
      Map -> Icons.Filled.Map
      Dashboard -> Icons.Filled.Dashboard
      Grass -> Icons.Filled.Grass
      Checklist -> Icons.Filled.Checklist
      Star -> Icons.Filled.Star
      Grid -> Icons.Filled.GridView
    }
}
