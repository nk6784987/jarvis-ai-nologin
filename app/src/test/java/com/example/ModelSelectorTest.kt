package com.example

import com.example.ai.*
import org.junit.Assert.*
import org.junit.Test

class ModelSelectorTest {
  private val sel = ModelSelector()
  private fun m(id: String, vision: Boolean = false, ctx: Int? = 8000, tools: Boolean = false) =
    ModelInfo("p", id, contextWindow = ctx, supportsVision = vision, supportsTools = tools)
  private fun h(key: String, responded: Boolean = true, lat: Long = 500, ok: Int = 5, bad: Int = 0, auth: Boolean = false) =
    ModelHealth(key, 1L, true, responded, null, lat, lat, ok, bad, null, auth)

  @Test fun visionRequestExcludesTextOnlyModels() {
    val r = sel.rank(listOf(m("text"), m("vis", vision = true)), AIRequest(turns = emptyList(), requireVision = true), { null }, null)
    assertEquals(listOf("vis"), r.map { it.model.id })
  }

  @Test fun authFailedModelIsNeverRouted() {
    val a = m("a"); val b = m("b")
    val r = sel.rank(listOf(a, b), AIRequest(turns = emptyList()), { if (it == a.key) h(it, auth = true) else h(it) }, null)
    assertEquals(listOf("b"), r.map { it.model.id })
  }

  @Test fun fasterRespondingModelWinsWhenOtherwiseEqual() {
    val slow = m("slow"); val fast = m("fast")
    val r = sel.rank(listOf(slow, fast), AIRequest(turns = emptyList()), { if (it == slow.key) h(it, lat = 6000) else h(it, lat = 300) }, null)
    assertEquals("fast", r.first().model.id)
  }

  @Test fun highErrorRateModelIsDropped() {
    val bad = m("bad")
    val r = sel.rank(listOf(bad), AIRequest(turns = emptyList()), { h(it, ok = 1, bad = 9) }, null)
    assertTrue(r.isEmpty())
  }

  @Test fun contextRequirementFilters() {
    val r = sel.rank(listOf(m("small", ctx = 4000), m("big", ctx = 128000)), AIRequest(turns = emptyList(), minContextTokens = 50000), { null }, null)
    assertEquals(listOf("big"), r.map { it.model.id })
  }

  @Test fun userPreferenceBoostsButDoesNotOverrideHardFilters() {
    val pref = m("pref"); val other = m("other", vision = true)
    val r = sel.rank(listOf(pref, other), AIRequest(turns = emptyList(), requireVision = true), { null }, pref.key)
    assertEquals(listOf("other"), r.map { it.model.id })
  }
}
