package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.JarvisSettings
import com.example.ui.components.HudPanel
import com.example.ui.components.imageIcons
import com.example.ui.theme.*

@Composable
fun SettingsScreen(
  settings: JarvisSettings,
  onUpdateSettings: (JarvisSettings) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  var currentSettings by remember { mutableStateOf(settings) }

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
        text = "VOICE & SYSTEM CONFIG",
        color = JarvisPrimary,
        style = MaterialTheme.typography.titleMedium,
        letterSpacing = 1.5.sp
      )
      Spacer(modifier = Modifier.width(36.dp))
    }

    HudPanel(
      title = "Voice Engine & API Configuration",
      modifier = Modifier.weight(1f)
    ) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        item {
          SettingToggleItem(
            "Voice Synthesis Enabled",
            "Speak responses aloud via TTS",
            currentSettings.voiceEnabled
          ) {
            currentSettings = currentSettings.copy(voiceEnabled = it)
            onUpdateSettings(currentSettings)
          }
        }
        item {
          SettingToggleItem(
            "Wake Word Mode ('Hey JARVIS')",
            "Listen for ambient wake phrase",
            currentSettings.wakeWordEnabled
          ) {
            currentSettings = currentSettings.copy(wakeWordEnabled = it)
            onUpdateSettings(currentSettings)
          }
        }
        item {
          SettingToggleItem(
            "Unrestricted Mode",
            "Confirmation dialogs band, agent step/retry limits 4x. Payment/OTP/PIN screens phir bhi block rehti hain. Messages, calls, deletes bina poochhe ho sakte hain.",
            currentSettings.unrestrictedMode
          ) {
            currentSettings = currentSettings.copy(unrestrictedMode = it)
            onUpdateSettings(currentSettings)
          }
        }
        item {
          SettingToggleItem(
            "Interrupt While Speaking",
            "Stop speaking when user interrupts",
            currentSettings.interruptEnabled
          ) {
            currentSettings = currentSettings.copy(interruptEnabled = it)
            onUpdateSettings(currentSettings)
          }
        }

        item {
          Spacer(modifier = Modifier.height(4.dp))
          Text(text = "API & PERMISSIONS", color = JarvisPrimary, style = MaterialTheme.typography.labelMedium)
        }

        item {
          Text(
            text = "AI provider keys, web-search keys and permissions are managed in API Configuration. Keys are stored only in Android Keystore-encrypted storage.",
            color = JarvisTextSecondary, style = MaterialTheme.typography.bodySmall
          )
        }
      }
    }
  }
}

@Composable
fun SettingToggleItem(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
        Text(text = title, color = JarvisTextPrimary, style = MaterialTheme.typography.bodyMedium)
        Text(text = subtitle, color = JarvisTextSecondary, style = MaterialTheme.typography.bodySmall)
      }
      Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
          checkedThumbColor = JarvisBackground,
          checkedTrackColor = JarvisPrimary,
          uncheckedThumbColor = JarvisTextSecondary,
          uncheckedTrackColor = JarvisSurface
        )
      )
    }
  }
}

@Composable
fun ApiKeyInput(label: String, value: String, onValueChange: (String) -> Unit) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Text(text = label, color = JarvisTextSecondary, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      placeholder = { Text("Enter secure API key...", color = JarvisTextSecondary) },
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(12.dp),
      visualTransformation = PasswordVisualTransformation(),
      singleLine = true,
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = JarvisPrimary,
        unfocusedBorderColor = JarvisBorder,
        focusedContainerColor = JarvisSurfaceVariant,
        unfocusedContainerColor = JarvisSurfaceVariant,
        focusedTextColor = JarvisTextPrimary,
        unfocusedTextColor = JarvisTextPrimary
      )
    )
  }
}
