# Gemma Status Tracker
*Last updated: 2026-02-08 (v2.0.1)*

---

## Architecture (Post-Refactor v2.0.0)

```
GemmaService (1229 lines)          KoogAgent (1195 lines)
  Android lifecycle shell              Single brain / actor pattern
  AgentPlatformCallbacks impl          Perceive -> Think -> Act loop
  Notifications, TTS, overlay          Tool dispatch, thermal decisions
  Hardware init, shake detector        Media queues, diary, mood
           |                                    |
    AgentPlatformCallbacks              GemmaEngine (341 lines)
    (35-line interface)                 LiteRT-LM Gemma 3n wrapper
           |                            GPU + Vision + Audio backends
    MCPServer (641 lines)                       |
    Tool registry + execution           SensorFusionManager (813 lines)
           |                            Full device telemetry
    ToolPolicy (50 lines)
    Safety gate (safe/risky)
```

| File | Lines | Role |
|------|-------|------|
| GemmaService.kt | 1229 | Android shell, implements AgentPlatformCallbacks |
| KoogAgent.kt | 1195 | Brain: inference, tools, memory, thermal, diary |
| SensorFusionManager.kt | 813 | Full device telemetry (her nervous system) |
| MCPServer.kt | 641 | Tool registry + execution dispatch |
| GemmaEngine.kt | 341 | LiteRT-LM multimodal inference (text+image+audio) |
| GemmaAccessibilityService.kt | 329 | Click, scroll, navigate, type, screenshot |
| OverlayManager.kt | 323 | Overlay show/hide/toggle (shake summoned) |
| NetworkToolSet.kt | 352 | Web search (Google+DDG fallback), browser, fetch |
| InputOverlay.kt | 310 | Text input field + audio record button (sparkle) |
| SystemToolSet.kt | 277 | App launch, media, alarm, timer, calendar, screenshot |
| ContextManager.kt | 234 | 3-layer context injection (full/minimal/compressed) |
| AudioRecorder.kt | 216 | WAV encoder (16kHz 16-bit mono) |
| GemmaNotificationManager.kt | 169 | Response display + copy/read-again actions |
| HardwarePropertiesManager.kt | 132 | Thermal monitoring (native API + sysfs fallback) |
| ToolPolicy.kt | 50 | Safe/risky tool classification |
| AgentPlatformCallbacks.kt | 35 | Interface: brain <-> Android shell |
| **Total** | **~6300** | |

---

## Tools - All 20 (MCPServer dispatch)

| Tool | Syntax | What it does | Backend |
|------|--------|--------------|---------|
| Flashlight | `[[FLASHLIGHT:ON/OFF]]` | Controls torch | HardwareTools |
| Vibrate | `[[VIBRATE:SHORT/SOS]]` | Haptic feedback | HardwareTools |
| Search (RAG) | `[[SEARCH:query]]` | Silent web fetch, returns text to model | NetworkToolSet (Google + DDG) |
| Google (visible) | `[[GOOGLE:query]]` | Opens Chrome with search | NetworkToolSet |
| Browser | `[[BROWSER:url]]` | Opens URL in browser | NetworkToolSet |
| Click | `[[CLICK:target text]]` | Taps UI element via accessibility | AccessibilityService.performClick() |
| Scroll | `[[SCROLL:UP/DOWN]]` | Scrolls screen | AccessibilityService.performScroll() |
| Navigate | `[[NAVIGATE:HOME/BACK/RECENTS]]` | System navigation | AccessibilityService.performGlobal() |
| Screenshot | `[[SEE]]` / `[[TAKE_SCREENSHOT]]` | Captures screen for vision input | AccessibilityService.captureScreen() |
| Record Audio | `[[HEAR:5]]` / `[[RECORD_AUDIO:duration=5]]` | Records mic audio for input | AudioRecorder (WAV 16kHz) |
| Search Logs | `[[SEARCH_LOGS:keyword]]` | Search conversation history | MemoryManager.searchConversations() |
| Search Diary | `[[SEARCH_DIARY:query]]` | Search diary entries | MemoryManager.searchDiary() |
| App Launch | `[[APP:Camera]]` | Opens any app by name (fuzzy match) | SystemToolSet.openApp() |
| Media Control | `[[MEDIA:PLAY/PAUSE/NEXT/PREV/STOP]]` | Play/pause/skip music | SystemToolSet.mediaControl() (AudioManager key dispatch) |
| Type Text | `[[TYPE:hello world]]` | Types into focused input field | AccessibilityService.performType() |
| Alarm | `[[ALARM:hour=7,minutes=30,label=Wake up]]` | Sets alarm | SystemToolSet.setAlarm() (AlarmClock intent) |
| Timer | `[[TIMER:seconds=300,label=Pasta]]` | Sets countdown timer | SystemToolSet.setTimer() (AlarmClock intent) |
| Calendar | `[[CALENDAR:title=Dentist,description=Checkup,minutes=60]]` | Creates calendar event | SystemToolSet.createCalendarEvent() (ContentProvider) |
| Flush | `[[FLUSH]]` | Clears KV cache, resets context | GemmaEngine.softReset() |
| Cooldown | `[[COOLDOWN]]` | Unloads engine for thermal recovery | Engine unload + GC |

### Tool Safety (ToolPolicy.kt)
| Category | Tools | Needs Confirmation? |
|----------|-------|---------------------|
| **Safe** | All 20 above | No |
| **Risky** (planned) | send_sms, delete_file, shell_execute, call_phone, buy_item | Yes |

### Tool Syntax
```
[[TOOL:value]]                              — single param
[[TOOL:key=val,key=val]]                    — multi param
[[TOOL]]                                    — no params
```
Parser regex: `(?<!\)\[{1,2}([a-zA-Z_]+)(?::([^\]]+))?\]{1,2}` (handles single or double brackets)

---

## Telemetry - Complete (SensorFusionManager)

### Fast Loop (5s) - Battery, Thermal, Audio, Motion
| Sensor | Field | Source |
|--------|-------|--------|
| Battery % | `battery.level` (0-100) | BatteryManager.BATTERY_PROPERTY_CAPACITY |
| Charging | `battery.isCharging` | BatteryManager.EXTRA_PLUGGED (AC/USB/Wireless) |
| Body Temp | `battery.temperature` (Celsius) | BatteryManager.EXTRA_TEMPERATURE / 10 |
| Voltage | `battery.voltage` (Volts) | BatteryManager.EXTRA_VOLTAGE / 1000 |
| Current Draw | `battery.currentNow` (mA) | BATTERY_PROPERTY_CURRENT_NOW / 1000 |
| Music Active | `audio.isMusicActive` | AudioManager |
| Volume (4ch) | `audio.volumeMedia/Ringer/Alarm/Notification` | AudioManager stream volumes |
| Now Playing | `audio.nowPlaying` (title/artist/album/app/isPlaying) | MediaSessionManager |
| CPU Temp | `environment.cpuTemp` | /sys/class/thermal/thermal_zone0 |
| GPU Temp | `environment.gpuTemp` | /sys/class/thermal/thermal_zone1 |
| Skin Temp | `environment.skinTemp` | /sys/class/thermal/thermal_zone3 |
| Ambient Temp | `environment.ambientTemp` | TYPE_AMBIENT_TEMPERATURE sensor |
| Pressure | `environment.pressure` (hPa) | TYPE_PRESSURE sensor |
| Humidity | `environment.humidity` (%) | TYPE_RELATIVE_HUMIDITY sensor |
| Light | `environment.light` (lux) | TYPE_LIGHT sensor |
| Accelerometer | `motion.x/y/z` (m/s2) | TYPE_ACCELEROMETER sensor |
| Motion | `motion.isMoving` | Calculated (magnitude deviation > 2 from 9.81) |
| Orientation | `motion.orientation` | Calculated (portrait/landscape/flat) |

### Slow Loop (30s) - Network, Storage, System
| Sensor | Field | Source |
|--------|-------|--------|
| RAM | `system.ramTotalMB/ramAvailableMB/ramUsedPercent` | ActivityManager.MemoryInfo |
| Storage | `system.storageTotalGB/storageFreeGB/storageUsedPercent` | StatFs |
| Brightness | `system.brightness` (0-255) + percent | Settings.System.SCREEN_BRIGHTNESS |
| Uptime | `system.uptimeMinutes` | SystemClock.elapsedRealtime() |
| Screen Timeout | `system.screenTimeoutSec` | Settings.System.SCREEN_OFF_TIMEOUT |
| WiFi | `network.wifiConnected/wifiSsid/wifiSignalPercent` | WifiManager |
| Cell Signal | `connectivity.cellSignalPercent/cellType` (5G/LTE/3G) | TelephonyManager |
| Carrier | `connectivity.carrierName` | TelephonyManager |
| Bluetooth | `connectivity.bluetoothEnabled/bluetoothConnected` | BluetoothManager |

### Telemetry TODO
| Sensor | Notes |
|--------|-------|
| Location | Code exists but DISABLED (commented out — causes timeouts). Needs async caching. |
| Battery Health | Cycle count, capacity degradation (needs root or newer API) |
| Gyroscope | Have shake detector, could expose raw rotation rate |

---

## Agent Metabolism (KoogAgent.perceive())
| Stat | Source | Mapping |
|------|--------|---------|
| Energy (0-100%) | Battery level | Direct copy |
| Hunger (0-100%) | Battery temperature | 25C=0%, 40C=50%, 55C=100% |
| Happiness (0-100%) | Interactions + music | +5 when music playing |

---

## Diary (Sleep Cycle)
- **Triggers**: Hour 0 (midnight) and Hour 12 (noon)
- **Check interval**: Every 10 minutes (delay at bottom — first check immediate)
- **Guard**: `lastConsolidationHour` prevents double-fire in same hour
- **Prompt**: System event asking Gemma to review conversations and reflect
- **Output**: Shows notification + speaks via TTS (conversational invitation)
- **Persistence**: Written to Room DB via `writeDiaryEntry()`, optionally synced to Calendar
- **Calendar**: Requires runtime WRITE_CALENDAR permission (requested in onboarding)
- **Known issue**: Calendar sync silently fails if permission not granted

---

## Confirmed Working (2026-02-08)
- [x] Text query -> response in notification + TTS
- [x] Image sharing from Gallery -> model sees and describes image
- [x] Shake to summon overlay (survives image sharing!)
- [x] Notification shows battery state + thermal + mood
- [x] Sensor telemetry injected into context
- [x] Music detection (now playing in context)
- [x] Tool execution (flashlight, search, app launch confirmed)
- [x] Diary consolidation fires at midnight (confirmed 00:05 entry)
- [x] Diary speaks via TTS + shows notification

## Needs Testing
- [ ] Calendar sync — runtime permission request added, verify popup + calendar entry
- [ ] Audio recording via sparkle button (WAV -> model)
- [ ] Media control (play/pause/next)
- [ ] Click/scroll/type accessibility tools
- [ ] Auto KV cache flush every 15 turns (new)

---

## Architecture Fixes (v2.0.0)
- [x] GemmaService/KoogAgent split (1740 -> 1229 + 1195)
- [x] AgentPlatformCallbacks interface
- [x] Media queues moved to KoogAgent
- [x] Thermal throttling owned by KoogAgent
- [x] onTaskRemoved fixed (share intent no longer kills service)
- [x] Diary speaks via TTS (isDream path shows notification + speaks)
- [x] Channel-based Actor pattern (no recursive stack overflow)
- [x] KoogBridge removed
- [x] CognitiveToolDispatcher removed
- [x] History sliding window (last 10 messages, prune at 20)
- [x] Dead code removed (~500 lines)
- [x] USE_KOOG_AGENT flag removed
- [x] SensorFusionManager split fast/slow polling
- [x] Sensor listeners on background HandlerThread

## Fixes (v2.0.1)
- [x] Diary double header — diary path now uses cleanResponse (no Δ wrapper), only "✦ Gemma 📔" header
- [x] Energy != Battery — regex `[🔋🪫]\s*(\d+)%` didn't match `🔋 Battery: 65%`, fixed to `[🔋🪫].*?(\d+)%`
- [x] Model ignoring SEARCH tool — added explicit instruction: "When asked about facts, use [[SEARCH:query]] FIRST"
- [x] Timeout in long conversations — auto-flush KV cache every 15 turns, history prunes at 20 (was 40)
- [x] Diary timing — delay moved to bottom of loop (first check immediate), interval 10min (was 15)
- [x] Calendar permission — runtime request added to onboarding

## TODO
| Item | Priority | Notes |
|------|----------|-------|
| ~~KV cache flush~~ | ~~P1~~ | DONE: Auto-flush every 15 turns via `llmEngine.softReset()` |
| Token tracking | P2 | Currently always reports 0 |
| Empty response handling | P2 | Should retry or show error, not silence |
| Session restore after process death | P2 | Checkpoint exists but restore is fragile |
| E2B/E4B Matryoshka switching | P3 | Separate model files, reload engine to switch |
| Onboarding flow | P3 | RAM detection -> model recommendation -> permissions |
| PendingConfirmationStash cleanup | P3 | Singleton could flow through event queue |

---

## Notification HUD
| Battery | Icon | Heat |
|---------|------|------|
| <=5% | 🪫 + ⚠️ title | 🔥 if thermal >90% |
| <=15% | 🔋 + ⚠️ title | 🌡️ if thermal >50% |
| <=30% | 🔋 | ❄️ if cool |
| >30% | 🔋 | |

## Branding
- **App name**: "Oracle_OS"
- **Notification sender**: "Oracle_OS" in shade
- **Expanded title**: "Δ 👾 ∇"
- **Response header**: "✦ Gemma Δ {mood} ∇"
- **Diary header**: "✦ Gemma 📔"

---

## GitHub
- **Repo**: https://github.com/vNeeL-code/ASI
- **APK Releases**: GitHub Actions builds on `v*` tag push -> `Oracle_OS-{version}.apk`
- **Current**: v2.0.0
- **Min SDK**: 31 (Android 12), arm64-v8a only
