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
import com.example.data.UserMemoryItem
import com.example.ui.components.HudPanel
import com.example.ui.components.imageIcons
import com.example.ui.theme.*

@Composable
fun MemoryScreen(
  memories: List<UserMemoryItem>,
  onDeleteMemory: (String) -> Unit,
  onClearAll: () -> Unit,
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
        text = "NEURAL LONG-TERM MEMORY",
        color = JarvisPrimary,
        style = MaterialTheme.typography.titleMedium,
        letterSpacing = 1.5.sp
      )
      IconButton(onClick = onClearAll) {
        Icon(imageIcons.Close, contentDescription = "Clear All", tint = JarvisError)
      }
    }

    HudPanel(
      title = "Persistent Stored Preferences & Facts",
      modifier = Modifier.weight(1f)
    ) {
      if (memories.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(text = "No long-term memories stored. Say 'Remember that I prefer...'", color = JarvisTextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(top = 8.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          items(memories) { memory ->
            Surface(
              color = JarvisSurfaceVariant,
              shape = RoundedCornerShape(12.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(text = memory.category.uppercase(), color = JarvisAccent, style = MaterialTheme.typography.labelSmall)
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(text = memory.content, color = JarvisTextPrimary, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { onDeleteMemory(memory.id) }) {
                  Icon(imageIcons.Close, contentDescription = "Delete Memory", tint = JarvisTextSecondary, modifier = Modifier.size(18.dp))
                }
              }
            }
          }
        }
      }
    }
  }
}
