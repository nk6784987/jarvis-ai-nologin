package com.example.viewmodel

import com.example.ai.ProviderType
import com.example.model.ApiServiceConfig
import com.example.permissions.Capability
import com.example.permissions.PermState

data class SearchProviderUi(val id: String, val name: String, val configured: Boolean, val needsCx: Boolean)

data class ProviderUi(val card: ApiServiceConfig, val providerId: String, val models: List<String>, val detail: String?)

data class ApiConfigUi(
  val accessibilityEnabled: Boolean = false,
  val screenCaptureActive: Boolean = false,
  val permissions: Map<Capability, PermState> = emptyMap(),
  val providers: List<ProviderUi> = emptyList(),
  val preferredModelKey: String? = null,
  val searchProviders: List<SearchProviderUi> = emptyList(),
  val busy: Boolean = false,
  val message: String? = null
)

/** Suspended agent request for a runtime permission / special-access screen. */
data class PermissionRequest(val capability: Capability, val reason: String)

/** Suspended agent request for explicit user approval of a SENSITIVE/CONSEQUENTIAL action. */
data class ConfirmationRequest(val description: String)

enum class ProviderTypeUi(val type: ProviderType, val label: String, val defaultUrl: String) {
  OPENAI(ProviderType.OPENAI_COMPATIBLE, "OpenAI-compatible", "https://api.openai.com/v1"),
  GEMINI(ProviderType.GEMINI, "Google Gemini", "https://generativelanguage.googleapis.com/v1beta"),
  ANTHROPIC(ProviderType.ANTHROPIC, "Anthropic", "https://api.anthropic.com/v1")
}
