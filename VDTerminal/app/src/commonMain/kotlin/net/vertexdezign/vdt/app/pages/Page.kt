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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.vertexdezign.vdt.app.layout.GridAspect
import net.vertexdezign.vdt.app.layout.GridLayout

/**
 * A **Page** — a screen the *user* composes from widgets, like an Android home screen. Unlike a
 * [net.vertexdezign.vdt.app.apps.VdtApp] (a code-defined feature with its own screen), pages are
 * user data: created, renamed, rearranged and deleted at runtime and persisted by [PageStore].
 *
 * A page holds **one arrangement per [GridAspect]**, because the two grids are different shapes and
 * an arrangement is only meaningful on the grid it was written for. The shell renders whichever the
 * body's aspect calls for, and edit mode edits that one; the other is left exactly as the user last
 * left it.
 *
 * [portrait] defaults to [landscape] rescaled, which is what lets a page stored before this existed
 * load without a storage-key bump: the field is simply absent from that JSON, so the default fills
 * it, and every page arrives with a usable portrait arrangement it can then diverge from. It is a
 * starting point and nothing more — the seeds override it with arrangements laid out by hand, since
 * a good landscape page is rarely a good portrait one.
 *
 * The two arrangements share instance ids where one was seeded from the other, which is deliberate:
 * a tile is the same tile in both orientations, so its instance-scoped settings (a map's zoom and
 * filters, a rig slot's position) follow it round rather than resetting when the device turns. The
 * consequence is that they diverge only where the user makes them: a tile added or removed in one
 * aspect leaves the other alone.
 */
@Serializable
data class Page(
  val id: String,
  val title: String,
  val icon: PageIcon,
  val autoShow: AutoShow,
  @SerialName("layout") val landscape: GridLayout,
  val portrait: GridLayout = landscape.rescaledTo(GridAspect.Portrait.columns, GridAspect.Portrait.rows),
) {
  fun layoutFor(aspect: GridAspect): GridLayout = when (aspect) {
    GridAspect.Landscape -> landscape
    GridAspect.Portrait -> portrait
  }

  fun withLayout(aspect: GridAspect, layout: GridLayout): Page = when (aspect) {
    GridAspect.Landscape -> copy(landscape = layout)
    GridAspect.Portrait -> copy(portrait = layout)
  }

  /** Both arrangements. For bookkeeping that spans them — see [PageStore]'s instance purge. */
  val layouts: List<GridLayout> get() = listOf(landscape, portrait)
}

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
