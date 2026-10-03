package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.JarvisState
import com.example.ui.theme.*

@Composable
fun HudHeader(
  isOnline: Boolean,
  jarvisState: JarvisState,
  onToggleOnline: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenProfile: () -> Unit,
  onOpenApi: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    color = JarvisSurface.copy(alpha = 0.7f),
    shape = RoundedCornerShape(16.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      // Left: Jarvis Title & Online Status
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Box(
          modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (isOnline) JarvisAccent else JarvisError)
            .clickable { onToggleOnline() }
        )
        Column {
          Text(
            text = "J.A.R.V.I.S.",
            color = JarvisPrimary,
            style = MaterialTheme.typography.titleMedium,
            letterSpacing = 2.sp
          )
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              text = if (isOnline) "ONLINE" else "OFFLINE",
              color = if (isOnline) JarvisTextSecondary else JarvisError,
              style = MaterialTheme.typography.labelSmall,
              letterSpacing = 1.sp
            )
            Text(
              text = "•",
              color = JarvisTextSecondary,
              style = MaterialTheme.typography.labelSmall
            )
            Text(
              text = jarvisState.name,
              color = JarvisAccent,
              style = MaterialTheme.typography.labelSmall,
              letterSpacing = 1.sp
            )
          }
        }
      }

      // Right Action Icons (Professional Material Icons - NO EMOJIS)
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(
          onClick = { onOpenApi() },
          modifier = Modifier.size(36.dp)
        ) {
          Icon(
            imageIcons.Api,
            contentDescription = "API Config",
            tint = JarvisTextSecondary,
            modifier = Modifier.size(20.dp)
          )
        }

        IconButton(
          onClick = { onOpenSettings() },
          modifier = Modifier.size(36.dp)
        ) {
          Icon(
            imageIcons.Settings,
            contentDescription = "Settings",
            tint = JarvisTextSecondary,
            modifier = Modifier.size(20.dp)
          )
        }

        IconButton(
          onClick = { onOpenProfile() },
          modifier = Modifier.size(36.dp)
            .border(1.dp, JarvisBorder, CircleShape)
        ) {
          Icon(
            imageIcons.Person,
            contentDescription = "Profile",
            tint = JarvisPrimary,
            modifier = Modifier.size(20.dp)
          )
        }
      }
    }
  }
}

// Helper object for clean icon selection
object imageIcons {
  val Api = Icons.Default.Code
  val Settings = Icons.Default.Settings
  val Person = Icons.Default.Person
  val Mic = Icons.Default.Mic
  val MicOff = Icons.Default.MicOff
  val Volume = Icons.Default.VolumeUp
  val Home = Icons.Default.Home
  val Chat = Icons.Default.Chat
  val Task = Icons.Default.Task
  val Search = Icons.Default.Search
  val Folder = Icons.Default.Folder
  val History = Icons.Default.History
  val Memory = Icons.Default.Memory
  val Apps = Icons.Default.Apps
  val Tools = Icons.Default.Build
  val Shield = Icons.Default.Security
  val Notifications = Icons.Default.Notifications
  val Network = Icons.Default.NetworkCheck
  val Battery = Icons.Default.BatteryFull
  val Play = Icons.Default.PlayArrow
  val Refresh = Icons.Default.Refresh
  val Check = Icons.Default.CheckCircle
  val Close = Icons.Default.Close
  val ArrowBack = Icons.Default.ArrowBack
}
