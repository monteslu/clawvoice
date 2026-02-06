# ClawVoice Communication Protocol - Android Implementation

> How the Android app connects to OpenClaw via WebSocket.

See also: [OpenClaw Integration](channel.md)

---

## Overview

ClawVoice connects to the OpenClaw Gateway WebSocket using protocol v3:

- **WebSocket** — Real-time bidirectional communication
- **Text only over wire** — STT/TTS happens locally on device
- **Device pairing** — Cryptographic identity + approval flow

---

## Client Configuration

### Required Values

```kotlin
// Client identification
put("id", "openclaw-android")  // MUST use this exact ID
put("version", BuildConfig.VERSION_NAME)
put("platform", "android")
put("mode", "webchat")  // For session sharing with web UI

// Role and scopes
put("role", "operator")
putJsonArray("scopes") {
    add("operator.read")
    add("operator.write")
}
```

### Valid Client IDs

| ID | Use |
|----|-----|
| `openclaw-android` | ✅ Use this for ClawVoice |
| `openclaw-ios` | iOS apps |
| `openclaw-macos` | macOS apps |
| `webchat-ui` | Browser webchat |
| `cli` | Command line tools |

### Valid Modes

| Mode | Origin Check | Session Sharing |
|------|--------------|-----------------|
| `webchat` | Yes | Yes |
| `ui` | No | Limited |
| `cli` | No | No |

---

## Origin Header (Critical!)

**Problem:** Gateway checks Origin header for `webchat` mode. Native apps may not send one, causing "origin not allowed" error.

**Solution:** Explicitly set Origin header:

```kotlin
val wsUrl = gatewayUrl
    .replace("https://", "wss://")
    .replace("http://", "ws://")

val request = Request.Builder()
    .url(wsUrl)
    .header("Origin", gatewayUrl)  // REQUIRED for webchat mode
    .build()

webSocket = okHttpClient.newWebSocket(request, listener)
```

---

## Pairing Flow

### Short Request ID (Recommended)

Generate a short ID client-side for easier user typing:

```kotlin
fun generateShortRequestId(): String {
    return UUID.randomUUID().toString().take(8)
}
```

Include in connect params:

```kotlin
putJsonObject("device") {
    put("id", deviceIdentity.deviceId)
    put("publicKey", deviceIdentity.publicKeyBase64)
    put("requestId", generateShortRequestId())  // e.g., "a1b2c3d4"
    put("signature", signed.signature)
    put("signedAt", signed.signedAt)
    put("nonce", signed.nonce)
}
```

User approves with: `openclaw nodes approve a1b2c3d4`

### Full Connect Request

```kotlin
val connectParams = buildJsonObject {
    put("minProtocol", 3)
    put("maxProtocol", 3)
    
    putJsonObject("client") {
        put("id", "openclaw-android")
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
        put("requestId", shortRequestId)  // Client-generated, 6-8 chars
        put("signature", signed.signature)
        put("signedAt", signed.signedAt)
        put("nonce", signed.nonce)
    }
}
```

### Pairing States

```kotlin
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()      // WS open, not authenticated
    object WaitingForPairing : ConnectionState()  // Show requestId to user
    object Ready : ConnectionState()          // Authenticated, can chat
    data class Error(val message: String) : ConnectionState()
}
```

### Handle Pairing Response

```kotlin
private fun handleChallenge(payload: JsonObject) {
    // ... sign challenge ...
    
    scope.launch {
        val response = sendRequest("connect", connectParams)
        val ok = response["ok"]?.jsonPrimitive?.boolean ?: false

        if (ok) {
            // Connected! Save token if provided
            val authPayload = response["payload"]?.jsonObject?.get("auth")?.jsonObject
            val newToken = authPayload?.get("deviceToken")?.jsonPrimitive?.contentOrNull
            if (newToken != null) {
                deviceToken = newToken
                SecureStorage.saveDeviceToken(context, newToken)
            }
            _connectionState.value = ConnectionState.Ready
        } else {
            val errorCode = response["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
            if (errorCode == "pairing_required") {
                // Show the requestId to user
                _connectionState.value = ConnectionState.WaitingForPairing
                _events.emit(GatewayEvent.PairingRequired(shortRequestId))
            }
        }
    }
}

// Listen for approval
private fun handlePaired(payload: JsonObject) {
    val newToken = payload["deviceToken"]?.jsonPrimitive?.contentOrNull
    if (newToken != null) {
        deviceToken = newToken
        SecureStorage.saveDeviceToken(context, newToken)
        _connectionState.value = ConnectionState.Ready
    }
}
```

---

## Message Filtering

```kotlin
object MessageFilter {
    
    fun processAssistantMessage(rawContent: String): ProcessedMessage {
        val trimmed = rawContent.trim()

        // Silent markers - don't display at all
        if (trimmed == "HEARTBEAT_OK" || trimmed == "NO_REPLY") {
            return ProcessedMessage(text = "", shouldDisplay = false)
        }

        val lines = rawContent.lines()
        val displayLines = mutableListOf<String>()
        var mediaPath: String? = null

        for (line in lines) {
            val lineTrimmed = line.trim()
            when {
                lineTrimmed.startsWith("MEDIA:") -> {
                    mediaPath = lineTrimmed.removePrefix("MEDIA:").trim()
                }
                lineTrimmed == "HEARTBEAT_OK" || lineTrimmed == "NO_REPLY" -> {
                    // skip
                }
                else -> displayLines.add(line)
            }
        }

        // Strip reply tags
        var finalText = displayLines.joinToString("\n")
        finalText = finalText
            .replace(Regex("""\[\[\s*reply_to_current\s*]]"""), "")
            .replace(Regex("""\[\[\s*reply_to:\s*[^\]]+\s*]]"""), "")
            .trim()

        return ProcessedMessage(
            text = finalText,
            mediaPath = mediaPath,
            shouldDisplay = finalText.isNotEmpty() || mediaPath != null
        )
    }
}
```

---

## Chat Methods

### Send Message

```kotlin
suspend fun sendMessage(text: String, sessionKey: String = "main"): Boolean {
    val params = buildJsonObject {
        put("sessionKey", sessionKey)
        put("message", text)
        put("idempotencyKey", UUID.randomUUID().toString())
    }
    
    return try {
        val response = sendRequest("chat.send", params)
        response["ok"]?.jsonPrimitive?.boolean ?: false
    } catch (e: Exception) {
        false
    }
}
```

### Receive Streaming Response

```kotlin
private fun handleChatEvent(payload: JsonObject) {
    val state = payload["state"]?.jsonPrimitive?.content  // "delta" or "final"
    
    val textContent = payload["message"]?.jsonObject
        ?.get("content")?.jsonArray
        ?.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
        ?.firstOrNull()
        ?.jsonObject?.get("text")?.jsonPrimitive?.content

    when (state) {
        "delta" -> {
            // Update UI with streaming text (cumulative)
            updateStreamingMessage(textContent)
        }
        "final" -> {
            // Complete - filter and speak
            val processed = MessageFilter.processAssistantMessage(textContent ?: "")
            if (processed.shouldDisplay) {
                speakText(processed.text)
            }
        }
    }
}
```

### Fetch History

```kotlin
suspend fun fetchHistory(sessionKey: String = "main", limit: Int = 50) {
    val params = buildJsonObject {
        put("sessionKey", sessionKey)
        put("limit", limit)
    }
    
    val response = sendRequest("chat.history", params)
    // Parse and filter messages...
}
```

---

## Configuration Constants

```kotlin
object ClawdConfig {
    const val PROTOCOL_VERSION = 3
    const val CONNECT_TIMEOUT_MS = 30_000L
    const val REQUEST_TIMEOUT_MS = 30_000L
    const val PING_INTERVAL_MS = 10_000L
    const val RECONNECT_BASE_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 60_000L
}
```

---

## Troubleshooting

| Error | Cause | Fix |
|-------|-------|-----|
| "origin not allowed" | Missing/wrong Origin header | Add `.header("Origin", gatewayUrl)` |
| "pairing_required" | New device | User runs `openclaw nodes approve <id>` |
| "protocol mismatch" | Wrong version | Use `minProtocol: 3, maxProtocol: 3` |
| No responses | Wrong mode/session | Check `mode: "webchat"` |

---

## Related

- [OpenClaw Integration](channel.md) — Gateway setup
- [Gateway Protocol](https://docs.openclaw.ai/gateway/protocol) — Full spec
