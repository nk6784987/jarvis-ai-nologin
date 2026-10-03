package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.model.JarvisState
import com.example.ui.theme.*

@Composable
fun VoiceControlBar(
  jarvisState: JarvisState,
  audioLevel: Float,
  onTriggerVoice: () -> Unit,
  onOpenHome: () -> Unit,
  onOpenConversation: () -> Unit,
  onOpenTasks: () -> Unit,
  onOpenQuickActions: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
    color = JarvisSurface.copy(alpha = 0.85f),
    shape = RoundedCornerShape(28.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Waveform when listening or speaking
      if (jarvisState == JarvisState.LISTENING || jarvisState == JarvisState.SPEAKING) {
        AudioWaveformView(
          isStreaming = true,
          audioLevel = audioLevel,
          modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround
      ) {
        // Home Navigation
        IconButton(
          onClick = { onOpenHome() },
          modifier = Modifier.size(44.dp)
        ) {
          Icon(
            imageIcons.Home,
            contentDescription = "Home",
            tint = JarvisPrimary
          )
        }

        // Conversation Layer Toggle
        IconButton(
          onClick = { onOpenConversation() },
          modifier = Modifier.size(44.dp)
        ) {
          Icon(
            imageIcons.Chat,
            contentDescription = "Conversation Matrix",
            tint = JarvisTextSecondary
          )
        }

        // Central Futuristic Voice Trigger Button
        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(
              Brush.radialGradient(
                listOf(
                  if (jarvisState == JarvisState.LISTENING) JarvisSecondary else JarvisPrimary,
                  JarvisSurfaceVariant
                )
              )
            )
            .border(2.dp, if (jarvisState == JarvisState.LISTENING) JarvisAccent else JarvisPrimary, CircleShape)
            .clickable { onTriggerVoice() },
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageIcons.Mic,
            contentDescription = "Voice Directive",
            tint = JarvisBackground,
            modifier = Modifier.size(28.dp)
          )
        }

        // Task Panel Toggle
        IconButton(
          onClick = { onOpenTasks() },
          modifier = Modifier.size(44.dp)
        ) {
          Icon(
            imageIcons.Task,
            contentDescription = "Task Pipeline",
            tint = JarvisTextSecondary
          )
        }

        // Quick Actions / Tools Menu
        IconButton(
          onClick = { onOpenQuickActions() },
          modifier = Modifier.size(44.dp)
        ) {
          Icon(
            imageIcons.Tools,
            contentDescription = "Quick Actions",
            tint = JarvisTextSecondary
          )
        }
      }
    }
  }
}
