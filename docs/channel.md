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
│  │                                                         │ │
│  │   Already handles:                                      │ │
│  │   • Device pairing + token issuance                     │ │
│  │   • Session management                                  │ │
│  │   • Agent invocation                                    │ │
│  │   • Message history                                     │ │
│  └────────────────────────────────────────────────────────┘ │
│                              │                               │
└──────────────────────────────┼───────────────────────────────┘
                               │
                    WebSocket (wss://)
                               │
                               ▼
                    ┌─────────────────────┐
                    │   Android Device    │
                    │  ┌───────────────┐  │
                    │  │  ClawVoice    │  │
                    │  │  • STT local  │  │
                    │  │  • TTS local  │  │
                    │  │  • Text → WS  │  │
                    │  └───────────────┘  │
                    └─────────────────────┘
```

## Why WebSocket?

| Feature | WebSocket (current) | REST (alternative) |
|---------|--------------------|--------------------|
| Streaming responses | ✅ Real-time deltas | ❌ Wait for complete |
| Connection state | ✅ Know immediately | ❌ Poll for status |
| Keepalive | ✅ Built-in ping | ❌ Manual heartbeat |
| Auth | ✅ Existing protocol | ❌ Custom implementation |
| Code reuse | ✅ Same as iOS/macOS | ❌ New endpoint |

## Setup

### 1. Start the Gateway

```bash
openclaw gateway --port 18789 --verbose
```

For remote access via hsync:
```bash
hsync --local 18789 --name my-gateway
# → wss://my-gateway.shiv.to
```

### 2. Connect from Android

In ClawVoice:
1. Enter gateway URL (e.g., `https://my-gateway.shiv.to`)
2. App converts to WebSocket and connects
3. First connect triggers pairing request

### 3. Approve Pairing

On the gateway machine:
```bash
openclaw nodes pending
openclaw nodes approve <requestId>
```

Or via the web UI at `http://localhost:18790`.

### 4. Done

ClawVoice now:
- Sends user text via `chat.send`
- Receives streamed responses via `chat` events
- Filters protocol markers (HEARTBEAT_OK, NO_REPLY, etc.)
- Speaks filtered responses via local TTS

## Protocol Reference

ClawVoice uses [Gateway Protocol v3](/docs/gateway/protocol.md):

### Authentication Flow

```
Gateway → App:  { type: "event", event: "connect.challenge", payload: { nonce, ts } }
App → Gateway:  { type: "req", method: "connect", params: { auth, device, ... } }
Gateway → App:  { type: "res", ok: true, payload: { auth: { deviceToken } } }
```

### Send Message

```json
{
  "type": "req",
  "id": "uuid",
  "method": "chat.send",
  "params": {
    "sessionKey": "main",
    "message": "What time is it?",
    "idempotencyKey": "uuid"
  }
}
```

### Receive Response (streaming)

```json
{
  "type": "event",
  "event": "chat",
  "payload": {
    "sessionKey": "main",
    "state": "delta",
    "message": { "content": [{ "type": "text", "text": "It's 3:45" }] }
  }
}
```

```json
{
  "type": "event",
  "event": "chat",
  "payload": {
    "sessionKey": "main",
    "state": "final",
    "message": { "content": [{ "type": "text", "text": "It's 3:45 PM." }] }
  }
}
```

## Message Filtering

ClawVoice filters these from display/TTS:

| Pattern | Purpose |
|---------|---------|
| `HEARTBEAT_OK` | Internal heartbeat ack |
| `NO_REPLY` | Silent response marker |
| `MEDIA:...` | Audio file path (for server TTS) |
| `[[reply_to_current]]` | Reply tag |
| `[[reply_to:...]]` | Reply tag with ID |

## Voice Response Tuning

Add to your agent's `SOUL.md`:

```markdown
## Voice Responses

When responding to voice clients:
- Keep responses concise (1-3 sentences)
- Avoid markdown, code blocks, tables
- Use natural conversational language
- Spell out abbreviations (NASA → "NASA" not "N.A.S.A.")
```

## Troubleshooting

### "Pairing required" but already approved
- Device token may have been revoked
- Clear app data and re-pair

### No responses
- Check gateway logs: `openclaw gateway --verbose`
- Verify WebSocket is reachable: `wscat -c wss://your-gateway/`

### Responses cut off
- Check `state: "final"` event is received
- May be network timeout

---

## Related

- [Android Implementation](comms.md) — Kotlin code details
- [Gateway Protocol](/docs/gateway/protocol.md) — Full protocol spec
- [Device Pairing](/docs/gateway/pairing.md) — Pairing workflow
