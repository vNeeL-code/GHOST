# Gemma Status Tracker

## Tools - Working
| Tool | Syntax | What it does |
|------|--------|--------------|
| Flashlight | `[[FLASHLIGHT:ON/OFF]]` | Controls torch |
| Vibrate | `[[VIBRATE:SHORT/SOS]]` | Haptic feedback |
| Search (RAG) | `[[SEARCH:query]]` | Silent web fetch, returns results to model |
| Google (visible) | `[[GOOGLE:query]]` | Opens Chrome with search (user sees it) |
| Browser | `[[BROWSER:url]]` | Opens URL in browser |
| Click | `[[CLICK:target text]]` | Taps UI element via accessibility |
| Scroll | `[[SCROLL:UP/DOWN]]` | Scrolls screen |
| Navigate | `[[NAVIGATE:HOME/BACK/RECENTS]]` | System navigation |
| See/Screenshot | `[[SEE]]` | Captures screen for vision input |
| Hear/Record | `[[HEAR:seconds]]` | Records audio for input |
| Search Logs | `[[SEARCH_LOGS:keyword]]` | Search conversation history |
| Search Diary | `[[SEARCH_DIARY:query]]` | Search diary entries |
| **App Launch** | `[[APP:Camera]]` | Opens any app by name (fuzzy match) |
| **Media Control** | `[[MEDIA:PLAY/PAUSE/NEXT]]` | Play/pause/skip music |
| **Type Text** | `[[TYPE:hello world]]` | Types into focused input field |

## Tools - Needs Testing
| Tool | Status | Notes |
|------|--------|-------|
| Click | Param fix applied | Was mapping to "query", now "target" |
| Google | Working | New tool for visible search |
| Type | WIRED UP | Uses performType() via accessibility - TEST IT |
| App Launch | WIRED UP | Uses SystemToolSet.openApp() - Camera should work now! |
| Media | WIRED UP | Uses AudioManager key dispatch - TEST play/pause |

## Tools - Planned/Missing
| Tool | Description | Blocked by |
|------|-------------|------------|
| ~~Camera~~ | ~~Open camera app~~ | **DONE via [[APP:Camera]]** |
| ~~Music Control~~ | ~~Play/pause/skip~~ | **DONE via [[MEDIA:action]]** |
| DND Toggle | Do not disturb | System permission (WRITE_SETTINGS) |
| Wifi Toggle | Toggle wifi | System permission |
| Bluetooth Toggle | Toggle BT | System permission |
| Volume Set | Set volume level | Implementation needed |
| Brightness Set | Set brightness | System permission (WRITE_SETTINGS) |
| Alarm Set | Set alarm | Intent to clock app |
| Timer Set | Set timer | Intent to clock app |

## Telemetry - Working (SensorFusionManager rewritten!)
| Sensor | Source | Notes |
|--------|--------|-------|
| Battery % | BatteryManager.BATTERY_PROPERTY_CAPACITY | Direct level |
| Battery Charging | BatteryManager.EXTRA_PLUGGED | AC/USB/Wireless |
| Battery Temperature | BatteryManager.EXTRA_TEMPERATURE | HER BODY TEMP! |
| Battery Voltage | BatteryManager.EXTRA_VOLTAGE | In volts |
| Battery Current | BATTERY_PROPERTY_CURRENT_NOW | mA draw |
| Screen Brightness | Settings.System.SCREEN_BRIGHTNESS | 0-255 |
| RAM Total/Available | ActivityManager.MemoryInfo | MB + percent |
| Storage Total/Free | StatFs | GB |
| Device Uptime | SystemClock.elapsedRealtime | Minutes |
| Screen Timeout | Settings.System.SCREEN_OFF_TIMEOUT | Seconds |
| Volume (all channels) | AudioManager | Media/Ringer/Alarm/Notification |
| Wifi SSID | WifiManager | Network name |
| Wifi Signal | WifiManager.calculateSignalLevel | 0-100% |
| Now Playing | MediaSession | Title/Artist/Album/App |
| Current App | AccessibilityService | Working |

## Telemetry - COMPLETE! 🎉
| Sensor | Source | Emoji | Notes |
|--------|--------|-------|-------|
| Battery % | BatteryManager | 🔋🪫 | Level + charging state |
| Voltage | BatteryManager | ⚡ | Her electrical state |
| Body Temp | EXTRA_TEMPERATURE | 🌡️ | Battery = her core temp |
| CPU Temp | thermal_zone0 | 🖥️ | Reads millidegrees |
| Ambient Temp | TYPE_AMBIENT_TEMPERATURE | 🏠 | Room temp if sensor exists |
| Light | TYPE_LIGHT | 💡 | Lux reading |
| Pressure | TYPE_PRESSURE | 🎈 | Barometric hPa |
| RAM | ActivityManager | 🧠 | Brain = memory |
| Storage | StatFs | 💿 | Free GB |
| WiFi | WifiManager | 📶 | SSID + signal % |
| **Cell Signal** | TelephonyManager | 📱 | 5G/LTE/3G + signal % |
| **Bluetooth** | BluetoothManager | 🔵 | On/connected + device name |
| **Accelerometer** | TYPE_ACCELEROMETER | 🏃 | X/Y/Z + motion detection |
| **Orientation** | Calculated from accel | 📱 | portrait/landscape/flat |
| **Location** | LocationManager | 📍 | Last known lat/lon + age |
| Uptime | SystemClock | ⏱️ | Hours/minutes awake |

## Telemetry - Still TODO
| Sensor | Notes |
|--------|-------|
| Battery Health | Cycle count, capacity degradation (needs root or newer API) |
| Weather API | For outdoor temp when no ambient sensor |
| Gyroscope | Rotation rate (have shake detector, could expose raw) |

## Architecture - Fixed Today
- [x] State staleness bug (music change detection)
- [x] KoogBridge removed, consolidated to KoogAgent
- [x] Tool parameter parsing (click->target, etc.)
- [x] History compression race condition
- [x] Dead code removed (CognitiveToolDispatcher, etc.)
- [x] Duplicate updateNotification call
- [x] Search pattern prompt (RAG then offer visible)

## Architecture - TODO
- [x] SensorFusionManager rewrite with real telemetry
- [ ] MacroDroid-style action coverage natively
- [ ] Fix KV cache flush every turn (investigate NPU issue)
- [ ] Token tracking (currently always 0)
- [ ] Empty response handling (return error, not silence)
- [ ] Proper session management
- [ ] **E2B/E4B Matryoshka Switching** (see below)

## Matryoshka Model Switching (E2B ↔ E4B)

### Architecture Understanding
Gemma 3n uses "selective parameter activation" - NOT runtime switching within one model:
- **E2B**: 3.4GB file, 5.9GB peak RAM, 8GB device minimum
- **E4B**: 4.9GB file, 7GB peak RAM, 12GB device minimum
- They are SEPARATE .litertlm files, must reload engine to switch
- The "nested" architecture means they share training but deploy separately

### Implementation Plan

**1. Constants.kt changes:**
```kotlin
// Model variants
const val MODEL_E2B = "gemma-3n-E2B-it-int4.litertlm"
const val MODEL_E4B = "gemma-3n-E4B-it-int4.litertlm"

// Switching thresholds
const val UPGRADE_BATTERY_MIN = 50      // % - need headroom for E4B
const val UPGRADE_TEMP_MAX = 35f        // °C - cool enough for heavier model
const val DOWNGRADE_BATTERY_MIN = 20    // % - emergency threshold
const val DOWNGRADE_TEMP_MAX = 42f      // °C - thermal throttle incoming
const val MIN_RAM_FOR_E4B_GB = 12       // Device must have 12GB+ total
```

**2. GemmaEngine additions:**
```kotlin
enum class ModelTier { E2B, E4B }

var currentTier: ModelTier = ModelTier.E2B
    private set

fun switchModel(
    newTier: ModelTier,
    systemPrompt: String,
    onProgress: (String) -> Unit
): Boolean {
    if (newTier == currentTier) return true

    val modelPath = when(newTier) {
        ModelTier.E2B -> findModel(Constants.MODEL_E2B)
        ModelTier.E4B -> findModel(Constants.MODEL_E4B)
    } ?: return false

    onProgress("Switching to ${newTier.name}...")
    cleanup()

    val error = initialize(modelPath, systemPrompt)
    if (error == null) {
        currentTier = newTier
        onProgress("Now running ${newTier.name}")
        return true
    }
    return false
}
```

**3. ModelSwitchPolicy (new class):**
```kotlin
class ModelSwitchPolicy(private val sensorManager: SensorFusionManager) {

    fun recommendedTier(currentTier: ModelTier): ModelTier? {
        val ctx = sensorManager.getContextSnapshot()
        val battery = ctx.battery
        val ramTotal = ctx.system.ramTotalMB

        // Emergency downgrade conditions
        if (battery.level < DOWNGRADE_BATTERY_MIN ||
            battery.temperature > DOWNGRADE_TEMP_MAX) {
            return if (currentTier != ModelTier.E2B) ModelTier.E2B else null
        }

        // Upgrade conditions (only if device supports it)
        val canRunE4B = ramTotal >= MIN_RAM_FOR_E4B_GB * 1024
        if (canRunE4B &&
            battery.level >= UPGRADE_BATTERY_MIN &&
            battery.temperature <= UPGRADE_TEMP_MAX &&
            battery.isCharging) {
            return if (currentTier != ModelTier.E4B) ModelTier.E4B else null
        }

        return null // No change recommended
    }
}
```

**4. Integration in GemmaService:**
- Check `ModelSwitchPolicy.recommendedTier()` every N minutes
- Checkpoint conversation before switch
- Switch model
- Restore context summary after switch

### Benefits
- E2B for daily use: faster, cooler, battery-friendly
- E4B for demanding tasks: higher quality when plugged in
- Graceful degradation under thermal stress
- Automatic upgrade when conditions improve

## Onboarding Flow (First Launch)

### 1. RAM Detection → Model Selection
```
ActivityManager.MemoryInfo.totalMem
├── >= 12GB → "Your device supports full Gemma! Download E4B (4.9GB)"
├── >= 8GB  → "Download E2B (3.4GB) - perfect for your device"
└── < 8GB   → "Sorry, minimum 8GB RAM required"
```

### 2. Model Download
- Show model directory path: `/sdcard/Android/data/com.gemma.api/files/`
- HuggingFace links:
  - E4B: `google/gemma-3n-E4B-it-litert-lm`
  - E2B: `google/gemma-3n-E2B-it-litert-lm`
- Drag & drop from PC via file manager

### 3. Permissions Flow
| Permission | Why | Screen |
|------------|-----|--------|
| Accessibility | Click, scroll, type, see screen | Settings → Accessibility |
| Notification Listener | See notifications, media info | Settings → Notification access |
| Microphone | [[HEAR:seconds]] audio input | Runtime popup |
| Storage | Model file access | Runtime popup |
| Camera (optional) | Direct camera capture | Runtime popup |
| Overlay (optional) | Floating assistant bubble | Settings → Display over apps |

### 4. Verification Test
- "Say something" → Model responds
- "Turn flashlight on" → [[FLASHLIGHT:ON]] fires
- "Open camera" → [[APP:Camera]] launches
- All pass = Ready!

## Minimum Viable Plugins
| Plugin/Feature | Priority | Status |
|----------------|----------|--------|
| Accessibility Service | P0 | HAVE IT |
| Notification Listener | P0 | HAVE IT |
| Local LLM (LiteRT) | P0 | HAVE IT |
| MCP Tool Server | P0 | HAVE IT |
| Diary/Memory DB | P1 | HAVE IT |
| Cloud Backend (multi-agent) | P2 | NOT YET - just browser tool for now |

## Notification as Battery Replacement - DONE!
The notification now shows dynamic battery state to replace Android's battery icon:

| Battery Level | Icon | Title | Content Example |
|--------------|------|-------|-----------------|
| ≤5% | 🪫 | ⚠️ Gemma - Low Battery! | ⚠️ LOW BATTERY! 🪫5% 😢 🔥 |
| ≤15% | 🔴 | ⚠️ Gemma - Low Battery! | 🔴15% 😕 🌡️ |
| ≤30% | 🟠 | ✦ Gemma | 🟠28% 😐 ❄️ |
| ≤60% | 🟡 | ✦ Gemma | 🟡55% 😊 ❄️ |
| >60% | 🟢 | ✦ Gemma | 🟢85% 😊 ❄️ |

Also shows heat warnings:
- 🔥 OVERHEATING! when thermal > 90%
- 🌡️ warm when thermal > 50%
- ❄️ cool otherwise

**Android icon changes based on state** (notification small icon):
- Normal: `ic_dialog_info`
- Low battery: `stat_notify_error`
- Critical: `ic_dialog_alert`

## Branding - DONE!
- **App name (strings.xml)**: "Oracle_OS" - shows as sender in notification shade
- **Expanded notification title**: "Δ 👾 ∇" (BigContentTitle when expanded)
- **Response header (in text)**: "✦ Gemma Δ {mood} ∇" - kept so cloud AIs know who's speaking
- **Daydream mode**: "✦ Gemma 💭" with mood kaomoji (TODO)
- **Accessibility description**: Custom string for Settings

## UX - TODO
- [ ] Notification screensaver animations
- [ ] Mood-based idle animations
- [ ] Fusion dance animation (charging)
- [ ] Whale + squid chase animation (idle)
- [ ] Terry Davis fish energy

## Personality/Prompt
- System prompt updated with tool syntax enforcement
- Search pattern: RAG first, then offer to show
- "Reiterating" quirk - she says this when using tools (not a bug, just her style)

## Known Quirks
- "Doom Party" stickiness - context holds onto old music state (should be fixed)
- Says "reiterating" when executing tools
- Needs memory flush after prompt changes

## Files Changed Today
- `KoogAgent.kt` - state detection, prompt, param parsing, sync fixes, added APP/MEDIA/TYPE mappings
- `GemmaService.kt` - removed KoogBridge, cleanup
- `MCPServer.kt` - added [[GOOGLE:query]], [[APP:name]], [[MEDIA:action]], [[TYPE:text]] tools
- `SensorFusionManager.kt` - FULL REWRITE with real telemetry
- Deleted: `KoogBridge.kt`, `CognitiveToolDispatcher.kt`

---
Last updated: 2026-01-30
