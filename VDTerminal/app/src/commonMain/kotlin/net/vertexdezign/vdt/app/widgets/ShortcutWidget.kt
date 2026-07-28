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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.LocalNavigator
import net.vertexdezign.vdt.app.Screen
import net.vertexdezign.vdt.app.apps.AppRegistry
import net.vertexdezign.vdt.app.apps.availableApps
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * A tile that opens an app — the app icon of a home screen, placed on a page like any other widget.
 * Which app is per-instance config, so a page can hold as many as it likes; tapping one opens that
 * app full-screen through [LocalNavigator].
 *
 * This is what the fine grid buys: a shortcut is meaningful at a size no readout panel could work at,
 * so it declares a 1×1 floor and everything else keeps its own. It also reaches the apps that
 * contribute no widget at all (Production, Storage, Animals, Diagnostics) — before this they were only
 * ever two taps deep behind the launcher, or one of the four pinned slots on the bar.
 *
 * There used to be one shortcut *type* per app, which put an "Open …" row in the picker for every app
 * ever registered and capped a page at one shortcut each. One configurable type keeps the picker to a
 * single row however many apps exist.
 */
object ShortcutWidget : Widget {
  override val id = "shortcut"
  override val title = "Shortcut"
  override val icon: ImageVector = Icons.Filled.Apps

  // A single cell — the smallest thing the grid can hold, and on a landscape tablet a square one at
  // roughly 91×90dp. Being placeable at one cell is the whole point of the grid rework; a row of
  // these along the edge of a page is a dock, not a set of panels.
  override val defaultColSpan = 1
  override val defaultRowSpan = 1
  override val minColSpan = 1
  override val minRowSpan = 1

  /** The config key naming the app this shortcut opens — how a seeded layout points one somewhere. */
  const val APP_KEY = "app"

  /**
   * One choice per *available* app: there is no point offering a shortcut to a screen that can only
   * say "mod not installed". A tile already pointed at an app that has since gone quiet keeps
   * pointing there — see [Content].
   */
  @Composable
  override fun configOptions(): List<ConfigOption> = listOf(
    ConfigOption(
      key = APP_KEY,
      label = "Opens",
      choices = availableApps().map { ConfigOption.Choice(it.id, it.title, it.icon) },
    ),
  )

  @Composable
  override fun Content(modifier: Modifier, config: WidgetConfig) {
    val open = LocalNavigator.current
    val app = AppRegistry.byId(config[APP_KEY].orEmpty())

    // Resolved against the *whole* registry, not the available apps, and left alone when it isn't
    // there: a placed tile is the user's, so a mod going away — or simply not having sent its first
    // message yet — greys the shortcut out rather than repointing or deleting it. It lights up again
    // by itself, because availability is composable.
    if (app == null) {
      ShortcutTile(
        Icons.AutoMirrored.Filled.HelpOutline,
        "Unavailable",
        tint = VdtColors.Gray,
        onClick = null,
        modifier = modifier,
      )
    } else {
      ShortcutTile(app.icon, app.title, onClick = { open(Screen.OpenApp(app.id)) }, modifier = modifier)
    }
  }
}

/**
 * The icon-and-label tile. It centres a square inside whatever cell block it was given rather than
 * stretching to fill it: one grid serves both orientations, so the same 1×1 shortcut is square in
 * landscape (~91×90dp) but tall and narrow in portrait (~61×142dp), and an icon that tracked those
 * bounds would visibly distort on rotation. The square is also capped, so a shortcut someone has
 * deliberately resized up stays an icon instead of becoming a billboard.
 *
 * A null [onClick] renders the tile inert — it has nothing to open.
 */
@Composable
private fun ShortcutTile(
  icon: ImageVector,
  label: String,
  onClick: (() -> Unit)?,
  modifier: Modifier = Modifier,
  tint: Color = VdtColors.Green,
) {
  BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val side = minOf(maxWidth, maxHeight).coerceAtMost(MAX_TILE_SIDE)
    Column(
      Modifier
        .size(side)
        .clip(RoundedCornerShape(8.dp))
        .background(VdtColors.Panel)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(8.dp))
        .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
        .padding(6.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
      Icon(
        icon,
        contentDescription = null,
        tint = tint,
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
