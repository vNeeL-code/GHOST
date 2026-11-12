package com.ghost.api.ui.screens

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.ghost.api.billing.BillingManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ghost.api.BuildConfig
import com.ghost.api.Constants
import com.ghost.api.GemmaAccessibilityService
import com.ghost.api.GemmaNotificationListener
import com.ghost.api.GemmaService
import com.ghost.api.hardware.HardwareToggleReceiver
import com.ghost.api.ui.EdgeLightsManager
import com.ghost.api.ui.OverlayActionRegistry

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    onShowDiaryHistory: () -> Unit = {},
    gemmaService: GemmaService?
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE) }

    var edgeLightsEnabled by remember { mutableStateOf(EdgeLightsManager.isShowing) }
    var edgeLightsStyle by remember { mutableStateOf(prefs.getString(Constants.PREF_EDGE_LIGHT_STYLE, Constants.EDGE_STYLE_BARS) ?: Constants.EDGE_STYLE_BARS) }

    var passiveTtsEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_PASSIVE_TTS, true)) }
    var barkBattery by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_BARK_BATTERY, true)) }
    var barkPower by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_BARK_POWER, true)) }
    var barkMotion by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_BARK_MOTION, true)) }
    var barkPresence by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_BARK_PRESENCE, true)) }
    var barkPeripherals by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_BARK_PERIPHERALS, true)) }
    var barkThermal by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_BARK_THERMAL, true)) }
    var barkNotifs by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_BARK_NOTIFS, true)) }
    var showPassiveCuesSubmenu by remember { mutableStateOf(false) }
    var showPuckConfigDialog by remember { mutableStateOf(false) }
    var showDiaryHistoryDialog by remember { mutableStateOf(false) }

    var hyperPucksEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_HYPER_PUCKS_ENABLED, true)) }
    var diaryActive by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_AUTONOMOUS_DIARY, false)) }
    var diarySyncCalendar by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_DIARY_SYNC_CALENDAR, false)) }
    var diaryCadence by remember { mutableStateOf(prefs.getString(Constants.PREF_DIARY_CADENCE, "3") ?: "3") }

    var puckSpreadScale by remember { mutableStateOf(prefs.getFloat(Constants.PREF_PUCK_SPREAD_SCALE, 1.0f)) }
    var puckVerticalOffsetDp by remember { mutableStateOf(prefs.getInt(Constants.PREF_PUCK_VERTICAL_OFFSET_DP, 0)) }
    var selectedConfigPuck by remember { mutableStateOf(OverlayActionRegistry.PuckId.CENTER) }
    var activePickerSlot by remember { mutableStateOf<Pair<String, String>?>(null) } // prefKey to title
    var bindingRefreshTick by remember { mutableStateOf(0) }

    var fastPathEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_FAST_PATH_ENABLED, true)) }
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
    var geminiKey by remember { mutableStateOf(tokenManager.getGeminiKey() ?: "") }
    var showGeminiKey by remember { mutableStateOf(false) }
    var geminiSearchGrounding by remember { mutableStateOf(prefs.getBoolean("gemini_search_grounding", true)) }
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }
    fun isSectionExpanded(title: String): Boolean = expandedSections[title] == true
    fun toggleSection(title: String) {
        expandedSections[title] = !isSectionExpanded(title)
    }

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
    var showAccessibilityDisclosureDialog by remember { mutableStateOf(false) }

    val billingManager = remember { BillingManager.create(context) }
    val billingPrice by billingManager.formattedPrice.collectAsState()
    val billingPurchased by billingManager.isOperatorPurchased.collectAsState()

    DisposableEffect(billingManager) {
        billingManager.startConnection()
        onDispose {
            billingManager.destroy()
        }
    }

    LaunchedEffect(billingPurchased) {
        if (billingPurchased) {
            isOperatorTier = true
        }
    }

    if (showOperatorUnlockDialog && BuildConfig.SHOW_OPERATOR_PASS_PAYWALL) {
        AlertDialog(
            onDismissRequest = { showOperatorUnlockDialog = false },
            title = {
                Text(
                    text = "Unlock Operator Pass ($billingPrice)",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8BB4F6)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Support GHOST development! ($billingPrice one-time purchase)",
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
                        val activity = context as? Activity
                        if (activity != null) {
                            showOperatorUnlockDialog = false
                            billingManager.launchPurchaseFlow(activity)
                        } else {
                            Toast.makeText(context, "Activity unavailable for store checkout", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))
                ) {
                    Text("Purchase ($billingPrice)", color = Color.Black, fontWeight = FontWeight.Bold)
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

    if (showAccessibilityDisclosureDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosureDialog = false },
            title = {
                Text(
                    text = "Accessibility Service Disclosure",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "GHOST requires Accessibility Service access to provide contextual assistance and UI automation.",
                        fontSize = 13.sp,
                        color = Color.White
                    )
                    Text(
                        text = "• How it is used: Inspecting active screen elements and performing clicks, scrolls, or text entry when you explicitly ask the agent to interact with your device.\n• Privacy & Security: All processing occurs entirely on-device with your local AI model. No screen content or personal data is ever collected, stored remotely, or transmitted to any external servers.",
                        fontSize = 12.sp,
                        color = Color(0xCCFFFFFF),
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityDisclosureDialog = false
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8BB4F6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Continue to Settings", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosureDialog = false }) {
                    Text("Cancel", color = Color(0x99FFFFFF))
                }
            },
            containerColor = Color(0xFF141418),
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showPassiveCuesSubmenu) {
        AlertDialog(
            onDismissRequest = { showPassiveCuesSubmenu = false },
            title = {
                Text(
                    text = "✧ Deterministic Barks & Cues",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8BB4F6),
                    fontSize = 17.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Customize spoken personality lines for hardware events and ambient states (GHOST companion reflexes):",
                        fontSize = 12.sp,
                        color = Color(0xCCFFFFFF),
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    SettingsSubmenuToggle(
                        icon = "⚡",
                        title = "Power & Charging",
                        subtitle = "Charger coupled / decoupled & external feed announcements",
                        checked = barkPower,
                        onCheckedChange = { checked ->
                            barkPower = checked
                            prefs.edit().putBoolean(Constants.PREF_BARK_POWER, checked).apply()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsSubmenuToggle(
                        icon = "🔋",
                        title = "Battery Telemetry",
                        subtitle = "Alerts at 15%, 10%, 5% critical ('energy low.. consciousness fading... need volts'), and 100% full charge",
                        checked = barkBattery,
                        onCheckedChange = { checked ->
                            barkBattery = checked
                            prefs.edit().putBoolean(Constants.PREF_BARK_BATTERY, checked).apply()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsSubmenuToggle(
                        icon = "🌀",
                        title = "Physical Reflexes",
                        subtitle = "Deliberate shake vertigo ('dizzy gyros'), free-fall drop landings, and collision shocks",
                        checked = barkMotion,
                        onCheckedChange = { checked ->
                            barkMotion = checked
                            prefs.edit().putBoolean(Constants.PREF_BARK_MOTION, checked).apply()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsSubmenuToggle(
                        icon = "👁️",
                        title = "Standby & Presence",
                        subtitle = "Greetings on unlock ('Eyes up', 'I see you') and standby nap cues on screen lock",
                        checked = barkPresence,
                        onCheckedChange = { checked ->
                            barkPresence = checked
                            prefs.edit().putBoolean(Constants.PREF_BARK_PRESENCE, checked).apply()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsSubmenuToggle(
                        icon = "🎧",
                        title = "Peripherals & Connectivity",
                        subtitle = "Bluetooth devices coupling/decoupling and Wi-Fi uplink changes",
                        checked = barkPeripherals,
                        onCheckedChange = { checked ->
                            barkPeripherals = checked
                            prefs.edit().putBoolean(Constants.PREF_BARK_PERIPHERALS, checked).apply()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsSubmenuToggle(
                        icon = "🌡️",
                        title = "Hardware Health",
                        subtitle = "Thermal throttling warnings and low flash storage capacity alerts",
                        checked = barkThermal,
                        onCheckedChange = { checked ->
                            barkThermal = checked
                            prefs.edit().putBoolean(Constants.PREF_BARK_THERMAL, checked).apply()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    SettingsSubmenuToggle(
                        icon = "💬",
                        title = "Spoken Notifications",
                        subtitle = "Snappy announcements for incoming messaging app dispatches",
                        checked = barkNotifs,
                        onCheckedChange = { checked ->
                            barkNotifs = checked
                            prefs.edit().putBoolean(Constants.PREF_BARK_NOTIFS, checked).apply()
                        }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPassiveCuesSubmenu = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8BB4F6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF141418),
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showDiaryHistoryDialog) {
        val memoryManager = remember {
            gemmaService?.memoryManager ?: com.ghost.api.database.MemoryManager(context.applicationContext)
        }
        var diaryEntries by remember { mutableStateOf<List<com.ghost.api.database.DiaryEntry>?>(null) }

        LaunchedEffect(Unit) {
            diaryEntries = withContext(Dispatchers.IO) {
                memoryManager.getRecentDiaryEntries(50)
            }
        }

        AlertDialog(
            onDismissRequest = { showDiaryHistoryDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "✧ Episodic Diary Reflections",
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Room DB (FIFO 25) • Documents/GHOST/diary_history.jsonl",
                            fontSize = 10.sp,
                            color = textDim
                        )
                    }
                }
            },
            text = {
                val entries = diaryEntries
                if (entries == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = accentColor)
                    }
                } else if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No episodic reflections recorded yet.\n\nTap 'Trigger Diary Log Now' to generate one immediately, or allow background cadence.",
                            fontSize = 13.sp,
                            color = Color(0x99FFFFFF),
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                } else {
                    val sdf = remember { java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm", java.util.Locale.US) }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(entries.size) { index ->
                            val entry = entries[index]
                            val dateStr = sdf.format(java.util.Date(entry.timestamp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B22)),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0x228BB4F6))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$dateStr • ${entry.eventType}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = accentColor
                                        )
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                val clip = android.content.ClipData.newPlainText("Diary Entry", entry.observation)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "Copied reflection to clipboard", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Text("📋", fontSize = 12.sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = entry.observation,
                                        fontSize = 12.sp,
                                        color = Color(0xFFE5E7EB),
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showDiaryHistoryDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Close", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                val entries = diaryEntries
                if (!entries.isNullOrEmpty()) {
                    TextButton(
                        onClick = {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            val allText = entries.joinToString("\n\n" + "=".repeat(30) + "\n\n") { e ->
                                "[${sdf.format(java.util.Date(e.timestamp))} • ${e.eventType} • ${e.contextData}]\n${e.observation}"
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("All Diary Entries", allText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied all reflections to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Copy All", color = accentColor, fontSize = 12.sp)
                    }
                }
            },
            containerColor = Color(0xFF141418),
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showPuckConfigDialog) {
        val _tick = bindingRefreshTick
        AlertDialog(
            onDismissRequest = { showPuckConfigDialog = false },
            title = {
                Text(
                    text = "✧ Pucks & Gestures",
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 580.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Tap a puck to configure swipe directions",
                        fontSize = 11.sp,
                        color = textDim
                    )

                    // 1. Row of Colored Puck Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val configPucks = listOf(
                            OverlayActionRegistry.PuckId.TL,
                            OverlayActionRegistry.PuckId.TOP,
                            OverlayActionRegistry.PuckId.TR,
                            OverlayActionRegistry.PuckId.CENTER,
                            OverlayActionRegistry.PuckId.BL,
                            OverlayActionRegistry.PuckId.BOTTOM,
                            OverlayActionRegistry.PuckId.BR
                        )
                        for (puck in configPucks) {
                            val isSelected = selectedConfigPuck == puck
                            val puckColor = Color(android.graphics.Color.parseColor(puck.colorHex))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) puckColor else puckColor.copy(alpha = 0.28f))
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color.White else puckColor.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedConfigPuck = puck }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✧",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isSelected) Color.Black else puckColor
                                )
                            }
                        }
                    }

                    // 2. Selected Puck Header & Enable Toggle
                    val currentPuck = selectedConfigPuck
                    val puckColor = Color(android.graphics.Color.parseColor(currentPuck.colorHex))
                    val isCenter = currentPuck == OverlayActionRegistry.PuckId.CENTER

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF181820))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(puckColor)
                            )
                            Text(
                                text = currentPuck.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        if (!isCenter) {
                            var isEnabled by remember(_tick, currentPuck) {
                                mutableStateOf(OverlayActionRegistry.isPuckEnabled(prefs, currentPuck))
                            }
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { checked ->
                                    isEnabled = checked
                                    prefs.edit().putBoolean(currentPuck.enabledPrefKey, checked).apply()
                                    bindingRefreshTick++
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = puckColor,
                                    checkedTrackColor = Color(0xFF334B77)
                                )
                            )
                        } else {
                            Text(
                                text = "Core ✧",
                                fontSize = 11.sp,
                                color = textDim,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // 3. Direction Slots for selected puck
                    val activeSlots = if (isCenter) {
                        listOf(
                            OverlayActionRegistry.DirectionSlot.TAP,
                            OverlayActionRegistry.DirectionSlot.UP,
                            OverlayActionRegistry.DirectionSlot.DOWN
                        )
                    } else {
                        OverlayActionRegistry.DirectionSlot.entries
                    }

                    for (slot in activeSlots) {
                        val slotPrefKey = OverlayActionRegistry.prefKeyForSlot(currentPuck, slot)
                        val actionSpec = remember(_tick, currentPuck, slot) {
                            OverlayActionRegistry.getSlotAction(prefs, currentPuck, slot)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF181820))
                                .clickable {
                                    activePickerSlot = slotPrefKey to "${currentPuck.title} • ${slot.label}"
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${slot.arrowGlyph} ${slot.label}",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = OverlayActionRegistry.getDisplayLabel(context, actionSpec),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accentColor
                            )
                        }
                    }

                    HorizontalDivider(color = dividerColor, thickness = 1.dp)

                    // 4. Spread & Offset Calibration
                    Text(
                        text = "Placement Calibration",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val spreads = listOf(0.8f to "Compact", 1.0f to "Standard", 1.2f to "Wide")
                        for ((scaleVal, sLabel) in spreads) {
                            val isSel = kotlin.math.abs(puckSpreadScale - scaleVal) < 0.05f
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) accentColor else Color(0xFF181820))
                                    .clickable {
                                        puckSpreadScale = scaleVal
                                        prefs.edit().putFloat(Constants.PREF_PUCK_SPREAD_SCALE, scaleVal).apply()
                                    }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = sLabel,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) Color.Black else Color.White
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val offsets = listOf(65 to "Lower", 0 to "Center", -45 to "Upper")
                        for ((offDp, oLabel) in offsets) {
                            val isSel = puckVerticalOffsetDp == offDp
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) accentColor else Color(0xFF181820))
                                    .clickable {
                                        puckVerticalOffsetDp = offDp
                                        prefs.edit().putInt(Constants.PREF_PUCK_VERTICAL_OFFSET_DP, offDp).apply()
                                    }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = oLabel,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPuckConfigDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFF141418),
            shape = RoundedCornerShape(16.dp)
        )
    }

    activePickerSlot?.let { (targetPrefKey, slotTitle) ->
        val installedApps = remember {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(mainIntent, 0)
                .mapNotNull { info ->
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    val label = try { info.loadLabel(pm).toString() } catch (_: Exception) { pkg }
                    label to pkg
                }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
        }
        AlertDialog(
            onDismissRequest = { activePickerSlot = null },
            title = {
                Text(
                    text = "Bind Action: $slotTitle",
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    fontSize = 15.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ACTIONS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    for (act in OverlayActionRegistry.BUILT_IN_ACTIONS) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1C1C22))
                                .clickable {
                                    prefs.edit().putString(targetPrefKey, act.id).apply()
                                    bindingRefreshTick++
                                    activePickerSlot = null
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${act.shortGlyph}  ${act.label}",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = act.category,
                                fontSize = 10.sp,
                                color = textDim
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "INSTALLED APPS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                    for ((appLabel, pkg) in installedApps) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1C1C22))
                                .clickable {
                                    prefs.edit().putString(targetPrefKey, "${OverlayActionRegistry.APP_PREFIX}$pkg").apply()
                                    bindingRefreshTick++
                                    activePickerSlot = null
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = appLabel,
                                fontSize = 12.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activePickerSlot = null }) {
                    Text("Cancel", color = textDim)
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
                        // 1. === Fast-Path Automation ===
                        item {
                            SettingsSectionHeader(
                                title = "Fast-Path Automation",
                                isExpanded = isSectionExpanded("Fast-Path Automation"),
                                onToggle = { toggleSection("Fast-Path Automation") }
                            )
                        }
                        if (isSectionExpanded("Fast-Path Automation")) {
                            item {
                                SettingsToggleRow(
                                    title = "Fast-Path Router",
                                    subtitle = "Zero-token instant bypass for URLs, volume & system actions",
                                    checked = fastPathEnabled,
                                    onCheckedChange = { checked ->
                                        fastPathEnabled = checked
                                        prefs.edit().putBoolean(Constants.PREF_FAST_PATH_ENABLED, checked).apply()
                                        Toast.makeText(context, if (checked) "Fast-path router active ⚡" else "Fast-path router disabled (all queries go to LLM)", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }

                        // 2. === Live Wallpapers & Visualisers ===
                        item {
                            SettingsSectionHeader(
                                title = "Live Wallpapers & Visualisers",
                                isExpanded = isSectionExpanded("Live Wallpapers & Visualisers"),
                                onToggle = { toggleSection("Live Wallpapers & Visualisers") }
                            )
                        }
                        if (isSectionExpanded("Live Wallpapers & Visualisers")) {
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
                                                                Toast.makeText(context, "Operator Pass ($billingPrice 🦕💭💸) required to unlock $label geometry", Toast.LENGTH_SHORT).show()
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
                                                            Toast.makeText(context, "Operator Pass ($billingPrice 🦕💭💸) required for Edge Style $label", Toast.LENGTH_SHORT).show()
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
                        }

                        // === Summon & Gesture Controls ===
                        item {
                            SettingsSectionHeader(
                                title = "Summon & Gesture Controls",
                                isExpanded = isSectionExpanded("Summon & Gesture Controls"),
                                onToggle = { toggleSection("Summon & Gesture Controls") }
                            )
                        }
                        if (isSectionExpanded("Summon & Gesture Controls")) {
                            item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp)
                                    .background(cardBg, RoundedCornerShape(12.dp))
                                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Summon Method",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Choose how GHOST is summoned onto the screen",
                                    fontSize = 11.sp,
                                    color = textDim
                                )
                                val summonOptions = listOf(
                                    Constants.SUMMON_METHOD_SHAKE to "Shake",
                                    Constants.SUMMON_METHOD_EDGE_NUB to "Edge Nub",
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
                                                        Constants.SUMMON_METHOD_EDGE_NUB -> "Edge summon nub active 🎚️"
                                                        Constants.SUMMON_METHOD_BOTH -> "Shake & Edge summon nub active ✨"
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

                                // Master Hyper Pucks Toggle
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1C1C22))
                                        .clickable {
                                            val next = !hyperPucksEnabled
                                            hyperPucksEnabled = next
                                            prefs.edit().putBoolean(Constants.PREF_HYPER_PUCKS_ENABLED, next).apply()
                                            Toast.makeText(context, if (next) "Hyper Pucks enabled" else "Hyper Pucks disabled", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = "Hyper Pucks",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "6 orbital quick-action gesture pucks surrounding input bar",
                                            fontSize = 10.sp,
                                            color = textDim
                                        )
                                    }
                                    Switch(
                                        checked = hyperPucksEnabled,
                                        onCheckedChange = { checked ->
                                            hyperPucksEnabled = checked
                                            prefs.edit().putBoolean(Constants.PREF_HYPER_PUCKS_ENABLED, checked).apply()
                                            Toast.makeText(context, if (checked) "Hyper Pucks enabled" else "Hyper Pucks disabled", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = accentColor,
                                            checkedTrackColor = Color(0xFF334B77)
                                        )
                                    )
                                }

                                // Puck & Action Configurator Button
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1C1C22))
                                        .clickable { showPuckConfigDialog = true }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Customize Pucks, Gestures & Positions",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = accentColor
                                    )
                                    Text(text = "✧", fontSize = 14.sp, color = accentColor)
                                }
                            }
                        }
                        }

                        // 3. === Voice & Speech ===
                        item {
                            SettingsSectionHeader(
                                title = "Voice & Speech",
                                isExpanded = isSectionExpanded("Voice & Speech"),
                                onToggle = { toggleSection("Voice & Speech") }
                            )
                        }
                        if (isSectionExpanded("Voice & Speech")) {
                            item {
                            SettingsToggleRow(
                                title = "Direct Voice Output",
                                subtitle = "Speaks aloud responses to your direct messages and commands",
                                checked = ttsEnabled,
                                onCheckedChange = { checked ->
                                    ttsEnabled = checked
                                    prefs.edit().putBoolean(Constants.PREF_TTS_ENABLED, checked).apply()
                                    val svc = gemmaService ?: GemmaService.instance
                                    svc?.ttsManager?.isTtsEnabled = checked
                                    if (!checked) svc?.ttsManager?.stop()
                                    Toast.makeText(context, if (checked) "Direct voice enabled" else "Direct voice muted", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF141418))
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = "GPP Cues",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Spoken notifications, hardware ticks & ambient announcements",
                                            fontSize = 11.sp,
                                            color = Color(0x99FFFFFF),
                                            lineHeight = 15.sp
                                        )
                                    }
                                    Switch(
                                        checked = passiveTtsEnabled,
                                        onCheckedChange = { checked ->
                                            passiveTtsEnabled = checked
                                            prefs.edit().putBoolean(Constants.PREF_PASSIVE_TTS, checked).apply()
                                            Toast.makeText(context, if (checked) "GPP cues enabled" else "GPP cues disabled", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color(0xFF8BB4F6),
                                            checkedTrackColor = Color(0xFF334B77),
                                            uncheckedThumbColor = Color(0xFF666666),
                                            uncheckedTrackColor = Color(0xFF222222)
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1C1C22))
                                        .clickable { showPassiveCuesSubmenu = true }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Customize GPP Reflexes",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF8BB4F6)
                                    )
                                    Text(
                                        text = "⚙️",
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                        }

                        // 4. === Cloud Reasoning & Search Grounding ===
                        item {
                            SettingsSectionHeader(
                                title = "Cloud Reasoning & Search Grounding",
                                isExpanded = isSectionExpanded("Cloud Reasoning & Search Grounding"),
                                onToggle = { toggleSection("Cloud Reasoning & Search Grounding") }
                            )
                        }
                        if (isSectionExpanded("Cloud Reasoning & Search Grounding")) {
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
                                            prefs.edit().putBoolean("gemini_search_grounding", checked).apply()
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.Black,
                                            checkedTrackColor = accentColor
                                        )
                                    )
                                }
                            }
                        }
                        }

                        // 6. === Inference Engine ===
                        item {
                            SettingsSectionHeader(
                                title = "Inference Engine",
                                isExpanded = isSectionExpanded("Inference Engine"),
                                onToggle = { toggleSection("Inference Engine") }
                            )
                        }
                        if (isSectionExpanded("Inference Engine")) {
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
                            }
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
                        }

                        // === Autonomous Diary ===
                        item {
                            SettingsSectionHeader(
                                title = "Autonomous Diary",
                                isExpanded = isSectionExpanded("Autonomous Diary"),
                                onToggle = { toggleSection("Autonomous Diary") }
                            )
                        }
                        if (isSectionExpanded("Autonomous Diary")) {
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
                                        Toast.makeText(context, "Autonomous diary enabled", Toast.LENGTH_SHORT).show()
                                    } else {
                                        com.ghost.api.GemmaService.instance?.cancelDiaryAlarm()
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
                                    "Google Calendar sync ACTIVE: Reflections mirror as Δ 👾 ∇ calendar blocks."
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
                        }

                        // === System & Memory ===
                        item {
                            SettingsSectionHeader(
                                title = "System & Memory",
                                isExpanded = isSectionExpanded("System & Memory"),
                                onToggle = { toggleSection("System & Memory") }
                            )
                        }
                        if (isSectionExpanded("System & Memory")) {
                            item {
                            SettingsActionCard(
                                title = "Trigger Diary Log Now",
                                subtitle = "Generate an episodic reflection immediately",
                                onClick = {
                                    val svc = gemmaService ?: GemmaService.instance
                                    svc?.startDiaryCycle()
                                    Toast.makeText(context, "Generating diary entry...", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                        item {
                            SettingsActionCard(
                                title = "View Diary History",
                                subtitle = "Inspect recent episodic memory logs",
                                onClick = {
                                    showDiaryHistoryDialog = true
                                    onShowDiaryHistory()
                                }
                            )
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
                    }

                    // === Permissions & Access ===
                    if (!isNotifGranted || !isOverlayGranted || !isAccessibilityGranted) {
                        item {
                            SettingsSectionHeader(
                                title = "Required System Access",
                                isExpanded = isSectionExpanded("Required System Access"),
                                onToggle = { toggleSection("Required System Access") }
                            )
                        }
                        if (isSectionExpanded("Required System Access")) {
                            if (!isAccessibilityGranted) {
                                item {
                                    SettingsActionCard(
                                        title = "Grant Accessibility Service",
                                        subtitle = "Required for active agent app awareness & dynamic avatar reactivity",
                                        isWarning = true,
                                        onClick = {
                                            showAccessibilityDisclosureDialog = true
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

                        // === Flavor-specific Bottom Card ===
                        val passTitle = if (BuildConfig.DISTRIBUTION_FLAVOR == "fdroid") "Guardian" else "Operator Pass"
                        item {
                            SettingsSectionHeader(
                                title = passTitle,
                                isExpanded = isSectionExpanded(passTitle),
                                onToggle = { toggleSection(passTitle) }
                            )
                        }
                        if (isSectionExpanded(passTitle)) {
                            if (BuildConfig.SHOW_OPERATOR_PASS_PAYWALL) {
                                // Play Store Flavor: Operator Pass (Tree Fiddy 🦕💭💸 Hunter vs Δ 👾 ∇ Operator)
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
                                                text = if (isOperatorTier) "OPERATOR" else "HUNTER",
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
                                                "Elite status unlocked. At your service, Operator. Δ 👾 ∇ Turing glyph, reactive edge lights II-IV, hexagonal/prismatic/cuboid visualizers, and custom operator avatars active."
                                            } else {
                                                "\"I need about tree fiddy...\" 🦕💭💸 100% uncrippled local offline AI & privacy. Unlock Operator status ($billingPrice) to get the elite Δ 👾 ∇ glyph, custom avatars, visualizer geometries, and edge light styles."
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
                                            // Free Tier Button: 🦕💭💸 [Hunter]
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
                                                            Toast.makeText(context, "Hunter Tier active 🦕💭💸", Toast.LENGTH_SHORT).show()
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
                                                        text = "Hunter",
                                                        fontSize = 11.sp,
                                                        color = if (!isOperatorTier) Color(0xFF8BB4F6) else textDim
                                                    )
                                                }
                                            }

                                            // Operator Tier Button: Δ 👾 ∇ [Operator]
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
                                                            Toast.makeText(context, "Operator Pass active Δ 👾 ∇", Toast.LENGTH_SHORT).show()
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
                                                        text = if (isOperatorTier) "Operator" else "Operator ($billingPrice)",
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
                            // F-Droid Flavor: FOSS Guardian (zero upsells, zero locks)
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
                                                text = "GUARDIAN",
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
                                            text = "Eyes up, Guardian: news and updates",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
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
                                                    text = "Here ↗",
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
                            // Patreon / Supporter Flavor: Fully Unlocked Operator
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
                                                text = "OPERATOR",
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
                                            text = "Operator, would you like to keep up with the project's updates? I would be delighted to take you there!",
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
                                                    text = "Here ↗",
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
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    isExpanded: Boolean = true,
    onToggle: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onToggle != null) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggle)
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                } else {
                    Modifier.padding(top = 8.dp, bottom = 4.dp)
                }
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF8BB4F6),
            letterSpacing = 1.sp
        )
        if (onToggle != null) {
            Text(
                text = if (isExpanded) "▾" else "▸",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8BB4F6)
            )
        }
    }
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

@Composable
private fun SettingsSubmenuToggle(
    icon: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1C1C22))
            .clickable { onCheckedChange(!checked) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = Color(0x99FFFFFF),
                    lineHeight = 13.sp
                )
            }
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

