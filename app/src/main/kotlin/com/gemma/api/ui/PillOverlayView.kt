package com.gemma.api.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import timber.log.Timber

/**
 * Pill-style expandable overlay (Gemini-inspired)
 *
 * States:
 * - COLLAPSED: Small pill showing mic + status indicator
 * - LISTENING: Pill with pulsing mic, partial results shown
 * - EXPANDED: Full input field + response area
 * - THINKING: Expanded with loading animation
 * - RESPONSE: Expanded showing AI response
 */
class PillOverlayView(
    context: Context,
    private val onQuery: (String) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    // UI Components
    private lateinit var pillContainer: LinearLayout
    private lateinit var micButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var expandedContainer: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var responseArea: TextView
    private lateinit var thinkingIndicator: ProgressBar

    // State
    private var currentState: State = State.COLLAPSED
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // Dimensions (dp -> px)
    private val pillHeightCollapsed = dpToPx(56)
    private val pillWidthCollapsed = dpToPx(180)
    private val pillWidthExpanded = dpToPx(340)
    private val pillHeightExpanded = dpToPx(200)

    // Colors
    private val colorPrimary = Color.parseColor("#1A73E8")  // Google Blue
    private val colorSurface = Color.parseColor("#2D2D2D")
    private val colorSurfaceVariant = Color.parseColor("#3D3D3D")
    private val colorOnSurface = Color.WHITE
    private val colorOnSurfaceVariant = Color.parseColor("#B0B0B0")

    enum class State {
        COLLAPSED,
        LISTENING,
        EXPANDED,
        THINKING,
        RESPONSE
    }

    init {
        setupViews()
        initSpeechRecognizer()
    }

    private fun setupViews() {
        // Root container with pill shape
        pillContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createPillBackground()
            elevation = dpToPx(8).toFloat()
            clipToOutline = true
            layoutParams = LayoutParams(pillWidthCollapsed, pillHeightCollapsed).apply {
                gravity = Gravity.CENTER
            }
        }

        // === COLLAPSED STATE UI ===
        val collapsedRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                pillHeightCollapsed
            )
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
        }

        // Mic button (main interaction point)
        micButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(colorOnSurface)
            background = createCircleDrawable(colorPrimary)
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            setOnClickListener { onMicClick() }
        }

        // Status text (tap to expand to text input)
        statusText = TextView(context).apply {
            text = "Tap to speak"
            setTextColor(colorOnSurfaceVariant)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(12)
            }
            setOnClickListener { expand() }
        }

        // No X button - shake to dismiss handles closing
        collapsedRow.addView(micButton)
        collapsedRow.addView(statusText)

        // === EXPANDED STATE UI ===
        expandedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(16))
        }

        // Response area (scrollable)
        responseArea = TextView(context).apply {
            setTextColor(colorOnSurface)
            textSize = 15f
            visibility = View.GONE
            maxLines = 6
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
        }

        // Thinking indicator
        thinkingIndicator = ProgressBar(context).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)).apply {
                gravity = Gravity.CENTER
                bottomMargin = dpToPx(12)
            }
        }

        // Input row
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createRoundedBackground(colorSurfaceVariant, dpToPx(24).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            )
            setPadding(dpToPx(16), 0, dpToPx(8), 0)
        }

        inputField = EditText(context).apply {
            hint = "Ask anything..."
            setTextColor(colorOnSurface)
            setHintTextColor(colorOnSurfaceVariant)
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendQuery()
                    true
                } else false
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    sendButton.visibility = if (s?.isNotBlank() == true) View.VISIBLE else View.GONE
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        sendButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setColorFilter(colorPrimary)
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            setOnClickListener { sendQuery() }
        }

        inputRow.addView(inputField)
        inputRow.addView(sendButton)

        expandedContainer.addView(responseArea)
        expandedContainer.addView(thinkingIndicator)
        expandedContainer.addView(inputRow)

        // Add all to pill
        pillContainer.addView(collapsedRow)
        pillContainer.addView(expandedContainer)

        // Outer container (for click-outside-to-dismiss)
        setOnClickListener {
            if (currentState == State.EXPANDED || currentState == State.RESPONSE) {
                collapse()
            }
        }

        addView(pillContainer)
    }

    private fun onMicClick() {
        when (currentState) {
            State.COLLAPSED -> startListening()
            State.LISTENING -> stopListening()
            State.EXPANDED, State.RESPONSE -> startListening()
            State.THINKING -> { /* ignore */ }
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Timber.w("Speech recognition not available")
            expand() // Fallback to text input
            return
        }

        val recognizer = speechRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            isListening = true
            setState(State.LISTENING)
            recognizer.startListening(intent)
            Timber.i("Pill: STT started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start STT")
            isListening = false
            expand()
        }
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }

    private fun expand() {
        if (currentState == State.EXPANDED) return
        setState(State.EXPANDED)
        inputField.requestFocus()
    }

    private fun collapse() {
        setState(State.COLLAPSED)
        inputField.text.clear()
        responseArea.visibility = View.GONE
    }

    private fun sendQuery() {
        sendQueryText(inputField.text.toString().trim())
    }

    private fun sendQueryText(query: String) {
        if (query.isEmpty()) return

        setState(State.THINKING)
        onQuery(query)
        inputField.text.clear()
    }

    fun showResponse(text: String) {
        setState(State.RESPONSE)
        responseArea.text = text
        responseArea.visibility = View.VISIBLE
    }

    fun showError(error: String) {
        setState(State.EXPANDED)
        statusText.text = error
        statusText.setTextColor(Color.parseColor("#FF5252"))
    }

    private fun setState(newState: State) {
        val oldState = currentState
        currentState = newState
        Timber.d("Pill: $oldState -> $newState")

        when (newState) {
            State.COLLAPSED -> {
                animatePillSize(pillWidthCollapsed, pillHeightCollapsed)
                expandedContainer.visibility = View.GONE
                thinkingIndicator.visibility = View.GONE
                statusText.text = "Tap to speak"
                statusText.setTextColor(colorOnSurfaceVariant)
                micButton.setColorFilter(colorOnSurface)
                stopPulseAnimation()
            }
            State.LISTENING -> {
                animatePillSize(pillWidthCollapsed + dpToPx(40), pillHeightCollapsed)
                statusText.text = "Listening..."
                statusText.setTextColor(colorPrimary)
                micButton.setColorFilter(Color.RED)
                startPulseAnimation()
            }
            State.EXPANDED -> {
                animatePillSize(pillWidthExpanded, pillHeightExpanded)
                expandedContainer.visibility = View.VISIBLE
                thinkingIndicator.visibility = View.GONE
                statusText.text = "Type or tap mic"
                statusText.setTextColor(colorOnSurfaceVariant)
                micButton.setColorFilter(colorOnSurface)
                stopPulseAnimation()
            }
            State.THINKING -> {
                animatePillSize(pillWidthExpanded, pillHeightExpanded)
                expandedContainer.visibility = View.VISIBLE
                thinkingIndicator.visibility = View.VISIBLE
                responseArea.visibility = View.GONE
                statusText.text = "Thinking..."
                statusText.setTextColor(colorPrimary)
            }
            State.RESPONSE -> {
                animatePillSize(pillWidthExpanded, pillHeightExpanded)
                expandedContainer.visibility = View.VISIBLE
                thinkingIndicator.visibility = View.GONE
                statusText.text = "Done"
                statusText.setTextColor(colorOnSurfaceVariant)
            }
        }
    }

    private var pulseAnimator: ObjectAnimator? = null

    private fun startPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(micButton, "alpha", 1f, 0.5f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        micButton.alpha = 1f
    }

    private fun animatePillSize(targetWidth: Int, targetHeight: Int) {
        val currentWidth = pillContainer.layoutParams.width
        val currentHeight = pillContainer.layoutParams.height

        if (currentWidth == targetWidth && currentHeight == targetHeight) return

        val widthAnim = ValueAnimator.ofInt(currentWidth, targetWidth).apply {
            addUpdateListener { animator ->
                pillContainer.layoutParams = pillContainer.layoutParams.apply {
                    width = animator.animatedValue as Int
                }
                pillContainer.requestLayout()
            }
        }

        val heightAnim = ValueAnimator.ofInt(currentHeight, targetHeight).apply {
            addUpdateListener { animator ->
                pillContainer.layoutParams = pillContainer.layoutParams.apply {
                    height = animator.animatedValue as Int
                }
                pillContainer.requestLayout()
            }
        }

        AnimatorSet().apply {
            playTogether(widthAnim, heightAnim)
            duration = 250
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Timber.w("Speech recognition not available")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Timber.d("Pill STT: Ready")
                }

                override fun onBeginningOfSpeech() {
                    Timber.d("Pill STT: Speech started")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Could animate mic button based on volume
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Timber.d("Pill STT: Speech ended")
                    isListening = false
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        else -> "Error: $error"
                    }
                    Timber.w("Pill STT error: $errorMsg")
                    isListening = false
                    if (inputField.text.isBlank()) {
                        collapse()
                    } else {
                        expand()
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    isListening = false
                    if (!text.isNullOrEmpty()) {
                        Timber.i("Pill STT result: $text")
                        // Send directly - don't rely on inputField.text which may not be visible
                        sendQueryText(text)
                    } else {
                        expand()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrEmpty()) {
                        statusText.text = text
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    fun cleanup() {
        pulseAnimator?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // === Drawing helpers ===

    private fun createPillBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(28).toFloat()
            setColor(colorSurface)
        }
    }

    private fun createCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun createRoundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
