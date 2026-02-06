package com.clawd.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SecureStorage {
    private const val PREFS_NAME = "clawd_secure"
    private const val KEY_AGENTS = "agents"
    private const val KEY_ACTIVE_AGENT_ID = "active_agent_id"
    private const val KEY_PRIVATE_KEY = "private_key"
    private const val KEY_PUBLIC_KEY = "public_key"

    private val json = Json { ignoreUnknownKeys = true }

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

    // --- Agent management ---

    fun getAgents(context: Context): List<Agent> {
        val raw = getPrefs(context).getString(KEY_AGENTS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<Agent>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAgents(context: Context, agents: List<Agent>) {
        getPrefs(context).edit()
            .putString(KEY_AGENTS, json.encodeToString(agents))
            .apply()
    }

    fun addAgent(context: Context, agent: Agent) {
        val agents = getAgents(context).toMutableList()
        agents.add(agent)
        saveAgents(context, agents)
        // If this is the first agent, make it active
        if (agents.size == 1) {
            setActiveAgentId(context, agent.id)
        }
    }

    fun updateAgent(context: Context, agent: Agent) {
        val agents = getAgents(context).toMutableList()
        val index = agents.indexOfFirst { it.id == agent.id }
        if (index >= 0) {
            agents[index] = agent
            saveAgents(context, agents)
        }
    }

    fun removeAgent(context: Context, agentId: String) {
        val agents = getAgents(context).filter { it.id != agentId }
        saveAgents(context, agents)
        if (getActiveAgentId(context) == agentId) {
            setActiveAgentId(context, agents.firstOrNull()?.id)
        }
    }

    fun getActiveAgentId(context: Context): String? {
        return getPrefs(context).getString(KEY_ACTIVE_AGENT_ID, null)
    }

    fun setActiveAgentId(context: Context, agentId: String?) {
        getPrefs(context).edit()
            .putString(KEY_ACTIVE_AGENT_ID, agentId)
            .apply()
    }

    fun getActiveAgent(context: Context): Agent? {
        val activeId = getActiveAgentId(context) ?: return null
        return getAgents(context).find { it.id == activeId }
    }

    // --- Legacy helpers for migration ---

    fun getGatewayUrl(context: Context): String? {
        return getActiveAgent(context)?.gatewayUrl
    }

    fun getDeviceToken(context: Context): String? {
        return getActiveAgent(context)?.deviceToken
    }

    fun saveDeviceToken(context: Context, token: String) {
        val agent = getActiveAgent(context) ?: return
        updateAgent(context, agent.copy(deviceToken = token))
    }

    fun getNtfyTopic(context: Context): String? {
        return getActiveAgent(context)?.ntfyTopic
    }

    // --- Keypair storage (shared across all agents, one device identity) ---

    fun saveKeyPair(context: Context, privateKey: ByteArray, publicKey: ByteArray) {
        getPrefs(context).edit()
            .putString(KEY_PRIVATE_KEY, android.util.Base64.encodeToString(privateKey, android.util.Base64.NO_WRAP))
            .putString(KEY_PUBLIC_KEY, android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP))
            .apply()
    }

    fun getPrivateKey(context: Context): ByteArray? {
        val encoded = getPrefs(context).getString(KEY_PRIVATE_KEY, null) ?: return null
        return android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
    }

    fun getPublicKey(context: Context): ByteArray? {
        val encoded = getPrefs(context).getString(KEY_PUBLIC_KEY, null) ?: return null
        return android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
    }

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
