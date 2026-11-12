
package com.ghost.api

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.os.IBinder
import com.ghost.api.database.MemoryManager
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import java.util.UUID
import com.ghost.api.hardware.HardwareToolSet
import com.ghost.api.hardware.NetworkToolSet
import com.ghost.api.hardware.SystemToolSet
import com.ghost.api.hardware.AutomationToolSet
import com.ghost.api.hardware.ShakeDetector
import com.ghost.api.hardware.AudioRecorder
import com.ghost.api.hardware.HardwarePropertiesManager
import android.graphics.Bitmap
import com.ghost.api.ui.OverlayManager
import com.ghost.api.database.ConversationTurn
import java.util.concurrent.atomic.AtomicReference
import com.ghost.api.agent.AgentPlatformCallbacks
import com.ghost.api.agent.GhostAgent
import com.ghost.api.agent.GhostMcpTool
import com.ghost.api.mcp.MCPServer
import com.ghost.api.hardware.SensorFusionManager
import com.ghost.api.hardware.BatteryState
import com.ghost.api.hardware.DeviceContext
import com.ghost.api.logic.ContextManager

/**
 * Background service that loads Gemma and runs API server
 *
 * NEW: Google ADK Architecture with Dynamic MCP Toolset
 * - GhostAgent handles orchestration (perceive-think-act loop)
 * - GhostMcpTool exposes lean dynamic action router (~80 tokens)
 * - GemmaEngine is pure inference
 */
class GemmaService : Service(), AgentPlatformCallbacks {
    
    companion object {
        var instance: GemmaService? = null
            private set
        @Volatile var isInferencing: Boolean = false
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): GemmaService = this@GemmaService
    }
    
    // UI streaming interface for the native chat activity
    interface UiCallback {
        fun onMessageAdded(
            message: String,
            isUser: Boolean,
            isComplete: Boolean = true,
            image: android.graphics.Bitmap? = null,
            imageUri: String? = null,
            images: List<android.graphics.Bitmap> = emptyList(),
            audioData: ByteArray? = null,
            audioDurationMs: Long? = null,
            webviewUrl: String? = null,
            webviewAspectRatio: Float? = null
        )
        fun onThinkingStateChanged(isThinking: Boolean)
        fun onThoughtUpdated(thought: String)
        fun onDownloadProgress(progressText: String?) {}
        fun onOpenUrl(url: String): Boolean = false
    }
    
    internal var uiCallback: UiCallback? = null

    internal val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val modelDownloader: ModelDownloader by lazy { ModelDownloader(applicationContext, serviceScope) }

    // Mandatory Service implementation
    override fun onBind(intent: Intent?): IBinder? {
        return LocalBinder()
    }

    // IO dispatcher: inference + DB work must not compete with the UI render thread.
    // Default dispatcher shares threads with the coroutine runtime and causes UI stutter
    // during token generation. IO has a larger, dedicated thread pool for blocking work.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // REPLACE: private lateinit var engine: GemmaEngine → Replaced with AtomicReference:
    private val engineRef = AtomicReference<LlmBackend?>(null)
    private val engineMutex = Mutex()
    private val _isSystemReady = MutableStateFlow(false)
    val isSystemReady = _isSystemReady.asStateFlow()

    val engine: LlmBackend?
        get() = engineRef.get()

    fun isGemmaLoaded(): Boolean = engineRef.get()?.let { 
        try { it.activeBackend != null } catch(e: Exception) { false }
    } ?: false

    // RAM Suspension State: Tracks when engine was proactively suspended to free memory for games
    val isSuspendedDueToRam = java.util.concurrent.atomic.AtomicBoolean(false)
    fun isEngineSuspendedDueToRam(): Boolean = isSuspendedDueToRam.get()

    // Thermal Safety State
    private val isCoolingDown = java.util.concurrent.atomic.AtomicBoolean(false)
    private val criticalCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastAutoReloadAttemptTime = 0L
    private val diaryMutex = kotlinx.coroutines.sync.Mutex()


    // ==========================================

    private lateinit var apiServer: ApiServer
    lateinit var memoryManager: MemoryManager
    internal lateinit var contextManager: com.ghost.api.logic.ContextManager

    // NEW: ADK Dynamic MCP Architecture
    internal lateinit var mcpServer: MCPServer
    internal lateinit var ghostAgent: GhostAgent
    internal lateinit var ttsManager: com.ghost.api.services.TTSManager
    internal lateinit var sensorFusionManager: com.ghost.api.hardware.SensorFusionManager
    internal lateinit var hardwarePropertiesManager: HardwarePropertiesManager
    internal lateinit var skillManager: com.ghost.api.skills.SkillManager

    internal lateinit var responseNotificationManager: com.ghost.api.ui.GemmaNotificationManager

    internal val CHANNEL_ID = Constants.CHANNEL_ID_SERVICE
    internal val NOTIFICATION_ID = Constants.NOTIFICATION_ID_SERVICE

    // Modular Subsystem Controllers extracted from GemmaService (DOC-3)
    internal val foregroundNotificationController: com.ghost.api.service.GhostForegroundNotificationController by lazy {
        com.ghost.api.service.GhostForegroundNotificationController(this, CHANNEL_ID, NOTIFICATION_ID)
    }

    internal val hardwareBarkController: com.ghost.api.logic.GppController by lazy {
        com.ghost.api.logic.GppController(
            context = this,
            serviceScope = serviceScope,
            ttsManagerProvider = { if (::ttsManager.isInitialized) ttsManager else null },
            ghostAgentProvider = { if (::ghostAgent.isInitialized) ghostAgent else null },
            onChatMessageAdded = { msg, isUser -> uiCallback?.onMessageAdded(msg, isUser = isUser) },
            onShowWorkSignal = { tag, dur -> showWorkSignal(tag, dur) }
        )
    }

    internal val ramValveController: com.ghost.api.service.GhostRamValveController by lazy {
        com.ghost.api.service.GhostRamValveController(
            context = this,
            serviceScope = serviceScope,
            isSuspendedDueToRam = isSuspendedDueToRam,
            isInferencing = { isInferencing },
            isSpeaking = { isSpeaking() },
            isGemmaLoaded = { isGemmaLoaded() },
            isAppInForeground = { ::overlayManager.isInitialized && overlayManager.isAppInForeground },
            onCheckpointRequested = { if (::ghostAgent.isInitialized) ghostAgent.checkpoint() },
            onUnloadEngine = { unloadEngine() },
            onEnsureEngineReady = { ensureEngineReady() },
            onUpdateNotification = { updateNotification(it) },
            onRefreshTelemetry = { refreshNotificationTelemetry() }
        )
    }

    internal val diaryAndCronManager: com.ghost.api.service.GhostDiaryAndCronManager by lazy {
        com.ghost.api.service.GhostDiaryAndCronManager(
            context = this,
            serviceScope = serviceScope,
            memoryManagerProvider = { if (::memoryManager.isInitialized) memoryManager else null },
            automationToolSetProvider = { if (::automationToolSet.isInitialized) automationToolSet else null },
            sensorFusionManagerProvider = { if (::sensorFusionManager.isInitialized) sensorFusionManager else null },
            hardwarePropertiesManagerProvider = { if (::hardwarePropertiesManager.isInitialized) hardwarePropertiesManager else null },
            isEngineResident = { isGemmaLoaded() && ::ghostAgent.isInitialized && ghostAgent.isReady && !isSuspendedDueToRam.get() },
            isInferencing = { isInferencing },
            lastActivityTimeProvider = { lastActivityTime },
            getCurrentThermalState = { getCurrentThermalState() },
            createCalendarEvent = { title, desc -> createCalendarEvent(title, desc) },
            executePrompt = { prompt, sessionId, isDream -> processQuery(prompt, sessionId = sessionId, isDream = isDream, fromUi = false) }
        )
    }

    private fun setupNotificationChannel() {
        foregroundNotificationController.setupNotificationChannel()
    }

    private fun startForegroundService() {
         val notification = buildNotification("Starting...")
         try {
             if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                 startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
             } else {
                 startForeground(NOTIFICATION_ID, notification)
             }
         } catch (e: Exception) { Timber.e(e) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_RECOVER_MODEL, "com.ghost.api.ACTION_DOWNLOAD_MODEL" -> {
                Timber.i("Manual model download / recovery requested by operator")
                serviceScope.launch {
                    initialize(allowDownload = true)
                }
            }
            Constants.ACTION_QUERY -> {
                val query = intent.getStringExtra(Constants.EXTRA_QUERY)
                if (!query.isNullOrEmpty()) {
                    scope.launch {
                        if (!_isSystemReady.value) {
                            Timber.w("Query received while initializing, waiting...")
                            _isSystemReady.first { it }
                        }
                        processQuery(query)
                    }
                }
            }
            "com.ghost.api.ACTION_TTS_SPEAK" -> {
                val text = intent.getStringExtra("text")
                if (!text.isNullOrEmpty() && ::ttsManager.isInitialized) {
                    Timber.i("TTS replay: ${text.take(30)}...")
                    ttsManager.speak(text)
                }
            }
            "com.ghost.api.ACTION_CRON_PROMPT" -> {
                val prompt = intent.getStringExtra("prompt")
                if (!prompt.isNullOrEmpty()) {
                    Timber.i("Cron prompt triggered: ${prompt.take(30)}...")
                    scope.launch {
                        processQuery(prompt)
                    }
                }
            }
            "com.ghost.api.ACTION_SHOW_OVERLAY" -> {
                Timber.i("Received ACTION_SHOW_OVERLAY")
                // Robust Init: If overlay manager isn't ready, try to init it immediately
                if (!::overlayManager.isInitialized) {
                    Timber.w("OverlayManager not initialized yet - forcing init")
                    try {
                        overlayManager = OverlayManager(this)
                        setupOverlayManager()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to force init OverlayManager")
                    }
                }

                if (::overlayManager.isInitialized) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            overlayManager.toggle { query ->
                                scope.launch {
                                    val sessionId = UUID.randomUUID().toString()
                                    processQuery(query, sessionId, fromUi = true, inputSource = com.ghost.api.logic.InputSource.OVERLAY)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to toggle overlay")
                            android.widget.Toast.makeText(this, "Overlay Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Timber.e("OverlayManager still not initialized after force init")
                    android.widget.Toast.makeText(this, "System still initializing... try again.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            "com.ghost.api.ACTION_SHARE_MEDIA" -> {
                val imagePath = intent.getStringExtra("image_path")
                    ?: SharedMediaHolder.pendingImagePath
                val sharedQuery = intent.getStringExtra("query")
                    ?: SharedMediaHolder.pendingQuery
                SharedMediaHolder.clear()

                if (!imagePath.isNullOrEmpty()) {
                    val bitmap = decodeAndDownsample(imagePath, 1024)
                    if (bitmap != null && ::ghostAgent.isInitialized) {
                        ghostAgent.offerImage(bitmap, imagePath)
                        updateNotification("Image ready ✨ ask me about it!")

                        if (!sharedQuery.isNullOrEmpty()) {
                            scope.launch {
                                processQuery(sharedQuery, null, false)
                            }
                        } else {
                            uiCallback?.onMessageAdded("[Shared Image]", isUser = true, image = bitmap, imageUri = imagePath)
                        }
                    } else if (bitmap == null) {
                        Timber.e("Failed to decode shared image: $imagePath")
                    } else {
                        Timber.w("Agent not ready ✨ image dropped")
                    }
                } else {
                    Timber.w("No image path in intent or SharedMediaHolder")
                }
            }

            "com.ghost.api.ACTION_REQUEST_SCREENSHOT" -> {
                Timber.i("Received screenshot request from Agent")
                val accessService = GemmaAccessibilityService.instance
                if (accessService != null) {
                    // Trigger capture via Accessibility Service
                    // Note: captureScreen accepts a callback (Bitmap?) -> Unit
                    accessService.captureScreen { bitmap ->
                        if (bitmap != null && ::ghostAgent.isInitialized) {
                            val persistentPath = try {
                                val dir = java.io.File(filesDir, "chat_images").apply { mkdirs() }
                                val file = java.io.File(dir, "screen_${System.currentTimeMillis()}.jpg")
                                java.io.FileOutputStream(file).use { out ->
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                                }
                                file.absolutePath
                            } catch (e: Exception) {
                                null
                            }
                            ghostAgent.offerImage(bitmap, persistentPath)
                            Timber.i("Agent screenshot captured and queued (path=$persistentPath)")
                        } else {
                            Timber.e("Agent screenshot failed (null bitmap)")
                        }
                    }
                } else {
                    Timber.w("Accessibility Service not connected. Cannot take screenshot.")
                    // responseNotificationManager.showResponse("👁 Vision requires Accessibility Service.")
                }
            }
            "com.ghost.api.ACTION_CONFIRM_TOOL", "com.ghost.api.ACTION_DENY_TOOL" -> {
                 val toolName = intent.getStringExtra("toolName") ?: "unknown"
                 val isApproved = (intent.action == "com.ghost.api.ACTION_CONFIRM_TOOL")

                 scope.launch {
                     if (::ghostAgent.isInitialized) {
                         val pendingData = com.ghost.api.service.GhostForegroundNotificationController.pendingConfirmations[toolName]

                         if (pendingData != null) {
                             ghostAgent.submitConfirmationDecision(
                                 toolName = toolName,
                                 params = pendingData.params,
                                 isApproved = isApproved,
                                 originalResponse = pendingData.originalResponse,
                                 responseChannel = pendingData.responseChannel
                             )

                             // Clear stash
                             com.ghost.api.service.GhostForegroundNotificationController.clearConfirmation(toolName)
                             responseNotificationManager.showResponse(if(isApproved) "✅ Approved $toolName" else "❌ Denied $toolName")
                         } else {
                             Timber.e("No pending confirmation channel found for $toolName!")
                             responseNotificationManager.showResponse("⚠\uFE0F Error: Confirmation session expired.")
                         }
                     }
                 }
            }
        }
        return START_STICKY
    }

    private lateinit var hardwareToolSet: HardwareToolSet // Hardware Bridge
    private lateinit var networkToolSet: NetworkToolSet // Search Bridge
    private lateinit var systemToolSet: SystemToolSet // Apps & Media Bridge
    private lateinit var automationToolSet: AutomationToolSet // Cron Bridge
    private lateinit var uiMacroToolSet: com.ghost.api.hardware.UiMacroToolSet // UI Automation Bridge
    private lateinit var fileToolSet: com.ghost.api.hardware.FileToolSet // MediaStore & Files Bridge
    private lateinit var shakeDetector: ShakeDetector // Shake to summon
    lateinit var overlayManager: OverlayManager // Floating input
    private lateinit var audioRecorder: AudioRecorder // Hearing




    private fun reportStatus(msg: String) {
        val intent = android.content.Intent(Constants.ACTION_STATUS_UPDATE)
        intent.putExtra(Constants.EXTRA_STATUS_MSG, msg)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        Timber.i("Status: $msg")
    }

    override fun onCreate() {
        instance = this
        super.onCreate()
        
        // Immediate foreground service start to satisfy Android 14 strict 5s ANR timeout
        setupNotificationChannel()
        startForegroundService()
        
        cleanupLegacyAlarms()
        setupDiaryWorker()

        // Watchdog check: If we crashed during last initialization, increment count
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_initializing", false)) {
            val newCount = prefs.getInt("init_crash_count", 0) + 1
            prefs.edit().putInt("init_crash_count", newCount).apply()
            Timber.e("Detected crash during last init! Crash count: $newCount")
        }

        // Hardware-tiered default model selection on first run & RAM-constraint clamping
        val hardwareTier = Constants.resolveHardwareModelTier(this)
        val currentModel = prefs.getString(Constants.PREF_SELECTED_MODEL, null)
        if (hardwareTier == "E2B" && currentModel != "E2B") {
            Timber.w("🛡️ Hardware constraint: Device RAM requires E2B tier. Enforcing E2B (was $currentModel)")
            prefs.edit()
                .putString(Constants.PREF_SELECTED_MODEL, "E2B")
                .remove(Constants.PREF_LAST_KNOWN_MODEL_PATH)
                .apply()
        } else if (currentModel == null) {
            prefs.edit().putString(Constants.PREF_SELECTED_MODEL, hardwareTier).apply()
            Timber.i("🎯 First run: Hardware tier resolved default model to $hardwareTier")
        }


        try {
            val overlayPerm = android.provider.Settings.canDrawOverlays(this)
            reportStatus("Service Created (Overlay Perm: $overlayPerm), Initializing...")

            hardwarePropertiesManager = HardwarePropertiesManager(this)
            hardwareBarkController.startThermalBarkMonitor(hardwarePropertiesManager)
            ttsManager = com.ghost.api.services.TTSManager(this)
            reportStatus("Init: NotificationManager...")
            responseNotificationManager = com.ghost.api.ui.GemmaNotificationManager(this)
            responseNotificationManager.pushStartupShortcut()
            reportStatus("Init: MemoryManager...")
            memoryManager = MemoryManager(applicationContext)
            serviceScope.launch(Dispatchers.IO) {
                memoryManager.runDatabaseCleanup()
            }

            reportStatus("Init: SensorFusion...")
            // Init Sensor Fusion (stored as class member for cleanup)
            sensorFusionManager = com.ghost.api.hardware.SensorFusionManager(this)
            
            reportStatus("Init: HardwareToolSet...")
            hardwareToolSet = HardwareToolSet(this, sensorFusionManager) // Pass manager here!
            reportStatus("Init: NetworkToolSet...")
            networkToolSet = NetworkToolSet(this) // Init Network
            reportStatus("Init: SystemToolSet...")
            systemToolSet = SystemToolSet(this) // Init Apps & Media Bridge
            reportStatus("Init: AutomationToolSet...")
            automationToolSet = AutomationToolSet(this) // Init Cron Bridge
            AutomationToolSet.loadTasks(this).forEach { automationToolSet.armTaskAlarm(it) }
            reportStatus("Init: UiMacroToolSet...")
            uiMacroToolSet = com.ghost.api.hardware.UiMacroToolSet(this) // Init UI Automation
            fileToolSet = com.ghost.api.hardware.FileToolSet(this) // Init MediaStore & Files Lazy Bridge
            reportStatus("Init: AudioRecorder...")
            audioRecorder = AudioRecorder(this) // Init Hearing

            reportStatus("Init: Shake/Overlay (deferred)...")
            // Shake to Summon (deferred to not slow startup)
            scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                try {
                    reportStatus("Init: OverlayManager...")
                    overlayManager = OverlayManager(this@GemmaService)
                    setupOverlayManager()

                    reportStatus("Init: ShakeDetector & EdgeNub...")
                    shakeDetector = ShakeDetector(this@GemmaService) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (::overlayManager.isInitialized) {
                                overlayManager.toggle { query ->
                                    scope.launch {
                                        val sessionId = UUID.randomUUID().toString()
                                        processQuery(query, sessionId, fromUi = true, inputSource = com.ghost.api.logic.InputSource.OVERLAY)
                                    }
                                }
                            }
                        }
                    }
                    updateSummonControls()
                    Timber.d("Summon controls initialized (Shake & Edge Nub)")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to init summon controls")
                }
            }
            
            reportStatus("Init: ContextManager...")
            contextManager = com.ghost.api.logic.ContextManager(sensorFusionManager)
            
            skillManager = com.ghost.api.skills.SkillManager(this)
            skillManager.loadSkillsFromAssets()
            // Load user skills from app-specific external storage
            val sdSkillsDir = getExternalFilesDir(null)?.absolutePath?.let { "$it/skills" }
            if (sdSkillsDir != null) skillManager.loadSkillsFromDir(sdSkillsDir)
            
            // Seed & load from user-accessible public folder: Documents/GHOST/skills
            try {
                skillManager.seedPublicSkillsFromAssets()
                skillManager.loadSkillsFromDir(skillManager.publicSkillsDir.absolutePath)
            } catch (e: Exception) {
                Timber.w(e, "Failed to load skills from public Documents folder")
            }

            // Initialize MCP Server
            reportStatus("Init: MCPServer...")
            mcpServer = com.ghost.api.mcp.MCPServer(
                context = this,
                hardwareTools = hardwareToolSet,
                networkTools = networkToolSet,
                systemTools = systemToolSet,
                uiMacroTools = uiMacroToolSet,
                automationTools = automationToolSet,
                audioRecorder = audioRecorder,
                sensorManager = sensorFusionManager,
                memoryManager = memoryManager,
                skillManager = skillManager
            )

            Timber.i("MCPServer initialized")

            reportStatus("Init: Complete")
            updateNotification("Ready")

            // Start Sensor Fusion polling now that we are in foreground
            if (::sensorFusionManager.isInitialized) {
                sensorFusionManager.startFusionLoop()
                Timber.i("SensorFusion loop started")
            }

            // Start API only if explicitly enabled by user
            val apiEnabled = prefs.getBoolean(Constants.PREF_API_SERVER_ENABLED, false)
            if (apiEnabled) {
                try {
                    apiServer = ApiServer(this, memoryManager)
                    apiServer.start()
                    reportStatus("Alive! API on Port ${Constants.API_PORT}")
                } catch (e: Exception) {
                    reportStatus("API Error: ${e.message}")
                    Timber.e(e, "API Bind Failed")
                }
            } else {
                Timber.i("Local API Server disabled by preference (default safe)")
            }

            scope.launch {
                reportStatus("Loading Model...")
                initialize(allowDownload = true)
                if (isGemmaLoaded() && ::ghostAgent.isInitialized && ghostAgent.isReady) {
                    reportStatus("Model Loaded & Ready (${engine?.activeBackend})")
                    _isSystemReady.value = true
                    Timber.i("GHOST: All systems online 🟢")
                    kotlinx.coroutines.delay(2000)
                    reportStatus("Running on ${engine?.activeBackend} Backend")
                } else if (modelDownloader.downloadStatus.value is ModelDownloader.DownloadState.Downloading) {
                    Timber.i("GHOST: Model download in progress on startup")
                    reportStatus("Downloading Weights...")
                } else {
                    _isSystemReady.value = false
                    reportStatus("Standby: Engine weights offline")
                    Timber.w("GHOST: Model not loaded on startup")
                }

                // Start Life Processes
                checkPermissions()
                startAnimationLoop()  // Start notification screensavers (power-aware)
                com.ghost.api.ui.EdgeLightsManager.restoreState(this@GemmaService)
            }
        } catch (e: Exception) {
            reportStatus("CRASH: ${e.message}")
            Timber.e(e, "Service Crash Detected")
            stopSelf()
        }
    }


    // === PERMISSIONS LOGIC ===
    private fun checkPermissions() {
        // Standard permissions managed through runtime flow
    }

    // === MEMORY MANAGEMENT ===

    fun resetMemory() {
        synchronized(this) {
            // Delete checkpoint files and clear session conversation history
            try {
                val checkpointDir = getExternalFilesDir(null) ?: filesDir
                val file = java.io.File(checkpointDir, "ghost_agent_checkpoint.json")
                if (file.exists()) file.delete()
                if (::memoryManager.isInitialized) {
                    serviceScope.launch(Dispatchers.IO) {
                        memoryManager.clearSessionMemory()
                    }
                }
                Timber.i("🧹 Memory wiped: checkpoints and session memory cleared")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete checkpoint files")
            }

            // Reset Ghost agent if initialized
            if (::ghostAgent.isInitialized) {
                try {
                    scope.launch {
                        ghostAgent.softReset()
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to soft reset Ghost agent history")
                }
            }
        }
    }


    // === THERMAL SAFETY SYSTEM (SILENT) ===

    private enum class ThermalSafetyState { COOL, WARM, HOT, CRITICAL }
    
    /**
     * Clear all crash counters and force-CPU states.
     * Restores GPU performance.
     */
    fun resetRecoveryState() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("init_crash_count", 0)
            .putBoolean("is_initializing", false)
            .putBoolean("force_cpu", false)
            .apply()
        Timber.i("\uD83E\uDDF9 Recovery state cleared via service")
    }

    /**
     * Hot-reloads the inference backend live on the fly (AUTO, CPU, GPU, NPU, OFF)
     * without killing the foreground service or restarting the app.
     */
    fun reloadWithBackend(backend: String) {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Constants.PREF_USER_BACKEND, backend)
            .putBoolean("force_cpu", false)
            .putBoolean("is_initializing", false)
            .putInt("init_crash_count", 0)
            .apply()

        serviceScope.launch {
            try {
                currentInFlightJob?.cancel()
                currentInFlightJob = null
            } catch (e: Exception) {
                Timber.w(e, "Error cancelling in-flight job on backend reload")
            }
            cancelThinking()
            isInferencing = false
            currentInFlightQuery = null

            if (backend == "OFF") {
                unloadEngine()
                _isSystemReady.value = true
                updateNotification("GHOST Online (Engine OFF)")
                reportStatus("Standby: Engine OFF")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@GemmaService, "Inference Engine OFF (RAM freed) 🍃", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                updateNotification("Switching to $backend...")
                initialize()
                val ready = isGemmaLoaded() && ::ghostAgent.isInitialized && ghostAgent.isReady
                _isSystemReady.value = ready
                withContext(Dispatchers.Main) {
                    val msg = if (ready) "Engine online on $backend 🚀" else "Engine failed to initialize on $backend ⚠️"
                    android.widget.Toast.makeText(this@GemmaService, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Hot-swaps the active model core (E4B or E2B) live.
     */
    fun reloadWithModel(modelCore: String) {
        val hardwareTier = Constants.resolveHardwareModelTier(this)
        val targetCore = if (hardwareTier == "E2B" && !modelCore.equals("E2B", ignoreCase = true)) {
            Timber.w("🛡️ Hardware constraint: Denying switch to $modelCore on E2B tier.")
            "E2B"
        } else {
            modelCore
        }
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Constants.PREF_SELECTED_MODEL, targetCore)
            .remove(Constants.PREF_LAST_KNOWN_MODEL_PATH)
            .putBoolean("force_cpu", false)
            .putBoolean("is_initializing", false)
            .putInt("init_crash_count", 0)
            .apply()

        serviceScope.launch {
            cancelThinking()
            isInferencing = false
            currentInFlightQuery = null
            updateNotification("Loading Gemma $targetCore...")
            initialize(allowDownload = true)
            val ready = isGemmaLoaded() && ::ghostAgent.isInitialized && ghostAgent.isReady
            _isSystemReady.value = ready
            withContext(Dispatchers.Main) {
                val msg = if (ready) "Active model core: $targetCore 🧠" else if (modelDownloader.downloadStatus.value is ModelDownloader.DownloadState.Downloading) "Downloading $targetCore weights... 📥" else "Model $targetCore weights offline ⚠️"
                android.widget.Toast.makeText(this@GemmaService, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Explicitly starts a user-requested download of the active or specified model core.
     */
    fun triggerModelDownload(modelCore: String? = null) {
        serviceScope.launch {
            if (modelDownloader.downloadStatus.value is ModelDownloader.DownloadState.Downloading) {
                Timber.w("Model download already in progress")
                return@launch
            }
            val hardwareTier = Constants.resolveHardwareModelTier(this@GemmaService)
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            var targetCore = modelCore ?: prefs.getString(Constants.PREF_SELECTED_MODEL, hardwareTier) ?: hardwareTier
            if (hardwareTier == "E2B") {
                targetCore = "E2B"
            }
            prefs.edit()
                .putString(Constants.PREF_SELECTED_MODEL, targetCore)
                .remove(Constants.PREF_LAST_KNOWN_MODEL_PATH)
                .apply()
            Timber.i("Manual model download initiated for: $targetCore (Hardware Tier: $hardwareTier)")
            initialize(allowDownload = true)
        }
    }

    /**
     * Compacts session conversation history into semantic memory and resets the KV cache.
     */
    fun flushSessionMemory() {
        serviceScope.launch {
            if (::ghostAgent.isInitialized) {
                ghostAgent.flushAndCompactSession()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@GemmaService, "Session compacted & KV cache cleared ✨", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Updates summon triggers (Shake, Edge Nub, or Both) based on user preference.
     */
    fun updateSummonControls() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val method = prefs.getString(Constants.PREF_SUMMON_METHOD, Constants.SUMMON_METHOD_BOTH) ?: Constants.SUMMON_METHOD_BOTH

            val allowShake = method == Constants.SUMMON_METHOD_SHAKE || method == Constants.SUMMON_METHOD_BOTH
            val allowNub = method == Constants.SUMMON_METHOD_EDGE_NUB || method == Constants.SUMMON_METHOD_BOTH

            if (::shakeDetector.isInitialized) {
                if (allowShake) {
                    shakeDetector.start()
                } else {
                    shakeDetector.stop()
                }
            }

            if (::overlayManager.isInitialized) {
                if (allowNub) {
                    overlayManager.attachEdgeNub {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            overlayManager.toggle { query ->
                                scope.launch {
                                    val sessionId = UUID.randomUUID().toString()
                                    processQuery(query, sessionId, fromUi = true, inputSource = com.ghost.api.logic.InputSource.OVERLAY)
                                }
                            }
                        }
                    }
                } else {
                    overlayManager.detachEdgeNub()
                }
            }
            Timber.i("🎚️ Summon controls updated: method=$method, shake=$allowShake, edgeNub=$allowNub")
        }
    }

    fun summonOverlay() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (::overlayManager.isInitialized) {
                overlayManager.showOverlay { query ->
                    scope.launch {
                        val sessionId = UUID.randomUUID().toString()
                        processQuery(query, sessionId, fromUi = true, inputSource = com.ghost.api.logic.InputSource.OVERLAY)
                    }
                }
            }
        }
    }

    /**
     * Records tool execution output length into GhostAgent's KV cache budget tracker.
     */
    fun recordToolOutput(charCount: Int) {
        if (::ghostAgent.isInitialized) {
            ghostAgent.recordToolChars(charCount)
        }
    }

    /**
     * Dispatches URL to external system browser with multi-layer Android 14+ BAL (Background Activity Launch) bypass.
     * Tier 1: Active foreground MainActivity if resumed.
     * Tier 2: PendingIntent with MODE_BACKGROUND_ACTIVITY_START_ALLOWED (API 34/35 BAL bypass).
     * Tier 3: Privileged GemmaAccessibilityService context.
     * Tier 4: Direct Service startActivity with FLAG_ACTIVITY_NEW_TASK.
     * Tier 5: Interactive heads-up notification with 1-tap browser intent action.
     */
    fun openUrl(url: String): Boolean {
        val targetUrl = if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            "https://$url"
        } else {
            url
        }
        val uri = try {
            android.net.Uri.parse(targetUrl)
        } catch (e: Exception) {
            Timber.e(e, "GemmaService: Failed to parse URL $targetUrl")
            return false
        }

        // Tier 1: Dispatch through active foreground Activity if resumed
        val uiHandled = uiCallback?.onOpenUrl(targetUrl) ?: false
        if (uiHandled) {
            Timber.i("GemmaService: URL opened via foreground Activity: $targetUrl")
            return true
        }

        // Tier 2: PendingIntent with Android 14+ Background Activity Launch (BAL) bypass
        var pendingIntentSucceeded = false
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 10000).toInt(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val options = android.app.ActivityOptions.makeBasic()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                options.setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                try {
                    val m = android.app.ActivityOptions::class.java.getMethod(
                        "setPendingIntentCreatorBackgroundActivityStartMode",
                        Int::class.javaPrimitiveType
                    )
                    m.invoke(options, android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                } catch (_: Throwable) {}
            }
            pendingIntent.send(this, 0, null, null, null, null, options.toBundle())
            Timber.i("GemmaService: URL dispatched via PendingIntent BAL bypass: $targetUrl")
            pendingIntentSucceeded = true
        } catch (e: Exception) {
            Timber.w(e, "GemmaService: PendingIntent BAL bypass failed for $targetUrl")
        }

        // Tier 3: Privileged Accessibility Service context
        var accessibilitySucceeded = false
        val accessService = GemmaAccessibilityService.instance
        if (accessService != null) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                accessService.startActivity(intent)
                Timber.i("GemmaService: URL launched via AccessibilityService: $targetUrl")
                accessibilitySucceeded = true
            } catch (e: Exception) {
                Timber.w(e, "GemmaService: AccessibilityService launch failed for $targetUrl")
            }
        }

        // Tier 4: Direct Service context launch
        var serviceSucceeded = false
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Timber.i("GemmaService: URL launched via Service context: $targetUrl")
            serviceSucceeded = true
        } catch (e: Exception) {
            Timber.w(e, "GemmaService: Direct startActivity failed for $targetUrl")
        }

        // Tier 5: Always post interactive heads-up notification so user has guaranteed instant 1-tap fallback
        if (::responseNotificationManager.isInitialized) {
            responseNotificationManager.showUrlNotification(targetUrl)
        }

        return pendingIntentSucceeded || accessibilitySucceeded || serviceSucceeded
    }

    private fun getThermalSafetyState(thermalState: HardwarePropertiesManager.ThermalState): ThermalSafetyState {
        return when (thermalState) {
            HardwarePropertiesManager.ThermalState.CRITICAL -> ThermalSafetyState.CRITICAL
            HardwarePropertiesManager.ThermalState.HOT -> ThermalSafetyState.HOT
            HardwarePropertiesManager.ThermalState.WARM -> ThermalSafetyState.WARM
            else -> ThermalSafetyState.COOL
        }
    }

    // Diary cycle: setupDiaryCron() → DiaryAlarmReceiver → startDiaryCycle() → generateOneShot → writeDiaryEntry + Calendar

    private var initAttempts = 0
    private suspend fun initialize(allowDownload: Boolean = true) {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)

        // 1. EARLY CHECK: If user set backend to OFF, enter standalone mode immediately. Never search or download.
        val userBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (userBackend == "OFF") {
            Timber.i("🛑 User selected backend: OFF. Entering standalone mode (RAM freed).")
            unloadEngine()
            _isSystemReady.value = true
            isSuspendedDueToRam.set(false)
            prefs.edit().putBoolean("is_initializing", false).apply()
            updateNotification("GHOST Online (Engine OFF)")
            reportStatus("Standby: Engine OFF")
            return
        }

        // WATCHDOG: Detect previous crash
        // NOTE: Counter is already incremented in onCreate(). Do NOT double-increment here.
        val crashCount = prefs.getInt("init_crash_count", 0)
        val hardwareTier = Constants.resolveHardwareModelTier(this)
        var selectedModel = prefs.getString(Constants.PREF_SELECTED_MODEL, hardwareTier) ?: hardwareTier
        if (hardwareTier == "E2B" && !selectedModel.equals("E2B", ignoreCase = true)) {
            Timber.w("🛡️ Hardware constraint: Enforcing E2B model tier in initialize()")
            selectedModel = "E2B"
            prefs.edit()
                .putString(Constants.PREF_SELECTED_MODEL, "E2B")
                .remove(Constants.PREF_LAST_KNOWN_MODEL_PATH)
                .apply()
        }

        val protectedModelsDir = getExternalFilesDir("models") 
            ?: getExternalFilesDir(null)?.let { File(it, "models") }
            ?: File(filesDir, "models")
        protectedModelsDir.mkdirs()

        val appFilesDir = getExternalFilesDir(null)
        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )

        // Storage readiness check: wait up to 2.5s if external storage is still mounting
        var mountWait = 0
        while (android.os.Environment.getExternalStorageState() != android.os.Environment.MEDIA_MOUNTED && mountWait < 5) {
            mountWait++
            delay(500)
        }

        val searchDirs = listOfNotNull(
            protectedModelsDir,
            appFilesDir,
            File("/storage/emulated/0/Android/data/$packageName/files/models"),
            File("/sdcard/Android/data/$packageName/files/models"),
            File(filesDir, "models"),
            filesDir
        ).distinct()

        if (prefs.getBoolean("is_initializing", false)) {
            Timber.e("🚨 WATCHDOG: Previous native initialization crashed! (Count: $crashCount, Model: $selectedModel)")
            
            // Check if lightweight E2B actually exists locally before attempting fallback
            val hasLocalE2B = searchDirs.any { dir ->
                if (!dir.exists() || !dir.isDirectory) return@any false
                val directE2b = File(dir, Constants.MODEL_NAME_E2B)
                (directE2b.exists() && directE2b.length() > 200 * 1024 * 1024L) ||
                dir.listFiles { f -> f.name.contains("e2b", ignoreCase = true) && f.length() > 200 * 1024 * 1024L }?.isNotEmpty() == true
            }

            if (selectedModel.equals("E4B", ignoreCase = true) && crashCount >= 2) {
                if (hasLocalE2B) {
                    Timber.w("🛡️ WATCHDOG: Falling back to local E2B (Compact) weights.")
                    prefs.edit()
                        .putString(Constants.PREF_SELECTED_MODEL, "E2B")
                        .putBoolean("force_cpu", false)
                        .putInt("init_crash_count", 0)
                        .apply()
                    updateNotification("Memory Safety: Switched to E2B (Compact)")
                } else {
                    Timber.w("🛡️ WATCHDOG: E4B crashed during init, but no local E2B file exists. Forcing CPU to avoid unwanted redownload.")
                    prefs.edit().putBoolean("force_cpu", true).apply()
                    updateNotification("Safe Mode: Forcing CPU")
                }
            } else if (crashCount >= 4) {
                 prefs.edit().putBoolean("force_cpu", true).apply()
                 updateNotification("Safe Mode: Forcing CPU")
            }
            prefs.edit().putBoolean("is_initializing", false).apply()
        }

        // PRE-INIT CLEANUP: Kill old engine to prevent memory leaks (95% RAM fix)
        // We do NOT call performCriticalCleanup() here because it kills sensors and TTS
        unloadEngine()

        try {
            updateNotification("Finding model...")

            val activeModel = selectedModel
            val targetVariant = activeModel.lowercase()

            // 0. Check cached model path from SharedPreferences first (fast-path bypasses directory listing)
            val cachedPath = prefs.getString(Constants.PREF_LAST_KNOWN_MODEL_PATH, null)
            var candidateFile: File? = if (!cachedPath.isNullOrBlank()) {
                val cached = File(cachedPath)
                if (cached.exists() && cached.length() > 200 * 1024 * 1024L) {
                    if (hardwareTier == "E2B" && cached.name.contains("e4b", ignoreCase = true)) {
                        Timber.w("⚠️ Ignoring cached E4B path on E2B hardware tier: ${cached.absolutePath}")
                        null
                    } else {
                        Timber.i("📦 Fast-path: Found cached model at ${cached.absolutePath} (${cached.length()} bytes)")
                        cached
                    }
                } else null
            } else null

            // 1. Check direct file candidates in search dirs before relying on directory listing (case-insensitive)
            if (candidateFile == null) {
                val candidateNames = if (hardwareTier == "E2B" || targetVariant.contains("e2b")) {
                    listOf(Constants.MODEL_NAME_E2B)
                } else {
                    listOf(Constants.MODEL_NAME_E4B, Constants.MODEL_NAME_E2B)
                }
                for (name in candidateNames) {
                    for (dir in searchDirs) {
                        if (!dir.exists() || !dir.isDirectory) continue
                        val directFile = File(dir, name)
                        if (directFile.exists() && directFile.length() > 200 * 1024 * 1024L) {
                            candidateFile = directFile
                            Timber.i("📦 Direct candidate match: ${directFile.absolutePath} (${directFile.length()} bytes)")
                            break
                        }
                        val ciFile = dir.listFiles { f ->
                            f.name.equals(name, ignoreCase = true) && f.length() > 200 * 1024 * 1024L
                        }?.firstOrNull()
                        if (ciFile != null) {
                            candidateFile = ciFile
                            Timber.i("📦 Case-insensitive candidate match: ${ciFile.absolutePath} (${ciFile.length()} bytes)")
                            break
                        }
                    }
                    if (candidateFile != null) break
                }
            }

            // 2. Directory listing scan if direct checks missed
            if (candidateFile == null) {
                candidateFile = searchDirs.filter { it.exists() && it.isDirectory }.flatMap { dir ->
                    dir.listFiles { file ->
                        val name = file.name
                        val isCandidate = (name.endsWith(".litertlm", ignoreCase = true) ||
                         name.endsWith(".gguf", ignoreCase = true) ||
                         name.endsWith(".nexa", ignoreCase = true)) &&
                        file.length() > 200 * 1024 * 1024L // Must be > 200MB to avoid partial/corrupted downloads

                        if (!isCandidate) false
                        else if (hardwareTier == "E2B" && name.contains("e4b", ignoreCase = true)) false
                        else true
                    }?.toList() ?: emptyList()
                }.filter { file ->
                    file.name.contains(targetVariant, ignoreCase = true)
                }.sortedByDescending { file ->
                    val name = file.name.lowercase()
                    var score = 0
                    if (file.parentFile?.canonicalPath == protectedModelsDir.canonicalPath) score += 100 // Prefer protected models dir
                    if (name.endsWith(".litertlm")) score += 50
                    score
                }.firstOrNull()
            }

            // FALLBACK SAFETY: If requested variant is missing on disk, adopt ANY valid local model before downloading
            if (candidateFile == null) {
                val fallbackModel = ModelDownloader.findAnyLocalModel(this, targetVariant = if (hardwareTier == "E2B") "E2B" else null)
                if (fallbackModel != null) {
                    val detectedVariant = if (fallbackModel.name.contains("e4b", ignoreCase = true)) "E4B" else "E2B"
                    if (hardwareTier == "E2B" && detectedVariant == "E4B") {
                        Timber.w("🛡️ Hardware constraint: Ignoring existing E4B file ${fallbackModel.name} on E2B hardware tier.")
                    } else {
                        Timber.w("Targeted model $activeModel not found on disk, but found existing ${fallbackModel.name}. Adopting $detectedVariant to prevent redundant download.")
                        prefs.edit().putString(Constants.PREF_SELECTED_MODEL, detectedVariant).apply()
                        candidateFile = fallbackModel
                    }
                }
            }

            // Storage Delay Guard: Retry up to 5 times with backoff in case storage volume was busy or mounting
            var retryCount = 0
            while (candidateFile == null && retryCount < 5) {
                retryCount++
                delay(retryCount * 500L)
                val fallback = ModelDownloader.findAnyLocalModel(this, targetVariant = if (hardwareTier == "E2B") "E2B" else null)
                if (fallback != null) {
                    val detectedVariant = if (fallback.name.contains("e4b", ignoreCase = true)) "E4B" else "E2B"
                    if (hardwareTier == "E2B" && detectedVariant == "E4B") {
                        // ignore E4B on E2B hardware
                    } else {
                        candidateFile = fallback
                        Timber.i("📦 Storage Delay Guard recovered model on attempt $retryCount: ${candidateFile.name}")
                        break
                    }
                }
            }

            // IN-PLACE RETENTION: Never move or delete the user's model file!
            val modelFile: File? = candidateFile

            if (modelFile != null) {
                prefs.edit().putString(Constants.PREF_LAST_KNOWN_MODEL_PATH, modelFile.absolutePath).apply()
                uiCallback?.onDownloadProgress(null)
                val variant = when {
                    modelFile.name.contains("E4B", ignoreCase = true) -> "E4B (Frontier)"
                    modelFile.name.contains("E2B", ignoreCase = true) -> "E2B (Compact)"
                    else -> "unknown variant"
                }
                Timber.i("📦 Found model: ${modelFile.name} ($variant) in ${modelFile.parent}")
            }

            if (modelFile == null) {
                val searchedPaths = searchDirs.mapNotNull { it.absolutePath }
                Timber.w("Model not found! Searched: $searchedPaths")
                if (!allowDownload) {
                    Timber.w("🛡️ SAFETY LOCK: Automatic background model download prohibited. Halting initialization safely.")
                    updateNotification("✧ GHOST: Model weights offline")
                    reportStatus("Standby: Storage Offline")
                    _isSystemReady.value = false
                    isSuspendedDueToRam.set(false)
                    prefs.edit().putBoolean("is_initializing", false).apply()
                    return
                }

                Timber.e("Initiating authorized model download...")
                val hfRepo = if (selectedModel.equals("E2B", ignoreCase = true)) Constants.MODEL_REPO_E2B else Constants.MODEL_REPO_E4B
                val hfFileName = if (selectedModel.equals("E2B", ignoreCase = true)) Constants.MODEL_NAME_E2B else Constants.MODEL_NAME_E4B
                updateNotification("Downloading $selectedModel model...")
                
                modelDownloader.startDownload(hfRepo, hfFileName)
                
                scope.launch {
                    try {
                        modelDownloader.downloadStatus.collect { state ->
                            when (state) {
                                is ModelDownloader.DownloadState.Downloading -> {
                                    val mbDone = state.bytesDownloaded / 1024 / 1024
                                    val mbTotal = state.totalBytes / 1024 / 1024
                                    val text = "Downloading Weights: ${state.progressPercent}% (${mbDone}MB / ${mbTotal}MB)"
                                    updateNotification(text)
                                    uiCallback?.onDownloadProgress(text)
                                }
                                is ModelDownloader.DownloadState.Success -> {
                                    updateNotification("Download complete! Initializing...")
                                    uiCallback?.onDownloadProgress(null)
                                    initialize(allowDownload = false)
                                    throw kotlinx.coroutines.CancellationException("Done")
                                }
                                is ModelDownloader.DownloadState.Error -> {
                                    updateNotification("Download failed: ${state.message}")
                                    uiCallback?.onDownloadProgress("Download Error: ${state.message}")
                                    throw kotlinx.coroutines.CancellationException("Error")
                                }
                                else -> {}
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Expected to exit flow collection
                    }
                }
                return
            }

            // Determine backend: User override > Watchdog > Auto waterfall
            val forcedBackend = if (userBackend != null && userBackend != "AUTO") {
                Timber.i("🎮 User backend override: $userBackend")
                when (userBackend.uppercase()) {
                    "GPU" -> updateNotification("✧ Running GPU systems diagnostic")
                    "CPU" -> updateNotification("✧ Running CPU systems diagnostic")
                    else -> updateNotification("✧ Running $userBackend systems diagnostic")
                }
                userBackend
            } else if (prefs.getInt("init_crash_count", 0) >= 4) {
                Timber.w("🚨 Forcing CPU backend due to repeated crashes (threshold 4)")
                updateNotification("✧ Running CPU systems diagnostic")
                "CPU"
            } else if (prefs.getBoolean("force_cpu", false)) {
                updateNotification("✧ Running CPU systems diagnostic")
                "CPU"
            } else {
                updateNotification("✧ Running Full systems Diagnostic")
                null
            }

            // Determine Tools (ADK Dynamic MCP Toolset: ~80 tokens instead of ~3,800 tokens)
            val ghostMcpTool = GhostMcpTool(
                context = applicationContext,
                mcpServer = mcpServer,
                skillManager = skillManager
            )
            val adkTools = listOf(ghostMcpTool)

            // Engine Creation (Locked to prevent double allocation)
            val newEngine = engineMutex.withLock {
                // CRITICAL: Cleanup old engine BEFORE creating new one to free RAM
                engineRef.getAndSet(null)?.cleanup()
                
                // Hardening: Defragment memory before heavy engine allocation
                System.gc()
                Runtime.getRuntime().gc()
                
                val engineInstance = GemmaEngine(applicationContext)
                prefs.edit().putBoolean("is_initializing", true).apply()
                val error = try {
                    engineInstance.initialize(
                        modelFile.absolutePath, 
                        "", 
                        toolSets = adkTools, 
                        forcedBackend = forcedBackend
                    )
                } finally {
                    prefs.edit().putBoolean("is_initializing", false).apply()
                }
                
                // Hardening: Final defrag after initialization
                System.gc()
                
                if (error != null) {
                    engineInstance.cleanup()
                    if (forcedBackend != "CPU") {
                        Timber.w("⚠️ Primary engine init error ($error). Automatically falling back to CPU backend to ensure GHOST stays alive!")
                        updateNotification("Memory Pressure: Recovering on CPU...")
                        System.gc()
                        Runtime.getRuntime().gc()

                        val cpuEngine = GemmaEngine(applicationContext)
                        val cpuError = try {
                            cpuEngine.initialize(
                                modelFile.absolutePath, 
                                "", 
                                toolSets = adkTools, 
                                forcedBackend = "CPU"
                            )
                        } catch (t: Throwable) {
                            t.message
                        }
                        if (cpuError == null) {
                            Timber.i("✅ Cleanly recovered on CPU backend!")
                            cpuEngine
                        } else {
                            cpuEngine.cleanup()
                            _isSystemReady.value = false
                            Timber.e("CPU fallback also failed: $cpuError")
                            updateNotification("Load Error: ${cpuError.take(80)}")
                            return@initialize
                        }
                    } else {
                        _isSystemReady.value = false
                        Timber.e("Model load failed on CPU: $error")
                        updateNotification("Load Error: ${error.take(80)}")
                        return@initialize
                    }
                } else {
                    engineInstance
                }
            }

            engineRef.set(newEngine)

            if (forcedBackend == null) {
                updateNotification("✧ All stations are now enabled")
            }

            // Init Cognitive Layer (now that engine is ready)
            reportStatus("Init: GhostAgent...")
            if (::ghostAgent.isInitialized) {
                try {
                    ghostAgent.shutdown()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to shutdown old GhostAgent")
                }
            }
            ghostAgent = GhostAgent(
                context = applicationContext,
                llmEngine = newEngine,
                sensorManager = sensorFusionManager,
                contextManager = contextManager,
                skillManager = skillManager,
                checkpointDir = getExternalFilesDir(null) ?: filesDir,
                mcpTool = ghostMcpTool,
                callbacks = this@GemmaService
            )

            ghostAgent.onConfirmationRequest = { event ->
                scope.launch(Dispatchers.Main) {
                    foregroundNotificationController.showConfirmationNotification(event)
                }
            }

            ghostAgent.initialize()
            Timber.i("GhostAgent ready")

            // Success: Clear watchdog
            prefs.edit()
                .putBoolean("is_initializing", false)
                .putBoolean("force_cpu", false)
                .putInt("init_crash_count", 0)
                .apply()

            isSuspendedDueToRam.set(false)
            _isSystemReady.value = true
            reportStatus("Running on ${newEngine.activeBackend} Backend")
            updateNotification("✧ Machine Status: Fully operational")

        } catch (e: Exception) {
            _isSystemReady.value = false
            Timber.e(e)
            updateNotification("Crash: ${e.message}")
        }
    }






    /**
     * Decode image from path with downsampling to max dimension.
     * Matches Google Gallery pattern: decode once, hold bitmap, pass to inference.
     */
    private fun decodeAndDownsample(path: String, maxDim: Int): Bitmap? {
        return try {
            val boundsOptions = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(path, boundsOptions)

            var sampleSize = 1
            while (boundsOptions.outWidth / sampleSize > maxDim || boundsOptions.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }

            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            android.graphics.BitmapFactory.decodeFile(path, opts)
        } catch (e: Exception) {
            Timber.e(e, "Failed to decode image: $path")
            null
        }
    }

    /**
     * Called directly by MainActivity to process native chat interface queries.
     * It uses the same backend engine but avoids spinning up unnecessary Overlay/Audio managers.
     */

    fun processNotificationAnnouncement(appName: String, title: String, text: String) {
        hardwareBarkController.processNotificationAnnouncement(appName, title, text)
    }

    /**
     * Broadcast deterministic device status cues (battery, power, thermal, physical reflexes, lock/unlock, peripherals)
     */
    fun processDeviceStatusCue(
        cueText: String,
        category: com.ghost.api.logic.CueCategory = com.ghost.api.logic.CueCategory.POWER,
        showInChat: Boolean = true
    ) {
        hardwareBarkController.processDeviceStatusCue(cueText, category, showInChat)
    }

    fun processQueryFromUi(query: String) {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val userBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (userBackend == "OFF") {
            uiCallback?.onMessageAdded(query, isUser = true)
            uiCallback?.onMessageAdded("Inference Engine is set to OFF in Settings. Select AUTO, CPU, or GPU to enable on-device chat.", isUser = false)
            return
        }

        val downloadState = modelDownloader.downloadStatus.value
        if (downloadState is ModelDownloader.DownloadState.Downloading) {
            uiCallback?.onMessageAdded(query, isUser = true)
            val mbDone = downloadState.bytesDownloaded / 1024 / 1024
            val mbTotal = downloadState.totalBytes / 1024 / 1024
            val name = ContextManager.resolveDeviceCallSign(applicationContext)
            uiCallback?.onMessageAdded("✧ $name is downloading neural weights (${downloadState.progressPercent}% • ${mbDone}MB / ${mbTotal}MB). Please wait for the download to finish! 📥", isUser = false)
            return
        }

        // Accept query and emit UI bubble immediately
        uiCallback?.onMessageAdded(query, isUser = true)
        uiCallback?.onThinkingStateChanged(true)

        serviceScope.launch {
            try {
                // Pass it through the core pipeline (ensureEngineReady will wake up suspended weights seamlessly)
                val response = processQuery(query, null, false, fromUi = true)

                withContext(Dispatchers.Main) {
                    uiCallback?.onThinkingStateChanged(false)
                    if (response == null) {
                        uiCallback?.onMessageAdded("Error: Request timed out or returned null.", isUser = false)
                    } else if (response.startsWith("Error:") || response.startsWith("I stumbled")) {
                        uiCallback?.onMessageAdded(response, isUser = false)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "UI processing failure")
                withContext(Dispatchers.Main) {
                    uiCallback?.onThinkingStateChanged(false)
                    uiCallback?.onMessageAdded("Error: ${e.message}", isUser = false)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    uiCallback?.onThinkingStateChanged(false)
                }
            }
        }
    }

    fun processMultimodalFromUi(
        query: String,
        images: List<android.graphics.Bitmap>? = null,
        audio: ByteArray? = null,
        imageUris: List<String>? = null
    ) {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val userBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (userBackend == "OFF") {
            uiCallback?.onMessageAdded(query, isUser = true, image = images?.firstOrNull(), imageUri = imageUris?.firstOrNull())
            uiCallback?.onMessageAdded("Inference Engine is set to OFF in Settings. Select AUTO, CPU, or GPU to enable on-device chat.", isUser = false)
            return
        }

        val persistentUris = images?.mapIndexed { idx, bmp ->
            imageUris?.getOrNull(idx) ?: try {
                val dir = java.io.File(filesDir, "chat_images").apply { mkdirs() }
                val file = java.io.File(dir, "ui_${System.currentTimeMillis()}_$idx.jpg")
                java.io.FileOutputStream(file).use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                }
                file.absolutePath
            } catch (e: Exception) {
                null
            }
        }

        // Emit user message with attached image preview and audio memo preview immediately
        val primaryUri = persistentUris?.firstOrNull()
        val audioDurationMs = audio?.let { (it.size / (16000 * 2)).toLong() * 1000L }
        uiCallback?.onMessageAdded(
            message = query,
            isUser = true,
            image = images?.firstOrNull(),
            imageUri = primaryUri,
            images = images ?: emptyList(),
            audioData = audio,
            audioDurationMs = audioDurationMs
        )
        uiCallback?.onThinkingStateChanged(true)

        serviceScope.launch {
            try {
                if (!ensureEngineReady()) {
                    withContext(Dispatchers.Main) {
                        uiCallback?.onThinkingStateChanged(false)
                        uiCallback?.onMessageAdded("System is still initializing. Please wait a moment and try again.", isUser = false)
                    }
                    return@launch
                }

                images?.forEachIndexed { idx, bmp ->
                    ghostAgent.offerImage(bmp, persistentUris?.getOrNull(idx))
                }
                audio?.let { ghostAgent.offerAudio(it) }

                val response = processQuery(query, null, false, fromUi = true)
                withContext(Dispatchers.Main) {
                    uiCallback?.onThinkingStateChanged(false)
                    if (response == null) {
                        uiCallback?.onMessageAdded("Error: Request timed out or returned null.", isUser = false)
                    } else if (response.startsWith("Error:") || response.startsWith("I stumbled")) {
                        uiCallback?.onMessageAdded(response, isUser = false)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "UI multimodal processing failure")
                withContext(Dispatchers.Main) {
                    uiCallback?.onThinkingStateChanged(false)
                    uiCallback?.onMessageAdded("Error: ${e.message}", isUser = false)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    uiCallback?.onThinkingStateChanged(false)
                }
            }
        }
    }

    suspend fun recordAudio(durationSeconds: Int): ByteArray? {
        return withContext(Dispatchers.IO) {
            if (::audioRecorder.isInitialized) {
                audioRecorder.record(durationSeconds, rawPcm = false)
            } else null
        }
    }

    var currentInFlightQuery: String? = null
        private set
    @Volatile var currentInFlightJob: kotlinx.coroutines.Job? = null
        private set

    /**
     * Core orchestrator: Context gathering + LLM reasoning + Tool execution
     */
    suspend fun processQuery(
        userPrompt: String,
        sessionId: String? = null,
        isDream: Boolean = false,
        fromUi: Boolean = false,
        inputSource: com.ghost.api.logic.InputSource = if (fromUi) com.ghost.api.logic.InputSource.USER_TYPED else com.ghost.api.logic.InputSource.BACKGROUND_TASK
    ): String? {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)

        // 1. Fast-Path Router (Zero-Token Bypass & Entity Extraction)
        // Does not run on autonomous dream cycles or internal background tasks
        var effectivePrompt = userPrompt
        var promptHint: String? = null
        if (!isDream) {
            val isFastPathEnabled = prefs.getBoolean(Constants.PREF_FAST_PATH_ENABLED, true)
            val routingResult = com.ghost.api.logic.FastPathRouter.route(
                input = userPrompt,
                source = inputSource,
                isFastPathEnabled = isFastPathEnabled
            )

            when (routingResult) {
                is com.ghost.api.logic.RoutingResult.HandledFastPath -> {
                    Timber.i("⚡ [FastPath] Handled without LLM: ${routingResult.action} | feedback=${routingResult.feedback}")
                    markActivity()
                    val phraseToSpeak = routingResult.spokenTts ?: routingResult.feedback
                    if (::ttsManager.isInitialized) {
                        ttsManager.stop()
                        ttsManager.speak(phraseToSpeak)
                    }
                    if (::ghostAgent.isInitialized) {
                        ghostAgent.recordFastPathTurn(
                            userText = userPrompt,
                            assistantHud = routingResult.feedback,
                            spokenTts = phraseToSpeak
                        )
                    }
                    withContext(Dispatchers.Main) {
                        if (!fromUi) {
                            uiCallback?.onMessageAdded(userPrompt, isUser = true)
                        }
                        uiCallback?.onMessageAdded(routingResult.feedback, isUser = false)
                    }
                    routingResult.execute?.invoke(this@GemmaService)
                    responseNotificationManager.showResponse("⚡ ${routingResult.feedback}")
                    return routingResult.feedback
                }
                is com.ghost.api.logic.RoutingResult.FallThroughToLlm -> {
                    effectivePrompt = routingResult.cleanUserMessage
                    if (routingResult.augmentedUserMessage != routingResult.cleanUserMessage) {
                        promptHint = routingResult.augmentedUserMessage.substringAfter(routingResult.cleanUserMessage).trim()
                    }
                }
            }
        }

        val userBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (userBackend == "OFF") {
            val msg = "Inference Engine is set to OFF in Settings. Select AUTO, CPU, or GPU to enable on-device chat."
            if (!isDream) {
                responseNotificationManager.showResponse("⚠️ $msg")
            }
            return msg
        }

        // Seamless wakeup from suspension OUTSIDE of engineMutex to prevent recursive mutex deadlock
        if (!ensureEngineReady()) {
            responseNotificationManager.showResponse("⚠️ System still starting up... try again in a moment")
            return "System is still initializing. Please wait a moment and try again."
        }

        return engineMutex.withLock {
            if (!isDream) {
                markActivity()
                if (::ttsManager.isInitialized) {
                    ttsManager.stop()
                }
                currentInFlightQuery = userPrompt
                if (!fromUi) {
                    withContext(Dispatchers.Main) {
                        uiCallback?.onMessageAdded(userPrompt, isUser = true)
                        uiCallback?.onThinkingStateChanged(true)
                    }
                }
            }

            isInferencing = true
            currentInFlightJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            try {
                return@withLock kotlinx.coroutines.withTimeoutOrNull(240000) {
                    val response = ghostAgent.processUserMessage(
                        message = effectivePrompt,
                        sessionId = sessionId ?: java.util.UUID.randomUUID().toString(),
                        isDream = isDream,
                        promptHint = promptHint
                    )
                    
                    // Watchdog Fix (Audit 3.0): Reset crash counter on successful inference
                    if (response != null && !response.contains("Error:")) {
                        getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                            .edit().putInt("init_crash_count", 0).apply()
                    }
                    
                    response ?: "Error: Agent returned null."
                }
            } finally {
                isInferencing = false
                currentInFlightQuery = null
                currentInFlightJob = null
                responseNotificationManager.cancelThinking()
                if (!isDream) {
                    withContext(Dispatchers.Main) {
                        uiCallback?.onThinkingStateChanged(false)
                    }
                }
            }
        }
    }

    /**
     * Streaming entry point for the OpenAI SSE endpoint.
     * Feeds tokens to [onToken] as they arrive from the engine.
     * Used by ApiServer /v1/chat/completions when stream=true.
     */
    suspend fun streamQueryTokens(prompt: String, onToken: (String) -> Unit) {
        if (!ensureEngineReady()) {
            onToken("System is still initializing.")
            return
        }
        engineMutex.withLock {
            markActivity()
            // Register a temporary token observer, then run inference
            ghostAgent.streamUserMessageTokens(
                message = prompt,
                sessionId = java.util.UUID.randomUUID().toString(),
                onToken = onToken
            )
        }
    }


    private var animationJob: Job? = null
    private var lastActivityTime = System.currentTimeMillis()
    private var lastKvFlushTime = System.currentTimeMillis()

    private fun buildNotification(textToNotify: String? = null): Notification {
        return foregroundNotificationController.buildNotification(
            textToNotify = textToNotify,
            sensorFusionManager = if (::sensorFusionManager.isInitialized) sensorFusionManager else null,
            hardwarePropertiesManager = if (::hardwarePropertiesManager.isInitialized) hardwarePropertiesManager else null,
            isInferencing = isInferencing,
            isSuspendedDueToRam = isSuspendedDueToRam.get(),
            isGemmaLoaded = isGemmaLoaded(),
            downloadState = modelDownloader.downloadStatus.value
        )
    }

    private fun startAnimationLoop() {
        animationJob?.cancel()
        animationJob = scope.launch {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            while (isActive) {
                val isInteractive = powerManager?.isInteractive ?: true

                if (isInteractive) {
                    foregroundNotificationController.advanceFrame()
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, buildNotification())
                }

                // RAM Safety Valve: Check if system is under critical memory pressure (>= 94% or < 400MB free)
                if (isIdle()) {
                    checkRamPressureAndSuspendIfNeeded()
                }

                val now = System.currentTimeMillis()

                // Auto-Reload Safety Valve: Proactively reload Gemma as soon as memory cools below 54%
                ramValveController.checkAutoReloadIfCooled(now)

                val delayMs = if (isInteractive) 2500L else 30000L

                if (now - lastActivityTime > 15 * 60 * 1000 && now - lastKvFlushTime > 15 * 60 * 1000) {
                    lastKvFlushTime = now
                    if (::ghostAgent.isInitialized) {
                        Timber.d("GemmaService: Triggering 15-min inactivity KV Cache Flush")
                        ghostAgent.sendSystemEvent(GhostAgent.SystemEventType.KV_CACHE_FLUSH)
                    }
                }

                delay(delayMs)
            }
        }
    }

    override fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Re-renders the live foreground notification telemetry card so battery and sensors
     * never stay frozen when the phone is idling or engine weights are suspended.
     */
    fun refreshNotificationTelemetry() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Timber.w(e, "Failed to refresh notification telemetry")
        }
    }

    private fun markActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    private fun isIdle(): Boolean {
        if (!isGemmaLoaded() || modelDownloader.downloadStatus.value is ModelDownloader.DownloadState.Downloading) {
            return false
        }
        return (System.currentTimeMillis() - lastActivityTime) > (30 * 1000)
    }

    private fun isSpeaking(): Boolean = if (::ttsManager.isInitialized) ttsManager.isSpeaking() else false

    fun checkRamPressureAndSuspendIfNeeded(): Boolean =
        ramValveController.checkRamPressureAndSuspendIfNeeded()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ramValveController.handleTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ramValveController.handleLowMemory()
    }

    /**
     * Ensures Gemma engine weights and GhostAgent are loaded and ready.
     * Re-initializes seamlessly from checkpoint if engine was suspended due to RAM pressure.
     */
    suspend fun ensureEngineReady(): Boolean {
        if (isGemmaLoaded() && ::ghostAgent.isInitialized && ghostAgent.isReady) {
            isSuspendedDueToRam.set(false)
            return true
        }
        Timber.i("Engine not loaded or agent not ready. Waking up engine weights...")
        updateNotification("Waking up Gemma...")
        initialize()
        val ready = isGemmaLoaded() && ::ghostAgent.isInitialized && ghostAgent.isReady
        if (ready) {
            isSuspendedDueToRam.set(false)
        }
        return ready
    }

    /**
     * Wakes up engine weights if they were suspended due to RAM pressure
     * or if user returned to the app. Non-blocking call for UI/activity resumes.
     */
    fun resumeEngineIfNeeded() {
        val userBackend = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (userBackend == "OFF") return
        if (isSuspendedDueToRam.get() || !isGemmaLoaded()) {
            Timber.i("Resuming engine from suspension / inactive state...")
            serviceScope.launch {
                ensureEngineReady()
            }
        }
    }

    /**
     * Called when a task from this app is removed from recents.
     *
     * WARNING: This fires for ANY task removal — including ShareReceiverActivity's
     * empty task (taskAffinity="", excludeFromRecents=true). Do NOT tear down the
     * service here or the shake detector, overlay, and API server all die.
     *
     * Only checkpoint state. Full cleanup belongs in onDestroy().
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.i("GemmaService: onTaskRemoved - checkpointing (NOT tearing down)")
        // Checkpoint agent state in case process is killed next
        if (::ghostAgent.isInitialized) {
            try {
                serviceScope.launch {
                    try {
                        kotlinx.coroutines.withTimeout(2000L) {
                            ghostAgent.checkpoint()
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Async checkpoint failed")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Checkpoint on task removed failed")
            }
        }
    }



    override fun onDestroy() {
        Timber.i("\uD83D\uDED1 GemmaService: Shutting down system...")
        instance = null // Set early to prevent leaks
        
        // 1. Immediate UI/Foreground Release
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Timber.w("stopForeground failed: ${e.message}")
        }
        
        // 2. Critical Native Cleanup (Must be synchronous/prioritized to release GPU)
        try {
            kotlinx.coroutines.runBlocking {
                // Allow up to 5s to ensure GPU allocations and native engines are completely released
                kotlinx.coroutines.withTimeout(5000) {
                    performCriticalCleanup()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Critical native cleanup failed or timed out")
        }

        // 3. Synchronous cleanup of sensory/network resources BEFORE cancelling scopes
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(3000) {
                    if (::apiServer.isInitialized) apiServer.stop()
                    if (::sensorFusionManager.isInitialized) sensorFusionManager.stop()
                    if (::shakeDetector.isInitialized) shakeDetector.stop()
                    if (::overlayManager.isInitialized) overlayManager.detachEdgeNub()
                }
            }
            Timber.i("✅ GemmaService: Shutdown complete")
        } catch (e: Exception) {
            Timber.e(e, "Error during GemmaService shutdown")
        }

        // 4. Cancel scopes AFTER all cleanup is done
        serviceScope.cancel()
        scope.cancel()
        
        super.onDestroy()
    }


    private suspend fun performCriticalCleanup() {
        // Fast cleanup
        try {
            if (::ghostAgent.isInitialized) ghostAgent.shutdown()
            
            // Clean up sensors properly to stop threads and listeners (RAM leak fix)
            if (::sensorFusionManager.isInitialized) {
                sensorFusionManager.close()
            }
            
            if (::ttsManager.isInitialized) ttsManager.shutdown()
            
            unloadEngine()
            
            if (::shakeDetector.isInitialized) shakeDetector.stop()
            if (::overlayManager.isInitialized) overlayManager.hideOverlay()
            if (::apiServer.isInitialized) apiServer.stop()
        } catch (e: Exception) {
            Timber.w(e, "Fast cleanup error")
        }
    }

    //
    // AgentPlatformCallbacks implementation
    //

    override fun showThinking() {
        if (uiCallback == null) {
            responseNotificationManager.showThinking()
        }
        showWorkSignal("THINKING")
    }

    override fun cancelThinking() {
        responseNotificationManager.cancelThinking()
        hideWorkSignal(force = true)
    }

    override fun showResponse(text: String, enableBubble: Boolean) {
        responseNotificationManager.showResponse(text)
    }

    override fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean, webviewUrl: String?, webviewAspectRatio: Float?) {
        uiCallback?.onMessageAdded(
            message = message,
            isUser = isUser,
            isComplete = isComplete,
            webviewUrl = webviewUrl,
            webviewAspectRatio = webviewAspectRatio
        )
        // Show Gemma response in notification tray alongside sensor card
        // Ensures user sees response without opening the app, especially when TTS is disabled
        if (!isUser && isComplete && message.isNotBlank()) {
            if (::responseNotificationManager.isInitialized) {
                responseNotificationManager.showResponse(message)
            }
        }
    }

    override fun onEmotionSignal(emoji: String) {
        // Emoji extracted from leading response character — used for toast/edge lighting signals
        Timber.d("Emotion signal: $emoji")
        com.ghost.api.audio.SystemVisualizer.pushEmotionColor(emoji)
        
        // Actually flash the emoji on screen via Toast
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, emoji, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onThoughtUpdated(thought: String) {
        uiCallback?.onThoughtUpdated(thought)
    }

    override fun onThoughtComplete(thought: String) {
        // Persist complete thoughts to Diary for long-term grounding
        scope.launch(Dispatchers.IO) {
            val thermal = getCurrentThermalState()
            writeDiaryEntry("LOGIC_TRACE", thought, thermal)
            Timber.i("\uD83E\uDDE0 Logic trace persisted: ${thought.take(50)}...")
        }
    }


    override fun showConfirmation(toolName: String, params: Map<String, Any?>, description: String) {
        // Delegated to showConfirmationNotification via onConfirmationRequest callback
    }

    override fun speak(text: String) {
        if (::ttsManager.isInitialized) ttsManager.speak(text)
    }

    override fun stopSpeaking() {
        if (::ttsManager.isInitialized) ttsManager.stop()
    }

    override fun storeConversationTurn(
        userMessage: String,
        response: String,
        sessionId: String,
        imageUri: String?,
        userTimestamp: Long,
        durationMs: Long?
    ) {
        val completionEpoch = System.currentTimeMillis()
        val tokenHashVal = if (durationMs != null) "$completionEpoch|$durationMs" else "$completionEpoch"
        scope.launch(Dispatchers.IO) {
            memoryManager.storeTurn(com.ghost.api.database.ConversationTurn(
                timestamp = userTimestamp,
                userMessage = userMessage,
                assistantResponse = response,
                tokensUsed = 0,
                sessionId = sessionId,
                tokenHash = tokenHashVal,
                imageUri = imageUri
            ))
        }
    }

    override fun writeDiaryEntry(eventType: String, content: String, thermalState: String) {
        scope.launch(Dispatchers.IO) {
            memoryManager.writeDiaryEntry(eventType, content, thermalState)
            // v4.20: Dynamic multi-device signature for synchronized calendar timeline
            if (eventType in listOf("MEMORY", "DREAM")) {
                try {
                    val cleanDeviceName = com.ghost.api.logic.ContextManager.resolveDeviceCallSign(applicationContext)
                    val signature = "✧ $cleanDeviceName"
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    val timestampStr = sdf.format(java.util.Date())
                    val formattedDescription = "[ $signature • $timestampStr ]\n\n${content.take(1000)}"
                    createCalendarEvent("Δ 👾 ∇", formattedDescription)
                } catch (e: Exception) {
                    Timber.w(e, "Calendar diary write failed (non-fatal)")
                }
            }
        }
    }

    override suspend fun unloadEngine() {
        if (::ghostAgent.isInitialized) {
            try {
                ghostAgent.shutdown()
            } catch (e: Exception) {
                Timber.w(e, "Failed to shutdown GhostAgent on engine unload")
            }
        }
        val oldEngine = engineRef.getAndSet(null)
        oldEngine?.cleanup()
        System.gc()
    }

    override suspend fun reloadEngine() {
        initialize()
    }

    override fun isEngineLoaded(): Boolean = isGemmaLoaded()

    override fun getCurrentThermalState(): String {
        return if (::hardwarePropertiesManager.isInitialized) {
            hardwarePropertiesManager.thermalState.value.name
        } else "UNKNOWN"
    }

    override fun getThermalDelayMs(lastInferenceTime: Long): Long {
        if (!::hardwarePropertiesManager.isInitialized) return 0L
        val thermalState = hardwarePropertiesManager.thermalState.value
        val timeSinceLastMs = System.currentTimeMillis() - lastInferenceTime

        return when (thermalState) {
            HardwarePropertiesManager.ThermalState.CRITICAL -> {
                if (timeSinceLastMs < 2500) 2000L else 500L
            }
            HardwarePropertiesManager.ThermalState.HOT -> {
                if (timeSinceLastMs < 2000) 800L else 200L
            }
            HardwarePropertiesManager.ThermalState.WARM -> {
                if (timeSinceLastMs < 1500) 250L else 0L
            }
            HardwarePropertiesManager.ThermalState.COOL -> 0L
        }
    }

    override fun broadcastMoodChange(state: String) {
        // Broadcast to MacroDroid or other listeners
    }

    override suspend fun getRecentConversationHistory(limit: Int): List<Pair<String, String>> {
        return try {
            val turns = memoryManager.getSessionHistory(limit)
            turns.map { it.userMessage to it.assistantResponse }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch history for diary")
            emptyList()
        }
    }

    override fun createCalendarEvent(title: String, description: String) {
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                systemToolSet.calendar(title, description, 15)
                Timber.i("\uD83D\uDCC5 Calendar event created: $title")
            } else {
                Timber.w("\uD83D\uDCC5 Calendar permission not granted")
            }
        } catch (e: Exception) {
            Timber.w(e, "Calendar event creation failed (non-fatal)")
        }
    }

    override fun getSkillsList(): String {
        return if (::skillManager.isInitialized) {
            skillManager.getSkillsListPrompt()
        } else {
            "None available."
        }
    }

    // Consolidate Overlay Init Logic
    private fun setupOverlayManager() {
        if (!::overlayManager.isInitialized) return

        // Audio-first sparkle overlay integration
        overlayManager.setAudioQueryCallback { audio ->
            scope.launch {
                // LiteRT LLM expects WAV format for miniaudio decoder
                if (::ghostAgent.isInitialized) {
                    ghostAgent.offerAudio(audio)
                }
                Timber.w("AUDIO_DEBUG: Queued ${audio.size} bytes of WAV audio")

                val sessionId = java.util.UUID.randomUUID().toString()
                // Tell the model to listen to the attached audio
                processQuery("[operator has sent a voice message - please listen and respond to contents of audio]", sessionId)
            }
        }
    }

    fun showWorkSignal(tag: String = "WORKING", durationMs: Long = 0) {
        if (::overlayManager.isInitialized) {
            overlayManager.showWorkSignal(tag, durationMs)
        }
    }

    fun hideWorkSignal(force: Boolean = false) {
        if (::overlayManager.isInitialized) {
            overlayManager.hideWorkSignal(force)
        }
    }


    fun cleanupLegacyAlarms() = diaryAndCronManager.cleanupLegacyAlarms()

    fun scheduleNextDiaryAlarm(forceReschedule: Boolean = false) =
        diaryAndCronManager.scheduleNextDiaryAlarm(forceReschedule)

    fun cancelDiaryAlarm() = diaryAndCronManager.cancelDiaryAlarm()

    private fun setupDiaryWorker() = diaryAndCronManager.setupDiaryWorker()

    fun onOperatorFeedbackLogged(reason: String, details: String, flaggedResponse: String) =
        diaryAndCronManager.onOperatorFeedbackLogged(reason, details, flaggedResponse)

    fun onScreenStateChanged(isInteractive: Boolean) =
        diaryAndCronManager.onScreenStateChanged(isInteractive)

    fun checkAndRunScheduledTasks(
        specificTaskId: String? = null,
        pendingResult: android.content.BroadcastReceiver.PendingResult? = null
    ) = diaryAndCronManager.checkAndRunScheduledTasks(specificTaskId, pendingResult)

    suspend fun runDiaryCycleSuspend(manualTrigger: Boolean = false): Boolean =
        diaryAndCronManager.runDiaryCycleSuspend(manualTrigger)

    fun startDiaryCycle(
        pendingResult: android.content.BroadcastReceiver.PendingResult? = null,
        manualTrigger: Boolean = true
    ) = diaryAndCronManager.startDiaryCycle(pendingResult, manualTrigger)

    suspend fun getRecentTurns(limit: Int = 20): List<ConversationTurn> {
        return memoryManager.getSessionHistory(limit)
    }

}

