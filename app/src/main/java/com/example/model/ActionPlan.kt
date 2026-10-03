package com.example.model

data class ActionStep(
  val action: String, // OPEN_APP, SEARCH, SELECT, PLAY, SCROLL, BACK, DOWNLOAD, GO_TO
  val target: String? = null,
  val query: String? = null,
  val position: Int? = null,
  val amount: String? = null
)

data class ActionPlan(
  val goal: String,
  val steps: List<ActionStep>,
  val requiresConfirmation: Boolean = false
)

data class ScreenContext(
  val currentApp: String = "Home",
  val visibleElements: List<String> = emptyList(),
  val lastSelectedItem: String? = null,
  val currentUrl: String? = null
)
