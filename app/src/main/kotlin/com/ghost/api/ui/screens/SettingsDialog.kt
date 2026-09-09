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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ghost.api.Constants
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
    var passiveTtsEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_PASSIVE_TTS, true)) }
    var pipVisibilityEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_PIP_VISIBILITY, true)) }
    var diaryActive by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, true)) }
    var diaryCadence by remember { mutableStateOf(prefs.getString(Constants.PREF_DIARY_CADENCE, "12") ?: "12") }
    var ttsEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_TTS_ENABLED, true)) }
    var backend by remember { mutableStateOf(prefs.getString(Constants.PREF_USER_BACKEND, "AUTO") ?: "AUTO") }
    val rawPreset = prefs.getString(Constants.PREF_VISUALIZER_PRESET, "OPTION_A") ?: "OPTION_A"
    val initialPreset = when (rawPreset) {
        "GHOST" -> "OPTION_A"
        "SUDA" -> "OPTION_B"
        "AUDIOSURF" -> "OPTION_D"
        else -> rawPreset
    }
    var visualizerPreset by remember { mutableStateOf(initialPreset) }

    val accentColor = Color(0xFF8BB4F6)
    val cardBg = Color(0xFF141418)
    val surfaceBg = Color(0xFF0E0E12)
    val dividerColor = Color(0x1AFFFFFF)
    val textDim = Color(0x99FFFFFF)

    val cn = remember { ComponentName(context, GemmaNotificationListener::class.java) }
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    val isNotifGranted = flat != null && flat.contains(cn.flattenToString())
    val isOverlayGranted = Settings.canDrawOverlays(context)

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
                            text = "✧ GHOST Settings",
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
                        // === Perception & Signals ===
                        item {
                            SettingsSectionHeader(title = "Perception & Signals")
                        }
                        item {
                            SettingsToggleRow(
                                title = "Edge Lights",
                                subtitle = "Ambient neon pulses on voice & thinking",
                                checked = edgeLightsEnabled,
                                onCheckedChange = { checked ->
                                    edgeLightsEnabled = checked
                                    context.sendBroadcast(
                                        Intent(context, HardwareToggleReceiver::class.java).apply {
                                            action = "com.ghost.api.ACTION_TOGGLE_EDGE_LIGHTS"
                                        }
                                    )
                                }
                            )
                        }
                        item {
                            SettingsToggleRow(
                                title = "Passive Notification TTS",
                                subtitle = "Read incoming notifications hands-free",
                                checked = passiveTtsEnabled,
                                onCheckedChange = { checked ->
                                    passiveTtsEnabled = checked
                                    prefs.edit().putBoolean(Constants.PREF_PASSIVE_TTS, checked).apply()
                                    Toast.makeText(context, if (checked) "Passive TTS enabled" else "Passive TTS disabled", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        item {
                            SettingsToggleRow(
                                title = "PiP Tool Overlays",
                                subtitle = "Floating tool windows and live output preview",
                                checked = pipVisibilityEnabled,
                                onCheckedChange = { checked ->
                                    pipVisibilityEnabled = checked
                                    prefs.edit().putBoolean(Constants.PREF_PIP_VISIBILITY, checked).apply()
                                    Toast.makeText(context, if (checked) "PiP overlays on" else "PiP overlays off", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }

                        // === Autonomous Diary ===
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
                                        DiaryWorker.schedule(context)
                                        Toast.makeText(context, "Autonomous diary enabled", Toast.LENGTH_SHORT).show()
                                    } else {
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
                                                        if (diaryActive) DiaryWorker.schedule(context)
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

                        // === Voice Output ===
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

                        // === Inference Engine ===
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

                        // === Live Wallpapers ===
                        item {
                            SettingsSectionHeader(title = "Live Wallpapers & Avatar Geometry")
                        }
                        item {
                            Column {
                                Text(
                                    text = "Avatar Visualizer Geometry",
                                    fontSize = 12.sp,
                                    color = textDim,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val rows = listOf(
                                        listOf(
                                            "OPTION_A" to "Option A (Orbital)",
                                            "OPTION_B" to "Option B (Hexagons)"
                                        ),
                                        listOf(
                                            "OPTION_C" to "Option C (Prisms)",
                                            "OPTION_D" to "Option D (Highway)"
                                        )
                                    )
                                    for (presetRow in rows) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            for ((key, label) in presetRow) {
                                                val isSelected = visualizerPreset == key ||
                                                    (key == "OPTION_A" && visualizerPreset == "GHOST") ||
                                                    (key == "OPTION_B" && visualizerPreset == "SUDA") ||
                                                    (key == "OPTION_D" && visualizerPreset == "AUDIOSURF")
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
                                title = "Camera Wallpaper",
                                subtitle = "Set dynamic pass-through camera background",
                                onClick = {
                                    context.sendBroadcast(
                                        Intent(context, HardwareToggleReceiver::class.java).apply {
                                            action = "com.ghost.api.ACTION_SET_CAMERA_WALLPAPER"
                                        }
                                    )
                                }
                            )
                        }
                        item {
                            SettingsActionCard(
                                title = "Avatar Wallpaper",
                                subtitle = "Set interactive GHOST avatar wallpaper",
                                onClick = {
                                    context.sendBroadcast(
                                        Intent(context, HardwareToggleReceiver::class.java).apply {
                                            action = "com.ghost.api.ACTION_SET_AVATAR_WALLPAPER"
                                        }
                                    )
                                }
                            )
                        }

                        // === System & Memory ===
                        item {
                            SettingsSectionHeader(title = "System & Memory")
                        }
                        item {
                            SettingsActionCard(
                                title = "Compress Session / Flush Memory",
                                subtitle = "Force long-term semantic memory synthesis",
                                onClick = {
                                    val svc = gemmaService ?: GemmaService.instance
                                    svc?.flushSessionMemory()
                                    Toast.makeText(context, "Flushing memory and compressing session...", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        item {
                            SettingsActionCard(
                                title = "Clear Safe Mode",
                                subtitle = "Restore full GPU inference after crash recovery",
                                onClick = {
                                    GemmaService.instance?.resetRecoveryState()
                                    Toast.makeText(context, "Safe mode cleared — GPU restored", Toast.LENGTH_SHORT).show()
                                }
                            )
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
                                title = "View Diary History",
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

                        // === Permissions & Access ===
                        if (!isNotifGranted || !isOverlayGranted) {
                            item {
                                SettingsSectionHeader(title = "Required System Access")
                            }
                        }
                        if (!isNotifGranted) {
                            item {
                                SettingsActionCard(
                                    title = "Grant Notification Listener Access",
                                    subtitle = "Required for context awareness & passive TTS",
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
                                    subtitle = "Required for floating PiP tools & edge lights",
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
