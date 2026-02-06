package com.clawd.app.data

object ClawdConfig {
    // Gateway (set during setup from active agent)
    var gatewayUrl: String = ""

    // ntfy push notifications
    const val NTFY_SERVER = "wss://ntfy.sh"

    // Protocol
    const val PROTOCOL_VERSION = 3

    // Timeouts
    const val CONNECT_TIMEOUT_MS = 30_000L
    const val REQUEST_TIMEOUT_MS = 30_000L
    const val RECONNECT_BASE_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 60_000L
    const val PING_INTERVAL_MS = 10_000L
}
