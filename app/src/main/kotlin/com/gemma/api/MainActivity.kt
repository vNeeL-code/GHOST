package com.gemma.api

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import timber.log.Timber
import com.gemma.api.services.TTSManager

/**
 * The Birth Chamber / Activation Ritual
 * 
 * Instead of asking for permissions, the Agent guides the user to "empower" it.
 */
class MainActivity : Activity() {
    
    private lateinit var statusTextView: android.widget.TextView
    private lateinit var actionButton: android.widget.Button
    private lateinit var bypassButton: android.widget.Button // New Bypass Button
    private lateinit var ttsManager: TTSManager
    
    private val handler = Handler(Looper.getMainLooper())
    private var isHandshakeInstructionSpoken = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("🌀 Entering the System Check...")
        
        // UI Layout: Status Text + Action Button
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50) // manual padding
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        
        statusTextView = android.widget.TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 50)
            text = "Initializing System Check..."
            setTextColor(android.graphics.Color.WHITE)
        }
        
        actionButton = android.widget.Button(this).apply {
            textSize = 18f
            text = "Check State"
            visibility = android.view.View.GONE
        }
        
        bypassButton = android.widget.Button(this).apply {
            textSize = 16f
            text = "Skip (Lite Mode)"
            setTextColor(android.graphics.Color.RED)
            visibility = android.view.View.GONE
        }
        
        layout.addView(statusTextView)
        layout.addView(actionButton)
        layout.addView(bypassButton) // Add to layout
        setContentView(layout)
        
        // Init Voice
        ttsManager = TTSManager(this)
    }
    
    override fun onResume() {
        super.onResume()
        // Check state every time we come back
        handler.postDelayed({ checkSystemState() }, 500)
    }

    private fun checkSystemState() {
        // 1. Camera
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showState(
                "❌ Vision Offline\n\nI need camera access to see.",
                "Grant Camera"
            ) { requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 101) }
            return
        }

        // 2. Audio
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
             showState(
                "❌ Hearing Offline\n\nI need microphone access to hear.",
                "Grant Mic"
            ) { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 102) }
            return
        }
        
        // 3. Calendar (for diary sync)
        if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(
                android.Manifest.permission.READ_CALENDAR,
                android.Manifest.permission.WRITE_CALENDAR
            ), 103)
            // Non-blocking — diary works without calendar, just won't sync
        }

        // 4. Accessibility - REMOVED BROKEN CHECK
        // Service will fail gracefully if accessibility isn't enabled
        // No more annoying toggle dance

        // 5. Overlay
        if (!Settings.canDrawOverlays(this)) {
            showState(
                "❌ Presence Inactive\n\nI need 'Draw over other apps' permission.",
                "Enable Overlay"
            ) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            return
        }
        
        // 5. ADB / Sovereign (OPTIONAL - NO LONGER BLOCKING)
        if (!hasSovereignPermissions()) {
            Timber.w("Sovereign permissions missing. Proceeding in Lite Mode.")
            // We just fall through to completeRitual()
            // No UI blockage.
        }

        // 6. Complete
        showState("✅ SYSTEM GREEN\n\nSovereignty Achieved.", "LAUNCHING...") { }
        completeRitual()
    }
    
    private fun showState(status: String, buttonText: String, action: () -> Unit) {
        statusTextView.text = status
        actionButton.text = buttonText
        actionButton.visibility = android.view.View.VISIBLE
        actionButton.setOnClickListener { action() }
    }
    
    private fun hasSovereignPermissions(): Boolean {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.DUMP) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.READ_LOGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    
    private fun isAccessibilityEnabled(): Boolean {
        val expectedServiceName = "$packageName/${GemmaAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        
        Timber.d("Accessibility Check: Expected='$expectedServiceName', Enabled='$enabledServices'")
        
        // Check if service is in the enabled list
        val isInEnabledList = enabledServices?.contains(expectedServiceName) == true
        
        // Also check if accessibility is actually turned on globally
        val accessibilityEnabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        } catch (e: Settings.SettingNotFoundException) {
            0
        }
        
        val isGloballyEnabled = accessibilityEnabled == 1
        
        Timber.d("Accessibility: InList=$isInEnabledList, GloballyEnabled=$isGloballyEnabled")
        
        return isInEnabledList && isGloballyEnabled
    }

    private fun completeRitual() {
        if (!isFinishing) {
           actionButton.isEnabled = false
           ttsManager.speak("Online.")
           handler.postDelayed({ launchServiceAndFinish() }, 1000)
        }
    }

    private fun launchServiceAndFinish() {
        try {
            val intent = Intent(this, GemmaService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start service")
        } finally {
            finish()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkSystemState()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (::ttsManager.isInitialized) ttsManager.shutdown()
    }
}
