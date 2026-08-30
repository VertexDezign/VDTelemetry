package net.vertexdezign.vdt.app.panels

import net.vertexdezign.vdt.model.CropCalendarData
import net.vertexdezign.vdt.model.CropRotationData
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

/**
 * A task this field is asking for, ready to be created.
 *
 * [month] is filled in only where the crop calendar can actually say something — a sow window it
 * knows, on a map running seasonal growth. Everywhere else it is null and the form opens on its own
 * default rather than on a month invented from data that carries none.
 */
data class FieldSuggestion(val type: FieldTaskType, val detail: String, val month: Int?)

/**
 * What to offer for this field, as prefilled tasks.
 *
 * **A suggestion is suppressed while a task of the same type is already on the board for that field.**
 * Without that rule every visit to the app re-offers work that is already written down, which is the
 * fastest way to make a suggester worth ignoring.
 *
 * Fertilize, lime, weed and spray are never here — see [FieldTaskType]. They are created by hand from
 * the field's own add-task control, which is where scheduling a month or three ahead earns its place:
 * crop care is *planned* forward, not triggered by a reading.
 */
fun fieldSuggestions(row: FieldRow, rotation: CropRotationData?, calendar: CropCalendarData?): List<FieldSuggestion> {
  val taken = row.tasks.map { it.type }.toSet()
  return fieldWork(row).filterNot { it in taken }.map { type ->
    if (type != FieldTaskType.SOW) {
      FieldSuggestion(type, composeTaskDetail(row.id, type), month = null)
    } else {
      val crop = suggestedCrop(row, rotation)
      FieldSuggestion(type, composeTaskDetail(row.id, type, crop.orEmpty()), month = sowMonth(crop, calendar))
    }
  }
}

/**
 * The crop this field's rotation says comes next, or null when nothing in the planner matches.
 *
 * FS25_CropRotation stores plans as a flat list with **no link from a plan to a field**, so this
 * reads the link the other way round: `fieldInfo` already carries this field's own history
 * (`lastCrop`, `prevCrop`, from the mod's per-field maps), and the ordered pair is matched against
 * every plan's sequence. The best-matching plan's *next* slot is the answer.
 *
 * Null rather than a guess when nothing matches. `FUTURE.md` -> "Assigning a CropRotation plan to a
 * field" holds the VDT-owned store that would answer this properly; the point of doing it this way
 * is to find out whether that store is worth its complexity before building it.
 */
fun suggestedCrop(row: FieldRow, rotation: CropRotationData?): String? {
  val history = row.info?.cropRotation ?: return null
  val last = history.lastCrop.trim()
  if (last.isEmpty()) return null
  val previous = history.prevCrop.trim()

  var bestScore = 0
  var best: String? = null
  for (plan in rotation?.rotations.orEmpty()) {
    val sequence = plan.sequence
    if (sequence.size < 2) continue
    for (index in sequence.indices) {
      if (!sequence[index].crop.equals(last, ignoreCase = true)) continue
      // Two matches beat one: a plan that also agrees about the crop before last is describing this
      // field's actual rotation rather than merely containing its current crop somewhere.
      val previousSlot = sequence[(index - 1 + sequence.size) % sequence.size]
      val score = if (previous.isNotEmpty() && previousSlot.crop.equals(previous, ignoreCase = true)) 2 else 1
      if (score <= bestScore) continue
      val next = sequence[(index + 1) % sequence.size]
      // A fallow slot is a real answer in a rotation, but it is not a crop to sow.
      if (next.state == 0 || next.crop.isBlank()) continue
      bestScore = score
      best = next.crop
    }
  }
  return best
}

/**
 * The month to date a sow task for: the first period this crop may be planted in, at or after today.
 *
 * Null outside `SEASONAL` growth, and that guard is the point rather than a formality — in the other
 * modes the game answers "yes" to every period for every crop, so `plant` is all twelve and carries
 * no information at all. Offering the task with no month, and saying so, beats inventing a best month
 * from a calendar that has none.
 */
fun sowMonth(crop: String?, calendar: CropCalendarData?): Int? {
  if (crop.isNullOrBlank() || calendar == null || !calendar.isSeasonal) return null
  val today = calendar.today?.period ?: return null
  val periods = calendar.crops.firstOrNull { it.name.equals(crop, ignoreCase = true) }?.plant.orEmpty()
  if (periods.isEmpty()) return null
  // Wrapped, so a window that has already passed this year points at next year's rather than at
  // nothing: "sow it in March" is still the right answer in November.
  val next = periods.filter { it >= today }.minOrNull() ?: periods.min()
  return periodToMonth(next)
}
