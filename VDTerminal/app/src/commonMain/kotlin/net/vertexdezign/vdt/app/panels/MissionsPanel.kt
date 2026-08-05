package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.ConfirmDialog
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Mission
import net.vertexdezign.vdt.model.MissionDetail
import net.vertexdezign.vdt.model.MissionFinishState
import net.vertexdezign.vdt.model.MissionLimit
import net.vertexdezign.vdt.model.MissionsData
import kotlin.math.roundToInt

/**
 * The Missions app full page: a master/detail over the [MissionsData] channel, split the way the
 * game's own contracts screen is — what is on offer above, what this farm has taken on below.
 * Selecting a contract shows its terms, the game's own detail rows, and the actions this player is
 * allowed: accept (with or without leased equipment), give up, collect.
 *
 * A null [data] means the channel is absent (export off / no data yet), which is distinct from a farm
 * with no contracts on offer — the two say different things and get different empty states.
 *
 * Nothing here knows what a harvest mission is. The rows come from the mod as the game formatted
 * them, and what the panel decides for itself is keyed off [Mission.status] and the presence of a
 * field id, never off [Mission.type] — mods register their own mission types.
 */
@Composable
fun MissionsPanel(
  data: MissionsData?,
  modifier: Modifier = Modifier,
  selectedId: Int? = null,
  onSelect: (Int) -> Unit = {},
  onCommand: (ClientMessage) -> Unit = {},
) {
  var pendingCancel by remember { mutableStateOf<Mission?>(null) }

  Panel(title = "Contracts", icon = Icons.AutoMirrored.Filled.Assignment, modifier = modifier) {
    when {
      data == null -> Centered("Waiting for contract data…")

      data.missions.isEmpty() -> Centered("No contracts on offer")

      else -> {
        // The caller may drive the selection (the map does); otherwise the panel keeps its own.
        var ownSelection by remember { mutableStateOf<Int?>(null) }
        // A board runs to a couple of dozen contracts, most of which you are not shopping for.
        // Filtering by type is the one cut that always makes sense; a type that vanishes from the
        // board takes its filter with it (the `in kinds` check), rather than emptying the list.
        var typeFilter by remember { mutableStateOf<String?>(null) }
        val kinds = remember(data) { missionKinds(data.missions) }
        val activeFilter = typeFilter?.takeIf { filter -> kinds.any { it.type == filter } }
        val shown = remember(data, activeFilter) {
          data.missions.filter {
            activeFilter == null ||
              it.type == activeFilter
          }
        }

        val ids = shown.map { it.id }
        val currentId = (selectedId ?: ownSelection)?.takeIf { it in ids } ?: ids.first()
        val current = shown.first { it.id == currentId }

        Row(Modifier.fillMaxSize()) {
          MissionList(
            missions = shown,
            limit = data.limit,
            kinds = kinds,
            activeFilter = activeFilter,
            onFilter = { typeFilter = if (it == activeFilter) null else it },
            currentId = currentId,
            onSelect = {
              ownSelection = it
              onSelect(it)
            },
          )
          Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.PanelBorder))
          Box(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp)) {
            MissionDetailView(
              mission = current,
              canManage = data.canManage,
              limitReached = data.limit?.isReached == true,
              onAccept = { lease -> onCommand(ClientMessage.AcceptMission(current.id, lease)) },
              onCollect = { onCommand(ClientMessage.DismissMission(current.id)) },
              onCancel = { pendingCancel = current },
            )
          }
        }
      }
    }

    // Giving up a contract forfeits it, which is why the game asks too.
    pendingCancel?.let { mission ->
      ConfirmDialog(
        title = "Give up contract?",
        message = "\"${mission.title}\" ${mission.location} will be cancelled and pays nothing.",
        confirmLabel = "Give up",
        onConfirm = {
          onCommand(ClientMessage.CancelMission(mission.id))
          pendingCancel = null
        },
        onDismiss = { pendingCancel = null },
      )
    }
  }
}

@Composable
private fun MissionList(
  missions: List<Mission>,
  limit: MissionLimit?,
  kinds: List<MissionKind>,
  activeFilter: String?,
  onFilter: (String) -> Unit,
  currentId: Int,
  onSelect: (Int) -> Unit,
) {
  // The game's own two sections: on offer, and everything this farm has taken on (running or done).
  val offered = missions.filter { it.isOffered }
  val taken = missions.filterNot { it.isOffered }

  Column(
    Modifier.width(250.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 10.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (limit != null) {
      Text(
        "${limit.active} / ${limit.max} ACTIVE",
        color = if (limit.isReached) VdtColors.Amber else VdtColors.Gray,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
      )
    }
    // The chips carry the game's own name for each kind of work ("Ernten", "Ballen pressen"), taken
    // off the contracts themselves — this module never spells out a mission type.
    if (kinds.size > 1) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (kind in kinds) {
          TypeChip(
            label = kind.label,
            count = kind.count,
            selected = kind.type == activeFilter,
            onClick = { onFilter(kind.type) },
          )
        }
      }
    }
    if (taken.isNotEmpty()) {
      ListLabel("Yours")
      taken.forEach { MissionRow(it, it.id == currentId) { onSelect(it.id) } }
    }
    if (offered.isNotEmpty()) {
      ListLabel("On offer")
      offered.forEach { MissionRow(it, it.id == currentId) { onSelect(it.id) } }
    }
  }
}

/** One kind of work on the board: the type token, the game's name for it, and how many there are. */
internal data class MissionKind(val type: String, val label: String, val count: Int)

/**
 * The kinds of work present on a board, most-offered first. The label is the contracts' own [title],
 * which is the game's localized name for that kind of work — so the filter reads in the player's
 * language without this module knowing a single mission type.
 */
internal fun missionKinds(missions: List<Mission>): List<MissionKind> = missions
  .groupBy { it.type }
  .map { (type, ms) -> MissionKind(type, ms.first().title.ifBlank { type }, ms.size) }
  .sortedWith(compareByDescending<MissionKind> { it.count }.thenBy { it.label })

@Composable
private fun TypeChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
  Row(
    Modifier
      .clip(RoundedCornerShape(10.dp))
      .background(if (selected) VdtColors.Green else VdtColors.TrackGray)
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      label,
      color = if (selected) VdtColors.White else VdtColors.TextDark,
      fontSize = 10.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
    )
    Text(
      count.toString(),
      color = if (selected) VdtColors.White.copy(alpha = 0.85f) else VdtColors.Gray,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun ListLabel(text: String) {
  Text(
    text.uppercase(),
    color = VdtColors.Gray,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 4.dp),
  )
}

@Composable
private fun MissionRow(mission: Mission, selected: Boolean, onClick: () -> Unit) {
  val bg = if (selected) VdtColors.Green else VdtColors.TrackGray
  val fg = if (selected) VdtColors.White else VdtColors.TextDark
  val subFg = if (selected) VdtColors.White.copy(alpha = 0.85f) else VdtColors.Gray

  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(bg)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 8.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        mission.title,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      // A finished contract shows what it pays out, not what it was advertised at.
      Text(
        money(mission.totalReward ?: mission.reward),
        color = if (selected) VdtColors.White else VdtColors.DarkGray,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
      )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        rowSubject(mission),
        color = subFg,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Text(statusLine(mission), color = statusColor(mission, selected), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun MissionDetailView(
  mission: Mission,
  canManage: Boolean,
  limitReached: Boolean,
  onAccept: (Boolean) -> Unit,
  onCollect: () -> Unit,
  onCancel: () -> Unit,
) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(mission.title, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    mission.npc?.let { npc ->
      Text("${npc.name} · ${mission.location}", color = VdtColors.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
    if (mission.description.isNotEmpty()) {
      Text(mission.description, color = VdtColors.DarkGray, fontSize = 11.sp)
    }

    mission.completion?.let { completion ->
      ProgressBar(
        fraction = completion,
        leftLabel = mission.extraProgress.ifEmpty { "Progress" },
        rightLabel = "${(completion * 100).roundToInt()}%",
      )
    }

    // Only the number the game's own rows don't already carry. While a contract is on offer they
    // list its terms but not the reward, so that is ours to state; once it is finished they are the
    // reward breakdown, and the one thing missing from them is what collecting it actually pays —
    // which is not the advertised reward, and on a leased contract can be negative.
    if (mission.isFinished) {
      mission.totalReward?.let { KeyValue("Payout", money(it)) }
    } else {
      KeyValue("Reward", money(mission.reward))
    }
    mission.minutesLeft?.let { KeyValue("Time left", formatMinutes(it)) }

    // The game's own rows, rendered as given — including the lease cost, which it prints in the
    // player's own currency (ours is the raw engine value, and the two differ by its currency
    // offset, so printing both would show the same cost twice with different numbers).
    mission.details.forEach { DetailRow(it) }

    MissionActions(
      mission = mission,
      canManage = canManage,
      limitReached = limitReached,
      onAccept = onAccept,
      onCollect = onCollect,
      onCancel = onCancel,
    )
  }
}

@Composable
private fun MissionActions(
  mission: Mission,
  canManage: Boolean,
  limitReached: Boolean,
  onAccept: (Boolean) -> Unit,
  onCollect: () -> Unit,
  onCancel: () -> Unit,
) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (!canManage) {
      // Saying why beats three dead buttons: the right is granted per farmhand in the game's own menu.
      Text(
        "You may not manage this farm's contracts",
        color = VdtColors.Gray,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
      )
      return@Column
    }

    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      when {
        mission.isOffered -> {
          // At the cap the engine refuses the accept, so the button says so instead of firing.
          ActionButton("Accept", VdtColors.Green, enabled = !limitReached) { onAccept(false) }
          if (mission.leasable) {
            ActionButton("With equipment", VdtColors.ProgressBlue, enabled = !limitReached) { onAccept(true) }
          }
        }

        mission.isActive -> ActionButton("Give up", VdtColors.Red) { onCancel() }

        mission.isFinished -> ActionButton("Collect", VdtColors.Green) { onCollect() }
      }
    }
    if (mission.isOffered && limitReached) {
      Text("This farm is already running its maximum contracts", color = VdtColors.Amber, fontSize = 10.sp)
    }
  }
}

@Composable
private fun ActionButton(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
  val bg = if (enabled) color else VdtColors.TrackGray
  val fg = if (enabled) VdtColors.White else VdtColors.Gray
  Box(
    Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(bg)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 8.dp),
  ) {
    Text(label.uppercase(), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun DetailRow(detail: MissionDetail) = KeyValue(detail.title, detail.value)

@Composable
private fun KeyValue(key: String, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(key, color = VdtColors.Gray, fontSize = 11.sp)
    Text(
      value,
      color = VdtColors.TextDark,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
    )
  }
}

/**
 * The compact tile: what this farm is working on, with progress, and how much is waiting to be taken.
 * Deliberately not the master/detail page — a tile is glanced at while driving, so it answers "how
 * are my contracts doing" and leaves taking one on to the app.
 */
@Composable
fun MissionsSummary(data: MissionsData?, modifier: Modifier = Modifier) {
  Panel(title = "Contracts", icon = Icons.AutoMirrored.Filled.Assignment, modifier = modifier) {
    val active = data?.missions?.filter { it.isActive }.orEmpty()
    val finished = data?.missions?.count { it.isFinished } ?: 0
    val offered = data?.missions?.count { it.isOffered } ?: 0

    when {
      data == null -> Centered("Waiting for contract data…")

      active.isEmpty() && finished == 0 ->
        Centered(if (offered > 0) "$offered on offer" else "No contracts")

      else ->
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          active.forEach { mission ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // What the job is: its kind, and what it is for.
                Text(
                  mission.title,
                  color = VdtColors.TextDark,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f, fill = false),
                )
                if (mission.subtitle.isNotEmpty()) {
                  Text(
                    mission.subtitle,
                    color = VdtColors.DarkGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                  )
                }
              }
              ProgressBar(
                fraction = mission.completion ?: 0f,
                leftLabel = widgetProgressLabel(mission),
                rightLabel = mission.minutesLeft?.let { formatMinutes(it) } ?: statusLine(mission),
              )
            }
          }
          // Both are calls to action — one pays out now, the other is work available.
          if (finished > 0) {
            Text(
              "$finished READY TO COLLECT",
              color = VdtColors.Green,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
            )
          }
          if (offered > 0) {
            Text("$offered ON OFFER", color = VdtColors.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
          }
        }
    }
  }
}

/**
 * The tile's progress label: where the work is, plus the running commentary the game supplies for the
 * contracts that have one ("3 trees remaining") — on a tile that line is worth more than a percentage
 * the bar is already showing.
 */
internal fun widgetProgressLabel(mission: Mission): String {
  val where = mission.location.ifEmpty { mission.title }
  return if (mission.extraProgress.isEmpty()) where else "$where · ${mission.extraProgress}"
}

/**
 * A row's second line: where the contract is, and what it is for. The subject is the crop on a
 * harvest or sowing job and the bale form on a baling one — the mod resolves it from the mission's
 * own fields and hands it over already localized, so "Ernten" in the list is followed by "Hafer"
 * without this module knowing what a harvest contract is.
 */
internal fun rowSubject(mission: Mission): String {
  val where = mission.location.ifEmpty { mission.type }
  return if (mission.subtitle.isEmpty()) where else "$where · ${mission.subtitle}"
}

/** The one-line state for a row: how it ended, how far along it is, or how long it is on offer for. */
internal fun statusLine(mission: Mission): String {
  // Bound to locals rather than smart-cast: the model is a different module, so the compiler will
  // not narrow its nullable properties for us.
  val finish = mission.finishState
  val completion = mission.completion
  val minutesLeft = mission.minutesLeft
  return when {
    finish != null ->
      when (finish) {
        MissionFinishState.SUCCESS -> "Done"
        MissionFinishState.FAILED -> "Failed"
        MissionFinishState.TIMED_OUT -> "Timed out"
        MissionFinishState.CANCELED -> "Cancelled"
      }

    mission.isFinished -> "Done"

    completion != null && mission.isActive -> "${(completion * 100).roundToInt()}%"

    mission.isActive -> "Preparing"

    minutesLeft != null -> formatMinutes(minutesLeft)

    else -> ""
  }
}

internal fun statusColor(mission: Mission, selected: Boolean): Color = when {
  selected -> VdtColors.White

  mission.finishState == MissionFinishState.SUCCESS -> VdtColors.Green

  mission.finishState != null -> VdtColors.Red

  // Under an hour of game time left is the point at which a contract is worth hurrying for.
  mission.isOffered && (mission.minutesLeft ?: Int.MAX_VALUE) < 60 -> VdtColors.Amber

  else -> VdtColors.DarkGray
}

/** In-game minutes as the contract list prints them: days and hours, down to minutes near the end. */
internal fun formatMinutes(minutes: Int): String {
  if (minutes <= 0) return "expired"
  val days = minutes / 1440
  val hours = (minutes % 1440) / 60
  return when {
    days > 0 -> "${days}d ${hours}h"
    hours > 0 -> "${hours}h"
    else -> "${minutes}m"
  }
}

/** Whole currency units with thousands separators; the mod already rounded away the cents. */
internal fun money(value: Int): String {
  val negative = value < 0
  val digits = (if (negative) -value else value).toString()
  val sb = StringBuilder()
  val firstGroup = digits.length % 3
  if (firstGroup > 0) sb.append(digits, 0, firstGroup)
  var i = firstGroup
  while (i < digits.length) {
    if (sb.isNotEmpty()) sb.append(',')
    sb.append(digits, i, i + 3)
    i += 3
  }
  return (if (negative) "-" else "") + sb.toString()
}
