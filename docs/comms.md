# ClawVoice Communication Protocol - Android Implementation

> How the Android app connects to OpenClaw via WebSocket.

See also: [OpenClaw Integration](channel.md)

---

## Overview

ClawVoice connects to the OpenClaw Gateway WebSocket and uses the standard protocol:

- **WebSocket** — Real-time bidirectional communication
- **Protocol v3** — Same as iOS/macOS/CLI clients
- **Text only over wire** — STT/TTS happens locally on device
- **Device pairing** — Cryptographic identity + approval flow

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android Device                            │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                   ClawVoice App                         │ │
│  │                                                         │ │
│  │  ┌─────────────┐                    ┌───────────────┐  │ │
│  │  │   Voice     │    [text]          │ ClawdClient   │  │ │
│  │  │   Input     │──────────────────▶│               │  │ │
│  │  │   (Mic)     │                    │  WebSocket    │  │ │
│  │  │      +      │                    │  chat.send    │──┼─┼──▶ Gateway
│  │  │   STT       │                    │  chat.history │  │ │
│  │  └─────────────┘                    │  ping         │◀─┼─┼── (events)
│  │                                     └───────┬───────┘  │ │
│  │                                             │ [text]   │ │
│  │  ┌─────────────┐                    ┌───────▼───────┐  │ │
│  │  │   Speaker   │◀───────────────────│ MessageFilter │  │ │
│  │  │   Output    │    [filtered]      │               │  │ │
│  │  │      +      │                    │ Strips:       │  │ │
│  │  │   TTS       │                    │ HEARTBEAT_OK  │  │ │
│  │  └─────────────┘                    │ NO_REPLY      │  │ │
│  │                                     │ MEDIA:...     │  │ │
│  │                                     │ [[reply_to:]] │  │ │
│  │                                     └───────────────┘  │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## Connection Flow

### First Launch - Device Pairing

```
┌──────────────┐                              ┌──────────────┐
│  ClawVoice   │                              │   OpenClaw   │
│   Android    │                              │   Gateway    │
└──────┬───────┘                              └──────┬───────┘
       │                                             │
       │  WebSocket Connect                          │
       │ ───────────────────────────────────────────▶│
       │                                             │
       │  event: connect.challenge                   │
       │  { nonce, ts }                              │
       │ ◀───────────────────────────────────────────│
       │                                             │
       │  req: connect                               │
       │  { device: { id, publicKey, signature },   │
       │    auth: { token: "" }, ... }               │
       │ ───────────────────────────────────────────▶│
       │                                             │
       │  res: { ok: false, error: pairing_required }│
       │ ◀───────────────────────────────────────────│
       │                                             │
       │  [App shows: "Waiting for approval"]        │
       │  [User runs: openclaw nodes approve <id>]   │
       │                                             │
       │  event: device.paired                       │
       │  { deviceToken: "..." }                     │
       │ ◀───────────────────────────────────────────│
       │                                             │
       │  [App saves deviceToken securely]           │
       │  [ConnectionState → Ready]                  │
       │                                             │
```

### Reconnect - With Token

```
┌──────────────┐                              ┌──────────────┐
│  ClawVoice   │                              │   OpenClaw   │
└──────┬───────┘                              └──────┬───────┘
       │                                             │
       │  WebSocket Connect                          │
       │ ───────────────────────────────────────────▶│
       │                                             │
       │  event: connect.challenge { nonce, ts }     │
       │ ◀───────────────────────────────────────────│
       │                                             │
       │  req: connect                               │
       │  { device: { signature of nonce },          │
       │    auth: { token: "<saved-token>" } }       │
       │ ───────────────────────────────────────────▶│
       │                                             │
       │  res: { ok: true, payload: { ... } }        │
       │ ◀───────────────────────────────────────────│
       │                                             │
       │  [ConnectionState → Ready]                  │
       │  [Fetch history: chat.history]              │
       │                                             │
```

### Chat Flow

```
┌──────────────┐                              ┌──────────────┐
│  ClawVoice   │                              │   OpenClaw   │
└──────┬───────┘                              └──────┬───────┘
       │                                             │
       │  [User speaks: "What's the weather?"]       │
       │  [STT → text locally]                       │
       │                                             │
       │  req: chat.send                             │
       │  { sessionKey: "main",                      │
       │    message: "What's the weather?" }         │
       │ ───────────────────────────────────────────▶│
       │                                             │
       │  res: { ok: true }                          │
       │ ◀───────────────────────────────────────────│
       │                                             │
       │  event: chat { state: "delta", text: "It" } │
       │ ◀───────────────────────────────────────────│
       │  event: chat { state: "delta", text: "It's"}│
       │ ◀───────────────────────────────────────────│
       │  ...                                        │
       │  event: chat { state: "final",              │
       │                text: "It's 72°F and sunny" }│
       │ ◀───────────────────────────────────────────│
       │                                             │
       │  [Filter → TTS speaks response]             │
       │                                             │
```

## Key Classes

### ClawdClient

Main WebSocket client. Handles:
- Connection lifecycle
- Challenge/response authentication
- Request/response correlation
- Event dispatching
- Auto-reconnect with exponential backoff

```kotlin
class ClawdClient(context: Context, gatewayUrl: String) {
    // State
    val connectionState: StateFlow<ConnectionState>
    val messages: StateFlow<List<ChatMessage>>
    val events: SharedFlow<GatewayEvent>
    
    // Actions
    fun connect(initialToken: String? = null)
    fun disconnect()
    suspend fun sendMessage(text: String, sessionKey: String = "main"): Boolean
    suspend fun fetchHistory(sessionKey: String = "main", limit: Int = 50)
}
```

### DeviceIdentity

Cryptographic device identity for pairing:

```kotlin
class DeviceIdentity {
    val deviceId: String        // SHA-256 of public key
    val publicKeyBase64: String // Ed25519 public key
    
    fun signChallenge(nonce: String, signedAtMs: Long, token: String): SignedChallenge
}
```

### MessageFilter

Strips protocol markers before display/TTS:

```kotlin
object MessageFilter {
    fun processAssistantMessage(rawContent: String): ProcessedMessage
    
    data class ProcessedMessage(
        val text: String,           // Cleaned text for display/TTS
        val mediaPath: String?,     // Extracted MEDIA: path (if any)
        val shouldDisplay: Boolean  // False for HEARTBEAT_OK, NO_REPLY
    )
}
```

**Filtered patterns:**
- `HEARTBEAT_OK` — Heartbeat acknowledgment (entire message)
- `NO_REPLY` — Silent response marker (entire message)
- `MEDIA:...` — Server TTS audio path (extracted, line removed)
- `[[reply_to_current]]` — Reply tag (stripped)
- `[[reply_to:...]]` — Reply tag with ID (stripped)

### ConnectionState

```kotlin
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()      // WebSocket open, not yet authenticated
    object WaitingForPairing : ConnectionState()
    object Ready : ConnectionState()          // Authenticated, can chat
    data class Error(val message: String) : ConnectionState()
}
```

## Protocol Details

### Connect Request

```kotlin
val connectParams = buildJsonObject {
    put("minProtocol", 3)
    put("maxProtocol", 3)
    putJsonObject("client") {
        put("id", "clawdbot-android")
        put("version", BuildConfig.VERSION_NAME)
        put("platform", "android")
        put("mode", "webchat")  // Uses webchat session routing
    }
    put("role", "operator")
    putJsonArray("scopes") {
        add("operator.read")
        add("operator.write")
    }
    putJsonObject("auth") {
        put("token", deviceToken ?: "")
    }
    putJsonObject("device") {
        put("id", deviceIdentity.deviceId)
        put("publicKey", deviceIdentity.publicKeyBase64)
        put("signature", signed.signature)
        put("signedAt", signed.signedAt)
        put("nonce", signed.nonce)
    }
}
```

### Chat Event Handling

```kotlin
private fun handleChatEvent(payload: JsonObject) {
    val state = payload["state"]?.jsonPrimitive?.content  // "delta" or "final"
    val runId = payload["runId"]?.jsonPrimitive?.content
    
    // Extract text from message.content array
    val textContent = payload["message"]?.jsonObject
        ?.get("content")?.jsonArray
        ?.filter { it["type"] == "text" }
        ?.firstOrNull()
        ?.get("text")?.jsonPrimitive?.content
    
    when (state) {
        "delta" -> {
            // Update streaming message (text is cumulative)
            currentStreamingContent = StringBuilder(textContent)
            updateMessageList()
        }
        "final" -> {
            // Response complete - trigger TTS
            val processed = MessageFilter.processAssistantMessage(textContent)
            if (processed.shouldDisplay) {
                emitTextComplete(processed.text)
            }
        }
    }
}
```

## Configuration

```kotlin
object ClawdConfig {
    const val PROTOCOL_VERSION = 3
    const val CONNECT_TIMEOUT_MS = 30_000L
    const val REQUEST_TIMEOUT_MS = 60_000L
    const val PING_INTERVAL_MS = 30_000L
    const val RECONNECT_BASE_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L
}
```

## Error Handling

| Scenario | Behavior |
|----------|----------|
| WebSocket failure | Auto-reconnect with backoff (max 10 attempts) |
| `pairing_required` | Show pairing UI, wait for `device.paired` event |
| Invalid token | Clear token, reconnect (triggers new pairing) |
| Request timeout | Fail the request, connection stays open |
| Ping failure | Log warning, connection may be stale |

## Security

1. **Device token** — Stored in EncryptedSharedPreferences (Android Keystore)
2. **Challenge signing** — Ed25519 signature proves device identity
3. **TLS required** — Always use `wss://` in production
4. **No secrets in logs** — Tokens and message content not logged

## Bandwidth

Only text crosses the network:
- User speech → STT (local) → text → WebSocket
- WebSocket → text → TTS (local) → audio

Typical message: ~100-500 bytes
Typical response: ~200-2000 bytes

---

## Related

- [OpenClaw Integration](channel.md) — Gateway setup
- [Gateway Protocol](/docs/gateway/protocol.md) — Full protocol spec
