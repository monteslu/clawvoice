package com.clawd.app.data

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

class DeviceIdentity private constructor(
    private val privateKey: Ed25519PrivateKeyParameters,
    private val publicKeyParams: Ed25519PublicKeyParameters
) {
    val deviceId: String = computeDeviceId(publicKeyParams.encoded)
    val publicKeyBase64: String = Base64.encodeToString(
        publicKeyParams.encoded,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )

    fun signChallenge(
        nonce: String,
        signedAtMs: Long,
        clientId: String = "openclaw-android",
        clientMode: String = "webchat",
        role: String = "operator",
        scopes: List<String> = listOf("operator.read", "operator.write"),
        token: String = ""
    ): SignedChallenge {
        // Build the v2 payload exactly as Gateway expects
        val payload = listOf(
            "v2",
            deviceId,
            clientId,
            clientMode,
            role,
            scopes.joinToString(","),
            signedAtMs.toString(),
            token,
            nonce
        ).joinToString("|")

        // Sign it
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        signer.update(payloadBytes, 0, payloadBytes.size)
        val signature = signer.generateSignature()

        // Base64URL encode (not standard base64!)
        val signatureBase64Url = Base64.encodeToString(
            signature,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        return SignedChallenge(
            signature = signatureBase64Url,
            signedAt = signedAtMs,
            nonce = nonce
        )
    }

    companion object {
        @Volatile
        private var instance: DeviceIdentity? = null

        fun loadOrCreate(context: Context): DeviceIdentity {
            return instance ?: synchronized(this) {
                instance ?: createInstance(context).also { instance = it }
            }
        }

        private fun createInstance(context: Context): DeviceIdentity {
            val storedPrivate = SecureStorage.getPrivateKey(context)
            val storedPublic = SecureStorage.getPublicKey(context)

            if (storedPrivate != null && storedPublic != null && storedPrivate.size == 32 && storedPublic.size == 32) {
                // Load existing Ed25519 keys
                val privateKey = Ed25519PrivateKeyParameters(storedPrivate, 0)
                val publicKey = Ed25519PublicKeyParameters(storedPublic, 0)
                return DeviceIdentity(privateKey, publicKey)
            }

            // Generate new Ed25519 keypair
            val keyPairGenerator = Ed25519KeyPairGenerator()
            keyPairGenerator.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val keyPair = keyPairGenerator.generateKeyPair()

            val privateKey = keyPair.private as Ed25519PrivateKeyParameters
            val publicKey = keyPair.public as Ed25519PublicKeyParameters

            SecureStorage.saveKeyPair(
                context,
                privateKey.encoded,
                publicKey.encoded
            )

            return DeviceIdentity(privateKey, publicKey)
        }

        private fun computeDeviceId(publicKey: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(publicKey).joinToString("") { "%02x".format(it) }
        }
    }
}

data class SignedChallenge(
    val signature: String,
    val signedAt: Long,
    val nonce: String
)
