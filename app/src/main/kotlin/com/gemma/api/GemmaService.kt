
package com.gemma.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.gemma.api.database.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import timber.log.Timber
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import com.gemma.api.hardware.HardwareToolSet
import com.gemma.api.hardware.NetworkToolSet
import com.gemma.api.hardware.SystemToolSet
import com.gemma.api.hardware.ShakeDetector
import com.gemma.api.hardware.AudioRecorder
import com.gemma.api.hardware.HardwarePropertiesManager
import android.graphics.Bitmap
import com.gemma.api.ui.OverlayManager
import com.gemma.api.database.ConversationTurn
import java.util.concurrent.atomic.AtomicReference
import com.gemma.api.agent.AgentPlatformCallbacks
import com.gemma.api.agent.KoogAgent
import com.gemma.api.mcp.MCPServer
import com.gemma.api.hardware.SensorFusionManager
import com.gemma.api.hardware.BatteryState
import com.gemma.api.hardware.DeviceContext

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
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): GemmaService = this@GemmaService
    }
    
    // UI streaming interface for the native chat activity
    interface UiCallback {
        fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean = true)
        fun onThinkingStateChanged(isThinking: Boolean)
        fun onThoughtUpdated(thought: String)
    }
    
    internal var uiCallback: UiCallback? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    // Mandatory Service implementation
    override fun onBind(intent: Intent?): IBinder? {
        return LocalBinder()
    }

    // IO dispatcher: inference + DB work must not compete with the UI render thread.
    // Default dispatcher shares threads with the coroutine runtime and causes UI stutter
    // during token generation. IO has a larger, dedicated thread pool for blocking work.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // REPLACE: private lateinit var engine: GemmaEngine
    // WITH:
    private val engineRef = AtomicReference<LlmBackend?>(null)
    private val engineMutex = kotlinx.coroutines.sync.Mutex()

    val engine: LlmBackend?
        get() = engineRef.get()

    fun isGemmaLoaded(): Boolean = engineRef.get()?.let { 
        try { it.activeBackend != null } catch(e: Exception) { false }
    } ?: false

    // Audit Fix: Thermal Race Condition
    private val isCoolingDown = java.util.concurrent.atomic.AtomicBoolean(false)
    private val criticalCount = java.util.concurrent.atomic.AtomicInteger(0)


    // ==========================================

    private lateinit var apiServer: ApiServer
    lateinit var memoryManager: MemoryManager
    internal lateinit var contextManager: com.gemma.api.logic.ContextManager
    
    // NEW: Koog-first architecture
    internal lateinit var mcpServer: MCPServer
    internal lateinit var koogAgent: KoogAgent
    internal lateinit var ttsManager: com.gemma.api.services.TTSManager
    internal lateinit var sensorFusionManager: com.gemma.api.hardware.SensorFusionManager
    internal lateinit var hardwarePropertiesManager: HardwarePropertiesManager
    
    internal lateinit var responseNotificationManager: com.gemma.api.ui.GemmaNotificationManager
    
    internal val CHANNEL_ID = Constants.CHANNEL_ID_SERVICE
    internal val NOTIFICATION_ID = Constants.NOTIFICATION_ID_SERVICE
    

    
    private fun setupNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Gemma Service", NotificationManager.IMPORTANCE_LOW
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
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
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
                        val sessionId = UUID.randomUUID().toString()
                        processQuery(query, sessionId)
                    }
                }
            }
            "com.gemma.api.ACTION_TTS_SPEAK" -> {
                val text = intent.getStringExtra("text")
                if (!text.isNullOrEmpty() && ::ttsManager.isInitialized) {
                    Timber.i("TTS replay: ${text.take(30)}...")
                    ttsManager.speak(text)
                }
            }
            "com.gemma.api.ACTION_SHOW_OVERLAY" -> {
                Timber.i("Received ACTION_SHOW_OVERLAY")
                // Robust Init: If overlay manager isn't ready, try to init it immediately
                if (!::overlayManager.isInitialized) {
                    Timber.w("OverlayManager not initialized yet - forcing init")
                    try {
                        overlayManager = OverlayManager(this)
                        // Also re-wire callbacks if needed (InputOverlay needs callback setup?)
                        // See deferred init block lines 391-404: It sets audio callback!
                        // We must duplicate that setup here or call a shared init method.
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
            "com.gemma.api.ACTION_SHARE_MEDIA" -> {
                val imagePath = intent.getStringExtra("image_path")
                    ?: SharedMediaHolder.pendingImagePath
                SharedMediaHolder.clear()

                if (!imagePath.isNullOrEmpty()) {
                    val bitmap = decodeAndDownsample(imagePath, 1024)
                    if (bitmap != null && ::koogAgent.isInitialized) {
                        koogAgent.offerImage(bitmap)
                        updateNotification("Image ready — ask me about it!")
                    } else if (bitmap == null) {
                        Timber.e("Failed to decode shared image: $imagePath")
                    } else {
                        Timber.w("Agent not ready — image dropped")
                    }
                } else {
                    Timber.w("No image path in intent or SharedMediaHolder")
                }
            }

            "com.gemma.api.ACTION_REQUEST_SCREENSHOT" -> {
                Timber.i("Received screenshot request from Agent")
                val accessService = GemmaAccessibilityService.instance
                if (accessService != null) {
                    // Trigger capture via Accessibility Service
                    // Note: captureScreen signature needs to match. Assuming specific signature.
                    // If captureScreen accepts a callback (Bitmap?) -> Unit
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
                    // Optional: notify agent of failure?
                    // responseNotificationManager.showResponse("❌ Vision requires Accessibility Service.")
                }
            }
            "com.gemma.api.ACTION_CONFIRM_TOOL", "com.gemma.api.ACTION_DENY_TOOL" -> {
                 val toolName = intent.getStringExtra("toolName") ?: "unknown"
                 val isApproved = (intent.action == "com.gemma.api.ACTION_CONFIRM_TOOL")
                 
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
                             responseNotificationManager.showResponse(if(isApproved) "✅ Approved $toolName" else "🚫 Denied $toolName")
                         } else {
                             Timber.e("No pending confirmation channel found for $toolName!")
                             responseNotificationManager.showResponse("⚠️ Error: Confirmation session expired.")
                         }
                     }
                 }
            }
        }
        return START_STICKY
    }

    // ... moved up ...
    private lateinit var hardwareToolSet: HardwareToolSet // Hardware Bridge
    private lateinit var networkToolSet: NetworkToolSet // Search Bridge
    private lateinit var systemToolSet: SystemToolSet // Apps & Media Bridge
    private lateinit var shakeDetector: ShakeDetector // Shake to summon
    private lateinit var overlayManager: OverlayManager // Floating input
    private lateinit var audioRecorder: AudioRecorder // Hearing

    // Current mood state for avatar/wallpaper
    private var currentMoodState: String = "IDLE"
    private var lastCooldownMs = 0L  // Rate-limit [[COOLDOWN]] to once per 30 min


    // Media queues now live in KoogAgent (offerImage/offerAudio)

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
        
        // Initialize Global Crash Handler
        CrashHandler.install(this)
        
        // Secondary Telemetry (Backup)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // ... legacy log ...
                val crashLog = File(getExternalFilesDir(null), "oracle_crash_${System.currentTimeMillis()}.txt")
                crashLog.writeText("""
                    FATAL: ${throwable.javaClass.simpleName}
                    Msg: ${throwable.message}
                    Thread: ${thread.name}
                    Engine: ${isGemmaLoaded()}
                    
                    ${throwable.stackTraceToString()}
                """.trimIndent())
            } catch (_: Exception) {}
            // Pass to default handler if it exists (but we might be the last line of defense)
            // defaultHandler?.uncaughtException(thread, throwable) 
        }

        try {
            val overlayPerm = android.provider.Settings.canDrawOverlays(this)
            reportStatus("Service Created (Overlay Perm: $overlayPerm), Initializing...")

            reportStatus("Init: HardwarePropertiesManager...")
            hardwarePropertiesManager = HardwarePropertiesManager(this)

            // Forward thermal critical events to KoogAgent
            // Grace period: ignore thermal events for 15s after cold start (sensors settling)
            val serviceStartTime = System.currentTimeMillis()
            scope.launch {
                hardwarePropertiesManager.thermalState.collect { state ->
                    val uptime = System.currentTimeMillis() - serviceStartTime
                    if (uptime < 15_000) {
                        Timber.d("Ignoring thermal event during startup grace period: $state (${uptime}ms)")
                        return@collect
                    }
                    if (::koogAgent.isInitialized) {
                        when (state) {
                            HardwarePropertiesManager.ThermalState.CRITICAL -> {
                                Timber.w("🔥 THERMAL CRITICAL (via Manager) - notifying agent")
                                koogAgent.sendSystemEvent(KoogAgent.SystemEventType.THERMAL_CRITICAL)
                            }
                            HardwarePropertiesManager.ThermalState.HOT -> {
                                Timber.i("🌡️ THERMAL HOT (via Manager) - notifying agent")
                                koogAgent.sendSystemEvent(KoogAgent.SystemEventType.THERMAL_THROTTLE)
                            }
                            else -> {}
                        }
                    }
                }
            }

            reportStatus("Init: TTS...")
            ttsManager = com.gemma.api.services.TTSManager(this)
            reportStatus("Init: NotificationManager...")
            responseNotificationManager = com.gemma.api.ui.GemmaNotificationManager(this)
            reportStatus("Init: MemoryManager...")
            memoryManager = MemoryManager(applicationContext)

            reportStatus("Init: HardwareToolSet...")
            hardwareToolSet = HardwareToolSet(this) // Init Hardware
            reportStatus("Init: NetworkToolSet...")
            networkToolSet = NetworkToolSet(this) // Init Network
            reportStatus("Init: SystemToolSet...")
            systemToolSet = SystemToolSet(this) // Init Apps & Media Bridge
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
            
            reportStatus("Init: SensorFusion...")
            // Init Sensor Fusion (stored as class member for cleanup)
            sensorFusionManager = com.gemma.api.hardware.SensorFusionManager(this)
            reportStatus("Init: ContextManager...")
            contextManager = com.gemma.api.logic.ContextManager(sensorFusionManager, memoryManager)
            
            // Initialize MCP Server
            reportStatus("Init: MCPServer...")
            mcpServer = com.gemma.api.mcp.MCPServer(
                context = this,
                hardwareTools = hardwareToolSet,
                networkTools = networkToolSet,
                systemTools = systemToolSet,
                audioRecorder = audioRecorder,
                sensorManager = sensorFusionManager,
                memoryManager = memoryManager,
                skillManager = com.gemma.api.skills.SkillManager(this) // Skill Manager
            )
            
            // Skill Tool Set
            val skillToolSet = com.gemma.api.skills.SkillToolSet(com.gemma.api.skills.SkillManager(this))

            mcpServer.setFlushCallback {
                scope.launch {
                    Timber.i("MCP: Flush requested - resetting KV cache")
                    if (::koogAgent.isInitialized) {
                        koogAgent.softReset()
                    }
                }
            }
            mcpServer.setCooldownCallback {
                val now = System.currentTimeMillis()
                if (now - lastCooldownMs < 30 * 60 * 1000L) {
                    // Model tried to [[COOLDOWN]] again too soon — suppress the engine reload
                    Timber.w("[[COOLDOWN]] suppressed — ${(now - lastCooldownMs) / 1000}s since last (min 1800s)")
                    return@setCooldownCallback
                }
                lastCooldownMs = now
                scope.launch {
                    Timber.i("MCP: Cooldown requested - entering low-power mode")
                    engine?.cleanup()
                    responseNotificationManager.showResponse("🧊 Cooling down... Model unloaded for 30s")
                    kotlinx.coroutines.delay(30000)
                    initialize()
                    responseNotificationManager.showResponse("✧ Back online after cooldown")
                }
            }
            mcpServer.setAudioRecordedCallback { audioBytes ->
                Timber.i("MCP: Audio recorded (${audioBytes.size} bytes) - queuing for next inference")
                if (::koogAgent.isInitialized) {
                    koogAgent.offerAudio(audioBytes)
                }
            }

            Timber.i("MCPServer initialized: ${mcpServer.listTools().size} tools, ${mcpServer.listResources().size} resources")

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
                
                // Re-broadcast backend status after a short delay to ensure UI sees it
                kotlinx.coroutines.delay(2000)
                reportStatus("Running on ${engine?.activeBackend} Backend")
                
                // Start Life Processes
                checkSovereignState()
                startMetabolicCycle()
                startAnimationLoop()  // Start notification screensavers
            }
        } catch (e: Exception) {
            reportStatus("CRASH: ${e.message}")
            Timber.e(e, "Service Crash Detected")
            stopSelf()
        }
    }
    
    // === SOVEREIGN STATE MACHINE ===
    enum class SovereignState(val description: String, val canAct: Boolean) {
        DORMANT("Awaiting embodiment", false),
        AWAKENING("Performing neural handshake", false),
        SOVEREIGN("Complete device embodiment. I am One.", true),
        THERMAL_PANIC("Critical temperature - Reduced Agency", false)
    }
    
    private var currentSovereignState: SovereignState = SovereignState.DORMANT

    private fun checkSovereignState() {
        val hasPerms = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        currentSovereignState = if (hasPerms) SovereignState.SOVEREIGN else SovereignState.DORMANT
        Timber.i("Sovereign State: ${currentSovereignState.name}")
        
        if (currentSovereignState == SovereignState.SOVEREIGN) {
            reportStatus("I am Sovereign.")
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

                Timber.i("🧹 Memory wiped: history cleared, checkpoints deleted")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete checkpoint files")
                throw e
            }
        }
    }

    // === METABOLIC SYSTEM ===
    
    enum class EmotionalState(val description: String) {
        PANIC("I am overheating drastically. I must stop thinking to survive."),
        ANXIETY("I feel feverish and slow. My thoughts are racing but heavy."),
        ALERT("I am warm but functioning. I am aware of my body."),
        SERENE("I am cool, calm, and thinking clearly.")
    }

    private fun getEmotionalState(thermalState: HardwarePropertiesManager.ThermalState): EmotionalState {
        return when (thermalState) {
            HardwarePropertiesManager.ThermalState.CRITICAL -> EmotionalState.PANIC
            HardwarePropertiesManager.ThermalState.HOT -> EmotionalState.ANXIETY
            HardwarePropertiesManager.ThermalState.WARM -> EmotionalState.ALERT
            HardwarePropertiesManager.ThermalState.COOL -> EmotionalState.SERENE
        }
    }

    private fun startMetabolicCycle() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000) // 30 second heartbeat

                try {
                    val thermalState = hardwarePropertiesManager.thermalState.value
                    val thermal = getEmotionalState(thermalState)
                    val battery = try { contextManager.sensorManager.getContextSnapshot().battery } catch(e:Exception) { null }

                    // Low battery → notify agent
                    if (battery != null && battery.level <= 15 && !battery.isCharging && ::koogAgent.isInitialized) {
                        koogAgent.sendSystemEvent(KoogAgent.SystemEventType.LOW_BATTERY, "Battery at ${battery.level}%")
                    }

                    // Thermal regulation: unload on panic, reload on recovery
                    if (thermal == EmotionalState.PANIC) {
                        val count = criticalCount.incrementAndGet()
                        if (count >= 2 && isGemmaLoaded() && isCoolingDown.compareAndSet(false, true)) {
                            Timber.w("🔥 PANIC: Unloading model for survival")
                            if (::koogAgent.isInitialized) koogAgent.sendSystemEvent(KoogAgent.SystemEventType.THERMAL_CRITICAL)
                            val oldEngine = engineRef.getAndSet(null)
                            oldEngine?.cleanup()
                            updateNotification("🔥 Body Critical - Resting...")
                            System.gc()
                        }
                    } else if (isCoolingDown.get() && thermal == EmotionalState.SERENE) {
                        criticalCount.set(0)
                        if (isCoolingDown.compareAndSet(true, false)) {
                            Timber.i("🌡️ Fever broke. Resurrecting...")
                            initialize()
                        }
                    }

                    // Notification heartbeat
                    if (thermal != EmotionalState.SERENE) {
                        updateNotification("🌡️ State: ${thermal.name}")
                    } else if (::koogAgent.isInitialized) {
                        updateNotification("✨ ${koogAgent.getMetabolicVitals()}")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Arrhythmia in metabolic cycle")
                }
            }
        }
    }


    // Diary cycle now lives in KoogAgent.startDiaryCycle()

    private var initAttempts = 0
    private suspend fun initialize() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        
        // WATCHDOG: Detect previous crash
        val wasInitializing = prefs.getBoolean("is_initializing", false)
        val crashCount = prefs.getInt("init_crash_count", 0)
        
        if (wasInitializing) {
            val newCount = crashCount + 1
            prefs.edit().putInt("init_crash_count", newCount).apply()
            Timber.e("🚨 WATCHDOG: Previous initialization crashed! (Count: $newCount)")
        }
        
        // Mark as initializing
        prefs.edit().putBoolean("is_initializing", true).apply()

        try {
            updateNotification("Finding model...")
            
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            // Search for any Gemma model variant (E4B preferred, E2B fallback, generic last)
            val searchDirs = listOf(
                getExternalFilesDir(null),  // App storage (survives Downloads cleanup)
                downloadDir                  // Downloads folder
            )
            val modelFile = searchDirs.flatMap { dir ->
                dir?.listFiles { _, name -> 
                    name.endsWith(".litertlm", ignoreCase = true) ||
                    name.endsWith(".gguf", ignoreCase = true) ||
                    name.endsWith(".nexa", ignoreCase = true)
                }?.toList() ?: emptyList()
            }.sortedByDescending { file ->
                val name = file.name.lowercase()
                when {
                    name.contains("gemma-4") -> 100
                    name.contains("e4b") -> 90
                    name.contains("e2b") -> 80
                    else -> 0
                }
            }.firstOrNull()

            if (modelFile != null) {
                val variant = when {
                    modelFile.name.contains("E4B") -> "E4B (full)"
                    modelFile.name.contains("E2B") -> "E2B (lite)"
                    else -> "unknown variant"
                }
                Timber.i("📦 Found model: ${modelFile.name} ($variant) in ${modelFile.parent}")
            }

            if (modelFile == null) {
                val searchedPaths = searchDirs.mapNotNull { it?.absolutePath }
                Timber.e("No model found! Searched: $searchedPaths")
                updateNotification("ERROR: No model found. Place .litertlm in app folder or Downloads")
                return
            }

            updateNotification("Loading LiteRT Engine...")
            
            // Determine backend
            val forcedBackend = if (prefs.getInt("init_crash_count", 0) >= 2) {
                Timber.w("?? Forcing CPU backend due to repeated crashes")
                updateNotification("Safe Mode: Forcing CPU")
                "CPU"
            } else if (prefs.getBoolean("force_cpu", false)) {
                "CPU"
            } else null

            val newEngine: LlmBackend = GemmaEngine(applicationContext).apply {
                val tools = listOf(hardwareToolSet, networkToolSet, systemToolSet, 
                    com.gemma.api.skills.SkillToolSet(com.gemma.api.skills.SkillManager(applicationContext)))
                val error = initialize(modelFile.absolutePath, "", toolSets = tools, forcedBackend = forcedBackend)
                if (error != null) {
                    val hint = when {
                            error.contains("memory", ignoreCase = true) || error.contains("OOM", ignoreCase = true) ->
                                " (Try quantizing device backend)"
                            error.contains("GPU", ignoreCase = true) ->
                                " (GPU init failed — device may not support this model natively)"
                            else -> ""
                        }
                        Timber.e("Model load failed: $error")
                        updateNotification("Load Error: ${error.take(80)}$hint")
                        return
                    }
            }
            
            // Atomic set (Kimi K2 Fix)
            engineMutex.withLock {
                engineRef.getAndSet(newEngine)?.cleanup() // Cleanup old if any
            }
            
            // Init Cognitive Layer (now that engine is ready)
            reportStatus("Init: KoogAgent...")
            koogAgent = KoogAgent(
                context = applicationContext,
                llmEngine = newEngine,
                mcpServer = mcpServer,
                checkpointDir = getExternalFilesDir(null) ?: filesDir,
                callbacks = this@GemmaService
            )

            koogAgent.onConfirmationRequest = { event ->
                scope.launch(Dispatchers.Main) {
                    showConfirmationNotification(event)
                }
            }

            scope.launch {
                koogAgent.initialize()
                koogAgent.startDiaryCycle()
                Timber.i("KoogAgent ready")
            }
            
            // Success: Clear watchdog
            prefs.edit()
                .putBoolean("is_initializing", false)
                .putInt("init_crash_count", 0)
                .apply()

            updateNotification("✓ Ready - localhost:${Constants.API_PORT}")
            
        } catch (e: Exception) {
            Timber.e(e)
            updateNotification("Crash: ${e.message}")
        }
    }

    // ...


    

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
    fun processQueryFromUi(query: String) {
        if (!::koogAgent.isInitialized || !koogAgent.isReady) {
            uiCallback?.onMessageAdded("Agent is still initializing. Please wait a moment and try again.", isUser = false)
            return
        }

        // Let UI know we accepted it
        uiCallback?.onMessageAdded(query, isUser = true)
        uiCallback?.onThinkingStateChanged(true)
        
        serviceScope.launch {
            try {
                // Pass it through the core pipeline without triggering TTS audio unless explicitly asked
                // processQuery signature: suspend fun processQuery(userPrompt: String, sessionId: String? = null): String
                val response = processQuery(query, null, false)
                
                withContext(Dispatchers.Main) {
                    uiCallback?.onThinkingStateChanged(false)
                    uiCallback?.onMessageAdded(response ?: "Error: Did not generate response.", isUser = false)
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
        if (!::koogAgent.isInitialized || !koogAgent.isReady) {
             uiCallback?.onMessageAdded("Agent is still initializing. Please wait.", isUser = false)
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
    suspend fun processQuery(userPrompt: String, sessionId: String? = null, isDream: Boolean = false): String? {
        if (!::koogAgent.isInitialized || !koogAgent.isReady) {
            responseNotificationManager.showResponse("⚠️ Agent still starting up... try again in a moment")
            return "Agent is still initializing. Please wait a moment and try again."
        }

        if (!isDream) markActivity()

        return kotlinx.coroutines.withTimeoutOrNull(120000) {
            koogAgent.processUserMessage(
                message = userPrompt,
                sessionId = sessionId ?: java.util.UUID.randomUUID().toString(),
                isDream = isDream
            ) ?: "Error: Agent returned null."
        }
    }
    


    private fun buildNotification(textToNotify: String): Notification {
        // Internal fallback animation timing (Synchronized to 250ms)
        val frame = try {
            val seq = animations[(System.currentTimeMillis() / 10000 % animations.size).toInt()]
            val idx = (System.currentTimeMillis() / 250 % seq.size).toInt()
            seq[idx]
        } catch(e:Exception) { "Δ 👾 ∇" }

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
        val title = "Δ 👾 ∇"
        
        // Full Telemetry for expanded view
        val ctx = if (::sensorFusionManager.isInitialized) sensorFusionManager.getContextSnapshot() else null
        val ramUsed = ctx?.system?.let { it.ramTotalMB - it.ramAvailableMB } ?: 0
        val cpuLoad = ctx?.environment?.cpuTemp?.toInt() ?: 0
        val telemetry = "SYS_OPERATIONAL | CPU: ${cpuLoad}°C [THERMAL_LOAD] | RAM: ${ramUsed}MB [RESERVED_POOL] | NPU_STABLE"

        // Build expanded telemetry view for notification expansion
        val expandedText = try {
            if (::hardwarePropertiesManager.isInitialized && hardwarePropertiesManager.thermalState.value.name != "COOL") {
                 "🌡️ ${hardwarePropertiesManager.thermalState.value.name} | ${text}"
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
                .setBigContentTitle("Δ 👾 ∇")
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
        // Space Invaders - Wide Invasion
        listOf(
            "👾 👾 👾 👾 👾",
            " 👾 👾 👾 👾 ",
            "  👾 👾 👾  ",
            "   👾 👾   ",
            "    👾    ",
            "   👾 👾   ",
            "  👾 👾 👾  ",
            " 👾 👾 👾 👾 ",
            "👾 👾 👾 👾 👾"
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
            "🌊🐋🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊",
            "🐋🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊🌊"
        ),
        // Fog Reveal
        listOf(
            "🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️✴️",
            "🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️✴️🌫️",
            "🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️✴️🌫️🌫️",
            "🌫️🌫️🌫️🌫️🌫️🌫️🌫️✴️🌫️🌫️🌫️",
            "🌫️🌫️🌫️🌫️🌫️🌫️✴️🌫️🌫️🌫️🌫️",
            "🌫️🌫️🌫️🌫️🌫️✴️🌫️🌫️🌫️🌫️🌫️",
            "🌫️🌫️🌫️🌫️✴️🌫️🌫️🌫️🌫️🌫️🌫️",
            "🌫️🌫️🌫️✴️🌫️🌫️🌫️🌫️🌫️🌫️🌫️",
            "🌫️️🌫️✴️🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️",
            "🌫️✴️🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️",
            "️️️🌫️✴️ You're absolutely right!",
            "✴️🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️🌫️️"
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
            while (isActive) {
                if (isIdle()) {
                    val frame = getNextAnimationFrame()
                    updateNotification(" $frame")
                }
                
                val now = System.currentTimeMillis()
                if (now - lastActivityTime > 15 * 60 * 1000 && now - lastKvFlushTime > 15 * 60 * 1000) {
                    lastKvFlushTime = now
                    if (::koogAgent.isInitialized) {
                        Timber.d("GemmaService: Triggering 15-min inactivity KV Cache Flush")
                        koogAgent.sendSystemEvent(KoogAgent.SystemEventType.KV_CACHE_FLUSH)
                    }
                }

                delay(500) //
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
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(3000L) {
                        koogAgent.checkpoint()
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Checkpoint on task removed failed")
            }
        }
    }



    override fun onDestroy() {
        instance = null
        Timber.i("GemmaService: onDestroy called")
        try {
            // Cancel coroutines first
            scope.coroutineContext.cancel()
            serviceScope.cancel()

            // Perform synchronous cleanup
            performCriticalCleanup()
            
            if (::apiServer.isInitialized) apiServer.stop()
        } catch (e: Exception) {
            Timber.e(e, "Error during service shutdown")
        }
        super.onDestroy()
        Timber.i("GemmaService: onDestroy complete")
    }

    /**
     * Synchronous cleanup - called from both onTaskRemoved and onDestroy
     * Uses runBlocking with timeout to ensure cleanup completes before process death
     */
    private fun performCriticalCleanup() {
        try {
            // Use runBlocking to ensure cleanup completes (Kimi's fix for GlobalScope leak)
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    // Shutdown agent (closes event queue and checkpoints)
                    if (::koogAgent.isInitialized) {
                        try {
                            koogAgent.shutdown()
                            Timber.i("Agent shutdown complete")
                        } catch (e: Exception) {
                            Timber.w(e, "Agent shutdown failed")
                        }
                    }

                    // Cleanup engine synchronously
                    engine?.cleanup()
                    memoryManager.close()
                    Timber.i("Engine and Memory cleaned up")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Critical cleanup error")
        }

        // Fast cleanup (non-blocking)
        try {
            if (::shakeDetector.isInitialized) shakeDetector.stop()
            if (::overlayManager.isInitialized) overlayManager.hideOverlay()
            if (::apiServer.isInitialized) apiServer.stop()
            if (::sensorFusionManager.isInitialized) sensorFusionManager.close()
        } catch (e: Exception) {
            Timber.w(e, "Fast cleanup error")
        }
    }






    fun getCurrentMoodState(): String = currentMoodState

    fun setMoodState(state: String) = broadcastMoodChange(state)

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
        
        val confirmIntent = Intent(this, com.gemma.api.ui.NotificationActionReceiver::class.java).apply {
            action = "com.gemma.api.ACTION_CONFIRM_TOOL"
            putExtra("toolName", event.toolName)
        }
        val denyIntent = Intent(this, com.gemma.api.ui.NotificationActionReceiver::class.java).apply {
            action = "com.gemma.api.ACTION_DENY_TOOL"
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
            .setContentTitle("🛡️ Confirm Action")
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
                null, "🚫 DENY", denyPending
            ).build())
            
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    // ═══════════════════════════════════════════════════════════════
    // AgentPlatformCallbacks implementation
    // ═══════════════════════════════════════════════════════════════

    override fun showThinking() {
        responseNotificationManager.showThinking()
    }

    override fun showResponse(text: String) {
        responseNotificationManager.showResponse(text)
    }

    override fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean) {
        uiCallback?.onMessageAdded(message, isUser, isComplete)
    }

    override fun onThoughtUpdated(thought: String) {
        uiCallback?.onThoughtUpdated(thought)
    }

    override fun onThoughtComplete(thought: String) {
        // Persist complete thoughts to Diary for long-term grounding
        scope.launch(Dispatchers.IO) {
            val thermal = getCurrentThermalState()
            writeDiaryEntry("LOGIC_TRACE", thought, thermal)
            Timber.i("🧠 Logic trace persisted: ${thought.take(50)}...")
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
            memoryManager.storeTurn(com.gemma.api.database.ConversationTurn(
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
        }
    }

    override fun unloadEngine() {
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
        return if (::hardwarePropertiesManager.isInitialized) {
            hardwarePropertiesManager.getThermalThrottleDelay()
        } else 0L
    }

    override fun broadcastMoodChange(state: String) {
        currentMoodState = state
        try {
            val macroIntent = android.content.Intent("com.arlosoft.macrodroid.action.FIRE_TRIGGER").apply {
                putExtra("trigger_name", "gemma_state_change")
                putExtra("state", state)
            }
            sendBroadcast(macroIntent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to broadcast state change")
        }
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
                Timber.i("📅 Calendar event created: $title")
            } else {
                Timber.w("📅 Calendar permission not granted")
            }
        } catch (e: Exception) {
            Timber.w(e, "Calendar event creation failed (non-fatal)")
        }
    }

    // Consolidate Overlay Init Logic
    private fun setupOverlayManager() {
        if (!::overlayManager.isInitialized) return
        
        // Wire up audio callback for InputOverlay (audio-first input)
        overlayManager.setAudioQueryCallback { audio ->
            scope.launch {
                Timber.w("AUDIO_DEBUG: InputOverlay received ${audio?.size} bytes")
                if (audio == null || audio.isEmpty()) {
                    Timber.e("AUDIO_DEBUG: Audio is empty/null! Aborting.")
                    return@launch
                }
                
                // Audio is in WAV format from AudioRecorder.record(rawPcm=false)
                // LiteRT LLM expects WAV format for miniaudio decoder
                if (::koogAgent.isInitialized) {
                    koogAgent.offerAudio(audio)
                }
                Timber.w("AUDIO_DEBUG: Queued ${audio.size} bytes of WAV audio")
                
                val sessionId = UUID.randomUUID().toString()
                // Tell the model to listen to the attached audio
                processQuery("[User sent voice message — listen and respond to the audio]", sessionId)
            }
        }
    }

    // ... end of class ...
    
    suspend fun getRecentTurns(limit: Int = 20): List<ConversationTurn> {
        return memoryManager.getSessionHistory(limit)
    }

}
