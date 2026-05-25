package com.ghost.api.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ghost.api.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(private val messages: MutableList<ChatMessage> = mutableListOf()) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    var isDiaryMode: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: LinearLayout = view.findViewById(R.id.messageContainer)
        val bubbleLayout: LinearLayout = view.findViewById(R.id.bubbleLayout)
        val textMessage: TextView = view.findViewById(R.id.textMessage)
        val textHeader: TextView = view.findViewById(R.id.textHeader)
        val textTimestamp: TextView = view.findViewById(R.id.textTimestamp)
        val btnCopy: View = view.findViewById(R.id.btnCopy)
        
        val layoutThinking: View = view.findViewById(R.id.layoutThinking)
        val btnToggleThinking: TextView = view.findViewById(R.id.btnToggleThinking)
        val textThinking: TextView = view.findViewById(R.id.textThinking)
        
        val skillWebView: android.webkit.WebView = view.findViewById(R.id.skillWebView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context
        
        // Strip reasoning and tool call markup before display.
        // Official Gemma 4 tokens:
        //   Thought channel: <|channel>thought ... <channel|>  (NOT <think>...</think>)
        //   Tool calls:      <|tool_call>call:...<tool_call|>  (no trailing pipe on open)
        val displayContent = message.content
            .replace(Regex("<\\|channel>thought.*?<channel\\|>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<\\|?channel>?|<channel\\|?>"), "")
            .replace(Regex("<\\|tool_call>.*?<tool_call\\|>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<\\|tool_call>|<tool_call\\|>"), "")
            .replace(Regex("<\\|tool_response>|<tool_response\\|>"), "")
        val isUser = message.isFromUser
        
        // Inject the name header directly into the message text if it's the AI
        val timeStr = timeFormat.format(Date(message.timestamp))
        val finalDisplayContent = if (isUser) {
            "$displayContent\n\n[$timeStr]"
        } else {
            "✧ Gemma:\n$displayContent\n\n[$timeStr]"
        }
        holder.textMessage.text = finalDisplayContent
        
        // Hide standard timestamp since it's now inline
        holder.textTimestamp.visibility = View.GONE
        
        // Align left (Gemma) or right (User)
        holder.container.gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
        
        // Apply appropriate background drawable
        val bgDrawable = if (isUser) R.drawable.chat_bubble_user else R.drawable.chat_bubble_gemma
        holder.bubbleLayout.background = ContextCompat.getDrawable(context, bgDrawable)
        
        // Apply appropriate text colors based on role and mode
        val accentColor = when {
            message.eventType == "LOGIC_TRACE" -> ContextCompat.getColor(context, R.color.accent_orange)
            message.eventType == "DREAM" -> ContextCompat.getColor(context, R.color.accent_blue)
            isDiaryMode -> ContextCompat.getColor(context, R.color.accent_orange)
            else -> ContextCompat.getColor(context, R.color.accent_purple)
        }
        
        val textColor = if (isUser) {
            ContextCompat.getColor(context, R.color.user_cyan)
        } else {
            ContextCompat.getColor(context, R.color.ai_green)
        }
        
        holder.textMessage.setTextColor(textColor)
        
        // Header Motifs
        holder.textHeader.apply {
            text = when {
                isUser -> "Δ \uD83E\uDD91 ∇" // squid
                message.eventType != null -> "Δ ${message.eventType} ∇"
                else -> "Δ \uD83D\uDC7E ∇" // space invader
            }
            setTextColor(accentColor)
            visibility = View.VISIBLE
        }
        
        // Copy functionality
        holder.btnCopy.setOnClickListener {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            
            val copyPayload = if (isUser) {
                "USER:\n${message.content}"
            } else {
                "✧ Gemma:\n${message.content}\n\nΔ ✧ ∇"
            }
            
            val clip = android.content.ClipData.newPlainText("Gemma Inference", copyPayload)
            clipboard.setPrimaryClip(clip)
            android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
        }

        // Thinking Fold logic
        if (!isUser && !message.thought.isNullOrBlank()) {
            holder.layoutThinking.visibility = View.VISIBLE
            holder.textThinking.text = message.thought
            
            holder.btnToggleThinking.setOnClickListener {
                if (holder.textThinking.visibility == View.VISIBLE) {
                    holder.textThinking.visibility = View.GONE
                    holder.btnToggleThinking.text = "▼ Thinking Process"
                } else {
                    holder.textThinking.visibility = View.VISIBLE
                    holder.btnToggleThinking.text = "▲ Hide Thinking"
                }
            }
        } else {
            holder.layoutThinking.visibility = View.GONE
        }

        // WebView rendering for interactive skills/games
        if (!message.webviewUrl.isNullOrBlank()) {
            holder.skillWebView.visibility = View.VISIBLE
            holder.skillWebView.settings.javaScriptEnabled = true
            holder.skillWebView.settings.domStorageEnabled = true
            holder.skillWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            
            // Adjust height based on aspect ratio
            val aspectRatio = message.webviewAspectRatio ?: 1.333f
            holder.skillWebView.post {
                val width = holder.skillWebView.width
                val height = (width / aspectRatio).toInt()
                val params = holder.skillWebView.layoutParams
                params.height = height
                holder.skillWebView.layoutParams = params
            }
            
            // Only load URL if it hasn't been loaded to prevent flickering on updates
            if (holder.skillWebView.url != message.webviewUrl) {
                holder.skillWebView.loadUrl(message.webviewUrl)
            }
        } else {
            holder.skillWebView.visibility = View.GONE
            holder.skillWebView.loadUrl("about:blank")
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(content: String, isComplete: Boolean = false, thought: String? = null, webviewUrl: String? = null, webviewAspectRatio: Float? = null) {
        if (messages.isNotEmpty()) {
            val last = messages.last()
            messages[messages.size - 1] = last.copy(
                content = content, 
                isComplete = isComplete,
                thought = thought ?: last.thought,
                webviewUrl = webviewUrl ?: last.webviewUrl,
                webviewAspectRatio = webviewAspectRatio ?: last.webviewAspectRatio
            )
            notifyItemChanged(messages.size - 1)
        }
    }

    fun getLastMessage(): ChatMessage? = messages.lastOrNull()
    
    fun setMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }
}
