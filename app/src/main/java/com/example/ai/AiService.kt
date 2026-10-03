package com.example.ai

import com.example.ai.providers.AnthropicProvider
import com.example.ai.providers.GeminiProvider
import com.example.ai.providers.OpenAiCompatProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProviderStatus(val provider: ProviderConfig, val keyPresent: Boolean, val valid: Boolean?, val message: String?, val models: List<ModelInfo>)

/** Facade: ModelRouter + ModelProvider registry. Never fabricates a reply; throws AiFailure.NoModelAvailable. */
class AiService(private val store: ProviderStore) {
  private val selector = ModelSelector()
  private var providers: Map<String, AIProvider> = build(store.load())
  private val checker = ModelHealthChecker({ providers }, { store.getKey(it) })
  val health = checker.health

  private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
  val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()
  private val _status = MutableStateFlow<List<ProviderStatus>>(emptyList())
  val status: StateFlow<List<ProviderStatus>> = _status.asStateFlow()

  val hasUsableProvider: Boolean get() = providers.values.any { it.config.enabled && !store.getKey(it.config.id).isNullOrBlank() }

  private fun build(cfgs: List<ProviderConfig>) = cfgs.filter { it.enabled }.associate { c ->
    c.id to when (c.type) {
      ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatProvider(c)
      ProviderType.GEMINI -> GeminiProvider(c)
      ProviderType.ANTHROPIC -> AnthropicProvider(c)
    }
  }

  fun configs(): List<ProviderConfig> = store.load()

  suspend fun addProvider(cfg: ProviderConfig, apiKey: String) {
    store.save(store.load().filterNot { it.id == cfg.id } + cfg)
    store.setKey(cfg.id, apiKey)
    providers = build(store.load())
    refresh(cfg.id)
  }

  suspend fun removeProvider(id: String) {
    store.removeProvider(id); providers = build(store.load()); _models.value = _models.value.filterNot { it.providerId == id }
    publishStatus()
  }

  fun setPreferred(modelKey: String?) { store.preferredModelKey = modelKey }

  /** validate key -> connectivity -> list models -> response test -> tool probe, for one/all providers. */
  suspend fun refresh(only: String? = null) = coroutineScope {
    val targets = providers.values.filter { only == null || it.config.id == only }
    val results = targets.map { p -> async(Dispatchers.IO) { discover(p) } }.awaitAll()
    val keep = _models.value.filterNot { m -> targets.any { it.config.id == m.providerId } }
    _models.value = keep + results.flatMap { it.models }
    val oldStatus = _status.value.filterNot { s -> targets.any { it.config.id == s.provider.id } }
    _status.value = oldStatus + results
  }

  private suspend fun discover(p: AIProvider): ProviderStatus {
    val key = store.getKey(p.config.id)
    if (key.isNullOrBlank()) return ProviderStatus(p.config, false, null, "API key missing", emptyList())
    return try {
      p.validate(key)
      val listed = runCatching { p.listModels(key) }.getOrDefault(emptyList())
      val wanted = p.config.requestedModels
      val models = if (wanted.isEmpty()) listed.take(40) else wanted.map { w ->
        listed.firstOrNull { it.id == w } ?: ModelInfo(p.config.id, w, capabilitySource = "user-declared",
          supportsVision = com.example.ai.providers.CapabilityHeuristics.vision(w),
          supportsTools = com.example.ai.providers.CapabilityHeuristics.tools(w),
          contextWindow = com.example.ai.providers.CapabilityHeuristics.context(w))
      }
      // Real response test on at most 3 candidate models to bound cost.
      val sample = if (wanted.isEmpty()) models.sortedByDescending { it.contextWindow ?: 0 }.take(3) else models
      sample.forEach { checker.check(it) }
      ProviderStatus(p.config, true, true, null, models)
    } catch (e: AiFailure) {
      ProviderStatus(p.config, true, false, e.message, emptyList())
    }
  }

  private fun publishStatus() { _status.value = _status.value.filter { s -> providers.containsKey(s.provider.id) } }

  /** Primary -> next compatible model -> next provider -> graceful typed failure. */
  suspend fun complete(request: AIRequest): AIResponse {
    if (_models.value.isEmpty() && hasUsableProvider) refresh()
    var ranked = selector.rank(_models.value, request, { checker.healthOf(it) }, store.preferredModelKey)
    if (ranked.isEmpty()) throw AiFailure.NoModelAvailable(
      if (!hasUsableProvider) "Koi AI provider/API key configure nahi hai. Settings > API Configuration mein add karein."
      else "Koi compatible model available nahi (vision/context/health filters ke baad).")
    val attempts = mutableListOf<String>()
    val tried = mutableSetOf<String>()
    var lastError: AiFailure? = null
    var perProviderFails = mutableMapOf<String, Int>()
    for (round in 0 until 2) {
      for (s in ranked) {
        if (!tried.add(s.model.key + "#$round")) continue
        if ((perProviderFails[s.model.providerId] ?: 0) >= 2 && round == 0) continue // skip to next provider
        val provider = providers[s.model.providerId] ?: continue
        val key = store.getKey(s.model.providerId) ?: continue
        val t0 = System.currentTimeMillis()
        try {
          val text = provider.generate(key, s.model, request)
          val lat = System.currentTimeMillis() - t0
          checker.record(s.model.key, true, lat, null)
          return AIResponse(text, s.model.providerId, s.model.id, lat, attempts)
        } catch (e: CancellationException) { throw e
        } catch (e: AiFailure) {
          lastError = e
          checker.record(s.model.key, false, null, e.message, auth = e is AiFailure.Auth)
          attempts += "${s.model.key}: ${e.message?.take(120)}"
          perProviderFails[s.model.providerId] = (perProviderFails[s.model.providerId] ?: 0) + 1
          if (e is AiFailure.RateLimited) delay(800)
        }
      }
      if (lastError is AiFailure.Auth || lastError is AiFailure.BadRequest) break
      ranked = selector.rank(_models.value, request, { checker.healthOf(it) }, store.preferredModelKey)
    }
    throw AiFailure.NoModelAvailable("Saare models fail hue. " + attempts.takeLast(3).joinToString(" | "))
  }
}
