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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.WakeLockStatus
import net.vertexdezign.vdt.app.theme.brandAccentFor
import net.vertexdezign.vdt.model.Environment
import net.vertexdezign.vdt.model.Vehicle

/** Top bar: environment stats, brand title, and controls. Port of the React `Header`. */
@Composable
fun Header(
  env: Environment?,
  vehicle: Vehicle?,
  modifier: Modifier = Modifier,
  wakeLock: WakeLockStatus = WakeLockStatus.Unsupported,
  onToggleWakeLock: () -> Unit = {},
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
      horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      Icon(Icons.Filled.Menu, "menu", tint = accent.text, modifier = Modifier.size(24.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stat(Icons.Filled.Thermostat, if (temp != null) "${temp.current}${temp.unit}" else "--", accent.text)
        Stat(Icons.Filled.CalendarMonth, env?.date ?: "--", accent.text)
        Stat(Icons.Filled.Schedule, env?.time ?: "--", accent.text)
      }
    }
    // Center third — brand title
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
      Text(
        brandName.uppercase(),
        color = accent.labelText,
        fontSize = 28.sp,
        fontWeight = FontWeight.Black,
        fontStyle = FontStyle.Italic,
      )
    }
    // Right third — controls
    Row(
      Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      WakeLockButton(wakeLock, onToggleWakeLock, accent.text)
      Icon(Icons.Filled.Search, "search", tint = accent.text, modifier = Modifier.size(20.dp))
      Icon(Icons.Filled.Remove, "zoom out", tint = accent.text, modifier = Modifier.size(20.dp))
      Icon(Icons.Filled.Add, "zoom in", tint = accent.text, modifier = Modifier.size(20.dp))
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
