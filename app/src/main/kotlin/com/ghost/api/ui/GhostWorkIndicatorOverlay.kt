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
 * Destiny Ghost-inspired floating HUD work indicator.
 * Appears in the screen corner during headless background operations (web search,
 * shell execution, reasoning) to provide clear visual feedback without a heavy WebView.
 *
 * Features:
 * - 4 geometric shell wings that open outward, tilt, and breathe during operation
 * - Concentric glowing eye / iris (reminiscent of Wallpaper Option A)
 * - Tracked "G H O S T" label with operational tag (e.g. "// SEARCH")
 * - Pure Android Canvas rendering: zero WebView, zero GPU context collisions, hardware accelerated
 */
@SuppressLint("ViewConstructor")
class GhostWorkIndicatorOverlay(
    context: Context,
    private val windowManager: WindowManager
) : View(context), Choreographer.FrameCallback {

    private val density = context.resources.displayMetrics.density

    // Drawing Paints - Preallocated for Zero-GC 60fps/120fps rendering
    private val paintCapsuleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintCapsuleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val paintEyeCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(16, 20, 26)
    }
    private val paintEyeIris = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
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
        color = Color.rgb(172, 185, 196) // Shaded titanium facet
    }
    private val paintWingStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
        color = Color.rgb(28, 34, 40)
    }
    private val paintTextTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 245, 255)
        textSize = 11.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.15f
    }
    private val paintTextTag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 229, 255)
        textSize = 8.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.18f
    }

    // Wing Paths - Preallocated
    private val wingPathLeft = Path()
    private val wingPathRight = Path()
    private val capsuleRect = RectF()

    // State & Animation
    private var currentAlpha = 0f
    private var openProgress = 0f // 0f = closed tight diamond, 1f = fully opened wings
    private var animTime = 0f
    private var isFrameCallbackRunning = false
    private var currentTag = "WORKING"
    private var isAttachedToWindow = false

    private var alphaAnimator: ValueAnimator? = null
    private var openAnimator: ValueAnimator? = null

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
        x = (16 * density).toInt()
        y = (48 * density).toInt()
    }

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (142 * density).toInt()
        val h = (38 * density).toInt()
        setMeasuredDimension(w, h)
    }

    fun show(tag: String = "WORKING", durationMs: Long = 0) {
        currentTag = tag.trim().uppercase()
        
        // Attach to window manager if not already attached
        if (!isAttachedToWindow) {
            try {
                windowManager.addView(this, windowParams)
                isAttachedToWindow = true
            } catch (e: Exception) {
                Timber.w(e, "GhostWorkIndicatorOverlay failed to attach to WindowManager")
                return
            }
        }

        visibility = View.VISIBLE

        // Animate in Alpha
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(currentAlpha, 1f).apply {
            duration = 200
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
            duration = 340
            interpolator = OvershootInterpolator(1.3f)
            addUpdateListener {
                openProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        startAnimationLoop()

        // Optional safety auto-hide
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

        // Animate Wings Closing
        openAnimator?.cancel()
        openAnimator = ValueAnimator.ofFloat(openProgress, 0f).apply {
            duration = 240
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
            duration = 260
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

        // 1. Sleek Cyber Capsule Container
        capsuleRect.set(1.5f * density, 1.5f * density, w - 1.5f * density, h - 1.5f * density)
        val capsuleRadius = (h - 3f * density) / 2f

        paintCapsuleBg.color = Color.argb((185 * currentAlpha).toInt(), 10, 14, 20)
        canvas.drawRoundRect(capsuleRect, capsuleRadius, capsuleRadius, paintCapsuleBg)

        paintCapsuleStroke.color = Color.argb(
            (75 * currentAlpha * (0.8f + 0.2f * sin(animTime * 3f))).toInt().coerceIn(0, 255),
            Color.red(accentCyan), Color.green(accentCyan), Color.blue(accentCyan)
        )
        canvas.drawRoundRect(capsuleRect, capsuleRadius, capsuleRadius, paintCapsuleStroke)

        // 2. Tracked HUD Text: "G H O S T" and Operational Tag
        paintTextTitle.alpha = (245 * currentAlpha).toInt()
        val textStartX = 14f * density
        val titleY = if (currentTag.isNotBlank()) 16.5f * density else 23.5f * density
        canvas.drawText("G H O S T", textStartX, titleY, paintTextTitle)

        if (currentTag.isNotBlank()) {
            paintTextTag.color = accentCyan
            paintTextTag.alpha = (230 * currentAlpha * (0.7f + 0.3f * sin(animTime * 4f))).toInt().coerceIn(0, 255)
            canvas.drawText("// $currentTag", textStartX, 29f * density, paintTextTag)
        }

        // 3. Animated Destiny Ghost Shell (Right side)
        val ghostCx = w - 23f * density
        val ghostCy = h / 2f

        val pulse = (0.5f + 0.5f * sin(animTime * 5.0f)).coerceIn(0f, 1f)
        val breathing = sin(animTime * 3.2f) * (1.2f * density)
        val spread = openProgress * (7.5f * density) + (if (openProgress > 0.4f) breathing else 0f)
        val tiltAngle = if (openProgress > 0.1f) sin(animTime * 2.2f) * 16f else 0f

        canvas.save()
        canvas.translate(ghostCx, ghostCy)
        canvas.rotate(tiltAngle)

        // 3A. Central Eye Core (Dark Sphere with Glowing Cyan Concentric Iris)
        val eyeRadius = 5.2f * density
        paintEyeCore.alpha = (255 * currentAlpha).toInt()
        canvas.drawCircle(0f, 0f, eyeRadius, paintEyeCore)

        // Iris Bloom Aura
        paintEyeGlow.color = Color.argb(
            (110 * pulse * currentAlpha * openProgress.coerceAtLeast(0.3f)).toInt().coerceIn(0, 255),
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
        canvas.drawCircle(0f, 0f, 1.8f * density * (0.85f + 0.25f * pulse), paintEyePupil)

        // 3B. The 4 Geometric Shell Wings (Top-Right, Bottom-Right, Bottom-Left, Top-Left at 45°)
        val apexR = 13.5f * density
        val shoulderR = 8.5f * density
        val wingWidth = 5.8f * density
        val innerR = 5.2f * density
        val baseWidth = 3.8f * density
        val notchR = 4.2f * density

        paintWingLeft.alpha = (245 * currentAlpha).toInt()
        paintWingRight.alpha = (235 * currentAlpha).toInt()
        paintWingStroke.alpha = (220 * currentAlpha).toInt()

        for (i in 0 until 4) {
            val wingAngle = i * 90f - 45f
            canvas.save()
            canvas.rotate(wingAngle)
            canvas.translate(0f, -spread) // Translate radially outward

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
