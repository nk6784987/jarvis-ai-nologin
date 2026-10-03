package com.example.ai

import android.content.Context
import com.example.security.SecureStore
import org.json.JSONArray
import org.json.JSONObject

/** Provider metadata in app-private prefs; API keys only in SecureStore. */
class ProviderStore(context: Context, private val secure: SecureStore) {
  private val prefs = context.getSharedPreferences("jarvis_ai_providers", Context.MODE_PRIVATE)

  fun load(): List<ProviderConfig> {
    val arr = runCatching { JSONArray(prefs.getString("providers", "[]")) }.getOrDefault(JSONArray())
    return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
      ProviderConfig(o.getString("id"), o.getString("name"), ProviderType.valueOf(o.getString("type")),
        o.getString("baseUrl"),
        o.optJSONArray("models")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        o.optBoolean("enabled", true))
    }
  }

  fun save(list: List<ProviderConfig>) {
    val arr = JSONArray()
    list.forEach { p -> arr.put(JSONObject().put("id", p.id).put("name", p.name).put("type", p.type.name)
      .put("baseUrl", p.baseUrl).put("models", JSONArray(p.requestedModels)).put("enabled", p.enabled)) }
    prefs.edit().putString("providers", arr.toString()).apply()
  }

  fun setKey(providerId: String, key: String) = secure.putSecret("ai_key_$providerId", key.trim())
  fun getKey(providerId: String): String? = secure.getSecret("ai_key_$providerId")
  fun removeProvider(providerId: String) { secure.removeSecret("ai_key_$providerId"); save(load().filterNot { it.id == providerId }) }
  var preferredModelKey: String?
    get() = prefs.getString("preferred", null)
    set(v) { prefs.edit().putString("preferred", v).apply() }
}
