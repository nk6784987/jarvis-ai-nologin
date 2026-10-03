package com.example.ai

/**
 * Scores models on measured + declared facts, not on the name alone:
 * availability, response success, latency, error rate, context, vision, tools, cost, user preference.
 */
class ModelSelector {
  data class Scored(val model: ModelInfo, val score: Double, val reasons: List<String>)

  fun rank(
    candidates: List<ModelInfo>, request: AIRequest,
    health: (String) -> ModelHealth?, preferredKey: String?
  ): List<Scored> = candidates.mapNotNull { m ->
    val h = health(m.key)
    if (h?.authFailed == true) return@mapNotNull null                      // bad key: never route here
    if (request.requireVision && !m.supportsVision) return@mapNotNull null // hard capability gate
    if (request.minContextTokens > 0 && (m.contextWindow ?: 0) in 1 until request.minContextTokens) return@mapNotNull null
    if (h != null && h.successes + h.failures >= 3 && h.errorRate > 0.6) return@mapNotNull null
    val r = mutableListOf<String>(); var s = 0.0
    when {
      h == null -> { s += 5; r += "untested" }
      h.responded -> { s += 40; r += "responded" }
      else -> { s -= 20; r += "no response yet" }
    }
    h?.avgLatencyMs?.let { l -> val sc = (15 - l / 400.0).coerceIn(0.0, 15.0); s += sc; r += "${l}ms" }
    h?.let { s -= it.errorRate * 30 }
    if (m.supportsTools) s += if (h?.toolsVerified == true) 10 else 4
    if (request.requireVision && m.supportsVision) s += 10
    m.contextWindow?.let { s += (it / 200_000.0).coerceAtMost(1.0) * 6 }
    when (request.task) {
      TaskKind.FAST_CLASSIFY -> h?.avgLatencyMs?.let { s += (8 - it / 500.0).coerceIn(0.0, 8.0) }
      TaskKind.PLANNING -> if ((m.contextWindow ?: 0) >= 100_000) s += 4
      else -> {}
    }
    m.inputCostPerMTok?.let { c -> s -= (c / 10.0).coerceIn(0.0, 6.0); r += "cost" }
    if (m.key == preferredKey) { s += 25; r += "user preferred" }
    Scored(m, s, r)
  }.sortedByDescending { it.score }
}
