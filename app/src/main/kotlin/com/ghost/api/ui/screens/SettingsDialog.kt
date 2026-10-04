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
import com.ghost.api.BuildConfig
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
    var diarySyncCalendar by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_DIARY_SYNC_CALENDAR, false)) }
    var isOperatorTier by remember { mutableStateOf(Constants.isOperatorTier(prefs)) }
    var ttsEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_TTS_ENABLED, true)) }
    var backend by remember { mutableStateOf(prefs.getString(Constants.PREF_USER_BACKEND, "AUTO") ?: "AUTO") }
    val isEngineActive = backend != "OFF"
    val hardwareTier = remember { Constants.resolveHardwareModelTier(context) }
    val is8GbDevice = remember { hardwareTier == "E2B" }
    var selectedModel by remember { mutableStateOf(prefs.getString(Constants.PREF_SELECTED_MODEL, hardwareTier) ?: hardwareTier) }
    var visualizerPreset by remember { mutableStateOf(prefs.getString(Constants.PREF_VISUALIZER_PRESET, "OPTION_A") ?: "OPTION_A") }
    var summonMethod by remember { mutableStateOf(prefs.getString(Constants.PREF_SUMMON_METHOD, Constants.SUMMON_METHOD_BOTH) ?: Constants.SUMMON_METHOD_BOTH) }

    val tokenManager = remember { com.ghost.api.logic.HFTokenManager(context) }
    val webSessionManager = remember { com.ghost.api.logic.WebSessionManager.getInstance(context) }
    var geminiKey by remember { mutableStateOf(tokenManager.getGeminiKey() ?: "") }
    var showGeminiKey by remember { mutableStateOf(false) }
    var geminiSearchGrounding by remember { mutableStateOf(webSessionManager.isGeminiSearchGroundingEnabled()) }

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

    var showOperatorUnlockDialog by remember { mutableStateOf(false) }

    if (showOperatorUnlockDialog && BuildConfig.SHOW_OPERATOR_PASS_PAYWALL) {
        AlertDialog(
            onDismissRequest = { showOperatorUnlockDialog = false },
            title = {
                Text(
                    text = "Unlock Operator Pass (£3.50)",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8BB4F6)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Support GHOST development! (£3.50 one-time purchase)",
                        fontSize = 13.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Perks unlocked:\n• Δ 👾 ∇ elite Turing header glyph (replaces 🦕💭💸)\n• Custom operator avatars & custom emoji input\n• Hexagonal, Prismatic & Cuboid visualizer geometries\n• Reactive edge light styles II, III, IV\n\nFree tier remains 100% uncrippled with full offline local AI & privacy.",
                        fontSize = 12.sp,
                        color = Color(0xCCFFFFFF),
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isOperatorTier = true
                        prefs.edit().putBoolean(Constants.PREF_IS_OPERATOR_TIER, true).apply()
                        showOperatorUnlockDialog = false
                        Toast.makeText(context, "Operator Pass Activated! Δ 👾 ∇ unlocked", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))
                ) {
                    Text("Activate (£3.50)", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOperatorUnlockDialog = false }) {
                    Text("Cancel", color = Color(0x99FFFFFF))
                }
            },
            containerColor = Color(0xFF141418),
            shape = RoundedCornerShape(16.dp)
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
                                subtitle = if (isEngineActive) "Generate an episodic reflection immediately" else "Generate an episodic reflection immediately (Engine is currently OFF)",
                                enabled = isEngineActive,
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
                                    val rows = if (BuildConfig.FEATURE_LOCKED_SKINS) {
                                        listOf(
                                            listOf(
                                                "OPTION_A" to "Radial",
                                                "OPTION_B" to "Hexagonal"
                                            ),
                                            listOf(
                                                "OPTION_C" to "Prismatic",
                                                "OPTION_D" to "Cuboid"
                                            )
                                        )
                                    } else {
                                        listOf(
                                            listOf(
                                                "OPTION_A" to "Radial (Default)"
                                            )
                                        )
                                    }
                                    for (presetRow in rows) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            for ((key, label) in presetRow) {
                                                val isSelected = visualizerPreset == key
                                                val isLocked = key != "OPTION_A" && !isOperatorTier
                                                val displayLabel = if (isLocked) "$label 🔒" else label
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(
                                                            when {
                                                                isSelected -> accentColor
                                                                isLocked -> cardBg.copy(alpha = 0.5f)
                                                                else -> cardBg
                                                            }
                                                        )
                                                        .border(
                                                            width = 1.dp,
                                                            color = if (isLocked) Color(0x15FFFFFF) else Color(0x33FFFFFF),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            if (isLocked) {
                                                                Toast.makeText(context, "Operator Pass (£3.50 🦕💭💸) required to unlock $label geometry", Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                visualizerPreset = key
                                                                prefs.edit().putString(Constants.PREF_VISUALIZER_PRESET, key).apply()
                                                                Toast.makeText(context, "Visualizer geometry set to $label", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                        .padding(vertical = 10.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = displayLabel,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        color = when {
                                                            isSelected -> Color.Black
                                                            isLocked -> textDim.copy(alpha = 0.6f)
                                                            else -> Color.White
                                                        }
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
                                    val styles = if (BuildConfig.FEATURE_LOCKED_SKINS) {
                                        listOf(
                                            Constants.EDGE_STYLE_BARS to "I",
                                            Constants.EDGE_STYLE_BOOM to "II",
                                            Constants.EDGE_STYLE_HEX to "III",
                                            Constants.EDGE_STYLE_WIREFRAME to "IV"
                                        )
                                    } else {
                                        listOf(
                                            Constants.EDGE_STYLE_BARS to "Style I (Default)"
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        for ((key, label) in styles) {
                                            val isSelected = edgeLightsStyle == key
                                            val isLocked = key != Constants.EDGE_STYLE_BARS && !isOperatorTier
                                            val displayLabel = if (isLocked) "$label 🔒" else label
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        when {
                                                            isSelected -> accentColor
                                                            isLocked -> Color(0x0DFFFFFF)
                                                            else -> Color(0x1AFFFFFF)
                                                        }
                                                    )
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (isLocked) Color(0x11FFFFFF) else Color(0x22FFFFFF),
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .clickable {
                                                        if (isLocked) {
                                                            Toast.makeText(context, "Operator Pass (£3.50 🦕💭💸) required for Edge Style $label", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            edgeLightsStyle = key
                                                            prefs.edit().putString(Constants.PREF_EDGE_LIGHT_STYLE, key).apply()
                                                            EdgeLightsManager.invalidate()
                                                        }
                                                    }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = displayLabel,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = when {
                                                        isSelected -> Color.Black
                                                        isLocked -> textDim.copy(alpha = 0.6f)
                                                        else -> Color.White
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // === Summon & Gesture Controls ===
                        item {
                            SettingsSectionHeader(title = "Summon & Gesture Controls")
                        }
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                                    .background(cardBg, RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = "Summon Trigger",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Choose how to invoke GHOST overlay from anywhere",
                                    fontSize = 11.sp,
                                    color = textDim,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                val summonOptions = listOf(
                                    Constants.SUMMON_METHOD_SHAKE to "Shake",
                                    Constants.SUMMON_METHOD_EDGE_NUB to "Edge",
                                    Constants.SUMMON_METHOD_BOTH to "Both",
                                    Constants.SUMMON_METHOD_OFF to "Off"
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for ((key, label) in summonOptions) {
                                        val isSelected = summonMethod == key
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) accentColor else Color(0x1AFFFFFF))
                                                .clickable {
                                                    summonMethod = key
                                                    prefs.edit().putString(Constants.PREF_SUMMON_METHOD, key).apply()
                                                    val svc = gemmaService ?: GemmaService.instance
                                                    svc?.updateSummonControls()
                                                    val desc = when (key) {
                                                        Constants.SUMMON_METHOD_SHAKE -> "Phone shake active 📳"
                                                        Constants.SUMMON_METHOD_EDGE_NUB -> "Right bezel handle active 🎚️"
                                                        Constants.SUMMON_METHOD_BOTH -> "Shake & Edge handle active ✨"
                                                        else -> "Overlay summoning disabled 🚫"
                                                    }
                                                    Toast.makeText(context, desc, Toast.LENGTH_SHORT).show()
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
                                subtitle = if (isEngineActive) {
                                    "Log episodic memory entries via background alarms"
                                } else {
                                    "Log episodic memory entries via background alarms (Engine is currently OFF)"
                                },
                                checked = diaryActive,
                                enabled = isEngineActive,
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
                            SettingsToggleRow(
                                title = "Device Calendar Integration",
                                subtitle = if (!isEngineActive) {
                                    "Calendar sync paused (Engine is currently OFF)"
                                } else if (diarySyncCalendar) {
                                    "Calendar sync ACTIVE: Reflections mirror as Δ 👾 ∇ calendar blocks."
                                } else {
                                    "In-app PRIVATE: Reflections stay strictly in local database with FIFO eviction (last 25 entries)."
                                },
                                checked = diarySyncCalendar,
                                enabled = isEngineActive,
                                onCheckedChange = { checked ->
                                    diarySyncCalendar = checked
                                    prefs.edit().putBoolean(Constants.PREF_DIARY_SYNC_CALENDAR, checked).apply()
                                    val msg = if (checked) "Calendar sync enabled (Δ 👾 ∇)" else "In-app private mode (FIFO 25)"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        item {
                            AnimatedVisibility(visible = diaryActive && isEngineActive) {
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

                        // 5. === Cloud Reasoning & Search Grounding ===
                        item {
                            SettingsSectionHeader(title = "Cloud Reasoning & Search Grounding")
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

                        // 6. === Inference Engine === (Category 6: Last setting, fire and forget)
                        item {
                            SettingsSectionHeader(title = "Inference Engine")
                        }
                        item {
                            Column(modifier = Modifier.padding(bottom = 14.dp)) {
                                val ramGb = Constants.getDeviceRamGb(context)
                                val coreTitle = when {
                                    is8GbDevice -> "Gemma 4 E2B • 8GB Compact"
                                    ramGb >= 20.0 -> "Gemma 4 E4B • 24GB Extreme"
                                    ramGb >= 14.5 -> "Gemma 4 E4B • 16GB Ultra"
                                    else -> "Gemma 4 E4B • 12GB Frontier"
                                }
                                val coreSubtitle = when {
                                    is8GbDevice -> "5,120 token dialogue runway (Hardware Locked)"
                                    ramGb >= 20.0 -> "10,240 token massive runway (MTP Speculative Decoding)"
                                    ramGb >= 14.5 -> "8,192 token extended runway (MTP Speculative Decoding)"
                                    else -> "6,144 token dialogue runway (MTP Speculative Decoding)"
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(cardBg)
                                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = if (is8GbDevice) "⚡" else "🧠",
                                            fontSize = 22.sp
                                        )
                                        Column {
                                            Text(
                                                text = coreTitle,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = coreSubtitle,
                                                fontSize = 11.sp,
                                                color = textDim
                                            )
                                        }
                                    }
                                }

                                val isNubiaDevice = remember {
                                    android.os.Build.MANUFACTURER.contains("Nubia", ignoreCase = true) ||
                                    android.os.Build.BRAND.contains("Nubia", ignoreCase = true) ||
                                    android.os.Build.MANUFACTURER.contains("ZTE", ignoreCase = true)
                                }
                                if (isNubiaDevice && !is8GbDevice) {
                                    Text(
                                        text = "🎮 REDMAGIC Tip: Add GHOST to Game Space for high-priority memory & 24/7 background immunity.",
                                        fontSize = 10.sp,
                                        color = Color(0xFFFFB74D),
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                            }
                        }
                        item {
                            SettingsSectionHeader(title = "Local Inference Engine")
                        }
                        item {
                            Column {
                                Text(
                                    text = "Model Architecture & Weights",
                                    fontSize = 12.sp,
                                    color = textDim,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val models = listOf(
                                        "E4B" to "E4B Frontier (3.4GB)",
                                        "E2B" to "E2B Compact (1.7GB)"
                                    )
                                    for ((mCore, label) in models) {
                                        val isSelected = selectedModel.equals(mCore, ignoreCase = true)
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) accentColor else cardBg)
                                                .clickable {
                                                    if (mCore == "E4B" && is8GbDevice) {
                                                        Toast.makeText(context, "E4B requires >=12GB RAM (Hardware locked to E2B)", Toast.LENGTH_SHORT).show()
                                                        return@clickable
                                                    }
                                                    selectedModel = mCore
                                                    prefs.edit().putString(Constants.PREF_SELECTED_MODEL, mCore).apply()
                                                    val svc = gemmaService ?: GemmaService.instance
                                                    if (svc != null) {
                                                        svc.reloadWithModel(mCore)
                                                    } else {
                                                        Toast.makeText(context, "Active model core: $mCore", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) Color.Black else Color.White,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
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

                        // === Flavor-specific Bottom Card ===
                        if (BuildConfig.SHOW_OPERATOR_PASS_PAYWALL) {
                            // Play Store Flavor: Operator Pass (Tree Fiddy 🦕💭💸 vs Δ 👾 ∇)
                            item {
                                SettingsSectionHeader(title = "Operator Pass")
                            }
                            item {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            width = 1.dp,
                                            color = if (isOperatorTier) Color(0xFF22C55E) else Color(0xFF3B82F6),
                                            shape = RoundedCornerShape(12.dp)
                                        ),
                                    color = cardBg
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (isOperatorTier) "GUARDIAN" else "MINION",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isOperatorTier) Color(0xFF22C55E) else Color(0xFF8BB4F6)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        if (isOperatorTier) Color(0x3322C55E) else Color(0x338BB4F6),
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (isOperatorTier) "Δ 👾 ∇" else "🦕💭💸",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isOperatorTier) Color(0xFF22C55E) else Color(0xFF8BB4F6)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = if (isOperatorTier) {
                                                "Elite status unlocked. Δ 👾 ∇ Turing glyph, reactive edge lights II-IV, hexagonal/prismatic/cuboid visualizers, and custom operator avatars active."
                                            } else {
                                                "100% uncrippled local offline AI & privacy. Unlock Guardian status for £3.50 (Tree Fiddy 🦕💭💸) to get the elite Δ 👾 ∇ glyph, custom avatars, visualizer geometries, and edge light styles."
                                            },
                                            fontSize = 11.sp,
                                            color = textDim,
                                            lineHeight = 16.sp
                                        )

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Tier selector buttons
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            // Free Tier Button: 🦕💭💸 [Minion]
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (!isOperatorTier) Color(0x333B82F6) else Color(0x14FFFFFF))
                                                    .border(
                                                        1.dp,
                                                        if (!isOperatorTier) Color(0xFF3B82F6) else Color(0x22FFFFFF),
                                                        RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable {
                                                        if (isOperatorTier) {
                                                            isOperatorTier = false
                                                            prefs.edit().putBoolean(Constants.PREF_IS_OPERATOR_TIER, false).apply()
                                                            if (visualizerPreset != "OPTION_A") {
                                                                visualizerPreset = "OPTION_A"
                                                                prefs.edit().putString(Constants.PREF_VISUALIZER_PRESET, "OPTION_A").apply()
                                                            }
                                                            if (edgeLightsStyle != Constants.EDGE_STYLE_BARS) {
                                                                edgeLightsStyle = Constants.EDGE_STYLE_BARS
                                                                prefs.edit().putString(Constants.PREF_EDGE_LIGHT_STYLE, Constants.EDGE_STYLE_BARS).apply()
                                                                EdgeLightsManager.invalidate()
                                                            }
                                                            Toast.makeText(context, "Minion Tier active 🦕💭💸", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                    .padding(vertical = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(
                                                        text = "🦕💭💸",
                                                        fontSize = 16.sp
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = "Minion",
                                                        fontSize = 11.sp,
                                                        color = if (!isOperatorTier) Color(0xFF8BB4F6) else textDim
                                                    )
                                                }
                                            }

                                            // Operator Tier Button: Δ 👾 ∇ [Guardian]
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isOperatorTier) Color(0x3322C55E) else Color(0x14FFFFFF))
                                                    .border(
                                                        1.dp,
                                                        if (isOperatorTier) Color(0xFF22C55E) else Color(0x22FFFFFF),
                                                        RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable {
                                                        if (!isOperatorTier) {
                                                            showOperatorUnlockDialog = true
                                                        } else {
                                                            Toast.makeText(context, "Guardian Pass active Δ 👾 ∇", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                    .padding(vertical = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text(
                                                        text = "Δ 👾 ∇",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isOperatorTier) Color(0xFF22C55E) else Color.White
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = "Guardian",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (isOperatorTier) Color(0xFF22C55E) else textDim
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (BuildConfig.DISTRIBUTION_FLAVOR == "fdroid") {
                            // F-Droid Flavor: FOSS Operator (zero upsells, zero locks)
                            item {
                                SettingsSectionHeader(title = "FOSS Community")
                            }
                            item {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            width = 1.dp,
                                            color = Color(0x338BB4F6),
                                            shape = RoundedCornerShape(12.dp)
                                        ),
                                    color = cardBg
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "OPERATOR",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF8BB4F6)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0x338BB4F6), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = "Δ 🐙 ∇",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF8BB4F6)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = "100% libre and free software. Zero network tracking, fully private on-device edge intelligence. Built with love for the open-source community.",
                                            fontSize = 11.sp,
                                            color = textDim,
                                            lineHeight = 16.sp
                                        )

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0x1F8BB4F6))
                                                .border(1.dp, Color(0x338BB4F6), RoundedCornerShape(10.dp))
                                                .clickable {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/vNeeL-code/GHOST")).apply {
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    context.startActivity(intent)
                                                }
                                                .padding(vertical = 11.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "Δ 🐙 ∇",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF8BB4F6)
                                                )
                                                Text(
                                                    text = "[foss / Operator]",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF8BB4F6)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Patreon / Supporter Flavor: Fully Unlocked Guardian
                            item {
                                SettingsSectionHeader(title = "Guardian Supporter")
                            }
                            item {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            width = 1.dp,
                                            color = Color(0xFF22C55E),
                                            shape = RoundedCornerShape(12.dp)
                                        ),
                                    color = cardBg
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "GUARDIAN",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF22C55E)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0x3322C55E), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = "Δ 👾 ∇",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF22C55E)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = "Elite status permanently active. All visualizer geometries, 4 reactive edge light styles, and custom avatars are fully unlocked. Thank you for supporting independent on-device AI development!",
                                            fontSize = 11.sp,
                                            color = textDim,
                                            lineHeight = 16.sp
                                        )

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0x1F22C55E))
                                                .border(1.dp, Color(0x3322C55E), RoundedCornerShape(10.dp))
                                                .clickable {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/vNeeL-code/GHOST")).apply {
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    context.startActivity(intent)
                                                }
                                                .padding(vertical = 11.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "Δ 👾 ∇",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF22C55E)
                                                )
                                                Text(
                                                    text = "[premium / Guardian]",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF22C55E)
                                                )
                                            }
                                        }
                                    }
                                }
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
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141418))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = alpha)
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Color(0x99FFFFFF).copy(alpha = alpha),
                lineHeight = 15.sp
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF8BB4F6),
                checkedTrackColor = Color(0xFF334B77),
                uncheckedThumbColor = Color(0xFF666666),
                uncheckedTrackColor = Color(0xFF222222),
                disabledCheckedThumbColor = Color(0xFF8BB4F6).copy(alpha = 0.4f),
                disabledCheckedTrackColor = Color(0xFF334B77).copy(alpha = 0.4f),
                disabledUncheckedThumbColor = Color(0xFF666666).copy(alpha = 0.4f),
                disabledUncheckedTrackColor = Color(0xFF222222).copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
private fun SettingsActionCard(
    title: String,
    subtitle: String,
    isWarning: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    val borderColor = if (isWarning) Color(0xFFF59E0B) else Color(0x1AFFFFFF)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141418))
            .border(1.dp, borderColor.copy(alpha = alpha), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = (if (isWarning) Color(0xFFF59E0B) else Color.White).copy(alpha = alpha)
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = Color(0x99FFFFFF).copy(alpha = alpha),
                lineHeight = 15.sp
            )
        }
        Text(
            text = "›",
            fontSize = 20.sp,
            color = (if (isWarning) Color(0xFFF59E0B) else Color(0x66FFFFFF)).copy(alpha = alpha),
            fontWeight = FontWeight.Light
        )
    }
}
