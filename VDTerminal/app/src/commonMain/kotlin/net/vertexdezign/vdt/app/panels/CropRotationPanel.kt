package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.CropOption
import net.vertexdezign.vdt.model.CropRotationData
import net.vertexdezign.vdt.model.CropRotationPlan
import net.vertexdezign.vdt.model.CropRotationSlot

// CropRotation.FALLOW_STATE / NO_CATCH_CROP_STATE — index 0 is the "nothing planted" sentinel for
// both the main crop and the catch crop; rendered muted rather than as a named crop.
private const val FALLOW_STATE = 0

private const val MAX_NAME = 40 // matches the mod's TextInputDialog cap for a rotation name

/**
 * Farm-page panel for the optional FS25_CropRotation channel: the farm's rotation plans and their
 * crop sequences. When the mod ships a crop catalog ([CropRotationData.crops]) the panel is a full
 * editor — per-slot crop/catch-crop dropdowns, add/remove slot, create/delete plan — all via
 * [onCommand] driving the mod's own planner wrappers; without a catalog it degrades to read-only.
 * A null [data] means the mod isn't installed, distinct from installed-but-no-plans.
 */
@Composable
fun CropRotationPanel(data: CropRotationData?, modifier: Modifier = Modifier, onCommand: (ClientMessage) -> Unit = {}) {
  var showCreate by remember { mutableStateOf(false) }
  var pendingDelete by remember { mutableStateOf<CropRotationPlan?>(null) }

  Panel(title = "Crop Rotation", icon = Icons.Filled.Agriculture, modifier = modifier) {
    when {
      data == null -> Centered("CropRotation mod not installed")

      data.rotations.isEmpty() && !data.editable() ->
        Centered("No rotation plans — create one in the game menu")

      else -> {
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (data.editable()) {
            Row(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text("PLANS", color = VdtColors.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
              ActionIcon(Icons.Filled.Add, "new rotation", VdtColors.Green) { showCreate = true }
            }
          }
          if (data.rotations.isEmpty()) {
            Text("No rotation plans yet", color = VdtColors.Gray, fontSize = 11.sp)
          }
          data.rotations
            .sortedBy { it.index }
            .forEach { plan ->
              PlanSection(
                plan = plan,
                crops = data.crops,
                catchCrops = data.catchCrops,
                onCommand = onCommand,
                onDelete = { pendingDelete = plan },
              )
            }
        }
      }
    }
  }

  if (showCreate) {
    NameDialog(
      title = "New rotation",
      onConfirm = { name ->
        onCommand(ClientMessage.CreateRotation(name))
        showCreate = false
      },
      onDismiss = { showCreate = false },
    )
  }

  pendingDelete?.let { plan ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Delete rotation?") },
      text = { Text(plan.name.ifBlank { "Unnamed rotation" }) },
      confirmButton = {
        TextButton(onClick = {
          onCommand(ClientMessage.DeleteRotation(plan.index))
          pendingDelete = null
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
    )
  }
}

private fun CropRotationData.editable(): Boolean = crops.isNotEmpty()

@Composable
private fun PlanSection(
  plan: CropRotationPlan,
  crops: List<CropOption>,
  catchCrops: List<CropOption>,
  onCommand: (ClientMessage) -> Unit,
  onDelete: () -> Unit,
) {
  val editable = crops.isNotEmpty()
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        plan.name.ifBlank { "Unnamed rotation" }.uppercase(),
        color = VdtColors.DarkGray,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      if (editable) ActionIcon(Icons.Filled.Delete, "delete rotation", VdtColors.DarkGray, onDelete)
    }

    if (plan.sequence.isEmpty()) {
      Text("No crops in this rotation", color = VdtColors.Gray, fontSize = 11.sp)
    } else {
      plan.sequence.forEachIndexed { i, slot ->
        SlotRow(
          position = i + 1,
          slot = slot,
          crops = crops,
          catchCrops = catchCrops,
          onCrop = { onCommand(ClientMessage.SetRotationCrop(plan.index, i + 1, it)) },
          onCatchCrop = { onCommand(ClientMessage.SetRotationCatchCrop(plan.index, i + 1, it)) },
        )
      }
    }

    if (editable) {
      Row(Modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ActionIcon(Icons.Filled.Add, "add slot", VdtColors.Green) {
          onCommand(ClientMessage.AddRotationSlot(plan.index))
        }
        // The mod keeps at least one slot, so hide remove when a single one is left.
        if (plan.sequence.size > 1) {
          ActionIcon(Icons.Filled.Remove, "remove last slot", VdtColors.DarkGray) {
            onCommand(ClientMessage.RemoveRotationSlot(plan.index))
          }
        }
      }
    }
  }
}

@Composable
private fun SlotRow(
  position: Int,
  slot: CropRotationSlot,
  crops: List<CropOption>,
  catchCrops: List<CropOption>,
  onCrop: (Int) -> Unit,
  onCatchCrop: (Int) -> Unit,
) {
  val fallow = slot.state == FALLOW_STATE
  // state -> resulting % for each dropdown option (see the mod's per-slot previewYields).
  val cropYields = remember(slot.cropYields) { slot.cropYields.associate { it.state to it.yieldPercent } }
  val catchYields = remember(slot.catchYields) { slot.catchYields.associate { it.state to it.yieldPercent } }
  Row(
    Modifier.fillMaxWidth().padding(start = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(
      Modifier.size(8.dp).clip(CircleShape).background(if (fallow) VdtColors.TrackGray else VdtColors.Green),
    )
    Text("$position.", color = VdtColors.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    // Crop + catch crop on one line (crop gets the extra room), current % on the right.
    CropDropdown(
      modifier = Modifier.weight(1.3f),
      options = crops,
      yields = cropYields,
      selectedState = slot.state,
      fallback = slot.crop.ifBlank { if (fallow) "Fallow" else "Crop ${slot.state}" },
      isCatch = false,
      onSelect = onCrop,
    )
    CropDropdown(
      modifier = Modifier.weight(1f),
      options = catchCrops,
      yields = catchYields,
      selectedState = slot.catchCropState,
      fallback = slot.catchCrop,
      isCatch = true,
      onSelect = onCatchCrop,
    )
    slot.yieldPercent?.let { pct -> YieldLabel(pct) }
  }
}

/** Catch-crop 0 is "no catch crop"; label it in English (the mod string follows the game language). */
private fun displayName(state: Int, name: String, isCatch: Boolean): String =
  if (isCatch && state == FALLOW_STATE) "No catch crop" else name

/**
 * The selected crop with a dropdown to change it; each menu option shows the % this slot would yield
 * if picked ([yields]). When [options] is empty (mod shipped no catalog) it renders as plain
 * read-only text — the [fallback] display name.
 */
@Composable
private fun CropDropdown(
  options: List<CropOption>,
  yields: Map<Int, Int?>,
  selectedState: Int,
  fallback: String,
  isCatch: Boolean,
  onSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val rawName = options.firstOrNull { it.state == selectedState }?.name ?: fallback
  val name = displayName(selectedState, rawName, isCatch)

  val color = if (isCatch) VdtColors.DarkGray else VdtColors.TextDark
  val size = if (isCatch) 11.sp else 13.sp
  val weight = if (isCatch) FontWeight.Normal else FontWeight.SemiBold

  if (options.isEmpty()) {
    // Read-only fallback (no catalog): plain text; hide an unset catch crop rather than clutter.
    if (isCatch && selectedState == FALLOW_STATE && rawName.isBlank()) return
    Text(
      name,
      modifier,
      color = color,
      fontSize = size,
      fontWeight = weight,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    return
  }

  var expanded by remember { mutableStateOf(false) }
  Box(modifier) {
    Row(Modifier.fillMaxWidth().clickable { expanded = true }, verticalAlignment = Alignment.CenterVertically) {
      Text(
        name,
        color = color,
        fontSize = size,
        fontWeight = weight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Icon(Icons.Filled.ArrowDropDown, "change", tint = VdtColors.Gray, modifier = Modifier.size(14.dp))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { OptionRow(displayName(option.state, option.name, isCatch), yields[option.state]) },
          onClick = {
            onSelect(option.state)
            expanded = false
          },
        )
      }
    }
  }
}

/** A dropdown option: crop name on the left, its resulting yield % (coloured) on the right. */
@Composable
private fun OptionRow(name: String, pct: Int?) {
  Row(
    Modifier.widthIn(min = 190.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      name,
      fontSize = 13.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    if (pct != null) {
      Spacer(Modifier.width(16.dp))
      Text("$pct%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = yieldColor(pct))
    }
  }
}

private fun yieldColor(pct: Int): Color = when {
  pct > 100 -> VdtColors.Green
  pct < 100 -> VdtColors.Red
  else -> VdtColors.DarkGray
}

@Composable
private fun YieldLabel(pct: Int) {
  Text("$pct%", color = yieldColor(pct), fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun NameDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
  var name by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { if (it.length <= MAX_NAME) name = it },
        label = { Text("Name") },
        singleLine = true,
        supportingText = { Text("${name.length}/$MAX_NAME") },
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Create") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun ActionIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
  Icon(
    icon,
    contentDescription = description,
    tint = tint,
    modifier = Modifier.size(20.dp).clip(CircleShape).clickable(onClick = onClick).padding(1.dp),
  )
}

@Composable
private fun Centered(text: String) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = VdtColors.Gray, fontSize = 12.sp)
  }
}
