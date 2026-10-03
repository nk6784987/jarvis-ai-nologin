package com.example.agent

enum class TaskState { CREATED, UNDERSTANDING, PLANNING, EXECUTING, WAITING, VERIFYING, RECOVERING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class AgentPrimitiveAction(val primitive: String, val target: String, val param: String? = null)

data class SubTask(
  val id: String,
  val title: String,
  var status: TaskState,
  val primitiveAction: AgentPrimitiveAction? = null,
  var detail: String = ""
)

data class AutonomousTask(
  val id: String,
  val goal: String,
  var state: TaskState,
  val subTasks: MutableList<SubTask>,
  val actionHistory: MutableList<String> = mutableListOf(),
  var currentStepIndex: Int = 0,
  var finalMessage: String = "",
  var verified: Boolean = false
)

/** Holds live task state. Sub-tasks are appended by AgentLoop as the model really decides them - no canned plans. */
class AutonomousAgentBrain {
  private var activeTask: AutonomousTask? = null
  private val taskHistoryList = mutableListOf<AutonomousTask>()

  fun createGoalTask(goal: String): AutonomousTask = AutonomousTask(System.currentTimeMillis().toString(), goal, TaskState.UNDERSTANDING, mutableListOf()).also { activeTask = it }
  fun getActiveTask(): AutonomousTask? = activeTask
  fun getTaskHistory(): List<AutonomousTask> = taskHistoryList

  fun addStep(task: AutonomousTask, title: String, primitive: String, target: String): SubTask =
    SubTask("${task.subTasks.size + 1}", title, TaskState.EXECUTING, AgentPrimitiveAction(primitive, target)).also { task.subTasks += it; task.currentStepIndex = task.subTasks.size - 1; task.state = TaskState.EXECUTING }

  fun finish(task: AutonomousTask, state: TaskState, message: String, verified: Boolean) {
    task.state = state; task.finalMessage = message; task.verified = verified
    taskHistoryList.add(task); if (activeTask === task) activeTask = null
  }

  fun cancelActiveTask() { activeTask?.let { finish(it, TaskState.CANCELLED, "Cancelled by user", false) } }
}
