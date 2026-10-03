package com.example.ui.screens

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
import com.example.agent.AgentStatus
import com.example.model.ApiServiceConfig
import com.example.model.ApiStatus
import com.example.permissions.Capability
import com.example.permissions.PermState
import com.example.ui.components.HudPanel
import com.example.ui.components.imageIcons
import com.example.ui.theme.*
import com.example.viewmodel.ApiConfigUi
import com.example.viewmodel.ProviderTypeUi

@Composable
fun ApiConfigScreen(
  ui: ApiConfigUi,
  agentStatus: AgentStatus,
  onOpenAccessibility: () -> Unit,
  onRequestPermission: (Capability) -> Unit,
  onStartScreenCapture: () -> Unit,
  onAddProvider: (name: String, type: ProviderTypeUi, baseUrl: String, models: String, apiKey: String) -> Unit,
  onRemoveProvider: (String) -> Unit,
  onTestProvider: (String) -> Unit,
  onSetPreferred: (String?) -> Unit,
  onSaveSearchKey: (providerId: String, key: String, cx: String) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  var showAdd by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      IconButton(onClick = onBack) { Icon(imageIcons.ArrowBack, contentDescription = "Back", tint = JarvisPrimary) }
      Text("API, DEVICE & PERMISSIONS", color = JarvisPrimary, style = MaterialTheme.typography.titleMedium, letterSpacing = 1.5.sp)
      Spacer(Modifier.width(36.dp))
    }

    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      item {
        Surface(color = JarvisSurfaceVariant, shape = RoundedCornerShape(12.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, if (ui.accessibilityEnabled) JarvisAccent else JarvisSecondary)) {
          Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
              Text("Android Accessibility Agent", color = JarvisTextPrimary, style = MaterialTheme.typography.bodyMedium)
              Text("Status: ${agentStatus.name}" + if (!ui.accessibilityEnabled) " - service OFF" else "", color = if (ui.accessibilityEnabled) JarvisAccent else JarvisTextSecondary, style = MaterialTheme.typography.bodySmall)
              if (!ui.accessibilityEnabled) Text("Settings > Accessibility > JARVIS enable karein. Bina iske app control/screen reading kaam nahi karegi.", color = JarvisTextSecondary, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
            }
            Button(onClick = onOpenAccessibility, colors = ButtonDefaults.buttonColors(containerColor = JarvisPrimary), shape = RoundedCornerShape(8.dp)) {
              Text(if (ui.accessibilityEnabled) "SETTINGS" else "ENABLE", color = JarvisBackground, style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }

      item { Text("PERMISSION CENTER", color = JarvisPrimary, style = MaterialTheme.typography.labelMedium) }
      items(Capability.values().filter { it != Capability.ACCESSIBILITY && it != Capability.SCREEN_CAPTURE }.toList()) { cap ->
        val st = ui.permissions[cap] ?: PermState.DENIED
        Surface(color = JarvisSurfaceVariant, shape = RoundedCornerShape(10.dp)) {
          Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
              Text(cap.label, color = JarvisTextPrimary, style = MaterialTheme.typography.bodyMedium)
              Text(cap.why, color = JarvisTextSecondary, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
            }
            if (st == PermState.GRANTED) Text("GRANTED", color = JarvisAccent, style = MaterialTheme.typography.labelSmall)
            else TextButton(onClick = { onRequestPermission(cap) }) { Text(if (st == PermState.NEEDS_SETTINGS) "OPEN SETTINGS" else "GRANT", color = JarvisPrimary) }
          }
        }
      }
      item {
        Surface(color = JarvisSurfaceVariant, shape = RoundedCornerShape(10.dp)) {
          Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
              Text("Screen capture (MediaProjection)", color = JarvisTextPrimary, style = MaterialTheme.typography.bodyMedium)
              Text("Android 11+ par accessibility screenshot kaafi hai. Purane devices ke liye user consent se start hota hai.", color = JarvisTextSecondary, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
            }
            if (ui.screenCaptureActive) Text("ACTIVE", color = JarvisAccent, style = MaterialTheme.typography.labelSmall)
            else TextButton(onClick = onStartScreenCapture) { Text("START", color = JarvisPrimary) }
          }
        }
      }

      item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
          Text("AI PROVIDERS & MODELS", color = JarvisPrimary, style = MaterialTheme.typography.labelMedium)
          TextButton(onClick = { showAdd = !showAdd }) { Text(if (showAdd) "CLOSE" else "+ ADD", color = JarvisPrimary) }
        }
      }
      if (showAdd) item { AddProviderForm(onSubmit = { n, t, u, m, k -> onAddProvider(n, t, u, m, k); showAdd = false }) }
      if (ui.providers.isEmpty()) item { Text("Koi AI provider configure nahi hai. JARVIS bina AI ke sirf device tools ke bina soch ke jawab nahi de sakta - ek provider add karein.", color = JarvisTextSecondary, style = MaterialTheme.typography.bodySmall) }
      items(ui.providers) { p ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          ApiCard(api = p.card, onTest = { onTestProvider(p.providerId) })
          p.detail?.let { Text(it, color = JarvisError, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp) }
          p.models.take(6).forEach { mk ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              RadioButton(selected = ui.preferredModelKey == mk, onClick = { onSetPreferred(if (ui.preferredModelKey == mk) null else mk) })
              Text(mk.substringAfter("::"), color = JarvisTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
          }
          TextButton(onClick = { onRemoveProvider(p.providerId) }) { Text("REMOVE PROVIDER", color = JarvisError, style = MaterialTheme.typography.labelSmall) }
        }
      }

      item { Text("WEB SEARCH PROVIDERS", color = JarvisPrimary, style = MaterialTheme.typography.labelMedium) }
      items(ui.searchProviders) { sp ->
        var key by remember { mutableStateOf("") }; var cx by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("${sp.name} - ${if (sp.configured) "CONFIGURED" else "NOT CONFIGURED"}", color = if (sp.configured) JarvisAccent else JarvisTextSecondary, style = MaterialTheme.typography.bodyMedium)
          ApiKeyInput(label = "API key", value = key, onValueChange = { key = it })
          if (sp.needsCx) OutlinedTextField(value = cx, onValueChange = { cx = it }, label = { Text("Search engine ID (cx)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
          Button(onClick = { onSaveSearchKey(sp.id, key, cx); key = ""; cx = "" }, enabled = key.isNotBlank() && (!sp.needsCx || cx.isNotBlank()),
            colors = ButtonDefaults.buttonColors(containerColor = JarvisPrimary)) { Text("SAVE", color = JarvisBackground) }
        }
      }
      ui.message?.let { m -> item { Text(m, color = JarvisSecondary, style = MaterialTheme.typography.bodySmall) } }
    }
  }
}

@Composable
private fun AddProviderForm(onSubmit: (String, ProviderTypeUi, String, String, String) -> Unit) {
  var type by remember { mutableStateOf(ProviderTypeUi.OPENAI) }
  var name by remember { mutableStateOf("") }
  var url by remember { mutableStateOf(type.defaultUrl) }
  var models by remember { mutableStateOf("") }
  var key by remember { mutableStateOf("") }
  Surface(color = JarvisSurfaceVariant, shape = RoundedCornerShape(12.dp)) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ProviderTypeUi.values().forEach { t ->
          FilterChip(selected = type == t, onClick = { type = t; url = t.defaultUrl }, label = { Text(t.label, fontSize = 11.sp) })
        }
      }
      OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. OpenRouter)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(models, { models = it }, label = { Text("Models (comma-separated, blank = auto-discover)") }, modifier = Modifier.fillMaxWidth())
      ApiKeyInput(label = "API key (Keystore-encrypted, never synced)", value = key, onValueChange = { key = it })
      Button(onClick = { onSubmit(name.ifBlank { type.label }, type, url, models, key) }, enabled = key.isNotBlank() && url.isNotBlank(),
        colors = ButtonDefaults.buttonColors(containerColor = JarvisPrimary)) { Text("VALIDATE & ADD", color = JarvisBackground) }
    }
  }
}

@Composable
fun ApiCard(api: ApiServiceConfig, onTest: () -> Unit) {
  val statusColor = when (api.status) {
    ApiStatus.CONNECTED, ApiStatus.WORKING -> JarvisAccent
    ApiStatus.TESTING -> JarvisSecondary
    ApiStatus.NOT_CONNECTED -> JarvisTextSecondary
    ApiStatus.ERROR -> JarvisError
  }
  Surface(color = JarvisSurfaceVariant, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(api.name, color = JarvisTextPrimary, style = MaterialTheme.typography.bodyMedium)
          Surface(color = statusColor.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
            Text(api.status.name, color = statusColor, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
          }
        }
        Spacer(Modifier.height(2.dp))
        Text("${api.type}" + if (api.latencyMs > 0) " • measured latency: ${api.latencyMs}ms" else " • not measured", color = JarvisTextSecondary, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
      }
      IconButton(onClick = onTest, modifier = Modifier.size(36.dp)) { Icon(imageIcons.Refresh, contentDescription = "Re-test", tint = JarvisPrimary, modifier = Modifier.size(18.dp)) }
    }
  }
}
