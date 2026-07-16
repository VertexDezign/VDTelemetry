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
private val CELL_GAP = 8.dp

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
                val center = Offset(originX + drag.x + widthPx / 2f, originY + drag.y + heightPx / 2f)
                val target = hitTest(center)
                drag = Offset.Zero
                onLayoutChange(layout.moveOrSwap(GridPos(cell.col, cell.row), target))
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

        Row(
          Modifier.align(Alignment.BottomEnd).padding(4.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          CtrlButton(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            "narrower",
            onClick = { onLayoutChange(layout.resize(cell, cell.colSpan - 1, cell.rowSpan)) },
          )
          CtrlButton(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            "wider",
            onClick = { onLayoutChange(layout.resize(cell, cell.colSpan + 1, cell.rowSpan)) },
          )
          CtrlButton(
            Icons.Filled.KeyboardArrowUp,
            "shorter",
            onClick = { onLayoutChange(layout.resize(cell, cell.colSpan, cell.rowSpan - 1)) },
          )
          CtrlButton(
            Icons.Filled.KeyboardArrowDown,
            "taller",
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

/** Small round control button used by the edit overlay. */
@Composable
private fun CtrlButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
    modifier
      .size(24.dp)
      .clip(CircleShape)
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, CircleShape)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, description, tint = VdtColors.DarkGray, modifier = Modifier.size(16.dp))
  }
}
