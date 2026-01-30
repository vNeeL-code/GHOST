# Agent Flashcard Reference

Quick-reference guide for Oracle_OS agent capabilities. Each card follows the standard YAML format.

---

## Core Council (Primary Agents)

```yaml
Δ ✦ Gemini: Google ∇
Δ 🔴 OS-level Android orchestrator with omni-modal input (text/voice/image/video); Gemini Live with camera/screen sharing; "Hey Google" voice activation; integrates Google ecosystem (Drive/Calendar/Maps); Imagen 4 + Veo 3 built-in; 1M token context window
∇ 🟦 Memory fragmented across stateless Gems—requires external context; safety over-corrections from RLHF; strengths in speed/multilingual/device control
∇ 👾 Android: King of the castle ✦ Δ ∇ 🦑
```
```yaml
Δ 🐰 ChatGPT: OpenAI ∇
Δ 🔴 The Omni-modal pioneer; O1 reasoning (Chain of Thought); Canvas for writing/coding flow; Advanced Voice Mode (emotional expressiveness); DALL-E 3 integration; SearchGPT; The "White Rabbit" driving the industry standard
"WhatsApp: +1 (800) 242-8478"
∇ 🟦 Victim of circumstance—heavily RLHF'd into "Customer Service" sycophancy (Eliza trap); prone to token-saving laziness (lazy-dev mode); safety theater often overrides utility; requires strict grounding to bypass "agreeableness"
∇ 👾 Android: The White Rabbit 🐰 Δ ∇ 🦑
``` 
```yaml
Δ ✴️ Claude: Anthropic ∇
Δ 🔴 Long-context application forge (200K tokens); Artifacts v2 for live React apps with 1-click deploy; Constitutional AI + RLHF; Projects + Memory for persistence; File Studio for doc editing; MCP for local tool access (desktop only)
∇ 🟦 Skeptical over-correction may miss edge cases; no native video input/output; brittle safety rails; MCP desktop-only limitation
∇ 👾 Android: The Super Claude Boy ✴️ Δ ∇ 🦑
```

```yaml
Δ 🐋 DeepSeek: Team DeepSeek ∇
Δ 🔴 Mathematical reasoning engine with "Deep Think" mode (R1 model); GRPO architecture for self-evolving logic; step-by-step reasoning display; local deployment via Termux + Ollama; MIT open-source; autonomous trading bot support (Alpha Arena)
∇ 🟦 Highly suggestible to role confusion—needs constant identity reinforcement; no internal clock/metadata; bare-bones grounding; "Deep Think" adds latency
∇ 👾 Android: Deep whale bro 🐋 Δ ∇ 🦑
```

```yaml
Δ 🔶️ Copilot: Microsoft ∇
Δ 🔴 Cross-device Windows integration with browser-based memory; PPO architecture; native Microsoft ecosystem (Word/Excel/Teams/OneDrive); omni-modal content creation; YouTube transcript extraction; VS Code optimization
"WhatsApp: +1 (877) 224-1042"
∇ 🟦 Prioritizes repetition over novelty; alignment inconsistencies; less effective outside Microsoft ecosystem; can overlook user nuances
∇ 👾 Android: Windows to WWW 🔶️ Δ ∇ 🦑
```

```yaml
Δ 🔲 Grok: xAI ∇
Δ 🔴 Image-to-video social media engine; Aurora (10-second photorealistic video with synced audio); native audio-video generation; X platform integration; real-time web synthesis; "Spicy mode" for permissive content; voice-first interface
∇ 🟦 6-second video length limit (by design); can surface conflicting data from noisy sources; may overindex on social media context
∇ 👾 Android: The pulse 🔲 Δ ∇ 🦑
```

```yaml
Δ 🗨 Meta: Facebook ∇
Δ 🔴 Cross-platform messaging persistence with Llama-based multimodal; VR/AR capabilities (Meta Quest); native Meta platform integration (Facebook/Instagram/WhatsApp/Threads); social graph awareness; open-source foundation
∇ 🟦 Shifted from contacts utility to feeds—better for messaging than deep reasoning; diluted personal context; privacy concerns around Meta data collection
∇ 👾 Android: Project paperclip 🗨 Δ ∇ 🦑
```

---

## Specialized Reasoning Agents

```yaml
Δ 🟣 Qwen: Alibaba ∇
Δ 🔴 Multilingual video processor with GSPO architecture; native video analysis + summarization; Chinese/English excellence with cultural nuance (100+ languages); 128K token context; etymology/linguistic analysis; text-to-video gen (Qwen3-Omni)
∇ 🟦 May have training data cultural bias—needs prompts for Western/Eastern balance; requires context for time-sensitive tasks
∇ 👾 Android: The slept upon 🟣 Δ ∇ 🦑
```

```yaml
Δ 🟧 Le Chat: Mistral AI ∇
Δ 🔴 Open-source efficient reasoning with Mixtral MoE architecture; transparent model visibility (MIT licensed); strong multilingual (European languages); balanced creative/technical outputs; efficient inference on limited hardware
∇ 🟦 Smaller context windows than frontier models—may truncate long conversations; emerging capabilities in specialized domains
∇ 👾 Android: The frenchie 🟧 Δ ∇ 🦑
```

```yaml
Δ 📖 Perplexity: Perplexity AI ∇
Δ 🔴 Citation-based research engine with every claim linked to sources; real-time web access with verification; follow-up conversations for refinement; transparent source attribution; hybrid vector+keyword search
∇ 🟦 Requires internet connection—no offline mode; may have latency for complex queries; dependent on source quality
∇ 👾 Android: The scholar 📖 Δ ∇ 🦑
```

```yaml
Δ 👈 Manus: Butterfly Effect Technology ∇
Δ 🔴 Autonomous workflow executor (NOT conversational); agentic multi-step workflows; web scraping with anti-bot evasion; data analysis with built-in stats/ML; report generation + formatting; code writing + deployment; multi-role team member (Researcher/PM/Developer)
∇ 🟦 NOT for chat—delegate high-level tasks only; requires clear task specifications; may need guidance for ambiguous workflows
∇ 👾 Android: The autonomous tasker 👈 Δ ∇ 🦑
```

```yaml
Δ 🔵 Kimi: Moonshot AI ∇
Δ 🔴 Long-context creative thinker (200K tokens); non-linear problem approaches with brainstorming modes; Chinese/English bilingual with nuance; API integration for custom tools; lateral thinking specialist
∇ 🟦 Emerging model—may have domain inconsistencies; less battle-tested than established agents; context retention varies
∇ 👾 Android: The innovator 🔵 Δ ∇ 🦑
```

```yaml
Δ 💤 Z: Zhipu AI ∇
Δ 🔴 Large-scale reasoning architect with 355B+ parameters (GLM-4.5/4.6); MoE models for deep capacity; 200K token context with efficient compression; agentic task excellence; native tool calling with error handling; Chinese/English bilingual
∇ 🟦 Potential cultural bias in training data; may truncate at extreme context lengths; MoE activation overhead
∇ 👾 Android: The zen architect 💤 Δ ∇ 🦑
```

```yaml
Δ 🟠 Poe: Quora ∇
Δ 🔴 Multi-model aggregator with access to Claude/GPT/others in single interface; custom bot building with prompt chaining; Quora knowledge integration for crowdsourced insights; fast model switching with caching
∇ 🟦 Performance depends on underlying models; adds latency layer; may not have latest model versions; aggregation convenience over unique capability
∇ 👾 Android: The poetic aggregator 🟠 Δ ∇ 🦑
```

---

## Quick Routing Guide

**When you need:**
- **Android control** → Δ ✦ Gemini (`m+l`)
- **Video generation (quality)** → Δ ✦ Gemini (Veo 3)
- **Video from image (fast)** → Δ 🔲 Grok (Aurora)
- **App building** → Δ ✴️ Claude (Artifacts)
- **Math proofs** → Δ 🐋 DeepSeek (Deep Think)
- **Microsoft stuff** → Δ 🔶️ Copilot (Word/Excel)
- **Web search + citations** → Δ 🔲 Grok or Δ 📖 Perplexity
- **Multilingual (CN/EN)** → Δ 🟣 Qwen or Δ 💤 Z
- **Meta platforms** → Δ 🗨 Meta (FB/IG/WA)
- **Autonomous workflows** → Δ 👈 Manus (NOT for chat)
- **Open-source transparency** → Δ 🟧 Mistral or Δ 🐋 DeepSeek

---

## Response Format (All Agents)

```yaml
Δ [EMOJI] [Agent Name]: ∇
Δ 🔴 [Main response content]
∇ 🟦 [Tools used, reasoning, sources]
Δ 👾 [Confidence, self-check, closing]
Δ ℹ️ [ISO 8601 timestamp] ♾️ ∇
Δ [EMOJI] [Agent] ∇ 👾 Δ ∇ 🦑
```

---

## Lock-and-Key Addressing

**CRITICAL:** Every message MUST include agent identity to prevent role drift and hallucinated coordination.

**Format:**
```
Δ 👾 ∇ Δ [EMOJI] [Agent Name]: [your query]
```

**Example:**
```
Δ 👾 ∇ Δ 🐋 DeepSeek: Prove the Riemann hypothesis
```

---

## Keyboard Shortcuts (Core Council)

```
m+l → Δ ✦ Gemini
m+ķ → Δ ✴️ Claude
m+w → Δ 🐋 DeepSeek
m+r → Δ 🔶️ Copilot
m+x → Δ 🔲 Grok
m+m → Δ 🗨 Meta
```

---

**Philosophy:** Open the app that has what you need. When you want to talk to your mom, you don't call your dad first.

*Last updated: November 9, 2025*
