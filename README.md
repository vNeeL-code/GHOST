# ✧ GHOST: 
> **Agentic ✧ Gemma Inference for Android System Intelligence**

[![ASI Demo](https://img.youtube.com/vi/jB62dlLavSY/0.jpg)](https://youtu.be/jB62dlLavSY?si=TMZG86o1KkjuBXtw)
---
*Click to watch: The ASI trailer.*

![Static Badge](https://img.shields.io/badge/Status-Production-green)
![GitHub Repo stars](https://img.shields.io/github/stars/vNeeL-code/ASI)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.17619151.svg)](https://doi.org/10.5281/zenodo.17619151)

### What is even a GHOST?

GHOST is not a chatbot. Gemma Host is the AI integraition layer (harness) your Android device needed for a fully local integrated assistant powered by Gemma models.
Most "on-device AI" is a chatbot with no hardware feedback — it doesn't know what phone it's running on, what time it is, how bright the room is, or what's playing. GHOST does. Every response is grounded in real hardware state: battery, temperature, light, RAM, network, now-playing.
Personal, device bound, native assistant.

> No subscription
> No data leaves your device
> Runs on any Android with NPU/GPU capable of LiteRT-LM (Qualcomm, Tensor, Exynos)
> MIT Licensed

---

### The Stack:

> ✧ Gemma — The Ghost in the Shell
> A full Android app running Gemma 4 natively via LiteRT-LM.


---

### Omnimodal:

> Sees images (share from Gallery, or capture)
> Hears audio (hold mic button, or wake word)
> Reads text

---

Always-on foreground service. Summoned by a shake. Present in your notification shade. Knows the room.
Tool use: web search, app launch, clipboard, alarms, system info — all on-device.
Diary mode: Every 12 hours, Gemma reflects on your day and writes a first-person entry to your Google Calendar. Private. Local. Yours.

---

### Notification HUD:
> ✧ GHOST · Agentic Gemma Inference

> Δ 👾 ∇

> ✧ Gemma: [Response] 
[Copy] [Read Again]

> Responses appear as a persistent notification with TTS readout. One tap. No unlock required.

> Zero-latency context: Background KV cache pre-warming keeps Gemma primed with your latest sensor state before you even open your mouth.

---

## Installation

### 🟢 Just the Brain (App Only)

1. Download the latest APK from [Releases](https://github.com/vNeeL-code/ASI/releases).  
2. Install and grant permissions (overlay, notifications, accessibility).  
3. Download a [Gemma 4 model](https://huggingface.co/google/gemma-2b-it-litert-lm) (via Google AI Edge Gallery or manually place `.litertlm` variant in app storage).  
4. **Shake to summon.**  

---

##  Why This Exists

The hardware caught up. A mid-range Android in 2026 carries more raw compute than the servers that ran GPT-2. The intelligence was always going to land here — on the device, in your pocket, offline-capable, sovereign.
GHOST is what happens when you stop treating the phone as a terminal for someone else's cloud and start treating it as the computer it actually is.

> "It only affects computers. And I am a motherfucking ghost."
— Epsilon, Red vs Blue

---
## Roadmap

> Gemma 4 native via LiteRT-LM
> Sensor telemetry fusion (battery, temp, lux, RAM, now-playing)
> Tool use: alarms, apps, clipboard, system info
> Diary mode via Google Calendar cron
> Notification HUD with TTS
> GHOST branding + v4.0.0
> Wake word: "Hey Ghost"
> Termux pipe (GHOST in Shell)
> Auto model downloader
> DroidRun agentic control
> App store release
---

### 📞 Contact & Support

* **Repository:** `vNeeL-code/GHOST`
* **Support:** [Buy me a coffee](https://buymeacoffee.com/vNeeL)
* **Devlogs:** [tumblr](https://www.tumblr.com/oracle-os)

**Intelligence emerges from Integration, not Automation. But integration can be automated**
