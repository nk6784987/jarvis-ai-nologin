package com.example.ai.providers

import android.util.Base64
import com.example.ai.*
import org.json.JSONArray
import org.json.JSONObject

/** Works with OpenAI, OpenRouter, Groq, Together, Mistral, DeepSeek, local servers, etc. */
class OpenAiCompatProvider(override val config: ProviderConfig) : AIProvider {
  private fun base() = config.baseUrl.trimEnd('/')
  private fun h(key: String) = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json")

  override suspend fun validate(apiKey: String) { Http.get("${base()}/models", h(apiKey)) }

  override suspend fun listModels(apiKey: String): List<ModelInfo> {
    val arr = Http.get("${base()}/models", h(apiKey)).optJSONArray("data") ?: JSONArray()
    return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
      val id = o.optString("id").ifBlank { return@mapNotNull null }
      val pricing = o.optJSONObject("pricing")
      ModelInfo(
        providerId = config.id, id = id,
        contextWindow = o.optInt("context_length", 0).takeIf { it > 0 } ?: CapabilityHeuristics.context(id),
        supportsVision = CapabilityHeuristics.vision(id) ||
          (o.optJSONObject("architecture")?.optString("modality")?.contains("image") == true),
        supportsTools = CapabilityHeuristics.tools(id),
        capabilitySource = if (o.has("context_length")) "provider" else "heuristic",
        inputCostPerMTok = pricing?.optString("prompt")?.toDoubleOrNull()?.times(1_000_000),
        outputCostPerMTok = pricing?.optString("completion")?.toDoubleOrNull()?.times(1_000_000)
      )
    }
  }

  override suspend fun generate(apiKey: String, model: ModelInfo, request: AIRequest): String {
    val msgs = JSONArray()
    request.system?.let { msgs.put(JSONObject().put("role", "system").put("content", it)) }
    request.turns.forEach { t ->
      if (t.imageJpeg != null) {
        val b64 = Base64.encodeToString(t.imageJpeg, Base64.NO_WRAP)
        msgs.put(JSONObject().put("role", t.role).put("content", JSONArray()
          .put(JSONObject().put("type", "text").put("text", t.text))
          .put(JSONObject().put("type", "image_url").put("image_url",
            JSONObject().put("url", "data:image/jpeg;base64,$b64")))))
      } else msgs.put(JSONObject().put("role", t.role).put("content", t.text))
    }
    val body = JSONObject().put("model", model.id).put("messages", msgs)
      .put("max_tokens", request.maxTokens).put("temperature", request.temperature)
    if (request.preferJson) body.put("response_format", JSONObject().put("type", "json_object"))
    val resp = Http.postJson("${base()}/chat/completions", h(apiKey), body)
    return resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
      ?.takeIf { it.isNotBlank() } ?: throw AiFailure.Server("Empty completion")
  }

  override suspend fun probeToolSupport(apiKey: String, model: ModelInfo): Boolean = try {
    val tool = JSONObject().put("type", "function").put("function", JSONObject()
      .put("name", "ping").put("description", "ping")
      .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
    val body = JSONObject().put("model", model.id).put("max_tokens", 16)
      .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
      .put("tools", JSONArray().put(tool))
    Http.postJson("${base()}/chat/completions", h(apiKey), body); true
  } catch (e: AiFailure.BadRequest) { false } catch (e: AiFailure) { throw e }
}
