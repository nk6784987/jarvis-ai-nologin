package com.example.tools

import com.example.permissions.Capability
import com.example.permissions.PermState
import com.example.permissions.PermissionCenter
import com.example.security.SafetyGate
import com.example.skills.SkillHealthStatus
import com.example.skills.SkillRegistry
import org.json.JSONObject

/** Executes tool calls: skill health -> permission gate -> safety confirmation -> execute -> record. */
class ToolRouter(
  private val tools: List<Tool>,
  private val registry: SkillRegistry,
  private val perms: PermissionCenter,
  /** Suspends until the user answers. Returns true if approved. */
  private val confirm: suspend (String) -> Boolean,
  /** Suspends while the UI requests a missing permission; returns true if it ended up granted. */
  private val requestPermission: suspend (Capability) -> Boolean,
  /** true = Unrestricted mode (no confirmation prompts). Android permissions are still requested. */
  private val unrestricted: () -> Boolean = { false },
  private val onAutoApproved: (String) -> Unit = {}
) {
  private val byId = tools.associateBy { it.id }
  fun find(id: String) = byId[id]

  /** Prompt-ready catalog: only tools whose skill is currently usable (or fixable by a permission prompt). */
  fun catalog(): String {
    val skills = registry.getAllSkills().associateBy { it.id }
    return tools.joinToString("\n") { t ->
      val s = skills[t.skillId]; val state = when (s?.healthStatus) { SkillHealthStatus.AVAILABLE -> "ready"; SkillHealthStatus.PERMISSION_REQUIRED -> "needs-permission(${s.healthDetail})"; else -> "unavailable(${s?.healthDetail})" }
      "- ${t.id} [$state, safety=${SafetyGate.classify(t.id)}]: ${t.description} args=${t.inputSchema}"
    }
  }

  suspend fun call(toolId: String, args: JSONObject): ToolResult {
    val t = byId[toolId] ?: return ToolResult.fail("UNKNOWN_TOOL", "Tool '$toolId' exist nahi karta")
    for (cap in t.requires) {
      // MEDIA/FILES are alternatives: one of them is enough.
      if (cap == Capability.FILES && perms.isGranted(Capability.MEDIA)) continue
      if (cap == Capability.MEDIA && perms.isGranted(Capability.FILES)) continue
      if (perms.state(cap) != PermState.GRANTED) {
        if (!requestPermission(cap)) return ToolResult.fail("PERMISSION_REQUIRED", "${cap.label} permission nahi mili (${cap.why})")
      }
    }
    if (SafetyGate.needsConfirmation(t.id)) {
      if (unrestricted()) onAutoApproved(t.describeCall(args))
      else if (!confirm(t.describeCall(args))) return ToolResult.fail("USER_DECLINED", "User ne confirm nahi kiya")
    }
    return try { t.execute(args) } catch (e: SecurityException) { ToolResult.fail("PERMISSION_REQUIRED", e.message ?: "security") }
    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ToolResult.fail("TOOL_ERROR", "${e.javaClass.simpleName}: ${e.message}") }
  }
}
