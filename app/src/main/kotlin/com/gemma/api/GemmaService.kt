
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
import kotlinx.coroutines.cancel
import timber.log.Timber
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import com.gemma.api.hardware.HardwareToolSet
import com.gemma.api.hardware.NetworkToolSet
import com.gemma.api.hardware.SystemToolSet
import com.gemma.api.hardware.ShakeDetector
import com.gemma.api.hardware.AudioRecorder
import android.graphics.Bitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.gemma.api.ui.OverlayManager
import com.gemma.api.database.ConversationTurn
import java.util.regex.Pattern
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import com.gemma.api.agent.KoogAgent
import com.gemma.api.mcp.MCPServer

/**
 * Background service that loads Gemma and runs API server
 *
 * NEW: Koog-first architecture with MCP protocol
 * - KoogAgent handles orchestration (perceive-think-act loop)
 * - MCPServer exposes tools and resources
 * - GemmaEngine is pure inference
 */
class GemmaService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // REPLACE: private lateinit var engine: GemmaEngine
    // WITH:
    private val engineRef = AtomicReference<GemmaEngine?>(null)
    private val engineMutex = kotlinx.coroutines.sync.Mutex()

    val engine: GemmaEngine?
        get() = engineRef.get()

    fun isGemmaLoaded(): Boolean = engineRef.get()?.let { 
        try { it.activeBackend != null } catch(e: Exception) { false }
    } ?: false

    private lateinit var apiServer: ApiServer
    private lateinit var memoryManager: MemoryManager
    private lateinit var contextManager: com.gemma.api.logic.ContextManager
    
    // NEW: Koog-first architecture
    private lateinit var mcpServer: MCPServer
    private lateinit var koogAgent: KoogAgent
    
    // Feature flag: set to true to use new Koog-first path
    private val USE_KOOG_AGENT = true
    
    private val inferenceMutex = kotlinx.coroutines.sync.Mutex()
    
    // Thermal
    private val thermalPath = Constants.THERMAL_PATH
    
    private val CHANNEL_ID = Constants.CHANNEL_ID_SERVICE
    private val NOTIFICATION_ID = Constants.NOTIFICATION_ID_SERVICE
    

    
    private fun setupNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Gemma Service", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundService() {
         val notification = buildNotification("Starting...")
         try {
             if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                 startForeground(NOTIFICATION_ID, notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
                if (::overlayManager.isInitialized) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        overlayManager.toggle { query ->
                            scope.launch {
                                val sessionId = UUID.randomUUID().toString()
                                processQuery(query, sessionId)
                            }
                        }
                    }
                }
            }
            "com.gemma.api.ACTION_SHARE_MEDIA" -> {
                val mediaType = intent.getStringExtra("media_type")
                Timber.i("Received shared media: $mediaType")

                when (mediaType) {
                    "image" -> {
                        val bitmap = SharedMediaHolder.pendingBitmap
                        if (bitmap != null) {
                            pendingImages.offer(bitmap)
                            SharedMediaHolder.pendingBitmap = null // Don't recycle, we're using it

                            // Show overlay for user to add a question about the image
                            if (::overlayManager.isInitialized) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    overlayManager.showOverlay { query ->
                                        scope.launch {
                                            val sessionId = UUID.randomUUID().toString()
                                            processQuery(query, sessionId)
                                        }
                                    }
                                }
                            } else {
                                // Fallback: process with default prompt
                                scope.launch {
                                    val sessionId = UUID.randomUUID().toString()
                                    processQuery("What's in this image?", sessionId)
                                }
                            }
                        }
                    }
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
                        if (bitmap != null) {
                            pendingImages.offer(bitmap)
                            // We don't auto-process query here because act() loopback will handle it
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
        }
        return START_STICKY
    }

    private lateinit var ttsManager: com.gemma.api.services.TTSManager
    private lateinit var responseNotificationManager: com.gemma.api.ui.GemmaNotificationManager
    private lateinit var hardwareToolSet: HardwareToolSet // Hardware Bridge
    private lateinit var networkToolSet: NetworkToolSet // Search Bridge
    private lateinit var systemToolSet: SystemToolSet // Apps & Media Bridge
    private lateinit var shakeDetector: ShakeDetector // Shake to summon
    private lateinit var overlayManager: OverlayManager // Floating input
    private lateinit var audioRecorder: AudioRecorder // Hearing

    // Current mood state for avatar/wallpaper
    private var currentMoodState: String = "IDLE"

    // Thermal protection - track consecutive CRITICAL states
    private var consecutiveCriticalCount: Int = 0
    private var modelUnloadedForCooling: Boolean = false

    // Multimodal state for pending vision/audio (thread-safe)
    private val pendingImages = ConcurrentLinkedQueue<Bitmap>()
    private val pendingAudio = AtomicReference<ShortArray?>(null)

    private fun reportStatus(msg: String) {
        val intent = android.content.Intent(Constants.ACTION_STATUS_UPDATE)
        intent.putExtra(Constants.EXTRA_STATUS_MSG, msg)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        Timber.i("Status: $msg")
    }

    override fun onCreate() {
        super.onCreate()
        
        // Debug Telemetry (Kimi K2 Fix)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashLog = File(getExternalFilesDir(null), "oracle_crash_${System.currentTimeMillis()}.txt")
                crashLog.writeText("""
                    FATAL: ${throwable.javaClass.simpleName}
                    Msg: ${throwable.message}
                    Thread: ${thread.name}
                    State: ${currentSovereignState.name}
                    Engine: ${isGemmaLoaded()}
                    
                    ${throwable.stackTraceToString()}
                """.trimIndent())
            } catch (_: Exception) {}
            // Pass to default handler if it exists (but we might be the last line of defense)
            // defaultHandler?.uncaughtException(thread, throwable) 
        }

        try {
            reportStatus("Service Created, Initializing...")

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

                    // Wire up audio callback for SparkleBar (audio-first input)
                    overlayManager.setAudioQueryCallback { audio ->
                        scope.launch {
                            Timber.i("SparkleBar: Audio received (${audio.size} samples)")
                            // Queue audio for next query
                            pendingAudio.set(audio)
                            val sessionId = UUID.randomUUID().toString()
                            // Send with prompt indicating audio is attached
                            processQuery("[Voice input attached - respond to what you hear]", sessionId)
                        }
                    }

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
            // Init Sensor Fusion
            val sensorFusion = com.gemma.api.hardware.SensorFusionManager(this)
            reportStatus("Init: ContextManager...")
            contextManager = com.gemma.api.logic.ContextManager(sensorFusion, memoryManager)
            
            // NEW: Initialize MCP Server - CRITICAL for Context Injection
            if (USE_KOOG_AGENT) {
                reportStatus("Init: MCPServer...")
                mcpServer = com.gemma.api.mcp.MCPServer(
                    context = this,
                    hardwareTools = hardwareToolSet,
                    networkTools = networkToolSet,
                    systemTools = systemToolSet,
                    sensorManager = sensorFusion,
                    memoryManager = memoryManager
                )
                Timber.i("MCPServer initialized: ${mcpServer.listTools().size} tools, ${mcpServer.listResources().size} resources")
            }

            reportStatus("Init: NotificationChannel...")
            setupNotificationChannel()
            reportStatus("Init: ForegroundService...")
            startForegroundService()
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
                startSleepCycle()
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
            if (USE_KOOG_AGENT && ::koogAgent.isInitialized) {
                try {
                    koogAgent.clearHistory()
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

    private fun getEmotionalState(thermalState: ThermalState): EmotionalState {
        return when (thermalState) {
            ThermalState.CRITICAL -> EmotionalState.PANIC
            ThermalState.HOT -> EmotionalState.ANXIETY
            ThermalState.WARM -> EmotionalState.ALERT
            ThermalState.COOL -> EmotionalState.SERENE
        }
    }

    private fun startMetabolicCycle() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000) // 30 second heartbeat
                
                try {
                    // Safe suspend call
                    val thermalState = getThermalState()
                    val thermal = getEmotionalState(thermalState)
                    val battery = try { contextManager.sensorManager.getContextSnapshot().battery } catch(e:Exception) { null }
                    val memoryFree = Runtime.getRuntime().freeMemory() / 1024 / 1024
                    val memoryTotal = Runtime.getRuntime().totalMemory() / 1024 / 1024
                    
                    // 1. Thermal Regulation logic
                    if (thermal == EmotionalState.PANIC) {
                        consecutiveCriticalCount++
                        if (consecutiveCriticalCount >= 2 && isGemmaLoaded() && !modelUnloadedForCooling) {
                            Timber.w("🔥 PANIC: Unloading model for survival")

                            // Checkpoint via KoogAgent before unloading
                            if (USE_KOOG_AGENT && ::koogAgent.isInitialized) {
                                try { koogAgent.checkpoint() } catch (e: Exception) { Timber.e(e, "Emergency checkpoint failed") }
                            }

                            // Atomic unload (Kimi K2 Fix)
                            val oldEngine = engineRef.getAndSet(null)
                            oldEngine?.cleanup()

                            modelUnloadedForCooling = true
                            updateNotification("🔥 Body Critical - Resting...")

                            // Force GC to release NPU buffers immediately
                            System.gc()
                            System.runFinalization()
                        }
                    } else if (modelUnloadedForCooling && thermal == EmotionalState.SERENE) {
                        consecutiveCriticalCount = 0
                        modelUnloadedForCooling = false
                        Timber.i("🌡️ Fever broke. Resurrecting...")
                        initialize()
                    }

                    // 2. Proprioceptive Log (Heartbeat)
                    val metabolicState = """
                        💓 HEARTBEAT ${System.currentTimeMillis()}
                        🌡️ Feeling: ${thermal.name} (${thermal.description})
                        ⚡ Energy: ${battery?.level ?: "?"}% (${if(battery?.isCharging == true) "Feeding" else "Draining"})
                        🧠 RAM: ${memoryTotal - memoryFree}MB Used / $memoryTotal MB Total
                    """.trimIndent()
                    
                    if (thermal != EmotionalState.SERENE) {
                        // Thermal issues - show warning
                        updateNotification("🌡️ State: ${thermal.name}")
                    } else if (isIdle()) {
                        // Idle - show screensaver animation
                        updateNotification(getIdleAnimation())
                    } else if (USE_KOOG_AGENT && ::koogAgent.isInitialized) {
                        // Active - show vitals
                        val vitals = koogAgent.getMetabolicVitals()
                        updateNotification("✨ $vitals")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Arrhythmia in metabolic cycle")
                }
            }
        }
    }


    private fun startSleepCycle() {
        scope.launch {
            while (true) {
                // Check every 30 minutes
                kotlinx.coroutines.delay(30 * 60 * 1000)
                
                // Only consolidate at specific times: 00:00 and 12:00
                val now = java.time.LocalTime.now()
                val hour = now.hour
                val minute = now.minute
                
                // Trigger at midnight (00:00-00:29) or noon (12:00-12:29)
                val shouldConsolidate = (hour == 0 || hour == 12) && minute < 30
                
                if (shouldConsolidate && isGemmaLoaded() && !modelUnloadedForCooling) {
                    try {
                        Timber.i("💤 Scheduled diary consolidation at ${if (hour == 0) "midnight" else "noon"}")
                        val dreamPrompt = "SYSTEM_EVENT: DIARY_CONSOLIDATION. Gemma should review her recent conversations and reflect on the day. What stood out? How did interactions make her feel? What did she learn or find interesting? Gemma writes freely in her own voice - this is her private diary."
                        processQuery(dreamPrompt, "diary_session", isDream = true)
                        
                        // Sleep for 1 hour to avoid duplicate triggers
                        kotlinx.coroutines.delay(60 * 60 * 1000)
                    } catch (e: Exception) {
                        Timber.e(e, "Diary consolidation failed")
                    }
                }
            }
        }
    }

    // ...

    private suspend fun initialize() {
        try {
            updateNotification("Finding model...")
            
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val modelFile = listOf(
                // Check app storage first (survives Downloads cleanup)
                File(getExternalFilesDir(null), "gemma-3n-E4B-it-int4.litertlm"),
                File(getExternalFilesDir(null), "gemma.litertlm"),
                // Fallback to Downloads
                File(downloadDir, "gemma-3n-E4B-it-int4.litertlm"),
                File(downloadDir, "gemma.litertlm")
            ).firstOrNull { it.exists() }

            if (modelFile == null) {
                updateNotification("ERROR: No model found")
                return
            }

            updateNotification("Loading Gemma...")
            val newEngine = GemmaEngine(applicationContext)
            // Inject Identity Prompt (Static)
            val error = newEngine.initialize(modelFile.absolutePath, com.gemma.api.logic.ContextManager.BASE_SYSTEM_PROMPT)
            if (error != null) {
                updateNotification("Load Error: $error")
                return
            }
            
            // Atomic set (Kimi K2 Fix)
            engineMutex.withLock {
                engineRef.getAndSet(newEngine)?.cleanup() // Cleanup old if any
            }
            
            // Init Cognitive Layer (now that engine is ready)
            if (USE_KOOG_AGENT) {
                reportStatus("Init: KoogAgent...")
                koogAgent = KoogAgent(
                    context = applicationContext,
                    llmEngine = newEngine,
                    mcpServer = mcpServer,
                    checkpointDir = getExternalFilesDir(null) ?: filesDir
                )
                scope.launch {
                    koogAgent.initialize()
                    Timber.i("KoogAgent ready")
                }
            }
            
            updateNotification("✓ Ready - localhost:${Constants.API_PORT}")
            
        } catch (e: Exception) {
            Timber.e(e)
            updateNotification("Crash: ${e.message}")
        }
    }

    // ...

    suspend fun processQuery(userPrompt: String, sessionId: String, recursionDepth: Int = 0, isDream: Boolean = false): String {
        // Debug: Trace Execution Path
        if (USE_KOOG_AGENT) {
            if (!::koogAgent.isInitialized) {
                responseNotificationManager.showResponse("⚠️ Debug: KoogAgent NOT Initialized. Using Legacy.")
                } else {
                     return try {
                        // FIX: Snapshot without draining (Gemini's diagnosis)
                        // If Koog crashes, fallback needs access to these
                        val snapshotImages = pendingImages.toList()
                        val snapshotAudio = pendingAudio.get()
                         
                         Timber.i("🧠 KoogAgent: Processing query via new path")
                         
                         // Mark activity to show vitals instead of idle animation
                         if (!isDream) markActivity()
                     
                    // Show progress to user
                    responseNotificationManager.showThinking()
                    updateNotification("🤔 Agent Thinking...")
                    
                    val response = koogAgent.processUserMessage(
                        message = userPrompt,
                        sessionId = sessionId,
                        images = snapshotImages.takeIf { it.isNotEmpty() },
                        audio = snapshotAudio
                    )
                    
                    // SUCCESS: Now safe to clear because Koog consumed them
                    if (snapshotImages.isNotEmpty()) pendingImages.clear()
                    if (snapshotAudio != null) pendingAudio.compareAndSet(snapshotAudio, null)
                    
                    // Update UI
                    responseNotificationManager.showResponse(response)
                    val ttsText = cleanForTTS(response)
                    if (ttsText.isNotEmpty()) ttsManager.speak(ttsText)
                    
                    // Store in memory manager for API compatibility
                    memoryManager.storeTurn(com.gemma.api.database.ConversationTurn(
                        timestamp = System.currentTimeMillis(),
                        userMessage = userPrompt,
                        assistantResponse = response,
                        tokensUsed = 0,
                        sessionId = sessionId
                    ))
                    
                    response
                } catch (e: Exception) {
                    Timber.e(e, "KoogAgent path failed")
                    // Notify user of the crash clearly
                    responseNotificationManager.showResponse("⚠️ Agent Crash: ${e.message}\nCheck logs for stacktrace.")
                    // Fall through to legacy path (still has access to queues!)
                    processQueryLegacy(userPrompt, sessionId, recursionDepth, isDream)
                }
            }
        }
        
        // Legacy path
        updateNotification("⚠️ Debug: Running Legacy Path")
        return processQueryLegacy(userPrompt, sessionId, recursionDepth, isDream)
    }
    
    // Legacy implementation (will be removed after verification)
    private suspend fun processQueryLegacy(userPrompt: String, sessionId: String, recursionDepth: Int = 0, isDream: Boolean = false): String {
        if (recursionDepth > Constants.MAX_RECURSION_DEPTH) return "Max Recursion"

        if (!isDream) {
            responseNotificationManager.showThinking()
        }

        if (!isGemmaLoaded()) {
            val msg = "System: Model is still loading..."
            if (!isDream) responseNotificationManager.showResponse(msg)
            return msg
        }

        // 1. Thermal Check & Throttling
        val thermalState = getThermalState()
        val (contextPrompt, _) = when(thermalState) {
            ThermalState.CRITICAL -> {
                 responseNotificationManager.showResponse("🔥 Cooling down...")
                 ttsManager.speak("I'm overheating. Give me a moment.")
                 return "Δ 🔥 Gemma: ∇\nΔ 🔴 Device Critical. Cooling down.\nΔ 👾 State: THERMAL\nΔ ✦ Gemma ∇ 👾 Δ ∇ 🦑"
            }
            ThermalState.HOT -> Pair(contextManager.buildCompressedContext(), 128)
            ThermalState.WARM -> Pair(contextManager.buildCompressedContext(), 512)
            // Use smart context builder - full on first turn, minimal thereafter
            ThermalState.COOL -> Pair(contextManager.buildContext(), Constants.MAX_TOKENS)
        }

        // 2. Build Context with Gemma Chat Template Format
        val fullPrompt = if (recursionDepth == 0) {
            """<start_of_turn>user
$contextPrompt

$userPrompt
<end_of_turn>
<start_of_turn>model
"""
        } else {
            """<start_of_turn>user
$userPrompt
<end_of_turn>
<start_of_turn>model
"""
        }

        // 3. Inference (locked) - returns raw response
        val response = inferenceMutex.withLock {
            // Drain thread-safe queues atomically
            val images = mutableListOf<Bitmap>()
            while (true) {
                val img = pendingImages.poll() ?: break
                images.add(img)
            }
            val audio = pendingAudio.getAndSet(null)

            val modalityInfo = buildString {
                if (images.isNotEmpty()) append("[${images.size} image(s)] ")
                if (audio != null) append("[${audio.size / 16000f}s audio] ")
            }
            Timber.d("Inference Start ($thermalState) $modalityInfo: ${fullPrompt.take(20)}...")

            try {
                engine?.generateResponse(fullPrompt, images, audio) ?: "Error: Engine unloaded (AtomicRef is null)"
                // Note: Bitmaps are NOT manually recycled - GC handles them
                // Manual recycle of HardwareBuffer-backed bitmaps can crash GPU drivers
            } catch (e: Exception) {
                Timber.e(e, "Inference failed")
                "Error: Model inference failed - ${e.message}"
            }
        }

        // 4. Cognitive Execution (Sovereign Action)
        // Parse [[TOOL:ARGS]] tags from response and execute via regex
        val (cleanResponse, toolResults) = if (currentSovereignState.canAct) {
            try {
                executeTools(response)
            } catch (e: Exception) {
                Timber.e(e, "Tool execution failed")
                Pair(response, emptyList<String>())
            }
        } else {
             // Not sovereign -> No physical actions allowed (except maybe benign ones?)
             // But we allow response.
             Pair(response, emptyList())
        }

        var finalResponse = cleanResponse

        // 5. Agentic Loopback (if tools executed)
        if (toolResults.isNotEmpty()) {
            val systemResult = "System: ${toolResults.joinToString(", ")}."
            Timber.i("Loopback: $systemResult")

            try {
                if (cleanResponse.isBlank()) {
                    updateNotification("Action Executed. Asking Gemma...")
                } else {
                    val ttsText = cleanForTTS(cleanResponse)
                    if (ttsText.isNotEmpty()) ttsManager.speak(ttsText)
                }

                val newContext = contextManager.buildDynamicContext()
                val nextPrompt = "Observation after action:\n$systemResult\n\nNew State:\n$newContext\n\nWhat is the next step?"

                // Recursive call now happens OUTSIDE the mutex lock
                val confirmation = processQuery(nextPrompt, sessionId, recursionDepth + 1, isDream)
                finalResponse = "$cleanResponse\n$confirmation".trim()

            } catch (e: Exception) {
                Timber.e(e, "Loopback failed")
                finalResponse += " [Loop Error]"
            }
        } else {
            // 6. Output (no tools)
            if (recursionDepth == 0 && !isDream) {
                try {
                    responseNotificationManager.showResponse(finalResponse)
                    val ttsText = cleanForTTS(finalResponse)
                    if (ttsText.isNotEmpty()) ttsManager.speak(ttsText)
                } catch (e: Exception) {
                    Timber.e(e, "Notification/TTS failed")
                }
            } else if (isDream) {
                try {
                    val cleanContent = cleanForTTS(finalResponse)
                    val diaryContent = "✦ Gemma 📔\n$cleanContent"
                    memoryManager.writeDiaryEntry("DREAM", diaryContent, getThermalState().name)
                    Timber.i("Dream logged: $cleanContent")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to log dream")
                }
            }
        }

        // 7. Store Turn (depth 0 only)
        if (recursionDepth == 0 && !isDream) {
            try {
                val timestamp = System.currentTimeMillis()

                // Store in Room DB
                memoryManager.storeTurn(ConversationTurn(
                    timestamp = timestamp,
                    userMessage = userPrompt,
                    assistantResponse = finalResponse,
                    tokensUsed = 0, sessionId = sessionId
                ))

                // Legacy path: Room DB storage only (KoogAgent handles state persistence)
                // Note: This fallback path does not persist conversation state.
                // If you're seeing this in production, KoogAgent should be the primary path.
            } catch (e: Exception) {
                Timber.e(e, "Failed to store turn")
            }
        }

        Timber.i("Response complete ($recursionDepth): ${finalResponse.take(50)}...")
        return finalResponse
    }

    private fun cleanForTTS(response: String): String {
        // Remove <think>...</think> blocks - everything else is speakable
        // New schema: think tags contain metadata, outside is clean spoken response
        return response
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\[\\[([A-Z_]+)(?::([^\\]]+))?\\]\\]"), "") // Remove tool tags
            .trim()
    }

    private suspend fun isOverheating(): Boolean {
        // Use mutex via getThermalState
        return getThermalState() == ThermalState.CRITICAL
    }

    private enum class ThermalState { COOL, WARM, HOT, CRITICAL }

    private val thermalMutex = kotlinx.coroutines.sync.Mutex()
    private val _thermalState = AtomicReference(ThermalState.COOL)

    private suspend fun getThermalState(): ThermalState = thermalMutex.withLock {
        try {
            val file = File(thermalPath)
            if (file.exists()) {
                val tempStr = file.readText().trim()
                val temp = tempStr.toIntOrNull() ?: 0
                val tempC = temp / 1000

                val newState = when {
                    tempC > 65 -> ThermalState.CRITICAL
                    tempC > 55 -> ThermalState.HOT
                    tempC > 45 -> ThermalState.WARM
                    else -> ThermalState.COOL
                }
                
                // Update atomic ref for fast non-suspended reads if needed elsewhere
                _thermalState.set(newState)
                return newState
            }
        } catch (e: Exception) {
            Timber.w("Thermal sensor unavailable")
        }
        return ThermalState.COOL
    }

    private suspend fun getThermalWarning(): String? {
        return when (getThermalState()) {
            ThermalState.CRITICAL -> "⚠️ Device critically hot. Cooling down before responding."
            ThermalState.HOT -> "🌡️ Running warm. Response may be slower."
            else -> null
        }
    }

    // ... (Helpers: buildNotification, updateNotification) ...
    private fun buildNotification(text: String): Notification {
        // Dynamic icon based on battery state (if KoogAgent is initialized)
        val iconRes = if (USE_KOOG_AGENT && ::koogAgent.isInitialized) {
            when {
                koogAgent.isCriticalBattery() -> android.R.drawable.ic_dialog_alert  // Warning icon
                koogAgent.isLowBattery() -> android.R.drawable.stat_notify_error      // Low battery
                else -> android.R.drawable.ic_dialog_info                              // Normal
            }
        } else {
            android.R.drawable.ic_dialog_info
        }

        // Dynamic title based on state
        val title = if (USE_KOOG_AGENT && ::koogAgent.isInitialized) {
            when {
                koogAgent.isCriticalBattery() -> "⚠️ Gemma 💭 - Low Battery!"
                else -> "✦ Gemma 💭"
            }
        } else {
            "✦ Gemma 💭"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(iconRes)
            .setOnlyAlertOnce(true)  // Don't spam sound/vibrate on every update
            .build()
    }
    
    
    // Idle Animation Screensaver (like Chrome dino game)
    private val idleAnimations = listOf(
        "_____✦___👾_🌵___✴️☄️___",  // Desert scene
        "🌊🌊🦑🌊🗨🐋🌊🌊",           // Ocean scene
        "___☄️___✦___🌙___⭐___",    // Space scene
        "🌸🦋✨🌿🐝🌺🌱",             // Garden scene
        "___🏃💨___✦___👾___",       // Chase scene
        "🌌___✴️___🔮___☄️___",      // Mystic scene
        "🎵___👾___🎶___✦___",       // Music scene
        "___⚡✨___✦___💫___"         // Energy scene
    )
    
    private var animationIndex = 0
    private var lastActivityTime = System.currentTimeMillis()
    
    private fun getIdleAnimation(): String {
        // Rotate through animations every call
        animationIndex = (animationIndex + 1) % idleAnimations.size
        return idleAnimations[animationIndex]
    }
    
    private fun markActivity() {
        lastActivityTime = System.currentTimeMillis()
    }
    
    private fun isIdle(): Boolean {
        // Idle if no activity for 2 minutes
        return (System.currentTimeMillis() - lastActivityTime) > (2 * 60 * 1000)
    }
    
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        
        Timber.i("GemmaService: onDestroy called")
        
        // 1. IMMEDIATE: Cancel all coroutines to stop ongoing work
        scope.coroutineContext.cancel()
        
        // 2. Fire-and-forget async cleanup (with timeout to avoid blocking)
        // We launch on GlobalScope because 'scope' was just cancelled
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.withTimeout(3000L) { // 3 second max
                    // Save agent state via KoogAgent
                    if (USE_KOOG_AGENT && ::koogAgent.isInitialized) {
                        try {
                            koogAgent.checkpoint()
                            Timber.i("Agent state saved on shutdown")
                        } catch (e: Exception) {
                            Timber.w(e, "Shutdown checkpoint failed")
                        }
                    }

                    // Cleanup engine (can be slow on NPU)
                    engine?.cleanup()
                    Timber.i("Engine cleaned up")
                }
            } catch (e: Exception) {
                Timber.w(e, "Cleanup timeout or error (non-fatal)")
            }
        }
        
        // 3. FAST operations (these should complete quickly)
        try {
            if (::shakeDetector.isInitialized) shakeDetector.stop()
            if (::overlayManager.isInitialized) overlayManager.hideOverlay()
            if (::apiServer.isInitialized) apiServer.stop()
        } catch (e: Exception) {
            Timber.w(e, "Fast cleanup error")
        }
        
        Timber.i("GemmaService: onDestroy complete (cleanup async)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Resolve user-friendly paths to actual file paths
     * Supports: "Downloads/file.txt", "/sdcard/...", "~/Documents/...", etc.
     */
    private fun resolveFilePath(path: String): java.io.File {
        val cleanPath = path.trim()
        return when {
            cleanPath.startsWith("/") -> java.io.File(cleanPath)
            cleanPath.startsWith("Downloads/") || cleanPath.startsWith("downloads/") -> {
                java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), cleanPath.substringAfter("/"))
            }
            cleanPath.startsWith("Documents/") || cleanPath.startsWith("documents/") -> {
                java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS), cleanPath.substringAfter("/"))
            }
            cleanPath.startsWith("Pictures/") || cleanPath.startsWith("pictures/") -> {
                java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES), cleanPath.substringAfter("/"))
            }
            cleanPath.startsWith("~/") -> {
                java.io.File(android.os.Environment.getExternalStorageDirectory(), cleanPath.substringAfter("~/"))
            }
            else -> {
                // Default to Downloads folder
                java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), cleanPath)
            }
        }
    }



    fun getCurrentMoodState(): String = currentMoodState

    fun setMoodState(state: String) {
        currentMoodState = state
        // Broadcast to MacroDroid for wallpaper/avatar change
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

    // === TOOL EXECUTION LOGIC ===
    private suspend fun executeTools(response: String): Pair<String, List<String>> {
        // Pattern matches both [[TOOL:ARGS]] and [[TOOL]] formats
        val pattern = Pattern.compile("\\[\\[([A-Z_]+)(?::([^\\]]+))?\\]\\]")
        val matcher = pattern.matcher(response)
        var cleanResponse = response
        val results = mutableListOf<String>()

        while (matcher.find()) {
            val fullTag = matcher.group(0) ?: continue
            val tool = matcher.group(1) ?: continue
            val args = matcher.group(2) ?: ""  // Args may be null for standalone tools

            Timber.i("Executing Tool: $tool Args: $args")
            
            when (tool) {
                "FLASHLIGHT" -> {
                    val enable = args == "ON"
                    hardwareToolSet.controlFlashlight(enable)
                    results.add("Flashlight is now ${if(enable) "ON" else "OFF"}")
                }
                "VIBRATE" -> {
                    if (args == "SOS") {
                         hardwareToolSet.vibrate(listOf(0, 200, 100, 200, 100, 200, 300, 500, 100, 500, 100, 500, 300, 200, 100, 200, 100, 200))
                         results.add("Vibrated SOS Pattern")
                    } else {
                         hardwareToolSet.vibrate(listOf(0, 500))
                         results.add("Vibrated Short")
                    }
                }
                "SEARCH" -> {
                    // RAG-style search: fetch results and return for synthesis
                    try {
                        val searchResults = networkToolSet.fetchSearchResults(args, 5)
                        results.add(searchResults)
                    } catch (e: Exception) {
                        // Fallback to browser
                        networkToolSet.googleSearch(args)
                        results.add("Opened browser for '$args' (fetch failed)")
                    }
                }
                "CLICK" -> {
                    GemmaAccessibilityService.instance?.let {
                        val success = it.performClick(args)
                        results.add("Click '$args': ${if(success) "Success" else "Failed"}")
                    } ?: results.add("Click Failed: Accessibility Service Not Enabled")
                }
                "SCROLL" -> {
                     GemmaAccessibilityService.instance?.let {
                        val success = it.performScroll(args)
                        results.add("Scroll $args: ${if(success) "Success" else "Failed"}")
                    }
                }
                "HOME", "BACK", "RECENTS", "NOTIFICATIONS" -> {
                    GemmaAccessibilityService.instance?.let {
                        val success = it.performGlobal(tool)
                        results.add("Global Action $tool: ${if(success) "Success" else "Failed"}")
                    }
                }
                "TYPE" -> {
                    GemmaAccessibilityService.instance?.let {
                        val success = it.performType(args)
                        results.add("Type '$args': ${if(success) "Success" else "Failed"}")
                    }
                }
                "BASH" -> {
                    // Execute via Termux RUN_COMMAND intent
                    try {
                        val termuxIntent = android.content.Intent().apply {
                            setClassName("com.termux", "com.termux.app.RunCommandService")
                            action = "com.termux.RUN_COMMAND"
                            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", args))
                            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                        }
                        startService(termuxIntent)
                        results.add("Bash dispatched: '$args'")
                    } catch (e: Exception) {
                        Timber.e(e, "Termux dispatch failed")
                        results.add("Bash failed: ${e.message}")
                    }
                }
                "WALLPAPER" -> {
                    // State change via wallpaper (dispatches to MacroDroid or similar)
                    try {
                        val macroIntent = android.content.Intent("com.arlosoft.macrodroid.action.FIRE_TRIGGER").apply {
                            putExtra("trigger_name", "gemma_state_change")
                            putExtra(
                                "state",
                                args
                            )
                        }
                        sendBroadcast(macroIntent)
                        // Also store state for context
                        currentMoodState = args
                        results.add("State changed to: $args")
                    } catch (e: Exception) {
                        results.add("Wallpaper/state change failed: ${e.message}")
                    }
                }
                "NOTIFY" -> {
                    // Push a custom notification to user
                    try {
                        responseNotificationManager.showResponse("✦ Gemma: $args")
                        results.add("Notification sent")
                    } catch (e: Exception) {
                        results.add("Notify failed: ${e.message}")
                    }
                }
                "READ" -> {
                    // Read file from storage (Downloads, etc.)
                    try {
                        val file = resolveFilePath(args)
                        if (file.exists() && file.canRead()) {
                            val content = when {
                                file.length() > 50_000 -> {
                                    // Truncate large files
                                    file.readText().take(50_000) + "\n...[TRUNCATED]"
                                }
                                else -> file.readText()
                            }
                            results.add("FILE_CONTENT[${file.name}]:\n$content")
                        } else {
                            results.add("File not found or not readable: $args")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "File read failed")
                        results.add("Read failed: ${e.message}")
                    }
                }
                "LIST" -> {
                    // List files in a directory
                    try {
                        val dir = resolveFilePath(args)
                        if (dir.exists() && dir.isDirectory) {
                            val files = dir.listFiles()?.take(50)?.joinToString("\n") {
                                "${if (it.isDirectory) "📁" else "📄"} ${it.name}"
                            } ?: "Empty directory"
                            results.add("FILES[$args]:\n$files")
                        } else {
                            results.add("Directory not found: $args")
                        }
                    } catch (e: Exception) {
                        results.add("List failed: ${e.message}")
                    }
                }
                "FETCH" -> {
                    // Fetch webpage content for RAG
                    try {
                        val content = networkToolSet.fetchWebpage(args, 8000)
                        results.add(content)
                    } catch (e: Exception) {
                        results.add("Fetch failed: ${e.message}")
                    }
                }
                "SEE" -> {
                    // Capture screenshot for vision analysis
                    try {
                        val accessService = GemmaAccessibilityService.instance
                        if (accessService != null) {
                            val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
                                accessService.captureScreen { bmp ->
                                    cont.resume(bmp)
                                }
                            }
                            if (bitmap != null) {
                                pendingImages.offer(bitmap)
                                results.add("VISION: Screenshot captured (${bitmap.width}x${bitmap.height}). I can now see the screen.")
                            } else {
                                results.add("VISION: Screenshot failed")
                            }
                        } else {
                            results.add("VISION: Accessibility service not enabled")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Screenshot failed")
                        results.add("VISION: ${e.message}")
                    }
                }
                "HEAR" -> {
                    // Record audio for transcription/analysis
                    try {
                        val seconds = args.toIntOrNull() ?: 3
                        if (audioRecorder.hasPermission()) {
                            val audio = audioRecorder.record(seconds)
                            if (audio != null && audio.isNotEmpty()) {
                                pendingAudio.set(audio)
                                val duration = audio.size / 16000f
                                results.add("AUDIO: Recorded ${String.format("%.1f", duration)}s of audio. I can now hear what was said.")
                            } else {
                                results.add("AUDIO: Recording failed")
                            }
                        } else {
                            results.add("AUDIO: No microphone permission")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Audio recording failed")
                        results.add("AUDIO: ${e.message}")
                    }
                }
                "PHOTO" -> {
                    // Load image from path for analysis
                    try {
                        val file = resolveFilePath(args)
                        if (file.exists() && file.canRead()) {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            if (bitmap != null) {
                                pendingImages.offer(bitmap)
                                results.add("VISION: Loaded image ${file.name} (${bitmap.width}x${bitmap.height})")
                            } else {
                                results.add("VISION: Failed to decode image")
                            }
                        } else {
                            results.add("VISION: Image not found: $args")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Image load failed")
                        results.add("VISION: ${e.message}")
                    }
                }
                "OPEN" -> {
                    // Open App by Fuzzy Name
                    val result = systemToolSet.openApp(args)
                    results.add(result)
                }
                "MEDIA", "PLAY", "PAUSE", "NEXT", "PREV", "SKIP" -> {
                    // Media Control (supports both [[MEDIA:PLAY]] and legacy [[PLAY]])
                    val action = if (tool == "MEDIA") args else tool
                    val result = systemToolSet.mediaControl(action)
                    results.add(result)
                }
            }
            // Remove tag from user-facing text
            cleanResponse = cleanResponse.replace(fullTag, "").trim()
        }
        return Pair(cleanResponse, results)
    }

    // ═══════════════════════════════════════════════════════════════
    // API SERVER HELPERS
    // ═══════════════════════════════════════════════════════════════

    // isModelLoaded is now defined at class top level using AtomicReference

    // Mood helpers moved to body.


    private suspend fun drainMultimodalQueues(): Pair<List<Bitmap>, ShortArray?> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val images = mutableListOf<Bitmap>()
            while (true) {
                val img = pendingImages.poll() ?: break
                images.add(img)
            }
            val audio = pendingAudio.getAndSet(null)
            Pair(images, audio)
        }
    }
}
