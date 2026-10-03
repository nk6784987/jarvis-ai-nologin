package com.example.skills

import com.example.permissions.Capability
import com.example.permissions.PermState
import com.example.permissions.PermissionCenter
import com.example.security.SafetyGate
import com.example.tools.Tool

enum class SkillSafetyLevel { READ_ONLY, REVERSIBLE, SENSITIVE, CONSEQUENTIAL }
enum class SkillHealthStatus { AVAILABLE, UNAVAILABLE, PERMISSION_REQUIRED, NETWORK_REQUIRED, FAILED, DISABLED }

data class SkillDefinition(
  val id: String,
  val name: String,
  val description: String,
  val safetyLevel: SkillSafetyLevel,
  val healthStatus: SkillHealthStatus,
  val version: String = "2.0.0",
  val requiredPermissions: List<String> = emptyList(),
  val isOfflineCapable: Boolean = false,
  val tools: List<String> = emptyList(),
  val verification: String = "",
  val fallback: String = "",
  val healthDetail: String = ""
)

private val SKILL_NAMES = linkedMapOf(
  "web_search" to ("Web Search" to "Provider-based web/image/video/news search + cited research"),
  "web_browser" to ("Web Browser" to "Open URLs/pages in the device browser"),
  "android_control" to ("Android Control" to "Open apps, tap, type, scroll via AccessibilityService"),
  "screen_vision" to ("Screen Vision" to "Screenshot + OCR + accessibility semantic screen model"),
  "downloads" to ("Downloads" to "DownloadManager with size/type/completion verification"),
  "file_manager" to ("File Manager" to "Find/open/rename/delete/share real files"),
  "contact_resolver" to ("Contact Resolver" to "ContactsContract lookup with ambiguity handling"),
  "messaging" to ("Messaging" to "WhatsApp / SMS / file sharing"),
  "calling" to ("Calling" to "Phone calls with call-state verification"),
  "email" to ("Email" to "Compose/send email via installed mail apps"),
  "reminders" to ("Reminders & Scheduler" to "AlarmManager/WorkManager persistent tasks"),
  "memory" to ("Memory" to "On-device long-term memory")
)

/** Skills are derived from the REAL tool set; health reflects live permissions/services, never a constant. */
class SkillRegistry(private val tools: () -> List<Tool>, private val perms: PermissionCenter, private val extraHealth: (String) -> Pair<SkillHealthStatus, String>?) {

  fun getAllSkills(): List<SkillDefinition> {
    val grouped = tools().groupBy { it.skillId }
    return SKILL_NAMES.map { (id, nd) ->
      val ts = grouped[id].orEmpty()
      val caps = ts.flatMap { it.requires }.distinct()
      // A skill needs ANY of its listed alternatives for storage-type caps, ALL for others.
      val missing = caps.filter { c -> perms.state(c) != PermState.GRANTED }.let { m ->
        if (caps.containsAll(listOf(Capability.MEDIA, Capability.FILES)) && (Capability.MEDIA !in m || Capability.FILES !in m)) m - Capability.MEDIA - Capability.FILES else m }
      val extra = extraHealth(id)
      val (status, detail) = when {
        ts.isEmpty() -> SkillHealthStatus.UNAVAILABLE to "No tool registered"
        extra != null && extra.first != SkillHealthStatus.AVAILABLE -> extra
        missing.isNotEmpty() -> SkillHealthStatus.PERMISSION_REQUIRED to "Needs: " + missing.joinToString { it.label }
        else -> SkillHealthStatus.AVAILABLE to ""
      }
      SkillDefinition(id, nd.first, nd.second, ts.map { SafetyGate.classify(it.id) }.maxByOrNull { it.ordinal }?.let { SkillSafetyLevel.valueOf(it.name) } ?: SkillSafetyLevel.READ_ONLY,
        status, requiredPermissions = caps.map { it.label }, tools = ts.map { it.id }, verification = ts.map { it.verification }.distinct().joinToString("; "),
        fallback = ts.map { it.fallback }.filter { it != "none" }.distinct().joinToString("; "), healthDetail = detail)
    }
  }

  fun health(skillId: String) = getAllSkills().firstOrNull { it.id == skillId }
}
