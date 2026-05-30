package com.ghost.api

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
import com.ghost.api.services.TTSManager
import com.ghost.api.ui.chat.ChatAdapter
import com.ghost.api.ui.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import java.io.File
import com.ghost.api.database.ConversationTurn

class MainActivity : ComponentActivity(), GemmaService.UiCallback {
    
    private lateinit var statusTextView: TextView
    private lateinit var actionButton: Button
    
    private var chatRecyclerView: RecyclerView? = null
    private var chatInputText: EditText? = null
    private var btnSend: TextView? = null
    private var thinkingProgress: ProgressBar? = null
    private var thinkingText: TextView? = null
    private var visualizer: com.ghost.api.ui.AudioVisualizerView? = null
    
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var ttsManager: TTSManager
    private var voiceController: com.ghost.api.ui.VoiceInputController? = null
    
    private var gemmaService: GemmaService? = null
    private var isBound = false
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var isRitualComplete = false
    private var isShowingDiary = false
    
    // Holds the picked image until the user types a prompt and hits send
    private var pendingImageBitmap: android.graphics.Bitmap? = null

    private val imagePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handlePickedImage(it) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as GemmaService.LocalBinder
            gemmaService = binder.getService()
            gemmaService?.uiCallback = this@MainActivity
            isBound = true
            transitionToChatUi()
            scope.launch {
                gemmaService?.isSystemReady?.collect { ready ->
                    if (ready) loadHistoricalChat()
                }
            }
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            gemmaService = null
        }
    }

    private val ttsStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.ghost.api.ACTION_TTS_START" -> {
                    visualizer?.visibility = View.VISIBLE
                    visualizer?.startAnimating()
                }
                "com.ghost.api.ACTION_TTS_STOP" -> {
                    visualizer?.stopAnimating()
                    visualizer?.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ttsManager = TTSManager(this)
        
        val filter = android.content.IntentFilter().apply {
            addAction("com.ghost.api.ACTION_TTS_START")
            addAction("com.ghost.api.ACTION_TTS_STOP")
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(ttsStateReceiver, filter, flags)
        
        setupLauncherUi()
    }
    
    private fun setupLauncherUi() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        statusTextView = TextView(this).apply { textSize = 18f; setTextColor(android.graphics.Color.WHITE); text = "Initializing..." }
        actionButton = Button(this).apply { text = "Check State"; visibility = View.GONE }
        layout.addView(statusTextView)
        layout.addView(actionButton)
        setContentView(layout)
    }
    
    override fun onResume() {
        super.onResume()
        if (!isRitualComplete) handler.postDelayed({ checkSystemState() }, 500)
    }

    private fun checkSystemState() {
        val perms = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_CALENDAR,
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE
        )
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            statusTextView.text = "Missing permissions: ${missing.size}"
            actionButton.text = "Grant Permissions"
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener { requestPermissions(missing.toTypedArray(), 100) }
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            statusTextView.text = "Storage access required to read model"
            actionButton.text = "Allow Storage"
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener { 
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) 
            }
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            statusTextView.text = "Overlay permission required"
            actionButton.text = "Enable Overlay"
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            return
        }

        completeRitual()
    }
    
    private fun completeRitual() {
        if (!isRitualComplete) {
            isRitualComplete = true
            ttsManager.speak("Online.")
            val intent = Intent(this, GemmaService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun transitionToChatUi() {
        setContentView(R.layout.activity_main_chat)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatInputText = findViewById(R.id.chatInputText)
        btnSend = findViewById(R.id.btnSend)
        thinkingProgress = findViewById(R.id.thinkingProgress)
        thinkingText = findViewById(R.id.thinkingText)
        visualizer = findViewById(R.id.visualizer)
        
        chatAdapter = ChatAdapter()
        chatRecyclerView?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
        }
        
        val btnMic = findViewById<TextView>(R.id.btnMic)
        voiceController = com.ghost.api.ui.VoiceInputController(
            context = this,
            micButton = btnMic ?: return,
            inputField = chatInputText ?: return,
            sparkleOrNull = null,
            onAudioReady = { audio ->
                gemmaService?.let { svc ->
                    scope.launch {
                        svc.processMultimodalFromUi("[Audio message received]", audio = audio)
                    }
                }
            },
            onTextReady = { text -> sendStagedMessage(text) }
        )

        chatInputText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                btnSend?.visibility = if (hasText) View.VISIBLE else View.GONE
                btnMic?.visibility  = if (hasText) View.GONE  else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnSend?.setOnClickListener {
            val text = chatInputText?.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                chatInputText?.setText("")
                sendStagedMessage(text)
            }
        }
        
        findViewById<TextView>(R.id.btnSparkle)?.setOnClickListener { imagePicker.launch("image/*") }
        val btnDropdown = findViewById<TextView>(R.id.btnMinimize)
        btnDropdown?.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.apply {
                add(0, 1, 0, "Transparent Wallpaper")
                add(0, 2, 0, "Edge Lights")
                add(0, 3, 0, "Milkdrop Wallpaper")
                add(0, 4, 0, "Passive Notification TTS")
                add(0, 5, 0, "Mute TTS")
                add(0, 6, 0, "Internal Diary")
                add(0, 7, 0, "Minimize")
            }
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        // Direct intent to wallpaper service — not a model prompt
                        sendBroadcast(Intent(this@MainActivity, com.ghost.api.hardware.HardwareToggleReceiver::class.java).apply { action = "com.ghost.api.ACTION_SET_TRANSPARENT_WALLPAPER" })
                        true
                    }
                    2 -> {
                        sendBroadcast(Intent(this@MainActivity, com.ghost.api.hardware.HardwareToggleReceiver::class.java).apply { action = "com.ghost.api.ACTION_TOGGLE_EDGE_LIGHTS" })
                        true
                    }
                    3 -> {
                        sendBroadcast(Intent(this@MainActivity, com.ghost.api.hardware.HardwareToggleReceiver::class.java).apply { action = "com.ghost.api.ACTION_SET_MILKDROP_WALLPAPER" })
                        true
                    }
                    4 -> { togglePassiveTts(); true }
                    5 -> { GemmaService.instance?.ttsManager?.stop(); true }
                    6 -> { /* TODO: switch to diary view */ true }
                    7 -> { moveTaskToBack(true); true }
                    else -> false
                }
            }
            popup.show()
        }
        
        loadHistoricalChat()
    }
    
    private fun loadHistoricalChat() {
        scope.launch {
            try {
                val history = gemmaService?.getRecentTurns(50) ?: return@launch
                withContext(Dispatchers.Main) {
                    val messages = history.sortedBy { it.timestamp }.flatMap { turn ->
                        val list = mutableListOf<ChatMessage>()
                        if (turn.userMessage.isNotEmpty()) list.add(ChatMessage(turn.userMessage, isFromUser = true, timestamp = turn.timestamp))
                        if (turn.assistantResponse.isNotEmpty()) list.add(ChatMessage(turn.assistantResponse, isFromUser = false, timestamp = turn.timestamp + 1))
                        list
                    }
                    chatAdapter.setMessages(messages)
                    chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
                }
            } catch (e: Exception) { Timber.e(e) }
        }
    }
    override fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean) {
        runOnUiThread {
            if (isUser) {
                chatAdapter.addMessage(ChatMessage(message, isFromUser = true))
            } else {
                val last = chatAdapter.getLastMessage()
                if (last != null && !last.isFromUser && !last.isComplete) {
                    chatAdapter.updateLastMessage(message, isComplete, null, null, null)
                } else {
                    chatAdapter.addMessage(ChatMessage(message, isFromUser = false, isComplete = isComplete))
                }
            }
            chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    override fun onThoughtUpdated(thought: String) {
        runOnUiThread { thinkingText?.text = "Processing: $thought" }
    }

    override fun onThinkingStateChanged(isThinking: Boolean) {
        runOnUiThread {
            thinkingProgress?.visibility = if (isThinking) View.VISIBLE else View.GONE
            thinkingText?.visibility = if (isThinking) View.VISIBLE else View.GONE
            if (isThinking) {
                val ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                thinkingText?.text = "Thinking... $ts"
            }
        }
    }

    private fun togglePassiveTts() {
        val prefs = getSharedPreferences(com.ghost.api.Constants.PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getBoolean(com.ghost.api.Constants.PREF_PASSIVE_TTS, true)
        prefs.edit().putBoolean(com.ghost.api.Constants.PREF_PASSIVE_TTS, !current).apply()
        val label = if (!current) "Passive TTS on" else "Passive TTS off"
        android.widget.Toast.makeText(this, label, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun sendStagedMessage(text: String) {
        val bitmap = pendingImageBitmap
        if (bitmap != null) {
            scope.launch {
                gemmaService?.processMultimodalFromUi(text, listOf(bitmap))
                withContext(Dispatchers.Main) {
                    pendingImageBitmap = null
                    chatInputText?.hint = "Δ \uD83D\uDC7E ∇" // Revert to motif
                    Toast.makeText(this@MainActivity, "Sent with image", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            gemmaService?.processQueryFromUi(text)
            Toast.makeText(this@MainActivity, "Transmission sent", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePickedImage(uri: Uri) {
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val reqWidth = 1024
                    val reqHeight = 1024
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, this) }
                        
                        val height: Int = outHeight
                        val width: Int = outWidth
                        var inSampleSize = 1
                        if (height > reqHeight || width > reqWidth) {
                            val halfHeight = height / 2
                            val halfWidth = width / 2
                            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                                inSampleSize *= 2
                            }
                        }
                        this.inSampleSize = inSampleSize
                        inJustDecodeBounds = false
                    }
                    contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                }
                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        pendingImageBitmap = bitmap
                        chatInputText?.hint = "[📎 Image attached]"
                        Toast.makeText(this@MainActivity, "Image staged. Type a prompt and send.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { Timber.e(e) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(serviceConnection)
        ttsManager.shutdown()
        unregisterReceiver(ttsStateReceiver)
    }
}
