package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.*
import com.example.ai.*
import com.example.data.LocalStore
import com.example.data.UserMemoryItem
import com.example.communication.CommunicationRouter
import com.example.communication.ContactResolver
import com.example.device.DeviceAgent
import com.example.device.FileManager
import com.example.device.JarvisAccessibilityService
import com.example.device.ScreenCaptureService
import com.example.memory.MemoryManager
import com.example.model.*
import com.example.permissions.Capability
import com.example.permissions.PermState
import com.example.permissions.PermissionCenter
import com.example.scheduler.ProactiveTaskEngine
import com.example.scheduler.ProactiveTaskStatus
import com.example.scheduler.ScheduledTask
import com.example.security.SecureStore
import com.example.skills.SkillDefinition
import com.example.skills.SkillHealthStatus
import com.example.skills.SkillRegistry
import com.example.tools.*
import com.example.vision.VisualIntelligenceEngine
import com.example.voice.*
import com.example.web.WebIntelligenceEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class JarvisScreen { HOME, CONVERSATION, TASKS, SETTINGS, PROFILE, API_CONFIG, AGENT_DEBUG, MEMORY, CAPABILITIES }

class JarvisViewModel(application: Application) : AndroidViewModel(application) {
  private val app = application

  // ---------- core services (all real) ----------
  private val secure = SecureStore(app)
  private val store = LocalStore(app)
  private val providerStore = ProviderStore(app, secure)
  val aiService = AiService(providerStore)
  private val device = DeviceAgent(app)
  private val vision = VisualIntelligenceEngine(device)
  private val web = WebIntelligenceEngine(secure, aiService)
  private val files = FileManager(app)
  private val contacts = ContactResolver(app)
  private val comm = CommunicationRouter(app, device)
  private val scheduler = ProactiveTaskEngine(app)
  private val memory = MemoryManager(store, aiService)
  private val perms = PermissionCenter(app)
  private val brain = AutonomousAgentBrain()
  private val tools: List<Tool> = buildTools(Deps(device, vision, web, files, comm, contacts, scheduler, memory))
  private val registry = SkillRegistry({ tools }, perms) { skillId -> extraHealth(skillId) }
  private val router = ToolRouter(tools, registry, perms, confirm = { askConfirmation(it) }, requestPermission = { askPermission(it) },
    unrestricted = { _settings.value.unrestrictedMode }, onAutoApproved = { addBot("Auto-approved (Unrestricted): $it") })
  private val agent = AgentLoop(aiService, router, memory, brain, observeScreen = { observe() }, onProgress = { t, s -> onAgentProgress(t, s) }, relaxedLimits = { _settings.value.unrestrictedMode })

  private val stt: SttProvider = AndroidSttProvider(app)
  private val tts: TtsProvider = AndroidTtsProvider(app)
  private val prefs = app.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

  private var agentJob: Job? = null

  // ---------- UI state ----------
  private val _currentScreen = MutableStateFlow(JarvisScreen.HOME)
  val currentScreen: StateFlow<JarvisScreen> = _currentScreen.asStateFlow()
  private val _jarvisState = MutableStateFlow(JarvisState.READY)
  val jarvisState: StateFlow<JarvisState> = _jarvisState.asStateFlow()
  private val _agentStatus = MutableStateFlow(AgentStatus.OFFLINE)
  val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()
  private val _activeTask = MutableStateFlow<AutonomousTask?>(null)
  val activeTask: StateFlow<AutonomousTask?> = _activeTask.asStateFlow()
  private val _taskHistory = MutableStateFlow<List<AutonomousTask>>(emptyList())
  val taskHistory: StateFlow<List<AutonomousTask>> = _taskHistory.asStateFlow()

  val memories: StateFlow<List<UserMemoryItem>> = store.memories
  val scheduledTasks: StateFlow<List<ScheduledTask>> = scheduler.scheduledTasks

  private val _skills = MutableStateFlow<List<SkillDefinition>>(emptyList())
  val skills: StateFlow<List<SkillDefinition>> = _skills.asStateFlow()
  private val _isOnline = MutableStateFlow(true)
  val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
  private val _audioLevel = MutableStateFlow(0.1f)
  val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
  private val _settings = MutableStateFlow(loadSettings())
  val settings: StateFlow<JarvisSettings> = _settings.asStateFlow()
  private val _latestActionPlan = MutableStateFlow<ActionPlan?>(null)
  val latestActionPlan: StateFlow<ActionPlan?> = _latestActionPlan.asStateFlow()
  private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()
  private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
  val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()
  private val _apiUi = MutableStateFlow(ApiConfigUi())
  val apiUi: StateFlow<ApiConfigUi> = _apiUi.asStateFlow()
  private val _telemetry = MutableStateFlow<List<Pair<String, String>>>(emptyList())
  val telemetry: StateFlow<List<Pair<String, String>>> = _telemetry.asStateFlow()

  /** Activity observes these and shows the system dialog / app dialog, then calls resolve*(). */
  private val _permissionRequest = MutableStateFlow<PermissionRequest?>(null)
  val permissionRequest: StateFlow<PermissionRequest?> = _permissionRequest.asStateFlow()
  private val _confirmation = MutableStateFlow<ConfirmationRequest?>(null)
  val confirmation: StateFlow<ConfirmationRequest?> = _confirmation.asStateFlow()
  private var permDeferred: CompletableDeferred<Boolean>? = null
  private var confirmDeferred: CompletableDeferred<Boolean>? = null

  init {
    ProactiveTaskEngine.ensureChannel(app)
    scheduler.rescheduleAll()
    watchNetwork()
    viewModelScope.launch { JarvisAccessibilityService.connected.collect { refreshDeviceState() } }
    viewModelScope.launch { ScreenCaptureService.active.collect { refreshDeviceState() } }
    viewModelScope.launch { aiService.status.collect { refreshApiUi() } }
    viewModelScope.launch { aiService.health.collect { refreshApiUi() } }
    viewModelScope.launch { scheduler.scheduledTasks.collect { refreshTasksList() } }
    viewModelScope.launch { while (isActive) { refreshTelemetry(); delay(5000) } }
    viewModelScope.launch(Dispatchers.IO) { if (aiService.hasUsableProvider) aiService.refresh() }
    refreshDeviceState()
  }

  // ---------- navigation / settings ----------
  fun navigateTo(screen: JarvisScreen) { _currentScreen.value = screen; if (screen == JarvisScreen.CAPABILITIES || screen == JarvisScreen.API_CONFIG) refreshDeviceState() }

  fun updateSettings(s: JarvisSettings) {
    _settings.value = s; tts.setSpeed(s.speechSpeed); tts.setVolume(s.volume)
    prefs.edit().putBoolean("voice", s.voiceEnabled).putBoolean("wake", s.wakeWordEnabled).putBoolean("auto", s.autoListening)
      .putBoolean("interrupt", s.interruptEnabled).putBoolean("unrestricted", s.unrestrictedMode).putFloat("speed", s.speechSpeed).putFloat("vol", s.volume).apply()
  }

  private fun loadSettings() = JarvisSettings(
    voiceEnabled = prefs.getBoolean("voice", true), wakeWordEnabled = prefs.getBoolean("wake", false), autoListening = prefs.getBoolean("auto", false),
    interruptEnabled = prefs.getBoolean("interrupt", true), unrestrictedMode = prefs.getBoolean("unrestricted", false), speechSpeed = prefs.getFloat("speed", 1f), volume = prefs.getFloat("vol", 0.8f))

  /** HudHeader's online chip now re-runs real connectivity + provider health instead of faking a toggle. */
  fun toggleOnlineStatus() { viewModelScope.launch(Dispatchers.IO) { _isOnline.value = hasInternet(); aiService.refresh() } }

  // ---------- memory (on-device) ----------
  fun deleteMemory(id: String) = store.deleteMemory(id)
  fun clearAllMemories() = store.clearAllMemory()
  fun clearAllLocalData() { agentJob?.cancel(); store.clearEverything(); memory.clearTemporary(); _chatMessages.value = emptyList() }

  // ---------- permissions & confirmations (suspend points used by ToolRouter) ----------
  private suspend fun askPermission(cap: Capability): Boolean {
    val d = CompletableDeferred<Boolean>(); permDeferred = d
    _permissionRequest.value = PermissionRequest(cap, cap.why)
    _agentStatus.value = AgentStatus.WAITING_FOR_USER
    val ok = withTimeoutOrNull(120_000) { d.await() } ?: false
    _permissionRequest.value = null; permDeferred = null; refreshDeviceState()
    return ok && perms.isGranted(cap)
  }

  /** Activity calls this after the system dialog / settings screen returns. */
  fun resolvePermission() { permDeferred?.complete(true) }
  fun cancelPermission() { permDeferred?.complete(false) }
  fun permissionsFor(c: Capability): Array<String> = perms.runtimePermissions(c)
  fun settingsIntentFor(c: Capability): Intent = perms.settingsIntent(c)
  fun permissionState(c: Capability): PermState = perms.state(c)
  fun refreshPermissions() = refreshDeviceState()

  private suspend fun askConfirmation(description: String): Boolean {
    val d = CompletableDeferred<Boolean>(); confirmDeferred = d
    _confirmation.value = ConfirmationRequest(description)
    _agentStatus.value = AgentStatus.WAITING_FOR_USER
    addBot("Confirm: $description (haan / nahi)"); speak("Confirm: $description")
    val ok = withTimeoutOrNull(90_000) { d.await() } ?: false
    _confirmation.value = null; confirmDeferred = null
    return ok
  }

  fun answerConfirmation(approved: Boolean) { confirmDeferred?.complete(approved) }

  // ---------- voice ----------
  fun triggerVoiceInteraction() {
    when (_jarvisState.value) {
      JarvisState.SPEAKING -> interruptSpeech()
      JarvisState.LISTENING -> { stt.stopListening(); _jarvisState.value = JarvisState.READY }
      else -> startListening()
    }
  }

  private fun startListening() {
    if (!perms.isGranted(Capability.MICROPHONE)) {
      viewModelScope.launch { if (askPermission(Capability.MICROPHONE)) startListening() else fail("Microphone permission ke bina awaaz command nahi chal sakti.") }
      return
    }
    _jarvisState.value = JarvisState.LISTENING
    stt.startListening(
      onResult = { _audioLevel.value = 0.1f; processUserCommand(it) },
      onError = { msg ->
        _audioLevel.value = 0.1f; _jarvisState.value = JarvisState.ERROR; addBot(msg.substringAfter(": "))
        viewModelScope.launch { delay(1500); if (_jarvisState.value == JarvisState.ERROR) _jarvisState.value = JarvisState.READY }
      },
      onLevel = { _audioLevel.value = it }
    )
  }

  fun interruptSpeech() {
    tts.stop(); _jarvisState.value = JarvisState.INTERRUPTED
    viewModelScope.launch { delay(400); if (_jarvisState.value == JarvisState.INTERRUPTED) _jarvisState.value = JarvisState.READY }
  }

  private fun speak(text: String) {
    if (!_settings.value.voiceEnabled) return
    _jarvisState.value = JarvisState.SPEAKING
    tts.speak(text,
      onComplete = { if (_jarvisState.value == JarvisState.SPEAKING) _jarvisState.value = JarvisState.READY },
      onError = { if (_jarvisState.value == JarvisState.SPEAKING) _jarvisState.value = JarvisState.READY })
  }

  // ---------- command handling ----------
  private fun isYes(q: String) = q in setOf("haan", "han", "yes", "ok", "okay", "kar do", "bhej do", "ha", "हाँ", "हां", "हा", "कर दो", "भेज दो", "confirm")
  private fun isNo(q: String) = q in setOf("nahi", "nahin", "no", "cancel", "ruk jao", "mat karo", "नहीं", "मत करो", "रुको")

  fun processUserCommand(query: String) {
    if (query.isBlank()) return
    val q = query.trim()
    _chatMessages.update { it + ChatMessage(System.nanoTime().toString(), true, q, now()) }
    val ql = q.lowercase()

    // answer to a pending confirmation prompt (voice or text)
    if (confirmDeferred != null) {
      if (isYes(ql)) { answerConfirmation(true); return }
      if (isNo(ql)) { answerConfirmation(false); return }
    }
    if (ql == "stop" || ql == "cancel" || ql == "ruko" || ql == "रुको") { cancelCurrent("Task cancel kar diya."); return }
    if (agentJob?.isActive == true) { addBot("Abhi ek task chal raha hai. 'stop' bolein ya khatam hone dein."); return }
    if (!_isOnline.value) { fail("Internet nahi hai - AI provider tak pahunch nahi sakta."); return }

    agentJob = viewModelScope.launch {
      _jarvisState.value = JarvisState.THINKING
      memory.addTurn("user", q)
      val outcome = try { agent.run(q) } catch (e: CancellationException) { throw e } catch (e: Exception) {
        AgentOutcome(TaskState.FAILED, "Internal error: ${e.javaClass.simpleName}: ${e.message}", false)
      }
      memory.addTurn("assistant", outcome.message)
      memory.logTask(q, outcome.message, outcome.steps, outcome.state == TaskState.COMPLETED && outcome.verified)
      _taskHistory.value = brain.getTaskHistory().toList(); _activeTask.value = null
      _agentStatus.value = if (device.isServiceEnabled) AgentStatus.READY else AgentStatus.OFFLINE
      _jarvisState.value = when (outcome.state) { TaskState.COMPLETED -> JarvisState.COMPLETED; TaskState.FAILED -> JarvisState.ERROR; else -> JarvisState.READY }
      addBot(outcome.message + (outcome.modelUsed?.let { "\n- via $it" } ?: ""))
      speak(outcome.message)
      delay(1200)
      if (_jarvisState.value == JarvisState.COMPLETED || _jarvisState.value == JarvisState.ERROR) _jarvisState.value = JarvisState.READY
      if (memory.recentTurns().size >= 10) launch { memory.summariseAndStore() }
    }
  }

  private fun cancelCurrent(msg: String) {
    agentJob?.cancel(); brain.cancelActiveTask(); tts.stop(); stt.stopListening()
    confirmDeferred?.complete(false); permDeferred?.complete(false)
    _activeTask.value = null; _jarvisState.value = JarvisState.READY; addBot(msg)
  }

  /** Tasks-screen button: shows the live agent task view. There is no canned pipeline any more. */
  fun executeTaskPipeline() { navigateTo(JarvisScreen.AGENT_DEBUG) }

  fun runGoalFromIntent(goal: String) { processUserCommand(goal) }

  private fun onAgentProgress(task: AutonomousTask, sub: SubTask?) {
    val terminal = task.state in setOf(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.WAITING)
    _activeTask.value = if (terminal) null else task.copy(subTasks = task.subTasks.toMutableList())
    _jarvisState.value = when (task.state) {
      TaskState.PLANNING, TaskState.UNDERSTANDING -> JarvisState.THINKING
      TaskState.EXECUTING, TaskState.RECOVERING -> JarvisState.EXECUTING
      TaskState.VERIFYING -> JarvisState.VERIFYING
      else -> _jarvisState.value
    }
    if (task.state == TaskState.EXECUTING || task.state == TaskState.RECOVERING) _agentStatus.value = AgentStatus.EXECUTING
    _latestActionPlan.value = ActionPlan(task.goal, task.subTasks.map { ActionStep(it.primitiveAction?.primitive ?: "STEP", target = it.primitiveAction?.target) })
    _tasks.value = task.subTasks.map { s ->
      TaskItem(s.id, s.title, when (s.status) {
        TaskState.COMPLETED -> TaskStatus.COMPLETED; TaskState.FAILED -> TaskStatus.ERROR
        TaskState.EXECUTING -> TaskStatus.EXECUTING; else -> TaskStatus.PENDING }, s.detail)
    } + scheduledAsTasks()
  }

  private suspend fun observe(): String {
    delay(500)
    val r = device.getScreenState()
    if (!r.ok) return "unavailable (${r.code})"
    val s = r.value!!
    return "app=${s.packageName}; text=" + s.screenText().replace("\n", " | ").take(500)
  }

  // ---------- scheduled tasks ----------
  fun updateScheduledTask(id: String, status: ProactiveTaskStatus) = scheduler.updateTaskStatus(id, status)
  fun deleteScheduledTask(id: String) = scheduler.deleteTask(id)

  // ---------- API configuration actions ----------
  fun addProvider(name: String, type: ProviderTypeUi, baseUrl: String, models: String, key: String) {
    viewModelScope.launch {
      _apiUi.update { it.copy(busy = true, message = "Validate ho raha hai...") }
      val cfg = ProviderConfig(
        id = name.lowercase().replace(Regex("[^a-z0-9]+"), "_") + "_" + System.currentTimeMillis() % 10000, name = name, type = type.type,
        baseUrl = baseUrl.trim(), requestedModels = models.split(",").map { it.trim() }.filter { it.isNotBlank() })
      withContext(Dispatchers.IO) { aiService.addProvider(cfg, key) }
      val st = aiService.status.value.firstOrNull { it.provider.id == cfg.id }
      _apiUi.update { it.copy(busy = false, message = if (st?.valid == true) "$name: key valid, ${st.models.size} model(s) mile." else "$name: ${st?.message ?: "validation fail"}") }
    }
  }
  fun removeProvider(id: String) { viewModelScope.launch { aiService.removeProvider(id); refreshApiUi() } }
  fun testProvider(id: String) { viewModelScope.launch(Dispatchers.IO) { _apiUi.update { it.copy(busy = true) }; aiService.refresh(id); _apiUi.update { it.copy(busy = false) } } }
  fun setPreferredModel(key: String?) { aiService.setPreferred(key); refreshApiUi() }
  fun saveSearchKey(providerId: String, key: String, cx: String) { web.configure(providerId, key, cx.ifBlank { null }); refreshApiUi() }

  // ---------- screen capture ----------
  fun screenCaptureIntent(): Intent = ScreenCaptureService.consentIntent(app)
  fun onScreenCaptureResult(code: Int, data: Intent?) { ScreenCaptureService.onConsentResult(app, code, data) }
  fun accessibilityIntent(): Intent = device.accessibilitySettingsIntent()

  // ---------- derived state ----------
  private fun extraHealth(skillId: String): Pair<SkillHealthStatus, String>? = when (skillId) {
    "web_search" -> if (!web.anyConfigured()) SkillHealthStatus.UNAVAILABLE to "Search provider key set nahi" else if (!_isOnline.value) SkillHealthStatus.NETWORK_REQUIRED to "Offline" else null
    "android_control", "screen_vision" -> if (!device.isServiceEnabled) SkillHealthStatus.PERMISSION_REQUIRED to "Accessibility service OFF" else null
    "messaging" -> if (comm.whatsappPackage == null) SkillHealthStatus.UNAVAILABLE to "WhatsApp installed nahi (SMS/share sheet chalega)" else null
    "downloads", "web_browser" -> if (!_isOnline.value) SkillHealthStatus.NETWORK_REQUIRED to "Offline" else null
    else -> null
  }

  private fun refreshDeviceState() {
    _agentStatus.value = when {
      _agentStatus.value == AgentStatus.EXECUTING -> AgentStatus.EXECUTING
      ScreenCaptureService.active.value && device.isServiceEnabled -> AgentStatus.SCREEN_ACCESS_ACTIVE
      device.isServiceEnabled -> AgentStatus.READY
      else -> AgentStatus.OFFLINE
    }
    refreshApiUi()
  }

  private fun refreshApiUi() {
    val health = aiService.health.value
    val providers = aiService.status.value.map { st ->
      val ms = st.models.map { it.key }
      val best = ms.mapNotNull { health[it] }.filter { it.responded }.minByOrNull { it.avgLatencyMs ?: Long.MAX_VALUE }
      val status = when {
        !st.keyPresent -> ApiStatus.NOT_CONNECTED; st.valid == false -> ApiStatus.ERROR
        best != null -> ApiStatus.WORKING; st.valid == true -> ApiStatus.CONNECTED; else -> ApiStatus.TESTING }
      ProviderUi(ApiServiceConfig(st.provider.id, st.provider.name, "${st.provider.type.name} • ${st.models.size} models", status, (best?.avgLatencyMs ?: 0L).toInt()), st.provider.id, ms, st.message?.take(160))
    }
    val known = providers.map { it.providerId }.toSet()
    val pending = aiService.configs().filter { it.id !in known }.map { c ->
      ProviderUi(ApiServiceConfig(c.id, c.name, c.type.name, ApiStatus.TESTING, 0), c.id, emptyList(), null) }
    _apiUi.update {
      it.copy(
        accessibilityEnabled = device.isServiceEnabled, screenCaptureActive = ScreenCaptureService.active.value, permissions = perms.snapshot(),
        providers = providers + pending, preferredModelKey = providerStore.preferredModelKey,
        searchProviders = web.providers.map { p -> SearchProviderUi(p.id, p.displayName, p.isConfigured(), p.id == "google_cse") })
    }
    _skills.value = registry.getAllSkills()
  }

  private fun refreshTasksList() { if (agentJob?.isActive != true) _tasks.value = scheduledAsTasks() }

  private fun scheduledAsTasks() = scheduler.scheduledTasks.value.map { t ->
    TaskItem(t.id, t.goal, when (t.status) { ProactiveTaskStatus.COMPLETED -> TaskStatus.COMPLETED; ProactiveTaskStatus.FAILED -> TaskStatus.ERROR; else -> TaskStatus.PENDING }, "${t.schedule} • ${t.status.name}")
  }

  private fun refreshTelemetry() {
    val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val batt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val bi = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val temp = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it > 0 }?.let { "%.1f°C".format(it / 10f) } ?: "n/a"
    val lat = aiService.health.value.values.filter { it.responded }.mapNotNull { it.avgLatencyMs }
    _telemetry.value = listOf("BATTERY" to "$batt%", "DEVICE TEMP" to temp, "AI LATENCY" to (if (lat.isEmpty()) "n/a" else "${lat.min()}ms"))
  }

  private fun watchNetwork() {
    val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    _isOnline.value = hasInternet()
    cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) { _isOnline.value = true; if (_jarvisState.value == JarvisState.OFFLINE) _jarvisState.value = JarvisState.READY }
      override fun onLost(network: Network) { _isOnline.value = false; _jarvisState.value = JarvisState.OFFLINE }
    })
  }

  private fun hasInternet(): Boolean {
    val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
  }

  private fun now() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
  private fun addBot(text: String) { _chatMessages.update { it + ChatMessage(System.nanoTime().toString(), false, text, now()) } }
  private fun fail(msg: String) {
    addBot(msg); _jarvisState.value = JarvisState.ERROR
    viewModelScope.launch { delay(1500); _jarvisState.value = JarvisState.READY }
    speak(msg)
  }

  override fun onCleared() { stt.destroy(); tts.destroy(); super.onCleared() }
}
