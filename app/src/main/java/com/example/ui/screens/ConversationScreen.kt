package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ChatMessage
import com.example.ui.components.HudPanel
import com.example.ui.components.imageIcons
import com.example.ui.theme.*

@Composable
fun ConversationScreen(
  messages: List<ChatMessage>,
  onSendMessage: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  var input by remember { mutableStateOf("") }

  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.SpaceBetween
  ) {
    HudPanel(
      title = "Neural Conversation Matrix",
      modifier = Modifier.weight(1f)
    ) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        items(messages) { msg ->
          ChatBubble(message = msg)
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Input row
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        placeholder = { Text("Transmit query to JARVIS...", color = JarvisTextSecondary) },
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
          if (input.isNotBlank()) {
            onSendMessage(input)
            input = ""
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

@Composable
fun ChatBubble(message: ChatMessage) {
  val alignment = if (message.isUser) Alignment.End else Alignment.Start
  val bgColor = if (message.isUser) JarvisSurfaceVariant else JarvisSurface
  val borderColor = if (message.isUser) JarvisSecondary else JarvisBorder
  val textColor = if (message.isUser) JarvisPrimary else JarvisTextPrimary

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = alignment
  ) {
    Surface(
      color = bgColor,
      shape = RoundedCornerShape(12.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
      modifier = Modifier.widthIn(max = 300.dp)
    ) {
      Column(
        modifier = Modifier.padding(12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = if (message.isUser) "YOU" else "J.A.R.V.I.S.",
            color = if (message.isUser) JarvisSecondary else JarvisAccent,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            letterSpacing = 1.sp
          )
          Text(
            text = message.timestamp,
            color = JarvisTextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = message.text,
          color = textColor,
          style = MaterialTheme.typography.bodyMedium
        )
      }
    }
  }
}
