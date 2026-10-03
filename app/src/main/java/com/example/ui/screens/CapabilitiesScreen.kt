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
import com.example.skills.SkillDefinition
import com.example.ui.components.HudPanel
import com.example.ui.components.imageIcons
import com.example.ui.theme.*

@Composable
fun CapabilitiesScreen(
  skills: List<SkillDefinition>,
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
        text = "DYNAMIC SKILL & TOOL REGISTRY",
        color = JarvisPrimary,
        style = MaterialTheme.typography.titleMedium,
        letterSpacing = 1.5.sp
      )
      Spacer(modifier = Modifier.width(36.dp))
    }

    HudPanel(
      title = "Registered Capabilities & Health",
      modifier = Modifier.weight(1f)
    ) {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        items(skills) { skill ->
          Surface(
            color = JarvisSurfaceVariant,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(text = skill.name, color = JarvisTextPrimary, style = MaterialTheme.typography.titleSmall)
                Text(text = skill.healthStatus.name, color = if (skill.healthStatus.name == "AVAILABLE") JarvisPrimary else JarvisError, style = MaterialTheme.typography.labelSmall)
              }
              Text(text = skill.description, color = JarvisTextSecondary, style = MaterialTheme.typography.bodySmall)
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(text = "Safety: ${skill.safetyLevel}", color = JarvisAccent, style = MaterialTheme.typography.labelSmall)
                Text(text = "v${skill.version}", color = JarvisTextSecondary, style = MaterialTheme.typography.labelSmall)
              }
            }
          }
        }
      }
    }
  }
}
