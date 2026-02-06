package com.clawd.app.network

import android.content.Context
import android.util.Log
import com.clawd.app.BuildConfig
import com.clawd.app.data.ClawdConfig
import com.clawd.app.data.DeviceIdentity
import com.clawd.app.data.SecureStorage
import com.clawd.app.network.protocol.ChatMessage
import com.clawd.app.network.protocol.ConnectionState
import com.clawd.app.network.protocol.GatewayEvent
import com.clawd.app.network.protocol.MessageFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ClawdClient(
    private val context: Context,
    private val gatewayUrl: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceIdentity = DeviceIdentity.loadOrCreate(context)

    private var webSocket: WebSocket? = null
    private var deviceToken: String? = null
    private var pingJob: Job? = null
    private var reconnectAttempts = 0

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(ClawdConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
        .writeTimeout(ClawdConfig.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    private val _events = MutableSharedFlow<GatewayEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Current streaming message being built
    private var currentStreamingContent = StringBuilder()
    private var currentRunId: String? = null
    private var textCompleteEmitted = false

    fun connect(initialToken: String? = null) {
        if (_connectionState.value == ConnectionState.Connecting ||
            _connectionState.value == ConnectionState.Ready) {
            return
        }

        deviceToken = initialToken ?: SecureStorage.getDeviceToken(context)
        _connectionState.value = ConnectionState.Connecting

        val wsUrl = gatewayUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .trimEnd('/')

        Log.d(TAG, "Connecting to: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                _connectionState.value = ConnectionState.Connected
                reconnectAttempts = 0
                startPingJob()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                try {
                    val jsonObj = json.parseToJsonElement(text).jsonObject
                    handleMessage(jsonObj)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                stopPingJob()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _connectionState.value = ConnectionState.Disconnected
                stopPingJob()
                if (code != 1000) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun handleMessage(json: JsonObject) {
        when (json["type"]?.jsonPrimitive?.content) {
            "event" -> handleEvent(json)
            "res" -> handleResponse(json)
        }
    }

    private fun handleEvent(json: JsonObject) {
        val event = json["event"]?.jsonPrimitive?.content ?: return
        val payload = json["payload"]?.jsonObject

        Log.d(TAG, "Event: $event")

        when (event) {
            "connect.challenge" -> payload?.let { handleChallenge(it) }
            "device.paired" -> payload?.let { handlePaired(it) }
            "chat" -> payload?.let { handleChatEvent(it) }
        }
    }

    private fun handleChallenge(payload: JsonObject) {
        val nonce = payload["nonce"]?.jsonPrimitive?.content ?: return
        val ts = payload["ts"]?.jsonPrimitive?.long ?: System.currentTimeMillis()

        Log.d(TAG, "Received challenge, signing with device: ${deviceIdentity.deviceId.take(16)}...")

        val signed = deviceIdentity.signChallenge(
            nonce = nonce,
            signedAtMs = ts,
            token = deviceToken ?: ""
        )

        val connectParams = buildJsonObject {
            put("minProtocol", ClawdConfig.PROTOCOL_VERSION)
            put("maxProtocol", ClawdConfig.PROTOCOL_VERSION)
            putJsonObject("client") {
                put("id", "clawdbot-android")
                put("version", BuildConfig.VERSION_NAME)
                put("platform", "android")
                put("mode", "webchat")
            }
            put("role", "operator")
            putJsonArray("scopes") {
                add("operator.read")
                add("operator.write")
            }
            putJsonArray("caps") {}
            putJsonArray("commands") {}
            putJsonObject("permissions") {}
            putJsonObject("auth") {
                put("token", deviceToken ?: "")
            }
            put("locale", Locale.getDefault().toLanguageTag())
            put("userAgent", "clawvoice/${BuildConfig.VERSION_NAME}")
            putJsonObject("device") {
                put("id", deviceIdentity.deviceId)
                put("publicKey", deviceIdentity.publicKeyBase64)
                put("signature", signed.signature)
                put("signedAt", signed.signedAt)
                put("nonce", signed.nonce)
            }
        }

        scope.launch {
            try {
                val response = sendRequest("connect", connectParams)
                val ok = response["ok"]?.jsonPrimitive?.boolean ?: false

                if (ok) {
                    val authPayload = response["payload"]?.jsonObject?.get("auth")?.jsonObject
                    val newToken = authPayload?.get("deviceToken")?.jsonPrimitive?.contentOrNull
                    if (newToken != null) {
                        deviceToken = newToken
                        SecureStorage.saveDeviceToken(context, newToken)
                    }
                    _connectionState.value = ConnectionState.Ready
                    Log.d(TAG, "Connected and authenticated!")

                    // Fetch history after connecting
                    fetchHistory()
                } else {
                    val errorCode = response["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
                    if (errorCode == "pairing_required") {
                        Log.d(TAG, "Pairing required")
                        _connectionState.value = ConnectionState.WaitingForPairing
                        _events.emit(GatewayEvent.PairingRequired)
                    } else {
                        val errorMsg = response["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                        _connectionState.value = ConnectionState.Error(errorMsg ?: "Connection failed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connect request failed", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Connect failed")
            }
        }
    }

    private fun handlePaired(payload: JsonObject) {
        val newToken = payload["deviceToken"]?.jsonPrimitive?.contentOrNull
        if (newToken != null) {
            deviceToken = newToken
            SecureStorage.saveDeviceToken(context, newToken)
            _connectionState.value = ConnectionState.Ready
            Log.d(TAG, "Device paired successfully!")

            scope.launch {
                _events.emit(GatewayEvent.Paired(newToken))
                fetchHistory()
            }
        }
    }

    private fun handleChatEvent(payload: JsonObject) {
        val sessionKey = payload["sessionKey"]?.jsonPrimitive?.contentOrNull ?: "main"
        val state = payload["state"]?.jsonPrimitive?.contentOrNull ?: "delta"
        val runId = payload["runId"]?.jsonPrimitive?.contentOrNull

        // Extract text from message.content array (only "text" type blocks)
        val messageObj = payload["message"]?.jsonObject
        val contentArray = messageObj?.get("content")?.jsonArray
        val textContent = contentArray
            ?.mapNotNull { it.jsonObject }
            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.firstOrNull()
            ?.get("text")?.jsonPrimitive?.contentOrNull

        Log.d(TAG, "handleChatEvent: state=$state, runId=$runId, text=$textContent")

        scope.launch {
            when (state) {
                "delta" -> {
                    // Only update if we have non-empty text (don't overwrite with empty)
                    if (!textContent.isNullOrBlank()) {
                        // Filter protocol markers
                        val processed = MessageFilter.processAssistantMessage(textContent)
                        if (!processed.shouldDisplay) {
                            Log.d(TAG, "Filtered out protocol marker: ${textContent.take(20)}")
                            return@launch
                        }

                        val displayText = processed.text.ifEmpty { textContent }

                        if (runId != currentRunId) {
                            // New response starting
                            currentRunId = runId
                            currentStreamingContent = StringBuilder()
                            textCompleteEmitted = false
                        }

                        // For delta, the text is cumulative, so replace entirely
                        currentStreamingContent = StringBuilder(displayText)

                        // Update messages with streaming content
                        val currentMessages = _messages.value.toMutableList()
                        val lastMessage = currentMessages.lastOrNull()
                        if (lastMessage?.role == "assistant") {
                            currentMessages[currentMessages.lastIndex] = lastMessage.copy(
                                content = displayText
                            )
                        } else {
                            currentMessages.add(ChatMessage(
                                role = "assistant",
                                content = displayText,
                                ts = System.currentTimeMillis()
                            ))
                        }
                        _messages.value = currentMessages
                    } else if (currentStreamingContent.isNotEmpty() && !textCompleteEmitted) {
                        // Empty text after having content = text is complete, trigger TTS early
                        textCompleteEmitted = true
                        val finalContent = currentStreamingContent.toString()
                        val processed = MessageFilter.processAssistantMessage(finalContent)
                        Log.d(TAG, "Text complete (empty delta), emitting textComplete")
                        _events.emit(GatewayEvent.Chat(
                            sessionKey = sessionKey,
                            type = "textComplete",
                            delta = null,
                            runId = runId,
                            content = processed.text
                        ))
                    } else if (runId != currentRunId) {
                        // New response with empty text - likely server TTS bug
                        // Just track the runId but don't create empty message
                        currentRunId = runId
                        currentStreamingContent = StringBuilder()
                        textCompleteEmitted = false
                        Log.d(TAG, "Empty text response started (server TTS bug?), runId=$runId")
                    }
                }
                "final" -> {
                    // Response complete - use text from final event (complete), fallback to accumulated
                    val finalContent = if (!textContent.isNullOrBlank()) textContent else currentStreamingContent.toString()
                    val processed = MessageFilter.processAssistantMessage(finalContent)

                    // Also update the displayed message with final complete text
                    if (processed.text.isNotBlank()) {
                        val currentMessages = _messages.value.toMutableList()
                        val lastMessage = currentMessages.lastOrNull()
                        if (lastMessage?.role == "assistant") {
                            currentMessages[currentMessages.lastIndex] = lastMessage.copy(content = processed.text)
                            _messages.value = currentMessages
                        }
                    }
                    currentRunId = null
                    currentStreamingContent = StringBuilder()
                    textCompleteEmitted = false

                    // Handle empty response (server TTS bug - audio only, no text)
                    if (processed.text.isBlank()) {
                        Log.d(TAG, "Empty final response (server TTS bug), skipping display")
                        // Remove any empty assistant message that might have been added
                        val currentMessages = _messages.value.toMutableList()
                        val lastMessage = currentMessages.lastOrNull()
                        if (lastMessage?.role == "assistant" && lastMessage.content.isBlank()) {
                            currentMessages.removeAt(currentMessages.lastIndex)
                            _messages.value = currentMessages
                        }
                        return@launch
                    }

                    _events.emit(GatewayEvent.Chat(
                        sessionKey = sessionKey,
                        type = state,
                        delta = null,
                        runId = runId,
                        content = processed.text
                    ))
                    return@launch
                }
            }

            _events.emit(GatewayEvent.Chat(
                sessionKey = sessionKey,
                type = state,
                delta = textContent,
                runId = runId,
                content = textContent
            ))
        }
    }

    private fun handleResponse(json: JsonObject) {
        val id = json["id"]?.jsonPrimitive?.content ?: return
        val deferred = pendingRequests.remove(id) ?: return
        deferred.complete(json)
    }

    private suspend fun sendRequest(method: String, params: JsonObject): JsonObject {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        val request = buildJsonObject {
            put("type", "req")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        val sent = webSocket?.send(request.toString()) ?: false
        if (!sent) {
            pendingRequests.remove(id)
            throw Exception("Failed to send request")
        }

        Log.d(TAG, "Sent: $request")

        return withTimeout(ClawdConfig.REQUEST_TIMEOUT_MS) {
            deferred.await()
        }
    }

    suspend fun sendMessage(text: String, sessionKey: String = "main"): Boolean {
        Log.d(TAG, "sendMessage called with text: $text, sessionKey: $sessionKey, connectionState: ${_connectionState.value}")
        // Add user message immediately
        val userMessage = ChatMessage(
            role = "user",
            content = text,
            ts = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMessage

        val params = buildJsonObject {
            put("sessionKey", sessionKey)
            put("message", text)
            put("idempotencyKey", UUID.randomUUID().toString())
        }

        return try {
            val response = sendRequest("chat.send", params)
            response["ok"]?.jsonPrimitive?.boolean ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            false
        }
    }

    suspend fun fetchHistory(sessionKey: String = "main", limit: Int = 50) {
        val params = buildJsonObject {
            put("sessionKey", sessionKey)
            put("limit", limit)
        }

        try {
            val response = sendRequest("chat.history", params)
            val ok = response["ok"]?.jsonPrimitive?.boolean ?: false
            if (ok) {
                val payload = response["payload"]?.jsonObject
                val messagesJson = payload?.get("messages")?.jsonArray
                if (messagesJson != null) {
                    val messages = messagesJson.mapNotNull { element ->
                        try {
                            val obj = element.jsonObject
                            val role = obj["role"]?.jsonPrimitive?.content ?: "user"
                            val rawContent = obj["content"]?.jsonPrimitive?.content ?: ""

                            // Filter assistant messages
                            val content = if (role == "assistant") {
                                val processed = MessageFilter.processAssistantMessage(rawContent)
                                if (!processed.shouldDisplay) return@mapNotNull null
                                processed.text
                            } else {
                                rawContent
                            }

                            if (content.isBlank()) return@mapNotNull null

                            ChatMessage(
                                role = role,
                                content = content,
                                ts = obj["ts"]?.jsonPrimitive?.long ?: 0L
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                    _messages.value = messages
                    Log.d(TAG, "Loaded ${messages.size} messages from history")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch history", e)
        }
    }

    private fun startPingJob() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(ClawdConfig.PING_INTERVAL_MS)
                try {
                    val params = buildJsonObject {}
                    sendRequest("ping", params)
                } catch (e: Exception) {
                    Log.w(TAG, "Ping failed", e)
                }
            }
        }
    }

    private fun stopPingJob() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= 10) {
            Log.w(TAG, "Max reconnect attempts reached")
            return
        }

        val delay = minOf(
            ClawdConfig.RECONNECT_BASE_DELAY_MS * (1 shl reconnectAttempts),
            ClawdConfig.RECONNECT_MAX_DELAY_MS
        )
        reconnectAttempts++

        scope.launch {
            Log.d(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
            delay(delay)
            connect()
        }
    }

    fun disconnect() {
        stopPingJob()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    companion object {
        private const val TAG = "ClawdClient"
    }
}
