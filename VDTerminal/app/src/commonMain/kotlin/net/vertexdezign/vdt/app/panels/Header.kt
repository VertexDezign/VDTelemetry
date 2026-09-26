package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.WakeLockStatus
import net.vertexdezign.vdt.app.components.ViewTab
import net.vertexdezign.vdt.app.state.ThemeMode
import net.vertexdezign.vdt.app.state.UiScaleStore
import net.vertexdezign.vdt.app.theme.VdtColors
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
  theme: ThemeMode = ThemeMode.System,
  onThemeChange: (ThemeMode) -> Unit = {},
  uiScale: Int = UiScaleStore.DEFAULT,
  onUiScaleChange: (Int) -> Unit = {},
) {
  val accent = brandAccentFor(vehicle?.brand?.name)
  val brandName = vehicle?.brand?.title?.takeIf { it.isNotBlank() } ?: "VDTerminal"
  val temp = env?.weather?.temperature

  BoxWithConstraints(modifier.fillMaxWidth().background(accent.active).padding(horizontal = 16.dp, vertical = 8.dp)) {
    // Thirds keep the brand on the screen's centre line, but a phone's third is narrower than the three
    // stats or the three controls. There the controls take the room they need, the stats shrink to the
    // clock, and the brand gets what is left. The layout doesn't depend on canEdit, so opening an app
    // (which hides EDIT) doesn't make the header jump.
    val compact = maxWidth / 3 < MIN_THIRD
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      // Left third
      Row(
        if (compact) Modifier.padding(end = 8.dp) else Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (!compact) {
          Stat(Icons.Filled.Thermostat, if (temp != null) "${temp.current}${temp.unit}" else "--", accent.text)
          Stat(Icons.Filled.CalendarMonth, env?.date ?: "--", accent.text)
        }
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
        if (compact) Modifier.padding(start = 8.dp) else Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        DisplaySettings(theme, onThemeChange, uiScale, onUiScaleChange, accent.text)
        WakeLockButton(wakeLock, onToggleWakeLock, accent.text)
        if (canEdit) {
          HeaderControl(
            if (editing) Icons.Filled.Check else Icons.Filled.Edit,
            if (editing) "DONE" else "EDIT",
            "edit layout",
            accent.text,
            onClick = onToggleEdit,
          )
        }
      }
    }
  }
}

/**
 * The narrowest third the full header fits in: three [HeaderControl]s at [CONTROL_MIN_WIDTH] with their
 * gaps, rounded up for the three stats, which need about as much.
 */
private val MIN_THIRD = 180.dp

private val CONTROL_MIN_WIDTH = 48.dp

/**
 * One control in the header's right third: an icon over the word for its state, the way every one of
 * them reads — the word is what says AWAKE or DARK, the icon only finds the control.
 *
 * The tap area is the whole column plus padding, at least 48dp wide: the glyph is 20dp, and the header
 * is the one place a hurried thumb goes to without looking. A null [onClick] is a control the browser
 * cannot offer, drawn at [tint]'s own dimmed alpha and not tappable.
 */
@Composable
private fun HeaderControl(icon: ImageVector, label: String, description: String, tint: Color, onClick: (() -> Unit)?) {
  var mod = Modifier.widthIn(min = CONTROL_MIN_WIDTH).clip(RoundedCornerShape(6.dp))
  if (onClick != null) mod = mod.clickable(role = Role.Button, onClick = onClick)
  Column(
    modifier = mod.padding(horizontal = 6.dp, vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(icon, "$description: $label", tint = tint, modifier = Modifier.size(20.dp))
    Text(label, color = tint, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
  HeaderControl(
    icon,
    label,
    "screen wake lock",
    tint.copy(alpha = alpha),
    onClick = onToggle.takeIf { status != WakeLockStatus.Unsupported },
  )
}

/**
 * This device's look: light or dark, and how big it draws. Both are set once per device and then left,
 * so they share one control and a menu rather than taking two of the header's few slots — a phone's
 * header has room for three. The label is the size, the one of the two you can't see at a glance.
 *
 * AUTO follows the device's own light/dark setting, so a tablet that goes dark at sunset takes the
 * terminal with it. Every option is a word, so the chosen one is told apart by its fill and its word,
 * not by hue.
 */
@Composable
private fun DisplaySettings(
  theme: ThemeMode,
  onThemeChange: (ThemeMode) -> Unit,
  uiScale: Int,
  onUiScaleChange: (Int) -> Unit,
  tint: Color,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    HeaderControl(Icons.Filled.DisplaySettings, "$uiScale%", "display settings", tint, onClick = { open = true })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingRow("THEME") {
          for ((mode, label) in listOf(
            ThemeMode.System to "Auto",
            ThemeMode.Light to "Light",
            ThemeMode.Dark to "Dark",
          )) {
            ViewTab(label, theme == mode, { onThemeChange(mode) })
          }
        }
        SettingRow("SIZE") {
          for (step in UiScaleStore.STEPS) {
            ViewTab("$step%", uiScale == step, { onUiScaleChange(step) })
          }
        }
      }
    }
  }
}

@Composable
private fun SettingRow(label: String, options: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { options() }
  }
}

@Composable
private fun Stat(icon: ImageVector, value: String, tint: androidx.compose.ui.graphics.Color) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    Text(value, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
  }
}
