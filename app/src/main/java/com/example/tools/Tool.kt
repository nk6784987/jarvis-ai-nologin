package com.example.tools

import com.example.permissions.Capability
import com.example.security.ActionClass
import com.example.security.SafetyGate
import org.json.JSONObject

data class ToolResult(
  val ok: Boolean,
  val message: String,
  /** true only when an independent check (screen/file/sent-status) confirmed the effect. */
  val verified: Boolean = false,
  val data: JSONObject? = null,
  val code: String = if (ok) "OK" else "FAILED"
) {
  fun toObservation(): String = buildString {
    append(if (ok) "OK" else "FAILED($code)"); if (ok) append(if (verified) " [VERIFIED]" else " [UNVERIFIED]"); append(": ").append(message.take(900))
    data?.let { append("\nDATA: ").append(it.toString().take(2500)) }
  }
  companion object {
    fun ok(msg: String, verified: Boolean = false, data: JSONObject? = null) = ToolResult(true, msg, verified, data)
    fun fail(code: String, msg: String) = ToolResult(false, msg, false, null, code)
  }
}

interface Tool {
  val id: String
  val skillId: String
  val description: String
  /** Compact JSON-schema-ish string shown to the planner model. */
  val inputSchema: String
  val outputSchema: String get() = "{ok, message, verified, data?}"
  val verification: String
  val fallback: String get() = "none"
  val requires: List<Capability> get() = emptyList()
  val safety: ActionClass get() = SafetyGate.classify(id)
  /** Human-readable text for the confirmation prompt. */
  fun describeCall(args: JSONObject): String = "$id ${args}"
  suspend fun execute(args: JSONObject): ToolResult
}
