package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.Task
import net.vertexdezign.vdt.model.TaskListData

/**
 * The join between FS25_TaskList and the fields, which exists only because the mod has no field
 * column: a task is a group, a detail string, a priority, an effort and a recurrence, and nothing
 * else. So the convention `F<number> - <Type> - <note>` in the detail is not a habit the user has to
 * keep — it is a **format this app writes and parses**.
 *
 * Lossy by construction: rename the detail in the game's own task UI and the link is gone. That is
 * the price of not owning a store, and it is the right price — the prefix costs nothing, survives
 * every edit made in-game, and is legible to a human reading the task list there. `FUTURE.md` ->
 * "VDT-owned data" holds the alternative, along with the constraint that makes it expensive (the FS25
 * Lua sandbox only opens files for *writing*, so anything the mod must read back has to be XML).
 */
private val FIELD_TASK = Regex("""^F(\d+)\s*-\s*([^-]+?)\s*(?:-\s*(.*))?$""")

/** The mod's own cap on a task detail (`Task.MAX_DETAIL_LENGTH`), which the composed string must fit. */
const val MAX_TASK_DETAIL = 45

/**
 * The work a field task can be about.
 *
 * A fixed vocabulary so the middle group of the format is total rather than free text — the app
 * *writes* these, and [of] reads anything, falling back to [OTHER] for a detail typed by hand in the
 * game's own UI. American spelling throughout, matching the game and the rest of this codebase
 * (`needsPlowing`, the growth plane's `plowed`).
 *
 * Fertilize, lime, weed and spray are in the vocabulary but are never *suggested*: under Precision
 * Farming the readings that would drive them are withheld by the mod or meaningless, so those tasks
 * are planned ahead by hand rather than triggered (see [fieldWork]).
 */
enum class FieldTaskType(val label: String) {
  SOW("Sow"),
  FERTILIZE("Fertilize"),
  LIME("Lime"),
  PLOW("Plow"),
  CULTIVATE("Cultivate"),
  ROLL("Roll"),
  HARVEST("Harvest"),
  WEED("Weed"),
  SPRAY("Spray"),
  MULCH("Mulch"),
  STONES("Stones"),

  /** A type this app didn't write. Kept as its own value so a hand-typed task still attaches. */
  OTHER("Other"),
  ;

  companion object {
    /** Match a written type, case-insensitively; anything unrecognised is [OTHER]. */
    fun of(text: String): FieldTaskType =
      entries.firstOrNull { it != OTHER && it.label.equals(text.trim(), ignoreCase = true) } ?: OTHER
  }
}

/** One task that named a field, with the group it lives in (every write needs the group id). */
data class FieldTaskRef(
  val groupId: String,
  val task: Task,
  val fieldId: Int,
  val type: FieldTaskType,
  /** What the detail actually said, which is what to show when [type] is [FieldTaskType.OTHER]. */
  val typeText: String,
  /** The free-text tail, empty when there is none. */
  val note: String,
)

/** Parse one task's detail, or null when it doesn't name a field — which is simply not a field task. */
fun parseFieldTask(groupId: String, task: Task): FieldTaskRef? {
  val match = FIELD_TASK.matchEntire(task.detail.trim()) ?: return null
  val id = match.groupValues[1].toIntOrNull() ?: return null
  val typeText = match.groupValues[2].trim()
  return FieldTaskRef(
    groupId = groupId,
    task = task,
    fieldId = id,
    type = FieldTaskType.of(typeText),
    typeText = typeText,
    note = match.groupValues.getOrElse(3) { "" }.trim(),
  )
}

/** Every field task on the board, keyed by field id. A task that names no field simply isn't here. */
fun fieldTasks(data: TaskListData?): Map<Int, List<FieldTaskRef>> = data
  ?.groups
  ?.flatMap { group -> group.tasks.mapNotNull { parseFieldTask(group.id, it) } }
  ?.groupBy { it.fieldId }
  .orEmpty()

/**
 * Compose a detail for a new task, trimming the **note** to whatever the budget leaves.
 *
 * Counted against the whole string, not just the tail: `F45 - Fertilize - ` is already eighteen
 * characters, and a form that counted only what the user typed would let the mod silently truncate
 * what the app thought it had saved.
 */
fun composeTaskDetail(fieldId: Int, type: FieldTaskType, note: String = ""): String {
  val head = "F$fieldId - ${type.label}"
  val tail = note.trim()
  if (tail.isEmpty()) return head.take(MAX_TASK_DETAIL)
  val room = MAX_TASK_DETAIL - head.length - 3 // " - "
  if (room <= 0) return head.take(MAX_TASK_DETAIL)
  return "$head - ${tail.take(room)}"
}

/**
 * The calendar month a task is due in, the same way the edit form reconstructs it: from `nextN` for
 * an every-N-months task and from `period` otherwise. The daily modes have no month, and answer with
 * whatever their period happens to hold — which is what the mod's own list shows too.
 */
fun taskMonth(task: Task): Int = if (task.recurMode == 3) periodToMonth(task.nextN) else periodToMonth(task.period)

/** How a field task reads in a list: its type, then whatever note came with it. */
fun fieldTaskLabel(ref: FieldTaskRef): String {
  val type = if (ref.type == FieldTaskType.OTHER) ref.typeText else ref.type.label
  return if (ref.note.isEmpty()) type else "$type — ${ref.note}"
}
