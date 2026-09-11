package com.ghost.api.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.ghost.api.audio.SystemVisualizer
import timber.log.Timber
import kotlin.math.sin

/**
 * Lightweight floating HUD work indicator.
 * Positioned in the sweet spot gap directly below the status bar icons and above the top widgets.
 *
 * Features:
 * - Playful terminal monospace flavor text (Minecraft / Terraria / Claude Code style)
 * - Animated 4-wing cyber star / ghost shell that expands, tilts, and breathes
 * - Concentric glowing eye / iris (Option A inspired)
 * - Zero WebView, zero GPU collisions, hardware-accelerated 2D Canvas rendering
 */
@SuppressLint("ViewConstructor")
class GhostWorkIndicatorOverlay(
    context: Context,
    private val windowManager: WindowManager
) : View(context), Choreographer.FrameCallback {

    private val density = context.resources.displayMetrics.density

    // Flavor text dictionaries for operations
    object FlavorTexts {
        private val SEARCH_FLAVORS = listOf(
            "querying the ether...",
            "spelunking duckduckgo...",
            "scouring the infosphere...",
            "consulting the oracle...",
            "intercepting web packets...",
            "surfing the datastream...",
            "fishing for answers...",
            "reading the matrix...",
            "crawling the web web...",
            "asking the void..."
        )

        private val TERMINAL_FLAVORS = listOf(
            "bashing bits...",
            "talking to the kernel...",
            "invoking root magic...",
            "wrestling adb daemon...",
            "running shady bash...",
            "allocating more ram...",
            "poking the shell...",
            "compiling chaos..."
        )

        private val FETCH_FLAVORS = listOf(
            "scraping electrons...",
            "downloading reality...",
            "parsing raw html...",
            "vacuuming data...",
            "ingesting payload..."
        )

        private val GENERAL_FLAVORS = listOf(
            "pondering the orb...",
            "brewing thoughts...",
            "herding electric sheep...",
            "recalibrating flux...",
            "spinning up synapses...",
            "crunching tensors...",
            "defying gravity...",
            "untangling strings..."
        )

        fun pick(tag: String): String {
            val list = when {
                tag.contains("SEARCH") -> SEARCH_FLAVORS
                tag.contains("TERMINAL") || tag.contains("BASH") || tag.contains("ADB") -> TERMINAL_FLAVORS
                tag.contains("FETCH") -> FETCH_FLAVORS
                else -> GENERAL_FLAVORS
            }
            return "> " + list.random()
        }
    }

    // Drawing Paints - Preallocated for Zero-GC 60fps/120fps rendering
    private val paintCapsuleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintCapsuleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }
    private val paintEyeCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(16, 20, 26)
    }
    private val paintEyeIris = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }
    private val paintEyePupil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val paintEyeGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintWingLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(245, 248, 252) // Lit titanium facet
    }
    private val paintWingRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(168, 180, 192) // Shaded titanium facet
    }
    private val paintWingStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        color = Color.rgb(28, 34, 40)
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 240, 248) // Soft terminal cyan
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        letterSpacing = 0.04f
    }

    // Preallocated Paths & Rects
    private val wingPathLeft = Path()
    private val wingPathRight = Path()
    private val capsuleRect = RectF()

    // State & Animation
    private var currentAlpha = 0f
    private var openProgress = 0f
    private var animTime = 0f
    private var isFrameCallbackRunning = false
    private var currentFlavorText = "> querying the ether..."
    private var isAttachedToWindow = false
    private var showTimestamp: Long = 0L
    private val minDisplayDurationMs = 1300L

    private var alphaAnimator: ValueAnimator? = null
    private var openAnimator: ValueAnimator? = null

    // Window layout: Hugs the top gap right below status bar (y = 28dp), above top widgets
    val windowParams: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = (14 * density).toInt()
        y = (28 * density).toInt() // Hugs top bar gap cleanly above widgets
    }

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val textWidth = paintText.measureText(currentFlavorText)
        val h = (27 * density).toInt()
        // text + left padding (10dp) + gap between text & star (8dp) + star diameter (20dp) + right padding (6dp)
        val w = (textWidth + 44 * density).toInt()
        setMeasuredDimension(w, h)
    }

    fun show(tag: String = "WORKING", durationMs: Long = 0) {
        showTimestamp = System.currentTimeMillis()
        currentFlavorText = FlavorTexts.pick(tag)
        requestLayout()

        // Attach to window manager if not already attached
        if (!isAttachedToWindow) {
            try {
                windowManager.addView(this, windowParams)
                isAttachedToWindow = true
            } catch (e: Exception) {
                Timber.w(e, "GhostWorkIndicatorOverlay failed to attach to WindowManager")
                return
            }
        } else {
            try {
                windowManager.updateViewLayout(this, windowParams)
            } catch (e: Exception) {
                Timber.w(e, "GhostWorkIndicatorOverlay layout update failed")
            }
        }

        visibility = View.VISIBLE

        // Animate in Alpha
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(currentAlpha, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Animate Wing Opening
        openAnimator?.cancel()
        openAnimator = ValueAnimator.ofFloat(openProgress, 1f).apply {
            duration = 320
            interpolator = OvershootInterpolator(1.25f)
            addUpdateListener {
                openProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        startAnimationLoop()

        removeCallbacks(autoHideRunnable)
        if (durationMs > 0) {
            postDelayed(autoHideRunnable, durationMs)
        }
    }

    private val autoHideRunnable = Runnable {
        hide()
    }

    fun hide() {
        removeCallbacks(autoHideRunnable)
        if (!isAttachedToWindow) return

        // Ensure the indicator stays visible long enough to be appreciated even on sub-second operations
        val elapsed = System.currentTimeMillis() - showTimestamp
        if (elapsed < minDisplayDurationMs) {
            postDelayed({ hide() }, minDisplayDurationMs - elapsed)
            return
        }

        // Animate Wings Closing
        openAnimator?.cancel()
        openAnimator = ValueAnimator.ofFloat(openProgress, 0f).apply {
            duration = 220
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                openProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Animate Fade Out
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(currentAlpha, 0f).apply {
            duration = 240
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                currentAlpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    stopAnimationLoop()
                    detachSafely()
                }
            })
            start()
        }
    }

    private fun detachSafely() {
        if (isAttachedToWindow) {
            try {
                windowManager.removeView(this)
            } catch (e: Exception) {
                Timber.w(e, "GhostWorkIndicatorOverlay detach failed")
            } finally {
                isAttachedToWindow = false
            }
        }
    }

    private fun startAnimationLoop() {
        if (!isFrameCallbackRunning) {
            isFrameCallbackRunning = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopAnimationLoop() {
        isFrameCallbackRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (isFrameCallbackRunning && isAttachedToWindow) {
            animTime += 0.035f
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (currentAlpha <= 0.01f) return

        val w = width.toFloat()
        val h = height.toFloat()
        val accentCyan = SystemVisualizer.currentAlbumColors?.getOrNull(0) ?: Color.rgb(0, 229, 255)

        // 1. Sleek Cyber Capsule Container (height = 27dp)
        capsuleRect.set(1.2f * density, 1.2f * density, w - 1.2f * density, h - 1.2f * density)
        val capsuleRadius = (h - 2.4f * density) / 2f

        paintCapsuleBg.color = Color.argb((185 * currentAlpha).toInt(), 10, 14, 20)
        canvas.drawRoundRect(capsuleRect, capsuleRadius, capsuleRadius, paintCapsuleBg)

        paintCapsuleStroke.color = Color.argb(
            (70 * currentAlpha * (0.8f + 0.2f * sin(animTime * 3f))).toInt().coerceIn(0, 255),
            Color.red(accentCyan), Color.green(accentCyan), Color.blue(accentCyan)
        )
        canvas.drawRoundRect(capsuleRect, capsuleRadius, capsuleRadius, paintCapsuleStroke)

        // 2. Terminal Monospace Flavor Text
        paintText.alpha = (245 * currentAlpha).toInt()
        val textStartX = 11f * density
        val textY = h / 2f + (3.4f * density)
        canvas.drawText(currentFlavorText, textStartX, textY, paintText)

        // 3. Compact Cyber Ghost Shell (Right side)
        val ghostCx = w - 16f * density
        val ghostCy = h / 2f

        val pulse = (0.5f + 0.5f * sin(animTime * 5.0f)).coerceIn(0f, 1f)
        val breathing = sin(animTime * 3.2f) * (0.9f * density)
        val spread = openProgress * (5.2f * density) + (if (openProgress > 0.4f) breathing else 0f)
        val tiltAngle = if (openProgress > 0.1f) sin(animTime * 2.2f) * 14f else 0f

        canvas.save()
        canvas.translate(ghostCx, ghostCy)
        canvas.rotate(tiltAngle)

        // 3A. Central Eye Core (Dark Sphere with Glowing Concentric Iris)
        val eyeRadius = 3.8f * density
        paintEyeCore.alpha = (255 * currentAlpha).toInt()
        canvas.drawCircle(0f, 0f, eyeRadius, paintEyeCore)

        // Iris Bloom Aura
        paintEyeGlow.color = Color.argb(
            (100 * pulse * currentAlpha * openProgress.coerceAtLeast(0.3f)).toInt().coerceIn(0, 255),
            Color.red(accentCyan), Color.green(accentCyan), Color.blue(accentCyan)
        )
        canvas.drawCircle(0f, 0f, eyeRadius * (1.3f + 0.4f * pulse), paintEyeGlow)

        // Concentric Iris Rings (Option A Inspired)
        paintEyeIris.color = Color.argb(
            (240 * currentAlpha).toInt(),
            Color.red(accentCyan), Color.green(accentCyan), Color.blue(accentCyan)
        )
        canvas.drawCircle(0f, 0f, eyeRadius * 0.78f, paintEyeIris)

        // Center Pupil Dot
        paintEyePupil.alpha = (255 * currentAlpha).toInt()
        canvas.drawCircle(0f, 0f, 1.3f * density * (0.85f + 0.25f * pulse), paintEyePupil)

        // 3B. The 4 Geometric Shell Wings (Compact scaled for 27dp capsule)
        val apexR = 9.8f * density
        val shoulderR = 6.2f * density
        val wingWidth = 4.2f * density
        val innerR = 3.8f * density
        val baseWidth = 2.8f * density
        val notchR = 3.1f * density

        paintWingLeft.alpha = (245 * currentAlpha).toInt()
        paintWingRight.alpha = (235 * currentAlpha).toInt()
        paintWingStroke.alpha = (220 * currentAlpha).toInt()

        for (i in 0 until 4) {
            val wingAngle = i * 90f - 45f
            canvas.save()
            canvas.rotate(wingAngle)
            canvas.translate(0f, -spread)

            // Left Facet (Lit titanium)
            wingPathLeft.reset()
            wingPathLeft.moveTo(0f, -notchR)
            wingPathLeft.lineTo(-baseWidth, -innerR)
            wingPathLeft.lineTo(-wingWidth, -shoulderR)
            wingPathLeft.lineTo(0f, -apexR)
            wingPathLeft.close()
            canvas.drawPath(wingPathLeft, paintWingLeft)

            // Right Facet (Shaded titanium for 3D depth)
            wingPathRight.reset()
            wingPathRight.moveTo(0f, -notchR)
            wingPathRight.lineTo(0f, -apexR)
            wingPathRight.lineTo(wingWidth, -shoulderR)
            wingPathRight.lineTo(baseWidth, -innerR)
            wingPathRight.close()
            canvas.drawPath(wingPathRight, paintWingRight)

            // Facet Seams & Outer Contours
            canvas.drawPath(wingPathLeft, paintWingStroke)
            canvas.drawPath(wingPathRight, paintWingStroke)

            canvas.restore()
        }

        canvas.restore()
    }
}
