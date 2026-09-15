package com.ghost.api.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ghost.api.Constants
import com.ghost.api.GemmaAccessibilityService
import com.ghost.api.GemmaNotificationListener
import com.ghost.api.GemmaService
import com.ghost.api.hardware.HardwareToggleReceiver
import com.ghost.api.ui.EdgeLightsManager
import com.ghost.api.workers.DiaryWorker

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onShowTutorial: () -> Unit,
    onShowDiaryHistory: () -> Unit,
    gemmaService: GemmaService?
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE) }

    var edgeLightsEnabled by remember { mutableStateOf(EdgeLightsManager.isShowing) }
    var edgeLightsStyle by remember { mutableStateOf(prefs.getString(Constants.PREF_EDGE_LIGHT_STYLE, Constants.EDGE_STYLE_BARS) ?: Constants.EDGE_STYLE_BARS) }
    var passiveTtsEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_PASSIVE_TTS, true)) }
    var diaryActive by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, true)) }
    var diaryCadence by remember { mutableStateOf(prefs.getString(Constants.PREF_DIARY_CADENCE, "12") ?: "12") }
    var ttsEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_TTS_ENABLED, true)) }
    var backend by remember { mutableStateOf(prefs.getString(Constants.PREF_USER_BACKEND, "AUTO") ?: "AUTO") }
    var visualizerPreset by remember { mutableStateOf(prefs.getString(Constants.PREF_VISUALIZER_PRESET, "OPTION_A") ?: "OPTION_A") }

    val tokenManager = remember { com.ghost.api.logic.HFTokenManager(context) }
    val webSessionManager = remember { com.ghost.api.logic.WebSessionManager.getInstance(context) }
    var geminiKey by remember { mutableStateOf(tokenManager.getGeminiKey() ?: "") }
    var showGeminiKey by remember { mutableStateOf(false) }
    var geminiSearchGrounding by remember { mutableStateOf(webSessionManager.isGeminiSearchGroundingEnabled()) }
    var connectedPeers by remember { mutableStateOf(webSessionManager.getConnectedPeers()) }
    var selectedLoginContact by remember { mutableStateOf<com.ghost.api.logic.PeerContact?>(null) }

    val accentColor = Color(0xFF8BB4F6)
    val cardBg = Color(0xFF141418)
    val surfaceBg = Color(0xFF0E0E12)
    val dividerColor = Color(0x1AFFFFFF)
    val textDim = Color(0x99FFFFFF)

    val cn = remember { ComponentName(context, GemmaNotificationListener::class.java) }
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    val isNotifGranted = flat != null && flat.contains(cn.flattenToString())
    val isOverlayGranted = Settings.canDrawOverlays(context)
    val accessCn = remember { ComponentName(context, GemmaAccessibilityService::class.java) }
    val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    val isAccessibilityGranted = enabledServices != null && enabledServices.contains(accessCn.flattenToString())

    // In-App WebView Login Sheet for AI Phonebook Contacts ("Holding Cookie")
    selectedLoginContact?.let { contact ->
        PeerLoginSheet(
            contact = contact,
            onDismiss = { selectedLoginContact = null },
            onSessionSaved = { cookies ->
                webSessionManager.saveSession(contact.name, cookies)
                connectedPeers = webSessionManager.getConnectedPeers()
                selectedLoginContact = null
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .padding(horizontal = 16.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 500.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0x338BB4F6), RoundedCornerShape(24.dp)),
                color = surfaceBg,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Title Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✧ Settings",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            letterSpacing = 0.5.sp
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0x1AFFFFFF), CircleShape)
                        ) {
                            Text(text = "✕", color = Color.White, fontSize = 14.sp)
                        }
                    }

                    HorizontalDivider(color = dividerColor, thickness = 1.dp)

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        // 1. === System & Memory === (Category 1: Quick tools)
                        item {
                            SettingsSectionHeader(title = "System & Memory")
                        }
                        item {
                            SettingsActionCard(
                                title = "Trigger Diary Log Now",
                                subtitle = "Generate an episodic reflection immediately",
                                onClick = {
                                    GemmaService.instance?.startDiaryCycle()
                                    Toast.makeText(context, "Generating diary entry...", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        item {
                            SettingsActionCard(
                                title = "View Log History",
                                subtitle = "Inspect recent episodic memory logs",
                                onClick = onShowDiaryHistory
                            )
                        }
                        item {
                            SettingsActionCard(
                                title = "Tutorial Programme",
                                subtitle = "Review setup orientation and gesture controls",
                                onClick = onShowTutorial
                            )
                        }

                        // 2. === Live Wallpapers & Visualisers === (Category 2: 4 options + wallpaper actions + edge lights + pip)
                        item {
                            SettingsSectionHeader(title = "Live Wallpapers & Visualisers")
                        }
                        item {
                            Column {
                                Text(
                                    text = "Live Wallpapers",
                                    fontSize = 12.sp,
                                    color = textDim,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val rows = listOf(
                                        listOf(
                                            "OPTION_A" to "Radial",
                                            "OPTION_B" to "Hexagonal"
                                        ),
                                        listOf(
                                            "OPTION_C" to "Prismatic",
                                            "OPTION_D" to "Cuboid"
                                        )
                                    )
                                    for (presetRow in rows) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            for ((key, label) in presetRow) {
                                                val isSelected = visualizerPreset == key
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) accentColor else cardBg)
                                                        .clickable {
                                                            visualizerPreset = key
                                                            prefs.edit().putString(Constants.PREF_VISUALIZER_PRESET, key).apply()
                                                            Toast.makeText(context, "Visualizer geometry set to $label", Toast.LENGTH_SHORT).show()
                                                        }
                                                        .padding(vertical = 10.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = label,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isSelected) Color.Black else Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            SettingsActionCard(
                                title = "Set Live Wallpaper",
                                subtitle = "Apply GHOST avatar visualizer to home / lock screen",
                                onClick = {
                                    context.sendBroadcast(
                                        Intent(context, HardwareToggleReceiver::class.java).apply {
                                            action = "com.ghost.api.ACTION_SET_AVATAR_WALLPAPER"
                                        }
                                    )
                                }
                            )
                        }
                        item {
                            SettingsToggleRow(
                                title = "Edge Lights",
                                subtitle = "Ambient audio equaliser",
                                checked = edgeLightsEnabled,
                                onCheckedChange = { checked ->
                                    edgeLightsEnabled = checked
                                    if (checked) {
                                        EdgeLightsManager.show(context)
                                    } else {
                                        EdgeLightsManager.hide(context)
                                    }
                                    val status = if (EdgeLightsManager.isShowing) "ON" else "OFF"
                                    Toast.makeText(context, "Edge Lights $status", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        if (edgeLightsEnabled) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 6.dp)
                                        .background(cardBg, RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = "Edge Light Style",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    val styles = listOf(
                                        Constants.EDGE_STYLE_BARS to "I",
                                        Constants.EDGE_STYLE_BOOM to "II",
                                        Constants.EDGE_STYLE_HEX to "III",
                                        Constants.EDGE_STYLE_WIREFRAME to "IV"
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        for ((key, label) in styles) {
                                            val isSelected = edgeLightsStyle == key
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSelected) accentColor else Color(0x1AFFFFFF))
                                                    .clickable {
                                                        edgeLightsStyle = key
                                                        prefs.edit().putString(Constants.PREF_EDGE_LIGHT_STYLE, key).apply()
                                                        EdgeLightsManager.invalidate()
                                                    }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) Color.Black else Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 3. === Voice & Speech === (Category 3: TTS + Passive Notification TTS)
                        item {
                            SettingsSectionHeader(title = "Voice & Speech")
                        }
                        item {
                            SettingsToggleRow(
                                title = "Voice Output (TTS)",
                                subtitle = "Auditory response synthesis via speech engine",
                                checked = ttsEnabled,
                                onCheckedChange = { checked ->
                                    ttsEnabled = checked
                                    prefs.edit().putBoolean(Constants.PREF_TTS_ENABLED, checked).apply()
                                    val svc = gemmaService ?: GemmaService.instance
                                    svc?.ttsManager?.isTtsEnabled = checked
                                    if (!checked) svc?.ttsManager?.stop()
                                    Toast.makeText(context, if (checked) "Voice output enabled" else "Voice output muted", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        item {
                            SettingsToggleRow(
                                title = "Passive Notification TTS",
                                subtitle = "Read incoming notifications automatically",
                                checked = passiveTtsEnabled,
                                onCheckedChange = { checked ->
                                    passiveTtsEnabled = checked
                                    prefs.edit().putBoolean(Constants.PREF_PASSIVE_TTS, checked).apply()
                                    Toast.makeText(context, if (checked) "Passive TTS enabled" else "Passive TTS disabled", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        // 4. === Autonomous Diary === (Category 4: Second to last)
                        item {
                            SettingsSectionHeader(title = "Autonomous Diary")
                        }
                        item {
                            SettingsToggleRow(
                                title = "Autonomous Reflections",
                                subtitle = "Log episodic memory entries via background alarms",
                                checked = diaryActive,
                                onCheckedChange = { checked ->
                                    diaryActive = checked
                                    prefs.edit().putBoolean(Constants.PREF_AUTONOMOUS_DIARY, checked).apply()
                                    if (checked) {
                                        com.ghost.api.GemmaService.instance?.scheduleNextDiaryAlarm(forceReschedule = true)
                                        DiaryWorker.schedule(context)
                                        Toast.makeText(context, "Autonomous diary enabled", Toast.LENGTH_SHORT).show()
                                    } else {
                                        com.ghost.api.GemmaService.instance?.cancelDiaryAlarm()
                                        DiaryWorker.cancel(context)
                                        Toast.makeText(context, "Autonomous diary paused", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                        item {
                            AnimatedVisibility(visible = diaryActive) {
                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                    Text(
                                        text = "Reflection Cadence",
                                        fontSize = 12.sp,
                                        color = textDim,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val cadences = listOf("1H" to "1", "3H" to "3", "6H" to "6", "12H" to "12", "24H" to "24")
                                        for ((label, value) in cadences) {
                                            val isSelected = diaryCadence == value
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSelected) accentColor else cardBg)
                                                    .clickable {
                                                        diaryCadence = value
                                                        prefs.edit().putString(Constants.PREF_DIARY_CADENCE, value).apply()
                                                        if (diaryActive) {
                                                            com.ghost.api.GemmaService.instance?.scheduleNextDiaryAlarm(forceReschedule = true)
                                                            DiaryWorker.schedule(context)
                                                        }
                                                        Toast.makeText(context, "Cadence set to $label", Toast.LENGTH_SHORT).show()
                                                    }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) Color.Black else Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 5. === Extend Your Mind === (AI Phonebook)
                        item {
                            val activeCount = com.ghost.api.logic.AiPhonebook.CONTACTS.count { contact ->
                                if (contact.authType == com.ghost.api.logic.PeerAuthType.GEMINI_DIRECT) {
                                    geminiKey.isNotBlank()
                                } else {
                                    connectedPeers.contains(contact.name)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsSectionHeader(title = "Extend Your Mind (AI Phonebook)")
                                Text(
                                    text = "$activeCount / ${com.ghost.api.logic.AiPhonebook.CONTACTS.size} Active",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeCount > 0) Color(0xFF4CAF50) else textDim,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }

                        // 5A. Direct Pipe: ✦ Gemini ("Mum")
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                                    .background(cardBg, RoundedCornerShape(12.dp))
                                    .border(1.dp, if (geminiKey.isNotBlank()) Color(0x664CAF50) else Color(0x338BB4F6), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "✦ Gemini (Google)",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Cloud Reasoning & Synthesis • 1M Context",
                                            fontSize = 11.sp,
                                            color = textDim
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (geminiKey.isNotBlank()) Color(0x334CAF50) else Color(0x1AFFFFFF))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (geminiKey.isNotBlank()) "● Direct Pipe" else "○ Key Needed",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (geminiKey.isNotBlank()) Color(0xFF4CAF50) else Color(0x88FFFFFF)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = geminiKey,
                                    onValueChange = { geminiKey = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("Google AI Studio API Key", fontSize = 12.sp, color = Color(0x66FFFFFF)) },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
                                    visualTransformation = if (showGeminiKey) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    trailingIcon = {
                                        Text(
                                            text = if (showGeminiKey) "Hide" else "Show",
                                            fontSize = 11.sp,
                                            color = accentColor,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .clickable { showGeminiKey = !showGeminiKey }
                                                .padding(horizontal = 8.dp)
                                        )
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = accentColor,
                                        unfocusedBorderColor = Color(0x33FFFFFF),
                                        cursorColor = accentColor
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Get Free Key ↗",
                                        fontSize = 11.sp,
                                        color = accentColor,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clickable {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                            }
                                            .padding(vertical = 4.dp)
                                    )
                                    Button(
                                        onClick = {
                                            tokenManager.setGeminiKey(geminiKey)
                                            Toast.makeText(context, if (geminiKey.isNotBlank()) "Gemini API Key Saved!" else "Key Cleared", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) {
                                        Text("Save Key", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Google Search Grounding toggle
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0x1A8BB4F6))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Live Google Search Grounding",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Real-time Google search grounding for live web knowledge",
                                            fontSize = 10.sp,
                                            color = textDim
                                        )
                                    }
                                    Switch(
                                        checked = geminiSearchGrounding,
                                        onCheckedChange = { checked ->
                                            geminiSearchGrounding = checked
                                            webSessionManager.setGeminiSearchGroundingEnabled(checked)
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = accentColor
                                        )
                                    )
                                }
                            }
                        }

                        // 5B. Frontier Web Session Peers ("Holding Cookie")
                        com.ghost.api.logic.AiPhonebook.CONTACTS
                            .filter { it.authType == com.ghost.api.logic.PeerAuthType.WEB_COOKIE }
                            .forEach { contact ->
                                item {
                                    val isConnected = connectedPeers.contains(contact.name)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 4.dp)
                                            .background(cardBg, RoundedCornerShape(12.dp))
                                            .border(
                                                1.dp,
                                                if (isConnected) Color(0x664CAF50) else Color(0x1AFFFFFF),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .padding(12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = contact.callsign,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isConnected) Color(0x334CAF50) else Color(0x1AFFFFFF))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (isConnected) "● Connected" else "○ Not Logged In",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isConnected) Color(0xFF4CAF50) else Color(0x88FFFFFF)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isConnected) {
                                                OutlinedButton(
                                                    onClick = { selectedLoginContact = contact },
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                                ) {
                                                    Text("Re-login", color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Button(
                                                    onClick = {
                                                        webSessionManager.clearSession(contact.name)
                                                        connectedPeers = webSessionManager.getConnectedPeers()
                                                        Toast.makeText(context, "${contact.callsign} disconnected", Toast.LENGTH_SHORT).show()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF4444)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                                ) {
                                                    Text("Disconnect", color = Color(0xFFFF6666), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                }
                                            } else {
                                                Button(
                                                    onClick = { selectedLoginContact = contact },
                                                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)
                                                ) {
                                                    Text("Log In", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                        // 6. === Inference Engine === (Category 6: Last setting, fire and forget)
                        item {
                            SettingsSectionHeader(title = "Inference Engine")
                        }
                        item {
                            Column {
                                Text(
                                    text = "Active Hardware Acceleration",
                                    fontSize = 12.sp,
                                    color = textDim,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val backends = listOf("AUTO", "CPU", "GPU", "OFF")
                                    for (b in backends) {
                                        val isSelected = backend == b
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) accentColor else cardBg)
                                                .clickable {
                                                    backend = b
                                                    prefs.edit().putString(Constants.PREF_USER_BACKEND, b).apply()
                                                    val svc = gemmaService ?: GemmaService.instance
                                                    if (svc != null) {
                                                        svc.reloadWithBackend(b)
                                                    } else {
                                                        Toast.makeText(context, "Backend set to $b", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = b,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) Color.Black else Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // === Permissions & Access ===
                        if (!isNotifGranted || !isOverlayGranted || !isAccessibilityGranted) {
                            item {
                                SettingsSectionHeader(title = "Required System Access")
                            }
                        }
                        if (!isAccessibilityGranted) {
                            item {
                                SettingsActionCard(
                                    title = "Grant Accessibility Service",
                                    subtitle = "Required for active agent app awareness & dynamic avatar reactivity",
                                    isWarning = true,
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                            }
                        }
                        if (!isNotifGranted) {
                            item {
                                SettingsActionCard(
                                    title = "Grant Notification Listener Access",
                                    subtitle = "Required for reading notifications, auto-replies & context awareness",
                                    isWarning = true,
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                            }
                        }
                        if (!isOverlayGranted) {
                            item {
                                SettingsActionCard(
                                    title = "Grant System Overlay Access",
                                    subtitle = "Required for edge lights & ambient HUD",
                                    isWarning = true,
                                    onClick = {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF8BB4F6),
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141418))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Color(0x99FFFFFF),
                lineHeight = 15.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF8BB4F6),
                checkedTrackColor = Color(0xFF334B77),
                uncheckedThumbColor = Color(0xFF666666),
                uncheckedTrackColor = Color(0xFF222222)
            )
        )
    }
}

@Composable
private fun SettingsActionCard(
    title: String,
    subtitle: String,
    isWarning: Boolean = false,
    onClick: () -> Unit
) {
    val borderColor = if (isWarning) Color(0xFFF59E0B) else Color(0x1AFFFFFF)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141418))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isWarning) Color(0xFFF59E0B) else Color.White
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Color(0x99FFFFFF),
                lineHeight = 15.sp
            )
        }
        Text(
            text = "›",
            fontSize = 20.sp,
            color = if (isWarning) Color(0xFFF59E0B) else Color(0x66FFFFFF),
            fontWeight = FontWeight.Light
        )
    }
}
