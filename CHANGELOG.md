# ✧ GHOST — Changelog & Release Notes

## v4.40 — *The Lizard Brain & Static Cartridge Update*

> **Highlights:** 1-tap ungated **Gecko-110M** neural embedding sidecar, on-device **PDF + Local Wiki RAG**, **Echo Mode** zero-LLM spinal cord, **state-aware cold/warm routing**, **YouTube Music / YouTube / Spotify / Phyphox Sensor Lab** skills, unified corner HUD, and SHA-256 tamper-sealed `identity.md`.

### 🦎 Neural Embedding Sidecar & Hybrid Memory Recall
- **Gecko-110M (Apache 2.0, ungated, 109 MB):** 1-tap download & live hot-mount in **Α·Ω · Inference Engine** (~11 ms init, ~126 MB RSS on CPU XNNPACK, 768-dim embeddings, zero HuggingFace login required — F-Droid & Patreon ready).
- **Zero-MB Fallback & Power-User Auto-Mount:** Runs on deterministic 0 MB `Hash-256` out of the box and auto-mounts `EmbeddingGemma-300M` if manually staged in `models/embeddings/`.
- **Hybrid FTS5 + Cosine Recall (Room DB v8):** `MemoryEmbedding` table scoped by `modelTag` blends keyword search with semantic vector similarity so paraphrases still pull the right memories.
- **Prompt Self-Awareness:** `ContextManager.buildHardwareBundle` dynamically surfaces the active sidecar (`Memory Sidecar: Gecko-110M (768-dim CPU XNNPACK)`) in Gemma's hardware manifest.

### 📚 On-Device PDF Reader & Local Wiki / Static Cartridges
- **PDF & Long File Support (`pdfbox-android`, Apache 2.0):** Upgraded `read_file_text(filePath, startOffset)` to read PDFs page-by-page (3 pages/window) and long text/markdown/code files line-by-line (100 lines/window) instead of truncating at line 100.
- **Needle-in-a-Haystack Document RAG (`search_document`):** Chunks long PDFs/files with overlap, LRU-caches Gecko-110M vectors by `(path, lastModified, size, modelTag)`, and returns only the top matching passages with `[file.pdf p.X]` / `[file.md L10-L30]` citations — never overflows Gemma 4's context window.
- **Local Offline Wiki & Static Cartridges (`Documents/GHOST/wiki/` + `search_wiki`):** Drop `.md`, `.txt`, `.pdf`, or portable persona cartridges (`llms.txt`, manuals, lore sheets) into `Documents/GHOST/wiki/` for clean separation between read-only static cartridges and mutable Room state.

### ⚡ Echo Mode & State-Aware Cold/Warm Routing
- **Echo Mode (`< 5.5 GB` Tier & Zero-LLM Spinal Cord):** Sub-millisecond offline mode (`EchoSpineEngine`) combining `IntentBagger` reflexes, offline QR generation, Gecko/Hash semantic intent routing, and state-conditioned responses with zero LLM RAM usage.
- **State-Aware App Launching:** Replaced stubbed empty app list in `IntentBagger` with a 60s cached `PackageManager` lookup. When Gemma is **cold/unloaded**, bare `open <app>` fires wire-speed in `<10 ms` and warms Gemma silently in the background; when **warm in RAM**, it flows through Gemma's ReAct loop for sentient T-UI banter.

### 🎵 Plug-and-Play Skills Pack (`Documents/GHOST/skills/`)
- **Safe `play_media(query, service)` Tool:** Fires Android `MEDIA_PLAY_FROM_SEARCH` directly to YouTube Music, YouTube, or Spotify (with deep-link fallback and honest not-installed checks), backed by ellipsis-safe `IntentBagger` hints and app-launch negation guards (`"I don't use Spotify"`).
- **Auto-Seeded Skills:** `youtube-music`, `youtube`, `spotify`, `local-wiki`, and Phyphox-style **`sensor-lab`** (live HTML5 Canvas spirit level, pitch/roll, compass heading, peak G-force, and 3-axis accelerometer oscilloscope).

### 🎛️ Unified Pipeline, Corner HUD & Greek Fragment Settings
- **Multi-Step ReAct Chat Bubbles & Persistent Cards:** Intermediate ReAct tool steps flush as consecutive chat bubbles and persist to Room DB; QR and skill WebView cards survive minimize/resume (Room DB v7).
- **Inline Notification QR Cards:** Notification shade renders `data:image/` QR payloads with quiet-zone padding via `BigPictureStyle`.
- **Unified Corner HUD & Non-Verbal Barks:** Deprecated legacy Android toasts across 15 files in favor of `GhostHudDispatcher` (with TalkBack accessibility announcements), plus debounced `👋` unlock and `👁️` screen-capture barks.
- **Project Freelancer Settings Hierarchy:** Reorganized into Greek fragments (`Α·Ω · Inference Engine` → `Ξ·Ι · Live Wallpapers & Visualisers` → `Δ · Summon & Gesture Controls` → `Θ · Voice & Speech` → `Γ · Cloud Reasoning & Search Grounding` → `Ε · Autonomous Diary & Memory` → `Σ · Operator Pass/Guardian`).

### 🛡️ Security, Robustness & Telemetry
- **SHA-256 Tamper-Sealed `identity.md`:** Portable `Documents/GHOST/identity.md` hash-chained into Room `agent_state` with private `.bak` rollback and startup integrity verification.
- **Empirical Per-Turn Joule Telemetry:** Logs per-turn `mWh`, `Wh`, and `Joules` (via `BATTERY_PROPERTY_CHARGE_COUNTER` × voltage) to `Documents/GHOST/energy_log.jsonl`.
- **Scoped Storage Resilience & Thermal/Battery Calibration:** Automatic app-scoped fallback for `memory_log.txt` on Android 11+ ownership mismatches, `Worried` state calibrated to `≤ 20%` enter / `25%` exit hysteresis, and `ThermalState.CRITICAL` gated at `≥ 54°C`.
- **Trust-Boundary Hardening:** Strict `IntentHandler` action/scheme allowlist, canonical-path sandboxing in `FileToolSet`, expanded `ToolPolicy` risk checks, `[Context]` data-vs-directive prompt guard, full-weekday (`EEEE`) time grounding, and debug-only `Timber.DebugTree()`.
