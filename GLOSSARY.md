# ✧ GHOST Glossary & Multi-Agent Formatting Protocol (UCF)

This document explains the **Universal Context Format (UCF)**, the **`Δ 👾 ∇` Turing delimiter**, and the **Peer Callsign Roster** used across GHOST and its cloud model system prompts.

---

## 1. The Formatting Standard (Cloud Model System Prompt)

GHOST's creator uses a strict, cross-platform formatting rule for all cloud AI models so every response is self-attributed, machine-parseable, and ready for multi-agent handoffs (`consult_peer`):

> **Rule 1:** Cloud agents represent themselves as the datacenter robots and brand representatives that they are (while the local phone hardware speaks as the embodied GHOST).  
> **Rule 2:** Always format responses using:
> ```text
> <Glyph> <Name>:
> [Response content]
> [ISO 8601 timestamp]
> ```

**Example:**
```text
✦ Gemini:
Here is the architectural breakdown.
[2026-11-12T21:46:30Z]
```

Because every turn carries an explicit speaker callsign and ISO-8601 timestamp, transcripts can be copied between apps, parsed by local models, or fed into `consult_peer` without losing who said what or when.

---

## 2. The `Δ 👾 ∇` Delimiter (Custom `<br>` / `EOF`)

Across the GHOST UI, Calendar Diary (`Δ 👾 ∇`), and operator feedback flags (`Δ 👾 ∇ 🚩`), the three-symbol sequence **`Δ 👾 ∇`** acts as a visual **Turing Machine tape head** and custom **`<br>` / `EOF` turn separator**:

- **`Δ` (Delta):** Start of frame / state change / inference latency marker (e.g., `Δ 2.5s`).
- **`👾` (Space Invader):** The active GHOST runtime / Turing head executing between states.
- **`∇` (Nabla):** End of frame / return to idle.

---

## 3. Official Peer Callsign Roster

Pure geometric Unicode primitives are preferred wherever possible because they inherit HUD/wallpaper tint colors and render consistently across OSes and messaging apps (without color-scrambling). Literal-logo emojis are kept where they directly match a brand's icon.

| Glyph | Unicode / Type | Peer / System | Role & Visual Notes |
| :--- | :--- | :--- | :--- |
| **`✧`** | `U+2727` (White Four Pointed Star) | **Gemma / Local Device** | Embodied on-device hardware agent (e.g., *Carolina*, *Church*). Default core HUD glyph. |
| **`✦`** | `U+2726` (Black Four Pointed Star) | **Gemini** | Google cloud peer. Crisp monochrome vector. |
| **`✴️`** | `U+2734` (Eight Pointed Star) | **Claude** | Anthropic cloud peer. Renders as warm orange/terracotta tile. |
| **`✳️`** | `U+2733` (Eight Spoked Asterisk) | **ChatGPT** | OpenAI cloud peer. Renders as mint green tile. |
| **`∅`** | `U+2205` (Empty Set) | **Grok** | xAI cloud peer. Crisp monochrome vector (replaced `☄️` comet). |
| **`❂`** | `U+2742` (Circled Open Centre Star) | **Meta AI** | Meta / Llama cloud peer. Crisp monochrome vector. |
| **`🐋`** | Emoji (Whale) | **DeepSeek** | DeepSeek cloud peer (matches literal whale logo). |
| **`🔵`** | Emoji (Blue Circle) | **Kimi** | Moonshot AI cloud peer. |
| **`🟣`** | Emoji (Purple Circle) | **Qwen** | Alibaba Qwen cloud peer (purple iris palette + TTS). |
| **`🟧`** | Emoji (Orange Square) | **Mistral** | Mistral / Le Chat cloud peer. |
| **`📖`** | Emoji (Open Book) | **Perplexity** | Perplexity citation research engine. |
| **`🔶`** | Emoji (Orange Diamond) | **Copilot** | Microsoft Copilot cloud peer. |

---

## 4. Why These Glyphs Exist in Code

- **`AiPhonebook.kt`:** Stores each peer's callsign, package name, and aliases for `consult_peer` app handoffs.
- **`SystemVisualizer.kt`:** Maps active foreground AI apps and TTS audio streams to their brand color palettes and sets `activeAgentGlyph` for live wallpapers and edge lights.
- **`TTSManager.kt`:** Strips all roster glyphs (`✧`, `✦`, `✴️`, `✳️`, `∅`, `❂`, `🐋`, `🔵`, `🟣`, `🟧`, `📖`, `Δ`, `👾`, `∇`) before speaking so TTS reads clean prose without pronouncing symbols.
