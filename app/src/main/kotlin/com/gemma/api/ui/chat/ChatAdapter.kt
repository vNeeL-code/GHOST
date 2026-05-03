package com.gemma.api.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gemma.api.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(private val messages: MutableList<ChatMessage> = mutableListOf()) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val timeFormat = SimpleDateFormat("'Δ' yyyy-MM-dd'T'HH:mm:ss'Z' '∇'", Locale.US)

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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context
        
        holder.textMessage.text = message.content
        holder.textTimestamp.text = timeFormat.format(Date(message.timestamp))
        holder.textTimestamp.visibility = View.VISIBLE

        val isUser = message.isFromUser
        
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
                isUser -> "Δ 🦑 ∇"
                message.eventType != null -> "Δ ${message.eventType} ∇"
                else -> "Δ 👾 ∇"
            }
            setTextColor(accentColor)
            visibility = View.VISIBLE
        }
        
        // Copy functionality
        holder.btnCopy.setOnClickListener {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Gemma Inference", message.content)
            clipboard.setPrimaryClip(clip)
            android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(content: String) {
        if (messages.isNotEmpty()) {
            val last = messages.last()
            messages[messages.size - 1] = last.copy(content = content)
            notifyItemChanged(messages.size - 1)
        }
    }
    
    fun setMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }
}
