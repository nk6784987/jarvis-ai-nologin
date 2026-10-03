package com.example.ai.providers

import android.util.Base64
import com.example.ai.*
import org.json.JSONArray
import org.json.JSONObject

/** Google Generative Language REST API (API-key auth). */
class GeminiProvider(override val config: ProviderConfig) : AIProvider {
  private fun base() = config.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
  private fun h(key: String) = mapOf("x-goog-api-key" to key, "Content-Type" to "application/json")

  override suspend fun validate(apiKey: String) { Http.get("${base()}/models?pageSize=1", h(apiKey)) }

  override suspend fun listModels(apiKey: String): List<ModelInfo> {
    val arr = Http.get("${base()}/models?pageSize=200", h(apiKey)).optJSONArray("models") ?: JSONArray()
    return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
      val methods = o.optJSONArray("supportedGenerationMethods") ?: return@mapNotNull null
      if ((0 until methods.length()).none { methods.getString(it) == "generateContent" }) return@mapNotNull null
      val id = o.optString("name").removePrefix("models/")
      ModelInfo(config.id, id, o.optString("displayName", id),
        contextWindow = o.optInt("inputTokenLimit", 0).takeIf { it > 0 },
        supportsVision = !id.contains("embedding") && !id.contains("tts") && !id.contains("imagen"),
        supportsTools = CapabilityHeuristics.tools(id), capabilitySource = "provider")
    }
  }

  override suspend fun generate(apiKey: String, model: ModelInfo, request: AIRequest): String {
    val contents = JSONArray()
    request.turns.forEach { t ->
      val parts = JSONArray().put(JSONObject().put("text", t.text))
      t.imageJpeg?.let { parts.put(JSONObject().put("inline_data", JSONObject()
        .put("mime_type", "image/jpeg").put("data", Base64.encodeToString(it, Base64.NO_WRAP)))) }
      contents.put(JSONObject().put("role", if (t.role == "assistant") "model" else "user").put("parts", parts))
    }
    val gen = JSONObject().put("maxOutputTokens", request.maxTokens).put("temperature", request.temperature)
    if (request.preferJson) gen.put("responseMimeType", "application/json")
    val body = JSONObject().put("contents", contents).put("generationConfig", gen)
    request.system?.let { body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it)))) }
    val resp = Http.postJson("${base()}/models/${model.id}:generateContent", h(apiKey), body)
    val parts = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
    val sb = StringBuilder()
    if (parts != null) for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
    return sb.toString().ifBlank { throw AiFailure.Server("Empty/blocked response") }
  }

  override suspend fun probeToolSupport(apiKey: String, model: ModelInfo): Boolean = try {
    val decl = JSONObject().put("name", "ping").put("description", "ping")
    val body = JSONObject()
      .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "ping")))))
      .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(decl))))
      .put("generationConfig", JSONObject().put("maxOutputTokens", 16))
    Http.postJson("${base()}/models/${model.id}:generateContent", h(apiKey), body); true
  } catch (e: AiFailure.BadRequest) { false }
}
