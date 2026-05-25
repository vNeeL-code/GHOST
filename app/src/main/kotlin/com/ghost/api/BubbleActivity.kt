package com.ghost.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ghost.api.ui.chat.ChatAdapter
import com.ghost.api.ui.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bubble Activity — shown inside the Android OS floating bubble head.
 * Uses the real chat UI (activity_main_chat) and binds to GemmaService,
 * exactly like MainActivity does, but without menus/wallpaper/diary extras.
 */
class BubbleActivity : ComponentActivity(), GemmaService.UiCallback {

    private var chatRecyclerView: RecyclerView? = null
    private var chatInputText: EditText? = null
    private var thinkingProgress: ProgressBar? = null
    private var thinkingText: TextView? = null
    private lateinit var chatAdapter: ChatAdapter
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var gemmaService: GemmaService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as GemmaService.LocalBinder
            gemmaService = binder.getService()
            gemmaService?.uiCallback = this@BubbleActivity
            isBound = true
            Timber.i("BubbleActivity bound to GemmaService")
            setupChatUi()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            gemmaService?.uiCallback = null
            gemmaService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                setLocusContext(android.content.LocusId("ghost_convo_final"), null)
            } catch (e: Exception) {
                Timber.w(e, "Locus context failed")
            }
        }

        setContentView(R.layout.activity_main_chat)

        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatInputText = findViewById(R.id.chatInputText)
        thinkingProgress = findViewById(R.id.thinkingProgress)
        thinkingText = findViewById(R.id.thinkingText)

        // Hide controls that don't belong in the bubble
        findViewById<android.view.View>(R.id.btnMinimize)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.titleText)?.visibility = android.view.View.GONE

        chatAdapter = ChatAdapter()
        chatRecyclerView?.apply {
            layoutManager = LinearLayoutManager(this@BubbleActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }

        // Bind to the already-running service (BIND_AUTO_CREATE = false so we don't double-start)
        val intent = Intent(this, GemmaService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupChatUi() {
        val btnSend = findViewById<TextView>(R.id.btnSend)
        val btnMic = findViewById<TextView>(R.id.btnMic)

        chatInputText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                btnSend?.visibility = if (hasText) android.view.View.VISIBLE else android.view.View.GONE
                btnMic?.visibility = if (hasText) android.view.View.GONE else android.view.View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnSend?.setOnClickListener {
            val text = chatInputText?.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                chatInputText?.setText("")
                gemmaService?.processQueryFromUi(text)
            }
        }

        // Load recent history so bubble isn't blank
        scope.launch {
            try {
                val history = gemmaService?.getRecentTurns(50) ?: return@launch
                val messages = history.sortedBy { it.timestamp }.flatMap { turn ->
                    val list = mutableListOf<ChatMessage>()
                    if (turn.userMessage.isNotEmpty()) list.add(ChatMessage(turn.userMessage, isFromUser = true, timestamp = turn.timestamp))
                    if (turn.assistantResponse.isNotEmpty()) list.add(ChatMessage(turn.assistantResponse, isFromUser = false, timestamp = turn.timestamp + 1))
                    list
                }
                if (messages.isNotEmpty()) {
                    chatAdapter.setMessages(messages)
                    chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load bubble chat history")
            }
        }
    }

    // UiCallback — keep the bubble live with streaming responses
    override fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean) {
        runOnUiThread {
            if (isUser) {
                chatAdapter.addMessage(ChatMessage(message, isFromUser = true))
            } else if (isComplete) {
                val last = chatAdapter.getLastMessage()
                if (last != null && !last.isFromUser && !last.isComplete) {
                    chatAdapter.updateLastMessage(message, isComplete, null, null, null)
                } else {
                    chatAdapter.addMessage(ChatMessage(message, isFromUser = false, isComplete = isComplete))
                }
            } else {
                val last = chatAdapter.getLastMessage()
                if (last != null && !last.isFromUser) {
                    chatAdapter.updateLastMessage(message, false, null)
                } else {
                    chatAdapter.addMessage(ChatMessage(message, isFromUser = false))
                }
            }
            chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    override fun onThinkingStateChanged(isThinking: Boolean) {
        runOnUiThread {
            thinkingProgress?.visibility = if (isThinking) android.view.View.VISIBLE else android.view.View.GONE
            thinkingText?.visibility = if (isThinking) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    override fun onThoughtUpdated(thought: String) {
        // Not shown in bubble — keeps UI clean
    }

    override fun onDestroy() {
        if (isBound) {
            // Only clear uiCallback if we own it
            if (gemmaService?.uiCallback === this) {
                gemmaService?.uiCallback = null
            }
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
