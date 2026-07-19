package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.HusbandriesData
import net.vertexdezign.vdt.model.Husbandry
import net.vertexdezign.vdt.model.HusbandryAnimalGroup
import net.vertexdezign.vdt.model.HusbandryCondition
import kotlin.math.roundToInt

/**
 * The Animals app full page: a master/detail over the own-farm [HusbandriesData] channel. The left
 * column lists owned pens; selecting one shows its detail — productivity, animal count, the condition
 * bars (food/water/straw/outputs/cleanliness) and the per-group animal breakdown.
 *
 * Read-only. A null [data] means the channel is absent (export off / no data yet) — distinct from an
 * owned-nothing farm, which shows the empty state.
 */
@Composable
fun AnimalsPanel(data: HusbandriesData?, modifier: Modifier = Modifier) {
  Panel(title = "Animals", icon = Icons.Filled.Pets, modifier = modifier) {
    when {
      data == null -> Centered("Waiting for animal data…")
      data.husbandries.isEmpty() -> Centered("No owned animals")
      else -> AnimalsMasterDetail(data)
    }
  }
}

@Composable
private fun AnimalsMasterDetail(data: HusbandriesData) {
  var selectedId by remember { mutableStateOf<String?>(null) }
  val ids = remember(data) { data.husbandries.map { it.id } }
  val currentId = selectedId.takeIf { it in ids } ?: ids.firstOrNull()

  Row(Modifier.fillMaxSize()) {
    Column(
      Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      data.husbandries.forEach { pen ->
        PenRow(
          name = pen.name,
          subtitle = "${pen.numAnimals} / ${pen.maxNumAnimals} animals",
          selected = pen.id == currentId,
          onClick = { selectedId = pen.id },
        )
      }
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(VdtColors.PanelBorder))
    Box(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp)) {
      val pen = data.husbandries.firstOrNull { it.id == currentId }
      if (pen != null) HusbandryDetail(pen) else Centered("Select a pen")
    }
  }
}

@Composable
private fun PenRow(name: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  val bg = if (selected) VdtColors.Green else VdtColors.TrackGray
  val fg = if (selected) VdtColors.White else VdtColors.TextDark
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(bg)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 8.dp),
  ) {
    Text(
      name,
      color = fg,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(subtitle, color = if (selected) VdtColors.White.copy(alpha = 0.85f) else VdtColors.Gray, fontSize = 10.sp)
  }
}

@Composable
private fun HusbandryDetail(pen: Husbandry) {
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(pen.name, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)

    ProgressBar(
      fraction = pen.productivity,
      leftLabel = "Productivity",
      rightLabel = "${(pen.productivity * 100).roundToInt()}%",
    )
    val animalFraction = if (pen.maxNumAnimals > 0) pen.numAnimals.toFloat() / pen.maxNumAnimals.toFloat() else 0f
    ProgressBar(
      fraction = animalFraction,
      leftLabel = "Animals",
      rightLabel = "${pen.numAnimals} / ${pen.maxNumAnimals}",
    )

    if (pen.food.isNotEmpty()) {
      SectionLabel("Food")
      pen.food.forEach { ConditionBar(it) }
    }

    if (pen.conditions.isNotEmpty()) {
      SectionLabel("Conditions")
      pen.conditions.forEach { ConditionBar(it) }
    }

    if (pen.animals.isNotEmpty()) {
      SectionLabel("Animals")
      pen.animals.forEach { AnimalGroupRow(it) }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(text.uppercase(), color = VdtColors.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun ConditionBar(condition: HusbandryCondition) {
  // Actual liters rather than a bare % — with the capacity when known (food groups carry one).
  val right =
    if (condition.capacity > 0) {
      "${fmtLiters(condition.value)} / ${fmtLiters(condition.capacity)} L"
    } else {
      "${fmtLiters(condition.value)} L"
    }
  ProgressBar(fraction = condition.ratio, leftLabel = condition.title, rightLabel = right)
}

/** Group a non-negative liter count with thousands separators (e.g. 145000 -> "145,000"). */
private fun fmtLiters(value: Int): String {
  val digits = value.toString()
  if (digits.length <= 3) return digits
  val sb = StringBuilder()
  val firstGroup = digits.length % 3
  if (firstGroup > 0) sb.append(digits, 0, firstGroup)
  var i = firstGroup
  while (i < digits.length) {
    if (sb.isNotEmpty()) sb.append(',')
    sb.append(digits, i, i + 3)
    i += 3
  }
  return sb.toString()
}

@Composable
private fun AnimalGroupRow(group: HusbandryAnimalGroup) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(VdtColors.White.copy(alpha = 0.6f))
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        group.name,
        color = VdtColors.TextDark,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      Text("×${group.count}", color = VdtColors.DarkGray, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    val repro = if (group.supportsReproduction) " · Repro ${group.reproduction}%" else ""
    Text(
      "Age ${group.age} mo · Health ${group.health}%$repro",
      color = VdtColors.Gray,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}
