# ClawVoice Channel - OpenClaw Integration

> How to add ClawVoice support to your OpenClaw gateway.

See also: [Android Communication Protocol](comms.md)

---

## Overview

ClawVoice connects to OpenClaw via a **gateway hook** — no core modifications needed. The hook adds a `/api/voice` endpoint that handles text-only communication (STT/TTS happens on the Android device).

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    OpenClaw Gateway                          │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                 Existing Components                     │ │
│  │   Sessions │ Agents │ Tools │ Channels │ WebSocket     │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │              hooks/clawvoice/                           │ │
│  │   ┌──────────────┐    ┌──────────────────────────────┐ │ │
│  │   │   HOOK.md    │    │        handler.js            │ │ │
│  │   │              │    │                              │ │ │
│  │   │ Trigger:     │    │  POST /api/voice/message     │ │ │
│  │   │ gateway:     │    │  POST /api/voice/pair        │ │ │
│  │   │   startup    │    │  GET  /api/voice/status      │ │ │
│  │   └──────────────┘    └──────────────────────────────┘ │ │
│  └────────────────────────────────────────────────────────┘ │
│                              │                               │
└──────────────────────────────┼───────────────────────────────┘
                               │
                    HTTPS (text only)
                               │
                               ▼
                    ┌─────────────────────┐
                    │   Android Device    │
                    │  ┌───────────────┐  │
                    │  │  ClawVoice    │  │
                    │  │  - STT local  │  │
                    │  │  - TTS local  │  │
                    │  └───────────────┘  │
                    └─────────────────────┘
```

## Installation

### 1. Create the Hook Directory

```bash
mkdir -p ~/.openclaw/workspace/hooks/clawvoice
```

### 2. Create HOOK.md

```markdown
# ClawVoice Hook

Adds voice client API endpoint.

Trigger: gateway:startup
```

### 3. Create handler.js

```javascript
// hooks/clawvoice/handler.js

module.exports = async function({ app, gateway, config }) {
  const crypto = require('crypto');
  
  // Store for pending device pairings
  const pendingPairings = new Map();
  // Store for approved devices (in production, persist this)
  const approvedDevices = new Map();
  
  // Middleware: authenticate device
  const authDevice = (req, res, next) => {
    const token = req.headers.authorization?.replace('Bearer ', '');
    if (!token) {
      return res.status(401).json({ error: 'Missing authorization' });
    }
    
    const device = approvedDevices.get(token);
    if (!device) {
      return res.status(401).json({ error: 'Invalid device token' });
    }
    
    req.device = device;
    next();
  };
  
  // POST /api/voice/pair - Request device pairing
  app.post('/api/voice/pair', async (req, res) => {
    const { deviceId, publicKey, deviceName } = req.body;
    
    if (!deviceId || !publicKey) {
      return res.status(400).json({ error: 'deviceId and publicKey required' });
    }
    
    // Check if already approved
    for (const [token, device] of approvedDevices) {
      if (device.deviceId === deviceId) {
        return res.json({ 
          status: 'approved',
          deviceToken: token 
        });
      }
    }
    
    // Check if already pending
    for (const [requestId, pending] of pendingPairings) {
      if (pending.deviceId === deviceId) {
        return res.json({ 
          status: 'pending',
          requestId,
          message: 'Waiting for approval. Run: openclaw devices approve ' + requestId
        });
      }
    }
    
    // Create new pairing request
    const requestId = crypto.randomBytes(8).toString('hex');
    pendingPairings.set(requestId, {
      deviceId,
      publicKey,
      deviceName: deviceName || 'ClawVoice Android',
      requestedAt: Date.now()
    });
    
    console.log(`[clawvoice] New pairing request: ${requestId}`);
    console.log(`[clawvoice] Approve with: openclaw devices approve ${requestId}`);
    
    res.json({
      status: 'pending',
      requestId,
      message: 'Waiting for approval'
    });
  });
  
  // GET /api/voice/pair/:requestId - Check pairing status
  app.get('/api/voice/pair/:requestId', async (req, res) => {
    const { requestId } = req.params;
    
    // Check if still pending
    if (pendingPairings.has(requestId)) {
      return res.json({ status: 'pending' });
    }
    
    // Check if approved (look up by original request)
    for (const [token, device] of approvedDevices) {
      if (device.requestId === requestId) {
        return res.json({ 
          status: 'approved',
          deviceToken: token
        });
      }
    }
    
    res.status(404).json({ status: 'not_found' });
  });
  
  // POST /api/voice/message - Send message to agent
  app.post('/api/voice/message', authDevice, async (req, res) => {
    const { text, sessionKey = 'agent:main:clawvoice' } = req.body;
    
    if (!text) {
      return res.status(400).json({ error: 'text required' });
    }
    
    try {
      // Send to agent session
      const response = await gateway.chat({
        sessionKey,
        message: text,
        channel: 'clawvoice',
        metadata: {
          deviceId: req.device.deviceId,
          deviceName: req.device.deviceName
        }
      });
      
      res.json({
        reply: response.text,
        sessionKey
      });
    } catch (err) {
      console.error('[clawvoice] Chat error:', err);
      res.status(500).json({ error: 'Failed to get response' });
    }
  });
  
  // GET /api/voice/status - Check connection status
  app.get('/api/voice/status', authDevice, async (req, res) => {
    res.json({
      status: 'connected',
      device: {
        id: req.device.deviceId,
        name: req.device.deviceName
      }
    });
  });
  
  // Admin endpoint: approve pending device
  app.post('/api/voice/admin/approve', async (req, res) => {
    // In production, protect this with admin auth
    const { requestId } = req.body;
    
    const pending = pendingPairings.get(requestId);
    if (!pending) {
      return res.status(404).json({ error: 'Request not found' });
    }
    
    // Generate device token
    const deviceToken = crypto.randomBytes(32).toString('hex');
    
    approvedDevices.set(deviceToken, {
      ...pending,
      requestId,
      approvedAt: Date.now()
    });
    
    pendingPairings.delete(requestId);
    
    console.log(`[clawvoice] Device approved: ${pending.deviceName}`);
    
    res.json({ 
      status: 'approved',
      deviceToken
    });
  });
  
  // Admin endpoint: list pending pairings
  app.get('/api/voice/admin/pending', async (req, res) => {
    const pending = [];
    for (const [requestId, device] of pendingPairings) {
      pending.push({ requestId, ...device });
    }
    res.json({ pending });
  });
  
  console.log('[clawvoice] Voice API endpoints registered');
};
```

### 4. Restart Gateway

```bash
openclaw gateway restart
```

## API Endpoints

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/api/voice/pair` | POST | None | Request device pairing |
| `/api/voice/pair/:id` | GET | None | Check pairing status |
| `/api/voice/message` | POST | Device Token | Send message, get response |
| `/api/voice/status` | GET | Device Token | Check connection |
| `/api/voice/admin/pending` | GET | Admin | List pending pairings |
| `/api/voice/admin/approve` | POST | Admin | Approve a device |

## Message Format

### Send Message

```http
POST /api/voice/message
Authorization: Bearer <device-token>
Content-Type: application/json

{
  "text": "What's the weather like?",
  "sessionKey": "agent:main:clawvoice"
}
```

### Response

```json
{
  "reply": "I don't have access to weather data, but I can help you check online.",
  "sessionKey": "agent:main:clawvoice"
}
```

## Response Tuning for Voice

Since responses will be spoken aloud, consider adding to your agent's SOUL.md:

```markdown
## Voice Responses

When the channel is `clawvoice`:
- Keep responses concise (1-3 sentences ideal)
- Avoid markdown formatting, code blocks, or tables
- Use natural, conversational language
- Spell out abbreviations that might confuse TTS
- Avoid lists longer than 3 items
```

## Security Notes

1. **Device tokens** should be stored securely (in production, use a database)
2. **Admin endpoints** should be protected with additional authentication
3. **HTTPS required** — the hook runs on the existing gateway which should be behind hsync/TLS
4. Consider **rate limiting** the message endpoint

## Persistence

The example above stores devices in memory. For production:

```javascript
// Use a file or database
const DEVICES_FILE = path.join(process.env.HOME, '.openclaw/workspace/clawvoice-devices.json');

function loadDevices() {
  try {
    return JSON.parse(fs.readFileSync(DEVICES_FILE, 'utf8'));
  } catch {
    return {};
  }
}

function saveDevices(devices) {
  fs.writeFileSync(DEVICES_FILE, JSON.stringify(devices, null, 2));
}
```

---

## Related

- [Android Communication Protocol](comms.md) — How the Android app connects
- [OpenClaw Hooks Documentation](/app/docs/hooks.md) — General hook system
