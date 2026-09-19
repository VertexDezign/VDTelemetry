package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Construction
import net.vertexdezign.vdt.model.ConstructionPart

// The pieces both master/detail panels need for a CONSTRUCTION — a Pumps & Hoses biogas plant, whose
// parts arrive split across the production and the storage channel and have to read as one building.
// Shared here for the same reason ProductionStorageParts.kt exists: two panels, one vocabulary.
//
// A plant reaches the app as three things: a `Construction` on the production channel (the name, and
// the parts that are not production points), a `construction` ref on each of its two production points
// (the fermenters merged into one, the cogeneration units into another), and a `construction` ref on
// its digestate tank over on the storage channel. The id on every one of those is the same.

/**
 * What to call a part role in a sentence a player recognises — plural throughout, because one row
 * stands for every machine of that role however many there are (the game merges them, and so do we).
 *
 * "Silos" is deliberately not the word: this app already uses it for the farm's grain silos, and the
 * only silo a plant ever contains is its digestate store.
 */
internal fun roleLabel(role: String): String = when (role) {
  "BUNKER" -> "Bunkers"
  "FERMENTER" -> "Fermenters"
  "POWERPLANT" -> "Cogeneration"
  "SILO" -> "Digestate tanks"
  "TORCH" -> "Gas torch"
  else -> role.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * How a utilization reading is doing, in a word — the game's own five bands, which it colours and we
 * spell out. A band must survive being read in greyscale, so the word carries it and the bar is only
 * ever the same blue (see the design rules in VDTerminal/README.md).
 *
 * Thresholds are the DLC's (InGameMenuProductionFrame.populateCellForItemInSection): above 100 it is
 * running past the rate its inputs sustain, from 75 it is running as intended, above 25 it is merely
 * running. The decompiled source nests those tests in a way that leaves the middle band unreachable —
 * a flattened if/elseif chain, not a rule — so this is the chain it was written as.
 *
 * The **torch** gets its own two words, because the same numbers mean the opposite thing there: it
 * burns the surplus a plant cannot use, so its reading is already the cogeneration figure minus 100,
 * and a torch reading zero is a plant wasting nothing. The DLC agrees — an idle torch is the one case
 * it reports as running *perfectly*. Calling that "stopped" would flag the healthy state as a fault,
 * which is the first thing a real capture showed: a working plant, torch at 0.
 */
internal fun utilizationWord(percent: Int, role: String = ""): String = when {
  role == "TORCH" -> if (percent > 0) "burning" else "idle"
  percent > 100 -> "at limit"
  percent >= 75 -> "optimal"
  percent > 25 -> "ok"
  percent > 0 -> "poor"
  else -> "stopped"
}

/**
 * A construction's parts as a stack of bars — the plant behind the production point being looked at.
 * Each row is one part type: the DLC's utilization reading where it has one, and what the part is
 * holding where it holds something.
 */
@Composable
internal fun ConstructionCard(construction: Construction) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.6f))
      .padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        construction.name,
        color = VdtColors.TextDark,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text("PLANT", color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
    construction.parts.forEach { part -> ConstructionPartRows(part) }
  }
}

@Composable
private fun ConstructionPartRows(part: ConstructionPart) {
  val heading = if (part.count > 1) "${roleLabel(part.role)} (${part.count})" else roleLabel(part.role)
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(heading.uppercase(), color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    // The digestate tank is the one role the DLC never gave a utilization: it answers a flat 0, which
    // its own panel prints as "Silos: 0%". Its fill bars say the same thing and say it correctly, so
    // the bogus reading is dropped rather than drawn.
    val percent = part.utilization?.takeUnless { part.role == "SILO" }
    if (percent != null) {
      ProgressBar(
        fraction = percent / 100f,
        leftLabel = "Utilization",
        rightLabel = "$percent% · ${utilizationWord(percent, part.role)}",
      )
    }
    part.fills.forEach { fill ->
      ProgressBar(
        fraction = if (fill.capacity > 0) fill.level.toFloat() / fill.capacity.toFloat() else 0f,
        leftLabel = fill.title,
        rightLabel = "${formatInt(fill.level)} / ${formatInt(fill.capacity)} L",
      )
    }
    if (percent == null && part.fills.isEmpty()) {
      Text("No reading", color = VdtColors.DarkGray, fontSize = 10.sp)
    }
  }
}

/** A master-list section header — the plant every row beneath it belongs to. */
@Composable
internal fun GroupHeader(name: String) {
  Text(
    name.uppercase(),
    color = VdtColors.DarkGray,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 6.dp, bottom = 2.dp),
  )
}
