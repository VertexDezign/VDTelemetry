package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.UnfoldMore
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.ControlTarget
import net.vertexdezign.vdt.app.components.FillUnitsDisplay
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.StatusColor
import net.vertexdezign.vdt.app.components.StatusIconButton
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.FillUnit
import net.vertexdezign.vdt.model.FoldableState
import net.vertexdezign.vdt.model.Implement
import net.vertexdezign.vdt.model.Vehicle
import kotlin.math.roundToInt

private val Green600 = Color(0xFF16A34A)
private val Gray300 = Color(0xFFD1D5DB)
private val Gray400 = Color(0xFF9CA3AF)

private fun findImplement(list: List<Implement>, pos: String): Implement? {
  for (imp in list) {
    if (imp.position == pos) return imp
    findImplement(imp.implement, pos)?.let { return it }
  }
  return null
}

private fun collectFillUnits(imp: Implement?): List<FillUnit> {
  if (imp == null) return emptyList()
  val units = mutableListOf<FillUnit>()
  imp.fillUnits?.fillUnit?.let { units += it }
  for (child in imp.implement) units += collectFillUnits(child)
  return units
}

private fun mergeFillUnits(units: List<FillUnit>): List<FillUnit> {
  val groups = LinkedHashMap<String, MutableList<FillUnit>>()
  for (u in units) {
    groups
      .getOrPut(
        u.type?.ifBlank { null } ?: u.title.ifBlank { "Unknown" },
      ) { mutableListOf() }
      .add(u)
  }
  return groups.values.map { g ->
    val first = g.first()
    first.copy(
      value = g.sumOf { it.value },
      fillLevelPercentage = (g.sumOf { it.fillLevelPercentage }.toDouble() / g.size).roundToInt(),
    )
  }
}

/** Implements panel with front/rear columns and a merged/separate fill-unit toggle. */
@Composable
fun Implements(vehicle: Vehicle, modifier: Modifier = Modifier, onCommand: (ClientMessage) -> Unit = {}) {
  var merged by remember { mutableStateOf(false) }
  val front = findImplement(vehicle.implement, "FRONT")
  val back = findImplement(vehicle.implement, "BACK")

  Panel(
    title = "Implements",
    icon = Icons.Filled.Anchor,
    modifier = modifier,
    headerActions = {
      Icon(
        if (merged) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.Layers,
        contentDescription = "toggle merge",
        tint = VdtColors.DarkGray,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { merged = !merged }.padding(2.dp),
      )
    },
  ) {
    Row(Modifier.fillMaxHeight().fillMaxWidth()) {
      ImplementColumn(
        "Front", front, merged, left = true, target = ControlTarget.FRONT,
        onCommand = onCommand, modifier = Modifier.weight(1f),
      )
      ImplementColumn(
        "Rear", back, merged, left = false, target = ControlTarget.BACK,
        onCommand = onCommand, modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun ImplementColumn(
  label: String,
  imp: Implement?,
  merged: Boolean,
  left: Boolean,
  target: ControlTarget,
  onCommand: (ClientMessage) -> Unit,
  modifier: Modifier = Modifier,
) {
  val attached = imp != null
  // The mod's old `combined.implement.front/back` was just the first front/back implement's own
  // aspect state — which is exactly `imp` here — so read status/damage straight off it.
  val damage = imp?.wearable?.damage ?: 0
  val fillUnits = collectFillUnits(imp).let { if (merged) mergeFillUnits(it) else it }
  val align = if (left) Alignment.Start else Alignment.End

  Column(
    modifier
      .fillMaxHeight()
      .then(if (left) Modifier.border(width = 0.dp, color = Color.Transparent) else Modifier)
      .padding(horizontal = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    horizontalAlignment = align,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
      Icon(Icons.Filled.Link, null, tint = if (attached) Green600 else Gray400, modifier = Modifier.height(16.dp))
    }
    // heightIn(min) — not a fixed height — so two lines can never be clipped by the box on
    // devices whose font metrics make the name+type stack taller than the minimum. Line heights
    // are tightened (the old React panel used `leading-tight`) so it normally fits at 34dp.
    Box(
      Modifier
        .fillMaxWidth()
        .heightIn(
          min = 34.dp,
        ).clip(
          RoundedCornerShape(4.dp),
        ).background(VdtColors.White)
        .border(1.dp, Gray300, RoundedCornerShape(4.dp))
        .padding(vertical = 2.dp),
      contentAlignment = Alignment.Center,
    ) {
      if (attached) {
        // Name plus (optional) type. The type is only rendered when present, so an implement
        // without a type shows a single, vertically-centred name instead of a name with an
        // empty line reserved below it.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            imp.name,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            color = VdtColors.TextDark,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (imp.type.isNotBlank()) {
            Text(
              imp.type,
              fontSize = 8.sp,
              lineHeight = 10.sp,
              color = Gray400,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      } else {
        Text("No Implement", fontSize = 10.sp, color = Gray300)
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Icon(Icons.Filled.Build, null, tint = VdtColors.DarkGray, modifier = Modifier.height(14.dp))
      Text("${100 - damage}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = VdtColors.DarkGray)
    }
    // Each control is clickable only when the implement has that aspect; the tap sends the ABSOLUTE
    // target for this column's front/back position, computed from the rendered state (idempotent
    // over the lossy command channel — see ClientMessage). Front/back are routed mod-side through
    // FS25_additionalInputs.
    val foldable = imp?.foldable
    StatusIconButton(
      Icons.Filled.UnfoldMore,
      active = foldable != null,
      color =
        if (foldable ==
          FoldableState.EXTENDED
        ) {
          StatusColor.Green
        } else {
          StatusColor.White
        },
      onClick =
        foldable?.let {
          { onCommand(ClientMessage.SetFolded(target, on = it != FoldableState.EXTENDED)) }
        },
    )
    StatusIconButton(
      Icons.Filled.PowerSettingsNew,
      active = imp?.isTurnedOn == true,
      color = StatusColor.Green,
      onClick =
        imp?.isTurnedOn?.let {
          { onCommand(ClientMessage.SetActivated(target, on = !it)) }
        },
    )
    StatusIconButton(
      if (imp?.lowered ==
        true
      ) {
        Icons.Filled.ArrowDownward
      } else {
        Icons.Filled.ArrowUpward
      },
      active = imp?.lowered == true,
      color = StatusColor.Green,
      onClick =
        imp?.lowered?.let {
          { onCommand(ClientMessage.SetLowered(target, on = !it)) }
        },
    )
    if (attached) FillUnitsDisplay(fillUnits, Modifier.fillMaxWidth(), spacing = 4)
  }
}
