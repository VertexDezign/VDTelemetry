package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.alerts.AlertRule
import net.vertexdezign.vdt.app.alerts.AlertSeverity
import net.vertexdezign.vdt.app.alerts.KeyedAlertRule
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.widgets.TaskListWidget
import net.vertexdezign.vdt.app.widgets.Widget

/** FS25_TaskList tasks. Full page is the task list filling the screen; also provides the tasks tile. */
object TasksApp : VdtApp {
  const val TASKS_DUE_ALERT_ID = "tasks.due"

  override val id = "tasks"
  override val title = "Tasks"
  override val icon: ImageVector = Icons.Filled.Checklist
  override val widgets: List<Widget> = listOf(TaskListWidget)

  // Warning, not Info, so it chimes: a due task asks for action. Keyed per task id, so each task
  // fires once when it turns due (Task.active — the mod's "currently due" flag) and can fire again
  // on its next recurrence; a batch turning due together (month change) is a single alert. The
  // null channel (mod not installed) freezes the rule — it can never fire, matching isAvailable.
  override val alerts: List<AlertRule> =
    listOf(
      KeyedAlertRule(
        id = TASKS_DUE_ALERT_ID,
        severity = AlertSeverity.Warning,
        title = "TASKS DUE",
        activeEntities = { inputs ->
          inputs.taskList?.groups
            ?.flatMap { it.tasks }
            ?.filter { it.active }
            ?.associate { it.id to it.description.ifEmpty { "Unnamed task" } }
        },
        message = { labels ->
          if (labels.size == 1) {
            labels.single()
          } else {
            "${labels.size} tasks: " + labels.take(3).joinToString(", ") + if (labels.size > 3) ", …" else ""
          }
        },
      ),
    )

  // A null channel is the mod-not-installed signal (the server broadcasts that null explicitly, and
  // an installed mod with no task groups still sends data). See TaskListPanel's empty states.
  @Composable
  override fun isAvailable(): Boolean {
    val taskList by LocalVdtStore.current.taskList.collectAsState()
    return taskList != null
  }

  @Composable
  override fun FullPage(modifier: Modifier) = TaskListWidget.Content(modifier)
}
