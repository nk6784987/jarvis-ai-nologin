package com.example.security

enum class ActionClass { READ_ONLY, REVERSIBLE, SENSITIVE, CONSEQUENTIAL }

/** Classifies every tool call; CONSEQUENTIAL/SENSITIVE ones need an explicit user confirmation before running. */
object SafetyGate {
  private val consequential = setOf("send_message", "send_email", "call", "delete_file", "share_file", "send_sms", "account_change", "payment")
  private val sensitive = setOf("share_screen", "read_contacts_list", "copy_file", "rename_file", "type_password")
  private val readOnly = setOf("read_screen", "screenshot", "ocr", "find_element", "web_search", "web_read", "list_files", "find_files", "get_current_app", "resolve_contact", "recall_memory")

  fun classify(tool: String): ActionClass = when {
    tool in consequential -> ActionClass.CONSEQUENTIAL
    tool in sensitive -> ActionClass.SENSITIVE
    tool in readOnly -> ActionClass.READ_ONLY
    else -> ActionClass.REVERSIBLE
  }

  fun needsConfirmation(tool: String) = classify(tool).let { it == ActionClass.CONSEQUENTIAL || it == ActionClass.SENSITIVE }

  /** Screens that show these are never driven by the agent without the user (payment/OTP/password). */
  private val financialMarkers = listOf("pay now", "confirm payment", "enter upi pin", "upi pin", "cvv", "otp", "transfer money", "place order", "buy now")
  fun looksFinancial(screenText: String) = financialMarkers.any { screenText.contains(it, true) }
}
