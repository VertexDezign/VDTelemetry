package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Pets
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.ListDetail
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.components.ProgressBar
import net.vertexdezign.vdt.app.i18n.LocalStrings
import net.vertexdezign.vdt.app.i18n.Strings
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.HusbandriesData
import net.vertexdezign.vdt.model.Husbandry
import net.vertexdezign.vdt.model.HusbandryAnimalGroup
import net.vertexdezign.vdt.model.HusbandryCondition
import kotlin.math.roundToInt

/**
 * The Animals app full page: a master/detail over the own-farm [HusbandriesData] channel. The left
 * column lists owned pens; selecting one shows its detail — productivity, animal count, the condition
 * bars (food/water/straw/outputs/cleanliness) and the per-group animal breakdown as a sortable table.
 *
 * Read-only. A null [data] means the channel is absent (export off / no data yet) — distinct from an
 * owned-nothing farm, which shows the empty state.
 */
@Composable
fun AnimalsPanel(data: HusbandriesData?, modifier: Modifier = Modifier) {
  val strings = LocalStrings.current
  Panel(title = strings.apps.animals, icon = Icons.Filled.Pets, modifier = modifier) {
    when {
      data == null -> Centered(strings.animals.waiting)
      data.husbandries.isEmpty() -> Centered(strings.animals.empty)
      else -> AnimalsMasterDetail(data)
    }
  }
}

@Composable
private fun AnimalsMasterDetail(data: HusbandriesData) {
  val s = LocalStrings.current.animals
  var selectedId by remember { mutableStateOf<String?>(null) }
  val ids = remember(data) { data.husbandries.map { it.id } }
  val currentId = selectedId.takeIf { it in ids } ?: ids.firstOrNull()
  // Hoisted above the detail so a chosen sort outlives switching pens (and a pen with no animals).
  var sort by remember { mutableStateOf<AnimalSort?>(null) }

  var detailOpen by remember { mutableStateOf(false) }
  ListDetail(
    listWidth = 240.dp,
    detailOpen = detailOpen,
    backLabel = s.listTitle,
    onBack = { detailOpen = false },
    list = { listModifier ->
      Column(
        listModifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        data.husbandries.forEach { pen ->
          PenRow(
            name = pen.name,
            subtitle = s.penSubtitle(pen.numAnimals, pen.maxNumAnimals),
            selected = pen.id == currentId,
            onClick = {
              selectedId = pen.id
              detailOpen = true
            },
          )
        }
      }
    },
  ) {
    val pen = data.husbandries.firstOrNull { it.id == currentId }
    if (pen != null) {
      HusbandryDetail(pen, sort, onSort = { sort = nextSort(sort, it) })
    } else {
      Centered(s.selectPrompt)
    }
  }
}

@Composable
private fun PenRow(name: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  val bg = if (selected) VdtColors.Green else VdtColors.TrackGray
  val fg = if (selected) VdtColors.OnFill else VdtColors.TextDark
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
    Text(subtitle, color = if (selected) VdtColors.OnFill.copy(alpha = 0.85f) else VdtColors.DarkGray, fontSize = 10.sp)
  }
}

@Composable
private fun HusbandryDetail(pen: Husbandry, sort: AnimalSort?, onSort: (AnimalColumn) -> Unit) {
  val s = LocalStrings.current.animals
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(pen.name, color = VdtColors.TextDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)

    pen.productivity?.let { productivity ->
      ProgressBar(
        fraction = productivity,
        leftLabel = s.productivity,
        rightLabel = "${(productivity * 100).roundToInt()}%",
      )
    }
    val animalFraction = if (pen.maxNumAnimals > 0) pen.numAnimals.toFloat() / pen.maxNumAnimals.toFloat() else 0f
    ProgressBar(
      fraction = animalFraction,
      leftLabel = s.animals,
      rightLabel = "${pen.numAnimals} / ${pen.maxNumAnimals}",
    )

    if (pen.food.isNotEmpty()) {
      SectionLabel(s.food)
      pen.food.forEach { ConditionBar(it) }
    }

    if (pen.conditions.isNotEmpty()) {
      SectionLabel(s.conditions)
      pen.conditions.forEach { ConditionBar(it) }
    }

    if (pen.animals.isNotEmpty()) {
      SectionLabel(s.animals)
      AnimalTable(pen.animals, sort, onSort)
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(text.uppercase(), color = VdtColors.DarkGray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun ConditionBar(condition: HusbandryCondition) {
  val strings = LocalStrings.current
  val numbers = strings.numbers
  // Actual liters rather than a bare % — with the capacity when known (food groups carry one).
  val right =
    if (condition.capacity > 0) {
      strings.common.litres(numbers.integer(condition.value), numbers.integer(condition.capacity))
    } else {
      strings.common.litre(numbers.integer(condition.value))
    }
  ProgressBar(fraction = condition.ratio, leftLabel = condition.title, rightLabel = right)
}

// ---- Animal group table --------------------------------------------------------------------------

/**
 * One column of the animal table. [width] is a fixed width for the figures — so their digits line up
 * down the column and against the header — and null for the name, which takes the remaining slack.
 */
private enum class AnimalColumn(val width: Dp?) {
  NAME(null),
  COUNT(60.dp),
  AGE(62.dp),
  HEALTH(70.dp),
  REPRO(70.dp),
  ;

  fun label(strings: Strings): String = when (this) {
    NAME -> strings.animals.columnAnimal
    COUNT -> strings.animals.columnCount
    AGE -> strings.animals.columnAge
    HEALTH -> strings.animals.columnHealth
    REPRO -> strings.animals.columnRepro
  }

  /** Figures are end-aligned; the name is not. */
  val numeric: Boolean get() = this != NAME

  fun cellText(strings: Strings, group: HusbandryAnimalGroup): String = when (this) {
    NAME -> group.name

    COUNT -> group.count.toString()

    AGE -> strings.common.monthsShort(group.age)

    HEALTH -> "${group.health}%"

    // Non-breeders have no reproduction figure at all — an em dash, not a misleading 0%.
    REPRO -> if (group.supportsReproduction) "${group.reproduction}%" else "—"
  }
}

/** The active sort. Null means the game's own cluster order, which is how the table starts. */
private data class AnimalSort(val column: AnimalColumn, val descending: Boolean)

/**
 * Clicking a header sorts by that column; clicking the active one flips the direction. A column is
 * first sorted the way you actually want to read it — biggest count/age/health first, names A→Z.
 */
private fun nextSort(current: AnimalSort?, column: AnimalColumn): AnimalSort = if (current?.column == column) {
  current.copy(descending = !current.descending)
} else {
  AnimalSort(column, descending = column.numeric)
}

private fun comparatorFor(sort: AnimalSort): Comparator<HusbandryAnimalGroup> {
  val ascending: Comparator<HusbandryAnimalGroup> =
    when (sort.column) {
      AnimalColumn.NAME -> compareBy { it.name.lowercase() }

      AnimalColumn.COUNT -> compareBy { it.count }

      AnimalColumn.AGE -> compareBy { it.age }

      AnimalColumn.HEALTH -> compareBy { it.health }

      // Non-breeders rank below every real percentage, so they collect at the far end either way.
      AnimalColumn.REPRO -> compareBy { if (it.supportsReproduction) it.reproduction else -1 }
    }
  return if (sort.descending) ascending.reversed() else ascending
}

/**
 * The per-group breakdown as a table: one row per cluster (breed + age), sortable by any column.
 * Sorting is what the table is for — a full barn is a dozen-odd clusters, and finding the oldest herd
 * or the one whose health has slipped means ordering them, not reading every card.
 *
 * [sortedWith] is stable, so ties keep the game's own order rather than shuffling between refreshes.
 */
@Composable
private fun AnimalTable(groups: List<HusbandryAnimalGroup>, sort: AnimalSort?, onSort: (AnimalColumn) -> Unit) {
  val strings = LocalStrings.current
  val rows = remember(groups, sort) { sort?.let { groups.sortedWith(comparatorFor(it)) } ?: groups }
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))) {
    AnimalTableHeader(sort, onSort)
    rows.forEachIndexed { index, group ->
      Row(
        Modifier
          .fillMaxWidth()
          .background(if (index % 2 == 0) VdtColors.Surface.copy(alpha = 0.6f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        AnimalColumn.entries.forEach { column ->
          Text(
            column.cellText(strings, group),
            color = if (column == AnimalColumn.NAME) VdtColors.TextDark else VdtColors.DarkGray,
            fontSize = 12.sp,
            fontWeight = if (column == AnimalColumn.NAME) FontWeight.SemiBold else FontWeight.Bold,
            textAlign = if (column.numeric) TextAlign.End else TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = cellModifier(column).padding(horizontal = 6.dp, vertical = 7.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun AnimalTableHeader(sort: AnimalSort?, onSort: (AnimalColumn) -> Unit) {
  val strings = LocalStrings.current
  Row(Modifier.fillMaxWidth().background(VdtColors.TrackGray), verticalAlignment = Alignment.CenterVertically) {
    AnimalColumn.entries.forEach { column ->
      val active = sort?.column == column
      val descending = active && sort.descending
      Row(
        cellModifier(column)
          // A header is a button that reports where the sort currently sits; the arrow beside it is
          // the same fact drawn, so it stays decorative rather than being announced a second time
          // (`clickable` merges its descendants into this one node).
          .clickable(role = Role.Button) { onSort(column) }
          .semantics {
            stateDescription = when {
              !active -> strings.common.notSorted
              descending -> strings.common.sortedDescending
              else -> strings.common.sortedAscending
            }
          }
          .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = if (column.numeric) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // The arrow leads on the end-aligned figure columns so the label keeps the same right edge as
        // the values below it, and its slot is reserved even when inactive so nothing shifts on click.
        if (column.numeric) SortArrow(active, descending)
        Text(
          column.label(strings).uppercase(),
          color = if (active) VdtColors.TextDark else VdtColors.DarkGray,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (!column.numeric) SortArrow(active, descending)
      }
    }
  }
}

@Composable
private fun SortArrow(active: Boolean, descending: Boolean) {
  Box(Modifier.size(12.dp)) {
    if (active) {
      Icon(
        if (descending) Icons.Filled.ArrowDropDown else Icons.Filled.ArrowDropUp,
        // Decorative: the header cell carries the sort state as its stateDescription.
        contentDescription = null,
        tint = VdtColors.TextDark,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

private fun RowScope.cellModifier(column: AnimalColumn): Modifier =
  column.width?.let { Modifier.width(it) } ?: Modifier.weight(1f)
