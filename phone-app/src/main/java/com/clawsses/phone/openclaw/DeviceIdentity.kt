package com.clawsses.phone.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Manages Ed25519 device identity for OpenClaw Gateway authentication.
 *
 * Uses BouncyCastle's Ed25519 implementation directly since Android's
 * built-in crypto providers don't reliably support Ed25519 across devices.
 * Keys are persisted as base64 in SharedPreferences.
 */
class DeviceIdentity(context: Context) {

    companion object {
        private const val TAG = "DeviceIdentity"
        private const val PREFS_NAME = "clawsses_device_identity"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_PRIVATE_KEY = "ed25519_private_key_raw"
        private const val KEY_PUBLIC_KEY = "ed25519_public_key_raw"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private lateinit var privateKeyParams: Ed25519PrivateKeyParameters
    private lateinit var publicKeyParams: Ed25519PublicKeyParameters

    /** SHA-256 hex fingerprint of the raw 32-byte public key */
    val deviceId: String

    /** Raw 32-byte public key, base64url-encoded (no padding) */
    val publicKeyBase64Url: String

    /** Persisted device token from a successful pairing (null if not yet paired) */
    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()
        }

    init {
        val storedPriv = prefs.getString(KEY_PRIVATE_KEY, null)
        val storedPub = prefs.getString(KEY_PUBLIC_KEY, null)

        if (storedPriv != null && storedPub != null) {
            try {
                val privBytes = Base64.getDecoder().decode(storedPriv)
                val pubBytes = Base64.getDecoder().decode(storedPub)
                if (privBytes.size == 32 && pubBytes.size == 32) {
                    privateKeyParams = Ed25519PrivateKeyParameters(privBytes, 0)
                    publicKeyParams = Ed25519PublicKeyParameters(pubBytes, 0)
                    Log.d(TAG, "Restored Ed25519 keypair from SharedPreferences")
                } else {
                    Log.w(TAG, "Stored key has wrong size (priv=${privBytes.size}, pub=${pubBytes.size}), regenerating")
                    throw IllegalStateException("Wrong key size")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore keypair, generating new one", e)
                generateAndPersistKeyPair()
            }
        } else {
            generateAndPersistKeyPair()
        }

        val rawPublicKey = publicKeyParams.encoded // 32 bytes

        deviceId = MessageDigest.getInstance("SHA-256")
            .digest(rawPublicKey)
            .joinToString("") { "%02x".format(it) }

        publicKeyBase64Url = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawPublicKey)

        Log.d(TAG, "Device ID: ${deviceId.take(16)}...")
        Log.d(TAG, "Public key (base64url): $publicKeyBase64Url (${rawPublicKey.size} bytes)")
    }

    /**
     * Build and sign the device auth payload per OpenClaw protocol v2.
     * Payload format: "v2|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce"
     */
    fun signAuthPayload(
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String,
        nonce: String
    ): String {
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
        Log.d(TAG, "Auth payload to sign: ${payload.take(120)}...")
        val sig = sign(payload.toByteArray(Charsets.UTF_8))
        Log.d(TAG, "Signature: ${sig.take(20)}... (${Base64.getUrlDecoder().decode(sig + "==").size} bytes)")
        return sig
    }

    private fun sign(data: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, privateKeyParams)
        signer.update(data, 0, data.size)
        val sig = signer.generateSignature()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
    }

    private fun generateAndPersistKeyPair() {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        privateKeyParams = keyPair.private as Ed25519PrivateKeyParameters
        publicKeyParams = keyPair.public as Ed25519PublicKeyParameters

        // Persist raw 32-byte keys
        prefs.edit()
            .putString(KEY_PRIVATE_KEY, Base64.getEncoder().encodeToString(privateKeyParams.encoded))
            .putString(KEY_PUBLIC_KEY, Base64.getEncoder().encodeToString(publicKeyParams.encoded))
            .remove(KEY_DEVICE_TOKEN) // Clear stale token since deviceId changes
            .apply()

        Log.i(TAG, "Generated and persisted new Ed25519 keypair")
    }
}
