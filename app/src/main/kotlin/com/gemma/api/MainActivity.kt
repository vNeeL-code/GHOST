package com.gemma.api

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import timber.log.Timber
import com.gemma.api.services.TTSManager
import com.gemma.api.ui.chat.ChatAdapter
import com.gemma.api.ui.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import com.gemma.api.database.ConversationTurn

/**
 * The Advanced Oracle_OS Gateway
 * Handles Permissions, then spawns the Native Chat Interface seamlessly hooking into GemmaService.
 */
class MainActivity : ComponentActivity(), GemmaService.UiCallback {
    
    // UI Elements - Launcher Phase
    private lateinit var statusTextView: TextView
    private lateinit var actionButton: Button
    private lateinit var bypassButton: Button
    
    // UI Elements - Chat Phase
    private var chatRecyclerView: RecyclerView? = null
    private var chatInputText: EditText? = null
    private var btnSend: Button? = null
    private var thinkingProgress: ProgressBar? = null
    private var thinkingText: TextView? = null
    
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var ttsManager: TTSManager
    
    // Service Binding
    private var gemmaService: GemmaService? = null
    private var isBound = false
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    
    private val handler = Handler(Looper.getMainLooper())
    private var isRitualComplete = false
    private var isShowingDiary = false

    // Multimodal
    private val imagePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handlePickedImage(it) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as GemmaService.LocalBinder
            gemmaService = binder.getService()
            gemmaService?.uiCallback = this@MainActivity
            isBound = true
            Timber.i("🌀 UI successfully bound to GemmaService")
            
            // Switch to Chat UI now that the backend is officially wired up
            if (!isFinishing) {
                transitionToChatUi()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            gemmaService?.uiCallback = null
            gemmaService = null
            Timber.i("🌀 UI unbound from GemmaService")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        Timber.i("🌀 Entering the System Check...")
        
        // Init Voice
        ttsManager = TTSManager(this)
        
        // Start in Launcher Phase
        setupLauncherUi()
    }
    
    private fun setupLauncherUi() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        
        statusTextView = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 50)
            text = "Initializing System Check..."
            setTextColor(android.graphics.Color.WHITE)
        }
        
        actionButton = Button(this).apply {
            textSize = 18f
            text = "Check State"
            visibility = View.GONE
        }
        
        bypassButton = Button(this).apply {
            textSize = 16f
            text = "Skip (Lite Mode)"
            setTextColor(android.graphics.Color.RED)
            visibility = View.GONE
        }
        
        layout.addView(statusTextView)
        layout.addView(actionButton)
        layout.addView(bypassButton)
        setContentView(layout)
    }
    
    override fun onResume() {
        super.onResume()
        if (!isRitualComplete) {
            handler.postDelayed({ checkSystemState() }, 500)
        }
    }

    // --- PERMISSIONS RITUAL ---
    
    private fun checkSystemState() {
        // 1. Notifications (Android 13+ requires POST_NOTIFICATIONS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showState(
                "❌ Voice Offline\n\nI need notification permission to talk to you.",
                "Grant Notifications"
            ) { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100) }
            return
        }

        // 2. Camera
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showState(
                "❌ Vision Offline\n\nI need camera access to see.",
                "Grant Camera"
            ) { requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101) }
            return
        }

        // 3. Audio
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
             showState(
                "❌ Hearing Offline\n\nI need microphone access to hear.",
                "Grant Mic"
            ) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 102) }
            return
        }

        // 4. Calendar (for diary sync)
        if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showState(
                "❌ Memory Offline\n\nI need calendar access to keep my diary.",
                "Grant Calendar"
            ) { requestPermissions(arrayOf(
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR
            ), 103) }
            return
        }

        // 5. Location (for context awareness)
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showState(
                "❌ GPS Offline\n\nI need location access to know where we are.",
                "Grant Location"
            ) { requestPermissions(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ), 104) }
            return
        }

        // 6. Phone state (for context awareness)
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showState(
                "❌ Phone Offline\n\nI need phone access to know call state.",
                "Grant Phone"
            ) { requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 105) }
            return
        }

        // 6b. Check for previous crashes and show forensic alert
        val crashLogDir = File(filesDir, "logs/crashes")
        if (crashLogDir.exists()) {
            val latestCrash = crashLogDir.listFiles()?.maxByOrNull { f -> f.lastModified() }
            if (latestCrash != null) {
                showState(
                    "⚠️ Forensic Alert: Previous crash detected.\n\n${latestCrash.name}\n\nReview before launching?",
                    "View Crash Log"
                ) {
                    val text = latestCrash.readText()
                    statusTextView.text = text.take(1000)
                    actionButton.text = "Recovery Boot"
                    actionButton.setOnClickListener {
                        latestCrash.delete()
                        // Clear service watchdog too
                        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit()
                            .putBoolean("is_initializing", false)
                            .putInt("init_crash_count", 0)
                            .apply()
                        completeRitual()
                    }
                }
                return
            }
        }

        // 7. Bluetooth (nearby devices)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showState(
                "❌ Bluetooth Offline\n\nI need Bluetooth access for nearby devices.",
                "Grant Bluetooth"
            ) { requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 106) }
            return
        }

        // 8. Accessibility (non-blocking)
        if (!isAccessibilityEnabled()) {
            Timber.i("Accessibility not enabled — click/scroll/type tools will be unavailable")
        }

        // 9. Overlay
        if (!Settings.canDrawOverlays(this)) {
            showState(
                "❌ Presence Inactive\n\nI need 'Draw over other apps' permission.",
                "Enable Overlay"
            ) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            return
        }
        
        // 10. Complete
        showState("(Δ 👾 ∇)\n\nStatus: online", "LAUNCHING...") { }
        completeRitual()
    }
    
    private fun showState(status: String, buttonText: String, action: () -> Unit) {
        statusTextView.text = status
        actionButton.text = buttonText
        actionButton.visibility = View.VISIBLE
        actionButton.setOnClickListener { action() }
    }
    
    private fun isAccessibilityEnabled(): Boolean {
        val expectedServiceName = "$packageName/${GemmaAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val isInEnabledList = enabledServices?.contains(expectedServiceName) == true
        val accessibilityEnabled = try { Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) } catch (e: Exception) { 0 }
        return isInEnabledList && accessibilityEnabled == 1
    }

    private fun completeRitual() {
        if (!isFinishing && !isRitualComplete) {
           isRitualComplete = true
           actionButton.isEnabled = false
           try { ttsManager.speak("Online.") } catch (e: Exception) { Timber.e(e) }
           handler.postDelayed({ launchAndBindService() }, 1000)
        }
    }

    private fun launchAndBindService() {
        try {
            val intent = Intent(this, GemmaService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // Bind so we can pipe the UI 
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start or bind service")
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkSystemState()
    }
    
    // --- NATIVE CHAT UI PHASE ---

    private fun transitionToChatUi() {
        setContentView(R.layout.activity_main_chat)
        
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatInputText = findViewById(R.id.chatInputText)
        btnSend = findViewById(R.id.btnSend)
        thinkingProgress = findViewById(R.id.thinkingProgress)
        thinkingText = findViewById(R.id.thinkingText)
        val btnSettings = findViewById<android.view.View>(R.id.titleText)
        
        btnSettings?.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menuInflater.inflate(R.menu.chat_settings_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_transparent_wallpaper -> {
                        try {
                            val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                                putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, 
                                    ComponentName(this@MainActivity, com.gemma.api.ui.CameraWallpaperService::class.java))
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Timber.e(e, "Setup Live Wallpaper Intent Failed")
                        }
                        true
                    }
                    R.id.action_notification_tts -> {
                        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
                        val newState = !prefs.getBoolean(Constants.PREF_PASSIVE_TTS, false)
                        prefs.edit().putBoolean(Constants.PREF_PASSIVE_TTS, newState).apply()
                        item.isChecked = newState
                        Toast.makeText(this@MainActivity, "Passive TTS: ${if (newState) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.action_toggle_diary -> {
                        isShowingDiary = !isShowingDiary
                        item.title = if (isShowingDiary) "Show Main Chat" else "Show Gemma's Internal Diary"
                        findViewById<TextView>(R.id.titleText)?.text = if (isShowingDiary) "Δ 📔 ∇" else "Δ ✧ ∇"
                        chatAdapter.isDiaryMode = isShowingDiary
                        loadHistoricalChat()
                        true
                    }
                    R.id.action_clear_safe_mode -> {
                        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit()
                            .putInt("init_crash_count", 0)
                            .putBoolean("is_initializing", false)
                            .putBoolean("force_cpu", false)
                            .putBoolean("gpu_native_crashed", false)
                            .putBoolean("npu_native_crashed", false)
                            .putBoolean("gpu_verified", false)
                            .putBoolean("npu_verified", false)
                            .putLong("last_native_crash_time", 0)
                            .apply()
                        Toast.makeText(this@MainActivity, "Recovery state cleared", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            
            // Set initial state for checkable items
            val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            popup.menu.findItem(R.id.action_notification_tts)?.isChecked = prefs.getBoolean(Constants.PREF_PASSIVE_TTS, false)
            if (isShowingDiary) {
                popup.menu.findItem(R.id.action_toggle_diary)?.title = "Show Main Chat"
            }
            
            popup.show()
        }
        
        chatAdapter = ChatAdapter()
        chatRecyclerView?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true // Start from bottom like a real chat app
            }
            adapter = chatAdapter
        }
        
        btnSend?.setOnClickListener {
            val text = chatInputText?.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && gemmaService != null) {
                chatInputText?.setText("")
                gemmaService?.processQueryFromUi(text)
            }
        }
        
        val btnAddMedia = findViewById<android.view.View>(R.id.btnAddMedia)
        val btnMic = findViewById<android.view.View>(R.id.btnMic)

        btnAddMedia?.setOnClickListener {
            imagePicker.launch("image/*")
        }

        btnMic?.setOnClickListener {
            handleMicClick()
        }
        
        loadHistoricalChat()
    }
    
    private fun loadHistoricalChat() {
        scope.launch {
            try {
                if (isShowingDiary) {
                    val entries = gemmaService?.memoryManager?.getRecentDiaryEntries(50) ?: emptyList()
                    withContext(Dispatchers.Main) {
                        val messages = entries.sortedBy { it.timestamp }.map { entry ->
                            ChatMessage(
                                content = entry.observation,
                                isFromUser = false,
                                timestamp = entry.timestamp,
                                eventType = entry.eventType
                            )
                        }
                        chatAdapter.setMessages(messages)
                        chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                } else {
                    val service = gemmaService
                    if (service == null) {
                        Timber.w("Cannot load historical chat: service not bound")
                        return@launch
                    }
                    val history: List<ConversationTurn> = service.getRecentTurns()
                    withContext(Dispatchers.Main) {
                        val messages = history.sortedBy { it.timestamp }.flatMap { turn: ConversationTurn ->
                            val list = mutableListOf<ChatMessage>()
                            if (turn.userMessage.isNotEmpty()) {
                                list.add(ChatMessage(turn.userMessage, isFromUser = true, timestamp = turn.timestamp))
                            }
                            if (turn.assistantResponse.isNotEmpty()) {
                                list.add(ChatMessage(turn.assistantResponse, isFromUser = false, timestamp = turn.timestamp + 1))
                            }
                            list
                        }
                        if (messages.isEmpty()) {
                            // Chat remains clean until initialized
                        } else {
                            chatAdapter.setMessages(messages)
                            chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
                        }
                    }
                }
            } catch (e: Exception) { Timber.e(e, "Failed to load chat history") }
        }
    }

    // --- GEMMA SERVICE CALLBACKS ---

    override fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean) {
        runOnUiThread {
            if (isUser || isComplete) {
                chatAdapter.addMessage(ChatMessage(message, isFromUser = isUser))
            } else {
                // Streaming update
                if (chatAdapter.itemCount > 0 && !isUser) {
                    chatAdapter.updateLastMessage(message)
                } else {
                    chatAdapter.addMessage(ChatMessage(message, isFromUser = isUser))
                }
            }
            chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    override fun onThoughtUpdated(thought: String) {
        if (isShowingDiary) {
            runOnUiThread {
                if (chatAdapter.itemCount > 0) {
                    chatAdapter.updateLastMessage(thought)
                } else {
                    chatAdapter.addMessage(ChatMessage(thought, isFromUser = false))
                }
                chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
    }

    override fun onThinkingStateChanged(isThinking: Boolean) {
        runOnUiThread {
            thinkingProgress?.visibility = if (isThinking) View.VISIBLE else View.GONE
            thinkingText?.visibility = if (isThinking) View.VISIBLE else View.GONE
            btnSend?.isEnabled = !isThinking
            chatInputText?.isEnabled = !isThinking
        }
    }

    private fun handlePickedImage(uri: Uri) {
        scope.launch {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    val downsampled = downsampleBitmap(bitmap, 1024)
                    gemmaService?.processMultimodalFromUi("Please analyze this image.", images = listOf(downsampled))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to process picked image")
            }
        }
    }

    private fun handleMicClick() {
        if (gemmaService == null) return
        
        scope.launch {
            try {
                onThinkingStateChanged(true)
                thinkingText?.text = "Δ Listening... ∇"
                
                // Record 5s of voice
                val audioBytes = gemmaService?.recordAudio(5)
                
                if (audioBytes != null) {
                    thinkingText?.text = "Δ Processing... ∇"
                    gemmaService?.processMultimodalFromUi("I am speaking to you now.", audio = audioBytes)
                } else {
                    onThinkingStateChanged(false)
                    android.widget.Toast.makeText(this@MainActivity, "Failed to record audio or permission denied", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Mic error")
                onThinkingStateChanged(false)
            }
        }
    }

    private fun downsampleBitmap(bitmap: android.graphics.Bitmap, maxDim: Int): android.graphics.Bitmap {
        if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
        val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
        return android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        if (::ttsManager.isInitialized) ttsManager.shutdown()
    }
}
