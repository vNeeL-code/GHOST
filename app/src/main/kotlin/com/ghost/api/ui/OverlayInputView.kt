package com.ghost.api.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import timber.log.Timber

class OverlayInputView(
    context: Context,
    private val onSend: (String) -> Unit
) : FrameLayout(context) {

    private val inputField: EditText
    private val micButton: Button
    private val sendButton: Button

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    init {
        // Premium glassmorphism-style background
        setBackgroundResource(com.ghost.api.R.drawable.thinking_bg)
        setPadding(24, 24, 24, 24)
        
        // Ensure rounded corners are clipped
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            clipToOutline = true
            elevation = 8f
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        // Mic button (native STT)
        micButton = Button(context).apply {
            text = "🎤"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                toggleVoiceInput()
            }
        }

        inputField = EditText(context).apply {
            hint = "Δ 👾 ∇"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            inputType = EditorInfo.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
        }

        sendButton = Button(context).apply {
            text = "➤"
            textSize = 20f
            setTextColor(Color.CYAN)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                handleSend()
            }
        }

        inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                handleSend()
                true
            } else {
                false
            }
        }

        container.addView(micButton)
        container.addView(inputField)
        container.addView(sendButton)
        addView(container)

        // Initialize speech recognizer
        initSpeechRecognizer()
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Timber.w("Speech recognition not available")
            micButton.isEnabled = false
            micButton.alpha = 0.5f
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Timber.d("STT ready")
                    micButton.text = "🔴"
                    micButton.setTextColor(Color.RED)
                }

                override fun onBeginningOfSpeech() {
                    Timber.d("STT speech started")
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Timber.d("STT speech ended")
                    resetMicButton()
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        else -> "STT error: $error"
                    }
                    Timber.w("STT error: $errorMsg")
                    resetMicButton()
                    if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                        Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        Timber.i("STT result: $text")
                        // Append to existing text or replace
                        val existing = inputField.text.toString()
                        if (existing.isNotEmpty()) {
                            inputField.setText("$existing $text")
                        } else {
                            inputField.setText(text)
                        }
                        inputField.setSelection(inputField.text.length)
                    }
                    resetMicButton()
                    isListening = false
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        // Show partial in hint or as preview
                        inputField.hint = text
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun toggleVoiceInput() {
        if (isListening) {
            speechRecognizer?.stopListening()
            resetMicButton()
            isListening = false
        } else {
            startVoiceInput()
        }
    }

    private fun startVoiceInput() {
        val recognizer = speechRecognizer ?: return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            isListening = true
            recognizer.startListening(intent)
            Timber.i("STT started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start STT")
            resetMicButton()
            isListening = false
        }
    }

    private fun resetMicButton() {
        micButton.text = "🎤"
        micButton.setTextColor(Color.WHITE)
        inputField.hint = "Δ 👾 ∇"
    }

    private fun handleSend() {
        // Stop any ongoing STT
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
        }

        val text = inputField.text.toString().trim()
        if (text.isNotEmpty()) {
            onSend(text)
            inputField.text.clear()
        }
    }

    fun focusInput() {
        inputField.requestFocus()
    }

    fun cleanup() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    fun appendText(text: String) {
        val existing = inputField.text.toString()
        if (existing.isNotEmpty()) {
            inputField.setText("$existing $text")
        } else {
            inputField.setText(text)
        }
        inputField.setSelection(inputField.text.length)
    }
}
