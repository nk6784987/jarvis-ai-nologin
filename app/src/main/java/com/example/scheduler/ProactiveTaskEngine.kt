package com.example.scheduler

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class ProactiveTaskStatus { SCHEDULED, WAITING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
enum class TaskType { REMINDER, AGENT_GOAL }

data class ScheduledTask(
  val id: String = "",
  val goal: String = "",
  val schedule: String = "",
  var status: ProactiveTaskStatus = ProactiveTaskStatus.SCHEDULED,
  val createdAt: Long = System.currentTimeMillis(),
  val nextExecutionTime: Long = 0L,
  val result: String = "",
  val notificationState: Boolean = true,
  val type: TaskType = TaskType.REMINDER,
  val repeatIntervalMs: Long = 0L,
  val needsNetwork: Boolean = false
)

/**
 * Persistent scheduler. Local truth = SharedPreferences (survives restart/boot, works offline);
 * REMINDER -> AlarmManager exact alarm (falls back to WorkManager inexact if exact not permitted).
 * AGENT_GOAL -> WorkManager (network/battery constraints). Agent goals that need screen control
 * are delivered as a notification asking the user to tap to run - Android forbids silent UI automation from background.
 */
class ProactiveTaskEngine(private val context: Context) {
  private val prefs = context.getSharedPreferences("jarvis_tasks", Context.MODE_PRIVATE)
  private val _tasks = MutableStateFlow(load())
  val scheduledTasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

  private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "tasks") _tasks.value = load() }
  init { ensureChannel(context); prefs.registerOnSharedPreferenceChangeListener(prefListener) } // rescheduleAll() is called explicitly (app start / boot) - never from init, or a firing alarm would re-arm itself

  /** Called after boot/app start: re-arm everything not yet fired. */
  fun rescheduleAll() { _tasks.value.filter { it.status == ProactiveTaskStatus.SCHEDULED }.forEach { arm(it) } }

  fun schedule(goal: String, atMs: Long, type: TaskType = TaskType.REMINDER, repeatMs: Long = 0, needsNetwork: Boolean = false, label: String = ""): ScheduledTask {
    require(atMs > System.currentTimeMillis() - 1000) { "Time past mein hai" }
    val t = ScheduledTask(id = "t${System.currentTimeMillis()}", goal = goal,
      schedule = label.ifBlank { java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault()).format(atMs) + if (repeatMs > 0) " (repeats)" else "" },
      nextExecutionTime = atMs, type = type, repeatIntervalMs = repeatMs, needsNetwork = needsNetwork)
    save(_tasks.value + t); arm(t); mirror(t); return t
  }

  fun updateTaskStatus(id: String, s: ProactiveTaskStatus) {
    val t = _tasks.value.firstOrNull { it.id == id } ?: return
    if (s == ProactiveTaskStatus.PAUSED || s == ProactiveTaskStatus.CANCELLED) disarm(t)
    if (s == ProactiveTaskStatus.SCHEDULED) arm(t)
    val n = t.copy(status = s); save(_tasks.value.map { if (it.id == id) n else it }); mirror(n)
  }

  fun deleteTask(id: String) { _tasks.value.firstOrNull { it.id == id }?.let(::disarm); save(_tasks.value.filter { it.id != id }) }

  fun canScheduleExact() = Build.VERSION.SDK_INT < 31 || (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

  private fun arm(t: ScheduledTask) {
    val delay = (t.nextExecutionTime - System.currentTimeMillis()).coerceAtLeast(0)
    if (t.type == TaskType.REMINDER && canScheduleExact()) {
      val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.nextExecutionTime, pending(t))
    } else {
      val req = OneTimeWorkRequestBuilder<TaskWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
        .setInputData(workDataOf("id" to t.id)).setConstraints(Constraints.Builder().apply { if (t.needsNetwork) setRequiredNetworkType(NetworkType.CONNECTED) }.build())
        .addTag(t.id).build()
      WorkManager.getInstance(context).enqueueUniqueWork(t.id, ExistingWorkPolicy.REPLACE, req)
    }
  }

  private fun disarm(t: ScheduledTask) {
    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(t))
    WorkManager.getInstance(context).cancelUniqueWork(t.id)
  }

  private fun pending(t: ScheduledTask) = PendingIntent.getBroadcast(context, t.id.hashCode(),
    Intent(context, ReminderReceiver::class.java).putExtra("id", t.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

  private fun mirror(@Suppress("UNUSED_PARAMETER") t: ScheduledTask) { /* local only: state already persisted by save() */ }

  private fun save(list: List<ScheduledTask>) {
    _tasks.value = list
    val arr = JSONArray(); list.forEach { t -> arr.put(JSONObject().put("id", t.id).put("goal", t.goal).put("schedule", t.schedule).put("status", t.status.name)
      .put("createdAt", t.createdAt).put("next", t.nextExecutionTime).put("result", t.result).put("type", t.type.name).put("repeat", t.repeatIntervalMs).put("net", t.needsNetwork)) }
    prefs.edit().putString("tasks", arr.toString()).apply()
  }

  private fun load(): List<ScheduledTask> = runCatching {
    val a = JSONArray(prefs.getString("tasks", "[]")); (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
      ScheduledTask(o.getString("id"), o.getString("goal"), o.optString("schedule"), ProactiveTaskStatus.valueOf(o.getString("status")), o.optLong("createdAt"), o.optLong("next"),
        o.optString("result"), true, TaskType.valueOf(o.optString("type", "REMINDER")), o.optLong("repeat"), o.optBoolean("net")) }
  }.getOrDefault(emptyList())

  /** Invoked by receiver/worker when a task fires. Delivers a real notification, handles repeat, persists result. */
  fun onFired(id: String) {
    val t = _tasks.value.firstOrNull { it.id == id } ?: load().firstOrNull { it.id == id } ?: return
    if (t.status != ProactiveTaskStatus.SCHEDULED) return
    notify(context, t)
    if (t.repeatIntervalMs > 0) {
      var next = t.nextExecutionTime + t.repeatIntervalMs; while (next <= System.currentTimeMillis()) next += t.repeatIntervalMs
      val n = t.copy(nextExecutionTime = next, result = "Last fired ${java.util.Date()}"); save(_tasks.value.map { if (it.id == id) n else it }); arm(n); mirror(n)
    } else { val n = t.copy(status = ProactiveTaskStatus.COMPLETED, result = "Delivered ${java.util.Date()}"); save(_tasks.value.map { if (it.id == id) n else it }); mirror(n) }
  }

  companion object {
    const val CHANNEL = "jarvis_reminders"
    fun ensureChannel(c: Context) { if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)) }
    fun notify(c: Context, t: ScheduledTask) {
      if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
      val open = PendingIntent.getActivity(c, t.id.hashCode(), Intent(c, com.example.MainActivity::class.java).putExtra("run_goal", if (t.type == TaskType.AGENT_GOAL) t.goal else null), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(if (t.type == TaskType.REMINDER) "JARVIS Reminder" else "JARVIS task ready - tap to run")
        .setContentText(t.goal).setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_HIGH).build()
      c.getSystemService(NotificationManager::class.java).notify(t.id.hashCode(), n)
    }
    /** Created lazily for receivers/workers that run without the ViewModel. */
    fun standalone(c: Context) = ProactiveTaskEngine(c.applicationContext)
  }
}

class ReminderReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) { intent.getStringExtra("id")?.let { ProactiveTaskEngine.standalone(context).onFired(it) } }
}

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) { if (intent.action == Intent.ACTION_BOOT_COMPLETED) ProactiveTaskEngine.standalone(context).rescheduleAll() }
}

class TaskWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
  override suspend fun doWork(): Result { inputData.getString("id")?.let { ProactiveTaskEngine.standalone(applicationContext).onFired(it) }; return Result.success() }
}
