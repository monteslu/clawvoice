package com.clawd.app.network.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class WsRequest(
    val type: String = "req",
    val id: String,
    val method: String,
    val params: JsonObject
)

@Serializable
data class WsResponse(
    val type: String,
    val id: String? = null,
    val ok: Boolean? = null,
    val payload: JsonObject? = null,
    val error: WsError? = null,
    val event: String? = null,
    val seq: Long? = null
)

@Serializable
data class WsError(
    val code: String,
    val message: String
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val ts: Long
)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class WaitingForPairing(val requestId: String? = null) : ConnectionState()
    object Ready : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class GatewayEvent {
    data class Chat(
        val sessionKey: String,
        val type: String,
        val delta: String?,
        val runId: String?,
        val content: String? = null
    ) : GatewayEvent()

    data class Paired(val deviceToken: String) : GatewayEvent()
    object PairingRequired : GatewayEvent()
}
