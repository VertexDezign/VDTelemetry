package net.vertexdezign.vdt.app.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import net.vertexdezign.vdt.app.theme.VdtColors

/** How long a banner stays up before expiring on its own. */
private const val BANNER_SHOW_MS = 10_000L

/** At most this many banners at once; a raise beyond it evicts the oldest. */
private const val MAX_BANNERS = 4

private val AlertSeverity.color: Color
  get() = when (this) {
    AlertSeverity.Info -> VdtColors.ProgressBlue
    AlertSeverity.Warning -> VdtColors.Amber
    AlertSeverity.Critical -> VdtColors.Red
  }

/**
 * The shell's transient notification surface: stacks a toast per [raised] alert, newest last. Lives
 * in the root overlay (above whatever page/app is open), so alerts are visible from anywhere. Each
 * banner auto-expires after [BANNER_SHOW_MS] or on tap; expiry only removes the *banner* — whether
 * the alert is still active is [AlertEngine.active]'s business, not this surface's.
 */
@Composable
fun AlertBannerHost(raised: SharedFlow<ActiveAlert>, modifier: Modifier = Modifier) {
  val banners = remember { mutableStateListOf<ActiveAlert>() }
  LaunchedEffect(raised) {
    raised.collect {
      // A raise burst inside the expiry window must not stack banners over the whole screen.
      // TODO: Future improvement, add queue
      if (banners.size >= MAX_BANNERS) banners.removeAt(0)
      banners.add(it)
    }
  }

  Column(
    modifier,
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // ActiveAlert has identity equality, so instances are valid keys even for a re-raised rule.
    for (banner in banners) {
      key(banner) {
        LaunchedEffect(banner) {
          delay(BANNER_SHOW_MS)
          banners.remove(banner)
        }
        Banner(banner, onDismiss = { banners.remove(banner) })
      }
    }
  }
}

@Composable
private fun Banner(alert: ActiveAlert, onDismiss: () -> Unit) {
  Row(
    Modifier
      .widthIn(min = 280.dp, max = 480.dp)
      .height(IntrinsicSize.Min)
      .clip(RoundedCornerShape(6.dp))
      .background(VdtColors.Panel)
      .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(6.dp))
      .clickable(onClick = onDismiss),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.width(4.dp).fillMaxHeight().background(alert.rule.severity.color))
    Icon(
      Icons.Filled.Warning,
      null,
      tint = alert.rule.severity.color,
      modifier = Modifier.padding(start = 12.dp),
    )
    Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
      Text(alert.rule.title, color = VdtColors.TextDark, fontSize = 13.sp, fontWeight = FontWeight.Black)
      Text(alert.message, color = VdtColors.DarkGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
  }
}
