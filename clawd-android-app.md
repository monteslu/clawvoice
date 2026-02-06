# Clawd Android App - Design & System Documentation

> A comprehensive guide for building an Android client for the Clawdbot system.
> Written so that another AI agent can understand and implement the entire app.

---

## Part 1: What is Clawdbot?

### Overview

**Clawdbot** is a self-hosted AI assistant gateway that bridges multiple messaging platforms (WhatsApp, Telegram, Discord, Slack, Signal, iMessage) to AI models (Claude, GPT, etc.). It runs as a single long-running process called the **Gateway** on a server (Linux/macOS/Windows).

Think of it as: **Your own private AI assistant that you can message from anywhere.**

### Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CLAWDBOT GATEWAY                                  │
│                        (runs on server/VPS)                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      Core Components                                 │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│  │  │   Agents     │  │   Sessions   │  │   Memory     │              │   │
│  │  │ (AI models)  │  │ (chat state) │  │ (SOUL/USER)  │              │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│  │  │    Tools     │  │    Cron      │  │   Plugins    │              │   │
│  │  │ (exec/web/fs)│  │  (scheduled) │  │ (extensions) │              │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    WebSocket Server (port 18789)                     │   │
│  │         Single control plane for ALL clients                         │   │
│  └───────────────────────────────┬─────────────────────────────────────┘   │
│                                  │                                          │
│  ┌───────────────────────────────┼─────────────────────────────────────┐   │
│  │              Channel Plugins  │  (messaging bridges)                 │   │
│  │  ┌────────┐ ┌────────┐ ┌─────┴──┐ ┌────────┐ ┌────────┐            │   │
│  │  │WhatsApp│ │Telegram│ │ Slack  │ │Discord │ │ Signal │  ...       │   │
│  │  └────────┘ └────────┘ └────────┘ └────────┘ └────────┘            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                         push-notify Plugin                           │   │
│  │            Sends notifications via ntfy.sh when user offline         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                    WebSocket connections (WSS)
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        │                           │                           │
        ▼                           ▼                           ▼
┌───────────────┐         ┌───────────────┐         ┌───────────────┐
│   Control UI  │         │   CLI Tools   │         │  Mobile Nodes │
│   (browser)   │         │  (clawdbot)   │         │ (iOS/Android) │
│ role:operator │         │ role:operator │         │  role: node   │
└───────────────┘         └───────────────┘         └───────────────┘
```

### Key Concepts

1. **Gateway**: The central server process. Handles all AI interactions, sessions, tools, and message routing.

2. **Agents**: AI model configurations (e.g., Claude Opus, GPT-4). Each agent has a workspace with memory files (SOUL.md, USER.md, daily notes).

3. **Sessions**: Conversation state. Each chat (DM, group, etc.) has its own session with history.

4. **Channels**: Messaging platform integrations (WhatsApp, Telegram, etc.). Each channel bridges messages to/from the agent.

5. **Nodes**: Companion devices that connect to the Gateway. Nodes can provide capabilities like camera, screen capture, location, etc.

6. **Operators**: Control plane clients (CLI, Control UI, your Android app). Can send chat messages, view status, approve requests, etc.

### What We're Building

An Android app that acts as an **operator** client:
- Connects to the Gateway via WebSocket
- Sends/receives chat messages
- Local voice input (STT) and output (TTS)
- Receives push notifications when disconnected (via ntfy)
- Requires device pairing for security

---

## Part 2: Gateway WebSocket Protocol

The Gateway exposes a single WebSocket endpoint that ALL clients use. The protocol is JSON over WebSocket text frames.

### Connection URL

```
wss://<gateway-host>:<port>/
```

For this deployment:
```
wss://your-gateway-host.example.com/
```

(This is an hsync tunnel that forwards to the Gateway on port 18789)

### Protocol Version

Current protocol version: **3**

Clients must negotiate protocol version during handshake.

### Message Types

All messages are JSON with a `type` field:

```typescript
// Request (client → server)
{
  "type": "req",
  "id": "<unique-request-id>",
  "method": "<method-name>",
  "params": { ... }
}

// Response (server → client)
{
  "type": "res",
  "id": "<matching-request-id>",
  "ok": true | false,
  "payload": { ... } | "error": { "code": "...", "message": "..." }
}

// Event (server → client, unsolicited)
{
  "type": "event",
  "event": "<event-name>",
  "payload": { ... },
  "seq": <optional-sequence-number>
}
```

### Handshake Flow

#### Step 1: Connect WebSocket

Open a WebSocket connection to the Gateway URL.

#### Step 2: Receive Challenge

The server immediately sends a challenge:

```json
{
  "type": "event",
  "event": "connect.challenge",
  "payload": {
    "nonce": "random-base64-string",
    "ts": 1737264000000
  }
}
```

#### Step 3: Send Connect Request

Client must respond with a `connect` request:

```json
{
  "type": "req",
  "id": "connect-1",
  "method": "connect",
  "params": {
    "minProtocol": 3,
    "maxProtocol": 3,
    "client": {
      "id": "clawd-android",
      "version": "1.0.0",
      "platform": "android",
      "mode": "operator"
    },
    "role": "operator",
    "scopes": ["operator.read", "operator.write"],
    "caps": [],
    "commands": [],
    "permissions": {},
    "auth": {
      "token": "<gateway-token-or-device-token>"
    },
    "locale": "en-US",
    "userAgent": "clawd-android/1.0.0",
    "device": {
      "id": "<stable-device-fingerprint>",
      "publicKey": "<base64-public-key>",
      "signature": "<base64-signature-of-nonce>",
      "signedAt": 1737264000000,
      "nonce": "<nonce-from-challenge>"
    }
  }
}
```

**Device Identity Fields:**
- `device.id`: A stable fingerprint derived from a keypair (e.g., SHA256 of public key)
- `device.publicKey`: Base64-encoded public key (Ed25519 or similar)
- `device.signature`: Signature of the challenge nonce, proving key ownership
- `device.signedAt`: Timestamp when signature was created
- `device.nonce`: Echo back the nonce from the challenge

#### Step 4: Receive Response

**If new device (needs pairing):**
```json
{
  "type": "res",
  "id": "connect-1",
  "ok": false,
  "error": {
    "code": "pairing_required",
    "message": "Device pairing required"
  }
}
```

The connection stays open. Gateway creates a pending pairing request.
Owner must approve via CLI: `clawdbot devices approve <requestId>`

After approval, server sends:
```json
{
  "type": "event",
  "event": "device.paired",
  "payload": {
    "deviceToken": "<new-device-token>",
    "role": "operator",
    "scopes": ["operator.read", "operator.write"]
  }
}
```

**If already paired (or auto-approved):**
```json
{
  "type": "res",
  "id": "connect-1",
  "ok": true,
  "payload": {
    "type": "hello-ok",
    "protocol": 3,
    "policy": {
      "tickIntervalMs": 15000
    },
    "auth": {
      "deviceToken": "<device-token>",
      "role": "operator",
      "scopes": ["operator.read", "operator.write"]
    }
  }
}
```

**Save the `deviceToken`** — use it for future connections instead of the gateway token.

### Chat Methods

#### Get Chat History

```json
{
  "type": "req",
  "id": "history-1",
  "method": "chat.history",
  "params": {
    "sessionKey": "main",
    "limit": 50
  }
}
```

Response:
```json
{
  "type": "res",
  "id": "history-1",
  "ok": true,
  "payload": {
    "messages": [
      {
        "role": "user",
        "content": "Hello",
        "ts": 1737264000000
      },
      {
        "role": "assistant",
        "content": "Hi! How can I help?",
        "ts": 1737264001000
      }
    ]
  }
}
```

#### Send Message

```json
{
  "type": "req",
  "id": "send-1",
  "method": "chat.send",
  "params": {
    "sessionKey": "main",
    "message": "What's the weather like?",
    "idempotencyKey": "<unique-key-for-dedup>"
  }
}
```

Response (immediate ack):
```json
{
  "type": "res",
  "id": "send-1",
  "ok": true,
  "payload": {
    "runId": "run-abc123",
    "status": "started"
  }
}
```

The actual response streams via events.

#### Chat Events (Streaming Response)

After sending, you'll receive events:

```json
{
  "type": "event",
  "event": "chat",
  "payload": {
    "sessionKey": "main",
    "runId": "run-abc123",
    "type": "text",
    "delta": "The weather ",
    "ts": 1737264002000
  }
}
```

```json
{
  "type": "event",
  "event": "chat",
  "payload": {
    "sessionKey": "main",
    "runId": "run-abc123",
    "type": "text",
    "delta": "is sunny today!",
    "ts": 1737264002100
  }
}
```

```json
{
  "type": "event",
  "event": "chat",
  "payload": {
    "sessionKey": "main",
    "runId": "run-abc123",
    "type": "done",
    "ts": 1737264003000
  }
}
```

Collect `delta` strings to build the full response.

#### Abort a Request

```json
{
  "type": "req",
  "id": "abort-1",
  "method": "chat.abort",
  "params": {
    "sessionKey": "main"
  }
}
```

### Keep-Alive

Send periodic pings to keep the connection alive. The server expects activity within `tickIntervalMs` (default 15 seconds).

WebSocket protocol-level pings work, or send:
```json
{
  "type": "req",
  "id": "ping-1",
  "method": "ping",
  "params": {}
}
```

---

## Part 3: Device Pairing

### Why Pairing?

Clawdbot requires explicit owner approval before a new device can connect. This prevents unauthorized access to your AI assistant.

### Pairing Flow

```
┌─────────────┐                              ┌─────────────┐
│ Android App │                              │   Gateway   │
└──────┬──────┘                              └──────┬──────┘
       │                                            │
       │  1. WebSocket connect                      │
       │ ─────────────────────────────────────────▶ │
       │                                            │
       │  2. connect.challenge (nonce)              │
       │ ◀───────────────────────────────────────── │
       │                                            │
       │  3. connect request (signed device ID)     │
       │ ─────────────────────────────────────────▶ │
       │                                            │
       │  4. error: pairing_required                │
       │ ◀───────────────────────────────────────── │
       │                                            │
       │        [Gateway creates pending request]   │
       │        [Owner runs: clawdbot devices       │
       │         approve <id>]                      │
       │                                            │
       │  5. event: device.paired (deviceToken)     │
       │ ◀───────────────────────────────────────── │
       │                                            │
       │  [App saves deviceToken for future use]    │
       │                                            │
```

### Generating Device Identity

The app needs a stable device identity:

1. **Generate a keypair** (Ed25519 recommended) on first launch
2. **Store it securely** (Android Keystore)
3. **Device ID** = SHA256(publicKey) as hex string
4. **Sign challenges** with the private key

```kotlin
// Pseudocode
class DeviceIdentity(context: Context) {
    private val keyPair: KeyPair = loadOrGenerateKeyPair()
    
    val deviceId: String = sha256Hex(keyPair.public.encoded)
    val publicKeyBase64: String = Base64.encode(keyPair.public.encoded)
    
    fun signChallenge(nonce: String, ts: Long): String {
        val message = "$nonce:$ts"
        val signature = sign(keyPair.private, message.toByteArray())
        return Base64.encode(signature)
    }
}
```

### Subsequent Connections

After pairing, use the `deviceToken` instead of the gateway token:

```json
{
  "params": {
    "auth": {
      "token": "<deviceToken>"
    },
    "device": {
      "id": "<same-device-id>",
      "publicKey": "<same-public-key>",
      "signature": "<sign-new-nonce>",
      ...
    }
  }
}
```

The server recognizes the device and allows connection without re-pairing.

---

## Part 4: Push Notifications (ntfy)

### Overview

When the Android app is disconnected, Clawdbot can still notify you via **ntfy** — an open-source push notification service.

Current configuration:
```
ntfy topic: your-ntfy-topic
ntfy server: https://ntfy.sh
```

### How It Works

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Clawdbot  │     │ push-notify │     │   ntfy.sh   │     │ Android App │
│   Gateway   │────▶│   Plugin    │────▶│   Server    │────▶│  (or ntfy)  │
└─────────────┘     └─────────────┘     └─────────────┘     └─────────────┘
                           │
                    Triggered when:
                    - User is disconnected
                    - Agent has a message/alert
```

The `push-notify` plugin:
1. Detects when the owner is offline (no active WebSocket from operator devices)
2. Sends a notification to the configured ntfy topic
3. Notification contains a summary of the message

### ntfy Protocol

ntfy is simple HTTP:

**Subscribe (WebSocket for real-time):**
```
wss://ntfy.sh/your-ntfy-topic/ws
```

**Subscribe (HTTP long-poll):**
```
GET https://ntfy.sh/your-ntfy-topic/json?poll=1
```

**Message format:**
```json
{
  "id": "abc123",
  "time": 1737264000,
  "event": "message",
  "topic": "your-ntfy-topic",
  "title": "Clawd",
  "message": "You have a new message from Clawd",
  "priority": 3
}
```

### Implementation Options

#### Option 1: Use ntfy Android App (Simplest)

1. Install ntfy app from F-Droid or Play Store
2. Subscribe to topic: `your-ntfy-topic`
3. Configure to open your Clawd app on tap

#### Option 2: Direct WebSocket in Your App (Recommended)

```kotlin
class NtfyClient(private val topic: String) {
    private var webSocket: WebSocket? = null
    
    fun connect(onMessage: (NtfyMessage) -> Unit) {
        val request = Request.Builder()
            .url("wss://ntfy.sh/$topic/ws")
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = Json.decodeFromString<NtfyMessage>(text)
                if (msg.event == "message") {
                    onMessage(msg)
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Reconnect with backoff
            }
        })
    }
}

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
```

#### Option 3: UnifiedPush

UnifiedPush is an open standard that ntfy supports. Useful if you want to support multiple push providers.

---

## Part 5: Android App Implementation

### App Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Android App                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    UI Layer                          │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │
│  │  │ SetupScreen │  │ ChatScreen  │  │ VoiceOverlay│  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  │   │
│  └───────────────────────────┬─────────────────────────┘   │
│                              │                              │
│  ┌───────────────────────────┼─────────────────────────┐   │
│  │                  Business Layer                      │   │
│  │  ┌─────────────┐  ┌──────┴──────┐  ┌─────────────┐  │   │
│  │  │ ChatManager │  │ VoiceManager│  │NotifyManager│  │   │
│  │  └──────┬──────┘  └─────────────┘  └──────┬──────┘  │   │
│  └─────────┼───────────────────────────────────┼───────┘   │
│            │                                   │            │
│  ┌─────────┼───────────────────────────────────┼───────┐   │
│  │         │        Network Layer              │       │   │
│  │  ┌──────▼──────┐                    ┌───────▼─────┐ │   │
│  │  │ ClawdClient │                    │ NtfyClient  │ │   │
│  │  │ (Gateway WS)│                    │ (Push WS)   │ │   │
│  │  └─────────────┘                    └─────────────┘ │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                  Local Services                      │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │
│  │  │SecureStorage│  │   STT       │  │    TTS      │  │   │
│  │  │ (Keystore)  │  │ (Android)   │  │ (Android)   │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Project Structure

```
clawd-android/
├── app/
│   ├── src/main/java/com/clawd/app/
│   │   ├── ClawdApplication.kt
│   │   ├── MainActivity.kt
│   │   │
│   │   ├── data/
│   │   │   ├── SecureStorage.kt          # Encrypted prefs (device token, keys)
│   │   │   └── DeviceIdentity.kt         # Keypair generation & signing
│   │   │
│   │   ├── network/
│   │   │   ├── ClawdClient.kt            # Gateway WebSocket client
│   │   │   ├── NtfyClient.kt             # ntfy WebSocket client
│   │   │   └── protocol/
│   │   │       ├── Messages.kt           # Protocol data classes
│   │   │       └── ProtocolHandler.kt    # Message serialization
│   │   │
│   │   ├── service/
│   │   │   ├── ClawdConnectionService.kt # Foreground service for connection
│   │   │   └── NtfyService.kt            # Background service for push
│   │   │
│   │   ├── speech/
│   │   │   ├── SpeechRecognizerManager.kt
│   │   │   └── TextToSpeechManager.kt
│   │   │
│   │   └── ui/
│   │       ├── setup/
│   │       │   └── SetupScreen.kt        # Gateway URL + token entry
│   │       ├── chat/
│   │       │   ├── ChatScreen.kt
│   │       │   └── MessageBubble.kt
│   │       └── voice/
│   │           └── VoiceOverlay.kt       # Voice-only interface
│   │
│   └── src/main/res/
│       └── ...
│
├── build.gradle.kts
└── README.md
```

### Key Components

#### 1. SecureStorage

```kotlin
object SecureStorage {
    private const val PREFS_NAME = "clawd_secure"
    private const val KEY_GATEWAY_URL = "gateway_url"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_PRIVATE_KEY = "private_key"
    private const val KEY_PUBLIC_KEY = "public_key"
    
    private fun getPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    fun saveGatewayUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_GATEWAY_URL, url).apply()
    }
    
    fun getGatewayUrl(context: Context): String? {
        return getPrefs(context).getString(KEY_GATEWAY_URL, null)
    }
    
    fun saveDeviceToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }
    
    fun getDeviceToken(context: Context): String? {
        return getPrefs(context).getString(KEY_DEVICE_TOKEN, null)
    }
    
    // Similar methods for keypair storage
}
```

#### 2. DeviceIdentity

```kotlin
class DeviceIdentity private constructor(
    private val privateKey: PrivateKey,
    val publicKey: PublicKey
) {
    val deviceId: String = computeDeviceId(publicKey)
    val publicKeyBase64: String = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    
    fun signChallenge(nonce: String, ts: Long): SignedChallenge {
        val message = "$nonce:$ts".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(privateKey)
            update(message)
        }.sign()
        
        return SignedChallenge(
            signature = Base64.encodeToString(signature, Base64.NO_WRAP),
            signedAt = ts,
            nonce = nonce
        )
    }
    
    companion object {
        fun loadOrCreate(context: Context): DeviceIdentity {
            val stored = SecureStorage.getKeyPair(context)
            if (stored != null) {
                return DeviceIdentity(stored.private, stored.public)
            }
            
            // Generate new Ed25519 keypair
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            SecureStorage.saveKeyPair(context, keyPair)
            return DeviceIdentity(keyPair.private, keyPair.public)
        }
        
        private fun computeDeviceId(publicKey: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(publicKey.encoded).joinToString("") { "%02x".format(it) }
        }
    }
}

data class SignedChallenge(
    val signature: String,
    val signedAt: Long,
    val nonce: String
)
```

#### 3. ClawdClient

```kotlin
class ClawdClient(
    private val gatewayUrl: String,
    private val deviceIdentity: DeviceIdentity
) {
    private var webSocket: WebSocket? = null
    private var deviceToken: String? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val _events = MutableSharedFlow<GatewayEvent>()
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    fun connect(initialToken: String? = null) {
        deviceToken = initialToken ?: SecureStorage.getDeviceToken(context)
        
        val request = Request.Builder()
            .url(gatewayUrl.replace("https://", "wss://").replace("http://", "ws://"))
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = ConnectionState.Connected
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(Json.parseToJsonElement(text).jsonObject)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
                // Implement reconnection with exponential backoff
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = ConnectionState.Disconnected
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
        
        when (event) {
            "connect.challenge" -> handleChallenge(payload!!)
            "device.paired" -> handlePaired(payload!!)
            "chat" -> handleChatEvent(payload!!)
            else -> { /* ignore unknown events */ }
        }
    }
    
    private fun handleChallenge(payload: JsonObject) {
        val nonce = payload["nonce"]!!.jsonPrimitive.content
        val ts = payload["ts"]!!.jsonPrimitive.long
        
        val signed = deviceIdentity.signChallenge(nonce, ts)
        
        val connectParams = buildJsonObject {
            put("minProtocol", 3)
            put("maxProtocol", 3)
            putJsonObject("client") {
                put("id", "clawd-android")
                put("version", BuildConfig.VERSION_NAME)
                put("platform", "android")
                put("mode", "operator")
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
            put("userAgent", "clawd-android/${BuildConfig.VERSION_NAME}")
            putJsonObject("device") {
                put("id", deviceIdentity.deviceId)
                put("publicKey", deviceIdentity.publicKeyBase64)
                put("signature", signed.signature)
                put("signedAt", signed.signedAt)
                put("nonce", signed.nonce)
            }
        }
        
        sendRequest("connect", connectParams)
    }
    
    private fun handlePaired(payload: JsonObject) {
        val newToken = payload["deviceToken"]?.jsonPrimitive?.content
        if (newToken != null) {
            deviceToken = newToken
            SecureStorage.saveDeviceToken(context, newToken)
            _connectionState.value = ConnectionState.Ready
        }
    }
    
    private fun handleChatEvent(payload: JsonObject) {
        val chatEvent = GatewayEvent.Chat(
            sessionKey = payload["sessionKey"]?.jsonPrimitive?.content ?: "main",
            type = payload["type"]?.jsonPrimitive?.content ?: "text",
            delta = payload["delta"]?.jsonPrimitive?.content,
            runId = payload["runId"]?.jsonPrimitive?.content
        )
        _events.tryEmit(chatEvent)
    }
    
    suspend fun sendMessage(text: String, sessionKey: String = "main"): Boolean {
        val params = buildJsonObject {
            put("sessionKey", sessionKey)
            put("message", text)
            put("idempotencyKey", UUID.randomUUID().toString())
        }
        
        val response = sendRequest("chat.send", params)
        return response["ok"]?.jsonPrimitive?.boolean ?: false
    }
    
    suspend fun getHistory(sessionKey: String = "main", limit: Int = 50): List<ChatMessage> {
        val params = buildJsonObject {
            put("sessionKey", sessionKey)
            put("limit", limit)
        }
        
        val response = sendRequest("chat.history", params)
        // Parse and return messages
        return emptyList() // TODO: implement parsing
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
        
        webSocket?.send(request.toString())
        
        return withTimeout(30_000) {
            deferred.await()
        }
    }
    
    private fun handleResponse(json: JsonObject) {
        val id = json["id"]?.jsonPrimitive?.content ?: return
        val deferred = pendingRequests.remove(id) ?: return
        deferred.complete(json)
    }
    
    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connected : ConnectionState()
    object WaitingForPairing : ConnectionState()
    object Ready : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class GatewayEvent {
    data class Chat(
        val sessionKey: String,
        val type: String,
        val delta: String?,
        val runId: String?
    ) : GatewayEvent()
}
```

### Configuration Constants

```kotlin
object ClawdConfig {
    // Gateway (set during setup)
    var gatewayUrl: String = "https://your-gateway-host.example.com"
    
    // ntfy push notifications
    const val NTFY_SERVER = "wss://ntfy.sh"
    var ntfyTopic: String = ""  // Set during setup
    
    // Protocol
    const val PROTOCOL_VERSION = 3
    
    // Timeouts
    const val CONNECT_TIMEOUT_MS = 30_000L
    const val REQUEST_TIMEOUT_MS = 30_000L
    const val RECONNECT_BASE_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 60_000L
}
```

### Dependencies

```kotlin
// build.gradle.kts (app level)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Secure storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Lifecycle (for StateFlow collection)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    
    // No Firebase! No Google Play Services!
}
```

### Permissions

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

---

## Part 6: Development Checklist

### Phase 1: Core Connection
- [ ] Project setup (Kotlin, dependencies)
- [ ] SecureStorage implementation
- [ ] DeviceIdentity (keypair generation, signing)
- [ ] ClawdClient (WebSocket, protocol handling)
- [ ] Setup screen (gateway URL input)
- [ ] Pairing flow UI (waiting for approval)
- [ ] Basic chat screen (send/receive text)

### Phase 2: Voice
- [ ] SpeechRecognizer integration (local STT)
- [ ] TextToSpeech integration (local TTS)
- [ ] Voice button (hold-to-talk)
- [ ] Auto-read responses toggle

### Phase 3: Push Notifications
- [ ] NtfyClient (WebSocket to ntfy.sh)
- [ ] Background service for ntfy
- [ ] Notification display
- [ ] Deep link to app on tap

### Phase 4: Polish
- [ ] Reconnection with exponential backoff
- [ ] Offline indicator
- [ ] Message history caching
- [ ] Dark mode
- [ ] Error handling & user feedback

---

## Appendix: Quick Reference

### Gateway Endpoint
```
URL: https://your-gateway-host.example.com
Auth: Gateway token (for initial setup) → Device token (after pairing)
Protocol: WebSocket, JSON frames, protocol version 3
```

### ntfy Push
```
Server: wss://ntfy.sh
Topic: your-ntfy-topic
Subscribe: wss://ntfy.sh/your-ntfy-topic/ws
```

### Device Pairing Approval
```bash
# On gateway server (or via Clawd chat)
clawdbot devices list          # See pending requests
clawdbot devices approve <id>  # Approve a device
```

### Key Methods
| Method | Description |
|--------|-------------|
| `connect` | Handshake with device identity |
| `chat.send` | Send a message |
| `chat.history` | Get conversation history |
| `chat.abort` | Cancel current request |
| `ping` | Keep connection alive |

### Key Events
| Event | Description |
|-------|-------------|
| `connect.challenge` | Server sends nonce to sign |
| `device.paired` | Pairing approved, includes deviceToken |
| `chat` | Streaming response chunks |

---

*No Firebase. No Google Play Services. No cloud STT/TTS. Your conversations stay between you and your server.*
