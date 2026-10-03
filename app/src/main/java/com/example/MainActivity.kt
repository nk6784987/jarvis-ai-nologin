package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.permissions.Capability
import com.example.permissions.PermState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.ui.components.HudHeader
import com.example.ui.components.VoiceControlBar
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.viewmodel.JarvisScreen
import com.example.viewmodel.JarvisViewModel

class MainActivity : ComponentActivity() {
  private val vm: JarvisViewModel by viewModels()
  private var returnedFromSettings = false

  override fun onResume() {
    super.onResume()
    if (returnedFromSettings) { returnedFromSettings = false; vm.resolvePermission() }
    vm.refreshPermissions()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.getStringExtra("run_goal")?.let { vm.runGoalFromIntent(it) }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    intent?.getStringExtra("run_goal")?.let { vm.runGoalFromIntent(it) }
    setContent {
      MyApplicationTheme {
        val viewModel: JarvisViewModel = vm
        val activity = this@MainActivity
        val telemetry by viewModel.telemetry.collectAsState()
        val apiUi by viewModel.apiUi.collectAsState()
        val permissionRequest by viewModel.permissionRequest.collectAsState()
        val confirmation by viewModel.confirmation.collectAsState()

        val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.resolvePermission(); viewModel.refreshPermissions() }
        val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> viewModel.onScreenCaptureResult(r.resultCode, r.data) }

        fun grant(cap: Capability) {
          val runtime = viewModel.permissionsFor(cap)
          if (runtime.isNotEmpty()) runtimeLauncher.launch(runtime)
          else { returnedFromSettings = true; activity.startActivity(viewModel.settingsIntentFor(cap)) }
        }
        val currentScreen by viewModel.currentScreen.collectAsState()
        val jarvisState by viewModel.jarvisState.collectAsState()
        val agentStatus by viewModel.agentStatus.collectAsState()
        val activeTask by viewModel.activeTask.collectAsState()
        val taskHistory by viewModel.taskHistory.collectAsState()
        val memories by viewModel.memories.collectAsState()
        val skills by viewModel.skills.collectAsState()
        val isOnline by viewModel.isOnline.collectAsState()
        val audioLevel by viewModel.audioLevel.collectAsState()
        val settings by viewModel.settings.collectAsState()
        val latestActionPlan by viewModel.latestActionPlan.collectAsState()
        val messages by viewModel.chatMessages.collectAsState()
        val tasks by viewModel.tasks.collectAsState()

        var showQuickActions by remember { mutableStateOf(false) }

        Surface(
          modifier = Modifier.fillMaxSize(),
          color = JarvisBackground
        ) {
          Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
              modifier = Modifier.fillMaxSize(),
              containerColor = JarvisBackground,
              topBar = {
                HudHeader(
                  isOnline = isOnline,
                  jarvisState = jarvisState,
                  onToggleOnline = { viewModel.toggleOnlineStatus() },
                  onOpenSettings = { viewModel.navigateTo(JarvisScreen.SETTINGS) },
                  onOpenProfile = { viewModel.navigateTo(JarvisScreen.PROFILE) },
                  onOpenApi = { viewModel.navigateTo(JarvisScreen.API_CONFIG) }
                )
              },
              bottomBar = {
                VoiceControlBar(
                  jarvisState = jarvisState,
                  audioLevel = audioLevel,
                  onTriggerVoice = { viewModel.triggerVoiceInteraction() },
                  onOpenHome = { viewModel.navigateTo(JarvisScreen.HOME) },
                  onOpenConversation = { viewModel.navigateTo(JarvisScreen.CONVERSATION) },
                  onOpenTasks = { viewModel.navigateTo(JarvisScreen.TASKS) },
                  onOpenQuickActions = { showQuickActions = true }
                )
              }
            ) { innerPadding ->
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(innerPadding)
              ) {
                when (currentScreen) {
                  JarvisScreen.HOME -> HomeScreen(
                    jarvisState = jarvisState,
                    audioLevel = audioLevel,
                    latestActionPlan = latestActionPlan,
                    telemetry = telemetry,
                    onTriggerVoice = { viewModel.triggerVoiceInteraction() },
                    onSendCommand = { viewModel.processUserCommand(it) },
                    onOpenTasks = { viewModel.navigateTo(JarvisScreen.TASKS) }
                  )
                  JarvisScreen.CONVERSATION -> ConversationScreen(
                    messages = messages,
                    onSendMessage = { viewModel.processUserCommand(it) }
                  )
                  JarvisScreen.TASKS -> TaskPanelScreen(
                    tasks = tasks,
                    onRunPipeline = { viewModel.executeTaskPipeline() }
                  )
                  JarvisScreen.AGENT_DEBUG -> AgentDebugScreen(
                    activeTask = activeTask,
                    taskHistory = taskHistory,
                    onBack = { viewModel.navigateTo(JarvisScreen.HOME) }
                  )
                  JarvisScreen.MEMORY -> MemoryScreen(
                    memories = memories,
                    onDeleteMemory = { viewModel.deleteMemory(it) },
                    onClearAll = { viewModel.clearAllMemories() },
                    onBack = { viewModel.navigateTo(JarvisScreen.HOME) }
                  )
                  JarvisScreen.CAPABILITIES -> CapabilitiesScreen(
                    skills = skills,
                    onBack = { viewModel.navigateTo(JarvisScreen.HOME) }
                  )
                  JarvisScreen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onUpdateSettings = { viewModel.updateSettings(it) },
                    onBack = { viewModel.navigateTo(JarvisScreen.HOME) }
                  )
                  JarvisScreen.PROFILE -> ProfileScreen(
                    memoryCount = memories.size,
                    onClearAllData = { viewModel.clearAllLocalData() },
                    onOpenMemory = { viewModel.navigateTo(JarvisScreen.MEMORY) },
                    onBack = { viewModel.navigateTo(JarvisScreen.HOME) }
                  )
                  JarvisScreen.API_CONFIG -> ApiConfigScreen(
                    ui = apiUi,
                    agentStatus = agentStatus,
                    onOpenAccessibility = { activity.startActivity(viewModel.accessibilityIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onRequestPermission = { grant(it) },
                    onStartScreenCapture = { captureLauncher.launch(viewModel.screenCaptureIntent()) },
                    onAddProvider = { n, t, u, m, k -> viewModel.addProvider(n, t, u, m, k) },
                    onRemoveProvider = { viewModel.removeProvider(it) },
                    onTestProvider = { viewModel.testProvider(it) },
                    onSetPreferred = { viewModel.setPreferredModel(it) },
                    onSaveSearchKey = { id, k, cx -> viewModel.saveSearchKey(id, k, cx) },
                    onBack = { viewModel.navigateTo(JarvisScreen.HOME) }
                  )
                }
              }
            }

            permissionRequest?.let { req ->
              AlertDialog(
                onDismissRequest = { viewModel.cancelPermission() },
                title = { Text("${req.capability.label} permission chahiye") },
                text = { Text(req.reason) },
                confirmButton = { TextButton(onClick = { grant(req.capability) }) { Text("ALLOW") } },
                dismissButton = { TextButton(onClick = { viewModel.cancelPermission() }) { Text("NOT NOW") } }
              )
            }
            confirmation?.let { c ->
              AlertDialog(
                onDismissRequest = { viewModel.answerConfirmation(false) },
                title = { Text("Confirm action") },
                text = { Text(c.description) },
                confirmButton = { TextButton(onClick = { viewModel.answerConfirmation(true) }) { Text("YES, DO IT") } },
                dismissButton = { TextButton(onClick = { viewModel.answerConfirmation(false) }) { Text("CANCEL") } }
              )
            }

            // Quick Actions Overlay Sheet
            if (showQuickActions) {
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .background(JarvisBackground.copy(alpha = 0.92f))
              ) {
                QuickActionSheet(
                  onClose = { showQuickActions = false },
                  onNavigateTasks = { viewModel.navigateTo(JarvisScreen.TASKS) },
                  onNavigateSettings = { viewModel.navigateTo(JarvisScreen.SETTINGS) },
                  onNavigateProfile = { viewModel.navigateTo(JarvisScreen.PROFILE) },
                  onNavigateApi = { viewModel.navigateTo(JarvisScreen.API_CONFIG) },
                  onNavigateAgentDebug = { viewModel.navigateTo(JarvisScreen.AGENT_DEBUG) },
                  onNavigateMemory = { viewModel.navigateTo(JarvisScreen.MEMORY) },
                  onNavigateCapabilities = { viewModel.navigateTo(JarvisScreen.CAPABILITIES) }
                )
              }
            }
          }
        }
      }
    }
  }
}
