package com.example

import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.example.device.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceAgentQueryTest {
  private fun node(i: Int, text: String, top: Int, clickable: Boolean = true, role: UiNode.Role = UiNode.Role.BUTTON) = UiNode(
    listOf(i), "app", "android.widget.Button", role, text, "", "", Rect(0, top, 100, top + 50),
    clickable, false, false, false, false, false, false, true, false, true, 1)

  private val snap = WindowSnapshot("app", null, listOf(node(0, "Second", 200), node(1, "First", 100), node(2, "Label", 300, clickable = false, role = UiNode.Role.TEXT)), 0L)
  private val agent = DeviceAgent(ApplicationProvider.getApplicationContext())

  @Test fun ordinalIsTopToBottom() {
    assertEquals("First", agent.findElement(ElementQuery(ordinal = 1, clickableOnly = true), snap).value?.text)
    assertEquals("Second", agent.findElement(ElementQuery(ordinal = 2, clickableOnly = true), snap).value?.text)
  }
  @Test fun missingElementIsReportedNotFaked() {
    val r = agent.findElement(ElementQuery(text = "Nope"), snap)
    assertEquals(ResultCode.ELEMENT_NOT_FOUND, r.code)
  }
  @Test fun noAccessibilityServiceMeansExplicitFailure() {
    assertEquals(ResultCode.SERVICE_NOT_ENABLED, agent.getScreenState().code)
    assertEquals(ResultCode.SERVICE_NOT_ENABLED, agent.pressBack().code)
  }
  @Test fun openingUnknownAppFailsHonestly() = kotlinx.coroutines.runBlocking {
    assertEquals(ResultCode.APP_NOT_INSTALLED, agent.openApp("definitely-not-an-installed-app-xyz").code)
  }
}
