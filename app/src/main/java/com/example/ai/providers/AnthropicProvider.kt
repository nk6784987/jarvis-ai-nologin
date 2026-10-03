package com.example.ai.providers

import android.util.Base64
import com.example.ai.*
import org.json.JSONArray
import org.json.JSONObject

class AnthropicProvider(override val config: ProviderConfig) : AIProvider {
  private fun base() = config.baseUrl.trimEnd('/').ifBlank { "https://api.anthropic.com/v1" }
  private fun h(key: String) = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01", "content-type" to "application/json")

  override suspend fun validate(apiKey: String) { Http.get("${base()}/models?limit=1", h(apiKey)) }

  override suspend fun listModels(apiKey: String): List<ModelInfo> {
    val arr = Http.get("${base()}/models?limit=100", h(apiKey)).optJSONArray("data") ?: JSONArray()
    return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
      val id = o.optString("id")
      ModelInfo(config.id, id, o.optString("display_name", id),
        contextWindow = CapabilityHeuristics.context(id), supportsVision = true, supportsTools = true,
        capabilitySource = "heuristic")
    }
  }

  override suspend fun generate(apiKey: String, model: ModelInfo, request: AIRequest): String {
    val msgs = JSONArray()
    request.turns.forEach { t ->
      val content: Any = if (t.imageJpeg != null) JSONArray()
        .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64")
          .put("media_type", "image/jpeg").put("data", Base64.encodeToString(t.imageJpeg, Base64.NO_WRAP))))
        .put(JSONObject().put("type", "text").put("text", t.text)) else t.text
      msgs.put(JSONObject().put("role", t.role).put("content", content))
    }
    val body = JSONObject().put("model", model.id).put("max_tokens", request.maxTokens)
      .put("temperature", request.temperature).put("messages", msgs)
    request.system?.let { body.put("system", it) }
    val resp = Http.postJson("${base()}/messages", h(apiKey), body)
    val arr = resp.optJSONArray("content") ?: JSONArray()
    val sb = StringBuilder()
    for (i in 0 until arr.length()) arr.getJSONObject(i).takeIf { it.optString("type") == "text" }?.let { sb.append(it.optString("text")) }
    return sb.toString().ifBlank { throw AiFailure.Server("Empty completion") }
  }

  override suspend fun probeToolSupport(apiKey: String, model: ModelInfo): Boolean = try {
    val tool = JSONObject().put("name", "ping").put("description", "ping")
      .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject()))
    val body = JSONObject().put("model", model.id).put("max_tokens", 16)
      .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
      .put("tools", JSONArray().put(tool))
    Http.postJson("${base()}/messages", h(apiKey), body); true
  } catch (e: AiFailure.BadRequest) { false }
}

object CapabilityHeuristics {
  fun vision(id: String): Boolean { val s = id.lowercase()
    return listOf("vision", "gpt-4o", "gpt-4.1", "gpt-5", "gemini", "claude", "llava", "pixtral", "qwen-vl", "qwen2-vl", "gemma-3", "llama-4").any { s.contains(it) } }
  fun tools(id: String): Boolean { val s = id.lowercase()
    return listOf("gpt-4", "gpt-5", "gpt-3.5", "o1", "o3", "o4", "gemini", "claude", "mistral-large", "llama-3.1", "llama-3.3", "llama-4", "qwen", "deepseek-chat").any { s.contains(it) } }
  fun context(id: String): Int? { val s = id.lowercase()
    return when {
      s.contains("gemini-1.5-pro") -> 2_000_000
      s.contains("gemini") -> 1_000_000
      s.contains("claude") -> 200_000
      s.contains("gpt-4.1") -> 1_000_000
      s.contains("gpt-4o") || s.contains("gpt-5") -> 128_000
      s.contains("llama-3.1") || s.contains("llama-3.3") -> 128_000
      else -> null
    } }
}
