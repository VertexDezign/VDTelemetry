package net.vertexdezign.vdt.app.i18n.de

import net.vertexdezign.vdt.app.i18n.TasksStrings

internal object DeTasks : TasksStrings {
  override val notInstalled = "TaskList-Mod nicht installiert"
  override val noGroups = "Keine Aufgabengruppen — lege eine im Spielmenü an"
  override fun dueNow(count: Int): String = "$count JETZT FÄLLIG"
  override val newTask = "Neue Aufgabe"
  override val editTask = "Aufgabe bearbeiten"
  override val deleteTitle = "Aufgabe löschen?"
  override val unnamedGroup = "Unbenannte Gruppe"
  override val addTask = "Aufgabe hinzufügen"
  override val noTasks = "Noch keine Aufgaben"
  override val complete = "erledigen"
  override val untitled = "(unbenannte Aufgabe)"

  override val detail = "Beschreibung"
  override val priority = "Priorität"
  override val effort = "Aufwand"
  override val repeat = "Wiederholung"
  override val startMonth = "Startmonat"
  override val month = "Monat"
  override val everyNMonths = "Alle N Monate"
  override val everyNDays = "Alle N Tage"
  override val once = "Einmalig"
  override val monthly = "Monatlich"
  override val daily = "Täglich"
  override fun select(label: String): String = "$label wählen"
  override fun decrease(label: String): String = "$label verringern"
  override fun increase(label: String): String = "$label erhöhen"
  override fun nowMonth(month: String): String = "Jetzt · $month"
  override fun inMonths(offset: Int, month: String): String = "+$offset · $month"

  override val alertTitle = "AUFGABEN FÄLLIG"
  override val unnamedTask = "Unbenannte Aufgabe"
  override fun alertMessage(count: Int, names: String): String = "$count Aufgaben: $names"
}
