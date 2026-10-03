package com.example.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * The only component allowed to touch other apps' UI. Everything returns real success/failure
 * from the Android framework (performAction / dispatchGesture results) - never a canned "true".
 */
class JarvisAccessibilityService : AccessibilityService() {

  private val executor = Executors.newSingleThreadExecutor()

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    _connected.value = true
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    event ?: return
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
      event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    ) {
      event.packageName?.toString()?.let { if (it != packageName) lastForegroundPackage = it }
      lastEventAt = System.currentTimeMillis()
      lastWindowClass = event.className?.toString() ?: lastWindowClass
    }
  }

  override fun onInterrupt() {}

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    instance = null; _connected.value = false
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    instance = null; _connected.value = false; executor.shutdown()
    super.onDestroy()
  }

  // ---------------- observation ----------------

  fun snapshot(maxNodes: Int = 1500): WindowSnapshot? {
    val root = rootInActiveWindow ?: return null
    val out = ArrayList<UiNode>()
    try {
      walk(root, emptyList(), 0, out, maxNodes)
    } finally {
      @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT < 33) root.recycle()
    }
    val pkg = root.packageName?.toString() ?: lastForegroundPackage.orEmpty()
    return WindowSnapshot(pkg, lastWindowClass, out, System.currentTimeMillis())
  }

  private fun walk(n: AccessibilityNodeInfo, path: List<Int>, depth: Int, out: MutableList<UiNode>, max: Int) {
    if (out.size >= max) return
    val r = Rect(); n.getBoundsInScreen(r)
    out += UiNode(
      path = path, packageName = n.packageName?.toString().orEmpty(), className = n.className?.toString().orEmpty(),
      role = roleOf(n), text = (n.text ?: "").toString(), contentDescription = (n.contentDescription ?: "").toString(),
      viewId = n.viewIdResourceName.orEmpty(), bounds = r,
      clickable = n.isClickable, longClickable = n.isLongClickable, editable = n.isEditable,
      scrollable = n.isScrollable, checkable = n.isCheckable, checked = n.isChecked, selected = n.isSelected,
      enabled = n.isEnabled, focused = n.isFocused, visible = n.isVisibleToUser, depth = depth
    )
    for (i in 0 until n.childCount) {
      val c = n.getChild(i) ?: continue
      try { walk(c, path + i, depth + 1, out, max) } finally {
        @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT < 33) c.recycle()
      }
    }
  }

  private fun roleOf(n: AccessibilityNodeInfo): UiNode.Role {
    val c = n.className?.toString().orEmpty()
    return when {
      n.isEditable || c.endsWith("EditText") -> UiNode.Role.INPUT
      c.endsWith("Switch") || c.endsWith("ToggleButton") -> UiNode.Role.SWITCH
      c.endsWith("CheckBox") || c.endsWith("RadioButton") || n.isCheckable -> UiNode.Role.CHECKBOX
      c.endsWith("SeekBar") || c.endsWith("Slider") -> UiNode.Role.SLIDER
      c.endsWith("ProgressBar") -> UiNode.Role.PROGRESS
      c.endsWith("ImageView") || c.endsWith("ImageButton") && n.text.isNullOrBlank() && !n.isClickable -> UiNode.Role.IMAGE
      c.endsWith("Button") || c.endsWith("ImageButton") -> UiNode.Role.BUTTON
      c.endsWith("TabWidget") -> UiNode.Role.TAB
      c.endsWith("RecyclerView") || c.endsWith("ListView") || c.endsWith("GridView") -> UiNode.Role.LIST
      c.endsWith("ScrollView") || c.endsWith("ViewPager") -> UiNode.Role.SCROLL_AREA
      c.endsWith("Toolbar") || c.endsWith("ActionBar") -> UiNode.Role.TOOLBAR
      c.contains("Menu") -> UiNode.Role.MENU
      c.contains("Dialog") || c.contains("AlertController") -> UiNode.Role.DIALOG
      n.isClickable && (n.text != null || n.contentDescription != null) -> UiNode.Role.BUTTON
      c.endsWith("TextView") -> UiNode.Role.TEXT
      c.endsWith("ViewGroup") || c.endsWith("FrameLayout") || c.endsWith("LinearLayout") || c.endsWith("RelativeLayout") -> UiNode.Role.CONTAINER
      else -> UiNode.Role.OTHER
    }
  }

  // ---------------- node resolution ----------------

  private fun resolve(path: List<Int>): AccessibilityNodeInfo? {
    var cur: AccessibilityNodeInfo = rootInActiveWindow ?: return null
    for (idx in path) cur = cur.getChild(idx) ?: return null
    return cur
  }

  /** Clicks node; if the node itself isn't clickable, climbs to the nearest clickable ancestor. */
  fun clickNode(path: List<Int>): Boolean = actOnClickable(path, AccessibilityNodeInfo.ACTION_CLICK)
  fun longClickNode(path: List<Int>): Boolean = actOnClickable(path, AccessibilityNodeInfo.ACTION_LONG_CLICK)

  private fun actOnClickable(path: List<Int>, action: Int): Boolean {
    var p = path
    while (true) {
      val n = resolve(p)
      if (n != null) {
        val ok = if (action == AccessibilityNodeInfo.ACTION_CLICK) n.isClickable else n.isLongClickable
        if (ok && n.performAction(action)) return true
      }
      if (p.isEmpty()) return false
      p = p.dropLast(1)
    }
  }

  fun setText(path: List<Int>, text: String): Boolean {
    val n = resolve(path) ?: return false
    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
    return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
  }

  fun focusedInputPath(): List<Int>? {
    val root = rootInActiveWindow ?: return null
    val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: return null
    val snap = snapshot() ?: return null
    val r = Rect(); f.getBoundsInScreen(r)
    return snap.nodes.firstOrNull { it.editable && it.bounds == r }?.path
  }

  fun scrollNode(path: List<Int>, forward: Boolean): Boolean {
    var p = path
    while (true) {
      val n = resolve(p)
      if (n != null && n.isScrollable) {
        val a = if (forward) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD
        if (n.performAction(a.id)) return true
      }
      if (p.isEmpty()) return false
      p = p.dropLast(1)
    }
  }

  fun firstScrollablePath(): List<Int>? = snapshot()?.nodes?.firstOrNull { it.scrollable && it.visible }?.path

  fun globalBack() = performGlobalAction(GLOBAL_ACTION_BACK)
  fun globalHome() = performGlobalAction(GLOBAL_ACTION_HOME)
  fun globalRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
  fun globalNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

  // ---------------- gestures ----------------

  suspend fun gesture(points: List<Pair<Float, Float>>, durationMs: Long, startDelay: Long = 0): Boolean {
    if (points.isEmpty()) return false
    val path = Path().apply {
      moveTo(points[0].first, points[0].second)
      if (points.size == 1) lineTo(points[0].first + 0.1f, points[0].second) else points.drop(1).forEach { lineTo(it.first, it.second) }
    }
    val g = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, startDelay, durationMs.coerceAtLeast(1))).build()
    val done = CompletableDeferred<Boolean>()
    val accepted = dispatchGesture(g, object : GestureResultCallback() {
      override fun onCompleted(d: GestureDescription?) { done.complete(true) }
      override fun onCancelled(d: GestureDescription?) { done.complete(false) }
    }, null)
    if (!accepted) return false
    return withTimeoutOrNull(durationMs + 3000) { done.await() } ?: false
  }

  // ---------------- screenshot (API 30+, no MediaProjection needed) ----------------

  suspend fun screenshot(): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val result = CompletableDeferred<Bitmap?>()
    takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
      override fun onSuccess(s: ScreenshotResult) {
        val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
        hw?.recycle(); s.hardwareBuffer.close()
        result.complete(soft)
      }
      override fun onFailure(errorCode: Int) { result.complete(null) }
    })
    return withTimeoutOrNull(5000) { result.await() }
  }

  companion object {
    @Volatile var instance: JarvisAccessibilityService? = null
      private set
    @Volatile var lastForegroundPackage: String? = null
    @Volatile var lastWindowClass: String? = null
    @Volatile var lastEventAt: Long = 0L
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
  }
}
