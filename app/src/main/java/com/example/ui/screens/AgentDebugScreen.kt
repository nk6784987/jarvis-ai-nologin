package com.example.ui.screens

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
import com.example.agent.AutonomousTask
import com.example.ui.components.HudPanel
import com.example.ui.components.imageIcons
import com.example.ui.theme.*

@Composable
fun AgentDebugScreen(
  activeTask: AutonomousTask?,
  taskHistory: List<AutonomousTask>,
  onBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      IconButton(onClick = onBack) {
        Icon(imageIcons.ArrowBack, contentDescription = "Back", tint = JarvisPrimary)
      }
      Text(
        text = "AUTONOMOUS AGENT BRAIN (DEBUG)",
        color = JarvisPrimary,
        style = MaterialTheme.typography.titleMedium,
        letterSpacing = 1.5.sp
      )
      Spacer(modifier = Modifier.width(36.dp))
    }

    HudPanel(
      title = "Active Goal & Subtasks",
      modifier = Modifier.weight(1f)
    ) {
      if (activeTask == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(text = "No active autonomous goal. Issue a natural language goal.", color = JarvisTextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(top = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          item {
            Text(text = "Goal: ${activeTask.goal}", color = JarvisAccent, style = MaterialTheme.typography.bodyLarge)
            Text(text = "State: ${activeTask.state}", color = JarvisPrimary, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
          }
          items(activeTask.subTasks) { sub ->
            Surface(
              color = JarvisSurfaceVariant,
              shape = RoundedCornerShape(8.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(text = sub.title, color = JarvisTextPrimary, style = MaterialTheme.typography.bodyMedium)
                Text(text = sub.status.name, color = JarvisSecondary, style = MaterialTheme.typography.labelSmall)
              }
            }
          }
        }
      }
    }
  }
}
