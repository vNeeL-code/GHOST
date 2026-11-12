package com.ghost.api.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.ghost.api.Constants
import com.ghost.api.GemmaService
import com.ghost.api.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * InputOverlay - Hyper Pucks Voice/Text & Radial Action HUD.
 *
 * - Central glassmorphic input bar with ✧ Turing sparkle & multimodal audio/text input.
 * - Up to 6 individually toggleable Hyper Pucks (`TOP`, `BOTTOM`, `TL`, `TR`, `BL`, `BR`).
 * - Disabled pucks are never instantiated in the View tree (zero CPU/GPU draw overhead).
 * - Each enabled puck supports 5 programmable directions (`TAP`, `UP`, `DOWN`, `LEFT`, `RIGHT`)
 *   routed through `OverlayActionRegistry` (Navigation, Media, System toggles, Scratchpad,
 *   Quick App Folder, or any installed App).
 * - All puck configuration lives cleanly in `SettingsDialog.kt`.
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
    private var appFolderPopup: View? = null

    // Premium Glassmorphic Palette
    private val colorSurface = Color.parseColor("#CC121212")
    private val colorSurfaceVariant = Color.parseColor("#33FFFFFF")
    private val colorOnSurface = Color.WHITE
    private val colorAccent = Color.parseColor("#8BB4F6")

    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val metrics = context.resources.displayMetrics
        val isLandscape = metrics.widthPixels > metrics.heightPixels
        val frameHeight = if (isLandscape) ViewGroup.LayoutParams.MATCH_PARENT else dpToPx(480)

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

        // ✧ Sparkle button (Center Cobalt Turing Star) — Tap, Up swipe, Down swipe
        sparkleButton = TextView(context).apply {
            text = "✧"
            textSize = 28f
            setTextColor(Color.parseColor(OverlayActionRegistry.PuckId.CENTER.colorHex)) // Cobalt Turing core
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
            background = createCircleBackground(Color.TRANSPARENT)

            var startX = 0f
            var startY = 0f
            var isMoving = false
            val threshold = dpToPx(16).toFloat()

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isMoving = false
                        v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        if (abs(dy) >= threshold) {
                            isMoving = true
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        val dy = event.rawY - startY
                        val slot = if (isMoving && abs(dy) >= threshold) {
                            if (dy < 0) OverlayActionRegistry.DirectionSlot.UP else OverlayActionRegistry.DirectionSlot.DOWN
                        } else {
                            OverlayActionRegistry.DirectionSlot.TAP
                        }
                        val action = OverlayActionRegistry.getSlotAction(prefs, OverlayActionRegistry.PuckId.CENTER, slot)
                        if (action != OverlayActionRegistry.ACTION_NONE) {
                            hapticPulse()
                            val shouldDismiss = OverlayActionRegistry.executeAction(
                                context = context,
                                actionSpec = action,
                                onSummonOverlay = { },
                                onOpenAppFolder = { toggleAppFolderPopup() }
                            )
                            if (shouldDismiss) {
                                onDismiss()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        true
                    }
                    else -> false
                }
            }
        }

        inputField = EditText(context).apply {
            hint = Constants.getUserGlyph(prefs)
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
                GemmaService.instance?.resumeEngineIfNeeded()
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    onFocusRequest()
                    GemmaService.instance?.resumeEngineIfNeeded()
                }
            }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    voiceController.handleTap()
                    true
                } else false
            }
        }

        voiceSendButton = TextView(context).apply {
            text = "🔵"
            textSize = 20f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            visibility = VISIBLE
        }

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
                onTextQuery(text)
            }
        )

        bar.addView(sparkleButton)
        bar.addView(inputField)
        bar.addView(voiceSendButton)

        setOnClickListener {
            if (appFolderPopup != null) {
                dismissAppFolderPopup()
            } else {
                Timber.i("InputOverlay: Outside tap detected - dismissing")
                onDismiss()
            }
        }

        addView(bar)
        addRadialButtons()
    }

    private fun addRadialButtons() {
        if (!prefs.getBoolean(Constants.PREF_HYPER_PUCKS_ENABLED, true)) return

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val isLandscape = screenW > screenH

        val spreadScale = prefs.getFloat(Constants.PREF_PUCK_SPREAD_SCALE, 1.0f).coerceIn(0.65f, 1.45f)
        val horizontalSpread = (if (isLandscape) 1.55f else 1.0f) * spreadScale
        val horizontalOffset = (dpToPx(90) * horizontalSpread).toInt()
        val verticalOffset = ((if (isLandscape) dpToPx(56) else dpToPx(105)) * spreadScale).toInt()
        val apexVerticalOffset = ((if (isLandscape) dpToPx(102) else dpToPx(182)) * spreadScale).toInt()

        val placements = listOf(
            Triple(OverlayActionRegistry.PuckId.TOP, 0f, -apexVerticalOffset.toFloat()),
            Triple(OverlayActionRegistry.PuckId.BOTTOM, 0f, apexVerticalOffset.toFloat()),
            Triple(OverlayActionRegistry.PuckId.TL, -horizontalOffset.toFloat(), -verticalOffset.toFloat()),
            Triple(OverlayActionRegistry.PuckId.TR, horizontalOffset.toFloat(), -verticalOffset.toFloat()),
            Triple(OverlayActionRegistry.PuckId.BL, -horizontalOffset.toFloat(), verticalOffset.toFloat()),
            Triple(OverlayActionRegistry.PuckId.BR, horizontalOffset.toFloat(), verticalOffset.toFloat())
        )

        for ((puckId, tx, ty) in placements) {
            if (OverlayActionRegistry.isPuckEnabled(prefs, puckId)) {
                setupRadialPuck(puckId, Color.parseColor(puckId.colorHex), tx, ty)
            }
        }

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
        val dist = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
        if (dist == 0f) return
        val maxRadius = dpToPx(24).toFloat()
        val clampedDist = min(dist * 0.5f, maxRadius)
        val factor = clampedDist / dist
        view.translationX = baseTx + (rawDx * factor)
        view.translationY = baseTy + (rawDy * factor)
    }

    private fun resetPuckSpring(view: TextView, baseTx: Float, baseTy: Float, defaultGlyph: String = "✧") {
        view.text = defaultGlyph
        view.animate()
            .translationX(baseTx)
            .translationY(baseTy)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setInterpolator(OvershootInterpolator(1.8f))
            .setDuration(220)
            .start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRadialPuck(puckId: OverlayActionRegistry.PuckId, color: Int, tx: Float, ty: Float) {
        val socket = createBaseSocket(color, tx, ty)
        addView(socket)

        val btnSize = dpToPx(48)
        val btn = TextView(context).apply {
            text = "✧"
            textSize = 22f
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
            var activeSlot: OverlayActionRegistry.DirectionSlot = OverlayActionRegistry.DirectionSlot.TAP

            setOnTouchListener { v, event ->
                if (appFolderPopup != null) return@setOnTouchListener true
                val threshold = dpToPx(18).toFloat()
                val textView = v as TextView

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        activeSlot = OverlayActionRegistry.DirectionSlot.TAP
                        val tapAction = OverlayActionRegistry.getSlotAction(prefs, puckId, OverlayActionRegistry.DirectionSlot.TAP)
                        textView.text = OverlayActionRegistry.getShortGlyph(tapAction)
                        v.animate().scaleX(1.18f).scaleY(1.18f).setDuration(90).start()
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        applyPuckWiggle(v, tx, ty, startX, startY, event.rawX, event.rawY)
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val totalDist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        val newSlot = if (totalDist >= threshold) {
                            if (abs(dx) > abs(dy)) {
                                if (dx > 0) OverlayActionRegistry.DirectionSlot.RIGHT else OverlayActionRegistry.DirectionSlot.LEFT
                            } else {
                                if (dy > 0) OverlayActionRegistry.DirectionSlot.DOWN else OverlayActionRegistry.DirectionSlot.UP
                            }
                        } else {
                            OverlayActionRegistry.DirectionSlot.TAP
                        }

                        if (newSlot != activeSlot) {
                            activeSlot = newSlot
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            val actionSpec = OverlayActionRegistry.getSlotAction(prefs, puckId, activeSlot)
                            textView.text = OverlayActionRegistry.getShortGlyph(actionSpec)
                            if (newSlot == OverlayActionRegistry.DirectionSlot.TAP) {
                                triggerSocketMagneticSparkle(socket)
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val selectedSlot = activeSlot
                        resetPuckSpring(textView, tx, ty)
                        val actionSpec = OverlayActionRegistry.getSlotAction(prefs, puckId, selectedSlot)
                        if (actionSpec != OverlayActionRegistry.ACTION_NONE) {
                            hapticPulse()
                            val shouldDismiss = OverlayActionRegistry.executeAction(
                                context = context,
                                actionSpec = actionSpec,
                                onSummonOverlay = { },
                                onOpenAppFolder = { toggleAppFolderPopup() }
                            )
                            if (shouldDismiss) {
                                onDismiss()
                            } else {
                                triggerSocketMagneticSparkle(socket)
                            }
                        } else {
                            triggerSocketMagneticSparkle(socket)
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        resetPuckSpring(textView, tx, ty)
                        true
                    }

                    else -> false
                }
            }
        }
        addView(btn)
    }

    /**
     * Lightweight on-demand Quick App Folder popup (replaces the 429-line AppReelOverlay).
     * Queries launcher activities asynchronously only when opened.
     */
    fun toggleAppFolderPopup() {
        if (appFolderPopup != null) {
            dismissAppFolderPopup()
            return
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor("#F214141E"))
                setStroke(dpToPx(1), colorAccent)
            }
            elevation = dpToPx(16).toFloat()
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
            layoutParams = LayoutParams(
                dpToPx(310),
                dpToPx(340)
            ).apply {
                gravity = Gravity.CENTER
            }
            isClickable = true
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
        }

        val title = TextView(context).apply {
            text = "📂 QUICK APP FOLDER"
            textSize = 12f
            setTextColor(colorAccent)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            setOnClickListener { dismissAppFolderPopup() }
        }

        header.addView(title)
        header.addView(closeBtn)
        card.addView(header)

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val grid = GridLayout(context).apply {
            columnCount = 4
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(grid)
        card.addView(scroll)

        appFolderPopup = card
        addView(card)

        CoroutineScope(Dispatchers.IO).launch {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    val label = try { info.loadLabel(pm).toString() } catch (_: Exception) { pkg }
                    val icon = try { info.loadIcon(pm) } catch (_: Exception) { null }
                    Triple(label, pkg, icon)
                }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }

            withContext(Dispatchers.Main) {
                if (appFolderPopup !== card) return@withContext
                val cellWidth = dpToPx(68)
                for ((label, pkg, icon) in apps) {
                    val cell = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6))
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = cellWidth
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        setOnClickListener {
                            hapticPulse()
                            OverlayActionRegistry.executeAction(context, "${OverlayActionRegistry.APP_PREFIX}$pkg")
                            dismissAppFolderPopup()
                            onDismiss()
                        }
                    }
                    val img = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
                        setImageDrawable(icon)
                    }
                    val txt = TextView(context).apply {
                        text = label
                        textSize = 10f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }
                    cell.addView(img)
                    cell.addView(txt)
                    grid.addView(cell)
                }
            }
        }
    }

    private fun dismissAppFolderPopup() {
        appFolderPopup?.let {
            removeView(it)
            appFolderPopup = null
        }
    }

    private fun hapticPulse() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(45)
            }
        } catch (e: Exception) {
            Timber.w(e, "Haptic failed")
        }
    }

    fun setThinking(thinking: Boolean) {
        isThinkingState = thinking
        voiceController.setThinking(thinking)
        if (thinking) {
            sparkleButton.setTextColor(Color.parseColor("#F59E0B"))
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
            GemmaService.instance?.overlayManager?.handleConfigurationChanged()
        }
    }

    fun focusInput() {
        inputField.requestFocus()
    }

    fun appendText(text: String) {
        inputField.append(text)
    }

    fun cleanup() {
        dismissAppFolderPopup()
        voiceController.cleanup()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }

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
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
