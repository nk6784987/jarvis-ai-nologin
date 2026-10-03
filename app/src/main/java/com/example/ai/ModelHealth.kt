package com.example.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ModelHealth(
  val modelKey: String,
  val checkedAt: Long = 0L,
  val available: Boolean = false,
  val responded: Boolean = false,
  val toolsVerified: Boolean? = null,
  val lastLatencyMs: Long? = null,
  val avgLatencyMs: Long? = null,
  val successes: Int = 0,
  val failures: Int = 0,
  val lastError: String? = null,
  val authFailed: Boolean = false
) {
  val errorRate: Double get() = if (successes + failures == 0) 0.0 else failures.toDouble() / (successes + failures)
}

/** Rolling runtime stats: every real call (health probe or user request) feeds this. */
class ModelHealthChecker(private val providers: () -> Map<String, AIProvider>, private val keyOf: (String) -> String?) {
  private val state = MutableStateFlow<Map<String, ModelHealth>>(emptyMap())
  val health: StateFlow<Map<String, ModelHealth>> = state.asStateFlow()

  fun healthOf(key: String): ModelHealth? = state.value[key]

  fun record(key: String, ok: Boolean, latencyMs: Long?, error: String?, auth: Boolean = false) {
    val old = state.value[key] ?: ModelHealth(key)
    val n = old.successes + old.failures
    val avg = if (ok && latencyMs != null)
      (((old.avgLatencyMs ?: latencyMs) * minOf(n, 9)) + latencyMs) / (minOf(n, 9) + 1) else old.avgLatencyMs
    state.value = state.value + (key to old.copy(
      checkedAt = System.currentTimeMillis(), available = ok || old.available && !auth, responded = ok || old.responded,
      lastLatencyMs = latencyMs ?: old.lastLatencyMs, avgLatencyMs = avg,
      successes = old.successes + if (ok) 1 else 0, failures = old.failures + if (ok) 0 else 1,
      lastError = if (ok) null else error, authFailed = auth))
  }

  /** Full check: credential/connectivity -> model availability -> real response test -> tool probe. */
  suspend fun check(model: ModelInfo): ModelHealth {
    val provider = providers()[model.providerId]
    val key = keyOf(model.providerId)
    if (provider == null || key.isNullOrBlank()) {
      record(model.key, false, null, "API key missing"); return state.value[model.key]!!
    }
    val t0 = System.currentTimeMillis()
    try {
      val reply = provider.generate(key, model, AIRequest(
        system = "Reply with exactly: OK", turns = listOf(ChatTurn("user", "ping")), maxTokens = 8, temperature = 0.0))
      val lat = System.currentTimeMillis() - t0
      record(model.key, reply.isNotBlank(), lat, if (reply.isBlank()) "empty reply" else null)
      val tools = try { provider.probeToolSupport(key, model) } catch (_: AiFailure) { null }
      state.value = state.value + (model.key to state.value[model.key]!!.copy(toolsVerified = tools))
    } catch (e: AiFailure) {
      record(model.key, false, null, e.message, auth = e is AiFailure.Auth)
    }
    return state.value[model.key]!!
  }
}
