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

/**
 * The Advanced Oracle_OS Gateway
 * Handles Permissions, then spawns the Native Chat Interface seamlessly hooking into GemmaService.
 */
class MainActivity : Activity(), GemmaService.UiCallback {
    
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
        showState("✅ SYSTEM GREEN\n\nSovereignty Achieved.", "LAUNCHING...") { }
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
           ttsManager.speak("Online.")
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
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
        val btnSettings = findViewById<android.view.View>(R.id.btnSettings)
        
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
                    else -> false
                }
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
        
        loadHistoricalChat()
    }
    
    private fun loadHistoricalChat() {
        scope.launch {
            try {
                // Fetch the last 20 messages for the UI purely from the SQLite DB bound in GemmaService
                val history = gemmaService?.getRecentConversationHistory(20) ?: emptyList()
                val messages = mutableListOf<ChatMessage>()
                // Since history is [newest..oldest], reverse it to [oldest..newest] for standard logical flow
                for ((userMsg, gemmaMsg) in history.reversed()) {
                    messages.add(ChatMessage(userMsg, isFromUser = true))
                    messages.add(ChatMessage(gemmaMsg, isFromUser = false))
                }
                withContext(Dispatchers.Main) {
                    if (messages.isNotEmpty()) {
                        chatAdapter.setMessages(messages)
                        chatRecyclerView?.scrollToPosition(messages.size - 1)
                    } else {
                        // Empty state initial greeting
                        chatAdapter.addMessage(ChatMessage("Hello! I am completely integrated natively now. How can I help?", isFromUser = false))
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load chat history")
            }
        }
    }

    // --- GEMMA SERVICE CALLBACKS ---

    override fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean) {
        runOnUiThread {
            chatAdapter.addMessage(ChatMessage(message, isFromUser = isUser))
            chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
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

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        if (::ttsManager.isInitialized) ttsManager.shutdown()
    }
}
