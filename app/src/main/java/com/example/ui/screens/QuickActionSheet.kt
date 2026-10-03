package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.HudPanel
import com.example.ui.components.imageIcons
import com.example.ui.theme.*

data class QuickActionItem(
  val title: String,
  val icon: ImageVector,
  val onClick: () -> Unit
)

@Composable
fun QuickActionSheet(
  onClose: () -> Unit,
  onNavigateTasks: () -> Unit,
  onNavigateSettings: () -> Unit,
  onNavigateProfile: () -> Unit,
  onNavigateApi: () -> Unit,
  onNavigateAgentDebug: () -> Unit,
  onNavigateMemory: () -> Unit,
  onNavigateCapabilities: () -> Unit,
  modifier: Modifier = Modifier
) {
  val actions = listOf(
    QuickActionItem("Task Matrix", imageIcons.Task) { onNavigateTasks(); onClose() },
    QuickActionItem("Capabilities", imageIcons.Tools) { onNavigateCapabilities(); onClose() },
    QuickActionItem("Neural Memory", imageIcons.Memory) { onNavigateMemory(); onClose() },
    QuickActionItem("Agent Brain", imageIcons.Tools) { onNavigateAgentDebug(); onClose() },
    QuickActionItem("Neural Search", imageIcons.Search) { onClose() },
    QuickActionItem("Subsystem Apps", imageIcons.Apps) { onClose() },
    QuickActionItem("Data Archives", imageIcons.Folder) { onClose() },
    QuickActionItem("Security Matrix", imageIcons.Shield) { onClose() },
    QuickActionItem("API Manager", imageIcons.Api) { onNavigateApi(); onClose() },
    QuickActionItem("Account", imageIcons.Person) { onNavigateProfile(); onClose() },
    QuickActionItem("Settings", imageIcons.Settings) { onNavigateSettings(); onClose() }
  )

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
      Text(
        text = "QUICK SUBSYSTEM ACTIONS",
        color = JarvisPrimary,
        style = MaterialTheme.typography.titleMedium,
        letterSpacing = 1.5.sp
      )
      IconButton(onClick = onClose) {
        Icon(imageIcons.Close, contentDescription = "Close", tint = JarvisTextSecondary)
      }
    }

    HudPanel(
      title = "Select Protocol",
      modifier = Modifier.weight(1f)
    ) {
      LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        items(actions) { action ->
          QuickActionTile(item = action)
        }
      }
    }
  }
}

@Composable
fun QuickActionTile(item: QuickActionItem) {
  Surface(
    color = JarvisSurfaceVariant,
    shape = RoundedCornerShape(12.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
    modifier = Modifier
      .aspectRatio(1f)
      .clickable { item.onClick() }
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Icon(
        imageVector = item.icon,
        contentDescription = item.title,
        tint = JarvisPrimary,
        modifier = Modifier.size(28.dp)
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = item.title,
        color = JarvisTextSecondary,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        maxLines = 1,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
      )
    }
  }
}
