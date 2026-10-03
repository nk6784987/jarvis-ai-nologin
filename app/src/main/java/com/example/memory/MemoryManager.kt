package com.example.memory

import com.example.ai.AIRequest
import com.example.ai.AiFailure
import com.example.ai.AiService
import com.example.ai.ChatTurn
import com.example.ai.TaskKind
import com.example.data.LocalStore
import com.example.data.UserMemoryItem

/**
 * Layers: temporary context (RAM, last turns) / long-term memory (on-device store) /
 * conversation summaries / task history / preferences. Retrieval is relevance-ranked top-K - the
 * whole database is never sent to the model.
 */
class MemoryManager(private val store: LocalStore, private val ai: AiService) {
  private val temp = ArrayDeque<ChatTurn>()
  private val maxTemp = 12

  fun addTurn(role: String, text: String) { temp.addLast(ChatTurn(role, text)); while (temp.size > maxTemp) temp.removeFirst() }
  fun recentTurns(): List<ChatTurn> = temp.toList()
  fun clearTemporary() = temp.clear()

  private fun tokens(s: String) = Regex("[\\p{L}\\p{N}]{2,}").findAll(s.lowercase()).map { it.value }.toSet()

  fun relevant(query: String, k: Int = 5): List<UserMemoryItem> {
    val q = tokens(query); if (q.isEmpty()) return emptyList()
    val all = store.memories.value
    val df = HashMap<String, Int>(); all.forEach { m -> tokens(m.content + " " + m.category).forEach { df[it] = (df[it] ?: 0) + 1 } }
    return all.map { m ->
      val t = tokens(m.content + " " + m.category)
      val score = q.intersect(t).sumOf { w -> Math.log(1.0 + all.size.toDouble() / (df[w] ?: 1)) } + if (m.category == "preference") 0.3 else 0.0
      m to score
    }.filter { it.second > 0.0 }.sortedByDescending { it.second }.take(k).map { it.first }
  }

  fun remember(content: String, category: String = "fact"): Boolean = store.addMemory(category, content.trim())
  fun forget(needle: String): Int = store.forgetMatching(needle)

  /** Everything stored about the user, for "tumhe mere baare mein kya yaad hai?" */
  fun recallAll(): List<UserMemoryItem> = store.memories.value

  fun savePreference(key: String, value: String) { store.setPreference(key, value) }

  fun logTask(goal: String, outcome: String, steps: Int, ok: Boolean) {
    store.appendLog("taskHistory", mapOf("goal" to goal, "outcome" to outcome.take(400), "steps" to steps, "ok" to ok, "at" to System.currentTimeMillis()))
  }

  /** Summarise the finished conversation window with the real model; skipped (not faked) if no model works. */
  suspend fun summariseAndStore(): Boolean {
    if (temp.size < 4) return false
    val transcript = temp.joinToString("\n") { "${it.role}: ${it.text.take(300)}" }
    return try {
      val s = ai.complete(AIRequest(system = "Summarise in <=3 short lines the durable facts/intents. No pleasantries.", turns = listOf(ChatTurn("user", transcript)), task = TaskKind.SUMMARIZE, maxTokens = 200)).text
      store.appendLog("conversationSummaries", mapOf("summary" to s, "at" to System.currentTimeMillis())); true
    } catch (e: AiFailure) { false }
  }
}
