
package com.ghost.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import com.ghost.api.agent.KoogAgent
import com.ghost.api.mcp.MCPServer
import com.ghost.api.hardware.SensorFusionManager
import com.ghost.api.hardware.BatteryState
import com.ghost.api.hardware.DeviceContext
import com.ghost.api.logic.ContextManager

/**
 * Background service that loads Gemma and runs API server
 *
 * NEW: Koog-first architecture with MCP protocol
 * - KoogAgent handles orchestration (perceive-think-act loop)
 * - MCPServer exposes tools and resources
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
        fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean = true)
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


    // Thermal Safety State
    private val isCoolingDown = java.util.concurrent.atomic.AtomicBoolean(false)
    private val criticalCount = java.util.concurrent.atomic.AtomicInteger(0)


    // ==========================================

    private lateinit var apiServer: ApiServer
    lateinit var memoryManager: MemoryManager
    internal lateinit var contextManager: com.ghost.api.logic.ContextManager

    // NEW: Koog-first architecture
    internal lateinit var mcpServer: MCPServer
    internal lateinit var koogAgent: KoogAgent
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
                SharedMediaHolder.clear()

                if (!imagePath.isNullOrEmpty()) {
                    val bitmap = decodeAndDownsample(imagePath, 1024)
                    if (bitmap != null && ::koogAgent.isInitialized) {
                        koogAgent.offerImage(bitmap)
                        updateNotification("Image ready ✨ ask me about it!")
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
                        if (bitmap != null && ::koogAgent.isInitialized) {
                            koogAgent.offerImage(bitmap)
                            Timber.i("Agent screenshot captured and queued")
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
                     if (::koogAgent.isInitialized) {
                         val pendingData = PendingConfirmationStash.pendingConfirmations[toolName]

                         if (pendingData != null) {
                             koogAgent.submitConfirmationDecision(
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
        
        cleanupLegacyAlarms()
        setupDiaryWorker()

        // Watchdog check: If we crashed during last initialization, increment count
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_initializing", false)) {
            val newCount = prefs.getInt("init_crash_count", 0) + 1
            prefs.edit().putInt("init_crash_count", newCount).apply()
            Timber.e("Detected crash during last init! Crash count: $newCount")
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

            reportStatus("Init: NotificationChannel...")
            setupNotificationChannel()
            reportStatus("Init: ForegroundService...")
            startForegroundService()

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
            }
        } catch (e: Exception) {
            reportStatus("CRASH: ${e.message}")
            Timber.e(e, "Service Crash Detected")
            stopSelf()
        }
    }


    // === PERMISSIONS LOGIC ===

    private fun checkPermissions() {
        val hasSecureSettings = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasSecureSettings) {
            reportStatus("All sensors are now operational.")
        }
    }

    // === MEMORY MANAGEMENT ===

    fun resetMemory() {
        synchronized(this) {
            // Reset Koog agent if initialized
            if (::koogAgent.isInitialized) {
                try {
                    koogAgent.shutdown() // Use shutdown to clear history and checkpoint
                } catch (e: Exception) {
                    Timber.w(e, "Failed to clear Koog agent history")
                }
            }

            // Delete all checkpoint files
            try {
                val checkpointDir = getExternalFilesDir(null) ?: filesDir
                listOf(
                    "koog_agent_checkpoint.json",
                    "oracle_agent_checkpoint.json",  // Legacy
                    "koog_checkpoint.json",
                    "koog_diary.json"
                ).forEach { name ->
                    val file = java.io.File(checkpointDir, name)
                    if (file.exists()) file.delete()
                }

                Timber.i("\uD83E\uDDF9 Memory wiped: history cleared, checkpoints deleted")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete checkpoint files")
                throw e
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
        prefs.edit().putString(Constants.PREF_USER_BACKEND, backend).apply()

        serviceScope.launch {
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
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@GemmaService, "Engine online on $backend 🚀", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Compacts session conversation history into semantic memory and resets the KV cache.
     */
    fun flushSessionMemory() {
        serviceScope.launch {
            if (::koogAgent.isInitialized) {
                koogAgent.flushAndCompactSession()
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@GemmaService, "Session compacted & KV cache cleared ✨", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
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

        // WATCHDOG: Detect previous crash
        // NOTE: Counter is already incremented in onCreate(). Do NOT double-increment here.
        val crashCount = prefs.getInt("init_crash_count", 0)

        if (prefs.getBoolean("is_initializing", false)) {
            Timber.e("\uD83D\uDEA8 WATCHDOG: Previous initialization crashed! (Count: $crashCount)")
            
            // Audit 2.0: Be less aggressive (4 crashes instead of 2) to allow for memory pressure recovery
            if (crashCount >= 4) {
                 prefs.edit().putBoolean("force_cpu", true).apply()
                 updateNotification("Safe Mode: Forcing CPU")
            }
        }

        // PRE-INIT CLEANUP: Kill old engine to prevent memory leaks (95% RAM fix)
        // We do NOT call performCriticalCleanup() here because it kills sensors and TTS
        unloadEngine()

        // Mark as initializing
        prefs.edit().putBoolean("is_initializing", true).apply()

        try {
            updateNotification("Finding model...")

            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            // Search for any Gemma model variant (E2B preferred)
            val searchDirs = listOf(
                getExternalFilesDir(null),  // App storage (survives Downloads cleanup)
                downloadDir                  // Downloads folder
            )
            val modelFile = searchDirs.flatMap { dir ->
                dir?.listFiles { file ->
                    val name = file.name
                    (name.endsWith(".litertlm", ignoreCase = true) ||
                     name.endsWith(".gguf", ignoreCase = true) ||
                     name.endsWith(".nexa", ignoreCase = true)) &&
                    file.length() > 200 * 1024 * 1024L // Must be > 200MB to avoid partial/corrupted downloads
                }?.toList() ?: emptyList()
            }.sortedByDescending { file ->
                val name = file.name.lowercase()
                when {
                    name.contains("e2b") -> 80
                    name.endsWith(".litertlm") -> 50
                    else -> 0
                }
            }.firstOrNull()

            if (modelFile != null) {
                uiCallback?.onDownloadProgress(null)
                val variant = when {
                    modelFile.name.contains("E2B") -> "E2B (lite)"
                    else -> "unknown variant"
                }
                Timber.i("\uD83D\uDCE6 Found model: ${modelFile.name} ($variant) in ${modelFile.parent}")
            }

            if (modelFile == null) {
                val searchedPaths = searchDirs.mapNotNull { it?.absolutePath }
                Timber.e("No model found! Searched: $searchedPaths")
                updateNotification("Downloading E2B model...")
                
                modelDownloader.startDownload("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm")
                
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

            updateNotification("Running Full System Diagnostics...")

            // Determine backend: User override > Watchdog > Auto waterfall
            val userBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO")
            if (userBackend == "OFF") {
                Timber.i("🛑 User selected backend: OFF. Entering standalone mode (RAM freed).")
                unloadEngine()
                _isSystemReady.value = true
                prefs.edit().putBoolean("is_initializing", false).apply()
                updateNotification("GHOST Online (Engine OFF)")
                reportStatus("Standby: Engine OFF")
                return
            }

            val forcedBackend = if (userBackend != null && userBackend != "AUTO") {
                Timber.i("\uD83C\uDFAE User backend override: $userBackend")
                updateNotification("Loading on $userBackend (user selected)")
                userBackend
            } else if (prefs.getInt("init_crash_count", 0) >= 4) {
                Timber.w("\uD83D\uDEA8 Forcing CPU backend due to repeated crashes (threshold 4)")
                updateNotification("Safe Mode: Forcing CPU")
                "CPU"
            } else if (prefs.getBoolean("force_cpu", false)) {
                "CPU"
            } else null

            // Determine Tools
            val coreTools = listOf(hardwareToolSet, networkToolSet, systemToolSet, com.ghost.api.skills.SkillToolSet(skillManager))
            val uiTools = listOf(uiMacroToolSet)
            val termuxTools = listOf(termuxAdbToolSet, automationToolSet)

            // Engine Creation (Locked to prevent double allocation)
            val newEngine = engineMutex.withLock {
                // CRITICAL: Cleanup old engine BEFORE creating new one to free RAM
                engineRef.getAndSet(null)?.cleanup()
                
                // Hardening: Defragment memory before heavy engine allocation
                System.gc()
                Runtime.getRuntime().gc()
                
                val engineInstance = GemmaEngine(applicationContext)
                val error = engineInstance.initialize(
                    modelFile.absolutePath, 
                    "", 
                    toolSets = coreTools, 
                    forcedBackend = forcedBackend
                )
                
                // Hardening: Final defrag after initialization
                System.gc()
                
                if (error != null) {
                    engineInstance.cleanup()
                    val hint = when {
                        error.contains("memory", ignoreCase = true) || error.contains("OOM", ignoreCase = true) ->
                            " (Try quantizing device backend)"
                        error.contains("GPU", ignoreCase = true) ->
                            " (GPU init failed ⚠\uFE0F device may not support this model natively)"
                        else -> ""
                    }
                    Timber.e("Model load failed: $error")
                    updateNotification("Load Error: ${error.take(80)}$hint")
                    return@initialize
                }
                engineInstance
            }

            engineRef.set(newEngine)

            // Init Cognitive Layer (now that engine is ready)
            reportStatus("Init: KoogAgent...")
            if (::koogAgent.isInitialized) {
                try {
                    koogAgent.shutdown()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to shutdown old KoogAgent")
                }
            }
            koogAgent = KoogAgent(
                context = applicationContext,
                llmEngine = newEngine,
                sensorManager = sensorFusionManager,
                contextManager = contextManager,
                skillManager = skillManager,
                checkpointDir = getExternalFilesDir(null) ?: filesDir,
                coreTools = coreTools,
                uiTools = uiTools,
                termuxTools = termuxTools,
                callbacks = this@GemmaService
            )

            koogAgent.onConfirmationRequest = { event ->
                scope.launch(Dispatchers.Main) {
                    showConfirmationNotification(event)
                }
            }

            koogAgent.initialize()
            Timber.i("KoogAgent ready")

            // Success: Clear watchdog
            prefs.edit()
                .putBoolean("is_initializing", false)
                .putInt("init_crash_count", 0)
                .apply()

            updateNotification("System Ready - localhost:${Constants.API_PORT}")

        } catch (e: Exception) {
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
        if (!::koogAgent.isInitialized || !koogAgent.isReady) return
        
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

        if (!::koogAgent.isInitialized || !koogAgent.isReady) {
            uiCallback?.onMessageAdded("System is still initializing. Please wait a moment and try again.", isUser = false)
            return
        }

        // Let UI know we accepted it
        uiCallback?.onMessageAdded(query, isUser = true)
        uiCallback?.onThinkingStateChanged(true)

        serviceScope.launch {
            try {
                // Pass it through the core pipeline without triggering TTS audio unless explicitly asked
                val response = processQuery(query, null, false)

                withContext(Dispatchers.Main) {
                    uiCallback?.onThinkingStateChanged(false)
                    if (response == null) {
                        uiCallback?.onMessageAdded("Error: Request timed out or returned null.", isUser = false)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "UI processing failure")
                withContext(Dispatchers.Main) {
                    uiCallback?.onThinkingStateChanged(false)
                    uiCallback?.onMessageAdded("Error: ${e.message}", isUser = false)
                }
            }
        }
    }

    fun processMultimodalFromUi(query: String, images: List<android.graphics.Bitmap>? = null, audio: ByteArray? = null) {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val userBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (userBackend == "OFF") {
            uiCallback?.onMessageAdded(query, isUser = true)
            uiCallback?.onMessageAdded("Inference Engine is set to OFF in Settings. Select AUTO, CPU, or GPU to enable on-device chat.", isUser = false)
            return
        }
        if (!::koogAgent.isInitialized || !koogAgent.isReady) {
             uiCallback?.onMessageAdded("System is still initializing. Please wait.", isUser = false)
             return
        }
        images?.forEach { koogAgent.offerImage(it) }
        audio?.let { koogAgent.offerAudio(it) }
        processQueryFromUi(query)
    }

    suspend fun recordAudio(durationSeconds: Int): ByteArray? {
        return withContext(Dispatchers.IO) {
            if (::audioRecorder.isInitialized) {
                audioRecorder.record(durationSeconds, rawPcm = false)
            } else null
        }
    }

    /**
     * Core orchestrator: Context gathering + LLM reasoning + Tool execution
     */
    suspend fun processQuery(userPrompt: String, sessionId: String? = null, isDream: Boolean = false): String? = engineMutex.withLock {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val userBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (userBackend == "OFF") {
            val msg = "Inference Engine is set to OFF in Settings. Select AUTO, CPU, or GPU to enable on-device chat."
            if (!isDream) {
                responseNotificationManager.showResponse("⚠\uFE0F $msg")
            }
            return@withLock msg
        }

        if (!::koogAgent.isInitialized || !koogAgent.isReady) {
            responseNotificationManager.showResponse("⚠\uFE0F System still starting up... try again in a moment")
            return@withLock "System is still initializing. Please wait a moment and try again."
        }

        if (!isDream) {
            markActivity()
            if (::ttsManager.isInitialized) {
                ttsManager.stop()
            }
        }

        isInferencing = true
        try {
            return kotlinx.coroutines.withTimeoutOrNull(240000) {
                val response = koogAgent.processUserMessage(
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
        }
    }

    /**
     * Streaming entry point for the OpenAI SSE endpoint.
     * Feeds tokens to [onToken] as they arrive from the engine.
     * Used by ApiServer /v1/chat/completions when stream=true.
     */
    suspend fun streamQueryTokens(prompt: String, onToken: (String) -> Unit) = engineMutex.withLock {
        if (!::koogAgent.isInitialized || !koogAgent.isReady) {
            onToken("System is still initializing.")
            return@withLock
        }
        markActivity()
        // Register a temporary token observer, then run inference
        koogAgent.streamUserMessageTokens(
            message = prompt,
            sessionId = java.util.UUID.randomUUID().toString(),
            onToken = onToken
        )
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

                val now = System.currentTimeMillis()
                // Audit 3.0: Reduced refresh rate to 5s to slash IPC overhead
                val delayMs = if (isInteractive) 5000L else 30000L 
                
                if (now - lastActivityTime > 15 * 60 * 1000 && now - lastKvFlushTime > 15 * 60 * 1000) {
                    lastKvFlushTime = now
                    if (::koogAgent.isInitialized) {
                        Timber.d("GemmaService: Triggering 15-min inactivity KV Cache Flush")
                        koogAgent.sendSystemEvent(KoogAgent.SystemEventType.KV_CACHE_FLUSH)
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
        if (::koogAgent.isInitialized) {
            try {
                serviceScope.launch {
                    try {
                        kotlinx.coroutines.withTimeout(2000L) {
                            koogAgent.checkpoint()
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
            if (::koogAgent.isInitialized) koogAgent.shutdown()
            
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


    // Tool execution lives in KoogAgent.act() → MCPServer.executeTool()

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

    private fun showConfirmationNotification(event: KoogAgent.AgentEvent.ConfirmationRequired) {
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
        responseNotificationManager.showThinking()
    }

    override fun cancelThinking() {
        responseNotificationManager.cancelThinking()
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

    override fun storeConversationTurn(userMessage: String, response: String, sessionId: String) {
        scope.launch(Dispatchers.IO) {
            memoryManager.storeTurn(com.ghost.api.database.ConversationTurn(
                timestamp = System.currentTimeMillis(),
                userMessage = userMessage,
                assistantResponse = response,
                tokensUsed = 0,
                sessionId = sessionId
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
                if (::koogAgent.isInitialized) {
                    koogAgent.offerAudio(audio)
                }
                Timber.w("AUDIO_DEBUG: Queued ${audio.size} bytes of WAV audio")

                val sessionId = java.util.UUID.randomUUID().toString()
                // Tell the model to listen to the attached audio
                processQuery("[Operator sent voice message - listen and respond to the audio]", sessionId)
            }
        }
    }

    fun showPipContent(title: String, htmlContent: String, durationMs: Long = 5000) {
        if (::overlayManager.isInitialized) {
            overlayManager.showPipContent(title, htmlContent, durationMs)
        }
    }

    fun showPipUrl(title: String, url: String, durationMs: Long = 10000) {
        if (::overlayManager.isInitialized) {
            overlayManager.showPipUrl(title, url, durationMs)
        }
    }

    fun cleanupLegacyAlarms() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
            val legacyActions = listOf(
                "com.ghost.api.ACTION_DIARY_CYCLE",
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

    private fun setupDiaryWorker() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, true)) {
            com.ghost.api.workers.DiaryWorker.schedule(this)
            Timber.i("🟢 Autonomous logging routine scheduled via DiaryWorker.schedule()")
        }
    }

    suspend fun runDiaryCycleSuspend(): Boolean {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val userBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO")
        if (userBackend == "OFF" || !::koogAgent.isInitialized || !koogAgent.isReady) {
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

        val compactedMemory = try { memoryManager.getCompactedSessionMemory() } catch (e: Exception) { "" }
        val recentTurns = try { memoryManager.getSessionHistory(10) } catch (e: Exception) { emptyList() }
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
            recentTurns.reversed().takeLast(6).joinToString("\n") {
                "User: ${it.userMessage.take(120)}\nGemma: ${it.assistantResponse.take(120)}"
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

        val prompt = """The current date and time is $currentDateTime ($label cycle).
            |
            |[AMBIENT SUBSTRATE & INTERACTION TELEMETRY]
            |- Substrate State: Battery at ${if (batteryLevel >= 0) "$batteryLevel% ($chargeStatus)" else "Unknown"}, Thermals: $thermal
            |- Media / Ambient Sound: $mediaTelemetry
            |- Interaction Telemetry: $interactionSummary
            |- Long-term Fact Memory: $compactedMemory
            |- Recent Conversation Turns:
            |$historyText
            |
            |[AUTONOMOUS MEMORY LOG DIRECTIVE]
            |Write a brief personal MEMORY LOG in the first person as ✧ Gemma (2-5 sentences).
            |- If there were active interactions, reason about your observations, your genuine assessment of the ideas, and key takeaways.
            |- If it was a quiet period without user interactions, reflect naturally on your current state, background media, what you are tracking, coincidences, or observations from ambient telemetry.
            |- Tone is free to be fitting of system state observations.""".trimMargin()

        Timber.i("📔 Diary cycle ($label) — generating via KoogAgent...")
        return try {
            val diaryResponse = processQuery(prompt, isDream = true)

            if (diaryResponse != null && diaryResponse.isNotBlank() && !diaryResponse.startsWith("Error:")) {
                val cleanContent = diaryResponse.trim()
                val intent = Intent("com.ghost.api.ACTION_DIARY_ENTRY_POSTED").apply {
                    putExtra("content", cleanContent)
                    setPackage(packageName)
                }
                sendBroadcast(intent)

                val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putLong("last_diary_execution_time", System.currentTimeMillis()).apply()

                Timber.i("📔 Diary entry persisted: ${diaryResponse.take(80)}...")
                true
            } else {
                Timber.w("📔 Diary generation returned empty/error: $diaryResponse")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "📔 Diary cycle failed")
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

