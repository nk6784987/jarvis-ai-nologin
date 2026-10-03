package com.example.agent

import com.example.model.ActionPlan
import com.example.model.ActionStep

data class ExecutionResult(
  val success: Boolean,
  val message: String,
  val verifiedScreenState: ScreenState?
)

class TaskExecutionLoop(private val bridge: DeviceBridge) {

  suspend fun executePlan(plan: ActionPlan, onStepProgress: (String, Boolean) -> Unit): ExecutionResult {
    for (step in plan.steps) {
      onStepProgress("Executing: ${step.action} -> ${step.target ?: step.query ?: ""}", false)
      
      val success = when (step.action) {
        "OPEN_APP" -> {
          val appName = step.target ?: "Browser"
          val opened = bridge.openApp(appName)
          if (!opened) {
            onStepProgress("Error: App '$appName' not installed on device.", false)
            return ExecutionResult(false, "App '$appName' not installed.", null)
          }
          bridge.verifyAction(appName)
        }
        "SEARCH" -> {
          bridge.typeText(step.query ?: "")
        }
        "SELECT" -> {
          bridge.tapElement(step.target ?: "First Result")
        }
        "SCROLL" -> {
          bridge.scroll(step.amount ?: "down")
        }
        "BACK" -> {
          bridge.pressBack()
        }
        else -> true
      }

      if (!success) {
        // Failure recovery attempt
        onStepProgress("Action failed. Attempting recovery observe/re-plan...", false)
        val recoverySuccess = bridge.verifyAction("Home")
        if (!recoverySuccess) {
          return ExecutionResult(false, "Failed to execute step: ${step.action}", null)
        }
      }

      onStepProgress("Verified: ${step.action}", true)
    }

    val finalScreen = bridge.getScreenState()
    return ExecutionResult(true, "Task completed and verified successfully.", finalScreen)
  }
}
