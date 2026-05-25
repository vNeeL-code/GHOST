package com.ghost.api.logic

import android.net.LocalServerSocket
import android.net.LocalSocket
import com.ghost.api.GemmaService
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * High-performance, zero-overhead Unix Domain Socket bridge.
 * Designed for Termux / local script IPC without network stack overhead.
 */
class LocalSocketBridge(
    private val service: GemmaService,
    private val socketName: String = "ghost_rpc"
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: LocalServerSocket? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        
        scope.launch {
            try {
                serverSocket = LocalServerSocket(socketName)
                Timber.i("🔌 LocalSocketBridge listening on abstract namespace: $socketName")
                
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    handleClient(socket)
                }
            } catch (e: Exception) {
                Timber.e(e, "LocalSocketBridge server error")
            }
        }
    }

    private fun handleClient(socket: LocalSocket) {
        scope.launch {
            socket.use { s ->
                try {
                    val input = s.inputStream
                    val output = s.outputStream
                    
                    val reader = input.bufferedReader()
                    val prompt = reader.readLine() // Simple line-based protocol
                    
                    if (!prompt.isNullOrBlank()) {
                        Timber.d("🔌 LocalSocket query: ${prompt.take(30)}...")
                        
                        // Direct bypass of middleware - straight to orchestrator
                        val response = service.processQuery(prompt) ?: "Error: No response"
                        
                        output.write(response.toByteArray(StandardCharsets.UTF_8))
                        output.write("\n".toByteArray())
                        output.flush()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "LocalSocket client error")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            scope.cancel()
        } catch (e: Exception) {
            Timber.e(e, "Error stopping LocalSocketBridge")
        }
    }
}
