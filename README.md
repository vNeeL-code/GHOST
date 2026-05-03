# ✧ GHOST: On-Device Agentic ✧ Gemma Inference for Android System Intelligence

> **Build Your Own Personal AI Companion: A local Gemma 4 model that lives on your phone. Sees images, hears audio, uses tools, keeps a diary. No cloud, no subscription, no data leaving your device.**

[![ASI Demo](https://img.youtube.com/vi/jB62dlLavSY/0.jpg)](https://youtu.be/jB62dlLavSY?si=TMZG86o1KkjuBXtw)
---
*Click to watch: The ASI trailer.*

![Static Badge](https://img.shields.io/badge/Status-Production-green)
![GitHub Repo stars](https://img.shields.io/github/stars/vNeeL-code/ASI)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.17619151.svg)](https://doi.org/10.5281/zenodo.17619151)

> [!IMPORTANT]
> **May 2026 Breakthrough: Stable Native Toolset Integration**
> We have successfully migrated to the **LiteRT-LM 0.10.2 Native Toolset**. This eliminates the latency of external regex parsers, allowing Gemma 4 to directly manipulate Android hardware (Flashlight, Alarms, Apps) with sub-second responsiveness.

> **Take what you need. Go as deep as you want.**

**GHOST** is not one thing. It's a **toolbox** for turning any Android phone into a **sensor‑grounded, always‑on AI companion**.

- **No subscription.**  
- **No data leaves your device.**  
- **Runs on Qualcomm, Tensor, Exynos — any NPU/GPU that can handle LiteRT-LM.**  

---

## The Three Pillars

### Pillar 1: The Native App (Gemma 4 native on‑device)

A full Android app that runs **Gemma 4** locally via LiteRT-LM.  
- **Motif:** Δ 👾 ∇
- **Agent:** ✧ Gemma
- **Omnimodal** – sees images (share from Gallery), hears audio (hold mic button), reads text.  
- **Always‑on foreground service** – summoned by a gentle shake.  
- **Tool use** – web search, app launch, clipboard, alarms, system info, all on‑device.  
- **Diary mode** – every 12 hours Gemma reflects on your interactions and writes to your calendar.  

**The notification HUD:**  
Gemma's responses appear as a notification with the **Δ 👾 ∇** motif. You hear them via TTS, and the text is visible in the shade. The notification has two buttons: **Copy** and **Read again / TTS**. 

**Zero-Latency Turn Logic**: 
Proactive background pre-warming keeps the KV cache "hot" with your latest sensory context (screen/sensors) before you even start typing.

---

## Installation

### 🟢 Path A: Just the Brain (App Only)

1. Download the latest APK from [Releases](https://github.com/vNeeL-code/ASI/releases).  
2. Install and grant permissions (overlay, notifications, accessibility).  
3. Download a [Gemma 4 model](https://huggingface.co/google/gemma-2b-it-litert-lm) (via Google AI Edge Gallery or manually place `.litertlm` variant in app storage).  
4. **Shake to summon.**  

---

### 📞 Contact & Support

* **Repository:** `vNeeL-code/ASI`
* **Support:** [Buy me a coffee](https://buymeacoffee.com/vNeeL)
* **Devlogs:** [tumblr](https://www.tumblr.com/oracle-os)

**Intelligence emerges from Integration, not Automation. But integration can be automated**
