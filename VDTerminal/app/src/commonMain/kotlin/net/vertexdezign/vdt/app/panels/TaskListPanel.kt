package net.vertexdezign.vdt.app.panels

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vertexdezign.vdt.app.components.Panel
import net.vertexdezign.vdt.app.theme.VdtColors
import net.vertexdezign.vdt.model.Task
import net.vertexdezign.vdt.model.TaskGroup
import net.vertexdezign.vdt.model.TaskListData

/**
 * Farm-page panel for the optional FS25_TaskList channel. Read-only for now (Step 3); the
 * complete/create/edit actions land in Step 4.
 *
 * A null [data] means the server has no `taskList.json` — i.e. the mod isn't installed — which is
 * rendered distinctly from an installed-but-empty task list.
 */
@Composable
fun TaskListPanel(data: TaskListData?, modifier: Modifier = Modifier) {
  Panel(title = "Tasks", icon = Icons.Filled.Checklist, modifier = modifier) {
    when {
      data == null -> Centered("TaskList mod not installed")

      data.groups.all { it.tasks.isEmpty() } -> Centered("No tasks")

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
          data.groups
            .filter { it.tasks.isNotEmpty() }
            .forEach { group -> GroupSection(group) }
        }
      }
    }
  }
}

@Composable
private fun GroupSection(group: TaskGroup) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      group.name.ifBlank { "Unnamed group" }.uppercase(),
      color = VdtColors.DarkGray,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    // Due tasks first, then the rest — the "what to do now" list is what the farm page is for.
    group.tasks
      .sortedWith(compareByDescending<Task> { it.active }.thenBy { it.priority })
      .forEach { task -> TaskRow(task) }
  }
}

@Composable
private fun TaskRow(task: Task) {
  Row(
    Modifier.fillMaxWidth().padding(start = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Filled green dot = due right now; hollow-grey = scheduled but not yet due.
    Box(
      Modifier
        .size(8.dp)
        .clip(CircleShape)
        .background(if (task.active) VdtColors.Green else VdtColors.TrackGray),
    )
    Text(
      task.description.ifBlank { task.detail }.ifBlank { "(untitled task)" },
      color = if (task.active) VdtColors.TextDark else VdtColors.Gray,
      fontSize = 12.sp,
      fontWeight = if (task.active) FontWeight.SemiBold else FontWeight.Normal,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (task.effort > 0) {
      Text("×${task.effort}", color = VdtColors.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
private fun Centered(text: String) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, color = VdtColors.Gray, fontSize = 12.sp)
  }
}
