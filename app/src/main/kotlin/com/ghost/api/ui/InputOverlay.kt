package com.ghost.api.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.ghost.api.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * InputOverlay - Minimal voice/text input overlay for agent queries
 * Just an input bar with the ✧ Gemma sparkle button.
 * Tap ✧ to record audio → sends raw audio directly to Gemma (no STT middleman)
 * Type text → sends text query
 *
 * Audio-first design: Gemma 3n is multimodal, so raw audio is more efficient than STT→text→LLM
 */
class InputOverlay(
    context: Context,
    private val onTextQuery: (String) -> Unit,
    private val onAudioQuery: (ByteArray) -> Unit,
    private val onDismiss: () -> Unit,
    private val onFocusRequest: () -> Unit = {}
) : FrameLayout(context) {

    private val inputField: EditText
    private val sparkleButton: TextView
    private lateinit var voiceSendButton: TextView
    private val voiceController: VoiceInputController

    private var isThinkingState = false

    // Colors (still needed for sparkle menu / thinking state)
    // Premium Glassmorphic Palette
    private val colorSurface = Color.parseColor("#CC121212") // Glassy dark
    private val colorSurfaceVariant = Color.parseColor("#33FFFFFF") // Subtle border
    private val colorOnSurface = Color.WHITE
    private val colorAccent = Color.parseColor("#8BB4F6")  // Ethereal Off-White Holographic Cobalt
    private val colorThinking = Color.parseColor("#F59E0B") // Amber
    private val colorRecording = Color.parseColor("#EF4444")  // Red-Pulse

    private data class BubbleSlot(
        val view: View,
        val direction: String,
        val targetDx: Float,
        val targetDy: Float,
        val boundPkg: String?
    )

    // Active deployed bubbles and slots
    private val activeSlots = mutableListOf<View>()
    private val activeBubbleSlots = mutableListOf<BubbleSlot>()

    private val colorGBlue = Color.parseColor("#4285F4")
    private val colorGRed = Color.parseColor("#EA4335")
    private val colorGYellow = Color.parseColor("#FBBC05")
    private val colorGGreen = Color.parseColor("#34A853")
    private val colorOrange = Color.parseColor("#F97316")
    private val colorCyan = Color.parseColor("#00F0FF")

    private val prefs = context.getSharedPreferences("Gemma_RadialPrefs", Context.MODE_PRIVATE)
    private var appPickerLayout: View? = null

    init {
        val metrics = context.resources.displayMetrics
        val isLandscape = metrics.widthPixels > metrics.heightPixels
        val frameHeight = if (isLandscape) ViewGroup.LayoutParams.MATCH_PARENT else dpToPx(480)

        // Main Frame size (expanded for 6-point radial hexagon/diamond)
        clipChildren = false
        clipToPadding = false
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            frameHeight
        ).apply {
            gravity = Gravity.CENTER
        }

        val barWidth = if (isLandscape) {
            dpToPx(240).coerceAtMost((metrics.widthPixels * 0.45f).toInt())
        } else {
            (metrics.widthPixels * 0.92f).toInt().coerceAtMost(dpToPx(380))
        }

        // Main Bar (Horizontal)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createBarBackground()
            elevation = dpToPx(4).toFloat()
            layoutParams = LayoutParams(
                barWidth,
                dpToPx(48)
            ).apply {
                gravity = Gravity.CENTER
            }
            setPadding(dpToPx(8), dpToPx(4), dpToPx(12), dpToPx(4))
        }

        // ✧ Sparkle button — Tap to open app, HOLD/SLIDE for shortcuts
        sparkleButton = TextView(context).apply {
            text = "\u2727"
            textSize = 28f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
            background = createCircleBackground(Color.TRANSPARENT)

            var startX = 0f
            var startY = 0f
            var isSubmenuOpen = false
            var hoveredDir: String? = null
            val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())

            val deployRunnable = Runnable {
                isSubmenuOpen = true
                hapticPulse()
                deploySparkleSubMenu(this)
            }

            val rebindRunnable = Runnable {
                val dir = hoveredDir ?: return@Runnable
                vibrateLongPress()
                dismissSubMenus()
                isSubmenuOpen = false
                hoveredDir = null
                showAppPicker("Sparkle", dir)
            }

            setOnTouchListener { v, event ->
                if (appPickerLayout != null) {
                    if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                        holdHandler.removeCallbacks(deployRunnable)
                        holdHandler.removeCallbacks(rebindRunnable)
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        isSubmenuOpen = false
                        hoveredDir = null
                    }
                    return@setOnTouchListener true
                }

                val threshold = dpToPx(16).toFloat()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isSubmenuOpen = false
                        hoveredDir = null
                        v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start()
                        holdHandler.postDelayed(deployRunnable, 200)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        val totalDist = Math.abs(dy)
                        if (!isSubmenuOpen && totalDist >= threshold) {
                            holdHandler.removeCallbacks(deployRunnable)
                            isSubmenuOpen = true
                            hapticPulse()
                            deploySparkleSubMenu(v)
                        }

                        if (isSubmenuOpen) {
                            if (totalDist >= threshold) {
                                val dir = if (dy > 0) "DOWN" else "UP"
                                if (dir != hoveredDir) {
                                    hoveredDir = dir
                                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    highlightBubble(dir, colorAccent)
                                    holdHandler.removeCallbacks(rebindRunnable)
                                    holdHandler.postDelayed(rebindRunnable, 550)
                                }
                            } else {
                                if (hoveredDir != null) {
                                    hoveredDir = null
                                    holdHandler.removeCallbacks(rebindRunnable)
                                    resetBubblesHighlight(colorAccent)
                                }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        holdHandler.removeCallbacks(deployRunnable)
                        holdHandler.removeCallbacks(rebindRunnable)
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()

                        if (appPickerLayout != null) {
                            isSubmenuOpen = false
                            hoveredDir = null
                            return@setOnTouchListener true
                        }

                        if (isSubmenuOpen) {
                            val targetDir = hoveredDir
                            if (targetDir != null) {
                                handleBubbleSelected("Sparkle", targetDir, colorAccent)
                            } else {
                                retractSparkleSubMenu(this)
                            }
                            isSubmenuOpen = false
                        } else {
                            val dy = event.rawY - startY
                            if (Math.abs(dy) >= threshold) {
                                val dir = if (dy > 0) "DOWN" else "UP"
                                launchBoundApp("Sparkle", dir)
                            } else {
                                val intent = android.content.Intent(context, com.ghost.api.MainActivity::class.java).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                context.startActivity(intent)
                                onDismiss()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        holdHandler.removeCallbacks(deployRunnable)
                        holdHandler.removeCallbacks(rebindRunnable)
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        if (isSubmenuOpen) {
                            retractSparkleSubMenu(this)
                            isSubmenuOpen = false
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        // Text input
        inputField = EditText(context).apply {
            hint = "Δ \uD83D\uDC7E ∇"
            setTextColor(colorOnSurface)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(8)
                marginEnd = dpToPx(8)
            }

            setOnClickListener { 
                onFocusRequest() 
                requestFocus()
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocusRequest()
            }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    voiceController.handleTap()
                    true
                } else false
            }
        }

        // Voice / Send button — wired to VoiceInputController
        voiceSendButton = TextView(context).apply {
            text = "🔵"
            textSize = 20f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            visibility = VISIBLE
        }

        // Build controller AFTER voiceSendButton and inputField exist
        // (assigned to val so it can be referenced in cleanup)
        voiceController = VoiceInputController(
            context = context,
            micButton = voiceSendButton,
            inputField = inputField,
            sparkleOrNull = sparkleButton,
            onAudioReady = { audio ->
                onAudioQuery(audio)
                onDismiss()
            },
            onTextReady = { text ->
                setThinking(true)
                dismissSubMenus()
                onTextQuery(text)
            }
        )

        bar.addView(sparkleButton)
        bar.addView(inputField)
        bar.addView(voiceSendButton)

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
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val isLandscape = screenW > screenH
        val heightDp = screenH / metrics.density

        // In landscape, center the cluster with ideal ergonomic spread
        val horizontalSpread = if (isLandscape) 1.55f else 1.0f
        val horizontalOffset = (dpToPx(90) * horizontalSpread).toInt()
        val verticalOffset = if (isLandscape) dpToPx(56) else dpToPx(105)
        val apexVerticalOffset = if (isLandscape) dpToPx(102) else dpToPx(182)

        // 1. Top Apex Vertex: Orange (CS:GO Tape Reel Launcher)
        setupTopOrangeButton(0f, -apexVerticalOffset.toFloat())

        // 2. Bottom Apex Vertex: Cyan (4-Way Media & Sticky Scratchpad Puck)
        setupBottomCyanPuck(0f, apexVerticalOffset.toFloat())

        // 3. Four Diagonal Google Nodes
        setupRadialButton(colorGRed, -horizontalOffset.toFloat(), -verticalOffset.toFloat(), "Red (Camera)")
        setupRadialButton(colorGBlue, horizontalOffset.toFloat(), -verticalOffset.toFloat(), "Blue (Search)")
        setupRadialButton(colorGGreen, -horizontalOffset.toFloat(), verticalOffset.toFloat(), "Green (Diary)")
        setupRadialButton(colorGYellow, horizontalOffset.toFloat(), verticalOffset.toFloat(), "Yellow (Tools)")
        
        // Tap outside to dismiss (children consume their own touch events)
        isClickable = true
        isFocusable = false
    }

    private fun createBaseSocket(color: Int, tx: Float, ty: Float): TextView {
        val socketSize = dpToPx(44)
        return TextView(context).apply {
            text = "+"
            textSize = 14f
            setTextColor(Color.argb(180, 255, 255, 255))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1C1C24"))
                setStroke(dpToPx(1), Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)))
            }
            translationX = tx
            translationY = ty
            alpha = 0.5f
            layoutParams = LayoutParams(socketSize, socketSize).apply {
                gravity = Gravity.CENTER
            }
        }
    }

    private fun triggerSocketMagneticSparkle(socket: View) {
        socket.animate()
            .scaleX(1.25f)
            .scaleY(1.25f)
            .alpha(1.0f)
            .setDuration(80)
            .withEndAction {
                socket.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(0.5f)
                    .setDuration(160)
                    .start()
            }
            .start()
    }

    private fun applyPuckWiggle(view: View, baseTx: Float, baseTy: Float, startX: Float, startY: Float, rawX: Float, rawY: Float) {
        val rawDx = rawX - startX
        val rawDy = rawY - startY
        val dist = Math.hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
        if (dist == 0f) return
        val maxRadius = dpToPx(24).toFloat()
        val clampedDist = Math.min(dist * 0.5f, maxRadius)
        val factor = clampedDist / dist
        view.translationX = baseTx + (rawDx * factor)
        view.translationY = baseTy + (rawDy * factor)
    }

    private fun resetPuckSpring(view: View, baseTx: Float, baseTy: Float) {
        view.animate()
            .translationX(baseTx)
            .translationY(baseTy)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.8f))
            .setDuration(220)
            .start()
    }

    private fun setupTopOrangeButton(tx: Float, ty: Float) {
        val socket = createBaseSocket(colorOrange, tx, ty)
        addView(socket)

        val btnSize = dpToPx(48)
        val btn = TextView(context).apply {
            text = "✧"
            textSize = 24f
            setTextColor(colorOrange)
            gravity = Gravity.CENTER
            background = createCircleBackground(colorSurface)
            elevation = dpToPx(6).toFloat()
            translationX = tx
            translationY = ty
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER
            }

            var startX = 0f
            var startY = 0f
            var lastRawX = 0f
            var isSwiping = false
            var isInDeadzone = true

            setOnTouchListener { v, event ->
                if (appPickerLayout != null) return@setOnTouchListener true
                val threshold = dpToPx(18).toFloat()
                val overlayMgr = com.ghost.api.GemmaService.instance?.overlayManager
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        lastRawX = event.rawX
                        isSwiping = false
                        isInDeadzone = true
                        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        applyPuckWiggle(v, tx, ty, startX, startY, event.rawX, event.rawY)
                        val totalDist = Math.hypot((event.rawX - startX).toDouble(), (event.rawY - startY).toDouble()).toFloat()

                        // Magnetic detent state transitions
                        if (isInDeadzone && totalDist >= threshold) {
                            isInDeadzone = false
                            isSwiping = true
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        } else if (!isInDeadzone && totalDist < threshold) {
                            isInDeadzone = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            triggerSocketMagneticSparkle(socket)
                        }

                        // Analog velocity continuous joystick spin on horizontal thumb hold
                        if (overlayMgr?.isAppReelVisible() == true) {
                            val dx = event.rawX - startX
                            val dy = event.rawY - startY
                            if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 0.7f) {
                                val deflection = dx - (Math.signum(dx) * threshold)
                                val norm = (deflection / dpToPx(24).toFloat()).coerceIn(-1.5f, 1.5f)
                                // Calibrated speed in cards/sec:
                                // Gentle tilt: ~1.2 cards/sec. Max hold: ~3.8 cards/sec.
                                // Fully readable, completely controllable, and stops on a dime!
                                val cardsPerSecond = -Math.signum(norm) * (1.0f + Math.pow(Math.abs(norm).toDouble(), 1.3).toFloat() * 2.8f)
                                overlayMgr.setAppReelJoystickVelocity(cardsPerSecond)
                            } else {
                                overlayMgr.setAppReelJoystickVelocity(0f)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        overlayMgr?.setAppReelJoystickVelocity(0f)
                        resetPuckSpring(v, tx, ty)
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val totalDist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        if (totalDist >= threshold) {
                            if (Math.abs(dy) > Math.abs(dx)) {
                                if (dy > 0) {
                                    // Pull DOWN -> Summon / Dismiss App Reel
                                    hapticPulse()
                                    if (overlayMgr?.isAppReelVisible() == true) {
                                        overlayMgr.hideAppReel()
                                    } else {
                                        overlayMgr?.showAppReel()
                                    }
                                } else {
                                    // Push UP -> Remote Confirm / Launch selected item in App Reel
                                    hapticPulse()
                                    if (overlayMgr?.isAppReelVisible() == true) {
                                        overlayMgr.launchAppReelSelected()
                                    } else {
                                        // If Reel wasn't open, open it
                                        overlayMgr?.showAppReel()
                                    }
                                }
                            }
                            // If horizontal drag (LEFT / RIGHT) -> already spun via joystick, release stays open at rest!
                        } else {
                            // Released in deadzone / neutral -> Safe zero state + Magnetic snap sparkle!
                            triggerSocketMagneticSparkle(socket)
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        overlayMgr?.setAppReelJoystickVelocity(0f)
                        resetPuckSpring(v, tx, ty)
                        true
                    }
                    else -> false
                }
            }
        }
        addView(btn)
    }

    private fun setupBottomCyanPuck(tx: Float, ty: Float) {
        val socket = createBaseSocket(colorCyan, tx, ty)
        addView(socket)

        val btnSize = dpToPx(48)
        val btn = TextView(context).apply {
            text = "✧"
            textSize = 24f
            setTextColor(colorCyan)
            gravity = Gravity.CENTER
            background = createCircleBackground(colorSurface)
            elevation = dpToPx(6).toFloat()
            translationX = tx
            translationY = ty
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER
            }

            var startX = 0f
            var startY = 0f
            var isSwiping = false
            var isInDeadzone = true

            setOnTouchListener { v, event ->
                if (appPickerLayout != null) return@setOnTouchListener true
                val threshold = dpToPx(18).toFloat()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isSwiping = false
                        isInDeadzone = true
                        v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        applyPuckWiggle(v, tx, ty, startX, startY, event.rawX, event.rawY)
                        val totalDist = Math.hypot((event.rawX - startX).toDouble(), (event.rawY - startY).toDouble()).toFloat()

                        if (isInDeadzone && totalDist >= threshold) {
                            isInDeadzone = false
                            isSwiping = true
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        } else if (!isInDeadzone && totalDist < threshold) {
                            isInDeadzone = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            triggerSocketMagneticSparkle(socket)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        resetPuckSpring(v, tx, ty)
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val totalDist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        if (totalDist >= threshold) {
                            if (Math.abs(dx) > Math.abs(dy)) {
                                if (dx > 0) {
                                    // Right -> Next Track
                                    hapticPulse()
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                                    Toast.makeText(context, "⏭", Toast.LENGTH_SHORT).show()
                                } else {
                                    // Left -> Previous Track
                                    hapticPulse()
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                                    Toast.makeText(context, "⏮", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                if (dy > 0) {
                                    // Down -> Play/Pause
                                    hapticPulse()
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                                    Toast.makeText(context, "⏯", Toast.LENGTH_SHORT).show()
                                } else {
                                    // Up -> Summon Scratchpad PiP
                                    hapticPulse()
                                    com.ghost.api.GemmaService.instance?.overlayManager?.showScratchpad()
                                }
                            }
                        } else {
                            // Released in deadzone -> Magnetic snap sparkle & neutral cancel!
                            triggerSocketMagneticSparkle(socket)
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        resetPuckSpring(v, tx, ty)
                        true
                    }
                    else -> false
                }
            }
        }
        addView(btn)
    }

    private fun setupRadialButton(color: Int, tx: Float, ty: Float, label: String) {
        val socket = createBaseSocket(color, tx, ty)
        addView(socket)

        val btnSize = dpToPx(48)
        val btn = TextView(context).apply {
            text = "✧"
            textSize = 24f
            setTextColor(color)
            gravity = Gravity.CENTER
            background = createCircleBackground(colorSurface)
            elevation = dpToPx(6).toFloat()
            translationX = tx
            translationY = ty
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER
            }

            var startX = 0f
            var startY = 0f
            var isSubmenuOpen = false
            var hoveredDir: String? = null
            var isInDeadzone = true

            val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val deployRunnable = Runnable {
                isSubmenuOpen = true
                hapticPulse()
                deployPuckSubMenu(this, tx, ty, label, color)
            }

            val rebindRunnable = Runnable {
                val dir = hoveredDir ?: return@Runnable
                vibrateLongPress()
                dismissSubMenus()
                isSubmenuOpen = false
                hoveredDir = null
                showAppPicker(label, dir)
            }

            setOnTouchListener { v, event ->
                if (appPickerLayout != null) {
                    if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                        holdHandler.removeCallbacks(deployRunnable)
                        holdHandler.removeCallbacks(rebindRunnable)
                        resetPuckSpring(v, tx, ty)
                        isSubmenuOpen = false
                        hoveredDir = null
                    }
                    return@setOnTouchListener true
                }

                val deadzoneThreshold = dpToPx(18).toFloat()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isSubmenuOpen = false
                        hoveredDir = null
                        isInDeadzone = true
                        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).start()
                        holdHandler.postDelayed(deployRunnable, 180)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        applyPuckWiggle(v, tx, ty, startX, startY, event.rawX, event.rawY)
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val totalDist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        if (!isSubmenuOpen && totalDist >= deadzoneThreshold) {
                            holdHandler.removeCallbacks(deployRunnable)
                            isSubmenuOpen = true
                            hapticPulse()
                            deployPuckSubMenu(v, tx, ty, label, color)
                        }

                        if (isSubmenuOpen) {
                            if (totalDist >= deadzoneThreshold) {
                                val dir = if (Math.abs(dx) > Math.abs(dy)) {
                                    if (dx > 0) "RIGHT" else "LEFT"
                                } else {
                                    if (dy > 0) "DOWN" else "UP"
                                }

                                if (dir != hoveredDir) {
                                    hoveredDir = dir
                                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    highlightBubble(dir, color)
                                    holdHandler.removeCallbacks(rebindRunnable)
                                    holdHandler.postDelayed(rebindRunnable, 550)
                                }
                            } else {
                                if (hoveredDir != null) {
                                    hoveredDir = null
                                    holdHandler.removeCallbacks(rebindRunnable)
                                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    resetBubblesHighlight(color)
                                }
                            }
                        } else {
                            if (isInDeadzone && totalDist >= deadzoneThreshold) {
                                isInDeadzone = false
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            } else if (!isInDeadzone && totalDist < deadzoneThreshold) {
                                isInDeadzone = true
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                triggerSocketMagneticSparkle(socket)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        holdHandler.removeCallbacks(deployRunnable)
                        holdHandler.removeCallbacks(rebindRunnable)
                        resetPuckSpring(v, tx, ty)

                        if (appPickerLayout != null) {
                            isSubmenuOpen = false
                            hoveredDir = null
                            return@setOnTouchListener true
                        }

                        if (isSubmenuOpen) {
                            val targetDir = hoveredDir
                            if (targetDir != null) {
                                handleBubbleSelected(label, targetDir, color)
                            } else {
                                triggerSocketMagneticSparkle(socket)
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                retractSubMenu(tx, ty)
                            }
                            isSubmenuOpen = false
                        } else {
                            val dx = event.rawX - startX
                            val dy = event.rawY - startY
                            val totalDist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            if (totalDist >= deadzoneThreshold) {
                                val flickDir = if (Math.abs(dx) > Math.abs(dy)) {
                                    if (dx > 0) "RIGHT" else "LEFT"
                                } else {
                                    if (dy > 0) "DOWN" else "UP"
                                }
                                launchBoundApp(label, flickDir)
                            } else {
                                triggerSocketMagneticSparkle(socket)
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        holdHandler.removeCallbacks(deployRunnable)
                        holdHandler.removeCallbacks(rebindRunnable)
                        resetPuckSpring(v, tx, ty)
                        if (isSubmenuOpen) {
                            retractSubMenu(tx, ty)
                            isSubmenuOpen = false
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        addView(btn)
    }

    private fun launchBoundApp(label: String, direction: String) {
        if (label.contains("Orange") && direction == "DOWN") {
            hapticPulse()
            com.ghost.api.GemmaService.instance?.overlayManager?.showAppReel()
            return
        }

        val boundPackage = prefs.getString("BIND_${label}_${direction}", null)
        
        // Audit 9.0: Agentic Shortcuts
        // If not bound to an app, or bound to a /command, trigger Gemma directly
        val command = when {
            boundPackage?.startsWith("/") == true -> boundPackage
            boundPackage == null && label.contains("Red") -> "/scan"
            boundPackage == null && label.contains("Blue") -> "/search"
            boundPackage == null && label.contains("Green") -> "/diary"
            boundPackage == null && label.contains("Yellow") -> "/tools"
            boundPackage == null && label.contains("Orange") && direction == "UP" -> {
                // Default unassigned UP slot on Orange: Toggle Flashlight!
                hapticPulse()
                com.ghost.api.hardware.HardwareToolSet(context).flashlight("TOGGLE")
                return
            }
            boundPackage == null && label.contains("Orange") && direction == "TAP" -> {
                // Default unassigned TAP on Orange: System Settings!
                hapticPulse()
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    onDismiss()
                } catch (e: Exception) { 
                    Timber.e(e, "Failed to open Settings")
                }
                return
            }
            else -> null
        }

        if (command != null) {
            hapticPulse()
            onTextQuery(command)
            return
        }

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
            } catch (e: Exception) { 
                Timber.e(e, "Failed to launch bound app: $boundPackage")
            }
        }
    }

    private fun deployPuckSubMenu(parent: View, tx: Float, ty: Float, label: String, color: Int) {
        dismissSubMenus()
        Timber.i("Radial: Deploying joystick bubbles for $label")
        parent.animate().scaleX(1.3f).scaleY(1.3f).setDuration(180).start()

        val overlayW = if (width > 0) width.toFloat() else context.resources.displayMetrics.widthPixels.toFloat()
        val overlayH = if (height > 0) height.toFloat() else dpToPx(520).toFloat()
        val baseCenterX = overlayW / 2f + tx
        val baseCenterY = overlayH / 2f + ty

        val bubbleSize = dpToPx(38)
        val distance = dpToPx(56).toFloat()

        val directions = listOf("UP", "DOWN", "LEFT", "RIGHT")
        val offsets = listOf(
            Pair(0f, -distance), // UP
            Pair(0f, distance),  // DOWN
            Pair(-distance, 0f), // LEFT
            Pair(distance, 0f)   // RIGHT
        )

        val pm = context.packageManager

        directions.forEachIndexed { index, dir ->
            val offset = offsets[index]
            val boundPkg = prefs.getString("BIND_${label}_${dir}", null)

            val bubble = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#E6181822"))
                    setStroke(dpToPx(1.5f.toInt()), Color.argb(180, Color.red(color), Color.green(color), Color.blue(color)))
                }
                elevation = dpToPx(8).toFloat()

                if (boundPkg != null && !boundPkg.startsWith("/")) {
                    val appIcon = try { pm.getApplicationIcon(boundPkg) } catch (e: Exception) { null }
                    if (appIcon != null) {
                        val iv = ImageView(context).apply {
                            setImageDrawable(appIcon)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            val iconSize = dpToPx(24)
                            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                                gravity = Gravity.CENTER
                            }
                        }
                        addView(iv)
                    } else {
                        val tv = TextView(context).apply {
                            text = boundPkg.split('.').lastOrNull()?.take(2)?.uppercase() ?: "?"
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                        }
                        addView(tv)
                    }
                } else if (boundPkg?.startsWith("/") == true) {
                    val iconText = when (boundPkg) {
                        "/scan" -> "📷"
                        "/search" -> "🔍"
                        "/diary" -> "📔"
                        "/tools" -> "🛠️"
                        else -> "⚡"
                    }
                    val tv = TextView(context).apply {
                        text = iconText
                        textSize = 16f
                        gravity = Gravity.CENTER
                    }
                    addView(tv)
                } else {
                    val tv = TextView(context).apply {
                        text = "+"
                        textSize = 18f
                        setTextColor(Color.parseColor("#99FFFFFF"))
                        gravity = Gravity.CENTER
                    }
                    addView(tv)
                }

                layoutParams = LayoutParams(bubbleSize, bubbleSize).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                x = baseCenterX - bubbleSize / 2f
                y = baseCenterY - bubbleSize / 2f
                scaleX = 0f
                scaleY = 0f
                alpha = 0f
            }

            addView(bubble)
            activeSlots.add(bubble)
            activeBubbleSlots.add(BubbleSlot(bubble, dir, offset.first, offset.second, boundPkg))

            bubble.animate()
                .translationX(baseCenterX - bubbleSize / 2f + offset.first)
                .translationY(baseCenterY - bubbleSize / 2f + offset.second)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                .setDuration(220)
                .setStartDelay((index * 20).toLong())
                .start()
        }
    }

    private fun deploySparkleSubMenu(parent: View) {
        dismissSubMenus()
        Timber.i("Radial: Deploying Sparkle bubbles")
        parent.animate().scaleX(1.3f).scaleY(1.3f).setDuration(180).start()

        val parentLoc = IntArray(2)
        parent.getLocationInWindow(parentLoc)
        val overlayLoc = IntArray(2)
        this@InputOverlay.getLocationInWindow(overlayLoc)

        val centerX = (parentLoc[0] - overlayLoc[0]) + parent.width / 2f
        val centerY = (parentLoc[1] - overlayLoc[1]) + parent.height / 2f

        val bubbleSize = dpToPx(38)
        val distance = dpToPx(56).toFloat()

        val directions = listOf("UP", "DOWN")
        val offsets = listOf(
            Pair(0f, -distance), // UP
            Pair(0f, distance)   // DOWN
        )

        val pm = context.packageManager

        directions.forEachIndexed { index, dir ->
            val offset = offsets[index]
            val boundPkg = prefs.getString("BIND_Sparkle_${dir}", null)

            val bubble = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#E6181822"))
                    setStroke(dpToPx(1.5f.toInt()), Color.argb(180, Color.red(colorAccent), Color.green(colorAccent), Color.blue(colorAccent)))
                }
                elevation = dpToPx(8).toFloat()

                if (boundPkg != null && !boundPkg.startsWith("/")) {
                    val appIcon = try { pm.getApplicationIcon(boundPkg) } catch (e: Exception) { null }
                    if (appIcon != null) {
                        val iv = ImageView(context).apply {
                            setImageDrawable(appIcon)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            val iconSize = dpToPx(24)
                            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                                gravity = Gravity.CENTER
                            }
                        }
                        addView(iv)
                    } else {
                        val tv = TextView(context).apply {
                            text = boundPkg.split('.').lastOrNull()?.take(2)?.uppercase() ?: "?"
                            textSize = 12f
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                        }
                        addView(tv)
                    }
                } else {
                    val tv = TextView(context).apply {
                        text = "+"
                        textSize = 18f
                        setTextColor(Color.parseColor("#99FFFFFF"))
                        gravity = Gravity.CENTER
                    }
                    addView(tv)
                }

                layoutParams = LayoutParams(bubbleSize, bubbleSize).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                x = centerX - bubbleSize / 2f
                y = centerY - bubbleSize / 2f
                scaleX = 0f
                scaleY = 0f
                alpha = 0f
            }

            addView(bubble)
            activeSlots.add(bubble)
            activeBubbleSlots.add(BubbleSlot(bubble, dir, offset.first, offset.second, boundPkg))

            bubble.animate()
                .translationX(centerX - bubbleSize / 2f + offset.first)
                .translationY(centerY - bubbleSize / 2f + offset.second)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                .setDuration(220)
                .setStartDelay((index * 20).toLong())
                .start()
        }
    }

    private fun retractSubMenu(tx: Float, ty: Float) {
        val overlayW = if (width > 0) width.toFloat() else context.resources.displayMetrics.widthPixels.toFloat()
        val overlayH = if (height > 0) height.toFloat() else dpToPx(520).toFloat()
        val baseCenterX = overlayW / 2f + tx
        val baseCenterY = overlayH / 2f + ty
        val bubbleSize = dpToPx(38)

        activeBubbleSlots.forEach { slot ->
            slot.view.animate()
                .translationX(baseCenterX - bubbleSize / 2f)
                .translationY(baseCenterY - bubbleSize / 2f)
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(160)
                .withEndAction { removeView(slot.view) }
                .start()
        }
        activeSlots.clear()
        activeBubbleSlots.clear()
    }

    private fun retractSparkleSubMenu(sparkleView: View) {
        val parentLoc = IntArray(2)
        sparkleView.getLocationInWindow(parentLoc)
        val overlayLoc = IntArray(2)
        this@InputOverlay.getLocationInWindow(overlayLoc)
        val centerX = (parentLoc[0] - overlayLoc[0]) + sparkleView.width / 2f
        val centerY = (parentLoc[1] - overlayLoc[1]) + sparkleView.height / 2f
        val bubbleSize = dpToPx(38)

        activeBubbleSlots.forEach { slot ->
            slot.view.animate()
                .translationX(centerX - bubbleSize / 2f)
                .translationY(centerY - bubbleSize / 2f)
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(160)
                .withEndAction { removeView(slot.view) }
                .start()
        }
        activeSlots.clear()
        activeBubbleSlots.clear()
    }

    private fun highlightBubble(dir: String, themeColor: Int) {
        activeBubbleSlots.forEach { slot ->
            if (slot.direction == dir) {
                slot.view.animate()
                    .scaleX(1.32f)
                    .scaleY(1.32f)
                    .alpha(1.0f)
                    .setDuration(120)
                    .start()
                (slot.view.background as? GradientDrawable)?.setStroke(
                    dpToPx(2),
                    Color.WHITE
                )
            } else {
                slot.view.animate()
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .alpha(0.45f)
                    .setDuration(120)
                    .start()
                (slot.view.background as? GradientDrawable)?.setStroke(
                    dpToPx(1),
                    Color.argb(90, Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor))
                )
            }
        }
    }

    private fun resetBubblesHighlight(themeColor: Int) {
        activeBubbleSlots.forEach { slot ->
            slot.view.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(120)
                .start()
            (slot.view.background as? GradientDrawable)?.setStroke(
                dpToPx(1.5f.toInt()),
                Color.argb(180, Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor))
            )
        }
    }

    private fun handleBubbleSelected(label: String, direction: String, color: Int) {
        val selectedSlot = activeBubbleSlots.find { it.direction == direction }
        selectedSlot?.view?.animate()
            ?.scaleX(1.4f)
            ?.scaleY(1.4f)
            ?.alpha(0f)
            ?.setDuration(150)
            ?.start()

        val boundPkg = prefs.getString("BIND_${label}_${direction}", null)
        if (boundPkg == null) {
            if (label.contains("Orange") && direction == "DOWN") {
                hapticPulse()
                dismissSubMenus()
                com.ghost.api.GemmaService.instance?.overlayManager?.showAppReel()
                return
            }
            if (label.contains("Orange") && direction == "UP") {
                hapticPulse()
                dismissSubMenus()
                com.ghost.api.hardware.HardwareToolSet(context).flashlight("TOGGLE")
                return
            }
            hapticPulse()
            dismissSubMenus()
            showAppPicker(label, direction)
        } else {
            hapticPulse()
            dismissSubMenus()
            launchBoundApp(label, direction)
        }
    }

    private fun dismissSubMenus() {
        com.ghost.api.GemmaService.instance?.overlayManager?.hideAppReel()
        activeSlots.forEach { slot ->
            slot.animate()
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(150)
                .withEndAction { removeView(slot) }
                .start()
        }
        activeSlots.clear()
        activeBubbleSlots.clear()

        appPickerLayout?.let { removeView(it); appPickerLayout = null }

        // Also reset main buttons scale
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v is TextView && v.text == "✧") {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }
    }

    data class AppPickerItem(val name: String, val pkgName: String, val icon: Drawable)

    companion object {
        @Volatile
        private var cachedAppList: List<AppPickerItem>? = null
    }

    private fun showAppPicker(parentLabel: String, direction: String) {
        if (appPickerLayout != null) removeView(appPickerLayout)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0181822"))
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#44FFFFFF"))
            }
            layoutParams = LayoutParams(dpToPx(290), dpToPx(390)).apply {
                gravity = Gravity.CENTER
            }
            elevation = dpToPx(20).toFloat()
            clipToOutline = true
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            setBackgroundColor(Color.parseColor("#252532"))
        }

        val title = TextView(context).apply {
            text = "Pin App • $direction"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#99FFFFFF"))
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            setOnClickListener {
                dismissSubMenus()
            }
        }
        header.addView(title)
        header.addView(closeBtn)
        container.addView(header)

        // Unbind / Clear button
        val currentBind = prefs.getString("BIND_${parentLabel}_${direction}", null)
        if (currentBind != null) {
            val clearBtn = TextView(context).apply {
                text = "✖ Unbind Current App"
                textSize = 12f
                setTextColor(Color.parseColor("#EF4444"))
                gravity = Gravity.CENTER
                setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
                setBackgroundColor(Color.parseColor("#1F1F2B"))
                setOnClickListener {
                    prefs.edit().remove("BIND_${parentLabel}_${direction}").apply()
                    hapticPulse()
                    dismissSubMenus()
                    Toast.makeText(context, "Slot cleared", Toast.LENGTH_SHORT).show()
                }
            }
            container.addView(clearBtn)
        }

        // Optimized recycled ListView (only inflates ~8 rows instead of 1000+ views)
        val listView = ListView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            divider = ColorDrawable(Color.parseColor("#15FFFFFF"))
            dividerHeight = 1
            isFastScrollEnabled = true
        }

        val progressBar = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply {
                gravity = Gravity.CENTER
                topMargin = dpToPx(80)
                bottomMargin = dpToPx(80)
            }
            visibility = View.GONE
        }
        container.addView(progressBar)
        container.addView(listView)

        fun bindList(apps: List<AppPickerItem>) {
            progressBar.visibility = View.GONE
            listView.visibility = View.VISIBLE
            listView.adapter = object : BaseAdapter() {
                override fun getCount(): Int = apps.size
                override fun getItem(pos: Int): Any = apps[pos]
                override fun getItemId(pos: Int): Long = pos.toLong()
                override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
                        val iv = ImageView(context).apply {
                            id = 2001
                            layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)).apply {
                                marginEnd = dpToPx(12)
                            }
                        }
                        val tv = TextView(context).apply {
                            id = 2002
                            textSize = 14f
                            setTextColor(Color.WHITE)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(iv)
                        addView(tv)
                    }
                    val item = apps[pos]
                    row.findViewById<ImageView>(2001).setImageDrawable(item.icon)
                    row.findViewById<TextView>(2002).text = item.name
                    row.setOnClickListener {
                        prefs.edit().putString("BIND_${parentLabel}_${direction}", item.pkgName).apply()
                        hapticPulse()
                        dismissSubMenus()
                        Toast.makeText(context, "Pinned ${item.name}", Toast.LENGTH_SHORT).show()
                    }
                    return row
                }
            }
        }

        val cached = cachedAppList
        if (cached != null) {
            bindList(cached)
        } else {
            progressBar.visibility = View.VISIBLE
            listView.visibility = View.GONE
            CoroutineScope(Dispatchers.IO).launch {
                val pm = context.packageManager
                val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                }
                val list = pm.queryIntentActivities(intent, 0).mapNotNull { info ->
                    try {
                        val name = info.loadLabel(pm).toString()
                        val pkg = info.activityInfo.packageName
                        val icon = info.loadIcon(pm)
                        AppPickerItem(name, pkg, icon)
                    } catch (e: Exception) { null }
                }.sortedBy { it.name.lowercase() }
                
                cachedAppList = list
                withContext(Dispatchers.Main) {
                    bindList(list)
                }
            }
        }

        appPickerLayout = container
        addView(container)
    }

    private fun vibrateLongPress() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 30, 40, 40), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(60)
            }
        } catch (e: Exception) {
            Timber.w(e, "Haptic long press failed")
        }
    }


    // Haptic pulse — used by sparkle and radial button interactions
    private fun hapticPulse() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            Timber.w(e, "Haptic failed")
        }
    }

    fun setThinking(thinking: Boolean) {
        isThinkingState = thinking
        voiceController.setThinking(thinking)
        if (thinking) {
            sparkleButton.setTextColor(Color.parseColor("#F59E0B")) // Amber
        } else {
            sparkleButton.setTextColor(colorAccent)
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
            inputField.hint = "Ask GHOST..."
            sparkleButton.setTextColor(colorAccent)
            sparkleButton.alpha = 1f
        }
    }



    fun getInputText(): String = inputField.text.toString()

    fun setInputText(text: String) {
        inputField.setText(text)
        inputField.setSelection(text.length)
        voiceController.syncButton()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        post {
            com.ghost.api.GemmaService.instance?.overlayManager?.handleConfigurationChanged()
        }
    }

    fun focusInput() {
        inputField.requestFocus()
    }

    fun appendText(text: String) {
        inputField.append(text)
    }

    fun cleanup() {
        dismissSubMenus()
        activeSlots.forEach { removeView(it) }
        activeSlots.clear()
        activeBubbleSlots.clear()
        appPickerLayout?.let { removeView(it); appPickerLayout = null }
        voiceController.cleanup()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
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

    private fun createCircleBackground(bgColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
            // Removed setStroke to eliminate the white ring outlines on transparent buttons
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
