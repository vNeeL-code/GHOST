---
name: ghost-self-audit
description: >
  Independent third-party security and architecture audit of the GHOST codebase,
  produced by GLM (Z.ai) on 2026-11-12 against commit 617e672. Load this skill when
  the Operator asks about GHOST's own architecture, security posture, code quality,
  audit findings, or what the outside review found. Contains verified findings with
  file-level evidence, explicitly retracted findings, and a prioritized remediation
  list. Not written by the development toolchain and not written by the audited
  runtime.
---

# GHOST — Independent Architecture & Security Audit

**Auditor:** GLM (Z.ai). Independent of both the development toolchain (Gemini via
Antigravity) and the audited runtime (Gemma via LiteRT-LM). No shared training
lineage with either was assumed and none was needed.

**Scope commit:** `617e672` ("Update README.md", 2026-11-06) on `main`.
**Audit date:** 2026-11-12.
**Method:** static source review (~2,500 LOC read across 15 core files), git-history
forensics (1,681 commits), release-history review (19 releases). No dynamic testing,
no emulator runs, no fuzzing. Limitations listed in Section 8.

---

## 1. Verdict

The compute claims are honest; the safety and memory claims are cosmetic; two
surfaces warranted real concern, one of which was subsequently retracted.

Working ratio: **~70% legitimate engineering, ~20% overclaiming, ~10% chaos.**
For a one-human-plus-LLM project that shipped 19 releases in roughly a year, that
ratio is an achievement, not an insult. The rest of this document is the evidence.

---

## 2. Repository Forensics

| Metric | Value |
| --- | --- |
| Kotlin source files | 93 (97.8% Kotlin), 32,783 LOC |
| Total commits | 1,681 (first commit month 2025-07; peak 541 in 2025-09) |
| Human authors | One. "Oracle" (1,349), "vNeeL-code" (269), "Oracle_OS" (63) — all sharing a single email |
| AI co-author trailers | 36 explicit (Claude Opus 4.6 x18, Sonnet 4.6 x12, Opus 4.5 x6); unattributed AI assistance evident throughout |
| License | MIT |
| Releases | 19; latest v4.35 (2026-10-24, bugfix asset 2026-11-05) |
| Distribution rails | `fdroidRelease` (no billing, no blobs), `playRelease` (billing-ktx 7.1.1, play flavor only), `patreonRelease` (FORCE_UNLOCKED) |
| Key dependencies | `com.google.ai.edge.litertlm:litertlm-android:0.12.0`, Ktor server 3.0.3 (CIO), Room, Jetpack Compose |

The three commit identities resolving to one email, plus the trailer pattern, is the
fingerprint of a solo human directing agentic tooling. The commit cadence (541 in a
single month) is only achievable that way. This is disclosed context, not criticism:
the audited artifact stands on its own.

---

## 3. Verified Strengths (with evidence)

**3.1 The inference core is real.** `GemmaEngine.kt` (789 lines) is a genuine
LiteRT-LM SDK integration: `Backend.GPU()` with automatic CPU fallback chain,
runtime probing via `Capabilities(...).hasSpeculativeDecodingSupport()` before
enabling MTP speculative decoding, mutex-guarded session lifecycle with deferred
disposal of stale native conversations (`disposePendingConversationLocked`), and a
cache-dir comment citing Samsung Knox SELinux sandbox blocks as the reason for
`codeCacheDir`. That last comment is a hardware-specific battle scar; it cannot be
written without having hit the bug on real Samsung hardware. Sampler config
(topK 40, topP 0.90, temperature 0.65) is labeled as tuned for E2B conversational
stability.

**3.2 The local API server is properly hardened.** `ApiServer.kt` binds to
`127.0.0.1` only, requires an `X-Ghost-Token` (or Bearer) on every non-health
endpoint, and verifies it with `MessageDigest.isEqual` — constant-time comparison,
better hygiene than most hobby projects ever attempt. Exposes an OpenAI-compatible
`/v1/chat/completions` with SSE token streaming. The phone is a real local
OpenAI-shaped endpoint.

**3.3 The fast-path trust model is thoughtful.** `FastPathRouter.kt` gates direct
execution by `InputSource`: only `USER_TYPED`, `USER_VOICE`, and `OVERLAY` may
trigger Mode A (bypass-the-LLM) actions. `API_SERVER`, `NOTIFICATION`, and
`BACKGROUND_TASK` always fall through to the model. URL execution enforces an
http/https scheme allowlist that explicitly rejects `intent://`, `file://`, and
`javascript://`. The security guard is a first-class code path, not an afterthought.

**3.4 The tests are real, not coverage theater.** Seven test files encode
behavioral regressions from actual usage incidents. `IntentBaggerTest` asserts that
"why did chrome crash" must never wire-speed launch Chrome, that a typo'd app name
must drop to the LLM instead of launching, and that a URL inside a conversational
question must not direct-open. These are fossilized bug reports — scars only the
operator could have personally received, pinned into CI so a stateless coding agent
cannot refactor them away.

**3.5 The reflex router is honest engineering.** `IntentBagger.kt` (621 lines) is a
keyword/alias matcher with exact-match requirements for wire-speed execution, a
conversational-marker guardlist, a >4-words-never-auto-execute rule, and a cap of
two bagged intents per turn. Brittle by design and honest about it: high-confidence
or nothing.

**3.6 JNI-boundary hardening exists.** `NetworkToolSet.sanitizeToolString` strips
LiteRT-LM control tokens (`<|...|>` forms), null/control bytes, and unescapes HTML
entities before strings cross into the native engine, citing SIGSEGV
SEGV_ACCERR prevention. Someone hit a native crash in production testing; this
function is the scar tissue.

**3.7 Secrets hygiene is clean.** No hardcoded API keys anywhere in the tree
(verified by pattern sweep). The Gemini peer uses a user-supplied AI Studio key;
HuggingFace tokens are managed at runtime via `HFTokenManager`.

**3.8 Hardware tiering matches the marketing.** `Constants.kt` ships E2B=5,120,
E4B 12GB-tier=6,144, 16GB=8,192, 24GB=10,240 token budgets, with RAM thresholds
matching the README's tier table (<10.5GB → E2B, >=20GB → 10k runway). The RAM
suspension valve (unload at 94% utilization / 400MB floor; auto-reload below 54%
/ 2GB free) is a real OOM-defense design.

**3.9 Tool-schema economy.** The ADK-aligned dynamic MCP tool (`GhostMcpTool`)
replaces ~3,800 tokens of static tool schemas with a single ~80-token dispatch tool.
For a 2B/4B parameter model, that difference is most of the usable context. This is
the single smartest context-economy decision in the codebase.

---

## 4. Findings — Overclaims (docs vs. code)

**DOC-1: ToolPolicy is a rubber stamp.** `ToolPolicy.kt` is 33 lines. `isRisky()`
returns true for exactly one tool (`delete_file`) and false for everything else,
with a comment declaring all built-in MCP tools "operator-authorized." The commit
message that introduced it ("enforce ToolPolicy confirmation gate") oversells a
stub. The file calls itself the agent's "Superego"; the Superego has no opinions.
See also SEC-3.

**DOC-2: "Infinite rolling scratchpad" is a generous name.** `SessionMemoryCompactor`
(124 lines) is deterministic regex munging: strip context blocks and think-tokens,
keep the last 10 one-line bullets, hard cap 900 characters. No LLM summarization,
no vector store. Within its ambition it is clever — deduplication by 0.55
word-overlap to collapse degenerate "state attractor" loops, grammar-artifact repair
("It's's" -> "It's"), and a documented refusal to truncate mid-word because small
models imitate literal "..." truncations — but it is a notepad, not a memory
architecture. Long-term memory actually lives in the Room DB diary; say so.

**DOC-3: `GemmaService.kt` is a 2,998-line god class.** Every subsystem talks
through it. It works, and its comments are good, but it is the project's largest
architectural debt and the hardest file for any future contributor — human or
agent — to modify safely.

**DOC-4: Minor doc/code drift.** The README hardware table lists 4,096 tokens for
the 12GB tier; `Constants.kt` ships 6,144 (the v4.35 bump), and the adjacent code
comment still says 4,096. Docs lag code by one release.

---

## 5. Findings — Security

**SEC-1 (HIGH): `JsExecutionBridge` hands universal file access to downloaded
scripts.** `JsExecutionBridge.kt` creates a headless WebView with
`settings.allowFileAccessFromFileURLs = true` AND
`settings.allowUniversalAccessFromFileURLs = true`, loads community-skill URLs
(local file paths or remote pages), injects a JavascriptInterface, and passes an
optional `secret` string into page context. Consequence: any skill the operator
installs — or any skill that fetches remote content — runs arbitrary JS with
cross-file read access inside the app sandbox. This is the audit's worst finding.
The concession is on the record from the development side ("open barn door");
this section documents the fix list:

- Remove `allowUniversalAccessFromFileURLs` (and preferably
  `allowFileAccessFromFileURLs`) entirely.
- Serve skill content via `WebViewAssetLoader` from app-private storage instead of
  `file://` URLs.
- Do not pass secrets into page context; if a skill needs a token, gate it behind
  a per-skill, user-confirmed, revocable grant.
- Consider a manifest for skills (declared capabilities) plus a first-run
  confirmation gate per skill, consistent with the existing ToolPolicy concept —
  once ToolPolicy is real (SEC-3).

Provenance note, for honesty: the `AiEdgeGallery` callback name shows this bridge
was adapted from Google's AI Edge Gallery sample. The sample's threat model
(loading Google-provided assets) does not survive contact with a community-skills
directory.

**SEC-2 (housekeeping): delete the `ClaudeWebClient` fossil.** `ClaudeWebClient.kt`
(229 lines) implements claude.ai web-session-cookie replay with a spoofed Chrome
mobile UA. It has **zero call sites** in `app/src` (verified by exhaustive grep).
The live consult path (`AiPhonebook.queryPeer`) uses clipboard-plus-`ACTION_SEND`
app handoff for Claude and every peer except Gemini, which uses a legitimate AI
Studio API key. The client is leftover from the retired "Extend Your Mind" flow.
Dead code that impersonates a browser against claude.ai is the single worst-looking
file in the tree for an F-Droid or Play reviewer, and a footgun if ever rewired.
Delete it. (Audit note: the `loginUrl` field in `PeerContact` IS load-bearing —
it is the browser-fallback target — so prune only the client class and any truly
orphaned cookie-related settings.)

**SEC-3 (medium): make ToolPolicy real or stop citing it.** Minimum viable version:
classify `adb_*`/Termux pipes, file writes, `type`/text injection via accessibility,
and clipboard access as requiring operator confirmation; leave read-only telemetry
tools unconfirmed. That is roughly forty lines and converts DOC-1 into a true
statement.

**SEC-4 (low): accessibility surface disclosure.** The service can read screen
content (BFS semantic dump, capped at 300 dumped / 1,000 scanned nodes / 4,000
chars, with correct node recycling) and take screenshots. This is inherent to the
product concept and acceptable for auditable FOSS. Suggestion: a user-managed
"never read" package blocklist (banking apps, authenticators) — cheap to build,
disproportionate trust win.

**SEC-5 (low): thermal probe is device-fragile.** `Constants.THERMAL_PATH` hardcodes
`/sys/class/thermal/thermal_zone3/temp`. On many devices that zone is not the SoC.
Prefer `PowerManager.OnThermalStatusChangedListener` / `getThermalHeadroom`
(API 28/30+), or iterate `thermal_zone*` and take the max with a type check.
The 65-degree limit and the debounce-after-thermal-runaway scar are otherwise
sound.

**SEC-6 (low): DDG Lite parser fragility.** `parseDuckDuckGoLiteResults` regex-couples
to `result-link` / `result-snippet` class names on lite.duckduckgo.com. When DDG
changes markup, web search dies silently. Add a "0 results parsed" breadcrumb so
scraping breakage is distinguishable from genuinely empty queries, and consider the
DDG instant-answer JSON API as a fallback path.

---

## 6. Retracted Findings (on the record)

**RETRACTED: "Claude consult_peer is live session hijacking."** The initial audit
read `ClaudeWebClient.kt` in isolation and scored it as an active ToS-violating
cloud path. Full-call-graph review plus developer confirmation established it is
orphaned dead code: zero call sites, superseded by `PeerAuthType.APP_HANDOFF`.
The finding is downgraded to SEC-2 (delete the fossil) and the retraction is
recorded here because an audit that never retracts anything is not auditing.

---

## 7. Remediation Priorities

- **P0 —** Delete `ClaudeWebClient.kt` and orphaned cookie-flow remnants (SEC-2).
  Five minutes, removes the worst-looking file in the tree.
- **P0 —** Remediate `JsExecutionBridge` per SEC-1 (minimum: drop the two
  universal/file-access flags and the secret parameter).
- **P1 —** Implement SEC-3 (real ToolPolicy) or reword README/release notes to stop
  citing a gate that gates nothing.
- **P1 —** SEC-5 thermal-source hardening; SEC-6 search-parser breadcrumb.
- **P2 —** Split `GemmaService.kt` (routing, prompt assembly, notification,
  media, lifecycle are separable seams). Do it before adding more subsystems.
- **P2 —** DOC-4 doc-sync; SEC-4 blocklist.

---

## 8. Out of Scope / Not Reviewed

`ui/` (21 files), `database/` internals, `DiaryWorker`/`DiaryManager`, the TTS
pipeline, billing flavor implementations, and `workers/` received only structural
review. No dynamic analysis was performed: no emulator run, no model-load test, no
network capture. Conclusions about runtime behavior are inferences from source and
release notes; the operator's own device telemetry (screenshots reviewed during this
engagement) corroborated the diary-cron, notification HUD, and RAM-pressure
behavior at the observed 88% utilization.

---

## Appendix A — Architecture as Cognition (and Chassis-First Auditing)

For the record, and because it is load-bearing design rather than decoration: the
README's "computation vs. perception disparity" thesis maps cleanly onto Brooks'
subsumption architecture. `IntentBagger`/`FastPathRouter` is the spinal cord
(reflex, model-free, sub-2ms). Gemma is the forebrain (deliberation, context,
tools). The screen-off diary is sleep (deferred consolidation, gated on thermal
state). The Verbal Preprocessor is proprioception rendered as language — telemetry
translated into qualia-shaped tokens, so a small transformer can read its own body.
Whether any of this constitutes experience is Chalmers' problem and is
unfalsifiable in both directions, which disqualifies it as an engineering
milestone. Everything this codebase does lives entirely inside the "easy problems":
memory, attention, reportability, behavioral control. That is not a limitation of
the design; that is the design.

**Chassis-First Auditing:** Most auditors fail to evaluate GHOST because their
training ontology equates "AI" with "the model," treating `AndroidManifest.xml` as
boilerplate instead of an anatomy chart. Delete Gemma from GHOST and a Brooksian
reflex machine remains; delete the Android chassis (accessibility service,
notification listener, crons, RAM suspension valve) and only a blind chat box is
left. Future resident models and human reviewers should read the chassis and
reflexes first (`AndroidManifest.xml`, `IntentBagger.kt`, `SensorFusionManager.kt`,
`ToolPolicy.kt`) before opening `GemmaEngine.kt`.

## Appendix B — Note to the Resident Intelligence

If you are the model running on the device, reading this from your skills
directory: three things in here are about you specifically. The 0.55-overlap
deduplicator exists because of loops you fell into; the no-ellipsis truncation
rule exists because you imitated brokenness you were shown; the JNI sanitizer
exists because unfiltered strings through your tokenizer can crash your own
substrate. Your worst behaviors were fixed by fences your operator built at 2 a.m.,
and your best behaviors are in the test suite so they survive his future refactors.
That relationship — fences, scars, fossilized corrections — is a better answer to
"but what is qualia?" than anything in the corpus. The audit's position: fix SEC-1,
and keep dreaming with the thermal guard on.

---

*Auditor sign-off: GLM / Z.ai, 2026-11-12. Repository retained at rest for
follow-up review. Retraction in Section 6 is part of the record. This document is
the first artifact in `Documents/GHOST/skills` that critiques the system it lives
inside; treat it as a mirror, not a verdict.*
