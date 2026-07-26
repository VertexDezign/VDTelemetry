package net.vertexdezign.vdt.app.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.vertexdezign.vdt.app.panels.EmptyPanel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.app.widgets.WidgetRegistry
import kotlin.math.roundToInt

/** Gap between grid cells; matches the 8.dp the hand-laid panel grids used. */
internal val CELL_GAP = 8.dp

/** Padding between the grid and the dashboard body edge; also subtracted when sizing cells. */
internal val GRID_PADDING = 8.dp

/**
 * Renders a [GridLayout]: a `columns × rows` grid of equal cells with [CELL_GAP] gaps, each widget
 * placed at its `col/row` origin spanning `colSpan/rowSpan`. Absolute offsets over a
 * [BoxWithConstraints] do the placement (Compose has no CSS-grid), which also gives the cell geometry
 * the drag hit-test needs.
 *
 * When [editing] is true each tile gains a move/resize/remove overlay (its own widget gestures are
 * masked), and every free position shows an "add" slot ([onAddRequest]). Layout mutations are
 * reported through [onLayoutChange]. Must be given bounded constraints (place under a `weight`/
 * `fillMaxSize`, not in a scroll).
 */
@Composable
fun WidgetGrid(
  layout: GridLayout,
  modifier: Modifier = Modifier,
  editing: Boolean = false,
  onLayoutChange: (GridLayout) -> Unit = {},
  onAddRequest: (GridPos) -> Unit = {},
) {
  val cols = layout.columns.coerceAtLeast(1)
  val rows = layout.rows.coerceAtLeast(1)
  BoxWithConstraints(modifier) {
    val density = LocalDensity.current
    val gapPx = with(density) { CELL_GAP.toPx() }
    val totalW = with(density) { maxWidth.toPx() }
    val totalH = with(density) { maxHeight.toPx() }
    val cellW = (totalW - gapPx * (cols - 1)) / cols
    val cellH = (totalH - gapPx * (rows - 1)) / rows
    val strideX = cellW + gapPx
    val strideY = cellH + gapPx

    fun spanWidth(cs: Int) = cellW * cs + gapPx * (cs - 1)
    fun spanHeight(rs: Int) = cellH * rs + gapPx * (rs - 1)

    // Snap a dragged tile's top-left corner to the nearest grid origin. It has to be the corner, not
    // the tile's centre: a widget is placed by its origin, so on this grid a centre-based target
    // would drop every multi-cell tile half its own size down and to the right of where it was let
    // go. Rounding (not truncating) means the nearest line wins, so a small nudge doesn't move it.
    fun hitTest(topLeft: Offset): GridPos = GridPos(
      (topLeft.x / strideX).roundToInt().coerceIn(0, cols - 1),
      (topLeft.y / strideY).roundToInt().coerceIn(0, rows - 1),
    )

    if (editing) {
      for (pos in layout.freePositions()) {
        AddSlot(
          onClick = { onAddRequest(pos) },
          modifier =
          Modifier
            .offset { IntOffset((pos.col * strideX).roundToInt(), (pos.row * strideY).roundToInt()) }
            .size(with(density) { cellW.toDp() }, with(density) { cellH.toDp() }),
        )
      }
    }

    for (cell in layout.cells) {
      key(cell.widgetId) {
        WidgetCell(
          cell = cell,
          layout = layout,
          editing = editing,
          originX = cell.col * strideX,
          originY = cell.row * strideY,
          widthPx = spanWidth(cell.colSpan),
          heightPx = spanHeight(cell.rowSpan),
          onLayoutChange = onLayoutChange,
          hitTest = ::hitTest,
        )
      }
    }
  }
}

@Composable
private fun WidgetCell(
  cell: LayoutCell,
  layout: GridLayout,
  editing: Boolean,
  originX: Float,
  originY: Float,
  widthPx: Float,
  heightPx: Float,
  onLayoutChange: (GridLayout) -> Unit,
  hitTest: (Offset) -> GridPos,
) {
  val density = LocalDensity.current
  var drag by remember { mutableStateOf(Offset.Zero) }
  val dragging = drag != Offset.Zero

  // The pointerInput below only restarts when this widget's own cell changes, so it must read
  // these through rememberUpdatedState rather than close over them directly — otherwise a drag
  // spanning another widget's edit (which changes `layout`/`onLayoutChange` upstream without
  // touching this cell) would submit the stale layout on drop.
  val currentLayout by rememberUpdatedState(layout)
  val currentOnLayoutChange by rememberUpdatedState(onLayoutChange)
  val currentOriginX by rememberUpdatedState(originX)
  val currentOriginY by rememberUpdatedState(originY)
  val currentHitTest by rememberUpdatedState(hitTest)

  // An unregistered id has no size contract to honour, so it falls back to the 1×1 floor — it renders
  // as an empty tile anyway and the user's next move is to remove it.
  val widget = WidgetRegistry.byId(cell.widgetId)
  val minColSpan = widget?.minColSpan ?: 1
  val minRowSpan = widget?.minRowSpan ?: 1

  Box(
    Modifier
      .zIndex(if (dragging) 1f else 0f)
      .offset { IntOffset((originX + drag.x).roundToInt(), (originY + drag.y).roundToInt()) }
      .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() }),
  ) {
    if (widget != null) widget.Content(Modifier.fillMaxSize()) else EmptyPanel(Modifier.fillMaxSize())

    if (editing) {
      // Full-tile overlay: a scrim that both signals "editable" and masks the widget's own gestures,
      // and hosts the drag. Deeper control buttons win taps in the main pass; drags elsewhere move it.
      Box(
        Modifier
          .fillMaxSize()
          .clip(RoundedCornerShape(4.dp))
          .background(VdtColors.Black.copy(alpha = 0.28f))
          .border(1.dp, VdtColors.Green, RoundedCornerShape(4.dp))
          .pointerInput(cell) {
            detectDragGestures(
              onDrag = { change, delta ->
                change.consume()
                drag += delta
              },
              onDragEnd = {
                val target = currentHitTest(Offset(currentOriginX + drag.x, currentOriginY + drag.y))
                drag = Offset.Zero
                currentOnLayoutChange(currentLayout.moveOrSwap(GridPos(cell.col, cell.row), target))
              },
              onDragCancel = { drag = Offset.Zero },
            )
          },
      ) {
        Icon(
          Icons.Filled.OpenWith,
          contentDescription = "drag to move",
          tint = VdtColors.White,
          modifier = Modifier.align(Alignment.Center).size(28.dp),
        )
        CtrlButton(
          Icons.Filled.Close,
          "remove widget",
          onClick = { onLayoutChange(layout.removeAt(cell.col, cell.row)) },
          modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        )

        // A resize direction is offered only when it would actually change the layout: shrinking is
        // blocked at the widget's own span floor, growing at the grid edge or against a neighbour.
        // resize() is a no-op (returns an equal layout) in those cases, so that's the signal.
        fun resizedTo(colSpan: Int, rowSpan: Int) = layout.resize(cell, colSpan, rowSpan, minColSpan, minRowSpan)
        fun canResizeTo(colSpan: Int, rowSpan: Int) = resizedTo(colSpan, rowSpan) != layout

        // Two rows of two rather than one row of four: on the 12×7 grid a minimum-size tile is only
        // about a cell wide, and four buttons in a line would run off it.
        Column(
          Modifier.align(Alignment.BottomEnd).padding(4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CtrlButton(
              Icons.AutoMirrored.Filled.KeyboardArrowLeft,
              "narrower",
              enabled = canResizeTo(cell.colSpan - 1, cell.rowSpan),
              onClick = { onLayoutChange(resizedTo(cell.colSpan - 1, cell.rowSpan)) },
            )
            CtrlButton(
              Icons.AutoMirrored.Filled.KeyboardArrowRight,
              "wider",
              enabled = canResizeTo(cell.colSpan + 1, cell.rowSpan),
              onClick = { onLayoutChange(resizedTo(cell.colSpan + 1, cell.rowSpan)) },
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CtrlButton(
              Icons.Filled.KeyboardArrowUp,
              "shorter",
              enabled = canResizeTo(cell.colSpan, cell.rowSpan - 1),
              onClick = { onLayoutChange(resizedTo(cell.colSpan, cell.rowSpan - 1)) },
            )
            CtrlButton(
              Icons.Filled.KeyboardArrowDown,
              "taller",
              enabled = canResizeTo(cell.colSpan, cell.rowSpan + 1),
              onClick = { onLayoutChange(resizedTo(cell.colSpan, cell.rowSpan + 1)) },
            )
          }
        }
      }
    }
  }
}

/**
 * An empty grid slot in edit mode: tap to open the widget picker for this position. On the 12×7 grid
 * an empty page shows 84 of these at once, so it reads as a faint backdrop rather than 84 buttons
 * competing with the tiles that are actually placed.
 */
@Composable
private fun AddSlot(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.22f))
      .border(1.dp, VdtColors.PanelBorder.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      Icons.Filled.Add,
      "add widget",
      tint = VdtColors.DarkGray.copy(alpha = 0.6f),
      modifier = Modifier.size(18.dp),
    )
  }
}

/**
 * Small round control button used by the edit overlay. When [enabled] is false it greys out and
 * ignores taps — used to show a resize direction has hit its limit.
 */
@Composable
private fun CtrlButton(
  icon: ImageVector,
  description: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Box(
    modifier
      .size(24.dp)
      .clip(CircleShape)
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, CircleShape)
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, description, tint = if (enabled) VdtColors.DarkGray else VdtColors.Gray, modifier = Modifier.size(16.dp))
  }
}
