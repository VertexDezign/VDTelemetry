package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.Screen
import net.vertexdezign.vdt.app.alerts.AlertSeverity
import net.vertexdezign.vdt.app.apps.availableApps
import net.vertexdezign.vdt.app.pages.Page
import net.vertexdezign.vdt.app.state.Favourite
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.theme.VdtColors

private val Gray400 = Color(0xFF9CA3AF)
private val Gray600 = Color(0xFF4B5563)

/**
 * Bottom bar — a **shell** surface, not a vehicle readout.
 *
 * It carries only what stays true whichever page you are on: how to get somewhere else (left), where
 * you are (centre), and what is wrong (right). Anything that is data about one subsystem belongs in a
 * widget instead, which is where this bar's old heading, fuel gauge and GPS cluster went — see
 * [Navigation] and [EngineTransmission].
 *
 * The consequence worth keeping is that it renders identically on foot; only the right zone empties.
 * The previous version took a `Vehicle?` and collapsed to a placeholder strip whenever there wasn't
 * one, which is most of the time in an app that also covers productions, storage, animals and tasks.
 *
 * The page dots used to be their own band between the pager and this bar. Folding them in removes a
 * row and puts them beside the launcher that navigates between the same pages.
 */
@Composable
fun Footer(
  pages: List<Page>,
  currentPageId: String?,
  screen: Screen?,
  modifier: Modifier = Modifier,
  onOpenLauncher: () -> Unit = {},
  onSelectPage: (String) -> Unit = {},
  onOpenScreen: (Screen) -> Unit = {},
  onOpenNotifications: () -> Unit = {},
) {
  val store = LocalVdtStore.current
  val alerts by store.alerts.active.collectAsState()
  val history by store.alerts.history.collectAsState()
  val favourites by store.favourites.favourites.collectAsState()
  val apps = availableApps()

  Row(
    modifier
      .fillMaxWidth()
      .background(VdtColors.Black)
      .height(64.dp)
      .padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Left — navigation. The launcher is pinned to the edge so it never moves; favourites grow
    // rightward from it, so nothing displaces the one control whose position must be learnable.
    Row(
      Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        Modifier
          .size(38.dp)
          .clip(RoundedCornerShape(9.dp))
          .background(VdtColors.White.copy(alpha = 0.14f))
          .clickable(onClick = onOpenLauncher),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Filled.Menu, "open app launcher", tint = VdtColors.White, modifier = Modifier.size(20.dp))
      }

      // Resolved against what exists *now*: a pin whose page was deleted or whose mod was removed
      // renders as nothing rather than as a dead button, and comes back if the target does.
      for (favourite in favourites) {
        val target = favourite.toScreen()
        when (favourite.kind) {
          Favourite.Kind.App ->
            apps.firstOrNull { it.id == favourite.id }?.let { app ->
              FavouriteButton(app.icon, app.title, target == screen) { onOpenScreen(target) }
            }

          Favourite.Kind.Page ->
            pages.firstOrNull { it.id == favourite.id }?.let { page ->
              FavouriteButton(page.icon.vector, page.title, target == screen) { onOpenScreen(target) }
            }
        }
      }
    }

    // Centre — where you are: page title over the position dots. Weighted like the two side zones so
    // a long page title clips instead of eating the space the launcher and alerts are entitled to.
    Column(
      Modifier.weight(1f),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      pages.firstOrNull { it.id == currentPageId }?.let { current ->
        Text(
          current.title.uppercase(),
          color = VdtColors.White,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      // A single dot carries no information — there is nowhere to swipe to.
      if (pages.size > 1) {
        PageDots(pages, currentPageId, onSelectPage)
      }
    }

    // Right — what's wrong, and the way into the log of what was. The chip is the live condition; the
    // bell is history, and stays put whether or not anything is active so it has a fixed home.
    Row(
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      alerts.maxByOrNull { it.rule.severity.ordinal }?.let { top ->
        AlertChip(
          top.rule.title,
          top.rule.severity,
          extra = alerts.size - 1,
          onClick = onOpenNotifications,
        )
      }
      NotificationBell(count = history.size, onClick = onOpenNotifications)
    }
  }
}

/** A pinned screen. Filled when it's the one open, so the row shows where you are as well as where you can go. */
@Composable
private fun FavouriteButton(icon: ImageVector, label: String, current: Boolean, onClick: () -> Unit) {
  Box(
    Modifier
      .size(38.dp)
      .clip(RoundedCornerShape(9.dp))
      .background(if (current) VdtColors.Accent else VdtColors.White.copy(alpha = 0.08f))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      icon,
      label,
      tint = if (current) VdtColors.Black else VdtColors.White.copy(alpha = 0.8f),
      modifier = Modifier.size(19.dp),
    )
  }
}

/** Opens the notification centre. The badge counts the session log, not the active set. */
@Composable
private fun NotificationBell(count: Int, onClick: () -> Unit) {
  Box(contentAlignment = Alignment.TopEnd) {
    Box(
      Modifier
        .size(38.dp)
        .clip(RoundedCornerShape(9.dp))
        .background(VdtColors.White.copy(alpha = 0.08f))
        .clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.Filled.Notifications,
        "open notifications",
        tint = VdtColors.White.copy(alpha = 0.8f),
        modifier = Modifier.size(19.dp),
      )
    }
    if (count > 0) {
      Box(
        Modifier
          .clip(CircleShape)
          .background(VdtColors.Red)
          .padding(horizontal = 5.dp, vertical = 1.dp),
      ) {
        Text(
          if (count > 9) "9+" else "$count",
          color = VdtColors.White,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }
  }
}

/** One dot per page, the current one larger and filled. Tapping a dot jumps to that page. */
@Composable
private fun PageDots(pages: List<Page>, currentPageId: String?, onSelectPage: (String) -> Unit) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (page in pages) {
      val active = page.id == currentPageId
      Box(
        Modifier
          .size(if (active) 9.dp else 6.dp)
          .clip(CircleShape)
          .background(if (active) VdtColors.White else Gray600)
          .clickable(interactionSource = null, indication = null) { onSelectPage(page.id) },
      )
    }
  }
}

/**
 * The most severe active alert, with a count of the rest. This mirrors the sticky state the alert
 * engine already owns — thresholds and hysteresis live there, so the bar never re-derives a
 * condition and never disagrees with the banner that announced it.
 */
@Composable
private fun AlertChip(title: String, severity: AlertSeverity, extra: Int, onClick: () -> Unit) {
  val background = when (severity) {
    AlertSeverity.Critical -> VdtColors.Red
    AlertSeverity.Warning -> VdtColors.Amber
    AlertSeverity.Info -> Gray600
  }
  Row(
    Modifier
      .clip(RoundedCornerShape(100.dp))
      .background(background)
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    Icon(Icons.Filled.Warning, null, tint = VdtColors.White, modifier = Modifier.size(14.dp))
    Text(
      title.uppercase(),
      color = VdtColors.White,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    if (extra > 0) {
      Text("+$extra", color = Gray400, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
  }
}
