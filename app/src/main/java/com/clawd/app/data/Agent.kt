package com.clawd.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Agent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val gatewayUrl: String,
    val deviceToken: String? = null,
    val ntfyTopic: String? = null,
    val voiceId: String? = null
)
