package net.vertexdezign.vdt.model

import kotlinx.serialization.Serializable

/**
 * Typed model of the optional **FS25_TaskList** channel the mod writes to `taskList.json` (separate
 * file, event-driven cadence — see the mod's `src/integrations/TaskList.lua`).
 *
 * Its own [version], independent of [VdtData.version]. Same tolerance rules as the rest of the model:
 * omitted keys fall back to these defaults, so the mod can add fields ahead of the client.
 *
 * The raw enum ints ([TaskGroup.type], [Task.type], [Task.recurMode]) are passed through verbatim
 * from the mod rather than mapped to Kotlin enums — the mod owns those enumerations and may extend
 * them, and the tolerant-decode contract keeps an unknown int from breaking the parse.
 */
@Serializable
data class TaskListData(
  val version: String = "",
  val groups: List<TaskGroup> = emptyList(),
)

@Serializable
data class TaskGroup(
  val id: String = "",
  val name: String = "",
  /** TaskGroup.GROUP_TYPE: 1 Standard, 2 Template, 3 TemplateInstance. */
  val type: Int = 0,
  val farmId: Int = 0,
  val effortMultiplier: Int = 1,
  val tasks: List<Task> = emptyList(),
)

@Serializable
data class Task(
  val id: String = "",
  /** The user's free-text note (empty for auto husbandry/production tasks). */
  val detail: String = "",
  /** Human-readable label resolved mod-side (Standard = [detail]; auto tasks = a generated string). */
  val description: String = "",
  /** Task.TASK_TYPE: 1 Standard, 2 HusbandryFood, 3 HusbandryConditions, 4 Production. */
  val type: Int = 0,
  val priority: Int = 0,
  /** Month/period the task is due (1-12). */
  val period: Int = 0,
  val effort: Int = 0,
  val shouldRecur: Boolean = false,
  /** Task.RECUR_MODE: 0 None, 1 Monthly, 2 Daily, 3 EveryNMonths, 4 EveryNDays. */
  val recurMode: Int = 0,
  /** The N in every-N-days / every-N-months. */
  val n: Int = 0,
  val nextN: Int = 0,
  /** True when this task is currently due (present in the mod's `activeTasks`). */
  val active: Boolean = false,
)
