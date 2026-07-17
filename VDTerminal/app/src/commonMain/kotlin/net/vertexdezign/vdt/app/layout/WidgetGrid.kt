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
 * Smallest a grid cell may get before its widget stops being readable. The page's column/row count is
 * capped so a cell never shrinks below this in the current viewport — the readability floor is on cell
 * *size*, not on any per-widget span (the grid is freely resizable, so a span floor wouldn't bound the
 * pixels). Tuned by eye; adjust here if tiles feel too dense or too sparse.
 */
internal val MIN_CELL_WIDTH = 200.dp
internal val MIN_CELL_HEIGHT = 140.dp

/**
 * The most cells that fit along one axis while keeping each at least [minCellPx] (accounting for the
 * [gapPx] between them), clamped to [GridLayout.MIN_SIDE]..[GridLayout.MAX_SIDE]. Returns the hard
 * ceiling when [availablePx] isn't known yet (not measured), so the control stays usable on first frame.
 *
 * Derivation: `availablePx >= n*minCellPx + (n-1)*gapPx` ⇒ `n <= (availablePx + gapPx)/(minCellPx + gapPx)`.
 */
internal fun maxGridSide(availablePx: Float, minCellPx: Float, gapPx: Float): Int {
  if (availablePx <= 0f) return GridLayout.MAX_SIDE
  val fit = ((availablePx + gapPx) / (minCellPx + gapPx)).toInt()
  return fit.coerceIn(GridLayout.MIN_SIDE, GridLayout.MAX_SIDE)
}

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
    fun hitTest(center: Offset): GridPos = GridPos(
      (center.x / strideX).toInt().coerceIn(0, cols - 1),
      (center.y / strideY).toInt().coerceIn(0, rows - 1),
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
  val currentWidthPx by rememberUpdatedState(widthPx)
  val currentHeightPx by rememberUpdatedState(heightPx)
  val currentHitTest by rememberUpdatedState(hitTest)

  Box(
    Modifier
      .zIndex(if (dragging) 1f else 0f)
      .offset { IntOffset((originX + drag.x).roundToInt(), (originY + drag.y).roundToInt()) }
      .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() }),
  ) {
    val widget = WidgetRegistry.byId(cell.widgetId)
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
                val center =
                  Offset(
                    currentOriginX + drag.x + currentWidthPx / 2f,
                    currentOriginY + drag.y + currentHeightPx / 2f,
                  )
                val target = currentHitTest(center)
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
        // blocked at a 1-cell span, growing at the grid edge or against a neighbour. resize() is a
        // no-op (returns the same layout) in those cases, so that's the signal.
        fun canResizeTo(colSpan: Int, rowSpan: Int) = layout.resize(cell, colSpan, rowSpan) != layout

        Row(
          Modifier.align(Alignment.BottomEnd).padding(4.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          CtrlButton(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            "narrower",
            enabled = canResizeTo(cell.colSpan - 1, cell.rowSpan),
            onClick = { onLayoutChange(layout.resize(cell, cell.colSpan - 1, cell.rowSpan)) },
          )
          CtrlButton(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            "wider",
            enabled = canResizeTo(cell.colSpan + 1, cell.rowSpan),
            onClick = { onLayoutChange(layout.resize(cell, cell.colSpan + 1, cell.rowSpan)) },
          )
          CtrlButton(
            Icons.Filled.KeyboardArrowUp,
            "shorter",
            enabled = canResizeTo(cell.colSpan, cell.rowSpan - 1),
            onClick = { onLayoutChange(layout.resize(cell, cell.colSpan, cell.rowSpan - 1)) },
          )
          CtrlButton(
            Icons.Filled.KeyboardArrowDown,
            "taller",
            enabled = canResizeTo(cell.colSpan, cell.rowSpan + 1),
            onClick = { onLayoutChange(layout.resize(cell, cell.colSpan, cell.rowSpan + 1)) },
          )
        }
      }
    }
  }
}

/** An empty grid slot in edit mode: tap to open the widget picker for this position. */
@Composable
private fun AddSlot(onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.3f))
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(Icons.Filled.Add, "add widget", tint = VdtColors.DarkGray, modifier = Modifier.size(28.dp))
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
