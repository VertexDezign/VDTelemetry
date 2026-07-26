package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.WakeLockStatus
import net.vertexdezign.vdt.app.theme.brandAccentFor
import net.vertexdezign.vdt.model.Environment
import net.vertexdezign.vdt.model.Vehicle

/**
 * Top bar: environment stats, the vehicle's identity, and controls.
 *
 * The centre is the *identity* block — brand over model name — because the bottom bar now owns
 * navigation and page position. On foot there is no vehicle to name, so it falls back to the product
 * name on a single line. The launcher used to sit at the left of this bar; it moved down beside the
 * page dots so that pressing to change page and reading which page you're on happen in one corner.
 */
@Composable
fun Header(
  env: Environment?,
  vehicle: Vehicle?,
  modifier: Modifier = Modifier,
  wakeLock: WakeLockStatus = WakeLockStatus.Unsupported,
  editing: Boolean = false,
  canEdit: Boolean = true,
  onToggleWakeLock: () -> Unit = {},
  onToggleEdit: () -> Unit = {},
) {
  val accent = brandAccentFor(vehicle?.brand?.name)
  val brandName = vehicle?.brand?.title?.takeIf { it.isNotBlank() } ?: "VDTerminal"
  val temp = env?.weather?.temperature

  Row(
    modifier.fillMaxWidth().background(accent.active).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Left third
    Row(
      Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Stat(Icons.Filled.Thermostat, if (temp != null) "${temp.current}${temp.unit}" else "--", accent.text)
      Stat(Icons.Filled.CalendarMonth, env?.date ?: "--", accent.text)
      Stat(Icons.Filled.Schedule, env?.time ?: "--", accent.text)
    }
    // Center third — identity: brand over model. Modded vehicle names run long, so both lines clip
    // rather than pushing the stats and controls out of the bar.
    Column(
      Modifier.weight(1f),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        brandName.uppercase(),
        color = accent.labelText,
        fontSize = 28.sp,
        fontWeight = FontWeight.Black,
        fontStyle = FontStyle.Italic,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      vehicle?.name?.takeIf { it.isNotBlank() }?.let { name ->
        Text(
          name,
          color = accent.text.copy(alpha = 0.75f),
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    // Right third — controls
    Row(
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      WakeLockButton(wakeLock, onToggleWakeLock, accent.text)
      if (canEdit) {
        Icon(
          if (editing) Icons.Filled.Check else Icons.Filled.Edit,
          if (editing) "done editing layout" else "edit layout",
          tint = accent.text,
          modifier = Modifier.size(20.dp).clickable(onClick = onToggleEdit),
        )
      }
    }
  }
}

/**
 * Screen wake-lock indicator + toggle. Shows the current [status] as an icon + label so the user can
 * see at a glance whether the screen is being kept awake; disabled (and dimmed) when the browser has
 * no Wake Lock API.
 */
@Composable
private fun WakeLockButton(status: WakeLockStatus, onToggle: () -> Unit, tint: Color) {
  val (icon, label, alpha) =
    when (status) {
      WakeLockStatus.On -> Triple(Icons.Filled.Coffee, "AWAKE", 1f)
      WakeLockStatus.Off -> Triple(Icons.Filled.Bedtime, "SLEEP", 0.55f)
      WakeLockStatus.Unsupported -> Triple(Icons.Filled.Bedtime, "N/A", 0.35f)
    }
  val color = tint.copy(alpha = alpha)
  var mod = Modifier.padding(horizontal = 2.dp)
  if (status != WakeLockStatus.Unsupported) mod = mod.clickable(onClick = onToggle)
  Column(modifier = mod, horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(icon, "screen wake lock: $label", tint = color, modifier = Modifier.size(20.dp))
    Text(label, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun Stat(icon: ImageVector, value: String, tint: androidx.compose.ui.graphics.Color) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    Text(value, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
  }
}
