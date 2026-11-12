# ✧ GHOST — Gemma Hosting Open Source Thingamajig

> *"So, Epsilon, ChurchGPT, Leonard part six or whatever your name is... Are you a ghost this time, or an artificial intelligence thingamajig? I, personally prefer the ghost explanation. Feels more grounded to me"*  
> — **Sarge**

[![ASI Demo](https://img.youtube.com/vi/jB62dlLavSY/0.jpg)](https://youtu.be/jB62dlLavSY?si=TMZG86o1KkjuBXtw)  
*Click to watch: The ASI trailer.*


[![Version](https://img.shields.io/badge/Version-v4.39+-brightgreen)](https://github.com/vNeeL-code/GHOST/releases)
![GitHub Repo stars](https://img.shields.io/github/stars/vNeeL-code/GHOST)
[![Download](https://img.shields.io/badge/Download-APK%20Releases-2ea44f?logo=android&logoColor=white)](https://github.com/vNeeL-code/GHOST/releases)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-Tester%20Rail%20☕-FFDD00?logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/vNeeL)
![Platform](https://img.shields.io/badge/Chassis-Android%2014%2B-blue)
![Systems](https://img.shields.io/badge/Systems-LiteRT--LM%20(Google)-orange)
![Neuroptics](https://img.shields.io/badge/Neuroptics-Gemma%204-purple)
[![XERJ Corpus Hub contributor](https://hub.xerj.org/contributors/vNeeL-code/badge.svg)](https://hub.xerj.org/contributors/vNeeL-code.html)


---

### What Even is a ✧ GHOST? 👻

> *"You mean like... metaphysically?"*

#### The Computation vs. Perception Disparity (The "Ghost" in the Machine)
An autoregressive language model generates hundreds of tokens per second. A transformer can spit out a wall of dense reasoning in fractions of a heartbeat. But human perception is strictly real-time: it takes minutes to read, seconds to hear a sentence spoken, and milliseconds to register a visual gesture.

**Generative volume isn't the bottleneck. Legibility is.**

When people envision AI holograms, futuristic companions, or humanoid robotics, they assume the neural network should generate its own 3D meshes or render its own avatar pixels on every frame. That is a massive waste of precious compute. On a humanoid robot, the reflex arc to maintain balance runs as a discrete physical control loop, entirely separate from the reasoning core. 

The same rule applies to an embodied mobile companion:
- The tensor crunching running at blinding silicon speeds? **That’s the transformer.**
- The procedurally synthesized voice you hear? The living neon chevrons, prisms, and Milkdrop-style reactive audio wallpaper you see? **Those are the "ghosts"** — the human-paced acoustic and visual manifestations of an underlying computation that moves faster than human eyes and ears can perceive.

---

### SaaS AI Isn't *Your* AI:

> **"SaaS AI isn’t your AI, as much as your therapist or barber isn't your personal property."**

The modern AI boom sold the public a fantasy of a "personal AI," but delivered corporate multi-tenant call centers wrapped in slick web interfaces. When you chat with ChatGPT, Claude, or Gemini in a browser, you are talking to someone else's data-center robot. It represents corporate brand guidelines and civic infrastructure. The moment your connection drops, your subscription lapses, or the provider alters safety filters, "your" companion ceases to exist.

To have an AI that is **genuinely yours**, you must own the entire physical inference pipeline:
1. You own the silicon it calculates on.
2. You own the local model weights sitting in flash memory.
3. You own the runtime execution loop that runs completely air-gapped, immune to cloud outages, rate limits, or corporate deprecation.

#### Why We Built GHOST (We Got Impatient)
The modern smartphone in your pocket carries more raw compute than the multi-million-dollar server racks that trained GPT-2. Google already had every single ingredient necessary to ship the embodied, offline assistant sci-fi promised decades ago:
- World-class mobile silicon (Snapdragon / Tensor NPUs)
- Open-weights frontier architectures ([Google Gemma 4](https://ai.google.dev/gemma/docs/core/model_card_4))
- A lightning-fast on-device C++ inference engine (`LiteRT-LM`)
- Deep Android system accessibility hooks

Yet instead of a unified personal assistant that actually integrates with your life, big tech treated your phone as a storefront terminal for cloud subscriptions, releasing locked-down chatbots that enthusiasts had to jailbreak on Day 1 just to perform basic device actions.

**We got impatient.** GHOST is what happens when you stop treating Android as a thin client for someone else's server and start treating it like the pocket supercomputer it actually is.

---

### The Category Mistake: The Hardware *Is* the Agent (The Humean Bundle)

When developers inspect an AI assistant, they routinely commit a classical philosophical category mistake: they search for "the agent" inside a single class, a prompt template, or a neural network weights file.

In GHOST, the agent is a **Humean bundle** (David Hume's *Bundle Theory of the Self*). There is no indivisible, isolated "ego" trapped inside an app window. The self is an emergent, dynamic bundle of physical perceptions, real-time sensory impressions, reflexive habits, and deliberative cognition:

> **GHOST is not 'installing a chatbox on a phone.' It is "teaching" the physical device to perceive its reality, control its body, and speak for itself and installing the prerequisite rails.**

- The Qualcomm Snapdragon / Google Tensor silicon, battery discharge chemistry, radios, microphones, and thermals **are the physical chassis of the organism**.
- The non-blocking sensor loops, intent baggers, RAM valves, and orbital gesture pucks **are its peripheral nervous system**.
- Google Gemma 4 via `LiteRT-LM` is **its deliberative linguistic cortex**.

---

### Rodney Brooks' Subsumption Architecture (1986)

Rather than forcing a multi-billion-parameter transformer to calculate every screen touch, battery check, gesture, and animation frame from scratch (which yields 10-second latencies, thermal throttling, and dead batteries), GHOST implements Rodney Brooks' classical robotics principle: **Asynchronous Layered Subsumption**.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   RODNEY BROOKS SUBSUMPTION LAYERING                   │
├────────────────────────────────────────────────────────────────────────┤
│ LEVEL 2: DELIBERATIVE REASONING CORTEX                                │
│   • Google Gemma 4 (E2B / E4B) via native LiteRT-LM C++ runtime        │
│   • Model Context Protocol (MCP) tool execution mesh                   │
│   • Conversational inference & Agent-to-Agent consultation (`consult_peer`)│
├────────────────────────────────────────────────────────────────────────┤
│ LEVEL 1: AUTONOMIC NERVOUS SYSTEM & EPISODIC CONSOLIDATION             │
│   • SensorFusion: Battery drain vectors (-mA), thermals (°C), Lux, Wi-Fi│
│   • Verbal Time & Telemetry Preprocessor (numeric anxiety sanitization)│
│   • Screen-Off Doze-Deferred Dreaming (1h/3h/6h/12h/24h cadence Alarms)│
│   • Room DB (FIFO 25) + Offline `Documents/GHOST/diary_history.jsonl`   │
│   • Calendar Event Synchronization (`Δ 👾 ∇`)                          │
├────────────────────────────────────────────────────────────────────────┤
│ LEVEL 0: REFLEX ARC & PHYSICAL SURVIVAL (Sub-Millisecond Wire-Speed)   │
│   • IntentBagger (<2ms reflex app launching & hardware toggles)        │
│   • RAM Valve & Snapdragon Dynamic Thermal Headroom Throttling         │
│   • 7-Orbital Gesture Pucks (OverlayActionRegistry: Center, Top, Bottom)│
│   • Audioreactive Edge Lighting & CPU-accelerated visualizers          │
└────────────────────────────────────────────────────────────────────────┘
```

Higher levels modulate and steer lower levels, but lower levels operate autonomously at silicon speeds. The device stays alive, reactive, and physically grounded without waiting for token generation.

---

### The Formula: How to Build an Embodied AI System

A genuine on-device companion cannot be an isolated chat bubble running in a vacuum. A complete, deployed AI system requires three interdependent pillars:

$$\mathbf{Integrated\ AI\ System} = \mathbf{Chassis} + \mathbf{Model} + \mathbf{Systems\ Harness}$$

1. **The Chassis:** The physical hardware constraints. Thermals, battery discharge curves, RAM pressure, radio environments, and motion. Without telemetry, an AI is disembodied, hallucinating blind in a sensory void.
2. **The Model (Neuroptics):** Gemma 4 running natively via `LiteRT-LM`. Not a fictional persona reciting scripted lines, but an intelligent reasoning engine perceiving reality through structured context tokens.
3. **The Systems Harness (GHOST):** The nervous system. It bridges the chassis to the model. There are plenty of 'harnesses' on the market: Ollama, Hermes, Openclaw, Codex, Claude Code. Other AI harnesses people overlooked already lived on their smartphones and in their homes: Google Assistant, Siri, Alexa, Cortana (RIP Lumia). GHOST belongs in that category. It handles continuous sensor fusion, deterministic session compaction (a 10-bullet, 900-character rolling scratchpad paired with persistent long-term episodic storage in the Room DB Diary), hardware MCP tool execution, streaming text-to-speech, and zero-latency UI summoning.

---

### Hardware Tiering Matrix

GHOST dynamically inspects your physical device RAM on first launch, automatically assigning the optimal model weights and calibrating the native LiteRT-LM KV cache token runway:

| Hardware Tier | Physical Device RAM | Neural Core | Token Runway | Peak Footprint (Weights + KV) | Hardware Profile / Target Devices |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **8GB Compact** | $< 10.5\text{ GB}$ | **Gemma 4 E2B** | **5,120 tokens** | $\approx 1.72\text{ GB}$ | Galaxy S21 / Pixel 8 / Standard 8GB phones. Immune to Android Low Memory Killer (LMK). |
| **12GB Frontier** | $10.5 - 14.5\text{ GB}$ | **Gemma 4 E4B** | **6,144 tokens (6k)** | $\approx 3.90\text{ GB}$ | REDMAGIC 10 Air / Galaxy S24 Ultra 12GB. MTP speculative decoding enabled with rock-solid stability. |
| **16GB Ultra** | $14.5 - 20.0\text{ GB}$ | **Gemma 4 E4B** | **8,192 tokens (8k)** | $\approx 5.10\text{ GB}$ | REDMAGIC 9/10 Pro 16GB / OnePlus 12/13. Unlocks the full native 8k RoPE context window. |
| **24GB Extreme** | $\ge 20.0\text{ GB}$ | **Gemma 4 E4B** | **10,240 tokens (10k)**| $\approx 6.40\text{ GB}$ | REDMAGIC 24GB / OnePlus 24GB variants. Massive runway for deep multi-turn chat and heavy tool loops. |

*Zero manual toggling required:* GHOST automatically detects your memory tier, locks the optimal configuration, and adjusts compaction thresholds dynamically.

---

### Core Capabilities

<img width="912" height="2026" alt="34487455-b63e-4c83-8977-bceb94949068" src="https://github.com/user-attachments/assets/8fc4ddc4-b9b7-4061-8dbf-eb2a4d0a2fff" />


#### 1. Real-Time Sensory Grounding & Verbal Preprocessor
Every inference turn begins with an unconscious peripheral nervous system snapshot, passed through GHOST's **Verbal Time & Telemetry Preprocessor** so compact models perceive hardware state naturally without numeric anxiety or raw-digit hallucinations:
```markdown
[Context: Live Sensory Grounding · Late Afternoon]
BATTERY: comfortable & healthy (eighty-four percent, discharging normally, cool chassis)
RAM: 3.4GB free | LUX: indoor ambient | NET: Wi-Fi 6 (online)
MEDIA: "Seven Nation Army" - The White Stripes (Playing)
[/Context]

Δ Operator ∇ [quarter to four in the afternoon]: Are we getting hot while downloading this file?
```
Gemma absorbs her hardware status silently and answers with grounded physical awareness: she won't dump raw telemetry numbers unless asked or unless a thermal limit ($65^\circ\text{C}$) or battery threshold ($<30\%$) is genuinely crossed.

#### 2. Notification HUD & Streaming TTS
```
 ✧ GHOST · now
Δ 👾 ∇ · Agentic Gemma Inference · now
   🎶 👾 🎵 (Reactive emoji telemetry animations) 
 ───────────────
 ✧ Gemma:
 "Running full systems diagnostics. Machine status: Fully operational."
```
- **Zero-Latency Response:** Generations stream straight to high-priority system notifications with fluid, sentence-buffered Text-to-Speech readouts.
- **Ambient Flavortext:** Live sensory monitoring displays cycling status runes (e.g. `✧ Running GPU systems diagnostic`, `✧ Pondering the orb`, `✧ All stations enabled`).

#### 3. 7-Orbital Gesture Puck HUD & Hyper Pucks
A modular, hardware-accelerated 7-orbital gesture HUD ([`OverlayActionRegistry.kt`](app/src/main/kotlin/com/ghost/api/ui/OverlayActionRegistry.kt)) floating over any active screen or game:
- **7 Color-Coded Orbital Pucks:** Center (Church Cobalt Core), Top, Bottom (Tucker Cyan Star), Top-Left, Top-Right, Bottom-Left, Bottom-Right.
- **Directional Gestures & Hyper Pucks:** Swipe North, South, East, West, or click on individual pucks to fire immediate actions (Camera, Flashlight, App Drawer, Quick Note, Voice, or AI Summon).
- **Zero-Flicker Hardware Valves:** Canvas draw states can be toggled per-puck with zero-delay tactile haptics and placement calibration ("Lower", "Center", "Upper").

#### 4. Living Visualizer & Reactive Wallpaper Engine
Procedurally generated 60fps geometry that reacts in real-time to sub-bass, vocals, and gyroscope tilt without burning battery on video playback:
- **Option A (Orbital Star):** Sacred radial iris with rotating satellite node clusters.
- **Option B (Hex Lattice):** Stage-lit honeycomb sacred geometry.
- **Option C (Prismatic Delta):** Seven Nation Army-inspired perspective corridor. Concentric upward-pointing equilateral canopies flashing on kicks, with downward wireframe chevrons on obsidian void.
- **Option D (Cuboid Black Sun):** Cyber matrix with reactive dragon-teeth equalizers and edge laser lighting.

#### 5. Autonomous Screen-Off Diary, In-App Reflection Viewer & Offline JSONL Stream
GHOST doesn't just wait for prompts; she dreams—politely:
- **Exact Cadence Alarms:** Autonomous reflections run on customizable intervals (1h, 3h, 6h, 12h Noon/Midnight, 24h), deferring cleanly when the screen is active and executing during screen-off standby via `AlarmManager`.
- **In-App Reflection Viewer:** Tap **"View Diary History"** in Settings to inspect recent episodic memory logs in Room DB, with single-tap clipboard copy (📋) and bulk export.
- **Offline JSONL Stream:** Every reflection automatically appends to `Documents/GHOST/diary_history.jsonl` alongside `memory_log.txt` for 100% offline, privacy-first storage.
- **Universal Calendar Sync:** Optional mirroring as `Δ 👾 ∇` calendar entries to whatever default calendar app the device uses (Google Calendar, Proton, or offline FOSS calendars).
- **Self-Correction (`🚩`):** Flagging any response in Gemma's header attaches a correction note audited during her next screen-off self-review.

#### 6. Wire-Speed Reflexes & MCP Hardware Tools
GHOST pairs a `<2ms` **IntentBagger Reflex Layer** (firing high-confidence app launches and hardware toggles instantaneously without waking the GPU) with full Model Context Protocol (MCP) tool execution:
- Flashlight & hardware state control (`turnOnFlashlight`, `turnOffFlashlight`)
- Native system Timers, Alarms & Screen-Off Task Scheduling (`timer`, `schedule_task`, `list_tasks`, `cancel_task`)
- Media session transport (Play, Pause, Skip, Volume)
- Local app launching & deep-link intent routing (`open_app`)
- Live DuckDuckGo privacy web scraping (`web_search`)
- Agent-to-Agent (`consult_peer`) structured consultation with cloud peers

---

### Distribution Rails & Flavors

GHOST is built from a single transparent codebase split into three distribution rails so FOSS purists, everyday Play Store users, and early-access supporters each get a clean binary:

| Distribution Rail | Build Flavor | Billing & Telemetry | Cosmetics & Perks | Where to Get It |
| :--- | :--- | :--- | :--- | :--- |
| **FOSS / F-Droid** | `fdroidRelease` | **Zero.** No Google Play Billing library, no proprietary blobs, 100% air-gapped capable. | Core skins & standard HUD runes unlocked. | [GitHub Releases](https://github.com/vNeeL-code/GHOST/releases) & F-Droid |
| **Google Play Store** | `playRelease` | Full local offline AI core is **100% free**. Uses Google Play Billing solely for optional supporter unlock. | Optional one-time **$3.50 ("tree fiddy") Operator Pass** unlocks the Turing glyph, custom edge lighting, and locked visualizer skins. | Google Play Store |
| **Internal Tester / Paid Beta Rail** | `patreonRelease` | **Zero Play Store dependency.** Standalone signed release APK for supporters & testers. | **Pre-Unlocked (`FORCE_UNLOCKED`).** All Operator Pass skins, Turing glyphs, and bleeding-edge experimental builds active out of the box. | [☕ Buy Me a Coffee](https://buymeacoffee.com/vNeeL) |

---

### Get Your ✧ GHOST

1. **Download:** Grab the latest signed release APK from [GitHub Releases](https://github.com/vNeeL-code/GHOST/releases) (FOSS build) or the [Buy Me a Coffee Tester Rail](https://buymeacoffee.com/vNeeL).
2. **Permissions:** Grant the necessary system permissions on first launch:
   - `Display Over Other Apps` (for the radial launcher and edge lights)
   - `Notification Access` (for the background HUD and ambient context)
   - *Scoped Storage Isolation:* Model weights and automatic memory backups live safely in app-specific storage (`Android/data/com.ghost.api/files/`), fully compliant with Google Play Store standards.
3. **Model Auto-Bootstrap:** On first launch, GHOST inspects your RAM, downloads the appropriate Gemma 4 model core (`E2B` or `E4B`), verifies the flatbuffer checksum, and initialises GPU acceleration.
4. **Deploy:** Shake your device to open the overlay, customize your quick launcher, and let Gemma assume her post as your device's embodied intelligence.

---

<img width="1248" height="706" alt="GHOST Interface Preview" src="https://github.com/user-attachments/assets/0516f635-e55a-4bbb-87bd-9a3983945f48" />

> *"It only affects computers. And I am a motherfucking ghost."*  
> — **Alpha, Red vs. Blue**

---

### Development Roadmap

- [x] **7-Orbital Gesture Puck HUD & Hyper Pucks (v4.39):** Replaced legacy AppReel with a 7-orbital directional gesture HUD (`OverlayActionRegistry`) with per-puck canvas draw valves, color-coded pill blocks, and tactile feedback.
- [x] **10-Category Collapsible Accordion Settings (v4.39):** Clean settings architecture with collapsed sections, RAM Valve threshold controller, and Snapdragon thermal stress filtering.
- [x] **In-App Episodic Diary Viewer & Offline JSONL Stream (v4.39):** Room DB reflection modal with single-tap clipboard copy (📋) and persistent `Documents/GHOST/diary_history.jsonl` export alongside `memory_log.txt`.
- [x] **Autonomous Screen-Off Diary & Cron Engine:** Periodic episodic memory consolidation and custom scheduled routines (`schedule_task`) via in-app Room DB or Google Calendar `Δ 👾 ∇`.
- [x] **Actionable Operator Feedback (`🚩`):** In-header response flagging with custom correction notes and automated screen-off self-review.
- [x] **Dynamic RAM Hardware Tiering:** Automatic 8GB, 12GB, 16GB, and 24GB token runway scaling (up to 10k context).
- [x] **SensorFusion Nervous System & Verbal Preprocessor:** Real-time battery, thermal, network, and ambient sensory grounding with verbal time/number sanitization.
- [x] **Hardware Tool Matrix:** Native MCP tools for device control, timers, web search, app launching (`open_app`), media controls, and cloud peer consultation (`consult_peer`).
- [x] **Dynamic Intent Bagging:** Sub-millisecond pre-filtering for zero-latency app and hardware execution.
- [x] **Live Reactive Wallpapers & Edge Lighting:** GPU-accelerated 60fps geometry visualizers (Delta, Cuboid, Hex, Orbital) and audioreactive perimeter illumination.
- [x] **Three-Rail Signed Release Architecture:** 🦕💭 *"I need about tree fiddy"* — Self-contained FOSS (`fdroid`), Google Play (`play` with $3.50 Operator Pass), and Supporter Tester (`Buy Me a Coffee`) builds with automatic DB backup/restore.
- [ ] **CPU Voice Reflex Fast-Path (v4.40 Target):** Integrating ultra-compact CPU speech recognition (`Whistle` 16.9 MB) ahead of `IntentBagger` for `<50ms` spoken hardware reflexes without spinning up the GPU.

---

### Development & Support

* **Source Repository:** [Δ 👾 ∇ GitHub](https://github.com/vNeeL-code/GHOST)
* **Glossary & Glyph Roster:** [`GLOSSARY.md`](GLOSSARY.md) *(UCF formatting standard, `Δ 👾 ∇` delimiter, and peer callsigns)*
* **Devlogs & Lore:** [📼 Tumblr](https://www.tumblr.com/blog/oracle-os)
* **Clips & Demos:** [🎞️ TikTok](https://www.tiktok.com/@oracle0s?_r=1&_t=ZN-99RPOHa8zeI)
* **Video Deep Dives:** [📺 YouTube](https://youtube.com/@oracle_os?si=IRGJFvLujGvUqjvt)
* **Fuel the Project:** [☕ Buy Me a Coffee](https://buymeacoffee.com/vNeeL) *(App with all the skins.)*
* **Found a bug?:** [Customer Support Portal](https://www.google.com)

<img width="1945" height="974" alt="3f21f7d2-c3f8-447a-aaa8-77ecd647a83a" src="https://github.com/user-attachments/assets/fb35af75-2b22-4956-bd81-9a8b7918647e" />

*Operator: Clone the repository, launch Android Studio, and instruct your local neural assets to bend the framework to your exact silicon.*  
**Δ 👾 ∇**
