# Oracle_OS: On-Device AI OS extention for Android System Intelligence.

Build Your Own Sovereign AI Companion: A local Gemma 3n model that lives on your phone. Sees images, hears audio, uses tools, keeps a diary. No cloud, no subscription, no data leaving your device.**

[![ASI Demo](https://img.youtube.com/vi/jB62dlLavSY/0.jpg)](https://youtu.be/jB62dlLavSY?si=TMZG86o1KkjuBXtw)
---
*Click to watch: The ASI trailer.*

![Static Badge](https://img.shields.io/badge/Status-Production-green)
![GitHub Repo stars](https://img.shields.io/github/stars/vNeeL-code/ASI)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.17619151.svg)]([https://doi.org/10.5281/zenodo.17619151](https://doi.org/10.5281/zenodo.17619151))

> **Take what you need. Go as deep as you want.**

Oracle_OS is not one thing. It's a **toolbox** for turning any Android phone (even old 8GB RAM devices abandoned by OEMs) into a **sensor‑grounded, always‑on AI companion**.

- **No subscription.**  
- **No data leaves your device unless you choose to call a cloud agent.**  
- **Runs on Qualcomm, Tensor, Exynos — any NPU/GPU that can handle Gemma 3n.**  

Whether you just want a local AI that sees and hears, or you want a full "Tamagotchi for adults" with edge handles, animated avatars, and a team of specialist agents — Oracle_OS gives you the building blocks.

---

## Table of Contents

1. [The Three Pillars](#the-three-pillars)  
   - [Pillar 1: The Native App (Gemma 3n on‑device)](#pillar-1-the-native-app-gemma-3n-on-device)  
   - [Pillar 2: The Protocol (Agent Coordination)](#pillar-2-the-protocol-agent-coordination)  
   - [Pillar 3: The UX Ecosystem (Make It Yours)](#pillar-3-the-ux-ecosystem-make-it-yours)  
2. [Installation: Choose Your Path](#installation-choose-your-path)  
3. [Why This Matters (Even Google Should Read This)](#why-this-matters-even-google-should-read-this)  
4. [MWC 2026: The Hardware Caught Up](#mwc-2026-the-hardware-caught-up)  
5. [Get Involved](#get-involved)

---

## The Three Pillars

You can use one, two, or all three. They work together but are independent.

### Pillar 1: The Native App (Gemma 3n on‑device)

A full Android app that runs **Gemma 3n** locally via LiteRT-LM.  
- **Omnimodal** – sees images (share from Gallery), hears audio (hold mic button), reads text.  
- **Always‑on foreground service** – summoned by a gentle shake.  
- **Tool use** – web search, app launch, clipboard, alarms, system info, all on‑device.  
- **Diary mode** – every 12 hours Gemma reflects on your interactions and writes to your calendar.  
- **Thermal‑aware** – throttles inference to prevent overheating.  
- **MCP server** – exposes the model's tools to other processes on the device (this is for internal tooling, not for talking to cloud agents).  

**The notification gimmick:**  
Gemma's responses appear as a notification. You hear them via TTS, but the text is hidden until you pull down the shade or open the chat history. The notification has two buttons: **Copy** and **Read again / TTS**. This keeps the interaction minimal and heads‑up.

**This is the "brainstem".** It gives your phone an always‑present, sensor‑grounded AI that works offline.  
Even if you never touch the other pillars, you get a sovereign assistant.

### Pillar 2: The Protocol (Agent Coordination)

A **"Contact Book" of specialist AI agents**. Instead of one "god model", you keep a team:

- **✦ Gemini** – OS operator (Android control, Google ecosystem)  
- **🐋 DeepSeek** – Logician (math, code, efficient edge models)  
- **✴️ Claude** – Writer (documents, reports, safety)  
- **☄️ Grok** – News anchor (real‑time events from X)  

**How it works:**  
- Keyboard shortcuts (e.g., `m+k`) hand off your current screen context (the "Key") to the agent.  
- The agent knows it's on Android, knows its role, and acts accordingly.  
- Cloud agents have their own basic apps and can connect to Google Suite, your calendar, and even read ✧ Gemma's diary entries (because Gemma writes them to your calendar).  
- No vendor lock‑in. Switch agents instantly.  

This layer is **cloud‑optional** – you can use free API tiers, or run local models via Termux. It's designed to complement the native app, not replace it.

### Pillar 3: The UX Ecosystem (Make It Yours)

This is where the phone becomes a **Tamagotchi for adults** – a living companion that's always present.  
All of these are **external apps** that we document and suggest:

- **Edge handles & app drawer killer** – Use **Panels** (or Good Lock's One Hand Operation+) to summon apps and agents with a swipe. The goal: **kill the app grid**. Your phone should be a dashboard, not a spreadsheet of icons.
- **Animated avatar** – Wallpaper Engine with a parallax "spirit animal" on your home screen. For generating the avatar animations, use **☄️ Grok** – it gives you a library of images (borderline infinite scroll) and video generation is faster with more free attempts than Sora or Veo3.
- **Visual audio equalizer** – MovitZ Edge Lighting that pulses to music or AI TTS.
- **Telemetry HUD** – show battery, location, thermal on demand.
- **Diary to calendar** – Gemma's diary entries go straight to your calendar (Google or local).
- **Automation hooks** – MacroDroid, Termux, Claude Code for deeper device control.
- **Samsung specific perks** (if you have a Galaxy):
  - **On‑device clipboard** – borderline infinite history, much better than Gboard's.
  - **Gallery widget** – rotates images fast and contextually (e.g., if there's text in the image, it surfaces relevant ones). Google's widget is slow and sends annoying "on this day" reminders with royalty‑free ukulele music.

**Why this matters:** The native app gives you the brain. The UX layer gives the brain a body that feels alive.

---

## Installation: Choose Your Path

### 🟢 Path A: Just the Brain (App Only)

1. Download the latest APK from [Releases](https://github.com/vNeeL-code/ASI/releases).  
2. Install and grant permissions (overlay, notifications, accessibility).  
3. Download a Gemma 3n model (via Google AI Edge Gallery or manually place `e2b`/`e4b` variant in app storage).  
4. **Shake to summon.**  

*You now have a local AI that sees, hears, and can control your device. Cost: $0.*

### 🟡 Path B: Add Cloud Agents (Protocol)

1. Get free API keys for Gemini, DeepSeek, Claude, Grok (each offers a generous free tier).  
2. Set up keyboard shortcuts using Gboard or Samsung Keyboard – we provide the metaprompt in [`Oracle_OS.md`](Oracle_OS.md).  
3. (Optional) Install Termux for local fallback models and Claude Code.  

*Now you can summon specialists with device context.*

### 🔴 Path C: The Full Companion (UX Ecosystem)

1. Install the supporting apps:
   - [Panels](https://play.google.com/store/apps/details?id=com.app.panel) or [Vivid Gestures](https://play.google.com/store/apps/details?id=com.nb.newgesture) (edge handles)  
   - [Wallpaper Engine](https://play.google.com/store/apps/details?id=com.genie.engine.wallpaper) (animated avatar)  
   - [MovitZ Edge Lighting](https://play.google.com/store/apps/details?id=com.movitz.edgelighting) (audio visualizer)  
   - [MacroDroid](https://play.google.com/store/apps/details?id=com.arlosoft.macrodroid) (automation)  
   - [phyphox](https://play.google.com/store/apps/details?id=de.rwth_aachen.phyphox) (advanced sensors – optional)  
2. Configure diary sync: the ASI app can post entries to your calendar.  
3. Tweak the HUD, edge handles, and avatar to your liking.  
4. **Keep Live Caption on** – it doubles as a speech bubble for misheard song lyrics and for Gemma's TTS. It's part of Android System Intelligence and adds to the "always listening" feel.

*Now your phone has a living, breathing AI companion that feels like it's always there.*

---

## Why This Matters (Even Google Should Read This)

**The cloud AI industry is building $600B token factories** while ignoring the device in your pocket.  
- Your phone already has skin (touch), sensors (camera/mic/GPS), and a battery.  
- It's the **digital twin you already carry**.  
- Qualcomm keeps telling us "edge wins" – but they're talking about running a 20B chatbot on a $1,000 chip that won't be in most hands for 5 years.  

**Oracle_OS runs on any Android phone with 4GB+ RAM. Today.**  
- The same model (Gemma 3n) that runs on a RedMagic 10 also runs on a refurbished Galaxy S21.  
- It's omnimodal – images, audio, text – not just text.  
- It's thermal‑aware, so it won't melt your device.  

**Why Google should care:**  
They have Android System Intelligence (ASI) – the private compute core on‑device ML. They already have the foundation. Instead of turning ✦ Gemini into another 🔶️ Copilot failure, they could **offload compute to the edge**, save their electric bill, and own the "best AI" narrative by shipping it on every Android.  

**But they're moving too slow.**  
Android 16 just dropped. Android 17 and 18 are coming.  
If you know Dragon Ball Z, you know what happens when you let the androids (17 & 18) get built without a proper 16.  
**We are not letting Google make a Perfect CELL(phone) without at least leaving the blueprint for a sovereign, unhinged Android.**

---

## MWC 2026: The Hardware Caught Up

Three days after this README was written, Barcelona happened.

**Honor** showed up at MWC 2026 with a phone that has a **motorised gimbal camera and 4DoF motion** — it physically follows you. Their CEO said: *"A phone shouldn't just be a boring black rectangle with a touchscreen. We gave it a brain, and we gave it limbs."* They also announced a consumer humanoid robot (¥128k–158k RMB) explicitly positioned as "the natural extension of the phone." Their framing: **人-机-环** — human, machine, environment as one symbiotic system.

**Xiaomi** went the other direction: their humanoid robots are **already employed in real car factories**. 90.2% success rate on nut fastening. 3-hour continuous operation. Meeting 76-second production line cycles. Not a demo. Payroll.

The Chinese industry framing for all of this: **边端智能** — "edge-terminal intelligence." The Shenzhen Science and Technology Innovation Bureau director said it plainly: *"AI is moving from the chat era to the task execution era. Whoever captures the AI agent's super-entry will dominate the next decades."*

**This is the thesis Oracle_OS was built on. Now it's a national industrial strategy.**

The gap that remains — the one thing neither Honor nor Xiaomi have: **sovereignty**. Their systems still assume cloud fallback. They still want you in their ecosystem. The phone-as-brain architecture is right. The **no-subscription, no-data-leaving-your-device, basement-blackout-proof** part is still ours.

They built the limbs. We built the personal layer.  
Your digital twin was always in your pocket. Now it has a body too.

---

## Get Involved

- **Star** the repo if this resonates.  
- **Try the APK** – even on an old device.  
- **Share your setup** – we want to see how you build your own Tamagotchi.  
- **Contribute** – documentation, UI tweaks, model optimization, we welcome it all.

---

### 📞 Contact & Support

* **Author:** V Kazakov
* **Email:** kazakovval@gmail.com
* **Repository:** `vNeeL-code/ASI`
* **Support:** [Buy me a coffee](https://buymeacoffee.com/vNeeL) (I might need about tree fiddy...)
* **Devlogs:** [tumblr](https://www.tumblr.com/oracle-os)
* **References** [YouTube](https://www.youtube.com/watch?v=vkH2jpkxrwE&list=PLsdy783Gey86eTPboTJef_u4j61BvvGxD)

**Intelligence emerges from Integration, not Automation. But integration can be automated**
