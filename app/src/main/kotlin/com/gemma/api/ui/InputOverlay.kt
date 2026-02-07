package com.gemma.api.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.gemma.api.hardware.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * InputOverlay - Minimal voice/text input overlay for agent queries
 * Just an input bar with the ✦ Gemma sparkle button.
 * Tap ✦ to record audio → sends raw audio directly to Gemma (no STT middleman)
 * Type text → sends text query
 *
 * Audio-first design: Gemma 3n is multimodal, so raw audio is more efficient than STT→text→LLM
 */
class InputOverlay(
    context: Context,
    private val onTextQuery: (String) -> Unit,
    private val onAudioQuery: (ByteArray) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val inputField: EditText
    private val sparkleButton: TextView
    private val audioRecorder = AudioRecorder(context)

    private var isRecording = false
    private var recordingJob: Job? = null
    private var pulseAnimator: ObjectAnimator? = null

    // Colors
    private val colorSurface = Color.parseColor("#1E1E1E")
    private val colorSurfaceVariant = Color.parseColor("#2D2D2D")
    private val colorOnSurface = Color.WHITE
    private val colorAccent = Color.parseColor("#8B5CF6")  // Purple sparkle
    private val colorRecording = Color.parseColor("#EF4444")  // Red when recording

    init {
        // Container bar
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createBarBackground()
            elevation = dpToPx(4).toFloat()
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(52)
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dpToPx(16)
                marginEnd = dpToPx(16)
            }
            setPadding(dpToPx(8), dpToPx(4), dpToPx(12), dpToPx(4))
        }

        // ✦ Sparkle button (tap to record audio)
        sparkleButton = TextView(context).apply {
            text = "\u2727"
            textSize = 28f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
            background = createCircleBackground(Color.TRANSPARENT)
            setOnClickListener { toggleRecording() }
            setOnLongClickListener {
                // Long press = extended recording (10s)
                if (!isRecording) {
                    startRecording(extended = true)
                }
                true
            }
        }

        // Text input
        inputField = EditText(context).apply {
            hint = "Δ 👾 ∇"
            setTextColor(colorOnSurface)
            setHintTextColor(Color.parseColor("#888888"))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(8)
                marginEnd = dpToPx(8)
            }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTextQuery()
                    true
                } else false
            }
        }

        // Send button (only shows when text entered)
        val sendButton = TextView(context).apply {
            text = "➤"
            textSize = 20f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            visibility = GONE
            setOnClickListener { sendTextQuery() }
        }

        // Show/hide send button based on text
        inputField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sendButton.visibility = if (s?.isNotBlank() == true) VISIBLE else GONE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        bar.addView(sparkleButton)
        bar.addView(inputField)
        bar.addView(sendButton)

        // Tap outside to dismiss (Escape Hatch)
        setOnClickListener { 
            Timber.i("InputOverlay: Outside tap detected - dismissing")
            onDismiss() 
        }

        addView(bar)
    }

    private fun toggleRecording() {
        Timber.i("InputOverlay: Audio button clicked, isRecording=$isRecording")
        if (isRecording) {
            stopRecording()
        } else {
            startRecording(extended = false)
        }
    }

    private fun startRecording(extended: Boolean) {
        Timber.i("InputOverlay: startRecording called, extended=$extended")
        if (!audioRecorder.hasPermission()) {
            Timber.w("InputOverlay: No audio permission")
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
            return
        }
        Timber.i("InputOverlay: Permission granted, starting countdown")

        val duration = if (extended) 10 else 5  // 5s normal, 10s long press

        recordingJob = CoroutineScope(Dispatchers.Main).launch {
            // PHASE 1: Countdown (let user prepare)
            inputField.hint = "3..."
            sparkleButton.setTextColor(Color.YELLOW)
            delay(300)
            inputField.hint = "2..."
            delay(300)
            inputField.hint = "1..."
            delay(300)

            // PHASE 2: Start recording with haptic feedback
            hapticPulse()
            isRecording = true
            updateRecordingUI(true)

            Timber.i("InputOverlay: Recording ${duration}s audio NOW...")
            // Fix: Use rawPcm=false to get WAV format (LiteRT-LM expects WAV with header)
            val audio: ByteArray? = withContext(Dispatchers.IO) {
                 audioRecorder.record(duration, false)
            }

            // PHASE 3: Done - another haptic
            hapticPulse()
            isRecording = false
            updateRecordingUI(false)

            if (audio != null && audio.isNotEmpty()) {
                // Debug: Check WAV header
                val header = audio.take(4).map { it.toInt().toChar() }.joinToString("")
                Timber.i("InputOverlay: Got ${audio.size} bytes, header='$header' (should be RIFF)")
                val wavCheck = audio.size >= 44 && header == "RIFF"
                Timber.i("InputOverlay: WAV format check: $wavCheck")
                onAudioQuery(audio)
            } else {
                Timber.w("InputOverlay: Recording failed or empty")
                Toast.makeText(context, "Recording failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hapticPulse() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            Timber.w(e, "Haptic failed")
        }
    }

    private fun stopRecording() {
        // audioRecorder runs via coroutine which we added cancellation for
        recordingJob?.cancel()
        isRecording = false
        updateRecordingUI(false)
    }

    private fun updateRecordingUI(recording: Boolean) {
        if (recording) {
            sparkleButton.setTextColor(colorRecording)
            startPulse()
            inputField.hint = "Δ 🎤 ∇"
            inputField.isEnabled = false
        } else {
            sparkleButton.setTextColor(colorAccent)
            stopPulse()
            inputField.hint = "Δ 👾 ∇"
            inputField.isEnabled = true
        }
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(sparkleButton, "alpha", 1f, 0.3f, 1f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        sparkleButton.alpha = 1f
    }

    private fun sendTextQuery() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return

        inputField.text.clear()
        onTextQuery(text)
    }

    fun focusInput() {
        inputField.requestFocus()
    }

    fun cleanup() {
        stopRecording()
        pulseAnimator?.cancel()
    }

    // === Drawing helpers ===

    private fun createBarBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(26).toFloat()
            setColor(colorSurface)
            setStroke(dpToPx(1), colorSurfaceVariant)
        }
    }

    private fun createCircleBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            Timber.d("InputOverlay: Window gained focus, enforcing keyboard")
            inputField.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            
            // Use flags that force the keyboard even if system thinks it's transitioning
            imm?.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            
            // Backup: Retry if it failed (common on Samsung/Pixel transitions)
            postDelayed({
                if (isAttachedToWindow) {
                    imm?.showSoftInput(inputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }, 200)
        }
    }
}
