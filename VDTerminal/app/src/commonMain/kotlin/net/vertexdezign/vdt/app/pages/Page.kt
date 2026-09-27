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
import net.vertexdezign.vdt.app.layout.GridAspect
import net.vertexdezign.vdt.app.layout.LayoutNode
import net.vertexdezign.vdt.app.layout.flipped

/**
 * A **Page** — a screen the *user* composes from widgets, like an Android home screen. Unlike a
 * [net.vertexdezign.vdt.app.apps.VdtApp] (a code-defined feature with its own screen), pages are
 * user data: created, renamed, rearranged and deleted at runtime and persisted by [PageStore].
 *
 * A page holds **one arrangement per [GridAspect]**, each a split tree ([LayoutNode]). A tree is
 * ratios, so either arrangement would draw at any size — but a good landscape page is rarely a good
 * portrait one, and the shell renders whichever the body's aspect calls for. Edit mode edits that
 * one; the other is left exactly as the user last left it.
 *
 * [portrait] defaults to [landscape] with every axis flipped — bands across become columns down. A
 * starting point and nothing more, for a page stored with only one arrangement; the seeds override
 * it with arrangements laid out by hand.
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
  val landscape: LayoutNode,
  val portrait: LayoutNode = landscape.flipped(),
) {
  fun layoutFor(aspect: GridAspect): LayoutNode = when (aspect) {
    GridAspect.Landscape -> landscape
    GridAspect.Portrait -> portrait
  }

  fun withLayout(aspect: GridAspect, layout: LayoutNode): Page = when (aspect) {
    GridAspect.Landscape -> copy(landscape = layout)
    GridAspect.Portrait -> copy(portrait = layout)
  }

  /** Both arrangements. For bookkeeping that spans them — see [PageStore]'s instance purge. */
  val layouts: List<LayoutNode> get() = listOf(landscape, portrait)
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
