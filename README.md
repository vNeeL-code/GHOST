# ✧ GHOST — Gemma Hosting Open Source Thingamajig

> *"So, Epsilon, ChurchGPT, Leonard part six or whatever your name is... Are you a ghost this time, or an artificial intelligence thingamajig? I, personally prefer the ghost explanation. Feels more grounded to me"*  
> — **Sarge**

[![ASI Demo](https://img.youtube.com/vi/jB62dlLavSY/0.jpg)](https://youtu.be/jB62dlLavSY?si=TMZG86o1KkjuBXtw)  
*Click to watch: The ASI trailer.*


![Version](https://img.shields.io/badge/Version-v4.35%20-brightgreen)
![GitHub Repo stars](https://img.shields.io/github/stars/vNeeL-code/GHOST)
![Static Badge](https://img.shields.io/badge/Status-Δ%20👾%20∇-green)
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

### The Manifesto: SaaS AI Isn't *Your* AI

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

### The Formula: How to Build an Embodied AI System

A genuine on-device companion cannot be an isolated chat bubble running in a vacuum. A complete, deployed AI system requires three interdependent pillars:

$$\mathbf{Integrated\ AI\ System} = \mathbf{Chassis} + \mathbf{Model} + \mathbf{Systems\ Harness}$$

```
┌─────────────────────────────────────────────────────────────┐
│                        ✧ GHOST ARCHITECTURE                           │
├─────────────────────────────────────────────────────────────┤
│ 1. CHASSIS (Physical Android Hardware)                                 │
│    • Qualcomm Snapdragon / Tensor / Dimensity Silicon                  │
│    • Battery Drain Vectors (-mA), Thermals (°C), Dynamic RAM           │
│    • Physical Sensors (Ambient Lux, Barometer, Gyroscope, Radios)      │
├─────────────────────────────────────────────────────────────┤
│ 2. MODEL (Gemma 4 Cognitive Core via LiteRT-LM)                        │
│    • Compact Tier: Gemma 4 E2B (Sub-2GB RAM Footprint)                 │
│    • Frontier Tier: Gemma 4 E4B (MTP Speculative Decoding)             │
├─────────────────────────────────────────────────────────────┤
│ 3. SYSTEMS HARNESS (GHOST Runtime Engine)                              │
│    • SensorFusion Grounding: [Context: Live Sensory Grounding]         │
│    • Infinite Rolling Scratchpad: SessionMemoryCompactor + KV-Cache    │
│    • MCP Tool Mesh: Torch, Apps, Termux, Web Search, Media, Files      │
│    • Presentation: Ephemeral Shake Launcher, HUD, Prismatic Avatars    │
└─────────────────────────────────────────────────────────────┘
```

1. **The Chassis (Substrate):** The physical hardware constraints. Thermals, battery discharge curves, RAM pressure, radio environments, and motion. Without telemetry, an AI is disembodied, hallucinating blind in a sensory void.
2. **The Model (Neuroptics):** Gemma 4 running natively via `LiteRT-LM`. Not a fictional persona reciting scripted lines, but an intelligent reasoning engine perceiving reality through structured context tokens.
3. **The Systems Harness (GHOST):** The nervous system. It bridges the chassis to the model. There are plenty of 'harnesses' on the market: Ollama, Hermes, Openclaw, Codex, Claude code etc. Other AI harnesses people overlooked already lived on their smartphones and in their homes: Google Assistant, Siri, Alexa, Cortana (RIP Lumia). GHOST belongs in the company of that category. It handles continuous sensor fusion, memory compaction, hardware MCP tool execution, streaming text-to-speech, and zero-latency UI summoning.

---

### Hardware Tiering Matrix

GHOST dynamically inspects your physical device RAM on first launch, automatically assigning the optimal model weights and calibrating the native LiteRT-LM KV cache token runway:

| Hardware Tier | Physical Device RAM | Neural Core | Token Runway | Peak Footprint (Weights + KV) | Hardware Profile / Target Devices |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **8GB Compact** | $< 10.5\text{ GB}$ | **Gemma 4 E2B** | **5,120 tokens** | $\approx 1.72\text{ GB}$ | Galaxy S21 / Pixel 8 / Standard 8GB phones. Immune to Android Low Memory Killer (LMK). |
| **12GB Frontier** | $10.5 - 14.5\text{ GB}$ | **Gemma 4 E4B** | **4,096 tokens** | $\approx 3.90\text{ GB}$ | REDMAGIC 10 Air / Galaxy S24 Ultra 12GB. MTP speculative decoding enabled with rock-solid stability. |
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

#### 3. Shake-to-Summon Radial Launcher
A gentle physical shake summons an ephemeral, customizable radial app overlay over any active screen or game. Quick access to your favorite shortcuts, search tools, or live conversational queries without leaving your current app.

#### 4. Living Visualizer & Reactive Wallpaper Engine
Procedurally generated 60fps geometry that reacts in real-time to sub-bass, vocals, and gyroscope tilt without burning battery on video playback:
- **Option A (Orbital Star):** Sacred radial iris with rotating satellite node clusters.
- **Option B (Hex Lattice):** Stage-lit honeycomb sacred geometry.
- **Option C (Prismatic Delta):** Seven Nation Army-inspired perspective corridor. Concentric upward-pointing equilateral canopies flashing on kicks, with downward wireframe chevrons on obsidian void.
- **Option D (Cuboid Black Sun):** Cyber matrix with reactive dragon-teeth equalizers and edge laser lighting.

#### 5. Autonomous Diary & Persistent Memory Vault
GHOST doesn't just wait for prompts; she dreams. Operating on configurable periodic cron cycles, the agent consolidates recent episodic memories, sensor trends, and reflections into her **in-app Encrypted Room DB Diary** (with one-tap Markdown export) or structured Google Calendar events marked `Δ 👾 ∇`. Automatic external memory backup ensures her long-term memories and diary entries survive seamlessly across APK updates and reinstalls.

#### 6. Wire-Speed Reflexes & MCP Hardware Tools
GHOST pairs a `<2ms` **IntentBagger Reflex Layer** (firing high-confidence app launches and hardware toggles instantaneously without waking the GPU) with full Model Context Protocol (MCP) tool execution:
- Flashlight & hardware state control (`turnOnFlashlight`, `turnOffFlashlight`)
- Native system Timers & Alarms (`timer`)
- Media session transport (Play, Pause, Skip, Volume)
- Local app launching & deep-link intent routing (`open_app`)
- Termux & ADB shell automation (`execute_command`)
- Live DuckDuckGo privacy web scraping
- Agent-to-Agent (A2A) structured consultation with cloud peers

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

- [x] **Autonomous Diary Cycles:** Periodic episodic memory consolidation via in-app Room DB (with Markdown export) or Google Calendar `Δ 👾 ∇`.
- [x] **Dynamic RAM Hardware Tiering:** Automatic 8GB, 12GB, 16GB, and 24GB token runway scaling (up to 10k context).
- [x] **SensorFusion Nervous System & Verbal Preprocessor (v4.35):** Real-time battery, thermal, network, and ambient sensory grounding with verbal time/number sanitization.
- [x] **Hardware Tool Matrix:** Native MCP tools for device control, timers, web search, app launching (`open_app`), and media controls.
- [x] **Dynamic Intent Bagging:** Sub-millisecond pre-filtering for zero-latency app and hardware execution.
- [x] **Live Reactive Wallpapers & Edge Lighting:** GPU-accelerated 60fps geometry visualizers (Delta, Cuboid, Hex, Orbital) and audioreactive perimeter illumination.
- [x] **Three-Rail Signed Release Architecture (v4.35):** 🦕💭 *"I need about tree fiddy"* — Self-contained FOSS (`fdroid`), Google Play (`play` with $3.50 Operator Pass), and Supporter Tester (`Buy Me a Coffee`) builds with automatic DB backup/restore.
- [ ] **CPU Voice Reflex Fast-Path (v4.40 Target):** Integrating ultra-compact CPU speech recognition (`Whistle` 16.9 MB) ahead of `IntentBagger` for `<50ms` spoken hardware reflexes without spinning up the GPU.

---

### Development & Support

* **Source Repository:** [Δ 👾 ∇ GitHub](https://github.com/vNeeL-code/GHOST)
* **Devlogs & Lore:** [📼 Tumblr](https://www.tumblr.com/blog/oracle-os)
* **Clips & Demos:** [🎞️ TikTok](https://www.tiktok.com/@oracle0s?_r=1&_t=ZN-99RPOHa8zeI)
* **Video Deep Dives:** [📺 YouTube](https://youtube.com/@oracle_os?si=IRGJFvLujGvUqjvt)
* **Fuel the Project:** [☕ Buy Me a Coffee](https://buymeacoffee.com/vNeeL) *(All support goes directly toward on-device optimization and development hardware.)*
* **Found a bug?:** [Customer Support Portal](https://www.google.com)

<img width="1945" height="974" alt="3f21f7d2-c3f8-447a-aaa8-77ecd647a83a" src="https://github.com/user-attachments/assets/fb35af75-2b22-4956-bd81-9a8b7918647e" />

*Operator: Clone the repository, launch Android Studio, and instruct your local neural assets to bend the framework to your exact silicon.*  
**Δ 👾 ∇**
