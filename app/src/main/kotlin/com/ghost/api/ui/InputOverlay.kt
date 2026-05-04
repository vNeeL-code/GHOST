package com.ghost.api.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.ghost.api.Constants
import com.ghost.api.hardware.AudioRecorder
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
    private val colorRecording = Color.parseColor("#f79503ff")  // Orange when recording

    // Google
    private var isThinking = false
    private val activeSlots = mutableListOf<View>()

    private val colorGBlue = Color.parseColor("#4285F4")
    private val colorGRed = Color.parseColor("#EA4335")
    private val colorGYellow = Color.parseColor("#FBBC05")
    private val colorGGreen = Color.parseColor("#34A853")

    private val prefs = context.getSharedPreferences("Gemma_RadialPrefs", Context.MODE_PRIVATE)
    private var appPickerLayout: View? = null

    init {
        // Main Frame size (expanded for radial hexagon)
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(280) 
        ).apply {
            gravity = Gravity.CENTER
        }

        // Main Bar (Horizontal)
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

        // ✧ Sparkle button — HOLD to record audio, release to send
        sparkleButton = TextView(context).apply {
            text = "\u2727"
            textSize = 28f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
            background = createCircleBackground(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        if (!isRecording) startRecording()
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (isRecording) stopRecording()
                        true
                    }
                    else -> false
                }
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
            dismissSubMenus()
            onDismiss() 
        }

        addView(bar)

        // Add Radial Buttons (Hexagon layout)
        addRadialButtons()
    }

    private fun addRadialButtons() {
        val horizontalOffset = dpToPx(90)
        val verticalOffset = dpToPx(110)

        // Add the 4 Google-colored buttons in a hexagon pattern around the center bar
        setupRadialButton(colorGRed, -horizontalOffset.toFloat(), -verticalOffset.toFloat(), "Red (Camera)")
        setupRadialButton(colorGBlue, horizontalOffset.toFloat(), -verticalOffset.toFloat(), "Blue (Search)")
        setupRadialButton(colorGGreen, -horizontalOffset.toFloat(), verticalOffset.toFloat(), "Green (Diary)")
        setupRadialButton(colorGYellow, horizontalOffset.toFloat(), verticalOffset.toFloat(), "Yellow (Tools)")
        
        // Ensure parent FrameLayout doesn't block children
        isClickable = false
        isFocusable = false
    }

    private fun setupRadialButton(color: Int, tx: Float, ty: Float, label: String) {
        val btnSize = dpToPx(48)
        val btn = TextView(context).apply {
            text = "✧"
            textSize = 24f
            setTextColor(color)
            gravity = Gravity.CENTER
            background = createCircleBackground(Color.parseColor("#1E1E1E"))
            elevation = dpToPx(6).toFloat()
            translationX = tx
            translationY = ty
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER
            }
            
            var startX = 0f
            var startY = 0f
            var isSwiping = false
            var wasLongClicked = false

            // Interaction: Pullable logic (Hold to expand)
            setOnLongClickListener {
                if (!isSwiping) {
                    wasLongClicked = true
                    hapticPulse()
                    expandSubMenu(this, tx, ty, label)
                }
                true
            }

            setOnTouchListener { v, event ->
                val threshold = 50f
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isSwiping = false
                        wasLongClicked = false
                        v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start()
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(event.rawX - startX) > threshold || Math.abs(event.rawY - startY) > threshold) {
                            isSwiping = true
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        if (wasLongClicked) {
                            wasLongClicked = false // consume the up event
                        } else if (isSwiping) {
                            val dx = event.rawX - startX
                            val dy = event.rawY - startY
                            val direction = if (Math.abs(dx) > Math.abs(dy)) {
                                if (dx > 0) "RIGHT" else "LEFT"
                            } else {
                                if (dy > 0) "DOWN" else "UP"
                            }
                            launchBoundApp(label, direction)
                        } else {
                            // Normal tap defaults to UP direction
                            launchBoundApp(label, "UP")
                        }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        wasLongClicked = false
                    }
                }
                false // Let long click fire if hold
            }
        }
        addView(btn)
    }

    private fun launchBoundApp(label: String, direction: String) {
        val boundPackage = prefs.getString("BIND_${label}_${direction}", null)
        if (boundPackage != null) {
            try {
                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage(boundPackage)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) {
                    context.startActivity(intent)
                    onDismiss()
                }
            } catch (e: Exception) { }
        }
    }

    private fun expandSubMenu(parent: View, px: Float, py: Float, parentLabel: String) {
        Timber.i("Radial: Expanding submenu for $parentLabel")
        // Visual feedback: Scale up parent even more
        parent.animate().scaleX(1.4f).scaleY(1.4f).setDuration(250).start()
        
        // Add 4 mini-slots around the button (North, South, East, West)
        val parentGroup = parent.parent as? ViewGroup ?: return
        val slotSize = dpToPx(32)
        val distance = dpToPx(60)

        val slots = listOf(
            Pair(0f, -distance.toFloat()), // North
            Pair(0f, distance.toFloat()),  // South
            Pair(-distance.toFloat(), 0f), // West
            Pair(distance.toFloat(), 0f)   // East
        )

        val directions = listOf("UP", "DOWN", "LEFT", "RIGHT")

        slots.forEachIndexed { index, pos ->
            val slot = TextView(context).apply {
                text = "+"
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                background = createCircleBackground(Color.parseColor("#3D3D3D"))
                translationX = px + pos.first
                translationY = py + pos.second
                alpha = 0f
                scaleX = 0f
                scaleY = 0f
                layoutParams = LayoutParams(slotSize, slotSize).apply {
                    gravity = Gravity.CENTER
                }
                
                setOnClickListener {
                    hapticPulse()
                    showAppPicker(parentLabel, directions[index])
                }
            }
            parentGroup.addView(slot)
            activeSlots.add(slot)
            slot.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setStartDelay((index * 50).toLong()).start()
        }
        
        Toast.makeText(context, "Slot expansion: $parentLabel", Toast.LENGTH_SHORT).show()
    }

    private fun dismissSubMenus() {
        activeSlots.forEach { it.animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(200).withEndAction { removeView(it) }.start() }
        activeSlots.clear()
        
        appPickerLayout?.let { removeView(it); appPickerLayout = null }
        
        // Also reset main buttons scale
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v is TextView && v.text == "✧") {
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
    }

    private fun showAppPicker(parentLabel: String, direction: String) {
        if (appPickerLayout != null) removeView(appPickerLayout)
        
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(intent, 0).sortedBy { it.loadLabel(pm).toString() }
        
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E61E1E1E"))
            layoutParams = LayoutParams(dpToPx(240), dpToPx(300)).apply {
                gravity = Gravity.CENTER
            }
            elevation = dpToPx(16).toFloat()
        }
        
        val title = TextView(context).apply {
            text = "Bind App to $direction swipe"
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#333333"))
        }
        container.addView(title)
        
        val scrollView = ScrollView(context)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        
        for (info in resolveInfos) {
            val appName = info.loadLabel(pm).toString()
            val pkgName = info.activityInfo.packageName
            val item = TextView(context).apply {
                text = appName
                setTextColor(Color.LTGRAY)
                setPadding(32, 24, 32, 24)
                setOnClickListener {
                    prefs.edit().putString("BIND_${parentLabel}_${direction}", pkgName).apply()
                    dismissSubMenus()
                }
            }
            list.addView(item)
            val divider = View(context).apply { setBackgroundColor(Color.DKGRAY); layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1) }
            list.addView(divider)
        }
        
        scrollView.addView(list)
        container.addView(scrollView)
        
        appPickerLayout = container
        addView(container)
    }

    private fun startRecording() {
        Timber.i("InputOverlay: Hold-to-record started")
        if (!audioRecorder.hasPermission()) {
            Timber.w("InputOverlay: No audio permission")
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
            return
        }

        // Immediate start — no countdown. 30s max (Gemma 3n audio cap, only ~132 tokens)
        hapticPulse()
        isRecording = true
        updateRecordingUI(true)

        recordingJob = CoroutineScope(Dispatchers.Main).launch {
            Timber.i("InputOverlay: Recording up to 30s audio...")
            val audio: ByteArray? = withContext(Dispatchers.IO) {
                audioRecorder.record(30, false)
            }

            // Recording finished (either released or hit 30s cap)
            hapticPulse()
            isRecording = false
            updateRecordingUI(false)

            if (audio != null && audio.isNotEmpty()) {
                val header = audio.take(4).map { it.toInt().toChar() }.joinToString("")
                Timber.i("InputOverlay: Got ${audio.size} bytes (${audio.size / 32000}s), header='$header'")
                onAudioQuery(audio)
            } else {
                Timber.w("InputOverlay: Recording failed or empty")
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
        // Stop the AudioRecorder mid-recording (triggers early return with what was captured)
        audioRecorder.stopRecording()
        // The coroutine in startRecording will pick up the partial audio and send it
    }

    fun setThinking(thinking: Boolean) {
        if (thinking) {
            inputField.isEnabled = false
            inputField.hint = "Gemma is processing..."
            sparkleButton.setTextColor(Color.parseColor("#F59E0B")) // Amber sparkle
            startPulse()
        } else {
            inputField.isEnabled = true
            inputField.hint = "Δ \uD83D\uDC7E ∇"
            sparkleButton.setTextColor(colorAccent)
            stopPulse()
        }
    }

    fun setLoading(loading: Boolean) {
        if (loading) {
            inputField.isEnabled = false
            inputField.hint = "Loading Engine (1m)..."
            sparkleButton.setTextColor(Color.GRAY)
            sparkleButton.alpha = 0.5f
        } else {
            inputField.isEnabled = true
            inputField.hint = "Δ \uD83D\uDC7E ∇"
            sparkleButton.setTextColor(colorAccent)
            sparkleButton.alpha = 1f
        }
    }

    private fun updateRecordingUI(recording: Boolean) {
        if (recording) {
            sparkleButton.setTextColor(colorRecording)
            startPulse()
            inputField.hint = "Listening..."
            inputField.isEnabled = false
        } else {
            sparkleButton.setTextColor(colorAccent)
            stopPulse()
            inputField.hint = "Δ \uD83D\uDC7E ∇"
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

        setThinking(true)
        dismissSubMenus()
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
}
