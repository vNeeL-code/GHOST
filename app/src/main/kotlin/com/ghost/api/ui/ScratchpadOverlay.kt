package com.ghost.api.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import timber.log.Timber

/**
 * ScratchpadOverlay - Persistent floating HUD sticky note / scratchpad window.
 * Summoned by the bottom Cyan gesture puck (Swipe Up / Tap).
 * Automatically preserves notes across app launches and restarts.
 */
@SuppressLint("ViewConstructor")
class ScratchpadOverlay(
    context: Context,
    private val windowManager: WindowManager,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val prefs = context.getSharedPreferences("Gemma_Scratchpad", Context.MODE_PRIVATE)
    private val PREF_KEY_TEXT = "scratchpad_text"

    private val titleBar: LinearLayout
    private val editContent: EditText
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    val windowParams: WindowManager.LayoutParams

    init {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.88).toInt().coerceAtMost(dpToPx(420))
        val height = dpToPx(280)

        windowParams = WindowManager.LayoutParams(
            width, height, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121214"))
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#33A78BFA"))
            }
            elevation = dpToPx(12).toFloat()
            clipToOutline = true
        }

        // === TITLE BAR (Draggable) ===
        titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1E"))
            setPadding(dpToPx(14), dpToPx(10), dpToPx(10), dpToPx(10))

            // Title with Turing Machine Icon
            addView(TextView(context).apply {
                text = "Δ \uD83D\uDC7E ∇ • SCRATCHPAD"
                setTextColor(Color.parseColor("#A78BFA"))
                textSize = 13f
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            // Copy button
            addView(TextView(context).apply {
                text = "\uD83D\uDCCB"
                textSize = 15f
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                setOnClickListener {
                    val textToCopy = editContent.text.toString()
                    if (textToCopy.isNotBlank()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("GHOSt Scratchpad", textToCopy))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                }
            })

            // Clear button
            addView(TextView(context).apply {
                text = "✕"
                textSize = 14f
                setTextColor(Color.parseColor("#99FFFFFF"))
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                setOnClickListener {
                    editContent.setText("")
                    prefs.edit().remove(PREF_KEY_TEXT).apply()
                    Toast.makeText(context, "Scratchpad cleared", Toast.LENGTH_SHORT).show()
                }
            })

            // Minimize / Close button
            addView(TextView(context).apply {
                text = "—"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                setOnClickListener {
                    onDismiss()
                }
            })

            // Drag Handler on Title Bar
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = windowParams.x
                        initialY = windowParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        windowParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        windowParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(this@ScratchpadOverlay, windowParams)
                        true
                    }
                    else -> false
                }
            }
        }
        rootLayout.addView(titleBar)

        // === SCRATCHPAD TEXT BODY ===
        val savedNotes = prefs.getString(PREF_KEY_TEXT, "") ?: ""
        editContent = EditText(context).apply {
            setText(savedNotes)
            hint = "Quick notes, codes, phone numbers, ideas...\nAuto-saved indefinitely."
            setHintTextColor(Color.parseColor("#44FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f
            )

            // Auto-save on every keystroke
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(PREF_KEY_TEXT, s?.toString() ?: "").apply()
                }
            })
        }
        rootLayout.addView(editContent)

        addView(rootLayout)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
