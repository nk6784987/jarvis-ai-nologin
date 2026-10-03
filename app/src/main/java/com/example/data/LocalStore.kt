package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class UserMemoryItem(val id: String = "", val category: String = "", val content: String = "", val timestamp: Long = 0L)

/**
 * On-device storage (no account, no cloud). Long-term memory, task history, conversation summaries
 * and preferences live in app-private SharedPreferences and survive restarts.
 */
class LocalStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("jarvis_local_store", Context.MODE_PRIVATE)

  private val _memories = MutableStateFlow(loadMemories())
  val memories: StateFlow<List<UserMemoryItem>> = _memories.asStateFlow()

  private fun loadMemories(): List<UserMemoryItem> = runCatching {
    val a = JSONArray(prefs.getString("memory", "[]"))
    (0 until a.length()).map { a.getJSONObject(it) }.map { UserMemoryItem(it.getString("id"), it.optString("category"), it.getString("content"), it.optLong("ts")) }
  }.getOrDefault(emptyList())

  private fun saveMemories(list: List<UserMemoryItem>) {
    _memories.value = list
    val a = JSONArray(); list.forEach { a.put(JSONObject().put("id", it.id).put("category", it.category).put("content", it.content).put("ts", it.timestamp)) }
    prefs.edit().putString("memory", a.toString()).apply()
  }

  fun addMemory(category: String, content: String): Boolean {
    if (content.isBlank()) return false
    val now = System.currentTimeMillis()
    saveMemories(_memories.value + UserMemoryItem("m$now", category, content.trim(), now)); return true
  }

  fun deleteMemory(id: String) = saveMemories(_memories.value.filterNot { it.id == id })
  fun clearAllMemory() = saveMemories(emptyList())

  fun forgetMatching(needle: String): Int {
    val n = needle.trim().lowercase(); if (n.isBlank()) return 0
    val hits = _memories.value.filter { it.content.lowercase().contains(n) || it.category.lowercase() == n }
    if (hits.isNotEmpty()) saveMemories(_memories.value - hits.toSet())
    return hits.size
  }

  fun setPreference(key: String, value: String) { prefs.edit().putString("pref_$key", value).apply() }
  fun getPreference(key: String): String? = prefs.getString("pref_$key", null)

  /** Capped append-only logs ("taskHistory", "conversationSummaries"). */
  fun appendLog(name: String, entry: Map<String, Any>, cap: Int = 200) {
    val a = runCatching { JSONArray(prefs.getString("log_$name", "[]")) }.getOrDefault(JSONArray())
    a.put(JSONObject(entry))
    val trimmed = JSONArray(); for (i in maxOf(0, a.length() - cap) until a.length()) trimmed.put(a.get(i))
    prefs.edit().putString("log_$name", trimmed.toString()).apply()
  }

  fun clearEverything() { prefs.edit().clear().apply(); _memories.value = emptyList() }
}
