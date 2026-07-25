package net.vertexdezign.vdt.app.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.theme.VdtColors

/**
 * The notification centre: everything that has fired this session, newest first.
 *
 * Third of the three alert surfaces, and the only one you can go *looking* at. The banner announces a
 * transition and fades; the bar chip says a condition is still true; this answers "what did I miss
 * while I was driving". An entry stays here after its condition clears, which is what makes it a log
 * rather than a second copy of the active set — still-active ones are marked so the two read
 * differently at a glance.
 *
 * Times are the in-game clock (see [ActiveAlert.at]).
 */
@Composable
fun NotificationCenter(
  history: List<ActiveAlert>,
  activeIds: Set<String>,
  onClear: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier
      .fillMaxSize()
      .background(VdtColors.Black.copy(alpha = 0.55f))
      .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    contentAlignment = Alignment.BottomEnd,
  ) {
    Column(
      Modifier
        .padding(horizontal = 14.dp)
        // Sits just above the bottom bar, on the side of the chip that opens it.
        .padding(bottom = 72.dp)
        .width(380.dp)
        .heightIn(max = 460.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(VdtColors.Panel)
        .border(1.dp, VdtColors.PanelBorder, RoundedCornerShape(8.dp))
        // Swallow taps so a click inside the card doesn't reach the dismiss scrim behind it.
        .clickable(interactionSource = null, indication = null) {},
    ) {
      Row(
        Modifier
          .fillMaxWidth()
          .background(VdtColors.White.copy(alpha = 0.5f))
          .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("NOTIFICATIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
        if (history.isNotEmpty()) {
          Text(
            "CLEAR",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = VdtColors.Green,
            modifier = Modifier.clickable(onClick = onClear),
          )
        }
      }

      if (history.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
              Icons.Filled.NotificationsNone,
              null,
              tint = VdtColors.Gray,
              modifier = Modifier.size(28.dp),
            )
            Text("Nothing has come up", fontSize = 12.sp, color = VdtColors.DarkGray)
          }
        }
      } else {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          for (alert in history) {
            NotificationRow(alert, stillActive = alert.rule.id in activeIds)
          }
        }
      }
    }
  }
}

@Composable
private fun NotificationRow(alert: ActiveAlert, stillActive: Boolean) {
  val severityColor = when (alert.rule.severity) {
    AlertSeverity.Critical -> VdtColors.Red
    AlertSeverity.Warning -> VdtColors.Amber
    AlertSeverity.Info -> VdtColors.DarkGray
  }
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Severity stripe rather than another icon — it reads as a column down the list.
    Box(Modifier.width(3.dp).heightIn(min = 30.dp).clip(RoundedCornerShape(2.dp)).background(severityColor))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
          alert.rule.title.uppercase(),
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = VdtColors.TextDark,
        )
        if (stillActive) {
          Text(
            "ACTIVE",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = VdtColors.White,
            modifier = Modifier
              .clip(RoundedCornerShape(100.dp))
              .background(severityColor)
              .padding(horizontal = 6.dp, vertical = 1.dp),
          )
        }
        alert.at?.let {
          Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
        }
      }
      Text(alert.message, fontSize = 12.sp, color = VdtColors.DarkGray)
    }
  }
}
