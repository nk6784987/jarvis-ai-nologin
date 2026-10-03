package com.example.device

import android.graphics.Rect

/** One node of the real accessibility tree. [path] = child indices from the window root; used to re-resolve live nodes. */
data class UiNode(
  val path: List<Int>,
  val packageName: String,
  val className: String,
  val role: Role,
  val text: String,
  val contentDescription: String,
  val viewId: String,
  val bounds: Rect,
  val clickable: Boolean,
  val longClickable: Boolean,
  val editable: Boolean,
  val scrollable: Boolean,
  val checkable: Boolean,
  val checked: Boolean,
  val selected: Boolean,
  val enabled: Boolean,
  val focused: Boolean,
  val visible: Boolean,
  val depth: Int
) {
  val label: String get() = text.ifBlank { contentDescription }
  val centerX: Int get() = bounds.centerX()
  val centerY: Int get() = bounds.centerY()
  val id: String get() = path.joinToString(".")
  enum class Role { BUTTON, TEXT, INPUT, IMAGE, LIST, LIST_ITEM, CHECKBOX, SWITCH, SLIDER, TAB, MENU, DIALOG, LINK, PROGRESS, SCROLL_AREA, TOOLBAR, CONTAINER, OTHER }
}

data class WindowSnapshot(
  val packageName: String,
  val activityHint: String?,
  val nodes: List<UiNode>,
  val capturedAt: Long
) {
  val interactive: List<UiNode> get() = nodes.filter { it.visible && it.enabled && (it.clickable || it.editable || it.checkable || it.scrollable) }
  fun screenText(): String = nodes.filter { it.visible && it.label.isNotBlank() }.joinToString("\n") { it.label }
}

enum class ResultCode {
  OK, SERVICE_NOT_ENABLED, ELEMENT_NOT_FOUND, ACTION_FAILED, APP_NOT_INSTALLED, TIMEOUT,
  UNSUPPORTED_API, SCREENSHOT_UNAVAILABLE, VERIFICATION_FAILED, INVALID_ARGUMENT, BLOCKED_SECURE_SCREEN
}

data class DeviceResult<out T>(val code: ResultCode, val value: T? = null, val detail: String = "") {
  val ok: Boolean get() = code == ResultCode.OK
  companion object {
    fun <T> ok(v: T, detail: String = "") = DeviceResult(ResultCode.OK, v, detail)
    fun <T> fail(code: ResultCode, detail: String = "") = DeviceResult<T>(code, null, detail)
  }
}

/** What must be true on the real screen for an action to count as done. */
sealed class Expect {
  data class PackageIs(val pkg: String) : Expect()
  data class TextVisible(val text: String) : Expect()
  data class TextGone(val text: String) : Expect()
  data class FieldContains(val text: String) : Expect()
  data class ScreenChanged(val beforeHash: Int) : Expect()
  data class ElementChecked(val query: ElementQuery, val checked: Boolean) : Expect()
}

data class ElementQuery(
  val text: String? = null,
  val contentDescription: String? = null,
  val viewId: String? = null,
  val role: UiNode.Role? = null,
  val packageName: String? = null,
  val ordinal: Int? = null,          // 1-based among matches in top-to-bottom, left-to-right order
  val clickableOnly: Boolean = false,
  val editableOnly: Boolean = false
)
