package net.vertexdezign.vdt.app.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import net.vertexdezign.vdt.app.widgets.Widget
import net.vertexdezign.vdt.app.widgets.WidgetConfig
import net.vertexdezign.vdt.app.widgets.WidgetConfigDialog
import net.vertexdezign.vdt.app.widgets.WidgetPicker
import net.vertexdezign.vdt.app.widgets.WidgetRegistry
import net.vertexdezign.vdt.app.widgets.availableWidgets

/**
 * What the config dialog is currently open for: a widget being placed, or a tile already on the page.
 * One dialog serves both, so the difference is only what SAVE does — add a cell, or update one.
 */
private sealed interface ConfigTarget {
  data class New(val widget: Widget, val at: GridPos) : ConfigTarget

  data class Placed(val cell: LayoutCell) : ConfigTarget
}

/**
 * The body of a [Page]: the [GridLayout] for the body's own [GridAspect], rendered as a [WidgetGrid],
 * with every edit written straight back through [PageStore] (which persists). [editing] is the
 * shell-wide edit toggle from the header; when on, the page's edit toolbar sits above the grid and the
 * grid shows its editing affordances. The status footer is rendered by the shell, not here.
 *
 * The aspect is measured here, from the box the grid is actually given, rather than passed down from
 * the shell — see [GridAspect.of]. Edit mode therefore edits whichever arrangement is on screen:
 * rotate the device and you are editing the other one, and the first is left as you had it.
 */
@Composable
fun ColumnScope.WidgetDashboard(page: Page, editing: Boolean, modifier: Modifier = Modifier) {
  val store = LocalVdtStore.current
  val pageStore = store.pages
  var addAt by remember(page.id, editing) { mutableStateOf<GridPos?>(null) }
  var configuring by remember(page.id, editing) { mutableStateOf<ConfigTarget?>(null) }
  // Deleting a page is destructive, so it's held here until the user confirms.
  var confirmDelete by remember(page.id) { mutableStateOf(false) }

  // Hide the toolbar while the confirmation is pending: its dialog only scrims the grid area below, so
  // an exposed toolbar would let a second destructive request stack up behind the modal.
  if (editing && !confirmDelete) {
    PageEditToolbar(page, pageStore, onDeleteRequest = { confirmDelete = true })
  }

  BoxWithConstraints(modifier.fillMaxWidth().weight(1f)) {
    val aspect = GridAspect.of(maxWidth, maxHeight)
    val layout = page.layoutFor(aspect)

    fun apply(next: GridLayout) = pageStore.update(page.withLayout(aspect, next))

    // The widget, not just its id: placement needs the size it asks for and the floor it may be
    // squeezed to, which is exactly what stops a tile landing as an unreadable single cell. Every
    // placement goes through here — the picker's fit probe, a direct add, and the add-after-configuring
    // path — so all three agree on what "placing this widget here" means.
    fun place(widget: Widget, at: GridPos, config: WidgetConfig = emptyMap()) = layout.addWidget(
      newInstanceId(),
      widget.id,
      at.col,
      at.row,
      colSpan = widget.defaultColSpan,
      rowSpan = widget.defaultRowSpan,
      minColSpan = widget.minColSpan,
      minRowSpan = widget.minRowSpan,
      config = config,
    )

    WidgetGrid(
      layout,
      Modifier.fillMaxSize().padding(GRID_PADDING),
      editing = editing,
      onLayoutChange = ::apply,
      onAddRequest = { addAt = it },
      onConfigureRequest = { configuring = ConfigTarget.Placed(it) },
    )

    val pending = addAt?.takeIf { editing && it in layout.freePositions() }
    if (pending != null) {
      // Which widgets want the config dialog has to be settled here: `configOptions` is composable
      // and the picker's onPick callback is not.
      val needsConfig = mutableSetOf<String>()
      for (widget in availableWidgets()) {
        if (widget.configOptions().isNotEmpty()) needsConfig += widget.id
      }

      WidgetPicker(
        // An empty slot doesn't mean every widget fits it: one whose floor won't clear the grid edge
        // or a neighbour is refused, and addWidget is then a no-op — listing it would give a row that
        // silently does nothing when tapped. Trying the placement is the only honest filter, the same
        // way the resize controls grey out on `resize() == layout`.
        //
        // Fit is now the *only* filter. Widgets already on the page used to be withheld, back when a
        // page could hold one tile per type; a second map with its own zoom and layers is a normal
        // thing to want.
        available = availableWidgets().filter { place(it, pending) != layout },
        onPick = { widget ->
          // A configurable widget is never placed unanswered: the same dialog that edits a tile later
          // collects the answers first, and cancelling it places nothing.
          if (widget.id in needsConfig) {
            configuring = ConfigTarget.New(widget, pending)
          } else {
            apply(place(widget, pending))
            addAt = null
          }
        },
        onDismiss = { addAt = null },
      )
    }

    when (val target = configuring) {
      null -> Unit

      is ConfigTarget.New ->
        WidgetConfigDialog(
          title = "Add ${target.widget.title}",
          options = target.widget.configOptions(),
          initial = emptyMap(),
          confirmLabel = "ADD",
          onConfirm = { config ->
            apply(place(target.widget, target.at, config))
            configuring = null
            addAt = null
          },
          // Back out of configuring, not out of adding: the picker is still open underneath.
          onDismiss = { configuring = null },
        )

      // Only the gear opens this, and it only renders for a widget that resolved and declared
      // options — so an unknown id here means the page changed under the dialog; drop it.
      is ConfigTarget.Placed ->
        WidgetRegistry.byId(target.cell.widgetId)?.let { widget ->
          WidgetConfigDialog(
            title = widget.title,
            options = widget.configOptions(),
            initial = target.cell.config,
            confirmLabel = "SAVE",
            onConfirm = { config ->
              apply(layout.reconfigure(target.cell.instanceId, config))
              configuring = null
            },
            onDismiss = { configuring = null },
          )
        }
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
 * Page-level editing: rename, icon, when it auto-shows, and delete. Delete goes through
 * [onDeleteRequest] so the parent can guard it; the non-destructive edits write straight back through
 * [store].
 *
 * There is no grid-size control: a page is laid out on one of two fixed grids, chosen by the body's
 * aspect ([GridAspect]), and each widget carries its own size, so there is nothing here for the user
 * to tune. Nor is there a control for editing the *other* orientation's arrangement — turn the device
 * and edit it there, which is the only way to see what you are arranging.
 */
@Composable
private fun PageEditToolbar(page: Page, store: PageStore, onDeleteRequest: () -> Unit) {
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

    Spacer(Modifier.weight(1f))

    // The address that pins a second device to this page (see DisplayStore). A page the user created
    // has a generated id, which is otherwise only visible by reading browser storage — without this,
    // display mode would only reach the seeded pages and the apps.
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      Label("DISPLAY")
      // Selectable: a generated id is a dozen random characters, which is exactly the kind of thing
      // you want to copy rather than re-read letter by letter onto the other device.
      SelectionContainer {
        // Darker than its label, like the title field beside it: this is a value to be read off and
        // typed into another device, so it outranks the word naming it.
        Text("?display=${page.id}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = VdtColors.TextDark)
      }
    }

    Spacer(Modifier.weight(1f))

    Icon(
      Icons.Filled.Delete,
      "delete page",
      tint = VdtColors.Red,
      modifier = Modifier.size(20.dp).clickableNoRipple(onClick = onDeleteRequest),
    )
  }
}

/**
 * A field name in the toolbar. [VdtColors.DarkGray] is the palette's quiet ink — see [VdtColors] for
 * why there is no paler one. It is the same tone the unselected [Chip]s and icons beside it use, so
 * the labels read as secondary without disappearing into the toolbar's tinted background.
 */
@Composable
private fun Label(text: String) {
  Text(text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
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
      .clickableNoRipple(onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 3.dp),
  )
}

/** Click target without the material ripple; keeps these dense controls keyboard/AX-activatable. */
private fun Modifier.clickableNoRipple(enabled: Boolean = true, onClick: () -> Unit): Modifier =
  this.clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick)
