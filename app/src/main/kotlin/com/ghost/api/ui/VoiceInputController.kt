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

    private val colorIdle      = Color.parseColor("#8BB4F6") // ethereal off-white cobalt — matches sparkle & app icon
    private val colorRecording = Color.parseColor("#A78BFA") // electric purple — active recording pulse & text
    private val colorConfirm   = Color.parseColor("#F97316") // orange — confirm/send
    private val colorSend      = Color.parseColor("#60A5FA") // electric cobalt — send arrow

    private val CIRCLE = "\u2B24" // ⬤ U+2B24 BLACK LARGE CIRCLE — tintable, matches sparkle style

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
        // Shut up TTS immediately when user initiates microphone input
        try {
            com.ghost.api.services.TTSManager.stopAll()
        } catch (e: Exception) {
            Timber.d("Could not stop TTS: ${e.message}")
        }
        haptic()
        voiceState = VoiceState.RECORDING
        pendingAudio = null
        inputField.hint = "Recording..."
        inputField.setHintTextColor(colorRecording)
        inputField.isEnabled = true
        syncButton()

        recordingJob = CoroutineScope(Dispatchers.Main).launch {
            Timber.i("VoiceInputController: Recording up to 30s...")
            val audio: ByteArray? = withContext(Dispatchers.IO) {
                audioRecorder.record(30, false)
            }
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
        inputField.hint = "Send or tap here to cancel"
        inputField.setHintTextColor(colorConfirm)
        inputField.isEnabled = true
        syncButton()
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

    /** Sync button appearance to current state — ⬤ stays, color signals state */
    fun syncButton() {
        val hasText = inputField.text.isNotBlank()
        when {
            hasText -> {
                // Has typed text: send arrow, cobalt colour
                micButton.text = "➤"
                micButton.setTextColor(colorSend)
                micButton.alpha = 1f
                sparkleOrNull?.setTextColor(colorIdle)
                stopPulse()
            }
            voiceState == VoiceState.RECORDING -> {
                // Recording: electric purple circle & sparkle
                micButton.text = CIRCLE
                micButton.setTextColor(colorRecording)
                sparkleOrNull?.setTextColor(colorRecording)
                startPulse()
            }
            voiceState == VoiceState.CONFIRM -> {
                // Confirm: amber / safety orange send arrow & sparkle
                micButton.text = "➤"
                micButton.setTextColor(colorConfirm)
                micButton.alpha = 1f
                sparkleOrNull?.setTextColor(colorConfirm)
                startPulse()
            }
            else -> {
                // Idle: ethereal cobalt circle & sparkle
                micButton.text = CIRCLE
                micButton.setTextColor(colorIdle)
                sparkleOrNull?.setTextColor(colorIdle)
                stopPulse()
            }
        }
    }

    fun reset() {
        recordingJob?.cancel()
        voiceState = VoiceState.IDLE
        pendingAudio = null
        stopPulse()
        inputField.hint = "Δ \uD83D\uDC7E ∇"
        inputField.setHintTextColor(Color.parseColor("#66FFFFFF"))
        inputField.isEnabled = true
        syncButton()
    }

    fun setThinking(thinking: Boolean) {
        micButton.isEnabled = !thinking
        inputField.isEnabled = !thinking
        inputField.hint = if (thinking) "Processing..." else "Δ \uD83D\uDC7E ∇"
    }

    fun cleanup() {
        audioRecorder.stopRecording()
        pulseAnimator?.cancel()
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(micButton, "alpha", 1f, 0.3f, 1f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val alphaVal = it.animatedValue as Float
                sparkleOrNull?.alpha = alphaVal
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        micButton.alpha = 1f
        sparkleOrNull?.alpha = 1f
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
