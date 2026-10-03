package com.example.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Native device control. Accessibility first (semantic nodes); coordinates only when the caller asks
 * or no node can be resolved. Every function returns a DeviceResult carrying the real outcome.
 */
class DeviceAgent(private val context: Context) {

  private val svc get() = JarvisAccessibilityService.instance
  private val pm: PackageManager get() = context.packageManager

  val isServiceEnabled: Boolean get() = svc != null

  fun accessibilitySettingsIntent(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  private fun <T> notEnabled(): DeviceResult<T> = DeviceResult.fail(
    ResultCode.SERVICE_NOT_ENABLED, "JARVIS Accessibility Service on nahi hai. Settings > Accessibility > JARVIS enable karein.")

  private fun metrics(): DisplayMetrics {
    val dm = DisplayMetrics()
    @Suppress("DEPRECATION")
    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
    return dm
  }

  // ---------- apps ----------

  /** Resolve an installed app from a package name OR a human label ("YouTube"). */
  fun resolveApp(nameOrPackage: String): Pair<String, String>? {
    val q = nameOrPackage.trim()
    runCatching { pm.getApplicationInfo(q, 0); pm.getLaunchIntentForPackage(q)?.let { return q to label(q) } }
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
    val ql = q.lowercase(Locale.ROOT)
    return apps.firstOrNull { it.second.equals(q, true) }
      ?: apps.firstOrNull { it.second.lowercase(Locale.ROOT).contains(ql) }
      ?: apps.firstOrNull { ql.contains(it.second.lowercase(Locale.ROOT)) && it.second.length > 3 }
  }

  private fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

  fun installedApps(): List<Pair<String, String>> {
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinct().sortedBy { it.second }
  }

  suspend fun openApp(nameOrPackage: String): DeviceResult<String> {
    val (pkg, lbl) = resolveApp(nameOrPackage)
      ?: return DeviceResult.fail(ResultCode.APP_NOT_INSTALLED, "'$nameOrPackage' device par installed nahi mila.")
    val intent = pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ?: return DeviceResult.fail(ResultCode.APP_NOT_INSTALLED, "$lbl ka launch intent nahi hai.")
    return try {
      context.startActivity(intent)
      verifyForeground(pkg, lbl)
    } catch (e: Exception) {
      DeviceResult.fail(ResultCode.ACTION_FAILED, "App launch fail: ${e.message}")
    }
  }

  private suspend fun verifyForeground(pkg: String, lbl: String): DeviceResult<String> {
    if (svc == null) return DeviceResult(ResultCode.OK, pkg, "Launched $lbl (foreground verify ke liye Accessibility on karein).")
    val ok = withTimeoutOrNull(5000) {
      while (currentPackage() != pkg) delay(250)
      true
    }
    return if (ok == true) DeviceResult.ok(pkg, "$lbl foreground mein verified")
    else DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "$lbl launch hua par foreground mein nahi dikha (current=${currentPackage()}).")
  }

  suspend fun openUrl(url: String, inPackage: String? = null): DeviceResult<String> {
    val uri = Uri.parse(if (url.startsWith("http", true) || url.contains("://")) url else "https://$url")
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    inPackage?.let { intent.setPackage(it) }
    if (intent.resolveActivity(pm) == null) return DeviceResult.fail(ResultCode.APP_NOT_INSTALLED, "Is URL ko kholne wala koi app nahi.")
    return try {
      context.startActivity(intent)
      delay(800)
      DeviceResult.ok(uri.toString(), "URL intent dispatched; foreground=${currentPackage() ?: "unknown"}")
    } catch (e: Exception) { DeviceResult.fail(ResultCode.ACTION_FAILED, e.message ?: "openUrl failed") }
  }

  fun currentPackage(): String? = svc?.snapshot(maxNodes = 1)?.packageName?.takeIf { it.isNotBlank() }
    ?: JarvisAccessibilityService.lastForegroundPackage

  fun getCurrentApp(): DeviceResult<String> {
    if (svc == null) return notEnabled()
    val p = currentPackage() ?: return DeviceResult.fail(ResultCode.ACTION_FAILED, "Active window nahi mila")
    return DeviceResult.ok(p, label(p))
  }

  // ---------- observation ----------

  fun getScreenState(): DeviceResult<WindowSnapshot> {
    val s = svc ?: return notEnabled()
    val snap = s.snapshot() ?: return DeviceResult.fail(ResultCode.ACTION_FAILED, "Active window ka content nahi mila (secure/blank screen ho sakti hai).")
    return DeviceResult.ok(snap)
  }

  fun getVisibleElements(): DeviceResult<List<UiNode>> {
    val r = getScreenState()
    return if (r.ok) DeviceResult.ok(r.value!!.nodes.filter { it.visible && (it.label.isNotBlank() || it.clickable || it.editable) })
    else DeviceResult.fail(r.code, r.detail)
  }

  fun readScreenText(): DeviceResult<String> {
    val r = getScreenState()
    return if (r.ok) DeviceResult.ok(r.value!!.screenText()) else DeviceResult.fail(r.code, r.detail)
  }

  fun findElement(q: ElementQuery, within: WindowSnapshot? = null): DeviceResult<UiNode> {
    val snap = within ?: getScreenState().let { if (!it.ok) return DeviceResult.fail(it.code, it.detail) else it.value!! }
    var m = snap.nodes.filter { n ->
      n.visible && n.bounds.width() > 0 && n.bounds.height() > 0 &&
        (q.text == null || n.text.contains(q.text, true) || n.contentDescription.contains(q.text, true)) &&
        (q.contentDescription == null || n.contentDescription.contains(q.contentDescription, true)) &&
        (q.viewId == null || n.viewId.endsWith(q.viewId)) &&
        (q.role == null || n.role == q.role) &&
        (q.packageName == null || n.packageName == q.packageName) &&
        (!q.clickableOnly || n.clickable) && (!q.editableOnly || n.editable)
    }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    if (q.ordinal != null) m = listOfNotNull(m.getOrNull(q.ordinal - 1))
    val hit = m.firstOrNull() ?: return DeviceResult.fail(ResultCode.ELEMENT_NOT_FOUND, "Element nahi mila: $q")
    return DeviceResult.ok(hit, if (m.size > 1) "${m.size} matches, pehla liya" else "")
  }

  suspend fun waitForElement(q: ElementQuery, timeoutMs: Long = 8000): DeviceResult<UiNode> {
    if (svc == null) return notEnabled()
    val end = System.currentTimeMillis() + timeoutMs
    var last: DeviceResult<UiNode> = DeviceResult.fail(ResultCode.TIMEOUT, "timeout")
    while (System.currentTimeMillis() < end) {
      last = findElement(q)
      if (last.ok) return last
      delay(300)
    }
    return DeviceResult.fail(ResultCode.TIMEOUT, "Element ${timeoutMs}ms mein nahi aaya. ${last.detail}")
  }

  suspend fun takeScreenshot(): DeviceResult<Bitmap> {
    val s = svc ?: return notEnabled()
    val bmp = s.screenshot() ?: return DeviceResult.fail(ResultCode.SCREENSHOT_UNAVAILABLE,
      "Accessibility screenshot unavailable (Android 11+ chahiye ya screen secure hai). MediaProjection fallback use karein.")
    return DeviceResult.ok(bmp)
  }

  // ---------- actions ----------

  suspend fun tapNode(node: UiNode): DeviceResult<Unit> {
    val s = svc ?: return notEnabled()
    if (s.clickNode(node.path)) return DeviceResult.ok(Unit, "semantic click")
    return tap(node.centerX, node.centerY)  // coordinate fallback only after node action refused
  }

  suspend fun tap(x: Int, y: Int): DeviceResult<Unit> {
    val s = svc ?: return notEnabled()
    return if (s.gesture(listOf(x.toFloat() to y.toFloat()), 60)) DeviceResult.ok(Unit, "gesture tap")
    else DeviceResult.fail(ResultCode.ACTION_FAILED, "Tap gesture cancel/reject hua")
  }

  suspend fun tapElement(q: ElementQuery): DeviceResult<UiNode> {
    val f = findElement(q); if (!f.ok) return f
    val r = tapNode(f.value!!)
    return if (r.ok) DeviceResult.ok(f.value, r.detail) else DeviceResult.fail(r.code, r.detail)
  }

  suspend fun longPress(node: UiNode): DeviceResult<Unit> {
    val s = svc ?: return notEnabled()
    if (s.longClickNode(node.path)) return DeviceResult.ok(Unit)
    return if (s.gesture(listOf(node.centerX.toFloat() to node.centerY.toFloat()), 700)) DeviceResult.ok(Unit)
    else DeviceResult.fail(ResultCode.ACTION_FAILED, "Long press fail")
  }

  suspend fun typeText(text: String, into: UiNode? = null): DeviceResult<String> {
    val s = svc ?: return notEnabled()
    val path = into?.path ?: s.focusedInputPath()
      ?: getScreenState().value?.nodes?.firstOrNull { it.editable && it.visible }?.path
      ?: return DeviceResult.fail(ResultCode.ELEMENT_NOT_FOUND, "Koi editable field nahi mila")
    if (!s.setText(path, text)) return DeviceResult.fail(ResultCode.ACTION_FAILED, "Text set nahi hua (field read-only/secure ho sakta hai)")
    delay(250)
    val after = getScreenState().value?.nodes?.firstOrNull { it.path == path }?.text.orEmpty()
    return if (after.contains(text.take(40))) DeviceResult.ok(after, "field value verified")
    else DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "Type kiya par field mein '${after.take(40)}' dikha")
  }

  suspend fun clearText(into: UiNode? = null): DeviceResult<Unit> {
    val s = svc ?: return notEnabled()
    val path = into?.path ?: s.focusedInputPath() ?: return DeviceResult.fail(ResultCode.ELEMENT_NOT_FOUND, "Focused field nahi")
    return if (s.setText(path, "")) DeviceResult.ok(Unit) else DeviceResult.fail(ResultCode.ACTION_FAILED, "Clear fail")
  }

  suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): DeviceResult<Unit> {
    val s = svc ?: return notEnabled()
    return if (s.gesture(listOf(x1.toFloat() to y1.toFloat(), x2.toFloat() to y2.toFloat()), durationMs)) DeviceResult.ok(Unit)
    else DeviceResult.fail(ResultCode.ACTION_FAILED, "Swipe fail")
  }

  suspend fun drag(x1: Int, y1: Int, x2: Int, y2: Int): DeviceResult<Unit> = swipe(x1, y1, x2, y2, 800)

  /** direction: "down" = see content further down the page. Node scroll first, swipe fallback. */
  suspend fun scroll(direction: String): DeviceResult<Unit> {
    val s = svc ?: return notEnabled()
    val forward = direction.lowercase() in listOf("down", "forward", "next", "right")
    val p = s.firstScrollablePath()
    if (p != null && s.scrollNode(p, forward)) return DeviceResult.ok(Unit, "node scroll")
    val m = metrics(); val x = m.widthPixels / 2; val a = (m.heightPixels * 0.7).toInt(); val b = (m.heightPixels * 0.3).toInt()
    return if (forward) swipe(x, a, x, b) else swipe(x, b, x, a)
  }

  fun pressBack(): DeviceResult<Unit> = svc?.let { if (it.globalBack()) DeviceResult.ok(Unit) else DeviceResult.fail(ResultCode.ACTION_FAILED, "Back fail") } ?: notEnabled()
  fun pressHome(): DeviceResult<Unit> = svc?.let { if (it.globalHome()) DeviceResult.ok(Unit) else DeviceResult.fail(ResultCode.ACTION_FAILED, "Home fail") } ?: notEnabled()

  // ---------- verification ----------

  suspend fun verifyAction(expect: Expect, timeoutMs: Long = 4000): DeviceResult<Unit> {
    if (svc == null) return notEnabled()
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
      val snap = svc?.snapshot()
      if (snap != null && holds(expect, snap)) return DeviceResult.ok(Unit)
      if (System.currentTimeMillis() >= end) return DeviceResult.fail(ResultCode.VERIFICATION_FAILED, "Screen expected state se match nahi hui: $expect")
      delay(250)
    }
  }

  fun screenHash(): Int = svc?.snapshot()?.let { s -> s.nodes.map { it.label to it.bounds }.hashCode() } ?: 0

  private fun holds(e: Expect, s: WindowSnapshot): Boolean = when (e) {
    is Expect.PackageIs -> s.packageName == e.pkg
    is Expect.TextVisible -> s.nodes.any { it.visible && it.label.contains(e.text, true) }
    is Expect.TextGone -> s.nodes.none { it.visible && it.label.contains(e.text, true) }
    is Expect.FieldContains -> s.nodes.any { it.editable && it.text.contains(e.text, true) }
    is Expect.ScreenChanged -> s.nodes.map { it.label to it.bounds }.hashCode() != e.beforeHash
    is Expect.ElementChecked -> findElement(e.query, s).value?.checked == e.checked
  }
}
