package net.vertexdezign.vdt.app.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.LocalNavigator
import net.vertexdezign.vdt.app.Screen
import net.vertexdezign.vdt.app.apps.VdtApp
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * A tile that opens an [app] — the app icon of a home screen, placed on a page like any other widget.
 * Tapping it opens that app full-screen through [LocalNavigator].
 *
 * This is what the fine grid buys: a shortcut is meaningful at a size no readout panel could work at,
 * so it declares a 1×1 floor and everything else keeps its own. It also reaches the apps that
 * contribute no widget at all (Production, Storage, Animals, Diagnostics) — before this they were only
 * ever two taps deep behind the launcher, or one of the four pinned slots on the bar.
 *
 * One instance per registered app, built once in [WidgetRegistry]; [id] embeds the app's id so a
 * placed shortcut survives a restart, and disappears cleanly if that app is ever removed (an
 * unresolvable widget id is dropped when pages load).
 */
class ShortcutWidget(private val app: VdtApp) : Widget {
  override val id: String = idFor(app.id)

  /** Only the picker shows this; the tile itself is labelled with the app's own name. */
  override val title: String = "Open ${app.title}"
  override val icon: ImageVector get() = app.icon

  // A single cell — the smallest thing the grid can hold, and on a landscape tablet a square one at
  // roughly 91×90dp. Being placeable at one cell is the whole point of the grid rework; a row of
  // these along the edge of a page is a dock, not a set of panels.
  override val defaultColSpan = 1
  override val defaultRowSpan = 1
  override val minColSpan = 1
  override val minRowSpan = 1

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val open = LocalNavigator.current
    ShortcutTile(app.icon, app.title, onClick = { open(Screen.OpenApp(app.id)) }, modifier = modifier)
  }

  companion object {
    private const val ID_PREFIX = "shortcut:"

    /** The widget id of the shortcut to the app with [appId] — how a seeded layout names one. */
    fun idFor(appId: String): String = "$ID_PREFIX$appId"
  }
}

/**
 * The icon-and-label tile. It centres a square inside whatever cell block it was given rather than
 * stretching to fill it: one grid serves both orientations, so the same 1×1 shortcut is square in
 * landscape (~91×90dp) but tall and narrow in portrait (~61×142dp), and an icon that tracked those
 * bounds would visibly distort on rotation. The square is also capped, so a shortcut someone has
 * deliberately resized up stays an icon instead of becoming a billboard.
 */
@Composable
private fun ShortcutTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val side = minOf(maxWidth, maxHeight).coerceAtMost(MAX_TILE_SIDE)
    Column(
      Modifier
        .size(side)
        .clip(RoundedCornerShape(8.dp))
        .background(VdtColors.Panel)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick)
        .padding(6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
      Icon(
        icon,
        contentDescription = null,
        tint = VdtColors.Green,
        modifier = Modifier.size((side * 0.42f).coerceIn(20.dp, 48.dp)),
      )
      Text(
        label.uppercase(),
        fontSize = 10.sp,
        color = VdtColors.DarkGray,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** Past this the icon stops reading as an icon and starts reading as a mistake. */
private val MAX_TILE_SIDE = 140.dp
