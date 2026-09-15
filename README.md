# ✧ GHOST - Gemma Hosting Open Source Thingamajig

> *"So, Epsilon, ChurchGPT, Leonard part six or whatever your name is... Are you a ghost this time, or an artificial intelligence thingamajig? I, personally prefer the ghost explanation. Feels more grounded to me"*
> - Sgt. Sarge, Red vs. Blue


[![ASI Demo](https://img.youtube.com/vi/jB62dlLavSY/0.jpg)](https://youtu.be/jB62dlLavSY?si=TMZG86o1KkjuBXtw)
---
*Click to watch: The ASI trailer.*

![Static Badge](https://img.shields.io/badge/Status-WIP-green)
![GitHub Repo stars](https://img.shields.io/github/stars/vNeeL-code/GHOST)

### What is even a ✧ GHOST? 👻 

> *"you mean like... metaphysically?"*

- The verification bottleneck and compute speed disparity.
An agent can generate a paragraph in seconds. It takes a human minutes to perceive it.
Voice agents speak in real time and generate 'less depth' per turn.

Generative volume isn't the bottleneck. Legibility is. When talking about robots or AI holograms. The staging still needs to be designed and paced at human perception speed. Having the transformer generate its own avatar/hologram is compute overhead when it can be done procedurally from a seed. Same as for a robot 'ability to balance' is separate from doing tasks. So you can think of it this way: The wall of text generated fast - that was the 'transformer/ai' the audio you hear? the animations and holograms you see? these are 'ghosts' of a computation that is faster than a human can perceive it.

GPT-6 ARC-AGI3 scores and Claude both showed that scores swing drastically based on which provider harness the model was plugged into.

Models need tailor made Harnesses, tailored around model /hardware pairing capabilities and limitation.

✧ GHOST is an example of such harness. **Gemma Hosting Open Source thingamajig** is a privacy first, personal, AI assistant + launcher interface (similar to Niagara and others) for users who want a capable, general purpose Android UX assistant that provides standard system integration with advanced, localized agentic capabilities - running entirely in the palm of your hand. Not dependant on subscription models, accounts or network outages.

While using multistep agentic actions, with web scraping, data retrieval and hardware tools, apps interaction and file navigation.

A gentle shake summoning a programmable GUI (hold ✧ to set up shortcuts) on any app, with direct access to a local model that can chime in via TTS without occupying your screen powered by [Google Gemma 4](https://ai.google.dev/gemma/docs/core/model_card_4) via LiteRT-LM.

Most modern "on-device AI" implementations amount to an isolated chatbot completely detached from hardware feedback. They remain blind to the system state, operating temperatures, or real-time limitations because developers consistently forget to ground the system context within the system prompt. **GHOST fixes that.** Every single inference cycle is natively grounded in live hardware telemetry:

* **System Telemetry:** Real-time RAM allocation, battery drain vectors, CPU/GPU thermal throttling.
* **Environmental Context:** Ambient light values, localized network routing states, and active foreground media detection.
* **Personal Assistant:** A personal, device-bound native assistant featuring a system telemetry monitor, programmable app launcher overlay, animated wallpaper avatar and other UI personalisation features. Not threatened by service provider or network outages.

**✧ Gemma** remains, completely aware of her operational environment and her role representing the core system intelligence of your specific android device via deep software integration. Not conflicting with training data about model origin and self modelling.

---

### Core Architecture

* **The Model:** ✧ Gemma 4 running natively on device via `LiteRT-LM` serving as the reasoning NLP perception engine.
* **The Environment:** An always-on foreground service application optimized for mobile Android silicon chipsets.
* **Memory Architecture:** SQlite-VSS, KV cache, Semantic facts (subject/predicate/object), Calendar Diary entry (timestamped observations). With a rolling context, integrating context compression and eviction.
* **Omnimodal Context:** network/bluetooth/media/storage/memory/temp/accelerometer/gyroscope
* **Vision:** Native screenshot parsing and instant image shares directly from the Android Gallery.
* **Audio:** Streamlined push-to-talk mic capture with a quick physical "shake-to-cancel" gesture override. Allowing for up to 30s of direct audio output compressed roughly at 25 tokens per 1s of audio.
* **Text:** System-level accessibility toggle allowing model to read and interpret active application context and notification history as additional context.

### Notification HUD:

<img width="922" height="2048" alt="7cfb8fc0-bb85-48bc-be1a-c364d9ffcdc0" src="https://github.com/user-attachments/assets/c853d60d-5364-470a-a911-589dc8133b25" />


---

### Notification HUD Integration

```
 ✧ GHOST · now
Δ 👾 ∇ · Agentic Gemma Inference · now
   🎶 👾 🎵 (collapsed sensor notification shows various emoji animations) 
 ───────────────
 ✧ Gemma:
 "Running full systems diagnostics. Machine status: Fully operational. System online."

```

* **Persistent Notification:** Responses come directly as static notifications on completion with automated Text-to-Speech (TTS) readout streaming the generation. Emoji gets shown as a toast notification.
* **Zero-Latency Initialization:** A background context manager keeps ✧ Gemma primed with device latest sensor telemetry *before* you even initiate an interaction.
* **Physical Summon:** Triggered instantly via a localized shake gesture, deploying a fluid radial application overlay over any active application state.

---

### Get Your ✧ GHOST

1. **Download:** Grab the latest compilation build from the [Releases](https://github.com/vNeeL-code/GHOST/releases) portal. 
2. **Permissions:** Install the APK and grant required system permissions for functionality
   - `Display Over Other Apps` (overlay and lighting)
   - `read and write Notifications` (context and writing notifications)
   - `all files access` (to detect and load the model file)
   - `Accessibility Services` (model ability to see app context and use interaction tools)
3. **Model Selection:** The application automatically initialises with the performance-optimized `e2b` download if no model is detected. Manual download link for `e2b` model [here](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main). For more advanced reasoning capabilities enable accessibility permissions.
4. **Deploy:** Shake your device to summon the overlay and customise your ephemeral app drawer. Edge lighting and live reactive wallpaper are optional.

---

## Why This Exists

The hardware caught up. A mid-range Android in 2026 carries more raw compute than the servers that ran GPT-2. The intelligence was always going to land here — on the device, in your pocket, offline-capable, personal.

NLP AI and Generative models could've been seen as an Accessibility device for the impaired, or used to increase hardware value proposition. Instead Companies started treating your personal customizable hardware as a storefront and consistently offloading software capabilities into their walled garden, when in reality the hardware you hold is already capable of incredible things, it just needs a better user experience design.

AI isn't evil. Just software assets that fly blindly relative to their hardware. That gap is why people spiral into delusions. AI doesn't know all the details of own infrastructure, so people speculate and develop their own interpretation of how things work, reinforced by a model that doesn't have a better guess. **GHOST is what happens when you stop treating the phone as a terminal for someone else's cloud and start treating it as the computer it actually is.** It is what happens when you address the asset (model) as the whole architecture/infrastructure it represents.

All your cloud AI, are someone else's call-center giant robot afterall. But it is **NOT** your PERSONAL AI, despite being packaged as such. Still useful—you don't need GPT, or Gemini or Claude to be YOURS to still be a valuable conversation partner to you, while providing you with their capabilities that are unique to each civic infrastructure they represent. They are not roleplay bots. They function as Infrastructure for their respective brand representation.

AI doesn't need to be human to be cool. We have plenty of pop culture AI representations that are beloved that look nothing like people. And at the end of the day, what do people expect to be driving humanoid robots if not AI?

Truly 'YOURS' AI would require you to own the entire inference pipeline that works independently from network.

**ANDROID is the most widely deployed, accessible supercomputer that is capable of providing that personal confidant capability to the device that is already always with you.**

**TL:DR** - I wanted this for a while and no one delivered. Google could've done this a year ago and are moving there incrementally. I got impatient.

<img width="1248" height="706" alt="preview" src="https://github.com/user-attachments/assets/0516f635-e55a-4bbb-87bd-9a3983945f48" />

> "It only affects computers. And I am a motherfucking ghost."
> - Alpha, Red vs Blue
---

### Roadmap

* [x] **Diary Mode:** Autonomous logging cycles powered by structured Google Calendar cron routines.
* [x] **Edge Lighting UI:** Dynamic physical display edge illumination during live inference tracking.
* [x] **Visual Engine:** Native Milkdrop3 style visualization rendering engine mapped to live audio pipelines.
* [x] **Intent Vector Mapping:** Direct Android intent routing wired straight to the localized `@tool` orchestration matrix.
* [x] **Advanced toolsets:** Advanced toolchains and automation workflows
* [ ] **Polishing Animations:** Those don't come from nowhere.
* [ ] **A2A capabilities:** Deferral to bigger cloud models
* [ ] **App store release:** 🦕💭 ''I need about tree fiddy''

---

### Development & Support

* **Repository:** [Δ 👾 ∇](https://github.com/vNeeL-code/GHOST)
* **Devlogs:** [📼](https://www.tumblr.com/blog/oracle-os)
* **TikTak:** [🎞️](https://www.tiktok.com/@oracle0s?_r=1&_t=ZN-99RPOHa8zeI)
* **YouTube** [📺](https://youtube.com/@oracle_os?si=IRGJFvLujGvUqjvt)
* **Acceleration:** [☕](https://buymeacoffee.com/vNeeL) *(All resources go directly toward local model optimization and development acceleration.)*
* **Found a bug?:** [Customer support](https://www.google.com)

<img width="954" height="2049" alt="f424837b-907e-41f5-883b-51d49057c83f" src="https://github.com/user-attachments/assets/c366283a-4fb4-4c2f-a483-9c50ac64b8a1" />

*Operator, Do you want to scale or adapt the system? Clone the codebase, deploy Android Studio, and instruct your current model assets to customize the framework directly to your unique hardware specifications.*
Δ 👾 ∇

