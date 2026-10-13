package net.vertexdezign.vdt.app.i18n

/** The Tasks app (FS25_TaskList): groups, tasks, the task form and the due-tasks alert. */
interface TasksStrings {
  val notInstalled: String
  val noGroups: String
  fun dueNow(count: Int): String
  val newTask: String
  val editTask: String
  val deleteTitle: String
  val unnamedGroup: String
  val addTask: String
  val noTasks: String
  val complete: String
  val untitled: String

  /** The task form. */
  val detail: String
  val priority: String
  val effort: String
  val repeat: String
  val startMonth: String
  val month: String
  val everyNMonths: String
  val everyNDays: String
  val once: String
  val monthly: String
  val daily: String
  fun select(label: String): String
  fun decrease(label: String): String
  fun increase(label: String): String
  fun nowMonth(month: String): String
  fun inMonths(offset: Int, month: String): String

  /** The due-tasks alert. */
  val alertTitle: String
  val unnamedTask: String
  fun alertMessage(count: Int, names: String): String
}
