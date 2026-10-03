package com.example.tools

import android.net.Uri
import com.example.communication.CommunicationRouter
import com.example.communication.ContactResolution
import com.example.communication.ContactResolver
import com.example.device.*
import com.example.memory.MemoryManager
import com.example.permissions.Capability
import com.example.scheduler.ProactiveTaskEngine
import com.example.scheduler.TaskType
import com.example.security.SafetyGate
import com.example.vision.SemanticScreen
import com.example.vision.VisualElement
import com.example.vision.VisualIntelligenceEngine
import com.example.web.SearchKind
import com.example.web.SearchFailure
import com.example.web.WebIntelligenceEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Shared mutable state tools need between calls within one session (last screen, last files, last search). */
class ToolSession {
  @Volatile var lastScreen: SemanticScreen? = null
  val files = LinkedHashMap<String, FileInfo>()
  var lastSearch: List<com.example.web.SearchResult> = emptyList()
}

private fun JSONObject.str(k: String): String? = optString(k).takeIf { it.isNotBlank() && it != "null" }
private fun fromDevice(r: DeviceResult<*>, okMsg: String, verified: Boolean = false) =
  if (r.ok) ToolResult.ok(listOf(okMsg, r.detail).filter { it.isNotBlank() }.joinToString(" - "), verified) else ToolResult.fail(r.code.name, r.detail)

class Deps(
  val device: DeviceAgent, val vision: VisualIntelligenceEngine, val web: WebIntelligenceEngine, val files: FileManager,
  val comm: CommunicationRouter, val contacts: ContactResolver, val scheduler: ProactiveTaskEngine, val memory: MemoryManager,
  val session: ToolSession = ToolSession()
)

fun buildTools(d: Deps): List<Tool> = listOf(
  object : Tool {
    override val id = "open_app"; override val skillId = "android_control"
    override val description = "Launch an installed app by name or package."
    override val inputSchema = """{"app":"string"}"""
    override val verification = "foreground package == target"
    override val requires = listOf(Capability.ACCESSIBILITY)
    override suspend fun execute(args: JSONObject): ToolResult {
      val r = d.device.openApp(args.str("app") ?: return ToolResult.fail("INVALID_ARGUMENT", "app missing"))
      return fromDevice(r, "opened", verified = r.ok && d.device.isServiceEnabled)
    }
  },
  object : Tool {
    override val id = "list_apps"; override val skillId = "android_control"
    override val description = "List installed launchable apps (use to check an app exists)."
    override val inputSchema = """{"filter":"string?"}"""; override val verification = "n/a (read-only)"
    override suspend fun execute(args: JSONObject): ToolResult {
      val f = args.str("filter")?.lowercase()
      val apps = d.device.installedApps().filter { f == null || it.second.lowercase().contains(f) }.take(40)
      return ToolResult.ok("${apps.size} apps", true, JSONObject().put("apps", JSONArray(apps.map { "${it.second} (${it.first})" })))
    }
  },
  object : Tool {
    override val id = "open_url"; override val skillId = "web_browser"
    override val description = "Open a URL in the default browser (or a given package). Use for search pages, e.g. https://www.youtube.com/results?search_query=..."
    override val inputSchema = """{"url":"string","package":"string?"}"""
    override val verification = "foreground package changed / page text visible"
    override suspend fun execute(args: JSONObject): ToolResult {
      val url = args.str("url") ?: return ToolResult.fail("INVALID_ARGUMENT", "url")
      return fromDevice(d.device.openUrl(url, args.str("package")), "url opened")
    }
  },
  object : Tool {
    override val id = "read_screen"; override val skillId = "screen_vision"
    override val description = "Return the visible text and interactive elements of the current screen from the accessibility tree."
    override val inputSchema = "{}"; override val verification = "n/a (read-only)"
    override val requires = listOf(Capability.ACCESSIBILITY)
    override suspend fun execute(args: JSONObject): ToolResult {
      val r = d.device.getScreenState(); if (!r.ok) return ToolResult.fail(r.code.name, r.detail)
      val s = r.value!!
      val inter = s.interactive.take(40).mapIndexed { i, n -> "#${n.id} ${n.role} '${n.label.take(50)}'${if (n.editable) " [input]" else ""}${if (n.checked) " [checked]" else ""}" }
      return ToolResult.ok("app=${s.packageName}", true, JSONObject().put("text", s.screenText().take(1800)).put("interactive", JSONArray(inter)))
    }
  },
  object : Tool {
    override val id = "analyze_screen"; override val skillId = "screen_vision"
    override val description = "Full vision pipeline: screenshot + OCR + accessibility tree -> typed elements (button/image/card/input...). Needed for 'first photo', 'top button', icons without text. Returns element ids for tap_visual."
    override val inputSchema = """{"ocr":"boolean?"}"""; override val verification = "n/a (read-only)"
    override val fallback = "read_screen"
    override suspend fun execute(args: JSONObject): ToolResult {
      val r = d.vision.analyzeCurrentScreen(withOcr = args.optBoolean("ocr", true)); if (!r.ok) return ToolResult.fail(r.code.name, r.detail)
      val s = r.value!!; d.session.lastScreen = s.copy(screenshot = null)
      val arr = JSONArray(); s.elements.take(45).forEach {
        arr.put(JSONObject().put("id", it.id).put("type", it.type.name).put("text", it.text.take(60)).put("clickable", it.clickable).put("y", it.bounds.top).put("x", it.bounds.left)) }
      s.screenshot?.recycle()
      return ToolResult.ok(s.summary + (s.ocrError?.let { " | OCR: $it" } ?: ""), true, JSONObject().put("elements", arr))
    }
  },
  object : Tool {
    override val id = "tap_visual"; override val skillId = "android_control"
    override val description = "Tap an element by its id from the latest analyze_screen, OR by spoken reference ('pehla', 'second result', 'top button')."
    override val inputSchema = """{"id":"string?","reference":"string?"}"""
    override val verification = "screen changes after tap"
    override suspend fun execute(args: JSONObject): ToolResult {
      val sc = d.session.lastScreen ?: return ToolResult.fail("NO_SCREEN_MODEL", "Pehle analyze_screen chalayein")
      val el: VisualElement = args.str("id")?.let { id -> sc.elements.firstOrNull { it.id == id } }
        ?: args.str("reference")?.let { d.vision.resolveSimpleReference(it, sc) }
        ?: return ToolResult.fail("ELEMENT_NOT_FOUND", "Element resolve nahi hua")
      val txt = d.device.readScreenText().value.orEmpty()
      if (SafetyGate.looksFinancial(txt)) return ToolResult.fail("BLOCKED_FINANCIAL", "Payment/OTP screen - user khud karein")
      val before = d.device.screenHash()
      val r = if (el.nodePath != null) d.device.tapNode(UiNode(el.nodePath, "", "", UiNode.Role.OTHER, el.text, "", "", el.bounds, true, false, false, false, false, false, false, true, false, true, 0)) else d.device.tap(el.bounds.centerX(), el.bounds.centerY())
      if (!r.ok) return ToolResult.fail(r.code.name, r.detail)
      val v = d.device.verifyAction(Expect.ScreenChanged(before), 3000)
      return if (v.ok) ToolResult.ok("tapped '${el.text.take(40)}' (${el.type})", true) else ToolResult.fail("VERIFICATION_FAILED", "Tap hua par screen nahi badli")
    }
  },
  object : Tool {
    override val id = "tap_element"; override val skillId = "android_control"
    override val description = "Tap an element by visible text/description/role/ordinal using the accessibility tree."
    override val inputSchema = """{"text":"string?","description":"string?","role":"BUTTON|INPUT|IMAGE|LIST_ITEM|TAB?","ordinal":"int?"}"""
    override val verification = "screen changes after tap"
    override val requires = listOf(Capability.ACCESSIBILITY)
    override suspend fun execute(args: JSONObject): ToolResult {
      val q = ElementQuery(text = args.str("text"), contentDescription = args.str("description"),
        role = args.str("role")?.let { runCatching { UiNode.Role.valueOf(it.uppercase()) }.getOrNull() }, ordinal = args.optInt("ordinal", 0).takeIf { it > 0 })
      if (SafetyGate.looksFinancial(d.device.readScreenText().value.orEmpty())) return ToolResult.fail("BLOCKED_FINANCIAL", "Payment/OTP screen - user khud karein")
      val before = d.device.screenHash()
      val r = d.device.tapElement(q); if (!r.ok) return ToolResult.fail(r.code.name, r.detail)
      val v = d.device.verifyAction(Expect.ScreenChanged(before), 3000)
      return if (v.ok) ToolResult.ok("tapped '${r.value!!.label.take(40)}'", true) else ToolResult.fail("VERIFICATION_FAILED", "Tap hua par screen nahi badli")
    }
  },
  object : Tool {
    override val id = "type_text"; override val skillId = "android_control"
    override val description = "Type text into the focused (or first) input field; optionally press search/enter afterwards via tap_element."
    override val inputSchema = """{"text":"string","clear_first":"boolean?"}"""
    override val verification = "read field value back"
    override val requires = listOf(Capability.ACCESSIBILITY)
    override suspend fun execute(args: JSONObject): ToolResult {
      if (args.optBoolean("clear_first", false)) d.device.clearText()
      val r = d.device.typeText(args.str("text") ?: return ToolResult.fail("INVALID_ARGUMENT", "text"))
      return if (r.ok) ToolResult.ok("typed", true) else ToolResult.fail(r.code.name, r.detail)
    }
  },
  object : Tool {
    override val id = "scroll"; override val skillId = "android_control"
    override val description = "Scroll the current screen."; override val inputSchema = """{"direction":"down|up"}"""
    override val verification = "screen content hash changes"
    override val requires = listOf(Capability.ACCESSIBILITY)
    override suspend fun execute(args: JSONObject): ToolResult {
      val before = d.device.screenHash(); val r = d.device.scroll(args.str("direction") ?: "down"); if (!r.ok) return ToolResult.fail(r.code.name, r.detail)
      val v = d.device.verifyAction(Expect.ScreenChanged(before), 2000)
      return if (v.ok) ToolResult.ok("scrolled", true) else ToolResult.ok("scroll dispatched, content unchanged (end of list?)", false)
    }
  },
  object : Tool {
    override val id = "press_back"; override val skillId = "android_control"
    override val description = "Press system Back."; override val inputSchema = "{}"; override val verification = "screen changes"
    override val requires = listOf(Capability.ACCESSIBILITY)
    override suspend fun execute(args: JSONObject) = d.device.screenHash().let { b -> val r = d.device.pressBack(); if (!r.ok) ToolResult.fail(r.code.name, r.detail) else ToolResult.ok("back", d.device.verifyAction(Expect.ScreenChanged(b), 1500).ok) }
  },
  object : Tool {
    override val id = "press_home"; override val skillId = "android_control"
    override val description = "Go to home screen."; override val inputSchema = "{}"; override val verification = "launcher in foreground"
    override val requires = listOf(Capability.ACCESSIBILITY)
    override suspend fun execute(args: JSONObject) = d.device.pressHome().let { if (it.ok) ToolResult.ok("home", false) else ToolResult.fail(it.code.name, it.detail) }
  },
  object : Tool {
    override val id = "wait_for"; override val skillId = "android_control"
    override val description = "Wait until text appears on screen (e.g. search results)."; override val inputSchema = """{"text":"string","timeout_ms":"int?"}"""
    override val verification = "element found"
    override val requires = listOf(Capability.ACCESSIBILITY)
    override suspend fun execute(args: JSONObject): ToolResult {
      val r = d.device.waitForElement(ElementQuery(text = args.str("text")), args.optLong("timeout_ms", 8000)); return if (r.ok) ToolResult.ok("found", true) else ToolResult.fail(r.code.name, r.detail)
    }
  },
  object : Tool {
    override val id = "web_search"; override val skillId = "web_search"
    override val description = "Search the web via configured provider. kind=web|image|video|news. Image results include image_url for download_file."
    override val inputSchema = """{"query":"string","kind":"web|image|video|news?","count":"int?"}"""
    override val verification = "results returned with source metadata"
    override val fallback = "open_url with a search page + read_screen"
    override suspend fun execute(args: JSONObject): ToolResult {
      val kind = runCatching { SearchKind.valueOf((args.str("kind") ?: "web").uppercase()) }.getOrDefault(SearchKind.WEB)
      val (res, fails) = d.web.search(args.str("query") ?: return ToolResult.fail("INVALID_ARGUMENT", "query"), kind, args.optInt("count", 8))
      if (res.isEmpty()) return ToolResult.fail("SEARCH_UNAVAILABLE", fails.joinToString("; "))
      d.session.lastSearch = res
      val arr = JSONArray(); res.forEachIndexed { i, r -> arr.put(JSONObject().put("i", i).put("title", r.title.take(80)).put("url", r.url).put("source", r.source).put("snippet", r.snippet.take(140)).put("date", r.date ?: "").put("image_url", r.imageUrl ?: "")) }
      return ToolResult.ok("${res.size} results via ${res[0].provider}", true, JSONObject().put("results", arr))
    }
  },
  object : Tool {
    override val id = "web_research"; override val skillId = "web_search"
    override val description = "Search, read top pages, and synthesize a cited answer for current-information questions."
    override val inputSchema = """{"query":"string"}"""; override val verification = "answer grounded in fetched pages (sources listed)"
    override suspend fun execute(args: JSONObject): ToolResult {
      val r = d.web.research(args.str("query") ?: return ToolResult.fail("INVALID_ARGUMENT", "query"))
      if (r.pages.isEmpty()) return ToolResult.fail("SEARCH_UNAVAILABLE", r.failures.joinToString("; ").ifBlank { "No readable pages" })
      return ToolResult.ok(r.answer ?: "Pages mile par synthesis fail: ${r.failures.lastOrNull()}", r.answer != null,
        JSONObject().put("sources", JSONArray(r.sources)).put("excerpt", r.pages.first().text.take(600)))
    }
  },
  object : Tool {
    override val id = "download_file"; override val skillId = "downloads"
    override val description = "Download a direct file URL into Downloads and verify size/type. expect=image|pdf|video to reject HTML error pages."
    override val inputSchema = """{"url":"string","file_name":"string","expect":"image|pdf|video?"}"""
    override val verification = "file exists, size>0, size matches, magic-bytes type matches"
    override val fallback = "open the page and use the app's own download button"
    override suspend fun execute(args: JSONObject): ToolResult {
      val exp = when (args.str("expect")) { "image" -> "image/"; "pdf" -> "application/pdf"; "video" -> "video/"; else -> null }
      val r = d.files.download(args.str("url") ?: return ToolResult.fail("INVALID_ARGUMENT", "url"), args.str("file_name") ?: "jarvis_${System.currentTimeMillis()}", exp)
      if (!r.ok) return ToolResult.fail(r.code.name, r.detail)
      val f = r.value!!.file; d.session.files[f.uri.toString()] = f
      return ToolResult.ok("Downloaded ${f.name} (${f.size / 1024} KB, ${f.mime}) at ${f.path}", true, JSONObject().put("uri", f.uri.toString()).put("path", f.path ?: ""))
    }
  },
  object : Tool {
    override val id = "find_files"; override val skillId = "file_manager"
    override val description = "Find files on the device (name contains, type = image|pdf|video|any, newest first)."
    override val inputSchema = """{"name":"string?","type":"image|pdf|video|any?","limit":"int?"}"""
    override val verification = "files read from MediaStore/Downloads"
    override val requires = listOf(Capability.MEDIA, Capability.FILES)
    override suspend fun execute(args: JSONObject): ToolResult {
      val t = args.str("type") ?: "any"; val lim = args.optInt("limit", 10)
      var found = when (t) {
        "pdf" -> d.files.find(args.str("name"), mimeExact = "application/pdf", limit = lim).ifEmpty { d.files.listDownloadsDir("pdf", lim) }
        "image" -> d.files.find(args.str("name"), mimePrefix = "image/", limit = lim)
        "video" -> d.files.find(args.str("name"), mimePrefix = "video/", limit = lim)
        else -> d.files.find(args.str("name"), limit = lim).ifEmpty { d.files.listDownloadsDir(null, lim) }
      }
      if (found.isEmpty()) return ToolResult.fail("NOT_FOUND", "Koi file nahi mili (storage permission check karein)")
      found.forEach { d.session.files[it.uri.toString()] = it }
      val arr = JSONArray(); found.forEach { arr.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("mime", it.mime).put("kb", it.size / 1024).put("modified", java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault()).format(it.modifiedMs))) }
      return ToolResult.ok("${found.size} files", true, JSONObject().put("files", arr))
    }
  },
  object : Tool {
    override val id = "open_file"; override val skillId = "file_manager"
    override val description = "Open a file found earlier in its default app."; override val inputSchema = """{"uri":"string"}"""; override val verification = "viewer intent resolved"
    override suspend fun execute(args: JSONObject): ToolResult {
      val f = d.session.files[args.str("uri")] ?: return ToolResult.fail("NOT_FOUND", "File session mein nahi - pehle find_files")
      return fromDevice(d.files.open(f), "opened ${f.name}")
    }
  },
  object : Tool {
    override val id = "delete_file"; override val skillId = "file_manager"
    override val description = "Delete a file (requires user confirmation)."; override val inputSchema = """{"uri":"string"}"""; override val verification = "file no longer queryable"
    override fun describeCall(args: JSONObject) = "File delete karun: ${d.session.files[args.optString("uri")]?.name ?: args.optString("uri")}?"
    override suspend fun execute(args: JSONObject): ToolResult {
      val f = d.session.files[args.str("uri")] ?: return ToolResult.fail("NOT_FOUND", "File nahi mili")
      val r = d.files.delete(f); if (!r.ok) return ToolResult.fail(r.code.name, r.detail)
      d.session.files.remove(f.uri.toString()); return ToolResult.ok("deleted ${f.name}", true)
    }
  },
  object : Tool {
    override val id = "resolve_contact"; override val skillId = "contact_resolver"
    override val description = "Look up a contact by name. Returns candidates; if more than one, you MUST ask_user which one."
    override val inputSchema = """{"name":"string"}"""; override val verification = "ContactsContract query"
    override val requires = listOf(Capability.CONTACTS)
    override suspend fun execute(args: JSONObject): ToolResult = when (val r = d.contacts.resolve(args.str("name") ?: return ToolResult.fail("INVALID_ARGUMENT", "name"))) {
      is ContactResolution.PermissionDenied -> ToolResult.fail("PERMISSION_REQUIRED", "Contacts permission chahiye")
      is ContactResolution.NotFound -> ToolResult.fail("NOT_FOUND", "Is naam ka contact nahi mila")
      is ContactResolution.Single -> ToolResult.ok("1 contact: ${r.contact.name}", true, JSONObject().put("contacts", JSONArray().put(JSONObject().put("name", r.contact.name).put("phones", JSONArray(r.contact.phones)).put("emails", JSONArray(r.contact.emails)))))
      is ContactResolution.Ambiguous -> ToolResult.fail("AMBIGUOUS", "${r.candidates.size} matches: " + r.candidates.joinToString { "${it.name} ${it.phones.firstOrNull() ?: ""}" })
    }
  },
  object : Tool {
    override val id = "send_message"; override val skillId = "messaging"
    override val description = "Send a text via whatsapp or sms to an already-resolved number."; override val inputSchema = """{"to_name":"string","number":"string","text":"string","channel":"whatsapp|sms"}"""
    override val verification = "SMS: sent-status broadcast; WhatsApp: message visible in chat"
    override fun describeCall(args: JSONObject) = "${args.optString("channel")} par ${args.optString("to_name")} ko bhejun: \"${args.optString("text").take(80)}\"?"
    override suspend fun execute(args: JSONObject): ToolResult {
      val n = args.str("number") ?: return ToolResult.fail("INVALID_ARGUMENT", "number"); val t = args.str("text") ?: return ToolResult.fail("INVALID_ARGUMENT", "text")
      val r = if (args.str("channel") == "sms") d.comm.sendSms(n, t) else d.comm.sendWhatsApp(n, t)
      return if (r.ok) ToolResult.ok(r.value ?: "sent", true) else ToolResult.fail(r.code.name, r.detail)
    }
  },
  object : Tool {
    override val id = "share_file"; override val skillId = "messaging"
    override val description = "Send a file/photo to a resolved contact via whatsapp (attach + verify recipient), or open the share sheet if channel=sheet."
    override val inputSchema = """{"to_name":"string?","number":"string?","uri":"string","channel":"whatsapp|sheet"}"""
    override val verification = "recipient name visible, send screen closes"
    override fun describeCall(args: JSONObject) = "${d.session.files[args.optString("uri")]?.name ?: "file"} ko ${args.optString("to_name", "share sheet")} ko bhejun?"
    override suspend fun execute(args: JSONObject): ToolResult {
      val f = d.session.files[args.str("uri")] ?: return ToolResult.fail("NOT_FOUND", "File nahi mili - pehle find_files")
      val uri = d.files.shareableUri(f)
      val r = if (args.str("channel") == "whatsapp") d.comm.sendFileViaWhatsApp(args.str("number") ?: return ToolResult.fail("INVALID_ARGUMENT", "number"), args.str("to_name") ?: "", uri, f.mime)
        else d.comm.shareFile(uri, f.mime)
      return if (r.ok) ToolResult.ok(r.value ?: "shared", args.str("channel") == "whatsapp") else ToolResult.fail(r.code.name, r.detail)
    }
  },
  object : Tool {
    override val id = "call"; override val skillId = "calling"
    override val description = "Place a phone call to a resolved number."; override val inputSchema = """{"to_name":"string","number":"string"}"""
    override val verification = "TelephonyManager state OFFHOOK"; override val requires = listOf(Capability.PHONE)
    override fun describeCall(args: JSONObject) = "${args.optString("to_name")} ko call lagaun?"
    override suspend fun execute(args: JSONObject): ToolResult { val r = d.comm.call(args.str("number") ?: return ToolResult.fail("INVALID_ARGUMENT", "number")); return if (r.ok) ToolResult.ok(r.value ?: "calling", r.value?.contains("verified") == true) else ToolResult.fail(r.code.name, r.detail) }
  },
  object : Tool {
    override val id = "send_email"; override val skillId = "email"
    override val description = "Compose an email (to, subject, body, optional attachment uri). send=false leaves a draft."
    override val inputSchema = """{"to":"string[]","subject":"string","body":"string","uri":"string?","send":"boolean?"}"""
    override val verification = "draft opened; if send=true compose screen closes"
    override fun describeCall(args: JSONObject) = "Email ${args.optString("to")} ko, subject \"${args.optString("subject")}\" - bhejun?"
    override suspend fun execute(args: JSONObject): ToolResult {
      val to = args.optJSONArray("to")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOfNotNull(args.str("to"))
      if (to.isEmpty()) return ToolResult.fail("INVALID_ARGUMENT", "recipient")
      val att = args.str("uri")?.let { d.session.files[it] }
      val r = d.comm.composeEmail(to, args.optString("subject"), args.optString("body"), att?.let { d.files.shareableUri(it) }, att?.mime ?: "*/*", args.optBoolean("send", false))
      return if (r.ok) ToolResult.ok(r.value ?: "draft", r.value?.contains("verified") == true) else ToolResult.fail(r.code.name, r.detail)
    }
  },
  object : Tool {
    override val id = "set_reminder"; override val skillId = "reminders"
    override val description = "Schedule a reminder or agent goal. Give absolute local time as epoch_ms computed from NOW provided in the prompt, or hour+minute (today/tomorrow auto)."
    override val inputSchema = """{"text":"string","epoch_ms":"long?","hour":"0-23?","minute":"0-59?","day_offset":"int?","repeat":"none|daily|weekly?","type":"reminder|goal?"}"""
    override val verification = "task persisted + alarm/work armed"
    override suspend fun execute(args: JSONObject): ToolResult {
      val text = args.str("text") ?: return ToolResult.fail("INVALID_ARGUMENT", "text")
      val at = if (args.has("epoch_ms") && args.optLong("epoch_ms") > 0) args.getLong("epoch_ms") else {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, args.optInt("day_offset", 0)); set(Calendar.HOUR_OF_DAY, args.optInt("hour", -1).takeIf { it >= 0 } ?: return ToolResult.fail("INVALID_ARGUMENT", "time chahiye")); set(Calendar.MINUTE, args.optInt("minute", 0)); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis() && !args.has("day_offset")) c.add(Calendar.DAY_OF_YEAR, 1); c.timeInMillis
      }
      val rep = when (args.str("repeat")) { "daily" -> 86_400_000L; "weekly" -> 7 * 86_400_000L; else -> 0L }
      val t = try { d.scheduler.schedule(text, at, if (args.str("type") == "goal") TaskType.AGENT_GOAL else TaskType.REMINDER, rep, needsNetwork = args.str("type") == "goal") } catch (e: IllegalArgumentException) { return ToolResult.fail("INVALID_ARGUMENT", e.message ?: "time") }
      val exact = d.scheduler.canScheduleExact()
      return ToolResult.ok("Scheduled '${t.goal}' for ${t.schedule}" + if (!exact && t.type == TaskType.REMINDER) " (exact-alarm permission nahi, isliye ~minutes ka delay ho sakta hai)" else "", verified = d.scheduler.scheduledTasks.value.any { it.id == t.id })
    }
  },
  object : Tool {
    override val id = "remember"; override val skillId = "memory"
    override val description = "Store a long-term fact/preference about the user."; override val inputSchema = """{"content":"string","category":"fact|preference|person?"}"""
    override val verification = "written to users/{uid}/memory"
    override suspend fun execute(args: JSONObject): ToolResult {
      val content = args.str("content") ?: return ToolResult.fail("INVALID_ARGUMENT", "content")
      return if (d.memory.remember(content, args.str("category") ?: "fact")) ToolResult.ok("saved", false) else ToolResult.fail("NOT_SIGNED_IN", "Memory save nahi hui (khali content)")
    }
  },
  object : Tool {
    override val id = "recall_memory"; override val skillId = "memory"
    override val description = "List what is remembered about the user (optionally filtered)."; override val inputSchema = """{"query":"string?"}"""; override val verification = "read-only"
    override suspend fun execute(args: JSONObject): ToolResult {
      val items = args.str("query")?.let { d.memory.relevant(it, 8) } ?: d.memory.recallAll()
      return ToolResult.ok("${items.size} memories", true, JSONObject().put("memories", JSONArray(items.map { "[${it.category}] ${it.content}" })))
    }
  },
  object : Tool {
    override val id = "forget_memory"; override val skillId = "memory"
    override val description = "Delete memories matching text."; override val inputSchema = """{"match":"string"}"""; override val verification = "count removed"
    override suspend fun execute(args: JSONObject): ToolResult { val n = d.memory.forget(args.str("match") ?: return ToolResult.fail("INVALID_ARGUMENT", "match")); return if (n > 0) ToolResult.ok("$n memory delete hui", true) else ToolResult.fail("NOT_FOUND", "Match nahi mila") }
  }
)
