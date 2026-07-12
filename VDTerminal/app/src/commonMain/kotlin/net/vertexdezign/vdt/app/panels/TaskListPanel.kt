package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.ClientMessage
import net.vertexdezign.vdt.TaskInput
import net.vertexdezign.vdt.app.components.ActionIcon
import net.vertexdezign.vdt.app.components.Centered
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Task
import net.vertexdezign.vdt.model.TaskGroup
import net.vertexdezign.vdt.model.TaskListData

// Task.TASK_TYPE.Standard — only Standard tasks are editable from the app (husbandry/production tasks
// carry placeable/fill-type config the app can't set), though any task can be completed or deleted.
private const val TASK_TYPE_STANDARD = 1

/** A pending create (taskId == null) or edit shown in the form dialog. */
private data class FormRequest(val groupId: String, val taskId: String?, val initial: TaskInput)

/**
 * Farm-page panel for the optional FS25_TaskList channel: lists the current farm's tasks and, via
 * [onCommand], drives the mod's own write wrappers (complete / delete / create / edit). A null [data]
 * means the mod isn't installed, rendered distinctly from an installed-but-groupless list.
 */
@Composable
fun TaskListPanel(data: TaskListData?, modifier: Modifier = Modifier, onCommand: (ClientMessage) -> Unit = {}) {
  var form by remember { mutableStateOf<FormRequest?>(null) }
  var pendingDelete by remember { mutableStateOf<Pair<String, Task>?>(null) }

  Panel(title = "Tasks", icon = Icons.Filled.Checklist, modifier = modifier) {
    when {
      data == null -> Centered("TaskList mod not installed")

      data.groups.isEmpty() -> Centered("No task groups — create one in the game menu")

      else -> {
        val activeCount = data.groups.sumOf { group -> group.tasks.count { it.active } }
        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            "$activeCount DUE NOW",
            color = if (activeCount > 0) VdtColors.Green else VdtColors.Gray,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
          )
          data.groups.forEach { group ->
            GroupSection(
              group = group,
              onAdd = { form = FormRequest(group.id, null, TaskInput()) },
              onComplete = { task -> onCommand(ClientMessage.CompleteTask(group.id, task.id)) },
              onEdit = { task -> form = FormRequest(group.id, task.id, editInitial(task)) },
              onDelete = { task -> pendingDelete = group.id to task },
            )
          }
        }
      }
    }
  }

  form?.let { request ->
    TaskFormDialog(
      title = if (request.taskId == null) "New task" else "Edit task",
      initial = request.initial,
      onSave = { input ->
        onCommand(
          if (request.taskId == null) {
            ClientMessage.CreateTask(request.groupId, input)
          } else {
            ClientMessage.EditTask(request.groupId, request.taskId, input)
          },
        )
        form = null
      },
      onDismiss = { form = null },
    )
  }

  pendingDelete?.let { (groupId, task) ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Delete task?") },
      text = { Text(task.label()) },
      confirmButton = {
        TextButton(onClick = {
          onCommand(ClientMessage.DeleteTask(groupId, task.id))
          pendingDelete = null
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun GroupSection(
  group: TaskGroup,
  onAdd: () -> Unit,
  onComplete: (Task) -> Unit,
  onEdit: (Task) -> Unit,
  onDelete: (Task) -> Unit,
) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        group.name.ifBlank { "Unnamed group" }.uppercase(),
        color = VdtColors.DarkGray,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      ActionIcon(Icons.Filled.Add, "add task", VdtColors.Green, onAdd)
    }
    if (group.tasks.isEmpty()) {
      Text("No tasks yet", color = VdtColors.Gray, fontSize = 11.sp)
    } else {
      // Due tasks first, then the rest — the "what to do now" list is what the farm page is for.
      group.tasks
        .sortedWith(compareByDescending<Task> { it.active }.thenBy { it.priority })
        .forEach { task -> TaskRow(task, onComplete, onEdit, onDelete) }
    }
  }
}

@Composable
private fun TaskRow(task: Task, onComplete: (Task) -> Unit, onEdit: (Task) -> Unit, onDelete: (Task) -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(start = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    // Filled green dot = due right now; hollow-grey = scheduled but not yet due.
    Box(
      Modifier.size(8.dp).clip(CircleShape).background(if (task.active) VdtColors.Green else VdtColors.TrackGray),
    )
    Text(
      task.label(),
      color = if (task.active) VdtColors.TextDark else VdtColors.Gray,
      fontSize = 12.sp,
      fontWeight = if (task.active) FontWeight.SemiBold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (task.active) ActionIcon(Icons.Filled.Check, "complete", VdtColors.Green, onClick = { onComplete(task) })
    if (task.type == TASK_TYPE_STANDARD) {
      ActionIcon(Icons.Filled.Edit, "edit", VdtColors.DarkGray, onClick = { onEdit(task) })
    }
    ActionIcon(Icons.Filled.Delete, "delete", VdtColors.DarkGray, onClick = { onDelete(task) })
  }
}

private fun Task.label(): String = description.ifBlank { detail }.ifBlank { "(untitled task)" }
