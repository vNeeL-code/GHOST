package com.ghost.api.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ghost.api.ui.chat.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()
    
    private val _thinkingText = MutableStateFlow("")
    val thinkingText: StateFlow<String> = _thinkingText.asStateFlow()

    private val _attachedImages = MutableStateFlow<List<android.graphics.Bitmap>>(emptyList())
    val attachedImages: StateFlow<List<android.graphics.Bitmap>> = _attachedImages.asStateFlow()

    private val _attachedImagePaths = MutableStateFlow<List<String>>(emptyList())
    val attachedImagePaths: StateFlow<List<String>> = _attachedImagePaths.asStateFlow()

    private val _attachedImage = MutableStateFlow<android.graphics.Bitmap?>(null)
    val attachedImage: StateFlow<android.graphics.Bitmap?> = _attachedImage.asStateFlow()

    private val _attachedImagePath = MutableStateFlow<String?>(null)
    val attachedImagePath: StateFlow<String?> = _attachedImagePath.asStateFlow()

    private val _isTtsActive = MutableStateFlow(false)
    val isTtsActive: StateFlow<Boolean> = _isTtsActive.asStateFlow()

    private val _downloadProgress = MutableStateFlow<String?>(null)
    val downloadProgress: StateFlow<String?> = _downloadProgress.asStateFlow()

    fun setDownloadProgress(progress: String?) {
        _downloadProgress.value = progress
    }

    fun setTtsActive(active: Boolean) {
        _isTtsActive.value = active
    }

    fun setAttachedImage(bitmap: android.graphics.Bitmap?, imagePath: String? = null) {
        _attachedImage.value = bitmap
        _attachedImagePath.value = imagePath
        _attachedImages.value = listOfNotNull(bitmap)
        _attachedImagePaths.value = listOfNotNull(imagePath)
    }

    fun setAttachedImages(bitmaps: List<android.graphics.Bitmap>, imagePaths: List<String> = emptyList()) {
        _attachedImages.value = bitmaps.take(2)
        _attachedImagePaths.value = imagePaths.take(2)
        _attachedImage.value = bitmaps.firstOrNull()
        _attachedImagePath.value = imagePaths.firstOrNull()
    }

    fun clearAttachedImages() {
        _attachedImages.value = emptyList()
        _attachedImagePaths.value = emptyList()
        _attachedImage.value = null
        _attachedImagePath.value = null
    }

    fun setMessages(newMessages: List<ChatMessage>) {
        _messages.value = newMessages
    }

    fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    fun updateLastMessage(content: String, thought: String? = null, isComplete: Boolean = true) {
        val current = _messages.value
        if (current.isNotEmpty() && !current.last().isFromUser) {
            val last = current.last()
            val updated = current.toMutableList().apply {
                this[size - 1] = last.copy(content = content, thought = thought, isComplete = isComplete)
            }
            _messages.value = updated
        }
    }

    fun setThinking(thinking: Boolean, text: String = "") {
        _isThinking.value = thinking
        _thinkingText.value = text
    }
}
