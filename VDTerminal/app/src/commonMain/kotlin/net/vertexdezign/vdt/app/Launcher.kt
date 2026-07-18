package net.vertexdezign.vdt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import net.vertexdezign.vdt.app.apps.VdtApp
import net.vertexdezign.vdt.app.pages.Page
import net.vertexdezign.vdt.app.theme.VdtColors

private const val TILES_PER_ROW = 3

/**
 * Full-screen launcher: a scrim (tap to dismiss) over a card with two sections — **Apps** (code-defined
 * features, opened full-screen) and **Pages** (the user's own widget dashboards, plus a tile to create
 * another). Selecting either opens it via [onOpen]. The current [screen] is highlighted.
 */
@Composable
fun Launcher(
  apps: List<VdtApp>,
  pages: List<Page>,
  screen: Screen,
  onOpen: (Screen) -> Unit,
  onCreatePage: () -> Unit,
  onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
  onRestoreDefaults: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier
      .fillMaxSize()
      .background(VdtColors.Black.copy(alpha = 0.55f))
      .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(VdtColors.Panel)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(8.dp))
        // Swallow taps on the card so they don't fall through to the dismiss scrim.
        .clickable(interactionSource = null, indication = null) {}
        .verticalScroll(rememberScrollState())
        .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Section("APPS") {
        Tiles(apps.map { Entry(it.icon, it.title, Screen.OpenApp(it.id)) }, screen, onOpen)
      }
      Section("PAGES") {
        // Long-press a page tile to drag it into a new slot; the order is what drives both the swipe
        // order and which page auto-shows, so reordering here is the single knob for both.
        ReorderablePageGrid(
          pages,
          screen,
          onOpen,
          onReorder,
          trailing = { Tile(Icons.Filled.Add, "New page", active = false, onClick = onCreatePage) },
        )
        // With every page gone there's no path back to the seeded dashboards — offer to restore them.
        if (pages.isEmpty()) RestoreDefaultsRow(onRestoreDefaults)
      }
    }
  }
}

private data class Entry(val icon: ImageVector, val title: String, val target: Screen)

/** Full-width action shown only when no pages remain: brings back the starter Vehicle/Farm pages. */
@Composable
private fun RestoreDefaultsRow(onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .background(VdtColors.White.copy(alpha = 0.5f))
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(Icons.Filled.Refresh, contentDescription = null, tint = VdtColors.DarkGray, modifier = Modifier.size(20.dp))
    Text(
      "RESTORE DEFAULT PAGES",
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      color = VdtColors.DarkGray,
    )
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
    content()
  }
}

/** Tiles wrapped onto rows of [TILES_PER_ROW], with an optional extra tile after the last entry. */
@Composable
private fun Tiles(
  entries: List<Entry>,
  screen: Screen,
  onOpen: (Screen) -> Unit,
  trailing: (@Composable () -> Unit)? = null,
) {
  // Reserve a slot for the trailing tile so it wraps with the rest instead of overflowing a row.
  val slots = entries.size + if (trailing != null) 1 else 0
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    for (start in 0 until maxOf(slots, 1) step TILES_PER_ROW) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (i in start until minOf(start + TILES_PER_ROW, slots)) {
          if (i < entries.size) {
            val entry = entries[i]
            Tile(entry.icon, entry.title, active = entry.target == screen, onClick = { onOpen(entry.target) })
          } else {
            trailing?.invoke()
          }
        }
      }
    }
  }
}

@Composable
private fun Tile(
  icon: ImageVector,
  title: String,
  active: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  highlighted: Boolean = false,
) {
  val accent = if (active) VdtColors.Green else VdtColors.DarkGray
  // A drop-target tile borrows the green accent (bolder border) so the user sees where a dragged page
  // will land; otherwise the tile falls back to its active/idle look.
  val borderColor = if (highlighted || active) VdtColors.Green else VdtColors.PanelBorder
  Column(
    modifier
      .width(96.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(
        if (active ||
          highlighted
        ) {
          VdtColors.Green.copy(alpha = 0.12f)
        } else {
          VdtColors.White.copy(alpha = 0.5f)
        },
      )
      .border(if (highlighted) 2.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 14.dp, horizontal = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(36.dp))
    Text(
      title.uppercase(),
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      color = accent,
      textAlign = TextAlign.Center,
    )
  }
}

/**
 * The Pages section laid out like [Tiles] (wrapped rows of [TILES_PER_ROW], [trailing] tile last) but
 * with each page tile draggable: a long-press lifts it and it follows the finger, and on release it
 * drops into whichever page slot its centre is nearest, reported through [onReorder]. Slot centres are
 * captured in the grid's own coordinate space so the hit-test is independent of row wrapping; nothing
 * reflows mid-drag (the lifted tile only translates), which keeps those centres stable.
 */
@Composable
private fun ReorderablePageGrid(
  pages: List<Page>,
  screen: Screen,
  onOpen: (Screen) -> Unit,
  onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
  trailing: @Composable () -> Unit,
) {
  var gridCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
  val centers = remember { mutableStateMapOf<Int, Offset>() }
  var dragIndex by remember { mutableStateOf<Int?>(null) }
  var dragOffset by remember { mutableStateOf(Offset.Zero) }

  fun nearest(point: Offset): Int? = pages.indices
    .filter { centers[it] != null }
    .minByOrNull { (centers[it]!! - point).getDistanceSquared() }

  val dropTarget = dragIndex?.let { di -> centers[di]?.let { nearest(it + dragOffset) } }

  // Reserve the trailing slot so the "New page" tile wraps with the rest instead of overflowing.
  val slots = pages.size + 1
  Column(
    Modifier.onGloballyPositioned { gridCoords = it },
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    for (start in 0 until slots step TILES_PER_ROW) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (i in start until minOf(start + TILES_PER_ROW, slots)) {
          if (i >= pages.size) {
            trailing()
            continue
          }
          val page = pages[i]
          val dragging = dragIndex == i
          Tile(
            page.icon.vector,
            page.title,
            active = Screen.OpenPage(page.id) == screen,
            onClick = { onOpen(Screen.OpenPage(page.id)) },
            highlighted = !dragging && dropTarget == i,
            modifier =
            Modifier
              .zIndex(if (dragging) 1f else 0f)
              .graphicsLayer {
                if (dragging) {
                  translationX = dragOffset.x
                  translationY = dragOffset.y
                  scaleX = 1.05f
                  scaleY = 1.05f
                  shadowElevation = 8f
                }
              }
              .onGloballyPositioned { c ->
                gridCoords?.let { grid -> centers[i] = grid.localBoundingBoxOf(c).center }
              }
              .pointerInput(pages.size) {
                detectDragGesturesAfterLongPress(
                  onDragStart = {
                    dragIndex = i
                    dragOffset = Offset.Zero
                  },
                  onDrag = { change, delta ->
                    change.consume()
                    dragOffset += delta
                  },
                  onDragEnd = {
                    centers[i]?.let { origin -> nearest(origin + dragOffset)?.let { onReorder(i, it) } }
                    dragIndex = null
                    dragOffset = Offset.Zero
                  },
                  onDragCancel = {
                    dragIndex = null
                    dragOffset = Offset.Zero
                  },
                )
              },
          )
        }
      }
    }
  }
}
