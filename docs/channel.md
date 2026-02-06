# ClawVoice - OpenClaw Integration

> How ClawVoice connects to your OpenClaw gateway.

See also: [Android Implementation](comms.md)

---

## Overview

ClawVoice connects directly to the **existing OpenClaw Gateway WebSocket** — no custom hooks or endpoints needed. It uses the same protocol as the iOS app, macOS app, and CLI.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    OpenClaw Gateway                          │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │            Existing Gateway WebSocket (:18789)          │ │
│  │                                                         │ │
│  │   Protocol v3:                                          │ │
│  │   • connect.challenge / connect (auth)                  │ │
│  │   • chat.send / chat.history                            │ │
│  │   • chat events (delta/final streaming)                 │ │
│  │   • ping keepalive                                      │ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
                               │
                    WebSocket (wss://)
                               │
                    ┌─────────────────────┐
                    │   Android Device    │
                    │  • STT local        │
                    │  • TTS local        │
                    │  • Text → WS        │
                    └─────────────────────┘
```

## Setup

### 1. Start the Gateway

```bash
openclaw gateway --port 18789
```

For remote access via hsync:
```bash
hsync --local 18789 --name my-gateway
# → wss://my-gateway.shiv.to
```

### 2. Connect from Android

Enter gateway URL (e.g., `https://my-gateway.shiv.to`). First connect triggers pairing.

### 3. Approve Pairing

```bash
openclaw nodes approve <requestId>
```

The `requestId` is a short code (6-8 chars) shown in the app.

---

## Client Identity

### Valid Client IDs

Use one of the pre-defined IDs recognized by the gateway:

| ID | Description |
|----|-------------|
| `openclaw-android` | Android app (ClawVoice) |
| `openclaw-ios` | iOS app |
| `openclaw-macos` | macOS app |
| `webchat-ui` | Browser webchat |
| `openclaw-control-ui` | Control UI |
| `cli` | Command line |

**ClawVoice should use:** `openclaw-android`

### Valid Modes

| Mode | Description | Origin Check |
|------|-------------|--------------|
| `webchat` | Shares sessions with web UI | Yes (browsers) |
| `ui` | Native UI client | No |
| `cli` | Command line | No |
| `node` | Device with capabilities | No |
| `backend` | Backend service | No |

**ClawVoice should use:** `webchat` for session sharing, but see Origin section below.

---

## Origin Checking (Important!)

The gateway enforces **origin checking** for `webchat` mode to prevent browser CSRF attacks.

### The Problem

- Browsers can't spoof Origin headers (security)
- Native apps CAN set any Origin header
- If native app sends no Origin, gateway rejects with "origin not allowed"

### Solutions

**Option A: Send Origin header (recommended for now)**

```kotlin
val request = Request.Builder()
    .url(wsUrl)
    .header("Origin", gatewayUrl)  // e.g., "https://my-gateway.shiv.to"
    .build()
```

User must add their gateway URL to `gateway.controlUi.allowedOrigins` in config.

**Option B: Use `ui` mode instead of `webchat`**

```kotlin
put("mode", "ui")  // No origin check
```

Trade-off: May not share sessions with web UI.

**Option C: Gateway fix (pending)**

Native app client IDs (`openclaw-android`, `openclaw-ios`, `openclaw-macos`) should skip origin checking since they're not vulnerable to CSRF.

---

## Pairing Flow

### Request ID

The `requestId` for pairing can be:
- **Gateway-generated**: Full UUID (36 chars) — hard to type
- **Client-generated**: Short ID (6-8 chars) — recommended

Client can send a short ID in the connect params:

```kotlin
putJsonObject("device") {
    put("id", deviceIdentity.deviceId)
    put("publicKey", deviceIdentity.publicKeyBase64)
    put("requestId", generateShortId())  // e.g., "a1b2c3"
    // ... signature fields
}
```

If `device.requestId` is provided, gateway uses it. Otherwise generates a UUID.

### Flow Diagram

```
App                                    Gateway
 │                                        │
 │──── WebSocket connect ────────────────▶│
 │                                        │
 │◀─── connect.challenge {nonce, ts} ─────│
 │                                        │
 │──── connect {device, auth} ───────────▶│
 │     device.requestId = "a1b2c3"        │
 │                                        │
 │◀─── error: pairing_required ───────────│
 │     (shows requestId "a1b2c3")         │
 │                                        │
 │         [User runs: openclaw nodes approve a1b2c3]
 │                                        │
 │◀─── event: device.paired ──────────────│
 │     {deviceToken: "..."}               │
 │                                        │
 │     [Save token, reconnect]            │
```

---

## Message Filtering

Filter these from display/TTS:

| Pattern | Purpose |
|---------|---------|
| `HEARTBEAT_OK` | Heartbeat ack |
| `NO_REPLY` | Silent response |
| `MEDIA:...` | Audio file path |
| `[[reply_to_current]]` | Reply tag |
| `[[reply_to:...]]` | Reply tag with ID |

---

## Configuration Reference

### Gateway Config (openclaw.json)

```json
{
  "gateway": {
    "controlUi": {
      "allowedOrigins": [
        "https://your-gateway.shiv.to"
      ]
    }
  }
}
```

Required if ClawVoice sends Origin header and uses `webchat` mode.

---

## Related

- [Android Implementation](comms.md) — Kotlin code details
- [Gateway Protocol](https://docs.openclaw.ai/gateway/protocol) — Full protocol spec
