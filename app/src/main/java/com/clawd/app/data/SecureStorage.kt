package com.clawd.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecureStorage {
    private const val PREFS_NAME = "clawd_secure"
    private const val KEY_GATEWAY_URL = "gateway_url"
    private const val KEY_DEVICE_TOKEN = "device_token"
    private const val KEY_NTFY_TOPIC = "ntfy_topic"
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

    fun saveNtfyTopic(context: Context, topic: String) {
        getPrefs(context).edit().putString(KEY_NTFY_TOPIC, topic).apply()
    }

    fun getNtfyTopic(context: Context): String? {
        return getPrefs(context).getString(KEY_NTFY_TOPIC, null)
    }

    fun saveDeviceToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_DEVICE_TOKEN, token).apply()
    }

    fun getDeviceToken(context: Context): String? {
        return getPrefs(context).getString(KEY_DEVICE_TOKEN, null)
    }

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
