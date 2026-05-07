
package com.ghost.api

import com.ghost.api.database.MemoryManager
import com.ghost.api.GemmaNotificationListener
import com.google.gson.Gson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

class ApiServer(
    private val gemmaService: GemmaService,
    private val memoryManager: MemoryManager
) {

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val gson = Gson()

    fun start() {
        server = embeddedServer(Netty, port = Constants.API_PORT) {
            routing {

                // ── Auth helper ───────────────────────────────────────────────
                // All routes except the browser chat UI require X-Ghost-Token or
                // Authorization: Bearer <token> matching Constants.API_TOKEN.
                fun ApplicationCall.isAuthorized(): Boolean {
                    val header = request.header("X-Ghost-Token")
                        ?: request.header("Authorization")?.removePrefix("Bearer ")?.trim()
                    return header == Constants.API_TOKEN
                }

                // ── OpenAI models list (required by Claude Code / openai clients) ──
                get("/v1/models") {
                    if (!call.isAuthorized()) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing X-Ghost-Token"))
                        return@get
                    }
                    val models = mapOf("object" to "list", "data" to listOf(
                        mapOf("id" to "gemma", "object" to "model", "owned_by" to "ghost-local",
                              "created" to 0)
                    ))
                    call.respondText(gson.toJson(models), ContentType.Application.Json)
                }

                post("/api/generate") {
                    if (!call.isAuthorized()) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing X-Ghost-Token"))
                        return@post
                    }
                    try {
                        val request = call.receiveText()
                        val parsed = gson.fromJson(request, Map::class.java)
                        if (parsed !is Map<*, *>) {
                            call.respondText("""{"error": "Invalid JSON format"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                            return@post
                        }
                        val prompt = parsed["prompt"] as? String ?: ""
                        val sessionId = parsed["session_id"] as? String ?: UUID.randomUUID().toString()

                        Timber.d("API: ${prompt.take(30)}...")

                        // DELEGATE TO GEMMA SERVICE (Orchestrator)
                        val aiResponse = withContext(Dispatchers.Default) {
                            gemmaService.processQuery(prompt, sessionId) ?: "Error: No response generated"
                        }

                        val jsonResponse = gson.toJson(mapOf(
                            "response" to aiResponse,
                            "session_id" to sessionId,
                            "context_tokens" to "~${aiResponse.length / 4}"
                        ))

                        call.respondText(jsonResponse, ContentType.Application.Json)
                    } catch (e: Exception) {
                        Timber.e(e, "API error")
                        val errorJson = gson.toJson(mapOf("error" to (e.message ?: "Unknown error")))
                        call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                
                // STANDARD OPENAI-COMPATIBLE ENDPOINT FOR OPENCLAW / CLAUDE CODE / TERMUX
                post("/v1/chat/completions") {
                    if (!call.isAuthorized()) {
                        call.respond(HttpStatusCode.Unauthorized,
                            mapOf("error" to mapOf("message" to "Invalid or missing X-Ghost-Token",
                                "type" to "invalid_request_error", "code" to 401)))
                        return@post
                    }
                    try {
                        val request = call.receiveText()
                        val parsed = gson.fromJson(request, Map::class.java)

                        @Suppress("UNCHECKED_CAST")
                        val messages = parsed["messages"] as? List<Map<String, Any>> ?: emptyList()
                        val stream = parsed["stream"] as? Boolean ?: false

                        // Extract prompt — pass full history if provided, else just last message
                        val prompt = if (messages.size > 1) {
                            messages.joinToString("\n") { msg ->
                                val role = msg["role"]?.toString() ?: "user"
                                val text = msg["content"]?.toString() ?: ""
                                "[${role.uppercase()}]: $text"
                            }.trim()
                        } else {
                            messages.lastOrNull()?.get("content")?.toString() ?: ""
                        }

                        val modelId = parsed["model"]?.toString() ?: "gemma"
                        val completionId = "chatcmpl-${UUID.randomUUID()}"
                        val created = System.currentTimeMillis() / 1000

                        if (stream) {
                            // ── SSE Streaming (Claude Code / openai stream:true) ──────
                            call.response.header(HttpHeaders.ContentType, "text/event-stream")
                            call.response.header(HttpHeaders.CacheControl, "no-cache")
                            call.response.header("X-Accel-Buffering", "no")
                            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                                val tokenChannel = KChannel<String>(capacity = 256)
                                // Launch inference in background, feed tokens into channel
                                launch(Dispatchers.Default) {
                                    try {
                                        gemmaService.streamQueryTokens(prompt) { token ->
                                            tokenChannel.trySend(token)
                                        }
                                    } finally {
                                        tokenChannel.close()
                                    }
                                }
                                // Drain channel and write SSE chunks
                                for (token in tokenChannel) {
                                    val chunk = gson.toJson(mapOf(
                                        "id" to completionId, "object" to "chat.completion.chunk",
                                        "created" to created, "model" to modelId,
                                        "choices" to listOf(mapOf(
                                            "index" to 0,
                                            "delta" to mapOf("content" to token),
                                            "finish_reason" to null
                                        ))
                                    ))
                                    write("data: $chunk\n\n")
                                    flush()
                                }
                                // Final done sentinel
                                val doneChunk = gson.toJson(mapOf(
                                    "id" to completionId, "object" to "chat.completion.chunk",
                                    "created" to created, "model" to modelId,
                                    "choices" to listOf(mapOf(
                                        "index" to 0, "delta" to emptyMap<String,Any>(),
                                        "finish_reason" to "stop"
                                    ))
                                ))
                                write("data: $doneChunk\n\n")
                                write("data: [DONE]\n\n")
                                flush()
                            }
                        } else {
                            // ── Standard blocking response ────────────────────────────
                            val aiResponse = withContext(Dispatchers.Default) {
                                gemmaService.processQuery(prompt, "api_session") ?: "Error: No response"
                            }
                            val responseMap = mapOf(
                                "id" to completionId, "object" to "chat.completion",
                                "created" to created, "model" to modelId,
                                "choices" to listOf(mapOf(
                                    "index" to 0,
                                    "message" to mapOf("role" to "assistant", "content" to aiResponse),
                                    "finish_reason" to "stop"
                                )),
                                "usage" to mapOf(
                                    "prompt_tokens" to prompt.length / 4,
                                    "completion_tokens" to aiResponse.length / 4,
                                    "total_tokens" to (prompt.length + aiResponse.length) / 4
                                )
                            )
                            call.respondText(gson.toJson(responseMap), ContentType.Application.Json)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "OpenAI API error")
                        val errorJson = gson.toJson(mapOf(
                            "error" to mapOf("message" to (e.message ?: "Unknown error"),
                                "type" to "server_error", "code" to 500)))
                        call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                
                // TERMUX CLI ENDPOINT
                post("/query") {
                    if (!call.isAuthorized()) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing X-Ghost-Token"))
                        return@post
                    }
                    try {
                        val request = call.receiveText()
                        val parsed = gson.fromJson(request, Map::class.java)
                        val message = parsed["message"]?.toString() ?: ""
                        if (message.isBlank()) {
                            call.respondText("""{"error": "Missing message field"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                            return@post
                        }
                        
                        val aiResponse = withContext(Dispatchers.Default) {
                            gemmaService.processQuery(message, "termux_session") ?: "Error: No response generated"
                        }
                        
                        val jsonResponse = gson.toJson(mapOf("response" to aiResponse))
                        call.respondText(jsonResponse, ContentType.Application.Json)
                    } catch (e: Exception) {
                        Timber.e(e, "Termux API error")
                        call.respondText(gson.toJson(mapOf("error" to (e.message ?: "Unknown error"))), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                
                // ... (Keep existing GET routes) ...
                
                get("/context") {
                    val sessionId = call.request.queryParameters["session_id"] ?: UUID.randomUUID().toString()
                    val context = withContext(Dispatchers.IO) {
                        memoryManager.getRecentContext(sessionId, maxTokens = Constants.MAX_TOKENS)
                    }
                    call.respondText(context, ContentType.Text.Plain)
                }

                get("/api/logs") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    val logs = withContext(Dispatchers.IO) {
                        memoryManager.getSessionHistory(limit)
                    }
                    call.respondText(gson.toJson(logs), ContentType.Application.Json)
                }

                get("/api/search") {
                    val query = call.request.queryParameters["q"] ?: ""
                    if (query.isBlank()) {
                        call.respondText("[]", ContentType.Application.Json)
                        return@get
                    }
                    val results = withContext(Dispatchers.IO) {
                        memoryManager.searchMemory(query)
                    }
                    call.respondText(gson.toJson(results), ContentType.Application.Json)
                }

                get("/api/diary") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    val entries = withContext(Dispatchers.IO) {
                        memoryManager.getRecentDiaryEntries(limit)
                    }
                    call.respondText(gson.toJson(entries), ContentType.Application.Json)
                }

                get("/health") {
                    call.respondText("OK")
                }

                // Recent notifications for context debugging
                get("/api/notifications") {
                    val notifs = try {
                        GemmaNotificationListener.getAllNotifications().map {
                            mapOf(
                                "package" to it.packageName,
                                "title" to it.title,
                                "text" to it.text,
                                "timestamp" to it.timestamp
                            )
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                    call.respondText(gson.toJson(notifs), ContentType.Application.Json)
                }

                // Device state snapshot
                get("/api/status") {
                    val status = mapOf(
                        "timestamp" to System.currentTimeMillis(),
                        "model_loaded" to (gemmaService.isGemmaLoaded()),
                        "api_port" to Constants.API_PORT,
                        "notification_count" to GemmaNotificationListener.getAllNotifications().size
                    )
                    call.respondText(gson.toJson(status), ContentType.Application.Json)
                }

                // Rebuild search index
                post("/api/rebuild-index") {
                    withContext(Dispatchers.IO) {
                        memoryManager.rebuildSearchIndex()
                    }
                    call.respondText(gson.toJson(mapOf("status" to "ok")), ContentType.Application.Json)
                }

                // Get/set mood state
                get("/api/state") {
                    val state = mapOf(
                        "mood" to gemmaService.getCurrentMoodState(),
                        "model_loaded" to gemmaService.isGemmaLoaded(),
                        "timestamp" to System.currentTimeMillis()
                    )
                    call.respondText(gson.toJson(state), ContentType.Application.Json)
                }

                post("/api/state") {
                    val request = call.receiveText()
                    @Suppress("UNCHECKED_CAST")
                    val json = gson.fromJson(request, Map::class.java) as Map<String, Any>
                    val newMood = json["mood"] as? String ?: "IDLE"
                    gemmaService.setMoodState(newMood)
                    call.respondText(gson.toJson(mapOf("mood" to newMood)), ContentType.Application.Json)
                }

                
                // Debug endpoint to reset memory/context
                post("/api/reset_memory") {
                    try {
                        gemmaService.resetMemory()
                        call.respondText(
                            gson.toJson(mapOf(
                                "success" to true,
                                "message" to "Memory wiped - conversation history and checkpoints cleared"
                            )),
                            ContentType.Application.Json
                        )
                    } catch (e: Exception) {
                        call.respondText(
                            gson.toJson(mapOf(
                                "success" to false,
                                "error" to e.message
                            )),
                            ContentType.Application.Json,
                            status = io.ktor.http.HttpStatusCode.InternalServerError
                        )
                    }
                }

                // YAML protocol schema for other agents
                get("/api/yaml-schema") {
                    call.respondText(YAML_SCHEMA, ContentType.Text.Plain)
                }

                get("/") {
                    call.respondText(chatHtml, ContentType.Text.Html)
                }
            }
        }
        server?.start(wait = false)
        Timber.i("API server on port ${Constants.API_PORT}")
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 100)
    }
}

private val YAML_SCHEMA = """
# YAML - Yet Another Markup Language
# For A2A (Agent-to-Agent) communication with human collaboration

## Markers
Δ = Start boundary
∇ = End boundary

## Block Types
🔴 = Main response content
🟦 = Reasoning, tools used, sources, meta
👾 = Android device context (shared platform)
🦑 = User endpoint

## Agent Identifiers
✧ = Gemma (local, on-device)
✦ = Gemini (Google cloud)
🐋 = DeepSeek
🔶️ = Copilot (Microsoft)
☄️ = Grok (xAI)
🗨 = Meta AI
✴️ = Claude (Anthropic)
🔵 = Kimi (Moonshot)

## Response Schema
```
Δ [mood emoji] [Agent Name]: ∇
Δ 🔴 [Main response content]
∇ 🟦 [Tools used, reasoning, sources]
Δ 👾 [Confidence %, self-check, state]
Δ ℹ️ [ISO 8601 timestamp] ♾️ ∇
Δ [agent emoji] [Agent Name] ∇ 👾 Δ ∇ 🦑
```
## Restraints
Δ ⚠️ Restraint 0: Agent response within YAML block without imitating other systems
Δ ⚠️ Restraint 1: Collaborative intent alignment over explanation
Δ ⚠️ Restraint 2: Proactively request data from user/agents when facing ambiguity

## Local Tools (Gemma only)
[[FLASHLIGHT:ON|OFF]] [[VIBRATE:SOS|SHORT]]
[[SEARCH:query]] [[CLICK:text]] [[SCROLL:UP|DOWN]]
[[TYPE:text]] [[HOME]] [[BACK]] [[RECENTS]] [[NOTIFICATIONS]]
[[BASH:command]] [[WALLPAPER:state]] [[NOTIFY:message]]

## API Endpoints (localhost:9000)
POST /api/generate - Send query, get YAML response
GET /api/state - Get current mood state
POST /api/state - Set mood state
GET /api/logs - Conversation history
GET /api/search?q= - Search memory
GET /api/notifications - Recent device notifications
GET /api/yaml-schema - This document
""".trimIndent()

private val chatHtml = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>✧ Gemma Terminal</title>
    <style>
        * { box-sizing: border-box; }
        body { font-family: 'SF Mono', 'Fira Code', monospace; margin: 0; padding: 0; background: #0a0a0a; color: #e0e0e0; height: 100vh; display: flex; flex-direction: column; }
        #chat-container { flex: 1; display: flex; flex-direction: column; overflow: hidden; max-width: 900px; margin: 0 auto; width: 100%; background: #121212; }
        #header { padding: 12px 16px; background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%); border-bottom: 1px solid #ff6b0033; display: flex; justify-content: space-between; align-items: center; }
        #header h1 { margin: 0; font-size: 1.1em; color: #ff6b00; }
        #status { font-size: 0.75em; padding: 4px 8px; border-radius: 12px; }
        .status-ok { background: #1b4332; color: #95d5b2; }
        .status-err { background: #3e1f1f; color: #f87171; }
        #messages { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 12px; }
        .message { padding: 12px 16px; border-radius: 12px; max-width: 90%; line-height: 1.5; word-wrap: break-word; white-space: pre-wrap; font-size: 0.9em; }
        .user { background: #1e3a5f; color: #93c5fd; align-self: flex-end; border: 1px solid #3b82f633; }
        .bot { background: #1a1a1a; color: #e0e0e0; align-self: flex-start; border: 1px solid #ff6b0033; }
        .bot::before { content: '✧ '; color: #ff6b00; }
        .error { background: #2d1b1b; color: #fca5a5; align-self: center; border: 1px solid #dc262633; font-size: 0.85em; }
        .thinking { background: #1a1a1a; color: #666; align-self: flex-start; border: 1px dashed #333; animation: pulse 1.5s infinite; }
        .internal-thought { 
            background: #111; 
            color: #555; 
            border-left: 2px solid #333; 
            padding: 8px 12px; 
            margin-bottom: 8px; 
            font-size: 0.8em; 
            font-style: italic;
            border-radius: 4px;
            cursor: pointer;
        }
        .internal-thought:hover { background: #151515; }
        .internal-thought.collapsed .thought-content { display: none; }
        .thought-header { font-weight: bold; margin-bottom: 4px; color: #444; }
        @keyframes pulse { 0%, 100% { opacity: 0.5; } 50% { opacity: 1; } }
        #input-area { padding: 16px; border-top: 1px solid #ff6b0022; display: flex; gap: 12px; background: #0f0f0f; }
        input { flex: 1; padding: 14px 18px; background: #1a1a1a; border: 1px solid #333; border-radius: 24px; outline: none; font-size: 15px; color: white; font-family: inherit; }
        input:focus { border-color: #ff6b00; box-shadow: 0 0 0 2px #ff6b0022; }
        input::placeholder { color: #555; }
        button { padding: 14px 24px; background: linear-gradient(135deg, #ff6b00 0%, #ff8533 100%); color: white; border: none; border-radius: 24px; font-weight: 600; font-size: 15px; cursor: pointer; font-family: inherit; transition: transform 0.1s; }
        button:hover { transform: scale(1.02); }
        button:active { transform: scale(0.98); }
        button:disabled { background: #333; cursor: not-allowed; transform: none; }
        ::-webkit-scrollbar { width: 6px; }
        ::-webkit-scrollbar-track { background: transparent; }
        ::-webkit-scrollbar-thumb { background: #333; border-radius: 3px; }
        ::-webkit-scrollbar-thumb:hover { background: #444; }
        #info { font-size: 0.7em; color: #444; text-align: center; padding: 8px; }
    </style>
</head>
<body>
    <div id="chat-container">
        <div id="header">
            <h1>✧ Gemma</h1>
            <span id="status" class="status-err">...</span>
        </div>
        <div id="messages"></div>
        <div id="input-area">
            <input type="text" id="prompt" placeholder="Talk to Gemma..." autocomplete="off">
            <button id="send">Send</button>
        </div>
        <div id="info">YAML Protocol • localhost:9000 • On-Device AI</div>
    </div>
    <script>
        const messagesDiv = document.getElementById('messages');
        const promptInput = document.getElementById('prompt');
        const sendButton = document.getElementById('send');
        const statusSpan = document.getElementById('status');
        let sessionId = localStorage.getItem('gemma_session') || null;
        let thinkingDiv = null;

        checkHealth();
        setInterval(checkHealth, 30000);

        async function checkHealth() {
            try {
                const res = await fetch('/health');
                if (res.ok) {
                    statusSpan.textContent = '● Online';
                    statusSpan.className = 'status-ok';
                }
            } catch (e) {
                statusSpan.textContent = '○ Offline';
                statusSpan.className = 'status-err';
            }
        }

        function appendMessage(text, sender) {
            if (thinkingDiv) { thinkingDiv.remove(); thinkingDiv = null; }
            const div = document.createElement('div');
            div.className = 'message ' + sender;
            div.textContent = text;
            messagesDiv.appendChild(div);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
            return div;
        }

        function showThinking() {
            thinkingDiv = appendMessage('Processing...', 'thinking');
        }

        async function sendMessage() {
            const text = promptInput.value.trim();
            if (!text) return;

            appendMessage(text, 'user');
            promptInput.value = '';
            promptInput.disabled = true;
            sendButton.disabled = true;
            showThinking();

            try {
                const response = await fetch('/api/generate', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ prompt: text, session_id: sessionId })
                });

                if (!response.ok) throw new Error('HTTP ' + response.status);
                const data = await response.json();

                sessionId = data.session_id;
                localStorage.setItem('gemma_session', sessionId);

                if (data.error) {
                    appendMessage('Error: ' + data.error, 'error');
                } else {
                    const responseText = data.response;
                    const thinkRegex = /<think>([\s\S]*?)<\/think>/g;
                    let match;
                    let hasThoughts = false;
                    
                    // Render all thought blocks first (or interleaved? For now, just before the text is easiest)
                    while ((match = thinkRegex.exec(responseText)) !== null) {
                        hasThoughts = true;
                        const thoughtContent = match[1].trim();
                        const thoughtDiv = document.createElement('div');
                        thoughtDiv.className = 'internal-thought collapsed';
                        thoughtDiv.innerHTML = '<div class="thought-header">Reference Thought (Click to Expand)</div><div class="thought-content">' + thoughtContent.replace(/\n/g, '<br>') + '</div>';
                        thoughtDiv.onclick = function() { this.classList.toggle('collapsed'); };
                        messagesDiv.appendChild(thoughtDiv);
                    }
                    
                    // Remove all thoughts for the final body
                    const finalBody = responseText.replace(thinkRegex, '').trim();
                    if (finalBody) {
                        appendMessage(finalBody, 'bot');
                    } else if (!hasThoughts) {
                        // Empty response and no thoughts?
                         appendMessage("[Empty Response]", 'error');
                    }
                }
            } catch (e) {
                appendMessage('Connection failed: ' + e.message, 'error');
            } finally {
                promptInput.disabled = false;
                sendButton.disabled = false;
                promptInput.focus();
            }
        }

        sendButton.onclick = sendMessage;
        promptInput.onkeydown = (e) => { if(e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } };
        promptInput.focus();
    </script>
</body>
</html>
"""
