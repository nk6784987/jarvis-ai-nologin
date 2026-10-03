package com.example

import com.example.security.ActionClass
import com.example.security.SafetyGate
import org.junit.Assert.*
import org.junit.Test

class SafetyGateTest {
  @Test fun consequentialToolsNeedConfirmation() {
    listOf("send_message", "send_email", "call", "delete_file", "share_file").forEach {
      assertEquals(it, ActionClass.CONSEQUENTIAL, SafetyGate.classify(it)); assertTrue(SafetyGate.needsConfirmation(it))
    }
  }
  @Test fun readOnlyToolsDoNot() {
    listOf("read_screen", "web_search", "find_files", "resolve_contact").forEach { assertFalse(SafetyGate.needsConfirmation(it)) }
  }
  @Test fun financialScreensAreDetected() {
    assertTrue(SafetyGate.looksFinancial("Enter UPI PIN to continue"))
    assertFalse(SafetyGate.looksFinancial("Iron Man trailer - YouTube"))
  }
}
