package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.TasksStrings

internal object EnTasks : TasksStrings {
  override val notInstalled = "TaskList mod not installed"
  override val noGroups = "No task groups — create one in the game menu"
  override fun dueNow(count: Int): String = "$count DUE NOW"
  override val newTask = "New task"
  override val editTask = "Edit task"
  override val deleteTitle = "Delete task?"
  override val unnamedGroup = "Unnamed group"
  override val addTask = "add task"
  override val noTasks = "No tasks yet"
  override val complete = "complete"
  override val untitled = "(untitled task)"

  override val detail = "Detail"
  override val priority = "Priority"
  override val effort = "Effort"
  override val repeat = "Repeat"
  override val startMonth = "Start month"
  override val month = "Month"
  override val everyNMonths = "Every N months"
  override val everyNDays = "Every N days"
  override val once = "Once"
  override val monthly = "Monthly"
  override val daily = "Daily"
  override fun select(label: String): String = "select $label"
  override fun decrease(label: String): String = "decrease $label"
  override fun increase(label: String): String = "increase $label"
  override fun nowMonth(month: String): String = "Now · $month"
  override fun inMonths(offset: Int, month: String): String = "+$offset · $month"

  override val alertTitle = "TASKS DUE"
  override val unnamedTask = "Unnamed task"
  override fun alertMessage(count: Int, names: String): String = "$count tasks: $names"
}
