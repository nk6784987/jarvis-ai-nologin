package com.example.agent

/** Status of the native device agent as shown in the UI. Derived from real service state. */
enum class AgentStatus {
  OFFLINE,
  CONNECTING,
  READY,
  SCREEN_ACCESS_ACTIVE,
  EXECUTING,
  VERIFYING,
  WAITING_FOR_USER,
  ERROR
}
