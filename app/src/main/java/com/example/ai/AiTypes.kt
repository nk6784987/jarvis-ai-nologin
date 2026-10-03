package com.example.ai

enum class ProviderType { OPENAI_COMPATIBLE, GEMINI, ANTHROPIC }

enum class TaskKind { CHAT, PLANNING, VISION, FAST_CLASSIFY, SUMMARIZE }

data class ProviderConfig(
  val id: String,
  val name: String,
  val type: ProviderType,
  val baseUrl: String,
  /** Models the user wants to use. Empty = auto-discover through the provider's model list. */
  val requestedModels: List<String> = emptyList(),
  val enabled: Boolean = true
)

data class ModelInfo(
  val providerId: String,
  val id: String,
  val displayName: String = id,
  val contextWindow: Int? = null,
  val supportsVision: Boolean = false,
  val supportsTools: Boolean = false,
  val capabilitySource: String = "heuristic",
  val inputCostPerMTok: Double? = null,
  val outputCostPerMTok: Double? = null
) { val key: String get() = "$providerId::$id" }

data class ChatTurn(val role: String /* user | assistant */, val text: String, val imageJpeg: ByteArray? = null)

data class AIRequest(
  val system: String? = null,
  val turns: List<ChatTurn>,
  val task: TaskKind = TaskKind.CHAT,
  val maxTokens: Int = 1024,
  val temperature: Double = 0.3,
  val requireVision: Boolean = false,
  val minContextTokens: Int = 0,
  val preferJson: Boolean = false
)

data class AIResponse(
  val text: String,
  val providerId: String,
  val modelId: String,
  val latencyMs: Long,
  val attempts: List<String> = emptyList()
)

sealed class AiFailure(message: String) : Exception(message) {
  class Auth(msg: String) : AiFailure(msg)
  class RateLimited(msg: String) : AiFailure(msg)
  class Network(msg: String) : AiFailure(msg)
  class BadRequest(msg: String) : AiFailure(msg)
  class Server(msg: String) : AiFailure(msg)
  class NoModelAvailable(msg: String) : AiFailure(msg)
  class Unsupported(msg: String) : AiFailure(msg)
}

interface AIProvider {
  val config: ProviderConfig
  /** Cheap credential + connectivity check. Throws AiFailure on problem. */
  suspend fun validate(apiKey: String)
  suspend fun listModels(apiKey: String): List<ModelInfo>
  suspend fun generate(apiKey: String, model: ModelInfo, request: AIRequest): String
  /** Real probe: does the endpoint accept a tool/function definition for this model? */
  suspend fun probeToolSupport(apiKey: String, model: ModelInfo): Boolean
}
