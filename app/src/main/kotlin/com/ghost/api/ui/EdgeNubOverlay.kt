package com.ghost.api.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import com.ghost.api.Constants
import timber.log.Timber
import kotlin.math.abs

/**
 * EdgeNubOverlay - A sleek, floating bezel handle docked to the right screen edge.
 *
 * Capabilities:
 * - Slide/swipe inward (to the left) triggers instant GHOST summon with haptic feedback.
 * - Tap on the handle also summons GHOST.
 * - Vertical drag allows repositioning along the bezel; position is persisted in SharedPreferences.
 * - Fully hardware-accelerated (FLAG_HARDWARE_ACCELERATED) for zero CPU rendering cost.
 */
@SuppressLint("ViewConstructor")
class EdgeNubOverlay(
    context: Context,
    private val windowManager: WindowManager,
    private val onSummon: () -> Unit
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    // Touch dimensions
    val viewWidth = (36 * density).toInt()  // Generous touch intercept width
    val viewHeight = (72 * density).toInt() // Bezel handle height

    // Visual geometry of the pill docked to the right edge
    private val pillWidth = 8 * density
    private val pillHeight = 56 * density
    private val pillCorner = 4 * density

    // Paint objects
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D9181822") // Translucent cyber-dark glass
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.parseColor("#668BB4F6") // Subtle ethereal cyan border
    }

    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#B38BB4F6")
        textAlign = Paint.Align.CENTER
        textSize = 9 * density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val accentGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#808BB4F6")
    }

    // Touch state tracking
    private var initialTouchRawX = 0f
    private var initialTouchRawY = 0f
    private var initialWindowY = 0
    private var isDraggingVertically = false
    private var hasTriggeredSummon = false
    private var isPressedState = false

    val windowParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        viewWidth,
        viewHeight,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 0
        y = resolveInitialY()
    }

    private fun resolveInitialY(): Int {
        val screenHeight = getScreenHeight()
        val defaultY = (screenHeight * 0.45f).toInt()
        val savedY = prefs.getInt(Constants.PREF_EDGE_NUB_Y, defaultY)
        return savedY.coerceIn((50 * density).toInt(), (screenHeight - 120 * density).toInt())
    }

    private fun getScreenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                windowManager.currentWindowMetrics.bounds.height()
            } catch (e: Exception) {
                context.resources.displayMetrics.heightPixels
            }
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.heightPixels
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // Pill rect hugged against the right screen edge
        val pillLeft = w - pillWidth
        val pillRight = w
        val pillTop = (h - pillHeight) / 2f
        val pillBottom = pillTop + pillHeight

        val pillRect = RectF(pillLeft, pillTop, pillRight, pillBottom)

        // Highlight colors when touched
        if (isPressedState) {
            bgPaint.color = Color.parseColor("#F220202E")
            borderPaint.color = Color.parseColor("#CC8BB4F6")
            glyphPaint.color = Color.WHITE
        } else {
            bgPaint.color = Color.parseColor("#B3161620")
            borderPaint.color = Color.parseColor("#4D8BB4F6")
            glyphPaint.color = Color.parseColor("#998BB4F6")
        }

        // Draw pill base with rounded left corners
        val path = Path()
        val radii = floatArrayOf(
            pillCorner, pillCorner, // Top-left
            0f, 0f,                 // Top-right (flush to edge)
            0f, 0f,                 // Bottom-right (flush to edge)
            pillCorner, pillCorner  // Bottom-left
        )
        path.addRoundRect(pillRect, radii, Path.Direction.CW)
        canvas.drawPath(path, bgPaint)
        canvas.drawPath(path, borderPaint)

        // Draw vertical center grip pill or ✧ motif
        val centerX = pillLeft + (pillWidth / 2f)
        val centerY = h / 2f

        // Small ✧ sparkle glyph or grip notch
        canvas.drawText("✧", centerX - (0.5f * density), centerY + (3.2f * density), glyphPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchRawX = event.rawX
                initialTouchRawY = event.rawY
                initialWindowY = windowParams.y
                isDraggingVertically = false
                hasTriggeredSummon = false
                isPressedState = true
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchRawX
                val dy = event.rawY - initialTouchRawY

                // Check inward swipe trigger (user drags handle to the left by >= 18dp)
                if (dx < -18 * density && !hasTriggeredSummon && !isDraggingVertically) {
                    hasTriggeredSummon = true
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    Timber.i("🎚️ EdgeNub inward swipe detected! Summoning GHOST overlay.")
                    onSummon()
                    isPressedState = false
                    invalidate()
                    return true
                }

                // Check vertical repositioning (user drags handle vertically by >= 8dp)
                if (abs(dy) > 8 * density || isDraggingVertically) {
                    isDraggingVertically = true
                    val screenHeight = getScreenHeight()
                    val minY = (40 * density).toInt()
                    val maxY = (screenHeight - 110 * density).toInt()
                    val newY = (initialWindowY + dy).toInt().coerceIn(minY, maxY)

                    if (windowParams.y != newY) {
                        windowParams.y = newY
                        try {
                            windowManager.updateViewLayout(this, windowParams)
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to update EdgeNub layout during drag")
                        }
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (isPressedState) {
                    if (!hasTriggeredSummon && !isDraggingVertically) {
                        // Quick tap on handle triggers summon
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        Timber.i("🎚️ EdgeNub tap detected! Summoning GHOST overlay.")
                        onSummon()
                    } else if (isDraggingVertically) {
                        // Persist repositioned Y coordinate
                        prefs.edit().putInt(Constants.PREF_EDGE_NUB_Y, windowParams.y).apply()
                        Timber.d("🎚️ EdgeNub new vertical position saved: ${windowParams.y}")
                    }
                }
                isPressedState = false
                isDraggingVertically = false
                hasTriggeredSummon = false
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isPressedState = false
                isDraggingVertically = false
                hasTriggeredSummon = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun attach() {
        if (parent == null) {
            try {
                windowParams.y = resolveInitialY()
                windowManager.addView(this, windowParams)
                alpha = 0f
                visibility = VISIBLE
                animate().alpha(1f).setDuration(200).start()
                Timber.d("EdgeNub overlay attached to WindowManager")
            } catch (e: Exception) {
                Timber.w(e, "Failed to attach EdgeNub overlay")
            }
        }
    }

    fun detach() {
        if (parent != null) {
            try {
                windowManager.removeView(this)
                Timber.d("EdgeNub overlay detached from WindowManager")
            } catch (e: Exception) {
                Timber.w(e, "Failed to detach EdgeNub overlay")
            }
        }
    }

    fun setNubVisibility(show: Boolean) {
        if (parent == null) return
        if (show) {
            if (visibility != VISIBLE) {
                alpha = 0f
                visibility = VISIBLE
                animate().alpha(1f).setDuration(150).start()
            }
        } else {
            if (visibility == VISIBLE) {
                animate().alpha(0f).setDuration(120).withEndAction {
                    visibility = GONE
                }.start()
            }
        }
    }
}
