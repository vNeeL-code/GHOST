package com.ghost.api.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.Vibrator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.ghost.api.hardware.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * VoiceInputController — shared mic state machine used by both the overlay
 * bar (InputOverlay) and the main chat bar (MainActivity).
 *
 * States:  IDLE → RECORDING → CONFIRM → (send) → IDLE
 *
 * @param context         Android context
 * @param micButton       The purple circle button (🟣/🔴/🟠/➤)
 * @param inputField      The EditText — used to detect "has text" and update hint
 * @param sparkleOrNull   Optional left-side sparkle to pulse during recording (overlay only)
 * @param onAudioReady    Called with raw WAV bytes when user confirms
 * @param onTextReady     Called when the user typed text and tapped send
 */
class VoiceInputController(
    private val context: Context,
    private val micButton: TextView,
    private val inputField: EditText,
    private val sparkleOrNull: TextView? = null,
    private val onAudioReady: (ByteArray) -> Unit,
    private val onTextReady: (String) -> Unit
) {
    private enum class VoiceState { IDLE, RECORDING, CONFIRM }

    private val audioRecorder = AudioRecorder(context)
    private var voiceState = VoiceState.IDLE
    private var pendingAudio: ByteArray? = null
    private var recordingJob: Job? = null
    private var pulseAnimator: ObjectAnimator? = null

    private val colorAccent  = Color.parseColor("#8B5CF6") // purple
    private val colorRecording = Color.parseColor("#f79503") // orange (recording)

    init {
        micButton.setOnClickListener { handleTap() }
        syncButton()

        inputField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                syncButton()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Tap the field to cancel recording or discard pending audio — escape hatch
        inputField.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN &&
                voiceState != VoiceState.IDLE) {
                audioRecorder.stopRecording()
                recordingJob?.cancel()
                haptic()
                reset()
                true // consume — don't open keyboard during cancel
            } else {
                false // pass through when idle so keyboard opens normally
            }
        }
    }

    /** Single entry-point for the button tap — delegates by current state */
    fun handleTap() {
        if (inputField.text.isNotBlank()) {
            sendText()
            return
        }
        when (voiceState) {
            VoiceState.IDLE      -> startRecording()
            VoiceState.RECORDING -> stopRecordingToConfirm()
            VoiceState.CONFIRM   -> sendAudio()
        }
    }

    private fun startRecording() {
        if (!audioRecorder.hasPermission()) {
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
            return
        }
        haptic()
        voiceState = VoiceState.RECORDING
        pendingAudio = null
        syncButton()
        updateSparkle(recording = true)

        recordingJob = CoroutineScope(Dispatchers.Main).launch {
            Timber.i("VoiceInputController: Recording up to 30s...")
            val audio: ByteArray? = withContext(Dispatchers.IO) {
                audioRecorder.record(30, false)
            }
            updateSparkle(recording = false)
            if (audio != null && audio.isNotEmpty()) {
                Timber.i("VoiceInputController: ${audio.size} bytes recorded")
                transitionToConfirm(audio)
            } else {
                Timber.w("VoiceInputController: Recording failed or empty")
                reset()
            }
        }
    }

    private fun stopRecordingToConfirm() {
        haptic()
        audioRecorder.stopRecording()
        // The coroutine picks up remaining audio and calls transitionToConfirm
    }

    private fun transitionToConfirm(audio: ByteArray) {
        pendingAudio = audio
        voiceState = VoiceState.CONFIRM
        haptic()
        syncButton()
        // Keep field enabled so a tap can cancel — hint explains both options
        inputField.hint = "🟠 Send  ·  tap here to cancel"
        inputField.isEnabled = true
        startPulse()
    }

    private fun sendAudio() {
        val audio = pendingAudio
        if (audio != null && audio.isNotEmpty()) {
            onAudioReady(audio)
        }
        reset()
    }

    private fun sendText() {
        val text = inputField.text.toString().trim()
        if (text.isBlank()) return
        inputField.setText("")
        onTextReady(text)
    }

    /** Sync button emoji to current state */
    fun syncButton() {
        val hasText = inputField.text.isNotBlank()
        micButton.text = when {
            hasText                            -> "➤"
            voiceState == VoiceState.RECORDING -> "🔴"
            voiceState == VoiceState.CONFIRM   -> "🟠"
            else                               -> "🟣"
        }
    }

    fun reset() {
        recordingJob?.cancel()
        voiceState = VoiceState.IDLE
        pendingAudio = null
        stopPulse()
        inputField.hint = "Δ 👾 ∇"
        inputField.isEnabled = true
        syncButton()
    }

    fun setThinking(thinking: Boolean) {
        micButton.isEnabled = !thinking
        inputField.isEnabled = !thinking
        inputField.hint = if (thinking) "✧ Processing..." else "Δ 👾 ∇"
    }

    fun cleanup() {
        audioRecorder.stopRecording()
        pulseAnimator?.cancel()
    }

    // ── Sparkle visual feedback (overlay bar) ─────────────────────────────

    private fun updateSparkle(recording: Boolean) {
        if (recording) {
            sparkleOrNull?.setTextColor(colorRecording)
            startPulse()
            inputField.hint = "Listening..."
            inputField.isEnabled = false
        } else {
            sparkleOrNull?.setTextColor(colorAccent)
            stopPulse()
            inputField.hint = "Δ 👾 ∇"
            inputField.isEnabled = true
        }
    }

    private fun startPulse() {
        val target = (sparkleOrNull ?: micButton)
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(target, "alpha", 1f, 0.3f, 1f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        (sparkleOrNull ?: micButton).alpha = 1f
    }

    private fun haptic() {
        try {
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                v?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v?.vibrate(50)
            }
        } catch (e: Exception) { Timber.w(e, "Haptic failed") }
    }
}
