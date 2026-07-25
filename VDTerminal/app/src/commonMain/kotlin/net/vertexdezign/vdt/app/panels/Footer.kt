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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.alerts.AlertSeverity
import net.vertexdezign.vdt.app.pages.Page
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
  modifier: Modifier = Modifier,
  onOpenLauncher: () -> Unit = {},
  onSelectPage: (String) -> Unit = {},
) {
  val alerts by LocalVdtStore.current.alerts.active.collectAsState()

  Row(
    modifier
      .fillMaxWidth()
      .background(VdtColors.Black)
      .height(64.dp)
      .padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Left — navigation. The launcher is pinned to the edge so it never moves; pinned favourites are
    // meant to grow rightward from it rather than displace it.
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
    }

    // Centre — where you are: page title over the position dots.
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
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

    // Right — what's wrong. Empty is the good case, and the usual one.
    Row(
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      alerts.maxByOrNull { it.rule.severity.ordinal }?.let { top ->
        AlertChip(top.rule.title, top.rule.severity, extra = alerts.size - 1)
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
private fun AlertChip(title: String, severity: AlertSeverity, extra: Int) {
  val background = when (severity) {
    AlertSeverity.Critical -> VdtColors.Red
    AlertSeverity.Warning -> VdtColors.Amber
    AlertSeverity.Info -> Gray600
  }
  Row(
    Modifier
      .clip(RoundedCornerShape(100.dp))
      .background(background)
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
