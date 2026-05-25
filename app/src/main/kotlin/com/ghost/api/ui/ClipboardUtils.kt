package com.ghost.api.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import timber.log.Timber

object ClipboardUtils {
    fun copyToClipboard(context: Context, text: String, label: String = "Δ \uD83D\uDC7E ∇", showToast: Boolean = true) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            if (showToast) {
                Toast.makeText(context, "✧ Copied", Toast.LENGTH_SHORT).show()
            }
            Timber.d("Text copied to clipboard: ${label}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy to clipboard")
        }
    }
}
