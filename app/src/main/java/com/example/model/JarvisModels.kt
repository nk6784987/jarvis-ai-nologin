package com.example.model

import com.example.agent.AgentStatus

enum class JarvisState {
  READY,
  LISTENING,
  THINKING,
  PROCESSING,
  SPEAKING,
  INTERRUPTED,
  EXECUTING,
  VERIFYING,
  COMPLETED,
  ERROR,
  OFFLINE
}

enum class TaskStatus {
  PENDING,
  EXECUTING,
  COMPLETED,
  ERROR
}

data class TaskItem(
  val id: String,
  val title: String,
  val status: TaskStatus,
  val detail: String = ""
)

data class ChatMessage(
  val id: String,
  val isUser: Boolean,
  val text: String,
  val timestamp: String,
  val intent: String? = null,
  val target: String? = null
)

data class ApiServiceConfig(
  val id: String,
  val name: String,
  val type: String,
  val status: ApiStatus,
  val latencyMs: Int
)

enum class ApiStatus {
  CONNECTED,
  NOT_CONNECTED,
  TESTING,
  WORKING,
  ERROR
}

data class UserProfileData(
  val name: String,
  val designation: String,
  val clearance: String,
  val neuralSync: Float,
  val coreVersion: String,
  val avatarUrl: String
)

data class JarvisSettings(
  val voiceEnabled: Boolean = true,
  val wakeWordEnabled: Boolean = false,
  val autoListening: Boolean = false,
  val interruptEnabled: Boolean = true,
  val speechSpeed: Float = 1.0f,
  val volume: Float = 0.8f,
  /** Unrestricted mode: skip confirmation prompts and use relaxed agent limits. Payment/OTP/PIN screens stay blocked. */
  val unrestrictedMode: Boolean = false,
  val agentStatus: AgentStatus = AgentStatus.OFFLINE
)
