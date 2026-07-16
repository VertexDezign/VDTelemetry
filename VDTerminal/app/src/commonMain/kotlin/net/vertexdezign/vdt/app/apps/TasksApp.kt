package net.vertexdezign.vdt.app.apps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.vertexdezign.vdt.app.state.LocalVdtStore
import net.vertexdezign.vdt.app.widgets.TaskListWidget
import net.vertexdezign.vdt.app.widgets.Widget

/** FS25_TaskList tasks. Full page is the task list filling the screen; also provides the tasks tile. */
object TasksApp : VdtApp {
  override val id = "tasks"
  override val title = "Tasks"
  override val icon: ImageVector = Icons.Filled.Checklist
  override val widgets: List<Widget> = listOf(TaskListWidget)

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
