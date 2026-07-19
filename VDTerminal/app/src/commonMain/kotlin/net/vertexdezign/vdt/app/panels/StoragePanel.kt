package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.ActionIcon
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.ProductionFill
import net.vertexdezign.vdt.model.StandaloneStorage
import net.vertexdezign.vdt.model.StorageData
import net.vertexdezign.vdt.model.StoredObject
import kotlin.math.roundToInt

/**
 * The Storage app full page: a master/detail over the own-farm [StorageData] channel. The left column
 * lists owned standalone storages (liter silos + object storages, mirroring the game's own "Im Besitz"
 * list); selecting one shows its detail on the right — a silo's per-fill-type levels, or an object
 * storage's count/capacity plus its per-type breakdown with an unload action.
 *
 * Object storages can be unloaded via [onCommand] (the same action as the in-game trigger dialog). A
 * null [data] means the channel is absent (export off / no data yet) — distinct from an owned-nothing
 * farm, which shows the empty state. Production points live on the sibling [ProductionPanel].
 */
@Composable
fun StoragePanel(data: StorageData?, modifier: Modifier = Modifier, onCommand: (ClientMessage) -> Unit = {}) {
  Panel(title = "Storage", icon = Icons.Filled.Warehouse, modifier = modifier) {
    when {
      data == null -> Centered("Waiting for storage data…")
      data.storages.isEmpty() -> Centered("No owned storages")
      else -> StorageMasterDetail(data, onCommand)
    }
  }
}

@Composable
private fun StorageMasterDetail(data: StorageData, onCommand: (ClientMessage) -> Unit) {
  // Selection is by id so it survives the ~2 s refreshes; falls back to the first entry when the
  // selected placeable disappears (sold / demolished) or on first render.
  var selectedId by remember { mutableStateOf<String?>(null) }
  val ids = remember(data) { data.storages.map { it.id } }
  val currentId = selectedId.takeIf { it in ids } ?: ids.firstOrNull()

  Row(Modifier.fillMaxSize()) {
    Column(
      Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      data.storages.forEach { storage ->
        OwnedRow(
          name = storage.name,
          subtitle = storageSubtitle(storage),
          selected = storage.id == currentId,
          onClick = { selectedId = storage.id },
        )
      }
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.PanelBorder))
    Box(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp)) {
      val storage = data.storages.firstOrNull { it.id == currentId }
      if (storage != null) StandaloneStorageDetail(storage, onCommand) else Centered("Select an entry")
    }
  }
}

// ---- Standalone storage detail -------------------------------------------------------------------

@Composable
private fun StandaloneStorageDetail(storage: StandaloneStorage, onCommand: (ClientMessage) -> Unit) {
  val isObject = storage.kind == "object"
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Icon(
        if (isObject) Icons.Filled.Inventory2 else Icons.Filled.Warehouse,
        contentDescription = null,
        tint = VdtColors.DarkGray,
        modifier = Modifier.size(16.dp),
      )
      Text(storage.name, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    if (isObject) {
      ObjectStorageBody(storage, onCommand)
    } else {
      if (storage.fills.isEmpty()) {
        Text("This storage is empty", color = VdtColors.Gray, fontSize = 11.sp)
      }
      storage.fills.forEach { fill -> FillRow(fill) }
    }
  }
}

/**
 * Object storage: a total count/capacity bar, then the per-type breakdown (title × count) with an
 * unload action per group. Unloading opens a dialog to pick the amount (1..min(count, cap)) and sends
 * the command to spawn that many objects out of the storage, the same as the in-game trigger.
 */
@Composable
private fun ObjectStorageBody(storage: StandaloneStorage, onCommand: (ClientMessage) -> Unit) {
  var pending by remember { mutableStateOf<StoredObject?>(null) }
  val fraction = if (storage.capacity > 0) storage.count.toFloat() / storage.capacity.toFloat() else 0f
  ProgressBar(
    fraction = fraction,
    leftLabel = "Objects",
    rightLabel = "${formatInt(storage.count)} / ${formatInt(storage.capacity)}",
  )
  if (storage.count == 0) {
    Text("This storage is empty", color = VdtColors.Gray, fontSize = 11.sp)
  }
  storage.objects.forEach { obj ->
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // The title takes all the slack so the count + unload button are pushed to the right edge and
      // line up across every row (a fixed-width, end-aligned count keeps the "×N" right edges tidy
      // regardless of digit count).
      Text(
        obj.title,
        color = VdtColors.TextDark,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        "×${formatInt(obj.count)}",
        color = VdtColors.DarkGray,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(min = 44.dp),
      )
      ActionIcon(Icons.Filled.Download, "unload ${obj.title}", VdtColors.Green, onClick = { pending = obj })
    }
  }

  pending?.let { obj ->
    // Effective cap: the per-action limit and how many are actually stored (server refuses more).
    val max = minOf(obj.count, if (storage.maxUnloadAmount > 0) storage.maxUnloadAmount else obj.count)
    UnloadDialog(
      obj = obj,
      max = max,
      onConfirm = { amount ->
        onCommand(ClientMessage.UnloadObjectStorage(storage.id, obj.index, obj.title, amount))
        pending = null
      },
      onDismiss = { pending = null },
    )
  }
}

/** Amount picker for unloading one object group — a slider and a synced numeric field, capped at [max]. */
@Composable
private fun UnloadDialog(obj: StoredObject, max: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
  val cap = max.coerceAtLeast(1)
  var amount by remember(obj, cap) { mutableStateOf(cap) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Unload ${obj.title}") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$amount of $cap", color = VdtColors.DarkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (cap > 1) {
          Slider(
            value = amount.toFloat(),
            onValueChange = { amount = it.roundToInt().coerceIn(1, cap) },
            valueRange = 1f..cap.toFloat(),
          )
        }
        OutlinedTextField(
          value = amount.toString(),
          onValueChange = { s -> s.toIntOrNull()?.let { amount = it.coerceIn(1, cap) } },
          label = { Text("Amount") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = { TextButton(onClick = { onConfirm(amount) }) { Text("Unload") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun FillRow(fill: ProductionFill) {
  val fraction = if (fill.capacity > 0) fill.level.toFloat() / fill.capacity.toFloat() else 0f
  ProgressBar(
    fraction = fraction,
    leftLabel = fill.title,
    rightLabel = "${formatInt(fill.level)} / ${formatInt(fill.capacity)} L",
  )
}

private fun typeCountLabel(n: Int): String = if (n == 1) "1 fill type" else "$n fill types"

private fun storageSubtitle(storage: StandaloneStorage): String =
  if (storage.kind == "object") "${storage.count} / ${storage.capacity}" else typeCountLabel(storage.fills.size)
