package com.clawsses.phone.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.NamedParameterSpec
import java.util.Base64

/**
 * Manages Ed25519 device identity for OpenClaw Gateway authentication.
 *
 * Generates an Ed25519 keypair in software (not AndroidKeyStore, since many
 * devices don't support Ed25519 in hardware). Keys are persisted as base64 in
 * SharedPreferences and the raw 32-byte public key is used for device identity.
 *
 * On each connect, signs the structured auth payload with the private key
 * to prove device identity.
 */
class DeviceIdentity(context: Context) {

    companion object {
        private const val TAG = "DeviceIdentity"
        private const val PREFS_NAME = "clawsses_device_identity"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_PRIVATE_KEY = "ed25519_private_key"
        private const val KEY_PUBLIC_KEY = "ed25519_public_key"
        // Ed25519 SPKI prefix: 30 2a 30 05 06 03 2b 65 70 03 21 00
        private val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var publicKey: PublicKey
    private var privateKey: PrivateKey

    /** SHA-256 hex fingerprint of the raw public key bytes */
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
            // Restore existing keypair from SharedPreferences
            try {
                val privBytes = Base64.getDecoder().decode(storedPriv)
                val pubBytes = Base64.getDecoder().decode(storedPub)
                val kf = KeyFactory.getInstance("Ed25519")
                privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                publicKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))

                // Verify it's actually Ed25519 (44-byte SPKI = 12 prefix + 32 key)
                if (publicKey.encoded.size == 44) {
                    Log.d(TAG, "Restored Ed25519 keypair from SharedPreferences")
                } else {
                    Log.w(TAG, "Stored key has wrong size (${publicKey.encoded.size}), regenerating")
                    throw IllegalStateException("Wrong key type stored")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore keypair, generating new one", e)
                val kp = generateEd25519KeyPair()
                privateKey = kp.first
                publicKey = kp.second
                persistKeyPair()
            }
        } else {
            // Generate new keypair
            val kp = generateEd25519KeyPair()
            privateKey = kp.first
            publicKey = kp.second
            persistKeyPair()
        }

        // Extract raw 32-byte Ed25519 public key from SPKI encoding
        val spki = publicKey.encoded
        val rawPublicKey = if (spki.size == 44 &&
            spki.take(ED25519_SPKI_PREFIX.size) == ED25519_SPKI_PREFIX.toList()) {
            spki.copyOfRange(ED25519_SPKI_PREFIX.size, spki.size)
        } else {
            Log.e(TAG, "Unexpected SPKI size: ${spki.size}, hex: ${spki.joinToString("") { "%02x".format(it) }}")
            spki.takeLast(32).toByteArray()
        }

        deviceId = MessageDigest.getInstance("SHA-256")
            .digest(rawPublicKey)
            .joinToString("") { "%02x".format(it) }

        publicKeyBase64Url = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(rawPublicKey)

        Log.d(TAG, "Device ID: ${deviceId.take(16)}...")
        Log.d(TAG, "Public key algo: ${publicKey.algorithm}, SPKI size: ${spki.size}, raw key size: ${rawPublicKey.size}")
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
        Log.d(TAG, "Signature size: ${Base64.getUrlDecoder().decode(sig + "==").size} bytes")
        return sig
    }

    private fun sign(data: ByteArray): String {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(data)
        val sig = signer.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig)
    }

    private fun generateEd25519KeyPair(): Pair<PrivateKey, PublicKey> {
        // Try Ed25519 first, then EdDSA with named parameter
        val kpg = try {
            KeyPairGenerator.getInstance("Ed25519")
        } catch (e: Exception) {
            Log.d(TAG, "Ed25519 not available, trying EdDSA with NamedParameterSpec")
            KeyPairGenerator.getInstance("EdDSA").also {
                it.initialize(NamedParameterSpec("Ed25519"))
            }
        }
        val keyPair = kpg.generateKeyPair()
        Log.i(TAG, "Generated new Ed25519 keypair (algo=${keyPair.private.algorithm}, " +
                "pub SPKI size=${keyPair.public.encoded.size})")
        return Pair(keyPair.private, keyPair.public)
    }

    private fun persistKeyPair() {
        val privB64 = Base64.getEncoder().encodeToString(privateKey.encoded)
        val pubB64 = Base64.getEncoder().encodeToString(publicKey.encoded)
        prefs.edit()
            .putString(KEY_PRIVATE_KEY, privB64)
            .putString(KEY_PUBLIC_KEY, pubB64)
            .apply()
        // Clear any stale device token since deviceId will change
        prefs.edit().remove(KEY_DEVICE_TOKEN).apply()
        Log.d(TAG, "Persisted Ed25519 keypair to SharedPreferences")
    }
}
