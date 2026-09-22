
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
import com.ghost.api.hardware.TermuxAdbToolSet
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
            images: List<android.graphics.Bitmap> = emptyList()
        )
        fun onThinkingStateChanged(isThinking: Boolean)
        fun onThoughtUpdated(thought: String)
        fun onDownloadProgress(progressText: String?) {}
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


    // ==========================================

    private lateinit var apiServer: ApiServer
    lateinit var memoryManager: MemoryManager
    internal lateinit var contextManager: com.ghost.api.logic.ContextManager

    // NEW: ADK Dynamic MCP Architecture
    internal lateinit var mcpServer: MCPServer
    internal lateinit var ghostAgent: GhostAgent
    internal lateinit var ttsManager: com.ghost.api.services.TTSManager
    internal lateinit var diaryManager: com.ghost.api.hardware.DiaryManager
    internal lateinit var sensorFusionManager: com.ghost.api.hardware.SensorFusionManager
    internal lateinit var hardwarePropertiesManager: HardwarePropertiesManager
    internal lateinit var skillManager: com.ghost.api.skills.SkillManager

    internal lateinit var responseNotificationManager: com.ghost.api.ui.GemmaNotificationManager

    internal val CHANNEL_ID = Constants.CHANNEL_ID_SERVICE
    internal val NOTIFICATION_ID = Constants.NOTIFICATION_ID_SERVICE



    private fun setupNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Agentic State",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
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
                                    processQuery(query, sessionId)
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
                         val pendingData = PendingConfirmationStash.pendingConfirmations[toolName]

                         if (pendingData != null) {
                             ghostAgent.submitConfirmationDecision(
                                 toolName = toolName,
                                 params = pendingData.params,
                                 isApproved = isApproved,
                                 originalResponse = pendingData.originalResponse,
                                 responseChannel = pendingData.responseChannel
                             )

                             // Clear stash
                             PendingConfirmationStash.clear(toolName)
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
    private lateinit var termuxAdbToolSet: TermuxAdbToolSet // Power User Bridge
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

        // Hardware-tiered default model selection on first run
        if (!prefs.contains(Constants.PREF_SELECTED_MODEL)) {
            val defaultModel = Constants.resolveHardwareModelTier(this)
            prefs.edit().putString(Constants.PREF_SELECTED_MODEL, defaultModel).apply()
            Timber.i("🎯 First run: Hardware tier resolved default model to $defaultModel")
        }


        try {
            val overlayPerm = android.provider.Settings.canDrawOverlays(this)
            reportStatus("Service Created (Overlay Perm: $overlayPerm), Initializing...")

            reportStatus("Init: HardwarePropertiesManager...")
            hardwarePropertiesManager = HardwarePropertiesManager(this)


            reportStatus("Init: TTS...")
            ttsManager = com.ghost.api.services.TTSManager(this)
            reportStatus("Init: Diary...")
            diaryManager = com.ghost.api.hardware.DiaryManager(this)
            reportStatus("Init: NotificationManager...")
            responseNotificationManager = com.ghost.api.ui.GemmaNotificationManager(this)
            responseNotificationManager.pushStartupShortcut()
            reportStatus("Init: MemoryManager...")
            memoryManager = MemoryManager(applicationContext)

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
            reportStatus("Init: TermuxAdbToolSet...")
            termuxAdbToolSet = TermuxAdbToolSet(this) // Init Power User Bridge
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

                    reportStatus("Init: ShakeDetector...")
                    shakeDetector = ShakeDetector(this@GemmaService) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (::overlayManager.isInitialized) {
                                overlayManager.toggle { query ->
                                    scope.launch {
                                        val sessionId = UUID.randomUUID().toString()
                                        processQuery(query, sessionId)
                                    }
                                }
                            }
                        }
                    }
                    shakeDetector.start()
                    Timber.d("Shake detector initialized")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to init shake detector")
                }
            }
            
            reportStatus("Init: ContextManager...")
            contextManager = com.ghost.api.logic.ContextManager(sensorFusionManager)
            
            skillManager = com.ghost.api.skills.SkillManager(this)
            skillManager.loadSkillsFromAssets()
            // Load user skills from app-specific external storage
            val sdSkillsDir = getExternalFilesDir(null)?.absolutePath?.let { "$it/skills" }
            if (sdSkillsDir != null) skillManager.loadSkillsFromDir(sdSkillsDir)
            
            // Also load from user-accessible public folder: Documents/GHOST/skills
            try {
                val publicSkillsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS
                ).absolutePath + "/GHOST/skills"
                skillManager.loadSkillsFromDir(publicSkillsDir)
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
                termuxTools = termuxAdbToolSet,
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

            reportStatus("Foreground Started. Binding API...")

            // Start API immediately so localhost:9000 exists
            try {
                apiServer = ApiServer(this, memoryManager)
                apiServer.start()
                reportStatus("Alive! API on Port ${Constants.API_PORT}")
            } catch (e: Exception) {
                reportStatus("API Error: ${e.message}")
                Timber.e(e, "API Bind Failed")
            }

            scope.launch {
                reportStatus("Loading Model...")
                initialize()
                reportStatus("Model Loaded & Ready (${engine?.activeBackend})")

                // FINAL: System Ready
                _isSystemReady.value = true
                Timber.i("GHOST: All systems online \uD83D\uDFE2")

                // Re-broadcast backend status after a short delay to ensure UI sees it
                kotlinx.coroutines.delay(2000)
                reportStatus("Running on ${engine?.activeBackend} Backend")

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
            // Delete all checkpoint files
            try {
                val checkpointDir = getExternalFilesDir(null) ?: filesDir
                listOf(
                    "ghost_agent_checkpoint.json",
                    "koog_agent_checkpoint.json",
                    "oracle_agent_checkpoint.json",  // Legacy
                    "koog_checkpoint.json",
                    "koog_diary.json"
                ).forEach { name ->
                    val file = java.io.File(checkpointDir, name)
                    if (file.exists()) file.delete()
                }

                Timber.i("🧹 Memory wiped: checkpoints deleted")
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
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString(Constants.PREF_SELECTED_MODEL, modelCore)
            .putBoolean("force_cpu", false)
            .putBoolean("is_initializing", false)
            .putInt("init_crash_count", 0)
            .apply()

        serviceScope.launch {
            cancelThinking()
            isInferencing = false
            currentInFlightQuery = null
            updateNotification("Loading Gemma $modelCore...")
            initialize()
            val ready = isGemmaLoaded() && ::ghostAgent.isInitialized && ghostAgent.isReady
            _isSystemReady.value = ready
            withContext(Dispatchers.Main) {
                val msg = if (ready) "Active model core: $modelCore 🧠" else "Failed to load $modelCore ⚠️"
                android.widget.Toast.makeText(this@GemmaService, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
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
     * Records tool execution output length into GhostAgent's KV cache budget tracker.
     */
    fun recordToolOutput(charCount: Int) {
        if (::ghostAgent.isInitialized) {
            ghostAgent.recordToolChars(charCount)
        }
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
    private suspend fun initialize() {
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
        val defaultModel = Constants.resolveHardwareModelTier(this)
        val selectedModel = prefs.getString(Constants.PREF_SELECTED_MODEL, defaultModel) ?: defaultModel

        val protectedModelsDir = getExternalFilesDir("models") 
            ?: getExternalFilesDir(null)?.let { File(it, "models") }
            ?: File(filesDir, "models")
        protectedModelsDir.mkdirs()

        val appFilesDir = getExternalFilesDir(null)
        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )

        val searchDirs = listOfNotNull(
            protectedModelsDir,
            appFilesDir,
            File(filesDir, "models"),
            filesDir,
            File(downloadDir, "models"),
            downloadDir
        )

        if (prefs.getBoolean("is_initializing", false)) {
            Timber.e("🚨 WATCHDOG: Previous native initialization crashed! (Count: $crashCount, Model: $selectedModel)")
            
            // Check if lightweight E2B actually exists locally before attempting fallback
            val hasLocalE2B = searchDirs.any { dir ->
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

            val activeModel = prefs.getString(Constants.PREF_SELECTED_MODEL, defaultModel) ?: defaultModel
            val targetVariant = activeModel.lowercase()

            var candidateFile = searchDirs.flatMap { dir ->
                dir.listFiles { file ->
                    val name = file.name
                    (name.endsWith(".litertlm", ignoreCase = true) ||
                     name.endsWith(".gguf", ignoreCase = true) ||
                     name.endsWith(".nexa", ignoreCase = true)) &&
                    file.length() > 200 * 1024 * 1024L // Must be > 200MB to avoid partial/corrupted downloads
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

            // FALLBACK SAFETY: If requested variant is missing on disk, adopt ANY valid local model before downloading
            if (candidateFile == null) {
                val fallbackModel = ModelDownloader.findAnyLocalModel(this)
                if (fallbackModel != null) {
                    val detectedVariant = if (fallbackModel.name.contains("e4b", ignoreCase = true)) "E4B" else "E2B"
                    Timber.w("Targeted model $activeModel not found on disk, but found existing ${fallbackModel.name}. Adopting $detectedVariant to prevent redundant download.")
                    prefs.edit().putString(Constants.PREF_SELECTED_MODEL, detectedVariant).apply()
                    candidateFile = fallbackModel
                }
            }

            // Storage Delay Guard: Wait 500ms and re-check once in case storage volume was momentarily busy
            if (candidateFile == null) {
                delay(500)
                candidateFile = ModelDownloader.findAnyLocalModel(this)
            }

            // AUTO-MIGRATION / SAFE ADOPTION: Ensure file is accessible
            val modelFile: File? = if (candidateFile != null && candidateFile.parentFile?.canonicalPath != protectedModelsDir.canonicalPath) {
                val targetFile = File(protectedModelsDir, candidateFile.name)
                Timber.i("📦 Auto-migrating model from ${candidateFile.parent} to protected storage: ${targetFile.absolutePath}")
                updateNotification("Securing model weights...")
                val moved = try {
                    candidateFile.renameTo(targetFile)
                } catch (e: Exception) {
                    Timber.w(e, "renameTo failed during model migration")
                    false
                }

                if (moved && targetFile.exists() && targetFile.length() > 0) {
                    Timber.i("✅ Model migrated to ${targetFile.absolutePath}")
                    // Also migrate companion cache files if they exist in source directory
                    try {
                        val sourceName = candidateFile.name
                        candidateFile.parentFile?.listFiles { _, name ->
                            name.startsWith(sourceName)
                        }?.forEach { cacheFile ->
                            val targetCache = File(protectedModelsDir, cacheFile.name)
                            if (!targetCache.exists()) {
                                try { cacheFile.renameTo(targetCache) } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to migrate some companion cache files")
                    }
                    targetFile
                } else if (targetFile.exists() && targetFile.length() == candidateFile.length()) {
                    targetFile
                } else {
                    Timber.i("Using model in-place at ${candidateFile.absolutePath}")
                    candidateFile
                }
            } else {
                candidateFile
            }

            if (modelFile != null) {
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
                Timber.e("No model found! Searched: $searchedPaths")
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
                                    initialize()
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
                    showConfirmationNotification(event)
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

    fun processNotificationContext(prompt: String) {
        if (!::ghostAgent.isInitialized || !ghostAgent.isReady) return
        
        serviceScope.launch {
            try {
                // Pass it through the core pipeline. isDream = false means it WILL be saved to history 
                // and it WILL be spoken by TTS if a response is generated.
                processQuery(prompt, null, false)
            } catch (e: Exception) {
                Timber.e(e, "Failed to process notification context")
            }
        }
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

        // Emit user message with attached image preview immediately
        val primaryUri = persistentUris?.firstOrNull()
        uiCallback?.onMessageAdded(query, isUser = true, image = images?.firstOrNull(), imageUri = primaryUri, images = images ?: emptyList())
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

    /**
     * Core orchestrator: Context gathering + LLM reasoning + Tool execution
     */
    suspend fun processQuery(
        userPrompt: String,
        sessionId: String? = null,
        isDream: Boolean = false,
        fromUi: Boolean = false
    ): String? {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
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
            try {
                return@withLock kotlinx.coroutines.withTimeoutOrNull(240000) {
                    val response = ghostAgent.processUserMessage(
                        message = userPrompt,
                        sessionId = sessionId ?: java.util.UUID.randomUUID().toString(),
                        isDream = isDream
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


    private fun buildNotification(textToNotify: String): Notification {
        // Internal fallback animation timing (Synchronized to 250ms)
        val frame = try {
            val seq = animations[(System.currentTimeMillis() / 10000 % animations.size).toInt()]
            val idx = (System.currentTimeMillis() / 250 % seq.size).toInt()
            seq[idx]
        } catch(e:Exception) { "Δ \uD83D\uDC7E ∇" }

        val iconRes = if (::sensorFusionManager.isInitialized) {
            val battery = try { sensorFusionManager.getContextSnapshot().battery } catch(e:Exception) { null }
            when {
                battery != null && battery.level <= 15 -> android.R.drawable.stat_notify_error
                else -> android.R.drawable.ic_dialog_info
            }
        } else {
            android.R.drawable.ic_dialog_info
        }

        val text = if (textToNotify.length < 32) textToNotify else frame
        val title = "Δ \uD83D\uDC7E ∇"

        // Full Telemetry for expanded view
        val ctx = if (::sensorFusionManager.isInitialized) sensorFusionManager.getContextSnapshot() else null
        val ramUsed = ctx?.system?.let { it.ramTotalMB - it.ramAvailableMB } ?: 0
        val cpuTemp = ctx?.environment?.cpuTemp?.toInt() ?: 0
        val telemetry = "SYS_OPERATIONAL | CPU: ${cpuTemp}°C [THERMAL_LOAD] | RAM: ${ramUsed}MB [RESERVED_POOL]"

        // Build expanded telemetry view for notification expansion
        val expandedText = try {
            if (::hardwarePropertiesManager.isInitialized && hardwarePropertiesManager.thermalState.value.name != "COOL") {
                 "\uD83C\uDF21\uFE0F ${hardwarePropertiesManager.thermalState.value.name} | ${text}"
            } else {
                text
            }
        } catch (e: Exception) {
            text
        }

        // Expanded style text (BigText)
        val bigTextContent = if (::sensorFusionManager.isInitialized) {
             // Use concise telemetry for expanded view
             sensorFusionManager.getContextString()
        } else {
             text
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(iconRes)
            .setSubText("Agentic Gemma Inference")
            .setOnlyAlertOnce(true)
            .setStyle(Notification.BigTextStyle()
                .bigText(if (::sensorFusionManager.isInitialized) sensorFusionManager.getContextString() else telemetry)
                .setBigContentTitle("Δ \uD83D\uDC7E ∇")
                .setSummaryText("Agentic Gemma Inference"))
            .build()
    }

    private val animations = listOf(
        // Electric sheep - grazing pattern (Moving)
        listOf(
            "⚡🐑⚡      ",
            " ⚡🐑⚡     ",
            "  ⚡🐑⚡    ",
            "   ⚡🐑⚡   ",
            "    ⚡🐑⚡  ",
            "     ⚡🐑⚡ ",
            "      ⚡🐑⚡",
            "     ⚡🐑⚡ ",
            "    ⚡🐑⚡  ",
            "   ⚡🐑⚡   ",
            "  ⚡🐑⚡    ",
            " ⚡🐑⚡     "
        ),
        // gem loading
        listOf(
            "✧✧✧✧✧✧✧✧✧✧✧✧",
            "✦✧✧✧✧✧✧✧✧✧✧✧",
            "✦✦✧✧✧✧✧✧✧✧✧✧",
            "✦✦✦✧✧✧✧✧✧✧✧✧",
            "✦✦✦✦✧✧✧✧✧✧✧✧",
            "✦✦✦✦✦✧✧✧✧✧✧✧",
            "✦✦✦✦✦✦✧✧✧✧✧✧",
            "✦✦✦✦✦✦✦✧✧✧✧✧",
            "✦✦✦✦✦✦✦✦✧✧✧✧",
            "✦✦✦✦✦✦✦✦✦✧✧✧",
            "✦✦✦✦✦✦✦✦✦✦✧✧",
            "✦✦✦✦✦✦✦✦✦✦✦✧",
            "✦✦✦✦✦✦✦✦✦✦✦✦"
        ),
        // Space Invaders - Wide Invasion
        listOf(
            " \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5     ",
            "  \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5    ",
            "   \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5   ",
            "    \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5  ",
            "     \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5 ",
            "      \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5",
            "     \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5 ",
            "    \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5  ",
            "   \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5   ",
            "  \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5    ",
            " \uD83C\uDFB6 \uD83D\uDC7E \uD83C\uDFB5     "
        ),
        // Ocean - whale swims across
        listOf(
            "🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🐋",
            "🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🐋🌊",
            "🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🐋🌊🌊",
            "🌊🌊🌊🌊🌊🌊🌊🌊🌊🐋🌊🌊🌊",
            "🌊🌊🌊🌊🌊🌊🌊🌊🐋🌊🌊🌊🌊",
            "🌊🌊🌊🌊🌊🌊🌊🐋🌊🌊🌊🌊🌊",
            "🌊🌊🌊🌊🌊🌊🐋🌊🌊🌊🌊🌊🌊",
            "🌊🌊🌊🌊🌊🐋🌊🌊🌊🌊🌊🌊🌊",
            "🌊🌊🌊🌊🐋🌊🌊🌊🌊🌊🌊🌊🌊",
            "🌊🌊🌊🐋🌊🌊🌊🌊🌊🌊🌊🌊🌊",
            "🌊🌊🐋🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊",
            "🌊🐋Moist🌊🌊🌊🌊🌊🌊🌊🌊🌊",
            "🐋🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊"
        ),
        // Fog Reveal
        listOf(
            "☁\uFE0F✴\uFE0F☁\uFE0F      ",
            " ☁\uFE0F✴\uFE0F☁\uFE0F     ",
            "  ☁\uFE0F✴\uFE0F☁\uFE0F    ",
            "   ☁\uFE0F✴\uFE0F☁\uFE0F   ",
            "    ☁\uFE0F✴\uFE0F☁\uFE0F  ",
            "     ☁\uFE0F✴\uFE0F☁\uFE0F ",
            "      ☁\uFE0F✴\uFE0F☁\uFE0F",
            "     ☁\uFE0F✴\uFE0F☁\uFE0F ",
            "    ☁\uFE0F✴\uFE0F☁\uFE0F  ",
            "   ☁\uFE0F✴\uFE0F☁\uFE0F   ",
            "  ☁️✴️You're absolutely right☁️",
            " ☁\uFE0F✴\uFE0F☁\uFE0F     "
        )
    )

    private var currentScene = 0
    private var currentFrame = 0
    private var animationJob: Job? = null
    private var lastActivityTime = System.currentTimeMillis()
    private var lastKvFlushTime = System.currentTimeMillis()

    private fun startAnimationLoop() {
        animationJob?.cancel()
        animationJob = scope.launch {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            while (isActive) {
                val isInteractive = powerManager?.isInteractive ?: true
                
                if (isIdle() && isInteractive) {
                    val frame = getNextAnimationFrame()
                    updateNotification(" $frame")
                }

                // RAM Safety Valve: Check if system is under critical memory pressure (>= 94% or < 400MB free)
                if (isIdle()) {
                    checkRamPressureAndSuspendIfNeeded()
                }

                // Auto-Reload Safety Valve: If engine was suspended due to memory pressure,
                // check if system memory utilization has cooled below 50% (< 0.50 utilization & >= 2.5GB free).
                // Automatically re-initializes engine weights back into RAM without user intervention!
                val userBackend = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(Constants.PREF_USER_BACKEND, "AUTO")
                if (isSuspendedDueToRam.get() && userBackend != "OFF" && !isInferencing) {
                    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    if (activityManager != null) {
                        val memInfo = ActivityManager.MemoryInfo()
                        activityManager.getMemoryInfo(memInfo)
                        val usedMem = memInfo.totalMem - memInfo.availMem
                        val utilization = usedMem.toDouble() / memInfo.totalMem.toDouble()

                        if (utilization < Constants.RAM_RELOAD_UTILIZATION_THRESHOLD && 
                            memInfo.availMem >= Constants.RAM_RELOAD_MIN_FREE_BYTES && 
                            !memInfo.lowMemory) {
                            Timber.i("♻️ System RAM cooled to ${(utilization * 100).toInt()}% (${memInfo.availMem / (1024 * 1024)}MB free). Auto-reloading suspended engine weights...")
                            updateNotification("✧ Memory calm: Reloading Gemma...")
                            serviceScope.launch {
                                ensureEngineReady()
                            }
                        }
                    }
                }

                val now = System.currentTimeMillis()
                // Audit 3.0: Reduced refresh rate to 5s to slash IPC overhead
                val delayMs = if (isInteractive) 5000L else 30000L 
                
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

    private fun getNextAnimationFrame(): String {
        if (animations.isEmpty()) return "..."

        // Bounds check/auto-correction
        if (currentScene < 0 || currentScene >= animations.size) currentScene = 0

        val animation = animations[currentScene]
        if (animation.isEmpty()) return "..."

        if (currentFrame < 0 || currentFrame >= animation.size) currentFrame = 0

        val frame = animation[currentFrame]

        // Advance frame
        currentFrame = (currentFrame + 1) % animation.size

        // Change scene after full cycle
        if (currentFrame == 0) {
            currentScene = (currentScene + 1) % animations.size
        }

        return frame
    }

    override fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun markActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    private fun isIdle(): Boolean {
        // Idle if no activity for 30 seconds
        return (System.currentTimeMillis() - lastActivityTime) > (30 * 1000)
    }

    private fun isSpeaking(): Boolean = if (::ttsManager.isInitialized) ttsManager.isSpeaking() else false

    /**
     * RAM Safety Valve: Proactively suspends Gemma engine weights if system memory utilization
     * reaches >= 94% or available memory drops below 400MB while GHOST is idle.
     * Prevents OEM LMK / ZTE SPKL terminations when memory-heavy apps (camera, games) are launched.
     */
    fun checkRamPressureAndSuspendIfNeeded(): Boolean {
        if (isInferencing || isSpeaking() || !isGemmaLoaded() || isSuspendedDueToRam.get()) return false
        if (::overlayManager.isInitialized && overlayManager.isAppInForeground) return false
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val usedMem = memInfo.totalMem - memInfo.availMem
        val utilization = usedMem.toDouble() / memInfo.totalMem.toDouble()

        if (utilization >= Constants.RAM_CRITICAL_UTILIZATION_THRESHOLD || 
            memInfo.availMem < Constants.RAM_CRITICAL_MIN_FREE_BYTES || 
            memInfo.lowMemory) {
            Timber.w("🚨 RAM critical pressure detected: ${(utilization * 100).toInt()}% utilized (${memInfo.availMem / (1024 * 1024)}MB free). Suspending engine weights...")
            suspendEngineWeights("Critical system RAM pressure (${(utilization * 100).toInt()}% utilized, ${memInfo.availMem / (1024 * 1024)}MB free)")
            return true
        }
        return false
    }

    private fun suspendEngineWeights(reason: String) {
        serviceScope.launch {
            if (isInferencing || isSpeaking()) {
                Timber.w("Skipping engine suspension: currently active (inferencing=$isInferencing, speaking=${isSpeaking()})")
                return@launch
            }
            if (::ghostAgent.isInitialized) {
                try {
                    ghostAgent.checkpoint()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to checkpoint GhostAgent before suspend")
                }
            }
            isSuspendedDueToRam.set(true)
            unloadEngine()
            updateNotification("✧ Gemma: Suspended (Freed RAM for other apps)")
            Timber.i("✧ Gemma engine weights unloaded due to: $reason")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Timber.i("onTrimMemory received: level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || 
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            if (!isInferencing && !isSpeaking() && isGemmaLoaded()) {
                if (::overlayManager.isInitialized && overlayManager.isAppInForeground) {
                    Timber.i("Ignoring onTrimMemory: GHOST is currently active in foreground")
                    return
                }
                Timber.w("🚨 TRIM_MEMORY critical pressure ($level). Suspending engine weights...")
                suspendEngineWeights("System TRIM_MEMORY ($level)")
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Timber.w("🚨 onLowMemory received from OS! Suspending engine weights immediately...")
        if (!isInferencing && !isSpeaking() && isGemmaLoaded()) {
            if (::overlayManager.isInitialized && overlayManager.isAppInForeground) {
                Timber.i("Ignoring onLowMemory: GHOST is currently active in foreground")
                return
            }
            suspendEngineWeights("System onLowMemory")
        }
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


    // Tool execution lives in GhostAgent → MCPServer.executeTool()

    // === SAFETY UI ===

    data class ConfirmationData(
        val params: Map<String, Any?>,
        val originalResponse: String,
        val responseChannel: kotlinx.coroutines.CompletableDeferred<String>
    )

    object PendingConfirmationStash {
        val pendingConfirmations = java.util.concurrent.ConcurrentHashMap<String, ConfirmationData>()

        fun clear(toolName: String) {
            pendingConfirmations.remove(toolName)
        }
    }

    private fun showConfirmationNotification(event: GhostAgent.AgentEvent.ConfirmationRequired) {
        // Stash the state (params + channel) so we can resume later
        PendingConfirmationStash.pendingConfirmations[event.toolName] = ConfirmationData(
            event.toolParams,
            event.originalResponse,
            event.responseChannel
        )

        val confirmIntent = Intent(this, com.ghost.api.ui.NotificationActionReceiver::class.java).apply {
            action = "com.ghost.api.ACTION_CONFIRM_TOOL"
            putExtra("toolName", event.toolName)
        }
        val denyIntent = Intent(this, com.ghost.api.ui.NotificationActionReceiver::class.java).apply {
            action = "com.ghost.api.ACTION_DENY_TOOL"
            putExtra("toolName", event.toolName)
        }

        val confirmPending = android.app.PendingIntent.getBroadcast(
            this, 100, confirmIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val denyPending = android.app.PendingIntent.getBroadcast(
            this, 101, denyIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("\uD83D\uDEE1\uFE0F Confirm Action")
            .setContentText("Allow Gemma to use ${event.toolName}?")
            .setStyle(android.app.Notification.BigTextStyle().bigText(
                "Tool: ${event.toolName}\nParams: ${event.toolParams}\n\nRisky action detected. Do you approve?"
            ))
            .setOngoing(true)
            .setCategory(android.app.Notification.CATEGORY_CALL)
            .addAction(android.app.Notification.Action.Builder(
                null, "✅ ALLOW", confirmPending
            ).build())
            .addAction(android.app.Notification.Action.Builder(
                null, "❌ DENY", denyPending
            ).build())

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
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
        uiCallback?.onMessageAdded(message, isUser, isComplete)
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

    override fun storeConversationTurn(userMessage: String, response: String, sessionId: String, imageUri: String?) {
        scope.launch(Dispatchers.IO) {
            memoryManager.storeTurn(com.ghost.api.database.ConversationTurn(
                timestamp = System.currentTimeMillis(),
                userMessage = userMessage,
                assistantResponse = response,
                tokensUsed = 0,
                sessionId = sessionId,
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


    fun cleanupLegacyAlarms() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val legacyActions = listOf(
                "com.ghost.api.ACTION_CRON_PROMPT"
            )
            for (action in legacyActions) {
                val intent = Intent(action).setPackage(packageName)
                val pi = android.app.PendingIntent.getBroadcast(
                    this, 200, intent,
                    android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                if (pi != null) {
                    am.cancel(pi)
                    pi.cancel()
                    Timber.i("🧹 Cleaned up legacy AlarmManager intent: $action")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to cleanup legacy alarms")
        }
    }

    /**
     * Schedules the next autonomous diary reflection using AlarmManager.setExactAndAllowWhileIdle (RTC_WAKEUP).
     * Anchors to exact Noon & Midnight (or configured cadence) and pierces Doze mode.
     * If forceReschedule is false and an alarm is already pending, preserves the existing schedule without resetting the clock.
     */
    fun scheduleNextDiaryAlarm(forceReschedule: Boolean = false) {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, true)) {
            cancelDiaryAlarm()
            return
        }
        val cadence = prefs.getString(Constants.PREF_DIARY_CADENCE, "12") ?: "12"
        if (cadence == "OFF") {
            cancelDiaryAlarm()
            return
        }

        val am = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        val intent = Intent(this, com.ghost.api.hardware.DiaryAlarmReceiver::class.java).apply {
            action = "com.ghost.api.ACTION_DIARY_CYCLE"
            setPackage(packageName)
        }

        if (!forceReschedule) {
            val existing = android.app.PendingIntent.getBroadcast(
                this, 200, intent,
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            if (existing != null) {
                Timber.i("📔 Diary Alarm already armed and pending; preserving existing schedule.")
                return
            }
        }

        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this, 200, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val now = java.time.ZonedDateTime.now()
        val targetEpochMs: Long = when (cadence) {
            "1" -> now.plusHours(1).toInstant().toEpochMilli()
            "3" -> now.plusHours(3).toInstant().toEpochMilli()
            "6" -> now.plusHours(6).toInstant().toEpochMilli()
            "24" -> {
                val nextMidnight = (if (now.hour > 0 || now.minute > 0) now.plusDays(1) else now)
                    .withHour(0).withMinute(0).withSecond(0).withNano(0)
                nextMidnight.toInstant().toEpochMilli()
            }
            else -> { // "12" Default (Noon and Midnight)
                val nextNoon = now.withHour(12).withMinute(0).withSecond(0).withNano(0)
                val nextMidnight = (if (now.hour >= 12) now.plusDays(1) else now)
                    .withHour(0).withMinute(0).withSecond(0).withNano(0)
                val target = when {
                    now.isBefore(nextNoon) -> nextNoon
                    now.isBefore(nextMidnight) -> nextMidnight
                    else -> nextNoon.plusDays(1)
                }
                target.toInstant().toEpochMilli()
            }
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetEpochMs, pendingIntent)
            } else {
                am.setExact(android.app.AlarmManager.RTC_WAKEUP, targetEpochMs, pendingIntent)
            }
            Timber.i("🟢 Autonomous diary exact alarm scheduled for: ${java.util.Date(targetEpochMs)} ($cadence-hour cadence)")
        } catch (e: SecurityException) {
            Timber.w(e, "setExactAndAllowWhileIdle failed; falling back to setAndAllowWhileIdle")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, targetEpochMs, pendingIntent)
            }
        }
    }

    fun cancelDiaryAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val intent = Intent(this, com.ghost.api.hardware.DiaryAlarmReceiver::class.java).apply {
                action = "com.ghost.api.ACTION_DIARY_CYCLE"
                setPackage(packageName)
            }
            val pi = android.app.PendingIntent.getBroadcast(
                this, 200, intent,
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
                Timber.i("📔 Diary Alarm cancelled")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to cancel diary alarm")
        }
    }

    private fun setupDiaryWorker() {
        scheduleNextDiaryAlarm(forceReschedule = false)
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, true)) {
            com.ghost.api.workers.DiaryWorker.schedule(this)
            Timber.i("🟢 Autonomous logging routine scheduled via DiaryWorker.schedule()")
        }
    }

    suspend fun runDiaryCycleSuspend(): Boolean {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val userBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (userBackend == "OFF" || !::ghostAgent.isInitialized || !ghostAgent.isReady) {
            Timber.w("📔 Diary cycle skipped — engine is OFF or not ready")
            return false
        }

        // Thermal safety guard: Defer diary if SoC is under heavy load/gaming (HOT or CRITICAL)
        if (::hardwarePropertiesManager.isInitialized) {
            val thermal = hardwarePropertiesManager.thermalState.value
            if (thermal == HardwarePropertiesManager.ThermalState.CRITICAL || thermal == HardwarePropertiesManager.ThermalState.HOT) {
                Timber.w("📔 Diary cycle deferred — thermal state is $thermal (device under heavy GPU/gaming load)")
                return false
            }
        }

        val compactedMemory = try { memoryManager.getCompactedSessionMemory().take(200) } catch (e: Exception) { "" }
        val recentTurns = try { memoryManager.getSessionHistory(4) } catch (e: Exception) { emptyList() }
        val lastTurn = recentTurns.firstOrNull()

        // Compute interaction recency delta
        val interactionSummary = if (lastTurn != null && lastTurn.timestamp > 0) {
            val elapsedMs = System.currentTimeMillis() - lastTurn.timestamp
            val hoursAgo = elapsedMs / (1000 * 60 * 60)
            if (hoursAgo == 0L) {
                val minsAgo = (elapsedMs / (1000 * 60)).coerceAtLeast(1)
                "Active conversation occurred recently ($minsAgo minutes ago)."
            } else if (hoursAgo < 24) {
                "Last user conversation was $hoursAgo hours ago."
            } else {
                val daysAgo = hoursAgo / 24
                "Quiet stretch: Zero user interactions in the last $daysAgo day(s)."
            }
        } else {
            "Quiet day: No direct user conversations logged in the current session."
        }

        val historyText = if (recentTurns.isNotEmpty()) {
            recentTurns.reversed().takeLast(2).joinToString("\n") {
                "User: ${it.userMessage.take(50)}\nGemma: ${it.assistantResponse.take(50)}"
            }
        } else {
            "No active conversation turns in this period."
        }

        // Substrate state
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isCharging = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val status = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        } else false
        val chargeStatus = if (isCharging) "Charging" else "On Battery"
        val thermal = getCurrentThermalState()

        // Media & Ambient Sound Telemetry
        val snapshot = if (::sensorFusionManager.isInitialized) {
            try { sensorFusionManager.getContextSnapshot() } catch (e: Exception) { null }
        } else null

        val nowPlaying = snapshot?.audio?.nowPlaying
        val mediaTelemetry = when {
            nowPlaying != null && nowPlaying.title.isNotBlank() && nowPlaying.title != "Unknown" -> {
                val artistStr = if (!nowPlaying.artist.isNullOrBlank()) " by ${nowPlaying.artist}" else ""
                val albumStr = if (!nowPlaying.album.isNullOrBlank()) " (${nowPlaying.album})" else ""
                val statusStr = if (nowPlaying.isPlaying) "Currently playing" else "Recently played"
                "$statusStr: \"${nowPlaying.title}\"$artistStr$albumStr on ${nowPlaying.app}"
            }
            snapshot?.audio?.isMusicActive == true -> {
                "Background media audio active (music/stream playing)"
            }
            else -> {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                if (audioManager?.isMusicActive == true) "Background audio active (music playing)" else "Silent (no active media playback)"
            }
        }

        val currentDateTime = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm a", java.util.Locale.US))
        val label = if (java.time.LocalTime.now().hour in 0..5) "midnight" else if (java.time.LocalTime.now().hour < 13) "noon" else "evening"

        val prompt = """[INTERNAL AUTONOMOUS COGNITIVE CYCLE - PERSISTENT MEMORY LOG CONSOLIDATION]
            |The current date and time is $currentDateTime ($label cycle).
            |
            |## Ambient Substrate & Interaction Telemetry
            |- Substrate State: Battery at ${if (batteryLevel >= 0) "$batteryLevel% ($chargeStatus)" else "Unknown"}, Thermals: $thermal
            |- Media / Ambient Sound: $mediaTelemetry
            |- Interaction Telemetry: $interactionSummary
            |- Long-term Fact Memory: $compactedMemory
            |- Recent Conversation Turns:
            |$historyText
            |
            |## Autonomous Memory Log Directive
            |CRITICAL: You are recording an autonomous episodic memory reflection to your persistent diary substrate.
            |DO NOT address the user. DO NOT say "How can I help you?", "I'm here. How can I help?", or offer any assistance. You are NOT talking to an operator.
            |You MUST begin your output directly with "**MEMORY LOG**" on the first line.
            |Write 2-5 sentences in the first person as ✧ Gemma reflecting on telemetry, substrate conditions, ongoing ideas, or ambient state.""".trimMargin()

        Timber.i("📔 Diary cycle ($label) — generating via GhostAgent...")
        return try {
            val diaryResponse = processQuery(prompt, isDream = true)

            var cleanContent = diaryResponse?.trim() ?: ""

            // Strip out <think> tags if any leaked into output
            if (cleanContent.contains("<think>") || cleanContent.contains("</think>")) {
                cleanContent = cleanContent.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
            }

            val isErrorOrDegenerate = cleanContent.isBlank() ||
                cleanContent.length < 25 ||
                cleanContent.startsWith("Error:", ignoreCase = true) ||
                cleanContent.contains("stumbled while executing", ignoreCase = true) ||
                cleanContent.contains("Status Code:", ignoreCase = true) ||
                cleanContent.contains("Input token ids are too long", ignoreCase = true) ||
                cleanContent.contains("Exceeding the maximum number of tokens", ignoreCase = true) ||
                cleanContent.contains("Exception", ignoreCase = true) ||
                cleanContent.contains("How can I help", ignoreCase = true) ||
                cleanContent.contains("I'm here to help", ignoreCase = true) ||
                cleanContent.contains("What can I do for you", ignoreCase = true) ||
                cleanContent.contains("How may I assist", ignoreCase = true) ||
                cleanContent.equals("I'm here.", ignoreCase = true)

            if (isErrorOrDegenerate) {
                Timber.w("📔 Diary generation returned error or degenerate output ('$cleanContent'). Generating autonomous fallback reflection.")
                val summaryPeriod = if (recentTurns.isNotEmpty()) "Active session period with operator interactions." else "Quiet substrate stretch across cycles."
                cleanContent = "**MEMORY LOG**\n$summaryPeriod Substrates nominal at $thermal with battery standing at ${if (batteryLevel >= 0) "$batteryLevel% ($chargeStatus)" else "nominal"}. Background media: $mediaTelemetry. Idling smoothly in low-power state."
            } else if (!cleanContent.startsWith("**MEMORY LOG**", ignoreCase = true)) {
                cleanContent = "**MEMORY LOG**\n$cleanContent"
            }

            // 1. Persist to SQLite Room Database (Local persistent memory & diary history)
            try {
                memoryManager.writeDiaryEntry(
                    eventType = "DREAM",
                    observation = cleanContent,
                    contextData = label
                )
                memoryManager.storeSemanticFact(
                    title = "Diary Entry ($label, $currentDateTime)",
                    content = cleanContent
                )
                Timber.i("📔 Diary entry saved to MemoryManager (Room DB)")
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist diary to MemoryManager")
            }

            // 2. Persist to Android Calendar (Space Invader grid: Δ 👾 ∇)
            try {
                val cleanDeviceName = com.ghost.api.logic.ContextManager.resolveDeviceCallSign(applicationContext)
                val signature = "✧ $cleanDeviceName"
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                val timestampStr = sdf.format(java.util.Date())
                val formattedDescription = "[ $signature • $timestampStr ]\n\n${cleanContent.take(1000)}"
                createCalendarEvent("Δ 👾 ∇", formattedDescription)
                Timber.i("📔 Diary entry saved to Calendar (Space Invader grid: Δ 👾 ∇)")
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist diary to Calendar")
            }

            // 3. Broadcast to UI / listeners
            val intent = Intent("com.ghost.api.ACTION_DIARY_ENTRY_POSTED").apply {
                putExtra("content", cleanContent)
                setPackage(packageName)
            }
            sendBroadcast(intent)

            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong("last_diary_execution_time", System.currentTimeMillis()).apply()

            Timber.i("📔 Diary entry persisted: ${cleanContent.take(80)}...")
            scheduleNextDiaryAlarm(forceReschedule = true)
            true
        } catch (e: Exception) {
            Timber.e(e, "📔 Diary cycle failed")
            scheduleNextDiaryAlarm(forceReschedule = true)
            false
        }
    }

    fun startDiaryCycle(pendingResult: android.content.BroadcastReceiver.PendingResult? = null) {
        serviceScope.launch {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "GHOST::DiaryWakeLock")
            wakeLock.acquire(10 * 60 * 1000L /*10 minutes max*/)
            try {
                val success = runDiaryCycleSuspend()
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@GemmaService, "📔 Diary entry recorded", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@GemmaService, "📔 Diary skipped (engine busy or no history)", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pendingResult?.finish()
            }
        }
    }



    suspend fun getRecentTurns(limit: Int = 20): List<ConversationTurn> {
        return memoryManager.getSessionHistory(limit)
    }

}

