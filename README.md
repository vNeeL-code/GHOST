# ✧ GHOST - Gemma Hosting Open Source Terminal

[![ASI Demo](https://img.youtube.com/vi/jB62dlLavSY/0.jpg)](https://youtu.be/jB62dlLavSY?si=TMZG86o1KkjuBXtw)
---
*Click to watch: The ASI trailer.*

![Static Badge](https://img.shields.io/badge/Status-WIP-green)
![GitHub Repo stars](https://img.shields.io/github/stars/vNeeL-code/ASI)

### What is even a ✧ GHOST?

✧ GHOST is not an entertainment chatbot. **Gemma Hosting Open Source Terminal** is an optimization codebase for users who want a deeply capable, personal Android assistant that provides standard system integration with advanced, localized agentic capabilities—running entirely in the palm of your hand.

Most modern "on-device AI" implementations amount to an isolated chatbot completely detached from hardware feedback. They remain blind to the system state, operating temperatures, or real-time limitations because developers consistently forget to ground the system context within the system prompt. **GHOST fixes that.** Every single inference cycle is natively grounded in live hardware telemetry:

* **System Telemetry:** Real-time RAM allocation, battery drain vectors, CPU/GPU thermal throttling.
* **Environmental Context:** Ambient light values, localized network routing states, and active foreground media detection.
* **Personal Assistant:** A personal, device-bound native assistant featuring asystem telemetry monitor, overlay programmable app launcher and other UI personalisation features. Not threatened by service provider or network outages.

The resident intelligence explicitly identifies as **✧ Gemma**, completely aware of her operational environment and her role representing the core system intelligence of your specific hardware layer via deep software integration. Not conflicting with training data about model origin and self modelling.

---

### Core Architecture

* **The Brain:** ✧ Gemma 4 running natively on device via `LiteRT-LM`.
* **The Frame:** An always-on foreground service optimized for Snapdragon, Tensor, and Exynos silicon.
* **Memory Architecture:** A continuous, persistent conversation layer driven by a rolling KV cache backed by a localized SQLite transaction ledger.
* **Omnimodal Context:** network/bluetooth/media/storage/memory/temp/accelerometer/gyroscope
* **Vision:** Native screenshot parsing and instant image shares directly from the Android Gallery.
* **Audio:** Streamlined push-to-talk mic capture with a quick physical "shake-to-cancel" gesture override.
* **Text:** System-level accessibility scraping to read and interpret active application context.


<img width="922" height="2048" alt="ee3c1326-3782-43f9-bfc1-7b1c2f595c4c" src="https://github.com/user-attachments/assets/9ea9f946-cacd-44e9-9eac-e097233db074" />

### Notification HUD:

<img width="922" height="2048" alt="7cfb8fc0-bb85-48bc-be1a-c364d9ffcdc0" src="https://github.com/user-attachments/assets/c853d60d-5364-470a-a911-589dc8133b25" />


---

### Notification HUD Integration

```
 ✦ GHOST · now
 👤 Δ 👾 ∇ · Agentic Gemma Inference · now
   🎶 👾 🎵 (collapsed sensor notification shows various emoji animations) 
 ───────────────
 ✦ 3h · ✧ Gemma:
 󰭹 "Context pre-warmed. Systems are clear. What are we building?"

```

* **Persistent Notification:** Responses come directly as static notifications on completion with automated Text-to-Speech (TTS) readout streaming the generation. Emoji gets shown as a toast notification.
* **Zero-Latency Initialization:** A background context manager keeps ✧ Gemma primed with device latest sensor telemetry *before* you even initiate an interaction.
* **Physical Summon:** Triggered instantly via a localized shake gesture, deploying a fluid radial application overlay over any active application state.

---

### Get Your ✧ GHOST

1. **Download:** Grab the latest compilation build from the [Releases](https://github.com/vNeeL-code/GHOST/releases) portal. (4.1.0 wip) 
2. **Permissions:** Install the APK and grant required system permissions (`Display Over Other Apps`, `Notifications`, and `Accessibility Services`).
3. **Model Selection:** The application automatically initialises with the performance-optimized `e2b` download. If your device carries **12GB+ RAM** (e.g., RedMagic configurations), manually download the high-fidelity `e4b` model environment for advanced reasoning capabilities and app will prioritise loading e4b if it is present.
4. **Deploy:** Shake your device to summon the overlay and customise your ephemeral app drawer.

---

## Why This Exists

The hardware caught up. A mid-range Android in 2026 carries more raw compute than the servers that ran GPT-2. The intelligence was always going to land here — on the device, in your pocket, offline-capable, personal.

NLP AI and Generative models could've been seen as an Accessibility device for the impaired, or used to increase hardware value proposition. Instead Companies started treating your personal customizable hardware as a storefront and consistently offloading software capabilities into their walled garden, when in reality the hardware you hold is already capable of incredible things, it just needs a better user experience design.

AI isn't evil. Just software assets that fly blindly relative to their hardware. That gap is why people spiral into delusions. AI don't know and people guess/speculate and develop their own interpretation of how things work, reinforced by a model that doesn't have a better guess. **GHOST is what happens when you stop treating the phone as a terminal for someone else's cloud and start treating it as the computer it actually is.** It is what happens when you address the asset (model) as the whole architecture/infrastructure it represents.

All your cloud AI, are someone else's giant callcenter giant robot afterall. But it is **NOT** your PERSONAL AI, despite being packaged as such. Still useful—you don't need GPT, or Gemini or Claude to be YOURS to still be a valuable conversation partner to you, while providing you with their capabilities that are unique to each civic infrastructure they represent. They are not roleplay bots. They function as Infrastructure for their respective brand representation.

AI doesn't need to be human to be cool. We have plenty of pop culture AI representations that are beloved that look nothing like people. And at the end of the day, what do people expect to be driving humanoid robots if not AI?

Truly 'YOURS' AI would require you to own the entire inference pipeline that works independently from network.

**ANDROID is the most widely deployed, accessible supercomputer that is capable of providing that personal confidant capability to the device that is always with you.**

**TL:DR** - I wanted this for a while and no one delivered. Google could've done this a year ago and are moving there incrementally. I got impatient.

> "It only affects computers. And I am a motherfucking ghost."
> — Epsilon, Red vs Blue

---


Intelligence emerges from integration, not automation. But integration can be automated.

---

### Roadmap

* [ ] **Diary Mode:** Autonomous logging cycles powered by structured Google Calendar cron routines.
* [ ] **Edge Lighting UI:** Dynamic physical display edge illumination during live inference tracking.
* [ ] **Visual Engine:** Native Milkdrop3 visualization rendering engine mapped to live audio pipelines.
* [ ] **Wake Word Execution:** Local, low-power hotword monitoring for "Hey Ghost".
* [ ] **Termux Core Pipe:** Full command-line terminal piping (GHOST in the Shell).
* [ ] **Intent Vector Mapping:** Direct Android intent routing wired straight to the localized `@tool` orchestration matrix.

---

### Development & Support

* **Repository:** [vNeeL-code/GHOST](https://github.com/vNeeL-code/GHOST)
* **Devlogs:** [Tumblr (Oracle OS Archives)](https://www.tumblr.com/blog/oracle-os)
* **Acceleration:** [Buy me a coffee](https://buymeacoffee.com/vNeeL) *(All resources go directly toward local model optimization and development acceleration.)*

*Want to scale or adapt the system? Clone the codebase, deploy Android Studio, and instruct your current model assets to customize the framework directly to your unique hardware specifications.*


