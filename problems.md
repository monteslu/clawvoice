# Clawvoice Android App - Current Issues

## Status

Connection and authentication working. Messages sending and receiving. However, some responses come back with empty text.

## Issue: Server Responses with Empty Text

### Symptom

When sending certain messages (e.g., "give me the latest news"), the server responds with:
- Empty text content (`text=""` or `text=null`)
- Only `mediaUrls` pointing to TTS audio files

The app cannot display or speak these responses because there's no text content.

### Example from Logs

**Request sent:**
```json
{
  "type": "req",
  "method": "chat.send",
  "params": {
    "sessionKey": "main",
    "message": "give me the latest news",
    "idempotencyKey": "4d3208b6-13f9-4ef3-aa6f-435b05217687"
  }
}
```

**Response received (delta events):**
```json
{
  "type": "event",
  "event": "chat",
  "payload": {
    "runId": "4d3208b6-13f9-4ef3-aa6f-435b05217687",
    "state": "delta",
    "message": {
      "role": "assistant",
      "content": [{"type": "text", "text": ""}]
    }
  }
}
```

**Agent event shows mediaUrls but no text:**
```json
{
  "event": "agent",
  "payload": {
    "stream": "assistant",
    "data": {
      "text": "",
      "delta": "",
      "mediaUrls": ["/tmp/tts-PTVnnv/voice-1770120832948.mp3"]
    }
  }
}
```

**Final event has null text:**
```
handleChatEvent: state=final, runId=4d3208b6-..., text=null
```

### What Works

Other messages DO return text properly:
```
text=I'm not? All my replies have been plain text — I haven't used the `tts` tool at all this conversation.
```

```
text=Nope, still here! Just dumped the filtering instructions. Did you need something else?
```

### Questions for Gateway

1. **Why are some responses text-only and others audio-only?**
   - Is the agent using a `tts` tool that replaces text with audio?
   - Should text always be included alongside audio?

2. **Expected behavior:**
   - Should the gateway always send text content in `message.content[].text`?
   - If audio is generated, should text still be preserved?

3. **Possible solutions:**
   - Gateway always includes text even when generating TTS audio
   - Or: App fetches and plays the mediaUrls audio files (requires accessible URLs)

### App-Side Implementation

The app currently:
- Filters out `HEARTBEAT_OK`, `NO_REPLY`, `MEDIA:` protocol markers
- Only extracts text from content blocks with `"type": "text"`
- Ignores empty text deltas to prevent overwriting good content
- Uses client-side TTS (Android TextToSpeech) to speak responses

The app CANNOT play server-generated audio because:
- `mediaUrls` point to local server paths (e.g., `/tmp/tts-xxx/voice.mp3`)
- These are not accessible URLs from the Android device

### Requested Fix

Please ensure the gateway always includes the text content in responses, even when generating server-side TTS audio. The text is needed for:
1. Displaying in the chat UI
2. Client-side TTS fallback when server audio isn't accessible
