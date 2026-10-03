package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.TaskItem
import com.example.model.TaskStatus
import com.example.ui.components.HudPanel
import com.example.ui.components.imageIcons
import com.example.ui.theme.*

@Composable
fun TaskPanelScreen(
  tasks: List<TaskItem>,
  onRunPipeline: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    HudPanel(
      title = "Agent Tasks & Schedule",
      actionContent = {
        Button(
          onClick = { onRunPipeline() },
          colors = ButtonDefaults.buttonColors(containerColor = JarvisPrimary),
          shape = RoundedCornerShape(12.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
          Icon(
            imageIcons.Play,
            contentDescription = "Open live agent view",
            tint = JarvisBackground,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("LIVE AGENT", color = JarvisBackground, style = MaterialTheme.typography.labelSmall)
        }
      },
      modifier = Modifier.weight(1f)
    ) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        items(tasks) { task ->
          TaskRow(task = task)
        }
      }
    }
  }
}

@Composable
fun TaskRow(task: TaskItem) {
  val statusColor = when (task.status) {
    TaskStatus.PENDING -> JarvisTextSecondary
    TaskStatus.EXECUTING -> JarvisSecondary
    TaskStatus.COMPLETED -> JarvisAccent
    TaskStatus.ERROR -> JarvisError
  }

  Surface(
    color = JarvisSurfaceVariant,
    shape = RoundedCornerShape(12.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Box(
          modifier = Modifier
            .size(10.dp)
            .background(statusColor, RoundedCornerShape(5.dp))
        )
        Column {
          Text(
            text = task.title,
            color = JarvisTextPrimary,
            style = MaterialTheme.typography.bodyMedium
          )
          if (task.detail.isNotBlank()) {
            Text(
              text = task.detail,
              color = JarvisTextSecondary,
              style = MaterialTheme.typography.labelSmall,
              fontSize = 10.sp
            )
          }
        }
      }

      Surface(
        color = statusColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp)
      ) {
        Text(
          text = task.status.name,
          color = statusColor,
          style = MaterialTheme.typography.labelSmall,
          fontSize = 10.sp,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
      }
    }
  }
}
