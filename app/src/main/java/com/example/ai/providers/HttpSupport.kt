package com.example.ai.providers

import com.example.ai.AiFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object Http {
  val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build() // deliberately NO logging interceptor: request headers contain API keys

  private val JSON = "application/json".toMediaType()

  suspend fun postJson(url: String, headers: Map<String, String>, body: JSONObject): JSONObject =
    call(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
      .post(body.toString().toRequestBody(JSON)).build())

  suspend fun get(url: String, headers: Map<String, String>): JSONObject =
    call(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build())

  private suspend fun call(req: Request): JSONObject = withContext(Dispatchers.IO) {
    try {
      client.newCall(req).execute().use { r ->
        val text = r.body?.string().orEmpty()
        if (r.isSuccessful) return@use JSONObject(if (text.isBlank()) "{}" else text)
        val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
          ?: text.take(200)
        throw when (r.code) {
          401, 403 -> AiFailure.Auth("HTTP ${r.code}: $detail")
          429 -> AiFailure.RateLimited("HTTP 429: $detail")
          in 400..499 -> AiFailure.BadRequest("HTTP ${r.code}: $detail")
          else -> AiFailure.Server("HTTP ${r.code}: $detail")
        }
      }
    } catch (e: IOException) {
      throw AiFailure.Network(e.message ?: "network error")
    } catch (e: org.json.JSONException) {
      throw AiFailure.Server("Invalid JSON from provider")
    }
  }
}
