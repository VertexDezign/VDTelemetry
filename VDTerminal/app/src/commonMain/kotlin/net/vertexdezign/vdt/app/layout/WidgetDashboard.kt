package net.vertexdezign.vdt.app.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.ConfirmDialog
import net.vertexdezign.vdt.app.pages.AutoShow
import net.vertexdezign.vdt.app.pages.Page
import net.vertexdezign.vdt.app.pages.PageIcon
import net.vertexdezign.vdt.app.pages.PageStore
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.app.widgets.WidgetPicker
import net.vertexdezign.vdt.app.widgets.availableWidgets

/**
 * The body of a [Page]: its [GridLayout] rendered as a [WidgetGrid], with every edit written straight
 * back through [PageStore] (which persists). [editing] is the shell-wide edit toggle from the header;
 * when on, the page's edit toolbar sits above the grid and the grid shows its editing affordances.
 * The status footer is rendered by the shell, not here.
 */
@Composable
fun ColumnScope.WidgetDashboard(page: Page, editing: Boolean, modifier: Modifier = Modifier) {
  val store = LocalVdtStore.current
  val pageStore = store.pages
  var addAt by
    remember(page.id, editing, page.layout.columns, page.layout.rows) { mutableStateOf<GridPos?>(null) }
  // A resize that would drop widgets is held here until the user confirms; delete is guarded too.
  var pendingResize by remember(page.id) { mutableStateOf<GridLayout?>(null) }
  var confirmDelete by remember(page.id) { mutableStateOf(false) }

  fun apply(next: GridLayout) = pageStore.update(page.copy(layout = next))

  // Shrinking is destructive (see GridLayout.withGridSize): apply straight away when nothing is lost,
  // otherwise stage the new layout and let the user confirm the drop.
  fun requestGridSize(columns: Int, rows: Int) {
    val next = page.layout.withGridSize(columns, rows)
    if (next.cells.size < page.layout.cells.size) pendingResize = next else apply(next)
  }

  if (editing) {
    PageEditToolbar(
      page,
      pageStore,
      onResizeGrid = ::requestGridSize,
      onDeleteRequest = { confirmDelete = true },
    )
  }

  Box(modifier.fillMaxWidth().weight(1f)) {
    WidgetGrid(
      page.layout,
      Modifier.fillMaxSize().padding(8.dp),
      editing = editing,
      onLayoutChange = ::apply,
      onAddRequest = { addAt = it },
    )

    val pending = addAt?.takeIf { editing && it in page.layout.freePositions() }
    if (pending != null) {
      val placed = page.layout.cells.map { it.widgetId }.toSet()
      WidgetPicker(
        available = availableWidgets().filter { it.id !in placed },
        onPick = {
          apply(page.layout.addWidget(it, pending.col, pending.row))
          addAt = null
        },
        onDismiss = { addAt = null },
      )
    }

    pendingResize?.let { next ->
      val dropped = page.layout.cells.size - next.cells.size
      ConfirmDialog(
        title = "SHRINK GRID?",
        message = "$dropped widget${if (dropped == 1) "" else "s"} won't fit the smaller grid and will be removed.",
        confirmLabel = "SHRINK",
        onConfirm = {
          apply(next)
          pendingResize = null
        },
        onDismiss = { pendingResize = null },
      )
    }

    if (confirmDelete) {
      ConfirmDialog(
        title = "DELETE PAGE?",
        message = "“${page.title}” and its layout will be permanently removed.",
        confirmLabel = "DELETE",
        onConfirm = {
          confirmDelete = false
          pageStore.remove(page.id)
        },
        onDismiss = { confirmDelete = false },
      )
    }
  }
}

/**
 * Page-level editing: rename, icon, when it auto-shows, grid size, and delete. Grid resizes go through
 * [onResizeGrid] and delete through [onDeleteRequest] so the parent can guard the destructive ones;
 * the non-destructive edits write straight back through [store].
 */
@Composable
private fun PageEditToolbar(
  page: Page,
  store: PageStore,
  onResizeGrid: (columns: Int, rows: Int) -> Unit,
  onDeleteRequest: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(VdtColors.Green.copy(alpha = 0.12f))
      .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    BasicTextField(
      value = page.title,
      onValueChange = { store.update(page.copy(title = it)) },
      singleLine = true,
      textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VdtColors.TextDark),
      modifier =
      Modifier
        .width(120.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(VdtColors.White)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(4.dp))
        .padding(horizontal = 6.dp, vertical = 4.dp),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
      for (option in PageIcon.entries) {
        Icon(
          option.vector,
          "icon ${option.name}",
          tint = if (page.icon == option) VdtColors.Green else VdtColors.DarkGray,
          modifier = Modifier.size(18.dp).clickableNoRipple { store.update(page.copy(icon = option)) },
        )
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      Label("SHOW")
      for (mode in AutoShow.entries) {
        Chip(mode.label, selected = page.autoShow == mode) { store.update(page.copy(autoShow = mode)) }
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      Label("GRID")
      Stepper(page.layout.columns) { onResizeGrid(page.layout.columns + it, page.layout.rows) }
      Text("×", fontSize = 11.sp, color = VdtColors.DarkGray)
      Stepper(page.layout.rows) { onResizeGrid(page.layout.columns, page.layout.rows + it) }
    }

    Spacer(Modifier.weight(1f))

    Icon(
      Icons.Filled.Delete,
      "delete page",
      tint = VdtColors.Red,
      modifier = Modifier.size(20.dp).clickableNoRipple(onDeleteRequest),
    )
  }
}

@Composable
private fun Label(text: String) {
  Text(text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VdtColors.Gray)
}

/** `− n +` control; the callback gets the delta. */
@Composable
private fun Stepper(value: Int, onStep: (Int) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    StepButton("−") { onStep(-1) }
    Text("$value", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VdtColors.TextDark)
    StepButton("+") { onStep(+1) }
  }
}

@Composable
private fun StepButton(glyph: String, onClick: () -> Unit) {
  Box(
    Modifier
      .size(16.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(VdtColors.White)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(3.dp))
      .clickableNoRipple(onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(glyph, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
  }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
  Text(
    text.uppercase(),
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    color = if (selected) VdtColors.White else VdtColors.DarkGray,
    modifier =
    Modifier
      .clip(RoundedCornerShape(3.dp))
      .background(if (selected) VdtColors.Green else VdtColors.White)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(3.dp))
      .clickableNoRipple(onClick)
      .padding(horizontal = 6.dp, vertical = 3.dp),
  )
}

/** Click target without the material ripple; keeps these dense controls keyboard/AX-activatable. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
  this.clickable(interactionSource = null, indication = null, onClick = onClick)
