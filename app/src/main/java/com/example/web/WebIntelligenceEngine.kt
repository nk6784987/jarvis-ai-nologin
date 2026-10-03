package com.example.web

import com.example.ai.AIRequest
import com.example.ai.AiFailure
import com.example.ai.AiService
import com.example.ai.ChatTurn
import com.example.ai.TaskKind
import com.example.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class SearchKind { WEB, IMAGE, VIDEO, NEWS }

data class SearchResult(
  val title: String,
  val url: String,
  val snippet: String,
  val source: String,
  val date: String?,
  val imageUrl: String? = null,
  val kind: SearchKind = SearchKind.WEB,
  val provider: String
)

class SearchFailure(val code: Code, message: String) : Exception(message) {
  enum class Code { NOT_CONFIGURED, AUTH, QUOTA, NETWORK, UNSUPPORTED, EMPTY, PARSE }
}

interface WebSearchProvider {
  val id: String
  val displayName: String
  val supports: Set<SearchKind>
  fun isConfigured(): Boolean
  suspend fun search(query: String, kind: SearchKind, count: Int = 8): List<SearchResult>
}

private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).followRedirects(true).build()

internal suspend fun httpGetString(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> = withContext(Dispatchers.IO) {
  try {
    http.newCall(Request.Builder().url(url).apply {
      header("User-Agent", "Mozilla/5.0 (Linux; Android 14) JARVIS/1.0"); headers.forEach { (k, v) -> header(k, v) }
    }.build()).execute().use { it.code to (it.body?.string().orEmpty()) }
  } catch (e: IOException) { throw SearchFailure(SearchFailure.Code.NETWORK, e.message ?: "network") }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun host(u: String) = runCatching { java.net.URI(u).host?.removePrefix("www.") }.getOrNull() ?: u

class BraveSearchProvider(private val secure: SecureStore) : WebSearchProvider {
  override val id = "brave"; override val displayName = "Brave Search API"
  override val supports = setOf(SearchKind.WEB, SearchKind.IMAGE, SearchKind.VIDEO, SearchKind.NEWS)
  override fun isConfigured() = !secure.getSecret("search_key_brave").isNullOrBlank()
  override suspend fun search(query: String, kind: SearchKind, count: Int): List<SearchResult> {
    val key = secure.getSecret("search_key_brave") ?: throw SearchFailure(SearchFailure.Code.NOT_CONFIGURED, "Brave API key set nahi hai")
    val path = when (kind) { SearchKind.WEB -> "web/search"; SearchKind.IMAGE -> "images/search"; SearchKind.VIDEO -> "videos/search"; SearchKind.NEWS -> "news/search" }
    val (code, body) = httpGetString("https://api.search.brave.com/res/v1/$path?q=${enc(query)}&count=$count", mapOf("X-Subscription-Token" to key, "Accept" to "application/json"))
    if (code == 401 || code == 403) throw SearchFailure(SearchFailure.Code.AUTH, "Brave key invalid")
    if (code == 429) throw SearchFailure(SearchFailure.Code.QUOTA, "Brave quota/rate limit")
    if (code !in 200..299) throw SearchFailure(SearchFailure.Code.NETWORK, "Brave HTTP $code")
    val j = try { JSONObject(body) } catch (e: Exception) { throw SearchFailure(SearchFailure.Code.PARSE, "Brave JSON invalid") }
    val arr = if (kind == SearchKind.WEB) j.optJSONObject("web")?.optJSONArray("results") else j.optJSONArray("results")
    val out = mutableListOf<SearchResult>()
    if (arr != null) for (i in 0 until arr.length()) {
      val o = arr.getJSONObject(i); val url = o.optString("url").ifBlank { continue }
      out += SearchResult(o.optString("title"), url, o.optString("description").ifBlank { o.optString("snippet") }, host(url),
        o.optString("age").ifBlank { o.optString("page_age") }.ifBlank { null },
        imageUrl = o.optJSONObject("properties")?.optString("url")?.ifBlank { null } ?: o.optJSONObject("thumbnail")?.optString("src")?.ifBlank { null },
        kind = kind, provider = id)
    }
    return out
  }
}

class GoogleCseProvider(private val secure: SecureStore) : WebSearchProvider {
  override val id = "google_cse"; override val displayName = "Google Programmable Search"
  override val supports = setOf(SearchKind.WEB, SearchKind.IMAGE)
  override fun isConfigured() = !secure.getSecret("search_key_google_cse").isNullOrBlank() && !secure.getSecret("search_cx_google_cse").isNullOrBlank()
  override suspend fun search(query: String, kind: SearchKind, count: Int): List<SearchResult> {
    if (kind !in supports) throw SearchFailure(SearchFailure.Code.UNSUPPORTED, "Google CSE sirf web/image support karta hai")
    val key = secure.getSecret("search_key_google_cse"); val cx = secure.getSecret("search_cx_google_cse")
    if (key.isNullOrBlank() || cx.isNullOrBlank()) throw SearchFailure(SearchFailure.Code.NOT_CONFIGURED, "Google CSE key/cx set nahi hai")
    val url = "https://www.googleapis.com/customsearch/v1?key=${enc(key)}&cx=${enc(cx)}&q=${enc(query)}&num=${count.coerceAtMost(10)}" + if (kind == SearchKind.IMAGE) "&searchType=image" else ""
    val (code, body) = httpGetString(url)
    if (code == 400 || code == 401 || code == 403) throw SearchFailure(if (code == 403 && body.contains("quota", true)) SearchFailure.Code.QUOTA else SearchFailure.Code.AUTH, "Google CSE HTTP $code")
    if (code == 429) throw SearchFailure(SearchFailure.Code.QUOTA, "Google CSE quota")
    if (code !in 200..299) throw SearchFailure(SearchFailure.Code.NETWORK, "Google CSE HTTP $code")
    val arr = JSONObject(body).optJSONArray("items") ?: return emptyList()
    return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
      val link = o.optString("link")
      SearchResult(o.optString("title"), if (kind == SearchKind.IMAGE) o.optJSONObject("image")?.optString("contextLink").orEmpty().ifBlank { link } else link,
        o.optString("snippet"), host(link), null, imageUrl = if (kind == SearchKind.IMAGE) link else null, kind = kind, provider = id)
    }
  }
}

data class PageContent(val url: String, val title: String, val text: String, val fetchedAt: Long)

data class WebResearchResult(
  val query: String,
  val results: List<SearchResult>,
  val pages: List<PageContent>,
  val answer: String?,            // AI synthesis, only when a model really produced it
  val sources: List<String>,
  val failures: List<String>
)

/** Provider registry + fallback + page reading + AI synthesis with source metadata. */
class WebIntelligenceEngine(private val secure: SecureStore, private val ai: AiService?) {
  val providers: List<WebSearchProvider> = listOf(BraveSearchProvider(secure), GoogleCseProvider(secure))

  fun configure(providerId: String, apiKey: String, cx: String? = null) {
    secure.putSecret("search_key_$providerId", apiKey.trim()); cx?.let { secure.putSecret("search_cx_$providerId", it.trim()) }
  }
  fun anyConfigured() = providers.any { it.isConfigured() }

  /** Provider fallback: first configured provider that supports [kind] and returns results. */
  suspend fun search(query: String, kind: SearchKind = SearchKind.WEB, count: Int = 8): Pair<List<SearchResult>, List<String>> {
    val failures = mutableListOf<String>()
    for (p in providers) {
      if (!p.isConfigured() || kind !in p.supports) continue
      var attempt = 0
      while (attempt < 2) {
        try { val r = p.search(query, kind, count); if (r.isNotEmpty()) return r to failures; failures += "${p.displayName}: no results"; break }
        catch (e: SearchFailure) { failures += "${p.displayName}: ${e.message}"; if (e.code != SearchFailure.Code.NETWORK) break }
        attempt++
      }
    }
    if (failures.isEmpty()) failures += "Koi search provider configure nahi hai (Settings > API Configuration > Web Search)."
    return emptyList<SearchResult>() to failures
  }

  suspend fun readPage(url: String, maxChars: Int = 6000): PageContent {
    val (code, html) = httpGetString(url)
    if (code !in 200..299) throw SearchFailure(SearchFailure.Code.NETWORK, "Page HTTP $code")
    val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim().orEmpty()
    val text = html.replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>"), " ")
      .replace(Regex("(?s)<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
      .replace(Regex("\\s+"), " ").trim().take(maxChars)
    if (text.isBlank()) throw SearchFailure(SearchFailure.Code.EMPTY, "Page se readable text nahi mila")
    return PageContent(url, title, text, System.currentTimeMillis())
  }

  /** Heuristic: only a trigger to *consider* the web; the agent's AI planner makes the real decision. */
  fun looksTimeSensitive(q: String): Boolean {
    val s = q.lowercase()
    return listOf("latest", "today", "current", "news", "price", "weather", "score", "aaj", "abhi", "taaza", "kimat", "mausam", "खबर", "आज", "अभी", "कीमत", "मौसम").any { s.contains(it) }
  }

  suspend fun research(query: String, readTop: Int = 3): WebResearchResult {
    val (results, failures) = search(query)
    val pages = mutableListOf<PageContent>(); val fails = failures.toMutableList()
    for (r in results.take(readTop)) {
      try { pages += readPage(r.url) } catch (e: SearchFailure) { fails += "${r.source}: ${e.message}" }
    }
    var answer: String? = null
    if (pages.isNotEmpty() && ai != null) {
      val ctx = pages.mapIndexed { i, p -> "[${i + 1}] ${p.title} (${p.url})\n${p.text.take(2500)}" }.joinToString("\n\n")
      answer = try {
        ai.complete(AIRequest(
          system = "Answer ONLY from the sources. Cite as [n]. If sources disagree, say so. If they don't contain the answer, say you could not find it. Reply in the user's language.",
          turns = listOf(ChatTurn("user", "Question: $query\n\nSources:\n$ctx")), task = TaskKind.SUMMARIZE, maxTokens = 600)).text
      } catch (e: AiFailure) { fails += "Synthesis fail: ${e.message}"; null }
    }
    return WebResearchResult(query, results, pages, answer, pages.map { it.url }, fails)
  }
}
