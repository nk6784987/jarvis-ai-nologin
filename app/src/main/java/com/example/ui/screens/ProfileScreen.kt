package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.HudPanel
import com.example.ui.components.imageIcons
import com.example.ui.theme.*

@Composable
fun ProfileScreen(
  memoryCount: Int,
  onClearAllData: () -> Unit,
  onOpenMemory: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  var confirmClear by remember { mutableStateOf(false) }
  Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      IconButton(onClick = onBack) { Icon(imageIcons.ArrowBack, contentDescription = "Back", tint = JarvisPrimary) }
      Text("ON-DEVICE PROFILE", color = JarvisPrimary, style = MaterialTheme.typography.titleMedium, letterSpacing = 1.5.sp)
      Spacer(Modifier.width(36.dp))
    }
    HudPanel(title = "Local Storage") {
      Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Koi login/account nahi hai. Memory, task history aur settings sirf is phone par store hote hain.", color = JarvisTextSecondary, style = MaterialTheme.typography.bodyMedium)
        Text("$memoryCount memories saved", color = JarvisTextPrimary, style = MaterialTheme.typography.titleMedium)
        Button(onClick = onOpenMemory, colors = ButtonDefaults.buttonColors(containerColor = JarvisPrimary), shape = RoundedCornerShape(12.dp)) {
          Text("VIEW LONG-TERM MEMORY", color = JarvisBackground, style = MaterialTheme.typography.labelMedium)
        }
        if (!confirmClear) {
          Button(onClick = { confirmClear = true }, colors = ButtonDefaults.buttonColors(containerColor = JarvisError.copy(alpha = 0.2f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisError), shape = RoundedCornerShape(12.dp)) {
            Text("ERASE ALL LOCAL DATA", color = JarvisError, style = MaterialTheme.typography.labelMedium)
          }
        } else {
          Text("Memory, history aur chat sab delete hoga. Pakka?", color = JarvisError, style = MaterialTheme.typography.bodySmall)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onClearAllData(); confirmClear = false }, colors = ButtonDefaults.buttonColors(containerColor = JarvisError)) { Text("YES, ERASE") }
            TextButton(onClick = { confirmClear = false }) { Text("CANCEL", color = JarvisPrimary) }
          }
        }
      }
    }
  }
}
