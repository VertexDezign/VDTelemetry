package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.AUTO_DRIVE_MAX_LOOPS
import net.vertexdezign.vdt.AutoDriveSlot
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.FilterChip
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.SearchField
import net.vertexdezign.vdt.app.components.ToolButton
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.AutoDrive
import net.vertexdezign.vdt.model.AutoDriveData
import net.vertexdezign.vdt.model.AutoDriveDestination
import net.vertexdezign.vdt.model.AutoDriveFillType
import net.vertexdezign.vdt.model.AutoDriveTarget
import net.vertexdezign.vdt.model.Vehicle

/**
 * AutoDrive's HUD on the terminal (issue #97): what it is doing, the mode, where it goes and where it
 * unloads, what it loads and how many rounds — and the start/stop. Every control sends an absolute
 * state ([ClientMessage.SetAutoDriveMode] and its siblings); the mod turns that into AutoDrive's own
 * events, so a multiplayer client drives it the way its HUD would.
 *
 * Each row appears only in the modes AutoDrive's own HUD shows it in, so the panel is never asking
 * for an unload destination a drive-to will not use.
 *
 * A destination is picked from a list that **replaces the controls** while it is open, rather than
 * from a dropdown: a network can have hundreds of markers, and on a cab tablet the panel's whole
 * height is what makes that list usable. Search narrows it; folders group it the way AutoDrive files
 * them.
 */
@Composable
fun AutoDrivePanel(
  vehicle: Vehicle?,
  network: AutoDriveData?,
  modifier: Modifier = Modifier,
  onCommand: (ClientMessage) -> Unit = {},
) {
  val ad = vehicle?.autoDrive
  var picking by remember { mutableStateOf<Picking?>(null) }

  Panel(
    title = "AutoDrive",
    icon = Icons.Filled.Route,
    modifier = modifier,
    headerActions = {
      if (picking != null) {
        ToolButton(Icons.Filled.Close, "close the list", onClick = { picking = null })
      }
    },
  ) {
    when {
      network == null -> Empty("AutoDrive is not installed")

      ad == null -> Empty(if (vehicle == null) "Not in a vehicle" else "AutoDrive does not drive this vehicle")

      picking is Picking.Destination -> {
        val slot = (picking as Picking.Destination).slot
        val selected = if (slot == AutoDriveSlot.TARGET) ad.target else ad.unloadTarget
        PickList(
          placeholder = "Search destinations…",
          groups = { query -> destinationGroups(network.destinations, query) },
          key = { "d:${it.id}" },
          label = { it.name },
          isCurrent = { it.id == selected?.id },
          onPick = { destination ->
            onCommand(ClientMessage.SetAutoDriveTarget(slot, destination.id, destination.name))
            picking = null
          },
        )
      }

      picking == Picking.FillType -> {
        PickList(
          placeholder = "Search fill types…",
          groups = { query ->
            listOf(null to fillTypeMatches(ad.fillTypes.orEmpty(), query)).filter { it.second.isNotEmpty() }
          },
          key = { "f:${it.name}" },
          label = { it.title },
          isCurrent = { it.name == ad.fillType },
          onPick = { fillType ->
            if (fillType.name != ad.fillType) onCommand(ClientMessage.SetAutoDriveFillType(fillType.name))
            picking = null
          },
        )
      }

      else -> Controls(ad, network, onPick = { picking = it }, onCommand = onCommand)
    }
  }
}

@Composable
private fun Empty(text: String) {
  Text(text, fontSize = 12.sp, color = VdtColors.DarkGray)
}

@Composable
private fun Controls(
  ad: AutoDrive,
  network: AutoDriveData,
  onPick: (Picking) -> Unit,
  onCommand: (ClientMessage) -> Unit,
) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Status(ad)

    Section("MODE") {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (mode in ad.modes) {
          FilterChip(mode.label, active = ad.mode == mode, onClick = {
            if (ad.mode != mode) onCommand(ClientMessage.SetAutoDriveMode(mode))
          })
        }
      }
    }

    // In pick-up & deliver and load the first marker is where it loads; in the rest it is simply where
    // it goes. AutoDrive's HUD labels the two lists by icon only, so the words here are ours.
    val loads = ad.mode.hasUnloadTarget
    Section(if (loads) "PICK UP AT" else "DESTINATION") {
      TargetButton(ad.target, network.destinations.isNotEmpty()) { onPick(Picking.Destination(AutoDriveSlot.TARGET)) }
    }
    if (ad.mode.hasUnloadTarget) {
      Section("DELIVER TO") {
        TargetButton(ad.unloadTarget, network.destinations.isNotEmpty()) {
          onPick(Picking.Destination(AutoDriveSlot.UNLOAD))
        }
      }
    }

    val fillTypes = ad.fillTypes
    if (ad.mode.hasFillType && !fillTypes.isNullOrEmpty()) {
      // A list rather than chips: a grain trailer is offered every bulk fill type in the game — sixty-odd
      // on the first capture — which as chips would be the whole panel.
      Section("LOAD") {
        TargetButton(
          fillTypes.firstOrNull {
            it.name == ad.fillType
          }?.title,
          enabled = true,
        ) { onPick(Picking.FillType) }
      }
    }

    if (ad.mode.hasLoops) {
      Section("ROUNDS") { Loops(ad, onCommand) }
    }

    StartStop(ad, onCommand)
  }
}

/** Driving or not, in words — and while driving, what it is doing and how long it has left. */
@Composable
private fun Status(ad: AutoDrive) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      // The state is the word; the fill only reinforces it.
      Text(
        if (ad.active) "DRIVING" else "STOPPED",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = if (ad.active) VdtColors.OnFill else VdtColors.DarkGray,
        modifier =
        Modifier
          .clip(RoundedCornerShape(3.dp))
          .background(if (ad.active) VdtColors.Green else VdtColors.TrackGray)
          .padding(horizontal = 8.dp, vertical = 3.dp),
      )
      if (ad.active) {
        val heading = ad.heading?.name
        val eta = ad.remainingSeconds?.let(::formatDriveTime)
        Text(
          listOfNotNull(heading?.let { "to $it" }, eta).joinToString(" · "),
          fontSize = 12.sp,
          color = VdtColors.TextDark,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    ad.task?.let {
      Text(it, fontSize = 12.sp, color = VdtColors.DarkGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
    content()
  }
}

/** The chosen marker, as a field that opens the list. Disabled on a map with no network yet. */
@Composable
private fun TargetButton(target: AutoDriveTarget?, enabled: Boolean, onClick: () -> Unit) =
  TargetButton(target?.name, enabled, onClick)

/** A choice as a field that opens its list: the current [label] in bold, or a prompt. */
@Composable
private fun TargetButton(label: String?, enabled: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(4.dp)
  val chosen = label?.ifBlank { null }
  Text(
    chosen ?: "Choose…",
    fontSize = 13.sp,
    fontWeight = if (chosen != null) FontWeight.Bold else FontWeight.Normal,
    color = if (enabled) VdtColors.TextDark else VdtColors.TextDisabled,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier =
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(VdtColors.Surface)
      .border(1.dp, VdtColors.PanelBorder, shape)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 8.dp),
  )
}

/**
 * The loop counter: `- N +`, where 0 is "until stopped" and reads as the word — the HUD draws an
 * infinity sign, which the wasm build has no glyph for. While driving, the rounds left as AutoDrive's
 * HUD counts them.
 */
@Composable
private fun Loops(ad: AutoDrive, onCommand: (ClientMessage) -> Unit) {
  fun set(loops: Int) = onCommand(ClientMessage.SetAutoDriveLoops(loops.coerceIn(0, AUTO_DRIVE_MAX_LOOPS)))
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    ToolButton(Icons.Filled.Remove, "fewer rounds", onClick = { set(ad.loops - 1) }, enabled = ad.loops > 0)
    Text(
      loopsLabel(ad),
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      color = VdtColors.TextDark,
    )
    ToolButton(
      Icons.Filled.Add,
      "more rounds",
      onClick = { set(ad.loops + 1) },
      enabled =
      ad.loops < AUTO_DRIVE_MAX_LOOPS,
    )
  }
}

/** "Until stopped", the count, or while driving "2 of 5 left" — the HUD's own remaining count. */
internal fun loopsLabel(ad: AutoDrive): String = when {
  ad.loops == 0 -> "Until stopped"
  ad.active -> "${(ad.loops - ad.loopsDone).coerceAtLeast(0)} of ${ad.loops} left"
  else -> "${ad.loops}"
}

/**
 * Start or stop, as a full-width button whose word is the action. Start is refused without a
 * destination, as AutoDrive's own start/stop is.
 */
@Composable
private fun StartStop(ad: AutoDrive, onCommand: (ClientMessage) -> Unit) {
  val canStart = ad.target != null
  val enabled = ad.active || canStart
  val shape = RoundedCornerShape(4.dp)
  Text(
    if (ad.active) "STOP" else "START",
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
    color = if (!enabled) VdtColors.TextDisabled else VdtColors.OnFill,
    modifier =
    Modifier
      .fillMaxWidth()
      .clip(shape)
      .background(
        when {
          !enabled -> VdtColors.TrackGray
          ad.active -> VdtColors.Red
          else -> VdtColors.Green
        },
      )
      .clickable(enabled = enabled, role = Role.Button) {
        onCommand(ClientMessage.SetAutoDriveActive(!ad.active))
      }
      .padding(vertical = 10.dp),
    textAlign = TextAlign.Center,
  )
}

/** What the list replacing the controls is picking, while one is open. */
private sealed interface Picking {
  data class Destination(val slot: AutoDriveSlot) : Picking

  data object FillType : Picking
}

/**
 * A searchable list that replaces the controls while it is open: [groups] narrows by the query and
 * groups (a null group heads nothing). The current choice is marked in bold and by a filled bar, never
 * by colour alone.
 */
@Composable
private fun <T> PickList(
  placeholder: String,
  groups: (String) -> List<Pair<String?, List<T>>>,
  key: (T) -> String,
  label: (T) -> String,
  isCurrent: (T) -> Boolean,
  onPick: (T) -> Unit,
) {
  var query by remember { mutableStateOf("") }
  val shown = groups(query)
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    SearchField(query, placeholder, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth())
    if (shown.isEmpty()) {
      Empty("Nothing matches")
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
      for ((group, entries) in shown) {
        if (group != null) {
          item(key = "group:$group") {
            Text(
              group.uppercase(),
              fontSize = 9.sp,
              fontWeight = FontWeight.Bold,
              color = VdtColors.DarkGray,
              modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
          }
        }
        items(entries, key = key) { entry ->
          val current = isCurrent(entry)
          Row(
            Modifier
              .fillMaxWidth()
              .clickable(role = Role.Button) { onPick(entry) }
              .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Box(
              Modifier
                .size(width = 3.dp, height = 16.dp)
                .background(if (current) VdtColors.TextDark else VdtColors.Panel),
            )
            Text(
              label(entry),
              fontSize = 13.sp,
              fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
              color = VdtColors.TextDark,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

/** [fillTypes] narrowed by [query], on the localized title or the engine name, in the mod's (title) order. */
internal fun fillTypeMatches(fillTypes: List<AutoDriveFillType>, query: String): List<AutoDriveFillType> {
  val q = query.trim().lowercase()
  return if (q.isEmpty()) fillTypes else fillTypes.filter { q in it.title.lowercase() || q in it.name.lowercase() }
}

/**
 * [destinations] narrowed by [query] (name or folder, case-insensitive) and grouped by folder —
 * unfiled (null) first, then the folders alphabetically, each sorted by name.
 */
internal fun destinationGroups(
  destinations: List<AutoDriveDestination>,
  query: String,
): List<Pair<String?, List<AutoDriveDestination>>> {
  val q = query.trim().lowercase()
  return destinations
    .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.folder?.lowercase()?.contains(q) == true }
    .groupBy { it.folder }
    .map { (folder, entries) -> folder to entries.sortedBy { it.name.lowercase() } }
    .sortedWith(compareBy({ it.first != null }, { it.first?.lowercase() }))
}

/** Seconds as AutoDrive's HUD prints them: `m:ss`, or `Ns` under a minute. */
internal fun formatDriveTime(seconds: Int): String {
  val minutes = seconds / 60
  val rest = seconds % 60
  return if (minutes > 0) "$minutes:${rest.toString().padStart(2, '0')}" else "${rest}s"
}
