package com.ghost.api

import com.ghost.api.database.MemoryManager
import com.ghost.api.GemmaNotificationListener
import com.google.gson.Gson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.cio.*
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

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val gson = Gson()

    fun start() {
        server = embeddedServer(CIO, port = Constants.API_PORT) {
            routing {

                fun ApplicationCall.isAuthorized(): Boolean {
                    val header = request.header("X-Ghost-Token")
                        ?: request.header("Authorization")?.removePrefix("Bearer ")?.trim()
                    return header == Constants.API_TOKEN
                }

                get("/v1/models") {
                    if (!call.isAuthorized()) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing X-Ghost-Token"))
                        return@get
                    }
                    val models = mapOf("object" to "list", "data" to listOf(
                        mapOf("id" to "gemma", "object" to "model", "owned_by" to "ghost-local", "created" to 0)
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
                        val prompt = parsed["prompt"] as? String ?: ""
                        val sessionId = parsed["session_id"] as? String ?: UUID.randomUUID().toString()

                        val aiResponse = withContext(Dispatchers.Default) {
                            gemmaService.processQuery(prompt, sessionId) ?: "Error: No response generated"
                        }

                        val jsonResponse = gson.toJson(mapOf(
                            "response" to aiResponse,
                            "session_id" to sessionId
                        ))
                        call.respondText(jsonResponse, ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                
                post("/v1/chat/completions") {
                    if (!call.isAuthorized()) {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized"))
                        return@post
                    }
                    try {
                        val request = call.receiveText()
                        val parsed = gson.fromJson(request, Map::class.java)
                        val messages = (parsed["messages"] as? List<Map<String, Any>>) ?: emptyList()
                        val stream = parsed["stream"] as? Boolean ?: false
                        val prompt = messages.lastOrNull()?.get("content")?.toString() ?: ""
                        val modelId = parsed["model"]?.toString() ?: "gemma"
                        val completionId = "chatcmpl-${UUID.randomUUID()}"
                        val created = System.currentTimeMillis() / 1000

                        if (stream) {
                            call.response.header(HttpHeaders.ContentType, "text/event-stream")
                            call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                                val tokenChannel = KChannel<String>(capacity = 256)
                                launch(Dispatchers.Default) {
                                    try {
                                        gemmaService.streamQueryTokens(prompt) { token ->
                                            tokenChannel.trySend(token)
                                        }
                                    } finally { tokenChannel.close() }
                                }
                                for (token in tokenChannel) {
                                    val chunk = gson.toJson(mapOf(
                                        "id" to completionId, "object" to "chat.completion.chunk",
                                        "created" to created, "model" to modelId,
                                        "choices" to listOf(mapOf("index" to 0, "delta" to mapOf("content" to token), "finish_reason" to null))
                                    ))
                                    write("data: $chunk\n\n"); flush()
                                }
                                write("data: [DONE]\n\n"); flush()
                            }
                        } else {
                            val aiResponse = withContext(Dispatchers.Default) {
                                gemmaService.processQuery(prompt, "api_session") ?: "Error"
                            }
                            val responseMap = mapOf(
                                "id" to completionId, "object" to "chat.completion",
                                "created" to created, "model" to modelId,
                                "choices" to listOf(mapOf("index" to 0, "message" to mapOf("role" to "assistant", "content" to aiResponse), "finish_reason" to "stop"))
                            )
                            call.respondText(gson.toJson(responseMap), ContentType.Application.Json)
                        }
                    } catch (e: Exception) {
                        call.respondText(gson.toJson(mapOf("error" to e.message)), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
                
                get("/api/status") {
                    val status = mapOf(
                        "timestamp" to System.currentTimeMillis(),
                        "model_loaded" to gemmaService.isGemmaLoaded(),
                        "api_port" to Constants.API_PORT
                    )
                    call.respondText(gson.toJson(status), ContentType.Application.Json)
                }

                post("/api/reset_memory") {
                    gemmaService.resetMemory()
                    call.respondText(gson.toJson(mapOf("success" to true)), ContentType.Application.Json)
                }

                get("/health") { call.respondText("OK") }
                get("/") { call.respondText("GHOST API Server Active", ContentType.Text.Plain) }
            }
        }
        server?.start(wait = false)
        Timber.i("API server started")
    }

    fun stop() { server?.stop(100, 100) }
}
