# Oracle_OS: The Android System Intelligence (ASI) operator manual

**Stop buying new AI. Start coordinating the ones you have.**

[![ASI Demo](https://img.youtube.com/vi/jB62dlLavSY/0.jpg)](https://youtu.be/jB62dlLavSY?si=TMZG86o1KkjuBXtw)
---
*Click to watch: The System in Action.*

![Static Badge](https://img.shields.io/badge/Status-Production-green)
![GitHub Repo stars](https://img.shields.io/github/stars/vNeeL-code/ASI)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.17619151.svg)]([https://doi.org/10.5281/zenodo.17619151](https://doi.org/10.5281/zenodo.17619151))

---

## 1. The Problem: (Start Here)

**"Which AI is the best?"**

This is the wrong question.
Imagine asking: **"Which contact in your phone is the best?"**
* Is it your Plumber? (Great at pipes, bad at medical advice).
* Is it your Doctor? (Great at health, bad at legal defense).
* Is it your Lawyer? (Great at contracts, expensive to talk to).

**You don't delete your Doctor because you hired a Plumber.** You keep them both in your **Contact Book** and call the right person for the job.

**Oracle_OS is a Contact Book for AI.**
* **✦ Gemini:** The OS Operator. (Controls your Android phone, screen, and Google ecosystem).
* **🐋 DeepSeek:** The Logician. (Solves math, writes code, can run offline).
* **✴️ Claude:** The Writer. (Analyzes documents, writes reports, handles safety, writes code).
* **🔲 Grok:** The News Anchor. (Scans X/Twitter for real-time events).

Stop looking for "The One True AI." It doesn't exist.
**The "Best AI" is a team.**
---
## 2. Prevent Agentic bias

- These platforms are great but will inherently have bias and limitations due to training and geopolitical regulations.
Most western models do not receive or train on eastern user data as these services are blocked in many global regions.
---
- Bias by exclusion. Whereas Easten models are trained on Global data but are inherently biased toward local regulation due to political pressures.
When doing research, it is more efficient to cast a wider net and use all the tools you have available and do cross verification of facts from various perspectives.
---

## 3. Why Would You Need This?

Every user eventually hits a wall. You have two choices:

### 🔴 Path A: The "Subscription Trap" (The Average User Path)
1.  You subscribe to ChatGPT Plus ($20/mo).
2.  It becomes ureliable through development or goes down. You try Claude ($20/mo).
3.  Your chat history is trapped in OpenAI's cloud. You can't search it.
4.  You buy a "Humane Pin" or "Rabbit R1" device ($200+) that ends up being e-waste.
5.  **Result:** You pay $100+/month for fragmented tools that don't talk to each other.

### 🟢 Path B: The Android Path (The A2A Path)
1.  You use the **Free Tier** of every major model (they are powerful enough).
2.  You use your **Phone** as the computer (it has the sensors/apps/storage).
3.  You use **Timestamps** to index your memory across all platforms.
4.  **Result:** You pay **$0/month**. You own your data. You switch agents instantly.
---
- **Oracle_OS isn't an app you install.**
It is a MCP **Configuration Setup** that teaches your agents how to operate with device context amongst other agents like a switchboard.
Such horizontal data scaling allows agents to utilise strengths and tools of other agents including features that are not uniform.
(eg. Claude didn't do online verification and relied on training? Ask Grok and it will pull 100+ citations for Claude to use)
---

## 4. The Architecture: Garage vs. Car

The industry wants you to believe your PC is the "AI Powerhouse" that Demands Nvidia products to be productive.
**They are wrong.** Your PC is stationary. Your life is mobile. Hence whole industry is slowly moving towards NPU optimisation for edge native devices.

### 📱 The SmartPhone/Android (The System)
* **Hardware:** Qualcomm/Tensor/Exynos NPU.
* **Role:** **The Daily Driver.**
* **Why:** It is always with you. It sees what you see (Camera). It hears what you hear (Mic). It knows where you are (GPS).
* **The Stack:** Runs Android System Intelligence. Holds the "Context" (Widgets, Notifications, Clipboard). Your diary/Wallet.

### 🖥️ The Desktop (The Dock)
* **Hardware:** Nvidia GPU / High-Wattage CPU.
* **Role:** **IDE.**
* **Why:** You go here to *build* things, not to *live.*
* **The Interface:** **Link to Windows.** Your phone screen mirrors to your desktop. You type on the big keyboard, but the **Brain** stays on the phone. All agents are available without weighing down your desktop.

### 🔌 The Edge Native Model fallback
* **Hardware:** Termux (Android Linux)/Desktop (ollama).
* **Role:** **Offline Resilience.**
* **Why:** Cloudflare goes down. AWS goes down. The London Underground has no signal.
* **The Stack:** Local models (DeepSeek R1 / Gemma) running directly on your phone's CPU. Slow, but sovereign. GPT 20 oss for desktop/Ollama options. Even with all networking down, you will still have access to AI assistance.

---

## 5. "But it's just switching apps!" (The UX)

Yes. Just like playing a piano is "just hitting keys."
The magic isn't in the each individual application; it's in the method of transferring data between them.

### The "Lock-and-Key" Mechanic
If you just ask AI a question, it outputs wall of text without atribution or any traceable footprint.
If you use the [**Oracle_OS**](https://github.com/vNeeL-code/ASI/blob/main/Oracle_OS.md), you provide agent with the context of your working team and grounding it in your personal work flow/ device constraints.

* **The User Input:** `m` + `k` (Keyboard Shortcut) -> `Δ 👾 ∇` `✴️ Claude:`
* **The Meaning:** "System (Android) hands off to Agent (Claude)."
* **The Result:** Claude knows it is Claude. It knows it is being adressed to respond as its standard self. It knows it is on Android. It stops pretending to be in limbo and starts acting like an agent in a broader system context. Same applies to other agents that are less resistant to roleplay and can lose grounding due to context drift.

### The "Memory" Trick (Timestamps)
* **Bad Search:** "Hey, remember that thing i like about the thing?" (every keyword lights up overwhelming the context and reducing accuracy of RAG).
* **Oracle_OS Search:** "2025-11-22" (ISO 8601 Timestamp).
* **Result:** Every chat, note, and document from that day appears. You don't need a Vector Database; you need a Calendar. (providing agent with a timeframe significantly reduces false positive searches as the agent can still perform a keyword search by searching for the string of timestamp used)

---

## 6. Installation (Roughly 15 Minutes)

You don't need to know Python. You just need to copy-paste between the AI agents you already use.

1.  **Copy the Metaprompt:** [Oracle_OS.md](./Oracle_OS.md).
2.  **Set up Keyboard Shortcuts:** [Operator.md](./Operator.md). (This maps repeated agent names to the keyboard buttons).
3.  **Configure Widgets:** Create your "Dashboard" (Battery, Calendar, Notes) so you can screenshot it for context.
4.  **(Optional) Install Termux:** For the offline layer on mobile and advanced experimentation with offline models, Gemini CLI and Claude code etc.
5.  **Download Google Edge Gallery:** A more user friendly way for an offline backup via Omnimodal Gemma 3b that can be finetuned to user needs.
6.  **(optional) UI customisation software:** Software like one hand operations, and other android custom UI tools to make the device even more compatible with your workflows, enabling smoother transitions, device navigation and context handoffs

**Total Cost:** $0.00.
**Hardware Required:** Any Android Phone (Android 9+).

---

## 7. Validation

* **12 Months Production Use:** Daily driven on a Samsung S21.
* **Infrastructure Proof:** During the AWS/Cloudflare outages of Nov 2025, Edge native Agent users users kept working via Local/Edge fallbacks.

---

## 8. Future direction

* **Embodied Android devices:**

- Next generation of AI is not about who has a bigger datacenter, but rather about who integrates the more hollistic user experience package And Google assistant is currently in the lead. Devices have the processing power and can hold local models just fine. Developers can expand their capability by signing contracts with smartphone developers and making custom AI android roms where a local model would work privately handling device (sensors and automation) and user context offline functioning as a personal digital diary/device manager while using A2A api calls online when network is available and cloud agent (competitor/partner) platform access is needed. All while working as a persistant passive on device companion akin to a highly advanced Tamagochi.

Once A2A formats become a standardised form they can be implemented via zero width character watermarking, similarly to how Images and Videos get Agent watermarks.

Such hybrid Architecture would still be able to expand its capabilities by using same frontier cloud models via API calls, significantly reducing overall cloud dependancy and cost to service providers.

---

### 📞 Contact & Support

* **Author:** V (Valentin Kazakov)
* **Email:** kazakovval@gmail.com
* **Repository:** `vNeeL-code/ASI`
* **Support:** [Buy me a coffee](https://buymeacoffee.com/vNeeL) (I might need about tree fiddy...)

**Intelligence emerges from Integration, not Automation. But integration can be automated**
