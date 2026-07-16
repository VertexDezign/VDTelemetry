package net.vertexdezign.vdt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Section("APPS") {
        Tiles(apps.map { Entry(it.icon, it.title, Screen.OpenApp(it.id)) }, screen, onOpen)
      }
      Section("PAGES") {
        Tiles(
          pages.map { Entry(it.icon.vector, it.title, Screen.OpenPage(it.id)) },
          screen,
          onOpen,
          trailing = { Tile(Icons.Filled.Add, "New page", active = false, onClick = onCreatePage) },
        )
      }
    }
  }
}

private data class Entry(val icon: ImageVector, val title: String, val target: Screen)

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
private fun Tile(icon: ImageVector, title: String, active: Boolean, onClick: () -> Unit) {
  val accent = if (active) VdtColors.Green else VdtColors.DarkGray
  Column(
    Modifier
      .width(96.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(if (active) VdtColors.Green.copy(alpha = 0.12f) else VdtColors.White.copy(alpha = 0.5f))
      .border(1.dp, if (active) VdtColors.Green else VdtColors.PanelBorder, RoundedCornerShape(6.dp))
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
