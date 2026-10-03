package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun HudPanel(
  title: String,
  modifier: Modifier = Modifier,
  actionContent: @Composable (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = JarvisSurface.copy(alpha = 0.75f),
    shape = RoundedCornerShape(16.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Box(
            modifier = Modifier
              .size(6.dp, 16.dp)
              .background(JarvisPrimary, RoundedCornerShape(2.dp))
          )
          Text(
            text = title.uppercase(),
            color = JarvisPrimary,
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 1.5.sp
          )
        }
        if (actionContent != null) {
          actionContent()
        }
      }
      content()
    }
  }
}
