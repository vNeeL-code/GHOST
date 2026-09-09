package com.ghost.api

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.ghost.api.hardware.HardwareToggleReceiver
import com.ghost.api.services.TTSManager
import com.ghost.api.ui.AudioVisualizerView
import com.ghost.api.ui.EdgeLightsManager
import com.ghost.api.ui.chat.ChatMessage
import com.ghost.api.ui.screens.ChatScreen
import com.ghost.api.ui.theme.GHOSTTheme
import com.ghost.api.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

class MainActivity : ComponentActivity(), GemmaService.UiCallback {
    
    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var ttsManager: TTSManager
    
    private var gemmaService: GemmaService? = null
    private var isBound = false
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val handler = Handler(Looper.getMainLooper())
    private var isRitualComplete = false
    
    private var audioVisualizerView: AudioVisualizerView? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handlePickedImage(it) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as GemmaService.LocalBinder
            val svc = binder.getService()
            gemmaService = svc
            svc.uiCallback = this@MainActivity
            isBound = true

            val downloadState = svc.modelDownloader.downloadStatus.value
            if (downloadState is ModelDownloader.DownloadState.Downloading) {
                val mbDone = downloadState.bytesDownloaded / 1024 / 1024
                val mbTotal = downloadState.totalBytes / 1024 / 1024
                chatViewModel.setDownloadProgress("Downloading Weights: ${downloadState.progressPercent}% (${mbDone}MB / ${mbTotal}MB)")
            }

            scope.launch {
                svc.isSystemReady.collect { ready ->
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
                    chatViewModel.setTtsActive(true)
                    audioVisualizerView?.visibility = View.VISIBLE
                    audioVisualizerView?.startAnimating()
                }
                "com.ghost.api.ACTION_TTS_STOP" -> {
                    chatViewModel.setTtsActive(false)
                    audioVisualizerView?.stopAnimating()
                    audioVisualizerView?.visibility = View.GONE
                }
                "com.ghost.api.ACTION_DIARY_ENTRY_POSTED" -> {
                    Timber.i("📔 Diary entry recorded and persisted to storage")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        ttsManager = TTSManager(this)
        
        val filter = android.content.IntentFilter().apply {
            addAction("com.ghost.api.ACTION_TTS_START")
            addAction("com.ghost.api.ACTION_TTS_STOP")
            addAction("com.ghost.api.ACTION_DIARY_ENTRY_POSTED")
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(ttsStateReceiver, filter, flags)
        
        // Ensure Compose UI renders immediately
        setContent {
            GHOSTTheme {
                val messages by chatViewModel.messages.collectAsState()
                val isThinking by chatViewModel.isThinking.collectAsState()
                val thinkingText by chatViewModel.thinkingText.collectAsState()
                val attachedImage by chatViewModel.attachedImage.collectAsState()
                val isTtsActive by chatViewModel.isTtsActive.collectAsState()
                val downloadProgress by chatViewModel.downloadProgress.collectAsState()

                var showSettings by remember { mutableStateOf(false) }

                ChatScreen(
                    messages = messages,
                    isThinking = isThinking,
                    thinkingText = thinkingText,
                    attachedImage = attachedImage,
                    isTtsActive = isTtsActive,
                    downloadProgress = downloadProgress,
                    onSendMessage = { text ->
                        sendStagedMessage(text)
                    },
                    onSendAudio = { audio ->
                        handleSendAudio(audio)
                    },
                    onPickImage = {
                        imagePicker.launch("image/*")
                    },
                    onClearImage = {
                        chatViewModel.setAttachedImage(null)
                    },
                    onToggleThinking = { message ->
                        // Future reasoning toggle expansion
                    },
                    onOpenSettings = {
                        showSettings = true
                    },
                    onPlayMessage = { text ->
                        ttsManager.forceSpeak(text)
                    },
                    visualizerViewFactory = { context ->
                        AudioVisualizerView(context).apply {
                            audioVisualizerView = this
                            visibility = View.GONE
                        }
                    }
                )

                if (showSettings) {
                    com.ghost.api.ui.screens.SettingsDialog(
                        onDismiss = { showSettings = false },
                        onShowTutorial = {
                            showSettings = false
                            showTutorialDialog()
                        },
                        onShowDiaryHistory = {
                            showSettings = false
                            scope.launch {
                                val entries = gemmaService?.memoryManager?.getRecentDiaryEntries(25) ?: emptyList()
                                withContext(Dispatchers.Main) {
                                    showDiaryHistoryDialog(entries)
                                }
                            }
                        },
                        gemmaService = gemmaService ?: GemmaService.instance
                    )
                }
            }
        }

        completeRitual()

        // Request runtime permissions non-blockingly after initial frame layout
        handler.postDelayed({
            checkAndRequestPermissions()
        }, 500)
    }
    
    override fun onResume() {
        super.onResume()
        completeRitual()
    }

    override fun onDownloadProgress(progressText: String?) {
        lifecycleScope.launch(Dispatchers.Main) {
            chatViewModel.setDownloadProgress(progressText)
        }
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
                    chatViewModel.setMessages(messages)
                }
            } catch (e: Exception) { Timber.e(e) }
        }
    }

    override fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean, image: android.graphics.Bitmap?) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isUser) {
                chatViewModel.addMessage(ChatMessage(message, isFromUser = true, image = image))
            } else {
                val current = chatViewModel.messages.value
                val last = current.lastOrNull()
                if (last != null && !last.isFromUser && !last.isComplete) {
                    chatViewModel.updateLastMessage(message)
                } else {
                    chatViewModel.addMessage(ChatMessage(message, isFromUser = false, isComplete = isComplete))
                }
            }
        }
    }

    override fun onThoughtUpdated(thought: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            chatViewModel.setThinking(true, "Processing: $thought")
        }
    }

    override fun onThinkingStateChanged(isThinking: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            val ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            chatViewModel.setThinking(isThinking, if (isThinking) "Thinking... $ts" else "")
        }
    }

    private fun sendStagedMessage(text: String) {
        val bitmap = chatViewModel.attachedImage.value
        chatViewModel.setAttachedImage(null)
        if (bitmap != null) {
            scope.launch {
                gemmaService?.processMultimodalFromUi(text, listOf(bitmap))
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Sent with image", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            gemmaService?.processQueryFromUi(text)
            Toast.makeText(this@MainActivity, "Transmission sent", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSendAudio(audio: ByteArray) {
        scope.launch {
            gemmaService?.processMultimodalFromUi("[Audio message received]", audio = audio)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Voice audio transmitted", Toast.LENGTH_SHORT).show()
            }
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
                        chatViewModel.setAttachedImage(bitmap)
                        Toast.makeText(this@MainActivity, "Image staged. Type a prompt and send.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { Timber.e(e) }
        }
    }

    private fun showTutorialDialog() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 36)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#141418"))
                cornerRadius = 32f
                setStroke(1, Color.parseColor("#4D8BB4F6"))
            }
        }

        val titleView = TextView(this).apply {
            text = "🎓 GHOST Tutorial Programme"
            textSize = 16f
            setTextColor(Color.parseColor("#8BB4F6"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        root.addView(titleView)

        val stepBadge = TextView(this).apply {
            text = "Step 1 of ${com.ghost.api.services.TutorialManager.steps.size}: Introduction"
            textSize = 12f
            setTextColor(Color.parseColor("#F97316"))
            setPadding(0, 0, 0, 16)
        }
        root.addView(stepBadge)

        val bodyView = TextView(this).apply {
            text = com.ghost.api.services.TutorialManager.steps.first().text
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setLineSpacing(6f, 1f)
            setPadding(0, 0, 0, 24)
        }
        root.addView(bodyView)

        // Control Buttons Row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val createButtonBg = {
            android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#262630"))
                cornerRadius = 20f
                setStroke(1, Color.parseColor("#33FFFFFF"))
            }
        }

        val pauseResumeBtn = Button(this).apply {
            text = "⏸ Pause"
            setTextColor(Color.WHITE)
            background = createButtonBg()
            setOnClickListener {
                if (com.ghost.api.services.TutorialManager.isPaused) {
                    com.ghost.api.services.TutorialManager.resume()
                    text = "⏸ Pause"
                } else {
                    com.ghost.api.services.TutorialManager.pause()
                    text = "▶ Resume"
                }
            }
        }

        val skipBtn = Button(this).apply {
            text = "⏭ Skip"
            setTextColor(Color.WHITE)
            background = createButtonBg()
            setOnClickListener {
                com.ghost.api.services.TutorialManager.next()
                pauseResumeBtn.text = "⏸ Pause"
            }
        }

        btnRow.addView(pauseResumeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 12 })
        btnRow.addView(skipBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnRow)

        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(root)
            .setPositiveButton("⏹ Stop & Dismiss") { _, _ ->
                com.ghost.api.services.TutorialManager.stop()
            }
            .setOnDismissListener {
                com.ghost.api.services.TutorialManager.stop()
            }
            .create()
            .apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                show()
            }

        com.ghost.api.services.TutorialManager.start { step, cur, total ->
            runOnUiThread {
                stepBadge.text = "Step $cur of $total: ${step.title}"
                bodyView.text = step.text
            }
        }
    }

    private fun showDiaryHistoryDialog(entries: List<com.ghost.api.database.DiaryEntry>) {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        root.addView(container)

        // Filter for human-readable diary/dream reflections (skipping legacy JSON dumps)
        val validEntries = entries.filter {
            !it.observation.trim().startsWith("{") && !it.observation.trim().startsWith("Session distilled:")
        }

        container.addView(TextView(this).apply {
            text = "✧ Gemma Diary Logs (${validEntries.size})"
            textSize = 16f
            setTextColor(Color.parseColor("#8BB4F6"))
            letterSpacing = 0.1f
            setPadding(0, 0, 0, 24)
        })

        if (validEntries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No dream diary entries yet. Tap 'Trigger Diary Log Now' or let the autonomous worker run at noon/midnight."
                textSize = 13f
                setTextColor(Color.parseColor("#99FFFFFF"))
            })
        } else {
            val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy • h:mm a", Locale.US)
            for (entry in validEntries) {
                val dateStr = sdf.format(Date(entry.timestamp))
                val cleanBody = entry.observation
                    .replace(Regex("^✧ Gemma 📔\\s*"), "")
                    .replace(Regex("^✧ Diary Entry:[^\\n]*\\n"), "")
                    .trim()

                // Entry card container
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 24, 32, 24)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#141414"))
                        cornerRadius = 24f
                        setStroke(1, Color.parseColor("#26FFFFFF"))
                    }
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    params.bottomMargin = 24
                    layoutParams = params
                }

                card.addView(TextView(this).apply {
                    text = "Δ 👾 ∇ • $dateStr"
                    textSize = 11f
                    setTextColor(Color.parseColor("#8BB4F6"))
                    setPadding(0, 0, 0, 8)
                })

                card.addView(TextView(this).apply {
                    text = cleanBody
                    textSize = 13.5f
                    setTextColor(Color.WHITE)
                    setLineSpacing(6f, 1f)
                })

                container.addView(card)
            }
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(root)
            .setPositiveButton("Close", null)
            .create()
            .apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                show()
            }
    }

    private var hasRequestedInitialPermissions = false

    private val standardPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] ?: false
        val writeCalendarGranted = permissions[android.Manifest.permission.WRITE_CALENDAR] ?: false
        Timber.i("Standard permissions result - Audio: $recordAudioGranted, Calendar: $writeCalendarGranted")
    }

    private fun checkAndRequestPermissions() {
        if (hasRequestedInitialPermissions) return
        hasRequestedInitialPermissions = true

        val permissionsToRequest = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.READ_CALENDAR)
            permissionsToRequest.add(android.Manifest.permission.WRITE_CALENDAR)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            standardPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        if (isBound) unbindService(serviceConnection)
        ttsManager.shutdown()
        unregisterReceiver(ttsStateReceiver)
    }
}
