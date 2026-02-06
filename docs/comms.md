# ClawVoice Communication Protocol - Android Guide

> How the Android app connects to OpenClaw.

See also: [OpenClaw Channel Setup](channel.md)

---

## Overview

ClawVoice uses a simple HTTP/REST protocol to communicate with OpenClaw:

- **Text only** — saves bandwidth, works on slow connections
- **STT/TTS on device** — voice processing stays local
- **Device pairing** — one-time approval for security
- **Same auth as other channels** — uses device tokens

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Android Device                            │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                   ClawVoice App                         │ │
│  │                                                         │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │ │
│  │  │   Voice     │  │   Voice     │  │   VoiceManager  │ │ │
│  │  │   Input     │──│   STT       │──│   (Kotlin)      │ │ │
│  │  │   (Mic)     │  │  (Android)  │  │                 │ │ │
│  │  └─────────────┘  └─────────────┘  └────────┬────────┘ │ │
│  │                                              │          │ │
│  │                                     Text     │          │ │
│  │                                              ▼          │ │
│  │                                    ┌─────────────────┐  │ │
│  │                                    │  OpenClawClient │  │ │
│  │                                    │  POST /api/voice│  │ │
│  │                                    └────────┬────────┘  │ │
│  │                                              │          │ │
│  │                                     Text     │          │ │
│  │                                              ▼          │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │ │
│  │  │   Speaker   │◀─│   TTS       │◀─│  ResponseHandler│ │ │
│  │  │   Output    │  │  (Android)  │  │                 │ │ │
│  │  └─────────────┘  └─────────────┘  └─────────────────┘ │ │
│  └────────────────────────────────────────────────────────┘ │
│                              │                               │
└──────────────────────────────┼───────────────────────────────┘
                               │
                    HTTPS (text only)
                               │
                               ▼
                    ┌─────────────────────┐
                    │   OpenClaw Gateway  │
                    │   /api/voice/*      │
                    └─────────────────────┘
```

## Connection Flow

### 1. First Launch - Device Pairing

```
┌──────────────┐                              ┌──────────────┐
│  ClawVoice   │                              │   OpenClaw   │
│   Android    │                              │   Gateway    │
└──────┬───────┘                              └──────┬───────┘
       │                                             │
       │  POST /api/voice/pair                       │
       │  { deviceId, publicKey, deviceName }        │
       │ ───────────────────────────────────────────▶│
       │                                             │
       │  { status: "pending", requestId: "abc123" } │
       │ ◀───────────────────────────────────────────│
       │                                             │
       │         [App shows: "Waiting for approval"] │
       │         [User runs: openclaw devices        │
       │                     approve abc123]         │
       │                                             │
       │  GET /api/voice/pair/abc123 (polling)       │
       │ ───────────────────────────────────────────▶│
       │                                             │
       │  { status: "approved", deviceToken: "..." } │
       │ ◀───────────────────────────────────────────│
       │                                             │
       │  [App saves deviceToken securely]           │
       │                                             │
```

### 2. Normal Operation

```
┌──────────────┐                              ┌──────────────┐
│  ClawVoice   │                              │   OpenClaw   │
└──────┬───────┘                              └──────┬───────┘
       │                                             │
       │  [User speaks: "What time is it?"]          │
       │  [STT converts to text locally]             │
       │                                             │
       │  POST /api/voice/message                    │
       │  Authorization: Bearer <deviceToken>        │
       │  { text: "What time is it?" }               │
       │ ───────────────────────────────────────────▶│
       │                                             │
       │                    [Agent processes message]│
       │                                             │
       │  { reply: "It's 3:45 PM." }                 │
       │ ◀───────────────────────────────────────────│
       │                                             │
       │  [TTS speaks response locally]              │
       │                                             │
```

## API Reference

### Base URL

```
https://<your-gateway-url>
```

Example: `https://pip-claw.shiv.to`

### Authentication

After pairing, include the device token in all requests:

```http
Authorization: Bearer <device-token>
```

### Endpoints

#### Request Pairing

```http
POST /api/voice/pair
Content-Type: application/json

{
  "deviceId": "sha256-of-device-public-key",
  "publicKey": "base64-encoded-public-key",
  "deviceName": "Luis's Pixel 8"
}
```

**Response (pending):**
```json
{
  "status": "pending",
  "requestId": "abc123def",
  "message": "Waiting for approval"
}
```

**Response (already approved):**
```json
{
  "status": "approved",
  "deviceToken": "64-char-hex-token"
}
```

#### Check Pairing Status

```http
GET /api/voice/pair/{requestId}
```

**Response:**
```json
{
  "status": "pending" | "approved" | "rejected",
  "deviceToken": "..." // only if approved
}
```

#### Send Message

```http
POST /api/voice/message
Authorization: Bearer <device-token>
Content-Type: application/json

{
  "text": "What's on my calendar today?",
  "sessionKey": "agent:main:clawvoice"  // optional
}
```

**Response:**
```json
{
  "reply": "You have a meeting at 2 PM with the design team.",
  "sessionKey": "agent:main:clawvoice"
}
```

#### Check Status

```http
GET /api/voice/status
Authorization: Bearer <device-token>
```

**Response:**
```json
{
  "status": "connected",
  "device": {
    "id": "abc123...",
    "name": "Luis's Pixel 8"
  }
}
```

## Android Implementation

### Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
```

### OpenClawClient

```kotlin
class OpenClawClient(
    private val baseUrl: String,
    private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)  // Agent may take time
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    
    // Get stored device token
    private fun getDeviceToken(): String? {
        return SecureStorage.getDeviceToken(context)
    }
    
    // Request pairing
    suspend fun requestPairing(
        deviceId: String,
        publicKey: String,
        deviceName: String
    ): PairingResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("deviceId", deviceId)
            put("publicKey", publicKey)
            put("deviceName", deviceName)
        }.toString()
        
        val request = Request.Builder()
            .url("$baseUrl/api/voice/pair")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = client.newCall(request).execute()
        json.decodeFromString(response.body!!.string())
    }
    
    // Check pairing status
    suspend fun checkPairing(requestId: String): PairingResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/voice/pair/$requestId")
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        json.decodeFromString(response.body!!.string())
    }
    
    // Send message to agent
    suspend fun sendMessage(text: String): MessageResponse = withContext(Dispatchers.IO) {
        val token = getDeviceToken() 
            ?: throw IllegalStateException("Not paired")
        
        val body = buildJsonObject {
            put("text", text)
        }.toString()
        
        val request = Request.Builder()
            .url("$baseUrl/api/voice/message")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw IOException("Request failed: ${response.code}")
        }
        
        json.decodeFromString(response.body!!.string())
    }
}

@Serializable
data class PairingResponse(
    val status: String,
    val requestId: String? = null,
    val deviceToken: String? = null,
    val message: String? = null
)

@Serializable
data class MessageResponse(
    val reply: String,
    val sessionKey: String? = null
)
```

### Device Identity

```kotlin
class DeviceIdentity(context: Context) {
    private val keyPair: KeyPair
    
    init {
        keyPair = loadOrGenerateKeyPair(context)
    }
    
    val deviceId: String
        get() = sha256Hex(keyPair.public.encoded)
    
    val publicKeyBase64: String
        get() = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
    
    private fun loadOrGenerateKeyPair(context: Context): KeyPair {
        // Check secure storage first
        SecureStorage.getKeyPair(context)?.let { return it }
        
        // Generate new Ed25519 keypair
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        SecureStorage.saveKeyPair(context, keyPair)
        return keyPair
    }
    
    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
```

### VoiceManager

```kotlin
class VoiceManager(
    private val context: Context,
    private val client: OpenClawClient
) {
    private val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private val tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.getDefault()
        }
    }
    
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    
    fun startListening() {
        _state.value = VoiceState.Listening
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, 
                     RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                )
                matches?.firstOrNull()?.let { text ->
                    processUserInput(text)
                }
            }
            
            override fun onError(error: Int) {
                _state.value = VoiceState.Error("Speech recognition failed")
            }
            
            // ... other callbacks
        })
        
        speechRecognizer.startListening(intent)
    }
    
    private fun processUserInput(text: String) {
        _state.value = VoiceState.Processing(text)
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val response = client.sendMessage(text)
                speak(response.reply)
            } catch (e: Exception) {
                _state.value = VoiceState.Error(e.message ?: "Failed")
            }
        }
    }
    
    private fun speak(text: String) {
        _state.value = VoiceState.Speaking(text)
        
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(utteranceId: String) {
                _state.value = VoiceState.Idle
            }
            override fun onError(utteranceId: String) {
                _state.value = VoiceState.Idle
            }
            override fun onStart(utteranceId: String) {}
        })
        
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "response")
    }
    
    fun stop() {
        speechRecognizer.cancel()
        tts.stop()
        _state.value = VoiceState.Idle
    }
}

sealed class VoiceState {
    object Idle : VoiceState()
    object Listening : VoiceState()
    data class Processing(val text: String) : VoiceState()
    data class Speaking(val text: String) : VoiceState()
    data class Error(val message: String) : VoiceState()
}
```

## Error Handling

| HTTP Code | Meaning | Action |
|-----------|---------|--------|
| 401 | Invalid/expired token | Re-pair device |
| 400 | Bad request | Check request format |
| 500 | Server error | Retry with backoff |
| Timeout | Network issue | Show offline state |

```kotlin
suspend fun sendMessageSafe(text: String): Result<String> {
    return try {
        val response = client.sendMessage(text)
        Result.success(response.reply)
    } catch (e: IOException) {
        Result.failure(e)
    }
}
```

## Offline Handling

Since we're not using WebSocket, the app can work in "fire and forget" mode:

1. **Queue messages** when offline
2. **Retry on reconnect**
3. **Show pending indicator**

```kotlin
class MessageQueue(context: Context) {
    private val queue = mutableListOf<String>()
    
    fun enqueue(text: String) {
        queue.add(text)
        trySend()
    }
    
    private fun trySend() {
        if (!isOnline()) return
        
        while (queue.isNotEmpty()) {
            val text = queue.first()
            try {
                client.sendMessage(text)
                queue.removeFirst()
            } catch (e: Exception) {
                break  // Will retry later
            }
        }
    }
}
```

## Security Notes

1. **Device token** — Store in Android Keystore via EncryptedSharedPreferences
2. **Gateway URL** — Store securely, allow user to change
3. **TLS required** — Always use HTTPS
4. **No sensitive data in logs** — Don't log tokens or messages

---

## Related

- [OpenClaw Channel Setup](channel.md) — Server-side hook configuration
- [Android App Architecture](../clawd-android-app.md) — Full app design doc
