package com.example.agent

import com.example.ai.AIRequest
import com.example.ai.AiFailure
import com.example.ai.AiService
import com.example.ai.ChatTurn
import com.example.ai.TaskKind
import com.example.memory.MemoryManager
import com.example.tools.ToolResult
import com.example.tools.ToolRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class AgentOutcome(
  val state: TaskState,
  val message: String,
  val verified: Boolean,
  val awaitingUser: Boolean = false,
  val steps: Int = 0,
  val warnings: List<String> = emptyList(),
  val modelUsed: String? = null
)

/**
 * Real loop: goal -> (AI picks next tool) -> act -> observe -> verify -> replan ... -> final.
 * All intermediate actions are chosen by the model from the live tool catalog.
 * Hard limits prevent infinite loops. If the AI is unreachable the outcome is FAILED - never a canned answer.
 */
class AgentLoop(
  private val ai: AiService,
  private val router: ToolRouter,
  private val memory: MemoryManager,
  private val brain: AutonomousAgentBrain,
  private val observeScreen: suspend () -> String,
  private val onProgress: (AutonomousTask, SubTask?) -> Unit,
  private val relaxedLimits: () -> Boolean = { false }
) {
  private val relaxed get() = relaxedLimits()
  private val MAX_STEPS get() = if (relaxed) 100 else 24
  private val MAX_CONSECUTIVE_FAILURES get() = if (relaxed) 16 else 4
  private val MAX_SAME_CALL get() = if (relaxed) 12 else 3

  private fun system(catalog: String) = """
You are JARVIS, an Android assistant that operates the user's real phone through tools. Understand Hindi, Hinglish and English; reply in the user's language style.
You decide every intermediate step yourself (search provider/browser, which result, download, verification). The user never needs to say "open Google first".
Respond with EXACTLY ONE JSON object per turn, no prose, no markdown:
{"thought":"short reasoning","action":"tool|ask_user|final","tool":"<tool id>","args":{...},"message":"<text for user when action is ask_user or final>"}
Rules:
- Use only tools from the catalog. Tools marked needs-permission will prompt the user; unavailable ones must not be used.
- After acting, rely on the OBSERVATION. [VERIFIED] means independently confirmed. [UNVERIFIED] or FAILED means you must not claim success - retry differently, use a fallback, or tell the user honestly.
- Never invent contacts, files, URLs, search results or screen contents. Use tool output only.
- If a contact is ambiguous or required info is missing, use action ask_user with a precise question.
- Sending messages/email, calling, deleting/sharing files are confirmed with the user by the system; just call the tool.
- For UI tasks: read_screen / analyze_screen before tapping; after each UI action check the new screen. Do not tap on payment/OTP/password screens.
- For "download an image of X": web_search kind=image -> pick a relevant result by title/source -> download_file with expect=image -> done only if it returned VERIFIED.
- If a question needs no tools (chat, explanation), answer directly with action final.
- final.message must state only what is verified. Mention anything unverified plainly.
TOOLS:
$catalog""".trimIndent()

  private fun now(): String = SimpleDateFormat("EEEE, dd MMM yyyy HH:mm 'epoch_ms='", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date()) + System.currentTimeMillis() + " tz=" + TimeZone.getDefault().id

  private fun parse(text: String): JSONObject? {
    val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val s = t.indexOf('{'); val e = t.lastIndexOf('}'); if (s < 0 || e <= s) return null
    return runCatching { JSONObject(t.substring(s, e + 1)) }.getOrNull()
  }

  suspend fun run(goal: String): AgentOutcome {
    val task = brain.createGoalTask(goal); onProgress(task, null)
    val mem = memory.relevant(goal, 5).joinToString("\n") { "- [${it.category}] ${it.content}" }
    val convo = ArrayDeque<ChatTurn>(); val prior = memory.recentTurns().takeLast(6)
    val first = buildString {
      append("NOW: ").append(now()).append('\n')
      if (mem.isNotBlank()) append("USER MEMORY (relevant):\n").append(mem).append('\n')
      if (prior.isNotEmpty()) append("RECENT CONVERSATION:\n").append(prior.joinToString("\n") { "${it.role}: ${it.text.take(200)}" }).append('\n')
      append("USER GOAL: ").append(goal)
    }
    convo.addLast(ChatTurn("user", first))
    val warnings = mutableListOf<String>(); val callCounts = HashMap<String, Int>()
    var consecutiveFails = 0; var steps = 0; var parseFails = 0; var lastModel: String? = null
    var actionsRun = 0; var lastActionVerified = true; var unresolvedVerifyFail = false

    while (steps < MAX_STEPS) {
      currentCoroutineContext().ensureActive()
      task.state = TaskState.PLANNING; onProgress(task, null)
      val resp = try {
        ai.complete(AIRequest(system = system(router.catalog()), turns = trim(convo), task = TaskKind.PLANNING, maxTokens = 700, temperature = 0.1, preferJson = true))
      } catch (e: AiFailure) {
        brain.finish(task, TaskState.FAILED, "AI se jawab nahi mila: ${e.message}", false); onProgress(task, null)
        return AgentOutcome(TaskState.FAILED, "AI se jawab nahi mila: ${e.message}", false, steps = steps, warnings = warnings)
      }
      lastModel = "${resp.providerId}/${resp.modelId}"
      val j = parse(resp.text)
      if (j == null) {
        if (++parseFails > 2) { brain.finish(task, TaskState.FAILED, "Model ne valid action format nahi diya", false); return AgentOutcome(TaskState.FAILED, "Model ne valid action format nahi diya.", false, steps = steps, modelUsed = lastModel) }
        convo.addLast(ChatTurn("assistant", resp.text.take(300))); convo.addLast(ChatTurn("user", "Invalid. Reply with exactly one JSON object as specified.")); continue
      }
      convo.addLast(ChatTurn("assistant", j.toString().take(700)))
      when (j.optString("action")) {
        "final" -> {
          val msg = j.optString("message").ifBlank { "Done." }
          val verified = actionsRun == 0 || (lastActionVerified && !unresolvedVerifyFail)
          val suffix = when {
            unresolvedVerifyFail -> "\n\n⚠ VERIFICATION_FAILED: last action verify nahi ho paya - result ko khud check kar lein."
            actionsRun > 0 && !lastActionVerified -> "\n\n(Note: last step ka independent verification nahi hua.)"
            else -> ""
          }
          val state = if (unresolvedVerifyFail) TaskState.FAILED else TaskState.COMPLETED
          brain.finish(task, state, msg + suffix, verified); onProgress(task, null)
          return AgentOutcome(state, msg + suffix, verified, steps = steps, warnings = warnings, modelUsed = lastModel)
        }
        "ask_user" -> {
          val msg = j.optString("message").ifBlank { "Kripya clarify karein." }
          task.state = TaskState.WAITING; brain.finish(task, TaskState.WAITING, msg, false); onProgress(task, null)
          return AgentOutcome(TaskState.WAITING, msg, false, awaitingUser = true, steps = steps, modelUsed = lastModel)
        }
        "tool" -> {
          val toolId = j.optString("tool"); val args = j.optJSONObject("args") ?: JSONObject()
          val key = toolId + args.toString()
          val n = (callCounts[key] ?: 0) + 1; callCounts[key] = n
          if (n > MAX_SAME_CALL) {
            brain.finish(task, TaskState.FAILED, "Same action baar-baar fail/repeat ho raha tha ($toolId) - ruk gaya.", false)
            return AgentOutcome(TaskState.FAILED, "Same step $toolId ${MAX_SAME_CALL}+ baar repeat hua bina progress ke - loop rok diya.", false, steps = steps, warnings = warnings, modelUsed = lastModel)
          }
          val sub = brain.addStep(task, "$toolId ${args.toString().take(60)}", toolId, args.toString().take(80)); onProgress(task, sub)
          val result: ToolResult = router.call(toolId, args); steps++
          val readOnly = com.example.security.SafetyGate.classify(toolId) == com.example.security.ActionClass.READ_ONLY
          if (!readOnly) actionsRun++
          sub.status = if (result.ok) TaskState.COMPLETED else TaskState.FAILED; sub.detail = result.toObservation().take(160)
          task.actionHistory += "$toolId -> ${result.code}"
          if (result.ok) { consecutiveFails = 0; if (!readOnly) { lastActionVerified = result.verified; unresolvedVerifyFail = false } }
          else {
            consecutiveFails++
            if (result.code == "VERIFICATION_FAILED") unresolvedVerifyFail = true
            if (!readOnly) lastActionVerified = false
            task.state = TaskState.RECOVERING
            if (result.code == "USER_DECLINED") { brain.finish(task, TaskState.CANCELLED, "Aapne action cancel kar diya.", false); return AgentOutcome(TaskState.CANCELLED, "Theek hai, action nahi kiya.", false, steps = steps, modelUsed = lastModel) }
            if (result.code == "BLOCKED_FINANCIAL") { brain.finish(task, TaskState.WAITING, result.message, false); return AgentOutcome(TaskState.WAITING, result.message, false, awaitingUser = true, steps = steps, modelUsed = lastModel) }
          }
          onProgress(task, sub)
          if (consecutiveFails >= MAX_CONSECUTIVE_FAILURES) {
            val m = "Lagatar $MAX_CONSECUTIVE_FAILURES actions fail hue (last: ${result.message.take(120)})."
            brain.finish(task, TaskState.FAILED, m, false); return AgentOutcome(TaskState.FAILED, m, false, steps = steps, warnings = warnings, modelUsed = lastModel)
          }
          val screen = if (!readOnly && result.ok && toolId in setOf("open_app", "open_url", "tap_element", "tap_visual", "type_text", "scroll", "press_back", "press_home")) "\nSCREEN NOW: " + runCatching { observeScreen() }.getOrDefault("unavailable") else ""
          val warn = if (n == MAX_SAME_CALL) "\nWARNING: this exact call was repeated $n times - change approach." else ""
          convo.addLast(ChatTurn("user", "OBSERVATION(${toolId}): ${result.toObservation()}$screen$warn"))
        }
        else -> convo.addLast(ChatTurn("user", "Unknown action. Use tool, ask_user or final."))
      }
    }
    val m = "Step limit ($MAX_STEPS) tak pahunch gaya, task poora verify nahi hua."
    brain.finish(task, TaskState.FAILED, m, false); onProgress(task, null)
    return AgentOutcome(TaskState.FAILED, m, false, steps = steps, warnings = warnings, modelUsed = lastModel)
  }

  /** Keep the first turn (goal/context) and the latest turns; older observations are dropped to bound context. */
  private fun trim(c: ArrayDeque<ChatTurn>): List<ChatTurn> {
    if (c.size <= 14) return c.toList()
    return listOf(c.first()) + c.toList().takeLast(12).let { l -> if (l.first().role == "user") l.drop(1) else l }.let { it }
  }
}
