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

data class ChatMessage(
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatAdapter(private val messages: MutableList<ChatMessage> = mutableListOf()) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.US)

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: LinearLayout = view.findViewById(R.id.messageContainer)
        val bubbleLayout: LinearLayout = view.findViewById(R.id.bubbleLayout)
        val textMessage: TextView = view.findViewById(R.id.textMessage)
        val textTimestamp: TextView = view.findViewById(R.id.textTimestamp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.textMessage.text = message.content
        holder.textTimestamp.text = timeFormat.format(Date(message.timestamp))
        holder.textTimestamp.visibility = View.VISIBLE

        val isUser = message.isFromUser
        
        // Align left (Gemma) or right (User)
        holder.container.gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
        
        // Apply appropriate background drawable
        val bgDrawable = if (isUser) R.drawable.chat_bubble_user else R.drawable.chat_bubble_gemma
        holder.bubbleLayout.background = ContextCompat.getDrawable(holder.itemView.context, bgDrawable)
        
        // Apply appropriate text colors (Matrix Green for AI, Cyan for User)
        val textColorId = if (isUser) R.color.user_cyan else R.color.matrix_green
        holder.textMessage.setTextColor(ContextCompat.getColor(holder.itemView.context, textColorId))
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
    
    fun setMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }
}
