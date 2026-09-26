package net.vertexdezign.vdt.app.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.vertexdezign.vdt.app.panels.EmptyPanel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.app.widgets.LocalWidgetInstance
import net.vertexdezign.vdt.app.widgets.WidgetRegistry

/** Gap between tiles; matches the 8.dp the hand-laid panel grids used. */
internal val CELL_GAP = 8.dp

/** Padding between the tiles and the dashboard body edge. */
internal val GRID_PADDING = 8.dp

/**
 * The [LayoutFrame] for an arrangement drawn [width] × [height] — the same numbers [SplitLayoutView]
 * lays out with, so an edit is checked against exactly what is on screen. Floors come from each
 * tile's widget; an unregistered one has none (loading turns those into empties anyway).
 */
fun layoutFrame(width: Dp, height: Dp): LayoutFrame =
  LayoutFrame(Rect(0f, 0f, width.value, height.value), CELL_GAP.value) { tile ->
    WidgetRegistry.byId(tile.widgetId)?.let { Size(it.minWidth.value, it.minHeight.value) } ?: Size.Zero
  }

/**
 * Renders a split-tree arrangement: [layOut] gives every leaf its rect, and each is placed there with
 * an absolute offset. Not nested `Row`/`Column`s with weights — edit mode needs the same rects for its
 * hit targets and floors, and one pure function giving them to both is what keeps the two agreeing.
 *
 * When [editing] is true each tile gains an overlay (its own gestures masked) with remove and, for a
 * configurable widget, a gear; every [Empty] shows add and close. Changes are reported through
 * [onLayoutChange]; adding and configuring are raised to the caller ([onAddRequest],
 * [onConfigureRequest]) because their dialogs are full-screen modals that have to be hosted above the
 * page. Must be given bounded constraints (place under a `weight`/`fillMaxSize`, not in a scroll).
 */
@Composable
fun SplitLayoutView(
  layout: LayoutNode,
  modifier: Modifier = Modifier,
  editing: Boolean = false,
  onLayoutChange: (LayoutNode) -> Unit = {},
  onAddRequest: (emptyId: String) -> Unit = {},
  onConfigureRequest: (Tile) -> Unit = {},
) {
  BoxWithConstraints(modifier) {
    val bounds = Rect(0f, 0f, maxWidth.value, maxHeight.value)
    for (leaf in layout.layOut(bounds, CELL_GAP.value)) {
      val place =
        Modifier
          .offset(leaf.rect.left.dp, leaf.rect.top.dp)
          .size(leaf.rect.width.dp, leaf.rect.height.dp)
      when (val node = leaf.node) {
        // Keyed by instance, so a tile keeps its state when the tree around it is restructured.
        is Tile ->
          key("tile", node.instanceId) {
            TileView(
              node,
              editing,
              onRemove = { onLayoutChange(layout.removeToEmpty(leaf.path)) },
              onConfigureRequest = onConfigureRequest,
              modifier = place,
            )
          }

        is Empty ->
          if (editing) {
            key("empty", node.id) {
              EmptyView(
                modifier = place,
                onAdd = { onAddRequest(node.id) },
                // The last leaf of a page can't be closed — closeEmpty says so by changing nothing.
                onClose = layout.closeEmpty(leaf.path).takeIf {
                  it != layout
                }?.let { next -> { onLayoutChange(next) } },
              )
            }
          }

        is Split -> Unit
      }
    }
  }
}

@Composable
private fun TileView(
  tile: Tile,
  editing: Boolean,
  onRemove: () -> Unit,
  onConfigureRequest: (Tile) -> Unit,
  modifier: Modifier = Modifier,
) {
  val widget = WidgetRegistry.byId(tile.widgetId)
  val configurable = widget?.configOptions().orEmpty().isNotEmpty()
  BoxWithConstraints(modifier) {
    if (widget == null) {
      EmptyPanel(Modifier.fillMaxSize())
    } else {
      // Which tile this is, for widgets that keep view state of their own — see WidgetSettings.
      CompositionLocalProvider(LocalWidgetInstance provides tile.instanceId) {
        widget.Content(Modifier.fillMaxSize(), tile.config)
      }
    }

    if (editing) {
      // As big as the tile allows: two side by side across the top with 4dp outside and between.
      val ctrlSize = minOf((maxWidth - 12.dp) / 2, maxHeight - 8.dp).coerceIn(CTRL_MIN, CTRL_MAX)
      // Full-tile scrim: signals "editable" and masks the widget's own gestures.
      Box(
        Modifier
          .fillMaxSize()
          .clip(RoundedCornerShape(4.dp))
          .background(VdtColors.Black.copy(alpha = 0.28f))
          .border(1.dp, VdtColors.Green, RoundedCornerShape(4.dp))
          .clickable(interactionSource = null, indication = null) {},
      ) {
        if (configurable) {
          CtrlButton(
            Icons.Filled.Settings,
            "configure widget",
            onClick = { onConfigureRequest(tile) },
            size = ctrlSize,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
          )
        }
        CtrlButton(
          Icons.Filled.Close,
          "remove widget",
          onClick = onRemove,
          size = ctrlSize,
          modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        )
      }
    }
  }
}

/**
 * Space left free, in edit mode: add a widget here, or close it so its neighbours take the room. Faint
 * rather than loud — it is a gap in the page, not a control competing with the tiles around it.
 */
@Composable
private fun EmptyView(onAdd: () -> Unit, onClose: (() -> Unit)?, modifier: Modifier = Modifier) {
  Box(
    modifier
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Surface.copy(alpha = 0.22f))
      .border(1.dp, VdtColors.PanelBorder.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
    contentAlignment = Alignment.Center,
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      CtrlButton(Icons.Filled.Add, "add widget", onClick = onAdd, size = CTRL_MAX)
      if (onClose != null) CtrlButton(Icons.Filled.Close, "close empty space", onClick = onClose, size = CTRL_MAX)
    }
  }
}

/**
 * Small round control button used by the edit overlays. When [enabled] is false it greys out and
 * ignores taps.
 */
@Composable
internal fun CtrlButton(
  icon: ImageVector,
  description: String,
  onClick: () -> Unit,
  size: Dp,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Box(
    modifier
      .size(size)
      .clip(CircleShape)
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, CircleShape)
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      icon,
      description,
      tint = if (enabled) VdtColors.DarkGray else VdtColors.TextDisabled,
      modifier = Modifier.size(size * 0.66f),
    )
  }
}

internal val CTRL_MIN = 24.dp
internal val CTRL_MAX = 36.dp
