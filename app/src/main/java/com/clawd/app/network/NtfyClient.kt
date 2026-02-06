package com.clawd.app.network

import android.util.Log
import com.clawd.app.data.ClawdConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

@Serializable
data class NtfyMessage(
    val id: String,
    val time: Long,
    val event: String,
    val topic: String,
    val title: String? = null,
    val message: String? = null,
    val priority: Int = 3
)

class NtfyClient(
    private val topic: String,
    private val server: String = ClawdConfig.NTFY_SERVER
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val _messages = MutableSharedFlow<NtfyMessage>(replay = 0, extraBufferCapacity = 16)
    val messages: SharedFlow<NtfyMessage> = _messages.asSharedFlow()

    fun connect() {
        val url = "$server/$topic/ws"
        Log.d(TAG, "Connecting to ntfy: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "ntfy WebSocket opened")
                reconnectAttempts = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "ntfy message: $text")
                try {
                    val msg = json.decodeFromString<NtfyMessage>(text)
                    if (msg.event == "message") {
                        scope.launch {
                            _messages.emit(msg)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing ntfy message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "ntfy WebSocket failure", t)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "ntfy WebSocket closed: $code $reason")
                if (code != 1000) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= 10) {
            Log.w(TAG, "Max reconnect attempts reached for ntfy")
            return
        }

        val delay = minOf(
            1000L * (1 shl reconnectAttempts),
            60000L
        )
        reconnectAttempts++

        scope.launch {
            Log.d(TAG, "Reconnecting to ntfy in ${delay}ms")
            delay(delay)
            connect()
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnected")
        webSocket = null
    }

    companion object {
        private const val TAG = "NtfyClient"
    }
}
