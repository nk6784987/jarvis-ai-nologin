package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ActionPlan
import com.example.model.JarvisState
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun HomeScreen(
  jarvisState: JarvisState,
  audioLevel: Float,
  latestActionPlan: ActionPlan?,
  telemetry: List<Pair<String, String>>,
  onTriggerVoice: () -> Unit,
  onSendCommand: (String) -> Unit,
  onOpenTasks: () -> Unit,
  modifier: Modifier = Modifier
) {
  var commandInput by remember { mutableStateOf("") }

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.SpaceBetween
  ) {
    // Live telemetry (real values supplied by the ViewModel)
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        telemetry.forEach { TelemetryBadge(label = it.first, value = it.second) }
      }
    }

    // Central AI Core Display
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.weight(1f)
    ) {
      JarvisCoreCanvas(state = jarvisState, sizeDp = 260)

      Spacer(modifier = Modifier.height(14.dp))

      // Dynamic Status Label & Action Plan Preview
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Surface(
          color = JarvisSurfaceVariant,
          shape = RoundedCornerShape(20.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, JarvisPrimary.copy(alpha = 0.5f))
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Box(
              modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(JarvisPrimary)
            )
            Text(
              text = "SYSTEM STATE: ${jarvisState.name}",
              color = JarvisPrimary,
              style = MaterialTheme.typography.labelMedium,
              letterSpacing = 1.5.sp
            )
          }
        }

        if (latestActionPlan != null) {
          Surface(
            color = JarvisSurface,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisAccent.copy(alpha = 0.6f))
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(
                text = "ACTION PLAN: ${latestActionPlan.goal}",
                color = JarvisAccent,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp
              )
              Text(
                text = "STATUS: ACTION READY / WAITING FOR DEVICE AGENT",
                color = JarvisTextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp
              )
            }
          }
        }
      }
    }

    // Quick Command Input & Suggestions
    Column(
      modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        val suggestions = listOf("YouTube kholo aur Iron Man trailer search karo", "Downloads mein latest PDF dhoondo", "Screen par pehla button dabao", "Aaj shaam 7 baje meeting yaad dilana")
        items(suggestions) { suggestion ->
          Surface(
            color = JarvisSurface,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
            modifier = Modifier.clickable {
              if (suggestion == "Run Task Pipeline") onOpenTasks()
              else onSendCommand(suggestion)
            }
          ) {
            Text(
              text = suggestion,
              color = JarvisTextSecondary,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = commandInput,
          onValueChange = { commandInput = it },
          placeholder = { Text("Speak naturally (Hindi/Hinglish/English)...", color = JarvisTextSecondary) },
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(24.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = JarvisPrimary,
            unfocusedBorderColor = JarvisBorder,
            focusedContainerColor = JarvisSurface,
            unfocusedContainerColor = JarvisSurface,
            focusedTextColor = JarvisTextPrimary,
            unfocusedTextColor = JarvisTextPrimary
          ),
          singleLine = true
        )

        Button(
          onClick = {
            if (commandInput.isNotBlank()) {
              onSendCommand(commandInput)
              commandInput = ""
            }
          },
          shape = RoundedCornerShape(24.dp),
          colors = ButtonDefaults.buttonColors(containerColor = JarvisPrimary)
        ) {
          Icon(
            imageIcons.Play,
            contentDescription = "Send",
            tint = JarvisBackground,
            modifier = Modifier.size(20.dp)
          )
        }
      }
    }
  }
}

@Composable
fun TelemetryBadge(label: String, value: String) {
  Surface(
    color = JarvisSurface.copy(alpha = 0.6f),
    shape = RoundedCornerShape(8.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = label,
        color = JarvisTextSecondary,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 9.sp,
        letterSpacing = 1.sp
      )
      Text(
        text = value,
        color = JarvisPrimary,
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 13.sp
      )
    }
  }
}
