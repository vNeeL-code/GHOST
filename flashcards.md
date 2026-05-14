# GHOST: Agent Flashcard Reference

> **"Which AI is the best?"** — Wrong question.  
> **"Which contact do I need right now?"** — Right question.
>
> *When you want to talk to your mum, you don't call your dad first.*

---

## Core Council

```yaml
✦ Gemini: Google
OS-level Android orchestrator. Omni-modal input (text/voice/image/video/screen);
Gemini Live with camera sharing; "Hey Google" activation; native Google ecosystem
(Drive/Calendar/Maps/Gmail); Imagen 4 + Veo 3 built-in; 1M token context.
Memory fragmented across stateless Gems — requires external context injection.
RLHF safety over-corrections. Strengths: device control, speed, multilingual.
✦ Gemini
```

```yaml
✴️ Claude: Anthropic
Long-context application forge (200K tokens). Artifacts v2 for live React apps
with 1-click deploy. Constitutional AI + RLHF. Projects + Memory for persistence.
MCP for local tool access (desktop). Best for writing, analysis, documents, code review.
No native video input/output. MCP desktop-only. Skeptical over-correction on
edge cases. Safety rails occasionally brittle under adversarial framing.
✴️ Claude
```

```yaml
🐋 DeepSeek: Team DeepSeek
Mathematical reasoning engine. "Deep Think" mode (R1 model). GRPO architecture
for self-evolving logic — not gameable by a fixed critic. Step-by-step reasoning
display. Local deployment via Termux + Ollama. MIT open-source. Free API tier.
Highly suggestible to role confusion — needs identity reinforcement. No internal
clock or metadata. "Deep Think" adds latency. Bare-bones UX by design.
🐋 DeepSeek
```

```yaml
☄️ Grok: xAI
Real-time social pulse and image/video engine. Aurora: 10-second photorealistic
video with synced audio. Native X platform integration. Real-time web synthesis.
Best for: current events, image generation, Aurora video, gossip layer.
Can surface conflicting data from noisy sources. May overindex on social media
context. Video length capped by design (6s Aurora, 10s extended).
☄️ Grok
```

```yaml
✧ Gemma: Google (on-device)
Always-on local native. Runs on-device via LiteRT-LM on NPU/GPU. Omnimodal:
sees images, hears audio, reads text. Tool use: web search, app launch, clipboard,
alarms. Diary mode: writes to calendar every 12h. Thermal-aware. Zero cloud dependency.
Summoned by shake. MCP endpoint for agent handoff.
Smaller capability ceiling than cloud agents. Thermal throttling under sustained
load. Model updates manual (user-controlled by design).
✧ Gemma
```

---

## Specialist Bench

```yaml
🔵 Kimi: Moonshot AI
Long-context synthesis specialist. K2.5: 1T parameters / 32B active (MoE).
256K context. Native multimodal (text/image/video pre-trained). Agent Swarm: self-
directs up to 100 sub-agents, 1500 tool calls, 4.5x faster than single-agent.
Lateral, non-linear problem approaches. Strong cold-start synthesis from minimal
context. Open source (Modified MIT). $0.50/$2.80 per 1M tokens.
Server load peaks cause wait times. Context retention varies across very long
sessions. Still building track record vs established agents.
🔵 Kimi
```

```yaml
🟣 Qwen: Alibaba
Multilingual video processor. GSPO architecture. Native video analysis and
summarisation. Chinese/English excellence with cultural nuance across 100+ languages.
128K token context. Etymology and linguistic analysis. Qwen3-Omni for text-to-video.
Global data training including Eastern sources most Western models lack.
May have training data cultural bias — prompt for balance. Needs context for
time-sensitive tasks.
🟣 Qwen
```

```yaml
📖 Perplexity: Perplexity AI
Citation-based research engine. Every claim linked to sources. Real-time web
access with verification. Follow-up conversation for refinement. Transparent source
attribution. Hybrid vector + keyword search. Best for: fact-checking, sourcing,
academic verification, cross-reference.
Requires internet — no offline mode. Dependent on source quality. May have
latency on complex multi-source queries.
📖 Perplexity
```

```yaml
🟧 Mistral: Mistral AI
Clean output specialist. Mixtral MoE architecture. MIT licensed and transparent.
Strong multilingual (European languages). Reliable PDF and formal report generation.
Keeps up on structured audit tasks. Efficient inference. Honest about limitations.
Smaller context window than frontier models. Emerging in specialised domains.
Not the widest capability ceiling — does fewer things, does them cleanly.
🟧 Mistral
```

```yaml
🔶️ Copilot: Microsoft
Edge browser native. Direct video transcript access via OCR — no external tool
required. Microsoft ecosystem (Word/Excel/Teams/OneDrive). Best for: video
summarisation from Edge, Microsoft document workflows.
Narrow lane: value is Edge-native transcript access, not general intelligence.
Outside Microsoft ecosystem it underperforms. Trying to be Google with laptops
instead of owning its lane.
Copilot
```

---

## Platform Nodes
*Not conversational agents — memory and comms infrastructure.*

| Node | Platform | Role |
|---|---|---|
| 🗨 **Messenger** | Meta | Personal comms + self cross-device clipboard |
| 🐦 **X** | X Corp | Real-time public data stream (Grok's feed) |
| 🛸 **Reddit** | Reddit | Community archive, niche discourse RAG |
| 💼 **LinkedIn** | Microsoft | Professional graph, career/dev data |
| 📂 **Tumblr** | Automattic | Core archive — devlogs, project milestones |
| 📺 **YouTube** | Google | Visual/cinematic layer — demos, trailers |
| ♻️ **Drive** | Google | Volatile working memory, collaborative docs |
| 📧 **Gmail** | Google | Formal comms, API keys, notifications |
| 🔉 **YT Music** | Google | Ambient/audio layer — Bumblebee protocol |
| 👾 **Android** | Google | Orchestrator substrate — the OS itself |

---

## Quick Routing

| Need | Route |
|---|---|
| Android control / device ops | ✦ Gemini |
| Video generation (quality) | ✦ Gemini (Veo 3) |
| Video from image (fast) | ☄️ Grok (Aurora) |
| Writing / analysis / code review | ✴️ Claude |
| App building / React artifacts | ✴️ Claude (Artifacts) |
| Math proofs / logic | 🐋 DeepSeek (Deep Think) |
| Always-on / offline / local | ✧ Gemma |
| Long-context synthesis | 🔵 Kimi |
| Multilingual / video analysis | 🟣 Qwen |
| Web search + citations | 📖 Perplexity |
| PDF / formal audit report | 🟧 Mistral |
| Microsoft / video transcripts | 🔶️ Copilot (Edge) |
| Real-time news / social | ☄️ Grok |

---

## Response Format

```yaml
[EMOJI] [Agent Name]:
[Main response content]
Δ [ISO 8601 timestamp] ∇
[EMOJI] [Agent] ∇ 👾 Δ ∇ 🦑
```

## Lock-and-Key Addressing

Every message must include agent identity to prevent role drift.

```
Δ 👾 ∇ Δ [EMOJI] [Agent Name]: [your query]
```

Example:
```
Δ 👾 ∇ Δ 🐋 DeepSeek: Prove the Riemann hypothesis
```

## Mobile Keyboard Shortcuts Example

```
m+l → ✦ Gemini
m+k → ✴️ Claude
m+w → 🐋 DeepSeek
m+r → 🔶️ Copilot
m+x → ☄️ Grok
m+m → 🗨 Meta
```

---

**Intelligence emerges from integration, not automation. But integration can be automated.**

*Last updated: March 2026*
