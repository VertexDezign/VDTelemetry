package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.TaskInput
import net.vertexdezign.vdt.app.components.FilterChip
import net.vertexdezign.vdt.model.Task

private const val MAX_DETAIL = 45 // matches the mod's Task.MAX_DETAIL_LENGTH

// recurMode indices (Task.RECUR_MODE): 0 Once, 1 Monthly, 2 Daily, 3 Every N months, 4 Every N days.
private val RECUR_LABELS = listOf("Once", "Monthly", "Daily", "Every N months", "Every N days")
internal val MONTH_LABELS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun usesMonth(recurMode: Int) = recurMode == 0 || recurMode == 1 || recurMode == 3
private fun usesN(recurMode: Int) = recurMode == 3 || recurMode == 4

/**
 * The N values the mod's own wizard offers (`ManageTasksFrame:onAddEditTaskRequestN`): 1..12, and for
 * "Every N months" also 24 and 36. It is deliberately *not* a contiguous range — a 1..12 stepper here
 * would rewrite an existing 24- or 36-month task as 12 the moment you opened it for editing.
 */
private fun nOptions(recurMode: Int): List<Int> = if (recurMode == 3) N_MONTHS else N_DAYS

private val N_DAYS = (1..12).toList()
private val N_MONTHS = N_DAYS + listOf(24, 36)

/** period -> calendar month (1-12), the inverse of the mod's convertMonthNumberToPeriod (offset +2). */
internal fun periodToMonth(period: Int): Int {
  var m = period + 2
  if (m > 12) m -= 12
  return m.coerceIn(1, 12)
}

/**
 * The form's initial values for editing [task]: the same user-facing intent the create/edit command
 * carries, reconstructed from the stored task. Only Standard tasks are edited, so the month comes
 * from `period` (Once/Monthly) or `nextN` (Every N months); the daily modes don't use it.
 */
fun editInitial(task: Task): TaskInput = TaskInput(
  detail = task.detail,
  priority = task.priority.coerceIn(1, 10),
  effort = task.effort.coerceIn(1, 5),
  recurMode = task.recurMode.coerceIn(0, 4),
  // No upper clamp: N is whatever the task actually carries (24 and 36 are legal, and a task made
  // elsewhere could hold any value). Editing must never silently rewrite it — see nOptions.
  n = task.n.coerceAtLeast(1),
  month = if (task.recurMode == 3) periodToMonth(task.nextN) else periodToMonth(task.period),
)

/**
 * The offsets the "from now" chips offer, in months.
 *
 * Short, because the point is the near future you would otherwise have to count out on your fingers.
 * Six is there for the one case that is genuinely half a year away — liming.
 */
private val MONTH_OFFSETS = listOf(0, 1, 2, 3, 6)

/**
 * "Now + [offset] months" as a calendar month, from the current game [period].
 *
 * The arithmetic is a fixed shift, which is what makes this cheap: [periodToMonth] is `period + 2`
 * wrapped, so an offset in months is the *same integer* as an offset in periods. The period is
 * wrapped before the conversion rather than after, so the sum stays in range whatever the offset.
 */
internal fun monthFromNow(period: Int, offset: Int): Int = periodToMonth(((period - 1 + offset) % 12 + 12) % 12 + 1)

/**
 * Create/edit dialog for a Standard task. Edits a working copy of [initial] and hands the result to
 * [onSave]; the caller turns it into a CreateTask/EditTask command. Fields that don't apply to the
 * chosen recurrence (month for the daily modes, N for the non-N modes) are hidden.
 *
 * [todayPeriod] is the current game period, from the crop calendar. When it is there the month gets a
 * row of "from now" chips above the absolute dropdown — a fast path for the thing crop care is
 * actually planned in ("lime this one in three months") without the reader working out which month
 * that is. When it is absent the chips are hidden rather than guessed at: a "+3 months" from an
 * unknown *now* is worse than no shortcut. The dropdown is always there underneath either way.
 *
 * Note this is not gated on the calendar's growth mode. That mode decides whether the crop *windows*
 * mean anything; a task's period is plain game time and is well defined in every mode.
 */
@Composable
fun TaskFormDialog(
  title: String,
  initial: TaskInput,
  onSave: (TaskInput) -> Unit,
  onDismiss: () -> Unit,
  todayPeriod: Int? = null,
) {
  var detail by remember { mutableStateOf(initial.detail) }
  var priority by remember { mutableIntStateOf(initial.priority) }
  var effort by remember { mutableIntStateOf(initial.effort) }
  var recurMode by remember { mutableIntStateOf(initial.recurMode) }
  var n by remember { mutableIntStateOf(initial.n) }
  var month by remember { mutableIntStateOf(initial.month) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    confirmButton = {
      TextButton(
        enabled = detail.isNotBlank(),
        onClick = { onSave(TaskInput(detail.trim(), priority, effort, recurMode, n, month)) },
      ) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    text = {
      Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = detail,
          onValueChange = { if (it.length <= MAX_DETAIL) detail = it },
          label = { Text("Detail") },
          singleLine = true,
          supportingText = { Text("${detail.length}/$MAX_DETAIL") },
          modifier = Modifier.fillMaxWidth(),
        )
        Stepper("Priority", priority, 1..10) { priority = it }
        Stepper("Effort", effort, 1..5) { effort = it }
        DropdownField("Repeat", RECUR_LABELS, recurMode) { recurMode = it }
        if (usesMonth(recurMode)) {
          if (todayPeriod != null) MonthOffsets(todayPeriod, month) { month = it }
          DropdownField(if (recurMode == 3) "Start month" else "Month", MONTH_LABELS, month - 1) { month = it + 1 }
        }
        if (usesN(recurMode)) {
          // Union with the current n so a value outside the mod's vocabulary (a task authored by some
          // other tool) stays selectable instead of being truncated away on save.
          val options = (nOptions(recurMode) + n).distinct().sorted()
          DropdownField(
            if (recurMode == 3) "Every N months" else "Every N days",
            options.map { it.toString() },
            options.indexOf(n),
          ) { n = options[it] }
        }
      }
    },
  )
}

/** Label on the left, the selected option + a menu on the right — for enumerated values (month,
 *  recurrence) where a stepper would mean clicking through the whole list. */
@Composable
private fun DropdownField(label: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, fontSize = 13.sp)
    Box {
      Row(
        Modifier.clickable { expanded = true }.widthIn(min = 120.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(options.getOrElse(selectedIndex) { "" }, fontSize = 13.sp)
        Icon(Icons.Filled.ArrowDropDown, "select $label")
      }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEachIndexed { index, option ->
          DropdownMenuItem(
            text = { Text(option) },
            onClick = {
              onSelect(index)
              expanded = false
            },
          )
        }
      }
    }
  }
}

/** Label on the left, [-] value [+] on the right. Clamps to [range]. */
@Composable
private fun Stepper(
  label: String,
  value: Int,
  range: IntRange,
  display: (Int) -> String = { it.toString() },
  onChange: (Int) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, fontSize = 13.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(enabled = value > range.first, onClick = { onChange((value - 1).coerceIn(range)) }) {
        Icon(Icons.Filled.Remove, "decrease $label")
      }
      Text(display(value), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 96.dp))
      IconButton(enabled = value < range.last, onClick = { onChange((value + 1).coerceIn(range)) }) {
        Icon(Icons.Filled.Add, "increase $label")
      }
    }
  }
}

/**
 * The "from now" chips, each echoing the month it resolves to.
 *
 * The resolved month is shown beside the offset because the task list itself only ever displays the
 * absolute month — an offset that isn't echoed back is a number the reader has to trust.
 */
@Composable
private fun MonthOffsets(todayPeriod: Int, month: Int, onPick: (Int) -> Unit) {
  FlowRow(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    MONTH_OFFSETS.forEach { offset ->
      val resolved = monthFromNow(todayPeriod, offset)
      val label = if (offset == 0) "Now · ${MONTH_LABELS[resolved - 1]}" else "+$offset · ${MONTH_LABELS[resolved - 1]}"
      FilterChip(label, month == resolved, { onPick(resolved) })
    }
  }
}
