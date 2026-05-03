package com.ghost.api

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ghost.api.ui.OverlayInputView

/**
 * Native Android Bubble Activity.
 * Provides a minimal chat interface within the OS bubble head.
 */
class BubbleActivity : Activity() {

    private var inputView: OverlayInputView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Minimal container for the Bubble
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Reuse the OverlayInputView logic for the bubble chat
        // Constructor takes (context, onSend callback)
        inputView = OverlayInputView(this) { query ->
            GemmaService.instance?.processQueryFromUi(query)
        }

        container.addView(inputView)
        setContentView(container)
    }

    override fun onResume() {
        super.onResume()
        // Focus the input when bubble expanded
        inputView?.focusInput()
    }

    override fun onDestroy() {
        inputView?.cleanup()
        super.onDestroy()
    }
}
