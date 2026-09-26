package net.vertexdezign.vdt.app.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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

/** A tile being dragged: where it was grabbed and how far it has travelled, in dp. */
private data class TileDrag(val path: NodePath, val origin: Offset, val grab: Offset, val moved: Offset) {
  val pointer: Offset get() = origin + grab + moved
}

/** A divider being dragged: where it started, and how far the finger has gone along its axis, in dp. */
private data class DividerDrag(val split: NodePath, val index: Int, val start: Float, val moved: Float)

/**
 * Renders a split-tree arrangement: [layOut] gives every leaf its rect, and each is placed there with
 * an absolute offset. Not nested `Row`/`Column`s with weights — edit mode needs the same rects for its
 * hit targets, snapping and floors, and one pure function giving them to both is what keeps the two
 * agreeing.
 *
 * When [editing] is true, everything happens on the thing the finger is on — no modes, no selection:
 *
 * - a **tile** masks its widget's gestures and can be dragged onto another leaf to swap with it (onto
 *   an empty, that is a move), split right or below, removed (it leaves an empty, so the structure
 *   stays), or configured;
 * - an **empty** can be filled or closed;
 * - a **divider** can be dragged, always snapping (see [snapDivider]), or its split equalized;
 * - each **page edge** adds a full-width or full-height band.
 *
 * Every control is offered only where its edit would do something — the pure operations return their
 * input when refused, which is what greys a button out or hides a target. Changes are reported
 * through [onLayoutChange]; adding and configuring are raised to the caller ([onAddRequest],
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
    // Edits are checked against the page at its real size; in edit mode it is *drawn* inset by a
    // gutter that holds the page-edge buttons, so they never land on a tile's own controls. A tree is
    // ratios, so the page simply draws a little smaller while being edited — but a floor check made
    // against that smaller drawing would refuse splits that are fine on the real page.
    val frame = layoutFrame(maxWidth, maxHeight)
    val drawn = if (editing) frame.bounds.deflate(EDGE_GUTTER.value) else frame.bounds
    // Both drags are local until the finger lifts: PageStore persists on every update, and a divider
    // drag is a change per frame. A new layout from outside ends either drag.
    var tileDrag by remember(layout, editing) { mutableStateOf<TileDrag?>(null) }
    var dividerDrag by remember(layout, editing) { mutableStateOf<DividerDrag?>(null) }
    val preview =
      dividerDrag?.let { drag ->
        val snapped = layout.snapDivider(drag.split, drag.index, drag.start + drag.moved, frame)
        layout.setDivider(drag.split, drag.index, snapped, frame)
      }
    val shown = preview ?: layout
    val leaves = shown.layOut(drawn, frame.gap)

    // The leaf under the finger, if dropping there would be allowed. A refused target simply isn't
    // highlighted, so the tile is visibly not going anywhere before it is let go.
    val swapTarget =
      tileDrag?.let { drag ->
        leaves
          .firstOrNull { it.path != drag.path && it.rect.contains(drag.pointer) }
          ?.takeIf { layout.swap(drag.path, it.path, frame) != layout }
      }

    for (leaf in leaves) {
      val drag = tileDrag?.takeIf { it.path == leaf.path }
      val place =
        Modifier
          .zIndex(if (drag != null) 1f else 0f)
          .offset((leaf.rect.left + (drag?.moved?.x ?: 0f)).dp, (leaf.rect.top + (drag?.moved?.y ?: 0f)).dp)
          .size(leaf.rect.width.dp, leaf.rect.height.dp)
      val isTarget = swapTarget?.path == leaf.path
      when (val node = leaf.node) {
        // Keyed by instance, so a tile keeps its state when the tree around it is restructured.
        is Tile ->
          key("tile", node.instanceId) {
            TileView(
              node,
              editing,
              edits =
              TileEdits(
                remove = { onLayoutChange(layout.removeToEmpty(leaf.path)) },
                configure = { onConfigureRequest(node) },
                splitRight = layout.split(leaf.path, Axis.Row, frame).takeIf {
                  it != layout
                }?.let { { onLayoutChange(it) } },
                splitBelow = layout.split(leaf.path, Axis.Column, frame).takeIf {
                  it != layout
                }?.let { { onLayoutChange(it) } },
                dragStart = { grab ->
                  tileDrag =
                    TileDrag(leaf.path, Offset(leaf.rect.left, leaf.rect.top), grab, Offset.Zero)
                },
                drag = { delta -> tileDrag = tileDrag?.let { it.copy(moved = it.moved + delta) } },
                dragEnd = {
                  val target = swapTarget
                  val from = tileDrag?.path
                  tileDrag = null
                  if (target != null && from != null) onLayoutChange(layout.swap(from, target.path, frame))
                },
                dragCancel = { tileDrag = null },
              ),
              dropTarget = isTarget,
              modifier = place,
            )
          }

        is Empty ->
          if (editing) {
            key("empty", node.id) {
              EmptyView(
                modifier = place,
                dropTarget = isTarget,
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

    if (editing) {
      for (divider in shown.dividers(drawn, frame.gap)) {
        key("divider", divider.split, divider.index) {
          // One equalize per split, on its first divider: a three-way split has two dividers and
          // one thing to equalize.
          val equalized = if (divider.index ==
            0
          ) {
            layout.equalize(divider.split, frame).takeIf { it != layout }
          } else {
            null
          }
          val active = dividerDrag?.let { it.split == divider.split && it.index == divider.index } == true
          val dragging =
            rememberDividerDrag(
              divider,
              onStart = {
                // Measured on the committed layout, so the drag is relative to where the line really is.
                val start = layout.dividers(frame.bounds, frame.gap).first {
                  it.split == divider.split &&
                    it.index == divider.index
                }
                dividerDrag = DividerDrag(divider.split, divider.index, start.position, 0f)
              },
              // The finger moves over the drawn page; the divider is set on the real one.
              onDrag = { delta ->
                val scale = layout.contentScale(divider.split, frame, drawn)
                dividerDrag = dividerDrag?.let { it.copy(moved = it.moved + delta * scale) }
              },
              onEnd = {
                dividerDrag = null
                if (preview != null && preview != layout) onLayoutChange(preview)
              },
              onCancel = { dividerDrag = null },
            )
          DividerStrip(divider, active, modifier = dragging)
          DividerHandle(divider, active, modifier = dragging)
          if (equalized != null && !active) EqualizeButton(divider, onClick = { onLayoutChange(equalized) })
        }
      }

      for (edge in PageEdge.entries) {
        val banded = layout.addAtEdge(edge, frame)
        if (banded != layout) EdgeButton(edge, frame.bounds, onClick = { onLayoutChange(banded) })
      }
    }
  }
}

/**
 * What a tile's edit overlay can do. A null split is one that would break a floor, and its button is
 * greyed out.
 */
private class TileEdits(
  val remove: () -> Unit,
  val configure: () -> Unit,
  val splitRight: (() -> Unit)?,
  val splitBelow: (() -> Unit)?,
  val dragStart: (grab: Offset) -> Unit,
  val drag: (delta: Offset) -> Unit,
  val dragEnd: () -> Unit,
  val dragCancel: () -> Unit,
)

@Composable
private fun TileView(
  tile: Tile,
  editing: Boolean,
  edits: TileEdits,
  dropTarget: Boolean,
  modifier: Modifier = Modifier,
) {
  val widget = WidgetRegistry.byId(tile.widgetId)
  val configurable = widget?.configOptions().orEmpty().isNotEmpty()
  // The gesture below only restarts when the tile does, so it reads the current handlers through
  // this — otherwise a drag spanning another edit would report into a stale layout.
  val currentEdits by rememberUpdatedState(edits)
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
      // As big as the tile allows: two across and two down, with 4dp outside and between.
      val roomForHint = maxWidth >= 120.dp && maxHeight >= 120.dp
      val ctrlSize = minOf((maxWidth - 12.dp) / 2, (maxHeight - 12.dp) / 2).coerceIn(CTRL_MIN, CTRL_MAX)
      // Full-tile scrim: signals "editable", masks the widget's own gestures, and hosts the drag.
      // Deeper control buttons win taps; a drag anywhere else moves the tile.
      Box(
        Modifier
          .fillMaxSize()
          .clip(RoundedCornerShape(4.dp))
          .background(VdtColors.Black.copy(alpha = if (dropTarget) 0.1f else 0.28f))
          .border(if (dropTarget) 4.dp else 1.dp, VdtColors.Green, RoundedCornerShape(4.dp))
          .pointerInput(tile.instanceId) {
            detectDragGestures(
              onDragStart = { at -> currentEdits.dragStart(Offset(at.x.toDp().value, at.y.toDp().value)) },
              onDrag = { change, delta ->
                change.consume()
                currentEdits.drag(Offset(delta.x.toDp().value, delta.y.toDp().value))
              },
              onDragEnd = { currentEdits.dragEnd() },
              onDragCancel = { currentEdits.dragCancel() },
            )
          },
      ) {
        // Decoration, so it only appears once there is room for it clear of the corner buttons.
        if (roomForHint) {
          Icon(
            Icons.Filled.OpenWith,
            contentDescription = "drag to swap",
            tint = VdtColors.White,
            modifier = Modifier.align(Alignment.Center).size(28.dp),
          )
        }
        if (configurable) {
          CtrlButton(
            Icons.Filled.Settings,
            "configure widget",
            onClick = edits.configure,
            size = ctrlSize,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
          )
        }
        CtrlButton(
          Icons.Filled.Close,
          "remove widget",
          onClick = edits.remove,
          size = ctrlSize,
          modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        )
        Row(
          Modifier.align(Alignment.BottomEnd).padding(4.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          CtrlButton(
            Icons.Filled.VerticalSplit,
            "split right",
            onClick = { edits.splitRight?.invoke() },
            enabled = edits.splitRight != null,
            size = ctrlSize,
          )
          CtrlButton(
            Icons.Filled.HorizontalSplit,
            "split below",
            onClick = { edits.splitBelow?.invoke() },
            enabled = edits.splitBelow != null,
            size = ctrlSize,
          )
        }
      }
    }
  }
}

/**
 * Space left free, in edit mode: add a widget here, or close it so its neighbours take the room. Faint
 * rather than loud — it is a gap in the page, not a control competing with the tiles around it — until
 * a dragged tile is over it, when it takes the same heavy outline a tile does.
 */
@Composable
private fun EmptyView(onAdd: () -> Unit, onClose: (() -> Unit)?, dropTarget: Boolean, modifier: Modifier = Modifier) {
  Box(
    modifier
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.Surface.copy(alpha = 0.22f))
      .border(
        if (dropTarget) 4.dp else 1.dp,
        if (dropTarget) VdtColors.Green else VdtColors.PanelBorder.copy(alpha = 0.5f),
        RoundedCornerShape(4.dp),
      ),
    contentAlignment = Alignment.Center,
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      CtrlButton(Icons.Filled.Add, "add widget", onClick = onAdd, size = CTRL_MAX)
      if (onClose != null) CtrlButton(Icons.Filled.Close, "close empty space", onClick = onClose, size = CTRL_MAX)
    }
  }
}

/**
 * The drag shared by a divider's strip and its handle, reporting the finger's travel along the
 * divider's axis in dp. The strip and the handle are separate elements rather than one box because the
 * handle is wider than the strip, and a child outside its parent's bounds can't be touched.
 */
@Composable
private fun rememberDividerDrag(
  divider: Divider,
  onStart: () -> Unit,
  onDrag: (Float) -> Unit,
  onEnd: () -> Unit,
  onCancel: () -> Unit,
): Modifier {
  val across = divider.axis == Axis.Row
  val currentOnStart by rememberUpdatedState(onStart)
  val currentOnDrag by rememberUpdatedState(onDrag)
  val currentOnEnd by rememberUpdatedState(onEnd)
  val currentOnCancel by rememberUpdatedState(onCancel)
  return Modifier.pointerInput(divider.split, divider.index) {
    detectDragGestures(
      onDragStart = { currentOnStart() },
      onDrag = { change, delta ->
        change.consume()
        currentOnDrag((if (across) delta.x else delta.y).toDp().value)
      },
      onDragEnd = { currentOnEnd() },
      onDragCancel = { currentOnCancel() },
    )
  }
}

/**
 * The divider's line and grab strip, reaching [GRIP_REACH] into each neighbour — short of the corner
 * buttons' 4dp inset. Faint at rest; solid and thicker while [active], drawn where the divider will
 * land: it snaps, so the line jumps between the positions it can take rather than following the finger.
 */
@Composable
private fun DividerStrip(divider: Divider, active: Boolean, modifier: Modifier = Modifier) {
  val across = divider.axis == Axis.Row
  val strip = if (across) divider.rect.inflateX(GRIP_REACH.value) else divider.rect.inflateY(GRIP_REACH.value)
  Box(
    Modifier
      .zIndex(2f)
      .offset(strip.left.dp, strip.top.dp)
      .size(strip.width.dp, strip.height.dp)
      .then(modifier),
    contentAlignment = Alignment.Center,
  ) {
    val thickness = if (active) 4.dp else 2.dp
    Box(
      Modifier
        .then(if (across) Modifier.size(thickness, strip.height.dp) else Modifier.size(strip.width.dp, thickness))
        .background(if (active) VdtColors.Green else VdtColors.Green.copy(alpha = 0.5f)),
    )
  }
}

/** A pill at the divider's middle — something to aim for that a thumb can actually hit. */
@Composable
private fun DividerHandle(divider: Divider, active: Boolean, modifier: Modifier = Modifier) {
  val across = divider.axis == Axis.Row
  val (w, h) = handleSize(divider)
  val center = divider.rect.center
  Box(
    Modifier
      .zIndex(2f)
      .offset((center.x - w / 2).dp, (center.y - h / 2).dp)
      .size(w.dp, h.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(VdtColors.Panel)
      .border(
        if (active) 3.dp else 1.dp,
        if (active) VdtColors.Green else VdtColors.PanelBorder,
        RoundedCornerShape(12.dp),
      )
      .then(modifier),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.Filled.DragHandle,
      "drag divider",
      tint = VdtColors.DarkGray,
      modifier = Modifier.size(20.dp).then(if (across) Modifier.rotate(90f) else Modifier),
    )
  }
}

/**
 * Evens out every child of the divider's split, beside its handle on the line. A Material icon rather
 * than an `=` glyph (the wasm build has no font fallback), and one that says which way it evens out.
 */
@Composable
private fun EqualizeButton(divider: Divider, onClick: () -> Unit) {
  val across = divider.axis == Axis.Row
  val (w, h) = handleSize(divider)
  val center = divider.rect.center
  val size = 28f
  val gap = 6f
  val x = if (across) center.x - size / 2 else center.x + w / 2 + gap
  val y = if (across) center.y + h / 2 + gap else center.y - size / 2
  CtrlButton(
    if (across) Icons.Filled.ViewColumn else Icons.Filled.TableRows,
    "make equal",
    onClick = onClick,
    size = size.dp,
    modifier = Modifier.zIndex(2f).offset(x.dp, y.dp),
  )
}

/** The handle lies along its divider: tall on a vertical line, wide on a horizontal one. */
private fun handleSize(divider: Divider): Pair<Float, Float> = if (divider.axis == Axis.Row) 24f to 48f else 48f to 24f

/**
 * Adds a band along one side of the page: a pill at the middle of that edge, lying along it in the
 * edit-mode gutter — outside the drawn page, so it can't cover a tile's controls, but inside the view,
 * since a control outside its parent's bounds can't be touched.
 */
@Composable
private fun EdgeButton(edge: PageEdge, bounds: Rect, onClick: () -> Unit) {
  val along = edge.axis == Axis.Column // a top/bottom band: the pill lies horizontally
  val w = if (along) 56f else 24f
  val h = if (along) 24f else 56f
  val inset = (EDGE_GUTTER.value - 24f) / 2
  val x =
    when (edge) {
      PageEdge.Left -> bounds.left + inset
      PageEdge.Right -> bounds.right - inset - w
      else -> bounds.center.x - w / 2
    }
  val y =
    when (edge) {
      PageEdge.Top -> bounds.top + inset
      PageEdge.Bottom -> bounds.bottom - inset - h
      else -> bounds.center.y - h / 2
    }
  Box(
    Modifier
      .zIndex(3f)
      .offset(x.dp, y.dp)
      .size(w.dp, h.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.Green, RoundedCornerShape(12.dp))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.Filled.Add,
      "add ${edge.name.lowercase()} band",
      tint = VdtColors.DarkGray,
      modifier = Modifier.size(18.dp),
    )
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

private fun Rect.inflateX(by: Float) = Rect(left - by, top, right + by, bottom)

private fun Rect.inflateY(by: Float) = Rect(left, top - by, right, bottom + by)

internal val CTRL_MIN = 24.dp
internal val CTRL_MAX = 36.dp

/** The margin the page is drawn inset by in edit mode, holding the page-edge buttons. */
private val EDGE_GUTTER = 28.dp

/**
 * How far the real page's content moves per dp of the drawn one, along the split at [split]: the
 * ratio of their extents once the fixed gaps are taken out. Exact for a divider of that split, since
 * the gaps are the only part of either that doesn't scale.
 */
private fun LayoutNode.contentScale(split: NodePath, frame: LayoutFrame, drawn: Rect): Float {
  val node = nodeAt(split) as? Split ?: return 1f
  val gaps = frame.gap * node.children.lastIndex
  val real = rectOf(split, frame.bounds, frame.gap)?.extentOn(node.axis) ?: return 1f
  val shown = rectOf(split, drawn, frame.gap)?.extentOn(node.axis) ?: return 1f
  return if (shown - gaps > 0f) (real - gaps) / (shown - gaps) else 1f
}

/** How far a divider's grab strip reaches into each neighbour — short of the 4dp-inset corner buttons. */
private val GRIP_REACH = 4.dp
